param(
    [switch]$SkipBuild,
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:deployDirectory = Join-Path $script:repositoryRoot 'deploy\docker'
$script:runId = "m8-notification-$([DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss'))"
$script:runDirectory = Join-Path $script:backendRoot ".run\$($script:runId)"
$script:gatewayPort = 18000
$script:paymentPort = 18105
$script:notificationPort = 18109
$script:smtpPort = 12525
$script:paymentTopic = "ecommerce-notification-payment-$($script:runId)"
$script:logisticsTopic = "ecommerce-notification-logistics-$($script:runId)"
$script:consumerGroup = "ecommerce-notification-verification-$($script:runId)"
$script:sourceEventId = [Guid]::NewGuid().ToString()
$script:duplicateOutboxId = [Guid]::NewGuid().ToString()
$script:sourceOutboxId = [Guid]::NewGuid().ToString()
$script:poisonEventId = [Guid]::NewGuid().ToString()
$script:customerId = 880001L
$script:adminId = 880002L
$script:processes = [ordered]@{}
$script:smtpProcess = $null
$script:smtpScriptPath = $null
$script:verification = [ordered]@{}
$javaHomeLauncher = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $null
}
else {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
}
$script:javaPath = if ($null -ne $javaHomeLauncher -and
    (Test-Path -LiteralPath $javaHomeLauncher)) {
    $javaHomeLauncher
}
else {
    (Get-Command java -ErrorAction Stop).Source
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null
$script:tracePath = Join-Path $script:runDirectory 'script-trace.log'

function Write-VerificationTrace {
    param([Parameter(Mandatory)][string]$Message)

    $line = "$([DateTimeOffset]::Now.ToString('o')) $Message"
    [IO.File]::AppendAllText(
        $script:tracePath,
        "$line`r`n",
        [Text.UTF8Encoding]::new($false))
}

function Import-LocalEnvironment {
    $envPath = Join-Path $script:deployDirectory '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw "Missing local environment file: $envPath"
    }
    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*#' -or $line -notmatch '=') {
            continue
        }
        $name, $value = $line -split '=', 2
        [Environment]::SetEnvironmentVariable($name.Trim(), $value, 'Process')
    }
}

function Require-Environment {
    param([Parameter(Mandatory)][string[]]$Names)

    $missing = @($Names | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
    })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
    }
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    param(
        [Parameter(Mandatory)][long]$UserId,
        [Parameter(Mandatory)][string[]]$Roles
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + 3600
        jti = [Guid]::NewGuid().ToString()
        roles = $Roles
    } | ConvertTo-Json -Compress
    $encodedHeader = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload))
    $unsigned = "$encodedHeader.$encodedPayload"
    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($env:IDENTITY_JWT_SECRET))
    try {
        $signature = ConvertTo-Base64Url (
            $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    }
    finally {
        $hmac.Dispose()
    }
    return "$unsigned.$signature"
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by PID $($listener[0].OwningProcess)."
    }
}

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][hashtable]$Environment,
        [string[]]$ApplicationArguments = @()
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing application artifact: $Jar"
    }
    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    try {
        $arguments = @(
            '-Xms128m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-jar',
            $Jar
        ) + $ApplicationArguments
        $process = Start-Process -FilePath $script:javaPath `
            -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            jar = $Jar
            port = $Port
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$ProcessName,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'no response'
    do {
        $managed = $script:processes[$ProcessName].process
        if ($managed.HasExited) {
            throw "$ProcessName exited before becoming ready. ExitCode=$($managed.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
            $lastState = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastState = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri. Last state: $lastState"
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][scriptblock]$Condition,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Invoke-NotificationSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i -e "MYSQL_PWD=$env:NOTIFICATION_DB_PASSWORD" `
        plainjournal-mysql mysql "-u$env:NOTIFICATION_DB_USER" $env:NOTIFICATION_DB_NAME -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Notification MySQL command failed.'
    }
    return @($output)
}

function Get-NotificationScalar {
    param([Parameter(Mandatory)][string]$Sql)
    $rows = @(Invoke-NotificationSql -Sql $Sql)
    if ($rows.Count -eq 0) {
        return $null
    }
    return [string]$rows[0]
}

function Invoke-PaymentSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" `
        plainjournal-mysql mysql "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Payment MySQL command failed.'
    }
    return @($output)
}

function Test-NotificationConsumerCaughtUp {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin consumerProgress `
            -n plainjournal-rocketmq-namesrv:9876 `
            -g $script:consumerGroup `
            -t $script:paymentTopic 2>&1)
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    return (($output -join "`n") -match 'Diff Total:\s+0(?:\s|$)')
}

