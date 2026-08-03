#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidatePattern('^\d{8}$')]
    [string]$EvidenceDate = '20260724',
    [switch]$SkipBuild,
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:deployDirectory = Join-Path $script:repositoryRoot 'deploy\docker'
$script:suffix = "$EvidenceDate$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$script:runId = "m8-analytics-$($script:suffix)"
$script:runDirectory = Join-Path $script:backendRoot ".run\$($script:runId)"
$script:analyticsDatabase = "ecom_analytics_$($script:suffix.ToLowerInvariant())"
$script:analyticsUser = "ecom_a_$($script:suffix.Substring($script:suffix.Length - 8).ToLowerInvariant())"
$script:analyticsPassword = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$script:tradeTopic = "ecommerce-analytics-trade-$($script:suffix)"
$script:paymentTopic = "ecommerce-analytics-payment-$($script:suffix)"
$script:consumerGroup = "analytics-$($script:suffix)"
$script:gatewayPort = 18000
$script:analyticsPort = 18110
$script:nacosUsername = 'nacos'
$script:processes = [ordered]@{}
$script:createdTopics = [Collections.Generic.List[string]]::new()
$script:createdConsumerGroups = [Collections.Generic.List[string]]::new()
$script:databaseCreated = $false
$script:userCreated = $false
$script:proxyStopped = $false
$script:verification = [ordered]@{}
$script:cleanup = [ordered]@{}
$script:failureContext = [ordered]@{}

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
        [Environment]::SetEnvironmentVariable(
            $name.Trim(),
            $value,
            'Process')
    }
}

function Require-Environment {
    param([Parameter(Mandatory)][string[]]$Names)

    $missing = @($Names | Where-Object {
        [string]::IsNullOrWhiteSpace(
            [Environment]::GetEnvironmentVariable($_, 'Process'))
    })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
    }
}

function Invoke-NetworkPreflight {
    if ($SkipNetworkPreflight) {
        Write-VerificationTrace 'network preflight skipped by explicit switch'
        return
    }
    $networkScript = 'D:\DevTools\Network\check-dev-network.ps1'
    if (-not (Test-Path -LiteralPath $networkScript)) {
        throw "Missing required network preflight: $networkScript"
    }
    & $networkScript
    if ($LASTEXITCODE -ne 0) {
        throw 'Development network preflight failed.'
    }
}

function Assert-CoreContainers {
    $required = @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy',
        'plainjournal-minio'
    )
    $running = @(docker ps --format '{{.Names}}')
    foreach ($name in $required) {
        if ($running -notcontains $name) {
            throw "Required core container is not running: $name"
        }
    }
    $script:verification.coreContainersAtStart = $required
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "Port $Port is already in use by PID $($listeners[0].OwningProcess)."
    }
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

function Wait-TcpPort {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 60
    )

    Wait-Until -Description "TCP port $Port" -TimeoutSeconds $TimeoutSeconds -Condition {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $task = $client.ConnectAsync('127.0.0.1', $Port)
            if (-not $task.Wait(1000)) {
                return $false
            }
            return $client.Connected
        }
        catch {
            return $false
        }
        finally {
            $client.Dispose()
        }
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
        $original[$entry.Key] =
            [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
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
            -RedirectStandardOutput (
                Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (
                Join-Path $script:runDirectory "$Name.err.log") `
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
            [Environment]::SetEnvironmentVariable(
                $entry.Key,
                $entry.Value,
                'Process')
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
            $response = Invoke-WebRequest -Uri $Uri `
                -SkipHttpErrorCheck `
                -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and
                $response.StatusCode -lt 300) {
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

function Stop-ManagedProcesses {
    foreach ($entry in @($script:processes.GetEnumerator())) {
        $managed = $entry.Value
        $processId = [int]$managed.process.Id
        $process = Get-CimInstance Win32_Process `
            -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        $jarName = [IO.Path]::GetFileName([string]$managed.jar)
        if ([string]$process.CommandLine -notlike "*$jarName*") {
            throw (
                "Refused to stop PID $processId because its command line " +
                "no longer matches $jarName.")
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
}

function Invoke-RootSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i `
        -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" `
        plainjournal-mysql mysql -uroot -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Root MySQL command failed.'
    }
    return @($output)
}