function Stop-ExpectedPortListeners {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][string[]]$AllowedProcessNames,
        [Parameter(Mandatory)][string[]]$ExpectedCommandFragments
    )

    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)
    $listenerProcessIds = @($listeners |
        Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($listenerProcessId in $listenerProcessIds) {
        $process = Get-CimInstance Win32_Process `
            -Filter "ProcessId=$listenerProcessId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            Write-VerificationTrace (
                "listener disappeared before cleanup: description=$Description, " +
                "port=$Port, pid=$listenerProcessId")
            continue
        }
        $processNameAllowed = $AllowedProcessNames -contains [string]$process.Name
        $commandLine = [string]$process.CommandLine
        $commandMatches = $false
        foreach ($fragment in $ExpectedCommandFragments) {
            if ($commandLine.IndexOf(
                    $fragment,
                    [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $commandMatches = $true
                break
            }
        }
        if (-not $processNameAllowed -or -not $commandMatches) {
            Write-VerificationTrace (
                "listener command mismatch: description=$Description, port=$Port, " +
                "pid=$listenerProcessId, process=$($process.Name)")
            throw (
                "Refused to stop PID $listenerProcessId on port $Port; " +
                "the listener does not match the expected $Description process.")
        }
        Write-VerificationTrace (
            "stopping port listener: description=$Description, port=$Port, " +
            "pid=$listenerProcessId")
        Stop-Process -Id $listenerProcessId -Force -ErrorAction Stop
        Wait-Process -Id $listenerProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Headers,
        [object]$Body
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 10
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $json = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        body = $json
    }
}

function Start-SmtpCapture {
    $capturePath = Join-Path $script:runDirectory 'smtp-capture.txt'
    $scriptPath = Join-Path $PSScriptRoot 'notification-smtp-capture-server.ps1'
    $script:smtpScriptPath = $scriptPath
    $script:smtpProcess = Start-Process -FilePath 'pwsh' `
        -ArgumentList @(
            '-NoProfile',
            '-File',
            "`"$scriptPath`"",
            '-Port',
            $script:smtpPort,
            '-OutputPath',
            "`"$capturePath`""
        ) `
        -RedirectStandardOutput (Join-Path $script:runDirectory 'smtp.out.log') `
        -RedirectStandardError (Join-Path $script:runDirectory 'smtp.err.log') `
        -WindowStyle Hidden `
        -PassThru
    Wait-Until -Description 'SMTP capture listener' -TimeoutSeconds 15 -Condition {
        $null -ne (Get-NetTCPConnection -State Listen -LocalPort $script:smtpPort `
            -ErrorAction SilentlyContinue)
    }
    return $capturePath
}

function Stop-ManagedProcesses {
    Write-VerificationTrace "cleanup begin; managedProcesses=$($script:processes.Count)"
    if ($null -ne $script:smtpProcess -and -not $script:smtpProcess.HasExited) {
        $smtpProcessId = [int]$script:smtpProcess.Id
        $smtpProcess = Get-CimInstance Win32_Process `
            -Filter "ProcessId=$smtpProcessId" `
            -ErrorAction SilentlyContinue
        $smtpCommandLine = if ($null -eq $smtpProcess) {
            ''
        }
        else {
            [string]$smtpProcess.CommandLine
        }
        if ($null -ne $smtpProcess -and
            $smtpCommandLine.IndexOf(
                [IO.Path]::GetFileName([string]$script:smtpScriptPath),
                [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Write-VerificationTrace "stopping smtp launch process pid=$smtpProcessId"
            Stop-Process -Id $smtpProcessId -Force -ErrorAction Stop
            Wait-Process -Id $smtpProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
        elseif ($null -ne $smtpProcess) {
            Write-VerificationTrace (
                "smtp launch pid no longer matches; refused direct stop: pid=$smtpProcessId")
        }
    }
    foreach ($entry in @($script:processes.GetEnumerator())) {
        $managed = $entry.Value
        $processId = [int]$managed.process.Id
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            Write-VerificationTrace "managed process already absent: name=$($entry.Key), pid=$processId"
            continue
        }
        if ($process.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            Write-VerificationTrace "managed process command mismatch: name=$($entry.Key), pid=$processId"
            continue
        }
        Write-VerificationTrace "stopping managed process: name=$($entry.Key), pid=$processId"
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
    foreach ($entry in @($script:processes.GetEnumerator())) {
        $managed = $entry.Value
        Stop-ExpectedPortListeners `
            -Port ([int]$managed.port) `
            -Description "$($entry.Key) application" `
            -AllowedProcessNames @('java.exe') `
            -ExpectedCommandFragments @(
                [IO.Path]::GetFullPath([string]$managed.jar),
                [IO.Path]::GetFileName([string]$managed.jar))
    }
    if ($null -ne $script:smtpScriptPath) {
        Stop-ExpectedPortListeners `
            -Port $script:smtpPort `
            -Description 'SMTP capture server' `
            -AllowedProcessNames @('pwsh.exe', 'powershell.exe') `
            -ExpectedCommandFragments @(
                [IO.Path]::GetFullPath([string]$script:smtpScriptPath),
                [IO.Path]::GetFileName([string]$script:smtpScriptPath))
    }
    Wait-Until -Description 'managed ports to close' -TimeoutSeconds 10 -Condition {
        foreach ($port in @(
                $script:gatewayPort,
                $script:paymentPort,
                $script:notificationPort,
                $script:smtpPort)) {
            $remainingListeners = @(Get-NetTCPConnection -State Listen -LocalPort $port `
                    -ErrorAction SilentlyContinue)
            if ($remainingListeners.Count -gt 0) {
                return $false
            }
        }
        return $true
    }
}