function Invoke-AnalyticsSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i `
        -e "MYSQL_PWD=$($script:analyticsPassword)" `
        plainjournal-mysql mysql "-u$($script:analyticsUser)" `
        $script:analyticsDatabase -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Analytics verification MySQL command failed.'
    }
    return @($output)
}

function Get-AnalyticsScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-AnalyticsSql -Sql $Sql)
    return $rows.Count -eq 0 ? $null : [string]$rows[0]
}

function New-VerificationDatabase {
    $user = $script:analyticsUser.Replace("'", "''")
    $password = $script:analyticsPassword.Replace("'", "''")
    Invoke-RootSql -Sql @"
CREATE DATABASE ``$($script:analyticsDatabase)``
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$user'@'%' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON ``$($script:analyticsDatabase)``.* TO '$user'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
    $script:databaseCreated = $true
    $script:userCreated = $true
}

function Remove-VerificationDatabase {
    $user = $script:analyticsUser.Replace("'", "''")
    Invoke-RootSql -Sql @"
DROP DATABASE IF EXISTS ``$($script:analyticsDatabase)``;
DROP USER IF EXISTS '$user'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
    $script:databaseCreated = $false
    $script:userCreated = $false
}

function New-RocketMqTopic {
    param([Parameter(Mandatory)][string]$Topic)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $Topic `
            -r 2 `
            -w 2 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'success') {
        throw "Unable to create RocketMQ topic ${Topic}: $($output -join "`n")"
    }
    $script:createdTopics.Add($Topic)
}