function Get-RocketMqTopics {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list RocketMQ topics: $($output -join "`n")"
    }
    return @($output | ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-RocketMqConsumerGroupPresent {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin getConsumerConfig `
            -n plainjournal-rocketmq-namesrv:9876 `
            -g $script:consumerGroup 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw (
            "Unable to inspect RocketMQ consumer group " +
            "$($script:consumerGroup): $($output -join "`n")")
    }
    $pattern = (
        '(?m)^\s*groupName\s*=\s*' +
        [regex]::Escape($script:consumerGroup) +
        '\s*$')
    return ($output -join "`n") -match $pattern
}

function Get-RocketMqConsumerOffsetKeys {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $output = @(docker exec plainjournal-rocketmq-broker sh -lc `
                'cat /home/rocketmq/store/config/consumerOffset.json' 2>&1)
        if ($LASTEXITCODE -eq 0) {
            try {
                $metadata = ($output -join "`n") | ConvertFrom-Json -AsHashtable
                return @($metadata.offsetTable.Keys)
            }
            catch {
                if ($attempt -eq 5) {
                    throw "Unable to parse RocketMQ consumer offsets: $($_.Exception.Message)"
                }
            }
        }
        elseif ($attempt -eq 5) {
            throw "Unable to inspect RocketMQ consumer offsets: $($output -join "`n")"
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Unable to inspect RocketMQ consumer offsets.'
}

function Test-RocketMqConsumerGroupResidual {
    if (Test-RocketMqConsumerGroupPresent) {
        return $true
    }
    $suffix = "@$($script:consumerGroup)"
    return @(
        Get-RocketMqConsumerOffsetKeys |
        Where-Object { $_.EndsWith($suffix, [StringComparison]::Ordinal) }
    ).Count -gt 0
}

function Get-RocketMqConsumerArtifactTopics {
    return @(Get-RocketMqTopics | Where-Object {
            ($_.StartsWith("%RETRY%$($script:consumerGroup)") -or
                $_.StartsWith("%DLQ%$($script:consumerGroup)"))
        } | Sort-Object -Unique)
}

function Remove-VerificationRocketMqResources {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $script:consumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            throw (
                "RocketMQ consumer group deletion attempt $attempt failed: " +
                ($output -join "`n"))
        }
        Start-Sleep -Seconds 3
        if (-not (Test-RocketMqConsumerGroupResidual)) {
            Start-Sleep -Seconds 3
            if (-not (Test-RocketMqConsumerGroupResidual)) {
                break
            }
        }
    }
    if (Test-RocketMqConsumerGroupResidual) {
        throw (
            "RocketMQ consumer group remained after cleanup: " +
            $script:consumerGroup)
    }

    foreach ($topicName in @(Get-RocketMqConsumerArtifactTopics)) {
        $artifactGroup = $topicName -replace '^%(?:RETRY|DLQ)%', ''
        $groupOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $artifactGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($groupOutput -join "`n") -notmatch 'success') {
            throw "RocketMQ artifact group cleanup failed for ${artifactGroup}: $($groupOutput -join "`n")"
        }
        $topicOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topicName 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($topicOutput -join "`n") -notmatch 'success') {
            throw "RocketMQ artifact topic cleanup failed for ${topicName}: $($topicOutput -join "`n")"
        }
    }

    foreach ($topic in @($script:paymentTopic, $script:logisticsTopic)) {
        if ($topic -notin @(Get-RocketMqTopics)) {
            continue
        }
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topic 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            throw "RocketMQ topic cleanup failed for ${topic}: $($output -join "`n")"
        }
        Wait-Until `
            -Description "RocketMQ topic $topic removal" `
            -TimeoutSeconds 10 `
            -Condition {
                $topic -notin @(Get-RocketMqTopics)
            }
    }
}

function Get-VerificationRocketMqResiduals {
    $topics = @(Get-RocketMqTopics | Where-Object {
            $_ -in @($script:paymentTopic, $script:logisticsTopic)
        }) + @(Get-RocketMqConsumerArtifactTopics)
    $groups = if (Test-RocketMqConsumerGroupResidual) {
        @($script:consumerGroup)
    }
    else {
        @()
    }
    return [ordered]@{
        consumerGroups = @($groups)
        topics = @($topics)
    }
}

function Cleanup-Data {
    try {
        Invoke-NotificationSql -Sql @"
DELETE a FROM notification_delivery_retry_audit a
JOIN notification_delivery d ON d.id = a.delivery_id
JOIN notification_task t ON t.id = d.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
DELETE d FROM notification_delivery d
JOIN notification_task t ON t.id = d.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
DELETE n FROM in_app_notification n
JOIN notification_task t ON t.id = n.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
DELETE FROM notification_task WHERE source_event_id = '$($script:sourceEventId)';
DELETE FROM consumed_event
WHERE consumer_group = '$($script:consumerGroup)';
DELETE FROM consumer_failure
WHERE consumer_group = '$($script:consumerGroup)';
DELETE FROM notification_recipient WHERE user_id = $($script:customerId);
"@ | Out-Null
    }
    catch {
        Write-Warning "Notification cleanup failed: $($_.Exception.Message)"
    }
    try {
        Invoke-PaymentSql -Sql @"
DELETE FROM outbox_event
WHERE id IN (
    '$($script:sourceOutboxId)',
    '$($script:duplicateOutboxId)',
    '$($script:poisonEventId)'
);
"@ | Out-Null
    }
    catch {
        Write-Warning "Payment cleanup failed: $($_.Exception.Message)"
    }
}

try {
    Write-VerificationTrace 'stage 1 begin'
    Write-Host 'Stage 1/8: validating the local network and middleware baseline.'
    if (-not $SkipNetworkPreflight) {
        & 'D:\DevTools\Network\check-dev-network.ps1'
    }
    foreach ($port in @(
            $script:gatewayPort,
            $script:paymentPort,
            $script:notificationPort,
            $script:smtpPort)) {
        Assert-PortAvailable -Port $port
    }

    Write-VerificationTrace 'stage 2 begin'
    Write-Host 'Stage 2/8: provisioning the Notification schema, Nacos config, and isolated topics.'
    Push-Location $script:deployDirectory
    try {
        & .\bootstrap-resources.ps1
    }
    finally {
        Pop-Location
    }
    Import-LocalEnvironment
    Require-Environment -Names @(
        'IDENTITY_JWT_SECRET',
        'METRICS_SCRAPE_TOKEN',
        'NACOS_ADMIN_PASSWORD',
        'PAYMENT_DB_NAME',
        'PAYMENT_DB_USER',
        'PAYMENT_DB_PASSWORD',
        'NOTIFICATION_DB_NAME',
        'NOTIFICATION_DB_USER',
        'NOTIFICATION_DB_PASSWORD')
    foreach ($topic in @($script:paymentTopic, $script:logisticsTopic)) {
        $result = docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $topic -r 2 -w 2 2>&1
        if (($result -join "`n") -notmatch 'create topic to .* success') {
            throw "RocketMQ verification topic creation failed: $topic"
        }
    }

    Write-VerificationTrace 'stage 3 begin'
    Write-Host 'Stage 3/8: building and starting Payment Outbox, Notification, and Gateway.'
    if (-not $SkipBuild) {
        & mvn -f (Join-Path $script:backendRoot 'pom.xml') `
            -pl ecommerce-gateway,services/payment-service,services/notification-service `
            -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Maven package failed.'
        }
    }
    $gatewayJar = Join-Path $script:backendRoot `
        'ecommerce-gateway\target\ecommerce-gateway-1.0.2-SNAPSHOT.jar'
    $paymentJar = Join-Path $script:backendRoot `
        'services\payment-service\target\payment-service-1.0.2-SNAPSHOT.jar'
    $notificationJar = Join-Path $script:backendRoot `
        'services\notification-service\target\notification-service-1.0.2-SNAPSHOT.jar'

    $commonEnvironment = @{
        NACOS_HOST = '127.0.0.1'
        NACOS_CLIENT_PORT = '8848'
        NACOS_USERNAME = 'nacos'
        NACOS_ADMIN_PASSWORD = $env:NACOS_ADMIN_PASSWORD
        IDENTITY_JWT_SECRET = $env:IDENTITY_JWT_SECRET
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
        APP_ENV = 'm8-notify'
        SERVICE_IP = '127.0.0.1'
        OTLP_TRACING_EXPORT_ENABLED = 'false'
        TRACING_SAMPLING_PROBABILITY = '0'
    }
    $notificationEnvironment = @{} + $commonEnvironment
    $notificationEnvironment += @{
        SERVICE_INSTANCE_ID = $script:runId
        NOTIFICATION_SERVICE_PORT = [string]$script:notificationPort
        NOTIFICATION_DB_NAME = $env:NOTIFICATION_DB_NAME
        NOTIFICATION_DB_USER = $env:NOTIFICATION_DB_USER
        NOTIFICATION_DB_PASSWORD = $env:NOTIFICATION_DB_PASSWORD
        ECOMMERCE_NOTIFICATION_EVENTS_PAYMENT_TOPIC = $script:paymentTopic
        ECOMMERCE_NOTIFICATION_EVENTS_LOGISTICS_TOPIC = $script:logisticsTopic
        ECOMMERCE_NOTIFICATION_EVENTS_CONSUMER_GROUP = $script:consumerGroup
        NOTIFICATION_EMAIL_ENABLED = 'true'
        NOTIFICATION_EMAIL_WORKER_ENABLED = 'true'
        NOTIFICATION_EMAIL_MAXIMUM_ATTEMPTS = '2'
        NOTIFICATION_EMAIL_RETRY_DELAY = '1s'
        NOTIFICATION_EMAIL_INITIAL_DELAY = '500'
        NOTIFICATION_EMAIL_FIXED_DELAY = '500'
        NOTIFICATION_SMTP_HOST = '127.0.0.1'
        NOTIFICATION_SMTP_PORT = [string]$script:smtpPort
        NOTIFICATION_SMTP_CONNECT_TIMEOUT_MS = '500'
        NOTIFICATION_SMTP_READ_TIMEOUT_MS = '1000'
        NOTIFICATION_SMTP_WRITE_TIMEOUT_MS = '1000'
    }
    Start-Application -Name 'notification' -Jar $notificationJar `
        -Port $script:notificationPort `
        -Environment $notificationEnvironment `
        -ApplicationArguments @(
            "--ecommerce.notification.events.payment-topic=$($script:paymentTopic)",
            "--ecommerce.notification.events.logistics-topic=$($script:logisticsTopic)",
            "--ecommerce.notification.events.consumer-group=$($script:consumerGroup)",
            '--ecommerce.notification.email.enabled=true',
            '--ecommerce.notification.email.worker-enabled=true',
            '--ecommerce.notification.email.maximum-attempts=2',
            '--ecommerce.notification.email.retry-delay=1s',
            '--ecommerce.notification.email.initial-delay=500',
            '--ecommerce.notification.email.fixed-delay=500',
            '--spring.mail.host=127.0.0.1',
            "--spring.mail.port=$($script:smtpPort)",
            '--spring.mail.properties.mail.smtp.connectiontimeout=500',
            '--spring.mail.properties.mail.smtp.timeout=1000',
            '--spring.mail.properties.mail.smtp.writetimeout=1000'
        )
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:notificationPort)/actuator/health/liveness" `
        -ProcessName 'notification'

    $paymentEnvironment = @{} + $commonEnvironment
    $paymentEnvironment += @{
        SERVICE_INSTANCE_ID = "$($script:runId)-payment"
        PAYMENT_SERVICE_PORT = [string]$script:paymentPort
        PAYMENT_DB_NAME = $env:PAYMENT_DB_NAME
        PAYMENT_DB_USER = $env:PAYMENT_DB_USER
        PAYMENT_DB_PASSWORD = $env:PAYMENT_DB_PASSWORD
        MOCK_PAYMENT_CALLBACK_SECRET = $env:MOCK_PAYMENT_CALLBACK_SECRET
        PAYMENT_INTERNAL_SERVICE_TOKEN = $env:PAYMENT_INTERNAL_SERVICE_TOKEN
        ECOMMERCE_PAYMENT_OUTBOX_TOPIC = $script:paymentTopic
        PAYMENT_OUTBOX_ENABLED = 'true'
        PAYMENT_OUTBOX_PUBLISHER_ID = "$($script:runId)-payment"
        PAYMENT_REFUND_CONSUMER_ENABLED = 'false'
        PAYMENT_REFUND_DISPATCH_ENABLED = 'false'
        PAYMENT_RECONCILIATION_ENABLED = 'false'
    }
    Start-Application -Name 'payment' -Jar $paymentJar `
        -Port $script:paymentPort `
        -Environment $paymentEnvironment `
        -ApplicationArguments @(
            "--ecommerce.payment.outbox.topic=$($script:paymentTopic)",
            '--ecommerce.payment.outbox.enabled=true',
            "--ecommerce.payment.outbox.publisher-id=$($script:runId)-payment",
            '--ecommerce.payment.refund-consumer.enabled=false',
            '--ecommerce.payment.refund-dispatch.enabled=false',
            '--ecommerce.payment.reconciliation.enabled=false'
        )
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:paymentPort)/actuator/health/liveness" `
        -ProcessName 'payment'

    $gatewayEnvironment = @{} + $commonEnvironment
    $gatewayEnvironment += @{
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
        GATEWAY_PORT = [string]$script:gatewayPort
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = '16379'
        REDIS_PASSWORD = $env:REDIS_PASSWORD
    }
    Start-Application -Name 'gateway' -Jar $gatewayJar `
        -Port $script:gatewayPort `
        -Environment $gatewayEnvironment
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:gatewayPort)/actuator/health/liveness" `
        -ProcessName 'gateway'

    Write-VerificationTrace 'stage 4 begin'
    Write-Host 'Stage 4/8: configuring the customer email preference through Gateway.'
    $customerToken = New-AccessToken -UserId $script:customerId -Roles @('CUSTOMER')
    $adminToken = New-AccessToken -UserId $script:adminId -Roles @('ADMIN')
    $customerHeaders = @{ Authorization = "Bearer $customerToken" }
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $gatewayBase = "http://127.0.0.1:$($script:gatewayPort)/api/v1/notifications"
    $preference = Invoke-JsonRequest -Method Put `
        -Uri "$gatewayBase/email-preference" `
        -Headers $customerHeaders `
        -Body @{ email = 'verification@plainjournal.local'; enabled = $true }
    if ($preference.status -ne 200 -or -not $preference.body.data.enabled) {
        Write-VerificationTrace "email preference failed: status=$($preference.status), body=$($preference.body | ConvertTo-Json -Depth 5 -Compress)"
        throw 'Email preference was not accepted through Gateway.'
    }

    Write-VerificationTrace 'stage 5 begin'
    Write-Host 'Stage 5/8: publishing a real Payment Outbox event while SMTP is unavailable.'
    $eventEnvelope = [ordered]@{
        eventId = $script:sourceEventId
        eventType = 'PaymentSucceeded'
        aggregateType = 'PaymentOrder'
        aggregateId = "$($script:runId)-payment"
        aggregateVersion = 1
        occurredAt = [DateTimeOffset]::UtcNow.ToString('o')
        producer = 'payment-service'
        payloadVersion = 1
        payload = [ordered]@{
            paymentNo = "$($script:runId)-payment"
            orderNo = "$($script:runId)-order"
            userId = $script:customerId
            reservationNo = "$($script:runId)-reservation"
            amount = 88.00
            channel = 'MOCK'
            channelTransactionNo = "$($script:runId)-channel"
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $escapedEnvelope = $eventEnvelope.Replace("'", "''")
    Invoke-PaymentSql -Sql @"
INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, claim_owner,
    claim_until, published_at, last_error, created_at, updated_at
) VALUES (
    '$($script:sourceOutboxId)', 'PaymentSucceeded', 'M8NotificationVerification',
    '$($script:runId)-payment', 1, '$escapedEnvelope', 'PENDING', 0,
    UTC_TIMESTAMP(3), NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
);
"@ | Out-Null

    Wait-Until -Description 'Payment Outbox publication' -Condition {
        $value = @(Invoke-PaymentSql -Sql @"
SELECT status FROM outbox_event WHERE id = '$($script:sourceOutboxId)';
"@)
        $value.Count -eq 1 -and $value[0] -eq 'PUBLISHED'
    }
    Wait-Until -Description 'Notification email NEEDS_ATTENTION' -TimeoutSeconds 30 -Condition {
        $value = Get-NotificationScalar -Sql @"
SELECT d.status
FROM notification_delivery d
JOIN notification_task t ON t.id = d.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
"@
        $value -eq 'NEEDS_ATTENTION'
    }

    Invoke-PaymentSql -Sql @"
INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, claim_owner,
    claim_until, published_at, last_error, created_at, updated_at
) VALUES (
    '$($script:duplicateOutboxId)', 'PaymentSucceeded', 'M8NotificationDuplicate',
    '$($script:runId)-payment', 1, '$escapedEnvelope', 'PENDING', 0,
    UTC_TIMESTAMP(3), NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
);
"@ | Out-Null
    Wait-Until -Description 'duplicate Payment Outbox publication' -Condition {
        $value = @(Invoke-PaymentSql -Sql @"
SELECT status FROM outbox_event WHERE id = '$($script:duplicateOutboxId)';
"@)
        $value.Count -eq 1 -and $value[0] -eq 'PUBLISHED'
    }
    Wait-Until -Description 'duplicate notification consumer acknowledgement' -Condition {
        Test-NotificationConsumerCaughtUp
    }
    Wait-Until -Description 'duplicate notification convergence' -Condition {
        (Get-NotificationScalar -Sql @"
SELECT
    (SELECT COUNT(*) FROM consumed_event
      WHERE event_id = '$($script:sourceEventId)'
        AND consumer_group = '$($script:consumerGroup)')
  + (SELECT COUNT(*) FROM notification_task
      WHERE source_event_id = '$($script:sourceEventId)')
  + (SELECT COUNT(*) FROM in_app_notification n
      JOIN notification_task t ON t.id = n.task_id
      WHERE t.source_event_id = '$($script:sourceEventId)')
  + (SELECT COUNT(*) FROM notification_delivery d
      JOIN notification_task t ON t.id = d.task_id
      WHERE t.source_event_id = '$($script:sourceEventId)');
"@) -eq '4'
    }

    $unread = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/unread-count" `
        -Headers $customerHeaders
    if ($unread.status -ne 200 -or [long]$unread.body.data.count -ne 1) {
        throw 'In-app notification was not readable while SMTP was unavailable.'
    }
    $notificationId = Get-NotificationScalar -Sql @"