function New-RocketMqConsumerGroup {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateSubGroup `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -g $ConsumerGroup `
            -s true `
            -m true `
            -d true `
            -o false `
            -q 1 `
            -r 16 `
            -i 0 `
            -w 1 `
            -a true 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'success') {
        throw (
            "Unable to create RocketMQ consumer group " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    $script:createdConsumerGroups.Add($ConsumerGroup)
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
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin getConsumerConfig `
            -n plainjournal-rocketmq-namesrv:9876 `
            -g $ConsumerGroup 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw (
            "Unable to inspect RocketMQ consumer group " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    $pattern = (
        '(?m)^\s*groupName\s*=\s*' +
        [regex]::Escape($ConsumerGroup) +
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
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    if (Test-RocketMqConsumerGroupPresent -ConsumerGroup $ConsumerGroup) {
        return $true
    }
    $suffix = "@$ConsumerGroup"
    return @(
        Get-RocketMqConsumerOffsetKeys |
        Where-Object { $_.EndsWith($suffix, [StringComparison]::Ordinal) }
    ).Count -gt 0
}

function Get-RocketMqConsumerArtifactTopics {
    param([Parameter(Mandatory)][string[]]$ConsumerGroups)

    return @(Get-RocketMqTopics | Where-Object {
            $topicName = $_
            ($topicName.StartsWith('%RETRY%') -or
                $topicName.StartsWith('%DLQ%')) -and
            @($ConsumerGroups | Where-Object {
                    $topicName.StartsWith("%RETRY%$_") -or
                    $topicName.StartsWith("%DLQ%$_")
                }).Count -gt 0
        } | Sort-Object -Unique)
}

function Remove-RocketMqConsumerGroup {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $ConsumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            throw (
                "RocketMQ consumer group deletion attempt $attempt failed " +
                "for ${ConsumerGroup}: $($output -join "`n")")
        }
        Start-Sleep -Seconds 3
        if (-not (Test-RocketMqConsumerGroupResidual -ConsumerGroup $ConsumerGroup)) {
            Start-Sleep -Seconds 3
            if (-not (Test-RocketMqConsumerGroupResidual -ConsumerGroup $ConsumerGroup)) {
                return
            }
        }
    }
    throw (
        "RocketMQ consumer group remained after three deletion attempts: " +
        $ConsumerGroup)
}

function Remove-RocketMqResources {
    $errors = [Collections.Generic.List[string]]::new()
    $configuredGroups = @($script:createdConsumerGroups)
    foreach ($consumerGroup in $configuredGroups) {
        try {
            Remove-RocketMqConsumerGroup -ConsumerGroup $consumerGroup
        }
        catch {
            $errors.Add("${consumerGroup}: $($_.Exception.Message)")
        }
    }
    foreach ($topic in @($script:createdTopics)) {
        if ($topic -notin @(Get-RocketMqTopics)) {
            continue
        }
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topic 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            $errors.Add("${topic}: $($output -join "`n")")
            continue
        }
        try {
            Wait-Until `
                -Description "RocketMQ topic $topic removal" `
                -TimeoutSeconds 10 `
                -Condition {
                    $topic -notin @(Get-RocketMqTopics)
                }
        }
        catch {
            $errors.Add("${topic} residual check: $($_.Exception.Message)")
        }
    }
    # RocketMQ may materialize a retry topic shortly after the consumer group
    # has already disappeared. A single snapshot races that asynchronous
    # creation, so require two consecutive clean scans and delete only the
    # actual artifact topic (its suffix is not necessarily a group name).
    $consecutiveCleanScans = 0
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $artifactTopics = @(Get-RocketMqConsumerArtifactTopics `
                -ConsumerGroups $configuredGroups)
        if ($artifactTopics.Count -eq 0) {
            $consecutiveCleanScans++
            if ($consecutiveCleanScans -ge 2) {
                break
            }
            Start-Sleep -Seconds 2
            continue
        }
        $consecutiveCleanScans = 0
        foreach ($topicName in $artifactTopics) {
            $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                    -n plainjournal-rocketmq-namesrv:9876 `
                    -c EcommerceCluster `
                    -t $topicName 2>&1)
            if ($LASTEXITCODE -ne 0 -or
                ($output -join "`n") -notmatch 'success') {
                $errors.Add("${topicName}: unable to delete artifact topic: " +
                    ($output -join "`n"))
            }
        }
        Start-Sleep -Seconds 3
    }
    $remainingArtifacts = @(Get-RocketMqConsumerArtifactTopics `
            -ConsumerGroups $configuredGroups)
    if ($remainingArtifacts.Count -ne 0) {
        $errors.Add(
            'consumer artifact topics did not quiesce: ' +
            ($remainingArtifacts -join ', '))
    }
    return @($errors)
}

function Send-DomainEvent {
    param(
        [Parameter(Mandatory)][string]$Topic,
        [Parameter(Mandatory)][string]$Tag,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Envelope
    )

    $body = $Envelope | ConvertTo-Json -Depth 12 -Compress
    $eventId = [string]$Envelope.eventId
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin sendMessage `
            -n plainjournal-rocketmq-namesrv:9876 `
            -t $Topic `
            -c $Tag `
            -k $eventId `
            -p $body 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'SEND_OK') {
        throw "Unable to send ${Tag}: $($output -join "`n")"
    }
}

function New-Envelope {
    param(
        [Parameter(Mandatory)][string]$EventId,
        [Parameter(Mandatory)][string]$EventType,
        [Parameter(Mandatory)][string]$Producer,
        [Parameter(Mandatory)][string]$AggregateType,
        [Parameter(Mandatory)][string]$AggregateId,
        [Parameter(Mandatory)][long]$AggregateVersion,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Payload
    )

    return [ordered]@{
        eventId = $EventId
        eventType = $EventType
        aggregateType = $AggregateType
        aggregateId = $AggregateId
        aggregateVersion = $AggregateVersion
        occurredAt = '2026-07-24T08:00:00.000Z'
        producer = $Producer
        payloadVersion = 1
        payload = $Payload
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
    $encodedHeader = ConvertTo-Base64Url (
        [Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url (
        [Text.Encoding]::UTF8.GetBytes($payload))
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

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][ValidateSet('Get', 'Post')][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $parsed = if ([string]::IsNullOrWhiteSpace($response.Content)) {
        $null
    }
    else {
        $response.Content | ConvertFrom-Json -Depth 20
    }
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        body = $parsed
    }
}

function Wait-SourceEventCount {
    param(
        [Parameter(Mandatory)][long]$Expected,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastObserved = $null
    do {
        $value = Get-AnalyticsScalar -Sql 'SELECT COUNT(*) FROM analytics_source_event;'
        if ($null -ne $value) {
            $lastObserved = [long]$value
            if ($lastObserved -eq $Expected) {
                return
            }
            if ($lastObserved -gt $Expected) {
                throw (
                    "Analytics source event count exceeded the isolated expectation: " +
                    "expected=$Expected actual=$lastObserved")
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $observed = $null -eq $lastObserved ? 'null' : [string]$lastObserved
    throw (
        "Timed out waiting for analytics source event count $Expected; " +
        "last observed count was $observed.")
}

function Ensure-ProxyRunning {
    $running = docker inspect -f '{{.State.Running}}' plainjournal-rocketmq-proxy 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]$running -ne 'true') {
        docker start plainjournal-rocketmq-proxy | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to restart RocketMQ Proxy.'
        }
    }
    $script:proxyStopped = $false
    Wait-TcpPort -Port 18082 -TimeoutSeconds 60
}

function Get-ResidualState {
    $schemas = @(Invoke-RootSql -Sql @"
SELECT COUNT(*) FROM information_schema.schemata
WHERE schema_name = '$($script:analyticsDatabase)';
"@)
    $users = @(Invoke-RootSql -Sql @"
SELECT COUNT(*) FROM mysql.user
WHERE user = '$($script:analyticsUser)';
"@)
    $ports = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in @($script:gatewayPort, $script:analyticsPort) })
    $jvms = @(Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq 'java.exe' -and (
                $_.CommandLine -like '*analytics-service-1.0.1-SNAPSHOT.jar*' -or
                $_.CommandLine -like '*ecommerce-gateway-1.0.1-SNAPSHOT.jar*')
        })
    $topicList = @(Get-RocketMqTopics)
    $consumerGroups = @($script:createdConsumerGroups | Where-Object {
        Test-RocketMqConsumerGroupResidual -ConsumerGroup $_
    })
    $coreNames = @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy',
        'plainjournal-minio'
    )
    $running = @(docker ps --format '{{.Names}}')
    return [ordered]@{
        residualDatabaseSchemas = $schemas.Count -eq 0 ? -1 : [int]$schemas[0]
        residualDatabaseUsers = $users.Count -eq 0 ? -1 : [int]$users[0]
        residualApplicationPorts = $ports.Count
        residualApplicationJvms = $jvms.Count
        residualConsumerGroups = @($consumerGroups)
        residualTopics = @($topicList | Where-Object {
                $_ -in @($script:tradeTopic, $script:paymentTopic)
            }) + @(Get-RocketMqConsumerArtifactTopics `
                -ConsumerGroups @($script:createdConsumerGroups))
        coreContainersRunning = @($coreNames | Where-Object { $running -contains $_ }).Count
    }
}

Import-LocalEnvironment
Require-Environment -Names @(
    'MYSQL_ROOT_PASSWORD',
    'NACOS_ADMIN_PASSWORD',
    'REDIS_PASSWORD',
    'IDENTITY_JWT_SECRET',
    'METRICS_SCRAPE_TOKEN'
)

$failure = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()
$startedAt = [DateTimeOffset]::Now
Write-VerificationTrace "run started: $($script:runId)"

try {
    Invoke-NetworkPreflight
    Assert-CoreContainers
    Assert-PortAvailable -Port $script:gatewayPort
    Assert-PortAvailable -Port $script:analyticsPort

    if (-not $SkipBuild) {
        Push-Location $script:backendRoot
        try {
            & mvn '-pl' 'ecommerce-gateway,services/analytics-service' `
                '-am' '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven package failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    New-VerificationDatabase
    New-RocketMqTopic -Topic $script:tradeTopic
    New-RocketMqTopic -Topic $script:paymentTopic
    New-RocketMqConsumerGroup -ConsumerGroup $script:consumerGroup

    $analyticsJar = Join-Path $script:backendRoot `
        'services\analytics-service\target\analytics-service-1.0.1-SNAPSHOT.jar'
    $gatewayJar = Join-Path $script:backendRoot `
        'ecommerce-gateway\target\ecommerce-gateway-1.0.1-SNAPSHOT.jar'

    Start-Application -Name 'analytics' -Jar $analyticsJar `
        -Port $script:analyticsPort `
        -ApplicationArguments @(
            '--ecommerce.analytics.events.enabled=true',
            '--ecommerce.analytics.events.endpoints=127.0.0.1:18082',
            "--ecommerce.analytics.events.consumer-group=$($script:consumerGroup)",
            "--ecommerce.analytics.events.trade-topic=$($script:tradeTopic)",
            "--ecommerce.analytics.events.payment-topic=$($script:paymentTopic)",
            '--ecommerce.analytics.events.initial-delay=250',
            '--ecommerce.analytics.events.fixed-delay=250',
            '--ecommerce.analytics.events.await-duration=5s',
            '--ecommerce.analytics.events.invisible-duration=10s',
            '--ecommerce.analytics.events.batch-size=20'
        ) `
        -Environment @{
        ANALYTICS_SERVICE_PORT = [string]$script:analyticsPort
        ANALYTICS_DB_NAME = $script:analyticsDatabase
        ANALYTICS_DB_USER = $script:analyticsUser
        ANALYTICS_DB_PASSWORD = $script:analyticsPassword
        MYSQL_HOST = '127.0.0.1'
        MYSQL_PORT = '13306'
        NACOS_HOST = '127.0.0.1'
        NACOS_CLIENT_PORT = '8848'
        NACOS_USERNAME = $script:nacosUsername
        NACOS_ADMIN_PASSWORD = $env:NACOS_ADMIN_PASSWORD
        IDENTITY_JWT_SECRET = $env:IDENTITY_JWT_SECRET
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
        SERVICE_INSTANCE_ID = "analytics-$($script:suffix)"
        SERVICE_RELEASE_ID = 'm8-analytics'
        OTLP_TRACING_EXPORT_ENABLED = 'false'
    }
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:analyticsPort)/actuator/health/liveness" `
        -ProcessName 'analytics'

    Start-Application -Name 'gateway' -Jar $gatewayJar `
        -Port $script:gatewayPort -Environment @{
        GATEWAY_PORT = [string]$script:gatewayPort
        NACOS_HOST = '127.0.0.1'
        NACOS_CLIENT_PORT = '8848'
        NACOS_USERNAME = $script:nacosUsername
        NACOS_ADMIN_PASSWORD = $env:NACOS_ADMIN_PASSWORD
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = '16379'
        REDIS_PASSWORD = $env:REDIS_PASSWORD
        GATEWAY_RATE_LIMIT_REDIS_ENABLED = 'false'
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
    }
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:gatewayPort)/actuator/health/liveness" `
        -ProcessName 'gateway'
    Wait-Until -Description 'analytics Gateway route' -TimeoutSeconds 90 -Condition {
        try {
            $route = Invoke-WebRequest `
                -Uri "http://127.0.0.1:$($script:gatewayPort)/api/v1/analytics/status" `
                -SkipHttpErrorCheck `
                -TimeoutSec 3
            return $route.StatusCode -eq 200
        }
        catch {
            return $false
        }
    }

    $adminToken = New-AccessToken -UserId 890000000000000101 -Roles @('ADMIN')
    $warehouseToken = New-AccessToken -UserId 890000000000000102 -Roles @('WAREHOUSE')
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $warehouseHeaders = @{ Authorization = "Bearer $warehouseToken" }
    $gatewayBase = "http://127.0.0.1:$($script:gatewayPort)"

    $orderCreatedOne = New-Envelope `
        -EventId ([Guid]::NewGuid().ToString()) `
        -EventType 'OrderCreated' `
        -Producer 'trade-service' `
        -AggregateType 'TradeOrder' `
        -AggregateId "ORD-AN-$($script:suffix)-1" `
        -AggregateVersion 0 `
        -Payload ([ordered]@{
            orderNo = "ORD-AN-$($script:suffix)-1"
            userId = 890000000000000001
            totalAmount = 100.00
        })
    Send-DomainEvent -Topic $script:tradeTopic -Tag 'OrderCreated' `
        -Envelope $orderCreatedOne
    Send-DomainEvent -Topic $script:tradeTopic -Tag 'OrderCreated' `
        -Envelope $orderCreatedOne

    Send-DomainEvent -Topic $script:paymentTopic -Tag 'PaymentSucceeded' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'PaymentSucceeded' `
            -Producer 'payment-service' `
            -AggregateType 'PaymentOrder' `
            -AggregateId "PAY-AN-$($script:suffix)-1" `
            -AggregateVersion 1 `
            -Payload ([ordered]@{
                paymentNo = "PAY-AN-$($script:suffix)-1"
                orderNo = "ORD-AN-$($script:suffix)-1"
                userId = 890000000000000001
                amount = 100.00
            }))

    Send-DomainEvent -Topic $script:tradeTopic -Tag 'OrderCompleted' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'OrderCompleted' `
            -Producer 'trade-service' `
            -AggregateType 'TradeOrder' `
            -AggregateId "ORD-AN-$($script:suffix)-1" `
            -AggregateVersion 4 `
            -Payload ([ordered]@{
                orderNo = "ORD-AN-$($script:suffix)-1"
                userId = 890000000000000001
                totalAmount = 100.00
                items = @(
                    [ordered]@{
                        lineNo = 1
                        productId = 890000000000001001
                        skuId = 890000000000001101
                        productTitle = 'Plain Journal linen organizer'
                        skuCode = "AN-$($script:suffix)-1"
                        quantity = 2
                        payableAmount = 60.00
                    },
                    [ordered]@{
                        lineNo = 2
                        productId = 890000000000001002
                        skuId = 890000000000001102
                        productTitle = 'Plain Journal legacy cup'
                        skuCode = "AN-$($script:suffix)-2"
                        quantity = 1
                    }
                )
            }))

    Send-DomainEvent -Topic $script:tradeTopic -Tag 'AfterSaleApplied' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'AfterSaleApplied' `
            -Producer 'trade-service' `
            -AggregateType 'AfterSaleOrder' `
            -AggregateId "AS-AN-$($script:suffix)-1" `
            -AggregateVersion 0 `
            -Payload ([ordered]@{
                afterSaleNo = "AS-AN-$($script:suffix)-1"
                orderNo = "ORD-AN-$($script:suffix)-1"
                userId = 890000000000000001
                refundAmount = 100.00
            }))

    Send-DomainEvent -Topic $script:paymentTopic -Tag 'RefundSucceeded' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'RefundSucceeded' `
            -Producer 'payment-service' `
            -AggregateType 'RefundOrder' `
            -AggregateId "REF-AN-$($script:suffix)-1" `
            -AggregateVersion 1 `
            -Payload ([ordered]@{
                refundNo = "REF-AN-$($script:suffix)-1"
                orderNo = "ORD-AN-$($script:suffix)-1"
                userId = 890000000000000001
                amount = 100.00
            }))

    Send-DomainEvent -Topic $script:tradeTopic -Tag 'OrderCreated' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'OrderCreated' `
            -Producer 'trade-service' `
            -AggregateType 'TradeOrder' `
            -AggregateId "ORD-AN-$($script:suffix)-2" `
            -AggregateVersion 0 `
            -Payload ([ordered]@{
                orderNo = "ORD-AN-$($script:suffix)-2"
                userId = 890000000000000002
                totalAmount = 20.00
            }))

    Wait-SourceEventCount -Expected 6
    if ((Get-AnalyticsScalar -Sql @"
SELECT COUNT(*) FROM analytics_source_event
WHERE event_id = '$($orderCreatedOne.eventId)';
"@) -ne '1') {
        throw 'Duplicate OrderCreated messages did not converge to one source event.'
    }

    docker stop -t 10 plainjournal-rocketmq-proxy | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to stop RocketMQ Proxy for fault injection.'
    }
    $script:proxyStopped = $true
    Write-VerificationTrace 'RocketMQ Proxy stopped for analytics recovery verification'

    Send-DomainEvent -Topic $script:tradeTopic -Tag 'OrderClosed' `
        -Envelope (New-Envelope `
            -EventId ([Guid]::NewGuid().ToString()) `
            -EventType 'OrderClosed' `
            -Producer 'trade-service' `
            -AggregateType 'TradeOrder' `
            -AggregateId "ORD-AN-$($script:suffix)-2" `
            -AggregateVersion 1 `
            -Payload ([ordered]@{
                orderNo = "ORD-AN-$($script:suffix)-2"
                userId = 890000000000000002
                totalAmount = 20.00
            }))
    Start-Sleep -Seconds 3
    if ((Get-AnalyticsScalar -Sql 'SELECT COUNT(*) FROM analytics_source_event;') -ne '6') {
        throw 'Analytics consumed an event while the RocketMQ Proxy was unavailable.'
    }
    Ensure-ProxyRunning
    Wait-SourceEventCount -Expected 7 -TimeoutSeconds 120

    $anonymous = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/api/v1/analytics/overview?from=2026-07-24&to=2026-07-24"
    $warehouse = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/api/v1/analytics/overview?from=2026-07-24&to=2026-07-24" `
        -Headers $warehouseHeaders
    $overview = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/api/v1/analytics/overview?from=2026-07-24&to=2026-07-24&productLimit=10" `
        -Headers $adminHeaders
    if ($anonymous.status -ne 401 -or $warehouse.status -ne 403 -or
        $overview.status -ne 200) {
        throw 'Analytics authorization or Gateway routing boundary failed.'
    }
    $totals = $overview.body.data.totals
    if ([long]$totals.createdOrderCount -ne 2 -or
        [decimal]$totals.createdOrderAmount -ne 120.00 -or
        [long]$totals.paymentCount -ne 1 -or
        [long]$totals.completedOrderCount -ne 1 -or
        [long]$totals.closedOrderCount -ne 1 -or
        [long]$totals.afterSaleCount -ne 1 -or
        [long]$totals.refundCount -ne 1 -or
        [long]$totals.uniqueCustomers -ne 2) {
        throw 'Analytics overview totals are incorrect.'
    }
    $products = @($overview.body.data.topProducts)
    $covered = @($products | Where-Object productId -eq 890000000000001001)[0]
    $legacy = @($products | Where-Object productId -eq 890000000000001002)[0]
    if ([decimal]$covered.netRevenue -ne 60.00 -or
        [long]$covered.revenueCoveredOrderCount -ne 1 -or
        [decimal]$legacy.netRevenue -ne 0.00 -or
        [long]$legacy.revenueCoveredOrderCount -ne 0) {
        throw 'Product revenue coverage did not preserve the legacy-event boundary.'
    }

    Invoke-AnalyticsSql -Sql @"
UPDATE analytics_daily_summary
SET created_order_count = 99
WHERE business_date = '2026-07-24';
DELETE FROM analytics_product_summary
WHERE business_date = '2026-07-24'
  AND product_id = 890000000000001001;
INSERT INTO analytics_product_summary
    (business_date, product_id, product_title, completed_order_count,
     units_sold, net_revenue, revenue_covered_order_count, updated_at)
VALUES
    ('2026-07-24', 890000000000009999, 'Injected orphan',
     1, 1, 1.00, 1, UTC_TIMESTAMP(3));
"@ | Out-Null

    $reconciliation = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/api/v1/analytics/admin/reconciliation?from=2026-07-24&to=2026-07-24" `
        -Headers $adminHeaders
    if ($reconciliation.status -ne 200 -or
        [long]$reconciliation.body.data.issueCount -ne 3 -or
        [bool]$reconciliation.body.data.saturated) {
        throw 'Analytics reconciliation did not identify exactly three injected issues.'
    }
    $issueTypes = @($reconciliation.body.data.issues |
        ForEach-Object { "$($_.projection):$($_.issueType)" } |
        Sort-Object)
    if (($issueTypes -join ',') -ne 'DAILY:STALE,PRODUCT:MISSING,PRODUCT:ORPHAN') {
        throw "Unexpected analytics reconciliation issues: $($issueTypes -join ',')"
    }

    $rebuildBody = @{
        commandId = "rebuild-$($script:suffix)"
        reason = 'Repair the three deliberately injected analytics projection issues.'
        from = '2026-07-24'
        to = '2026-07-24'
    }
    $rebuild = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/api/v1/analytics/admin/rebuild" `
        -Headers $adminHeaders `
        -Body $rebuildBody
    $rebuildAgain = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/api/v1/analytics/admin/rebuild" `
        -Headers $adminHeaders `
        -Body $rebuildBody
    if ($rebuild.status -ne 200 -or $rebuildAgain.status -ne 200 -or
        [long]$rebuild.body.data.beforeIssueCount -ne 3 -or
        [long]$rebuild.body.data.afterIssueCount -ne 0 -or
        [string]$rebuild.body.data.createdAt -ne
            [string]$rebuildAgain.body.data.createdAt) {
        throw 'Audited analytics rebuild did not converge idempotently.'
    }
    $conflictBody = @{
        commandId = $rebuildBody.commandId
        reason = 'This intentionally conflicts with the prior rebuild command payload.'
        from = '2026-07-24'
        to = '2026-07-24'
    }
    $conflict = Invoke-JsonRequest -Method Post `
        -Uri "$gatewayBase/api/v1/analytics/admin/rebuild" `
        -Headers $adminHeaders `
        -Body $conflictBody
    if ($conflict.status -ne 409) {
        throw 'Reused analytics rebuild command id did not return HTTP 409.'
    }

    $finalReconciliation = Invoke-JsonRequest -Method Get `
        -Uri "$gatewayBase/api/v1/analytics/admin/reconciliation?from=2026-07-24&to=2026-07-24" `
        -Headers $adminHeaders
    if ([long]$finalReconciliation.body.data.issueCount -ne 0 -or
        (Get-AnalyticsScalar -Sql 'SELECT COUNT(*) FROM analytics_rebuild_audit;') -ne '1' -or
        (Get-AnalyticsScalar -Sql 'SELECT COUNT(*) FROM consumer_failure;') -ne '0') {
        throw 'Analytics rebuild left unresolved projection or consumer-failure facts.'
    }

    $metrics = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$($script:analyticsPort)/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
    if ($metrics.StatusCode -ne 200 -or
        $metrics.Content -notmatch 'ecommerce_analytics_events_total' -or
        $metrics.Content -notmatch 'ecommerce_analytics_rebuilds_total' -or
        $metrics.Content -notmatch 'ecommerce_analytics_reconciliation_issues') {
        throw 'Analytics Prometheus export omitted required bounded metrics.'
    }

    $script:verification.gatewayAndAuthorization = [ordered]@{
        statusRoute = 200
        anonymousOverview = $anonymous.status
        warehouseOverview = $warehouse.status
        adminOverview = $overview.status
    }
    $script:verification.eventProjection = [ordered]@{
        sentMessages = 8
        persistedSourceEvents = 7
        duplicateOrderCreatedRows = 1
        createdOrders = [long]$totals.createdOrderCount
        createdAmount = [decimal]$totals.createdOrderAmount
        paymentCount = [long]$totals.paymentCount
        completedCount = [long]$totals.completedOrderCount
        closedCount = [long]$totals.closedOrderCount
        afterSaleCount = [long]$totals.afterSaleCount
        refundCount = [long]$totals.refundCount
        uniqueCustomers = [long]$totals.uniqueCustomers
    }
    $script:verification.productRevenueCoverage = [ordered]@{
        coveredProductRevenue = [decimal]$covered.netRevenue
        coveredProductOrders = [long]$covered.revenueCoveredOrderCount
        legacyProductRevenue = [decimal]$legacy.netRevenue
        legacyProductCoveredOrders = [long]$legacy.revenueCoveredOrderCount
    }
    $script:verification.proxyRecovery = [ordered]@{
        sourceEventsDuringOutage = 6
        sourceEventsAfterRecovery = 7
    }
    $script:verification.reconciliationAndRebuild = [ordered]@{
        injectedIssues = 3
        issueTypes = $issueTypes
        beforeIssueCount = [long]$rebuild.body.data.beforeIssueCount
        afterIssueCount = [long]$rebuild.body.data.afterIssueCount
        repeatedCommandCreatedAt = [string]$rebuildAgain.body.data.createdAt
        conflictingCommandStatus = $conflict.status
        rebuildAuditRows = 1
    }
    $script:verification.metrics = [ordered]@{
        authenticatedPrometheusStatus = $metrics.StatusCode
        analyticsEvents = $true
        analyticsRebuilds = $true
        analyticsReconciliationIssues = $true
    }
    $script:verification.completedAt = [DateTimeOffset]::Now
    Write-VerificationTrace 'all analytics verification stages passed'
}
catch {
    $failure = $_
    $script:failureContext.message = $_.Exception.Message
    $script:failureContext.stack = $_.ScriptStackTrace
    Write-VerificationTrace "verification failed: $($_.Exception.Message)"
}
finally {
    try {
        Stop-ManagedProcesses
    }
    catch {
        $cleanupErrors.Add("process cleanup: $($_.Exception.Message)")
    }
    try {
        Ensure-ProxyRunning
    }
    catch {
        $cleanupErrors.Add("RocketMQ Proxy recovery: $($_.Exception.Message)")
    }
    try {
        foreach ($errorText in @(Remove-RocketMqResources)) {
            $cleanupErrors.Add("RocketMQ cleanup: $errorText")
        }
    }
    catch {
        $cleanupErrors.Add("RocketMQ cleanup: $($_.Exception.Message)")
    }
    try {
        if ($script:databaseCreated -or $script:userCreated) {
            Remove-VerificationDatabase
        }
    }
    catch {
        $cleanupErrors.Add("database cleanup: $($_.Exception.Message)")
    }
    try {
        Wait-Until -Description 'analytics verification application ports to close' `
            -TimeoutSeconds 20 `
            -Condition {
            $listeners = @(Get-NetTCPConnection -State Listen `
                    -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.LocalPort -in @($script:gatewayPort, $script:analyticsPort)
                })
            return $listeners.Count -eq 0
        }
    }
    catch {
        $cleanupErrors.Add("port cleanup: $($_.Exception.Message)")
    }

    try {
        $residual = Get-ResidualState
        foreach ($entry in $residual.GetEnumerator()) {
            $script:cleanup[$entry.Key] = $entry.Value
        }
        if ($residual.residualDatabaseSchemas -ne 0) {
            $cleanupErrors.Add(
                "residual analytics database schemas: " +
                $residual.residualDatabaseSchemas)
        }
        if ($residual.residualDatabaseUsers -ne 0) {
            $cleanupErrors.Add(
                "residual analytics database users: " +
                $residual.residualDatabaseUsers)
        }
        if ($residual.residualApplicationPorts -ne 0) {
            $cleanupErrors.Add(
                "residual analytics application ports: " +
                $residual.residualApplicationPorts)
        }
        if ($residual.residualApplicationJvms -ne 0) {
            $cleanupErrors.Add(
                "residual analytics JVMs: " +
                $residual.residualApplicationJvms)
        }
        if ($residual.residualConsumerGroups.Count -ne 0) {
            $cleanupErrors.Add(
                "residual analytics RocketMQ consumer groups: " +
                ($residual.residualConsumerGroups -join ', '))
        }
        if ($residual.residualTopics.Count -ne 0) {
            $cleanupErrors.Add(
                "residual analytics RocketMQ topics: " +
                ($residual.residualTopics -join ', '))
        }
        if ($residual.coreContainersRunning -ne 7) {
            $cleanupErrors.Add(
                "expected seven core containers after cleanup, found " +
                $residual.coreContainersRunning)
        }
    }
    catch {
        $cleanupErrors.Add("residual inspection: $($_.Exception.Message)")
    }
    $script:cleanup.cleanupErrors = @($cleanupErrors)
    $script:cleanup.completedAt = [DateTimeOffset]::Now

    $verificationDocument = [ordered]@{
        runId = $script:runId
        evidenceDate = $EvidenceDate
        startedAt = $startedAt
        succeeded = $null -eq $failure
        verification = $script:verification
        failure = $script:failureContext
    }
    [IO.File]::WriteAllText(
        (Join-Path $script:runDirectory 'verification.json'),
        ($verificationDocument | ConvertTo-Json -Depth 20),
        [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText(
        (Join-Path $script:runDirectory 'cleanup.json'),
        ($script:cleanup | ConvertTo-Json -Depth 20),
        [Text.UTF8Encoding]::new($false))
}

if ($null -ne $failure) {
    throw $failure
}
if ($cleanupErrors.Count -gt 0) {
    throw "Verification passed but cleanup failed: $($cleanupErrors -join '; ')"
}

Write-Output (Join-Path $script:runDirectory 'verification.json')