SELECT CAST(n.id AS CHAR)
FROM in_app_notification n
JOIN notification_task t ON t.id = n.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
"@
    if ([string]::IsNullOrWhiteSpace($notificationId)) {
        throw 'Notification MySQL fact did not expose the generated in-app notification ID.'
    }
    $notificationList = Invoke-JsonRequest -Method Get `
        -Uri "${gatewayBase}?size=20" `
        -Headers $customerHeaders
    if ($notificationList.status -ne 200) {
        throw "Notification list through Gateway returned HTTP $($notificationList.status)."
    }
    $expectedReferenceNo = "$($script:runId)-order"
    $matchingNotifications = @($notificationList.body.data.items | Where-Object {
            $_.referenceNo -eq $expectedReferenceNo
        })
    if ($matchingNotifications.Count -ne 1) {
        throw (
            "Expected one Gateway notification for reference $expectedReferenceNo, " +
            "found $($matchingNotifications.Count).")
    }
    $gatewayNotificationId = $matchingNotifications[0].id
    if ($gatewayNotificationId -isnot [string]) {
        $actualType = if ($null -eq $gatewayNotificationId) {
            'null'
        }
        else {
            $gatewayNotificationId.GetType().FullName
        }
        throw "Gateway notification ID must be a JSON string, received $actualType."
    }
    if (-not [string]::Equals(
            $notificationId,
            $gatewayNotificationId,
            [StringComparison]::Ordinal)) {
        throw (
            "Gateway notification ID does not exactly match MySQL: " +
            "mysql=$notificationId gateway=$gatewayNotificationId.")
    }
    $deliveryId = [long](Get-NotificationScalar -Sql @"
SELECT d.id
FROM notification_delivery d
JOIN notification_task t ON t.id = d.task_id
WHERE t.source_event_id = '$($script:sourceEventId)';
"@)
    $script:verification.smtpUnavailable = [ordered]@{
        inAppUnread = [long]$unread.body.data.count
        notificationIdContract = [ordered]@{
            gatewayHttpStatus = $notificationList.status
            jsonType = 'string'
            mysqlId = $notificationId
            gatewayId = $gatewayNotificationId
            exactMatch = $true
        }
        deliveryStatus = 'NEEDS_ATTENTION'
        duplicateSourceEventsConverged = $true
        attempts = [int](Get-NotificationScalar -Sql @"
SELECT attempts FROM notification_delivery WHERE id = $deliveryId;
"@)
    }

    Write-VerificationTrace 'stage 6 begin'
    Write-Host 'Stage 6/8: rejecting customer recovery and performing idempotent admin recovery.'
    $retryBody = @{
        commandId = "$($script:runId)-retry-command"
        reason = 'Controlled SMTP outage ended; retry the same delivery with audit.'
    }
    $customerRetry = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/admin/email-deliveries/$deliveryId/retry" `
        -Headers $customerHeaders `
        -Body $retryBody
    if ($customerRetry.status -ne 403) {
        throw "Customer retry should be forbidden, received HTTP $($customerRetry.status)."
    }
    $capturePath = Start-SmtpCapture
    $adminRetry = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/admin/email-deliveries/$deliveryId/retry" `
        -Headers $adminHeaders `
        -Body $retryBody
    $adminRetryAgain = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/admin/email-deliveries/$deliveryId/retry" `
        -Headers $adminHeaders `
        -Body $retryBody
    if ($adminRetry.status -ne 200 -or $adminRetryAgain.status -ne 200) {
        throw 'Idempotent admin retry was not accepted.'
    }
    Wait-Until -Description 'SMTP delivery success after audited retry' -TimeoutSeconds 30 -Condition {
        (Get-NotificationScalar -Sql @"
SELECT status FROM notification_delivery WHERE id = $deliveryId;
"@) -eq 'SENT'
    }
    Wait-Until -Description 'captured SMTP message' -TimeoutSeconds 15 -Condition {
        (Test-Path -LiteralPath $capturePath) -and
        ((Get-Item -LiteralPath $capturePath).Length -gt 0)
    }
    $smtpText = Get-Content -LiteralPath $capturePath -Raw
    $providerMessageId = Get-NotificationScalar -Sql @"
SELECT provider_message_id FROM notification_delivery WHERE id = $deliveryId;
"@
    if (-not $smtpText.Contains($providerMessageId)) {
        throw 'Captured SMTP message did not preserve the stable Message-ID.'
    }
    $auditCount = [long](Get-NotificationScalar -Sql @"
SELECT COUNT(*) FROM notification_delivery_retry_audit WHERE delivery_id = $deliveryId;
"@)
    if ($auditCount -ne 1) {
        throw "Expected one retry audit row, found $auditCount."
    }
    $script:verification.recovery = [ordered]@{
        customerRetryHttpStatus = $customerRetry.status
        adminRetryHttpStatus = $adminRetry.status
        repeatedAdminRetryHttpStatus = $adminRetryAgain.status
        finalDeliveryStatus = 'SENT'
        auditRows = $auditCount
        providerMessageId = $providerMessageId
        capturedMessages = ([regex]::Matches($smtpText, '----- MESSAGE -----')).Count
    }

    Write-VerificationTrace 'stage 7 begin'
    Write-Host 'Stage 7/8: verifying durable poison-event governance without raw payload exposure.'
    $poisonMarker = "$($script:runId)-private-marker"
    $poisonEnvelope = [ordered]@{
        eventId = $script:poisonEventId
        eventType = 'PaymentSucceeded'
        payloadVersion = 99
        payload = [ordered]@{
            userId = $script:customerId
            privateMarker = $poisonMarker
        }
    } | ConvertTo-Json -Depth 5 -Compress
    $escapedPoison = $poisonEnvelope.Replace("'", "''")
    Invoke-PaymentSql -Sql @"
INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, claim_owner,
    claim_until, published_at, last_error, created_at, updated_at
) VALUES (
    '$($script:poisonEventId)', 'PaymentSucceeded', 'M8NotificationPoison',
    '$($script:runId)-poison', 1, '$escapedPoison', 'PENDING', 0,
    UTC_TIMESTAMP(3), NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
);
"@ | Out-Null
    Wait-Until -Description 'poison event durable failure record' -TimeoutSeconds 30 -Condition {
        (Get-NotificationScalar -Sql @"
SELECT status FROM consumer_failure
WHERE consumer_group = '$($script:consumerGroup)'
  AND raw_payload LIKE '%$poisonMarker%';
"@) -eq 'NEEDS_ATTENTION'
    }
    $failureReport = Invoke-JsonRequest -Method Get `
        -Uri "http://127.0.0.1:$($script:notificationPort)/actuator/consumerfailures" `
        -Headers $adminHeaders
    $failureJson = $failureReport.body | ConvertTo-Json -Depth 10 -Compress
    if ($failureReport.status -ne 200 -or $failureJson.Contains($poisonMarker)) {
        throw 'Consumer failure endpoint was unavailable or exposed the raw poison payload.'
    }
    $script:verification.poisonEvent = [ordered]@{
        status = 'NEEDS_ATTENTION'
        actuatorHttpStatus = $failureReport.status
        rawPayloadExposed = $false
    }

    Write-VerificationTrace 'stage 8 begin'
    Write-Host 'Stage 8/8: cleaning run data and verifying zero transient residue.'
    Cleanup-Data
    $remainingRows = [long](Get-NotificationScalar -Sql @"
SELECT
    (SELECT COUNT(*) FROM notification_task WHERE source_event_id = '$($script:sourceEventId)')
  + (SELECT COUNT(*) FROM consumed_event WHERE consumer_group = '$($script:consumerGroup)')
  + (SELECT COUNT(*) FROM consumer_failure WHERE consumer_group = '$($script:consumerGroup)')
  + (SELECT COUNT(*) FROM notification_recipient WHERE user_id = $($script:customerId));
"@)
    if ($remainingRows -ne 0) {
        throw "Notification verification rows remain after cleanup: $remainingRows"
    }
    Stop-ManagedProcesses
    Remove-VerificationRocketMqResources
    $rocketMqResiduals = Get-VerificationRocketMqResiduals
    if (@($rocketMqResiduals.consumerGroups).Count -gt 0 -or
        @($rocketMqResiduals.topics).Count -gt 0) {
        throw (
            "Notification RocketMQ cleanup left residual metadata: groups=" +
            "$($rocketMqResiduals.consumerGroups -join ','), topics=" +
            "$($rocketMqResiduals.topics -join ',')")
    }
    $script:verification.cleanup = [ordered]@{
        notificationRowsRemaining = $remainingRows
        managedPortsListening = 0
        temporaryJvmProcesses = 0
        residualRocketMqConsumerGroups = @($rocketMqResiduals.consumerGroups)
        residualRocketMqTopics = @($rocketMqResiduals.topics)
    }
    $script:verification.runId = $script:runId
    $script:verification.generatedAt = [DateTimeOffset]::Now.ToString('o')
    $verificationPath = Join-Path $script:runDirectory 'verification.json'
    $script:verification | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $verificationPath -Encoding utf8
    $script:verification.cleanup | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (Join-Path $script:runDirectory 'cleanup.json') `
            -Encoding utf8
    Write-Host "M8 Notification verification passed: $verificationPath"
}
catch {
    Write-VerificationTrace "failed: $($_.Exception.GetType().FullName): $($_.Exception.Message)"
    Write-VerificationTrace "position: $($_.InvocationInfo.PositionMessage)"
    Write-Host "M8 Notification verification failed: $($_.Exception.Message)"
    throw
}
finally {
    Write-VerificationTrace 'finally begin'
    Cleanup-Data
    try {
        Stop-ManagedProcesses
    }
    catch {
        Write-VerificationTrace "process cleanup failed: $($_.Exception.Message)"
        Write-Warning "Process cleanup verification failed: $($_.Exception.Message)"
    }
    try {
        Remove-VerificationRocketMqResources
    }
    catch {
        Write-VerificationTrace "topic cleanup failed: $($_.Exception.Message)"
        Write-Warning "Verification topic cleanup failed: $($_.Exception.Message)"
    }
    Write-VerificationTrace 'finally end'
}
