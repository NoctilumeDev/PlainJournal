#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipPackage,
    [switch]$SkipFrontendBuild,
    [string]$ManualInspectionReadyFile,
    [string]$ManualInspectionContinueFile,
    [ValidateRange(60, 1800)][int]$ManualInspectionTimeoutSeconds = 900,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:frontendRoot = Join-Path $script:repositoryRoot 'frontend'
$script:runId = "m8ui$(([Guid]::NewGuid().ToString('N')).Substring(0, 12))"
$script:chatNodeId = "$($script:runId)-chat"
$script:dispatcherGroup = "ecommerce-chat-dispatcher-$($script:runId)"
$script:deliveryGroupPrefix = "chat-delivery-$($script:runId)"
$script:rocketMqConsumerGroups = @(
    $script:dispatcherGroup
    "$($script:deliveryGroupPrefix)-$($script:chatNodeId)"
)
$script:processes = [ordered]@{}
$script:databaseReady = $false
$script:customerId = $null
$script:agentId = $null
$script:conversationId = $null
$script:preexistingConsumerFailureRows = 0L
$script:ports = [ordered]@{
    gateway = 18000
    identity = 18101
    chat = 18108
    storefront = 18200
    admin = 18201
}
$script:customerEmail = "$($script:runId)-customer@example.invalid"
$script:agentEmail = "$($script:runId)-agent@example.invalid"
$script:customerPassword = 'M8CustomerPass123'
$script:agentPassword = 'M8AgentPass123'
$script:customerContent = "M8.6 customer response-drop recovery $($script:runId)"
$script:agentContent = "M8.6 agent realtime reply $($script:runId)"

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        [Environment]::SetEnvironmentVariable(
            $trimmed.Substring(0, $separator).Trim(),
            $trimmed.Substring($separator + 1).Trim(),
            'Process')
    }
}

function Assert-RequiredEnvironment {
    $required = @(
        'IDENTITY_DB_NAME',
        'IDENTITY_DB_USER',
        'IDENTITY_DB_PASSWORD',
        'CHAT_DB_NAME',
        'CHAT_DB_USER',
        'CHAT_DB_PASSWORD',
        'IDENTITY_JWT_SECRET',
        'NACOS_ADMIN_PASSWORD',
        'REDIS_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
        })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
    }
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by PID $($listener[0].OwningProcess)."
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
        if ($script:processes.Contains($ProcessName)) {
            $managed = $script:processes[$ProcessName].process
            if ($managed.HasExited) {
                throw "$ProcessName exited before $Uri became ready. ExitCode=$($managed.ExitCode)"
            }
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
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][string]$Description,
        [int]$TimeoutSeconds = 45,
        [int]$IntervalMilliseconds = 500
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Start-ManagedProcess {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][hashtable]$Environment,
        [Parameter(Mandatory)][string]$CommandMarker,
        [Parameter(Mandatory)][int]$Port
    )

    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] = [Environment]::GetEnvironmentVariable(
            $entry.Key,
            'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
    }
    try {
        $process = Start-Process -FilePath $FilePath `
            -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            marker = $CommandMarker
            port = $Port
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Start-JavaApplication {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment,
        [Parameter(Mandatory)][int]$Port
    )

    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
        throw "Missing application artifact: $Jar"
    }
    Start-ManagedProcess -Name $Name -FilePath $script:javaPath `
        -Arguments @(
            '-Xms128m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-jar',
            $Jar
        ) `
        -WorkingDirectory $script:backendRoot `
        -Environment $Environment `
        -CommandMarker ([IO.Path]::GetFileName($Jar)) `
        -Port $Port
}

function Start-ViteApplication {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][int]$Port
    )

    Start-ManagedProcess -Name $Name -FilePath $script:nodePath `
        -Arguments @(
            $script:viteScript,
            '--host',
            '127.0.0.1',
            '--port',
            [string]$Port
        ) `
        -WorkingDirectory $WorkingDirectory `
        -Environment @{
            VITE_API_PROXY_TARGET = "http://127.0.0.1:$($script:ports.gateway)"
        } `
        -CommandMarker 'vite.js' `
        -Port $Port
}

function Stop-VerifiedApplicationProcess {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Marker
    )

    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" `
        -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return
    }
    if ($process.CommandLine -notlike "*$Marker*" -or
            $process.CommandLine -notlike "*$($script:repositoryRoot)*") {
        throw "Refused to stop PID $ProcessId; command line no longer matches $Name."
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction Stop
    Wait-Process -Id $ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        throw "Managed process did not exit: $Name/$ProcessId"
    }
}

function Stop-ManagedProcess {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $script:processes.Contains($Name)) {
        return
    }
    $managed = $script:processes[$Name]
    Stop-VerifiedApplicationProcess `
        -ProcessId ([int]$managed.process.Id) `
        -Name $Name `
        -Marker $managed.marker

    $deadline = (Get-Date).AddSeconds(10)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $managed.port `
                -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            Stop-VerifiedApplicationProcess `
                -ProcessId ([int]$listener.OwningProcess) `
                -Name $Name `
                -Marker $managed.marker
        }
        if ($listeners.Count -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    if (Get-NetTCPConnection -State Listen -LocalPort $managed.port `
            -ErrorAction SilentlyContinue) {
        throw "Application port remained active after cleanup: $Name/$($managed.port)"
    }
    $script:processes.Remove($Name)
}

function Stop-AllApplications {
    $names = @($script:processes.Keys)
    [Array]::Reverse($names)
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($name in $names) {
        try {
            Stop-ManagedProcess -Name $name
        }
        catch {
            $failures.Add("$name`: $($_.Exception.Message)")
        }
    }
    if ($failures.Count -gt 0) {
        throw "Application cleanup failed: $($failures -join '; ')"
    }
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

function Get-RocketMqTopics {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list RocketMQ topics: $($output -join "`n")"
    }
    return @($output | ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-VerificationRocketMqArtifactTopics {
    $groups = @($script:rocketMqConsumerGroups)
    return @(Get-RocketMqTopics | Where-Object {
            $topicName = $_
            ($topicName.StartsWith('%RETRY%') -or
                $topicName.StartsWith('%DLQ%')) -and
            @($groups | Where-Object {
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

function Remove-VerificationRocketMqConsumerGroups {
    foreach ($consumerGroup in $script:rocketMqConsumerGroups) {
        Remove-RocketMqConsumerGroup -ConsumerGroup $consumerGroup
    }
    foreach ($topicName in @(Get-VerificationRocketMqArtifactTopics)) {
        $artifactGroup = $topicName -replace '^%(?:RETRY|DLQ)%', ''
        Remove-RocketMqConsumerGroup -ConsumerGroup $artifactGroup
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topicName 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            throw "Unable to delete RocketMQ artifact topic ${topicName}: $($output -join "`n")"
        }
    }
}

function Get-ResidualRocketMqConsumerGroups {
    return @($script:rocketMqConsumerGroups | Where-Object {
            Test-RocketMqConsumerGroupResidual -ConsumerGroup $_
        })
}

function Get-ResidualRocketMqTopics {
    return @(Get-VerificationRocketMqArtifactTopics)
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory)][ValidateSet('identity', 'chat')][string]$Target,
        [Parameter(Mandatory)][string]$Sql
    )

    $user = if ($Target -eq 'identity') {
        $env:IDENTITY_DB_USER
    }
    else {
        $env:CHAT_DB_USER
    }
    $password = if ($Target -eq 'identity') {
        $env:IDENTITY_DB_PASSWORD
    }
    else {
        $env:CHAT_DB_PASSWORD
    }
    $database = if ($Target -eq 'identity') {
        $env:IDENTITY_DB_NAME
    }
    else {
        $env:CHAT_DB_NAME
    }
    $output = $Sql | docker exec -i -e "MYSQL_PWD=$password" plainjournal-mysql `
        mysql "-u$user" $database -N -B
    if ($LASTEXITCODE -ne 0) {
        throw "$Target MySQL command failed."
    }
    return $output
}

function Get-MySqlScalar {
    param(
        [Parameter(Mandatory)][ValidateSet('identity', 'chat')][string]$Target,
        [Parameter(Mandatory)][string]$Sql
    )

    $lines = @(Invoke-MySql -Target $Target -Sql $Sql)
    if ($lines.Count -ne 1) {
        throw "Expected one scalar row from $Target MySQL, got $($lines.Count)."
    }
    return $lines[0]
}

function ConvertTo-MySqlLiteral {
    param([Parameter(Mandatory)][string]$Value)

    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function Get-ScopedConsumerFailureCount {
    if (-not $script:databaseReady) {
        return 0L
    }
    $dispatcherGroup = ConvertTo-MySqlLiteral -Value $script:dispatcherGroup
    $deliveryGroupPattern = ConvertTo-MySqlLiteral `
        -Value "$($script:deliveryGroupPrefix)-%"
    return [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group = $dispatcherGroup
   OR consumer_group LIKE $deliveryGroupPattern;
"@)
}

function Get-CurrentConversationConsumerFailureCount {
    if (-not $script:databaseReady -or $null -eq $script:conversationId) {
        return 0L
    }
    $dispatcherGroup = ConvertTo-MySqlLiteral -Value $script:dispatcherGroup
    $deliveryGroupPattern = ConvertTo-MySqlLiteral `
        -Value "$($script:deliveryGroupPrefix)-%"
    return [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM consumer_failure
WHERE (
        consumer_group = $dispatcherGroup
        OR consumer_group LIKE $deliveryGroupPattern
      )
  AND JSON_VALID(raw_payload)
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId')) IN (
      SELECT CAST(id AS CHAR)
      FROM chat_message
      WHERE conversation_id = $($script:conversationId)
  );
"@)
}

function Clear-ScopedConsumerFailures {
    if (-not $script:databaseReady) {
        return
    }
    $dispatcherGroup = ConvertTo-MySqlLiteral -Value $script:dispatcherGroup
    $deliveryGroupPattern = ConvertTo-MySqlLiteral `
        -Value "$($script:deliveryGroupPrefix)-%"
    Invoke-MySql -Target chat -Sql @"
DELETE FROM consumer_failure
WHERE consumer_group = $dispatcherGroup
   OR consumer_group LIKE $deliveryGroupPattern;
"@ | Out-Null
}

function Clear-ChatData {
    if (-not $script:databaseReady -or $null -eq $script:customerId) {
        return
    }
    $customerId = [long]$script:customerId
    Invoke-MySql -Target chat -Sql @"
DELETE a
FROM chat_attachment a
JOIN chat_message m ON m.id = a.message_id
JOIN chat_conversation c ON c.id = m.conversation_id
WHERE c.customer_id = $customerId;
DELETE u
FROM chat_attachment_upload u
JOIN chat_conversation c ON c.id = u.conversation_id
WHERE c.customer_id = $customerId;
DELETE r
FROM message_receipt r
JOIN chat_message m ON m.id = r.message_id
JOIN chat_conversation c ON c.id = m.conversation_id
WHERE c.customer_id = $customerId;
DELETE FROM outbox_event
WHERE aggregate_id IN (
    SELECT conversation_no
    FROM chat_conversation
    WHERE customer_id = $customerId
);
DELETE m
FROM chat_message m
JOIN chat_conversation c ON c.id = m.conversation_id
WHERE c.customer_id = $customerId;
DELETE cm
FROM conversation_member cm
JOIN chat_conversation c ON c.id = cm.conversation_id
WHERE c.customer_id = $customerId;
DELETE FROM chat_conversation WHERE customer_id = $customerId;
"@ | Out-Null
}

function Clear-IdentityData {
    if (-not $script:databaseReady) {
        return
    }
    $customerEmail = ConvertTo-MySqlLiteral -Value $script:customerEmail
    $agentEmail = ConvertTo-MySqlLiteral -Value $script:agentEmail
    Invoke-MySql -Target identity -Sql @"
DELETE rt
FROM refresh_token rt
JOIN user_account u ON u.id = rt.user_id
WHERE u.email IN ($customerEmail, $agentEmail);
DELETE FROM login_record
WHERE normalized_email IN ($customerEmail, $agentEmail);
DELETE ur
FROM user_role ur
JOIN user_account u ON u.id = ur.user_id
WHERE u.email IN ($customerEmail, $agentEmail);
DELETE FROM user_account
WHERE email IN ($customerEmail, $agentEmail);
"@ | Out-Null
}

function Invoke-Redis {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = docker exec plainjournal-redis redis-cli --no-auth-warning `
        -a $env:REDIS_PASSWORD @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis command failed.'
    }
    return $output
}

function Clear-RunRedis {
    if (-not $script:databaseReady) {
        return
    }
    $keys = @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "ecommerce:$($script:runId):*"
        ) | Where-Object { $_ })
    foreach ($key in $keys) {
        Invoke-Redis -Arguments @('DEL', [string]$key) | Out-Null
    }
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)]$Body
    )

    return Invoke-RestMethod -Method Post -Uri $Uri `
        -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Depth 8 -Compress) `
        -TimeoutSec 20
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$script:runDirectory = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
}
else {
    Join-Path $script:backendRoot ".run/m8-chat-frontend-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null
$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$identityJar = Join-Path $script:backendRoot `
    'services/identity-service/target/identity-service.jar'
$chatJar = Join-Path $script:backendRoot `
    'services/chat-service/target/chat-service.jar'
$gatewayJar = Join-Path $script:backendRoot `
    'ecommerce-gateway/target/ecommerce-gateway.jar'
$browserScript = Join-Path $script:frontendRoot 'e2e/real-chat-workspace.mjs'
$browserResultPath = Join-Path $script:runDirectory 'browser-result.json'
$screenshotDirectory = Join-Path $script:runDirectory 'screenshots'
$script:nodePath = (Get-Command node -ErrorAction Stop).Source
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$javaHomeExecutable = if ($javaHome) {
    Join-Path $javaHome 'bin/java.exe'
}
else {
    $null
}
$script:javaPath = if ($javaHomeExecutable -and
        (Test-Path -LiteralPath $javaHomeExecutable -PathType Leaf)) {
    (Resolve-Path -LiteralPath $javaHomeExecutable).Path
}
else {
    (Get-Command java -ErrorAction Stop).Source
}
$viteCandidates = @(Get-ChildItem `
        -Path (Join-Path $script:frontendRoot 'node_modules/.pnpm') `
        -Filter vite.js `
        -Recurse `
        -File `
        -ErrorAction Stop |
        Where-Object {
            $_.FullName -match '[\\/]node_modules[\\/]vite[\\/]bin[\\/]vite\.js$'
        })
if ($viteCandidates.Count -ne 1) {
    throw "Expected one Vite entry script, found $($viteCandidates.Count)."
}
$script:viteScript = $viteCandidates[0].FullName
$startedAtUtc = [DateTimeOffset]::UtcNow

try {
    if (-not $SkipNetworkPreflight) {
        if (-not (Test-Path -LiteralPath $networkPreflight -PathType Leaf)) {
            throw "Network preflight script not found: $networkPreflight"
        }
        & $networkPreflight
        if ($LASTEXITCODE -ne 0) {
            throw 'Network preflight failed.'
        }
    }

    & $bootstrapPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Local resource bootstrap failed.'
    }
    Import-DotEnv -Path $envPath
    Assert-RequiredEnvironment
    $script:databaseReady = $true
    foreach ($port in $script:ports.Values) {
        Assert-PortAvailable -Port $port
    }
    $script:preexistingConsumerFailureRows = Get-ScopedConsumerFailureCount
    Clear-ScopedConsumerFailures
    Assert-Equal -Actual (Get-ScopedConsumerFailureCount) -Expected 0L `
        -Message 'M8.6 verification could not clear its scoped failure rows.'
    Clear-RunRedis
    Clear-IdentityData

    if (-not $SkipPackage) {
        Push-Location $script:backendRoot
        try {
            & mvn '-pl' `
                'ecommerce-gateway,services/identity-service,services/chat-service' `
                '-am' '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven packaging failed.'
            }
        }
        finally {
            Pop-Location
        }
    }
    if (-not $SkipFrontendBuild) {
        Push-Location $script:frontendRoot
        try {
            & pnpm build
            if ($LASTEXITCODE -ne 0) {
                throw 'Frontend production build failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    Start-JavaApplication -Name 'identity' -Jar $identityJar -Port $script:ports.identity `
        -Environment @{
            APP_ENV = $script:runId
            IDENTITY_SERVICE_PORT = [string]$script:ports.identity
            SERVICE_INSTANCE_ID = "$($script:runId)-identity"
            SERVICE_RELEASE_ID = 'm8-chat-frontend-v1'
        }
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:ports.identity)/actuator/health/liveness" `
        -ProcessName 'identity'

    Start-JavaApplication -Name 'chat' -Jar $chatJar -Port $script:ports.chat `
        -Environment @{
            APP_ENV = $script:runId
            CHAT_SERVICE_PORT = [string]$script:ports.chat
            SERVICE_INSTANCE_ID = $script:chatNodeId
            SERVICE_RELEASE_ID = 'm8-chat-frontend-v1'
            CHAT_ATTACHMENT_CLEANUP_ENABLED = 'false'
            CHAT_OUTBOX_INITIAL_DELAY = '500'
            CHAT_OUTBOX_FIXED_DELAY = '300'
            CHAT_REALTIME_INITIAL_DELAY = '500'
            CHAT_REALTIME_FIXED_DELAY = '250'
            CHAT_REALTIME_AWAIT_DURATION = '5s'
            CHAT_DISPATCHER_CONSUMER_GROUP = $script:dispatcherGroup
            CHAT_DELIVERY_CONSUMER_GROUP_PREFIX = $script:deliveryGroupPrefix
            CHAT_PRESENCE_TTL = '8s'
            CHAT_PRESENCE_REFRESH_INTERVAL = '2s'
            ROCKETMQ_ENDPOINTS = '127.0.0.1:18082'
        }
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:ports.chat)/actuator/health/liveness" `
        -ProcessName 'chat'

    Start-JavaApplication -Name 'gateway' -Jar $gatewayJar -Port $script:ports.gateway `
        -Environment @{
            APP_ENV = $script:runId
            GATEWAY_PORT = [string]$script:ports.gateway
            SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
            SERVICE_RELEASE_ID = 'm8-chat-frontend-v1'
        }
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:ports.gateway)/actuator/health/liveness" `
        -ProcessName 'gateway'
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:ports.gateway)/api/v1/identity/status" `
        -ProcessName 'gateway'
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:ports.gateway)/api/v1/chat/status" `
        -ProcessName 'gateway'

    $identityBaseUrl = "http://127.0.0.1:$($script:ports.gateway)/api/v1/identity"
    Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
        email = $script:customerEmail
        password = $script:customerPassword
        displayName = 'M8 Customer'
    } | Out-Null
    Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
        email = $script:agentEmail
        password = $script:agentPassword
        displayName = 'M8 Agent'
    } | Out-Null

    $customerEmailLiteral = ConvertTo-MySqlLiteral -Value $script:customerEmail
    $agentEmailLiteral = ConvertTo-MySqlLiteral -Value $script:agentEmail
    $script:customerId = [long](Get-MySqlScalar -Target identity `
            -Sql "SELECT id FROM user_account WHERE email = $customerEmailLiteral;")
    $script:agentId = [long](Get-MySqlScalar -Target identity `
            -Sql "SELECT id FROM user_account WHERE email = $agentEmailLiteral;")
    Invoke-MySql -Target identity -Sql @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT $($script:agentId), id, CURRENT_TIMESTAMP(3)
FROM identity_role
WHERE code = 'ADMIN';
"@ | Out-Null

    Start-ViteApplication -Name 'storefront' `
        -WorkingDirectory (Join-Path $script:frontendRoot 'storefront-web') `
        -Port $script:ports.storefront
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:ports.storefront)/login" `
        -ProcessName 'storefront'
    Start-ViteApplication -Name 'admin' `
        -WorkingDirectory (Join-Path $script:frontendRoot 'admin-web') `
        -Port $script:ports.admin
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:ports.admin)/login" `
        -ProcessName 'admin'

    if (-not [string]::IsNullOrWhiteSpace($ManualInspectionReadyFile)) {
        if ([string]::IsNullOrWhiteSpace($ManualInspectionContinueFile)) {
            throw 'ManualInspectionContinueFile is required with ManualInspectionReadyFile.'
        }
        $manualInspection = [ordered]@{
            runId = $script:runId
            storefrontUrl = "http://127.0.0.1:$($script:ports.storefront)"
            adminUrl = "http://127.0.0.1:$($script:ports.admin)"
            customerEmail = $script:customerEmail
            customerPassword = $script:customerPassword
            agentEmail = $script:agentEmail
            agentPassword = $script:agentPassword
        }
        $manualInspection | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath $ManualInspectionReadyFile -Encoding utf8NoBOM
        $deadline = (Get-Date).AddSeconds($ManualInspectionTimeoutSeconds)
        do {
            if (Test-Path -LiteralPath $ManualInspectionContinueFile -PathType Leaf) {
                break
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        if (-not (Test-Path -LiteralPath $ManualInspectionContinueFile -PathType Leaf)) {
            throw 'Manual browser inspection timed out.'
        }
    }

    $browserEnvironment = @{
        PJ_CHAT_CUSTOMER_EMAIL = $script:customerEmail
        PJ_CHAT_CUSTOMER_PASSWORD = $script:customerPassword
        PJ_CHAT_AGENT_EMAIL = $script:agentEmail
        PJ_CHAT_AGENT_PASSWORD = $script:agentPassword
        PJ_CHAT_RUN_ID = $script:runId
        PJ_CHAT_BROWSER_RESULT = $browserResultPath
        PJ_CHAT_SCREENSHOT_DIRECTORY = $screenshotDirectory
        PJ_CHAT_STOREFRONT_URL = "http://127.0.0.1:$($script:ports.storefront)"
        PJ_CHAT_ADMIN_URL = "http://127.0.0.1:$($script:ports.admin)"
    }
    $originalBrowserEnvironment = @{}
    foreach ($entry in $browserEnvironment.GetEnumerator()) {
        $originalBrowserEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable(
            $entry.Key,
            'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
    }
    try {
        $nativePreference = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
        try {
            $browserOutput = @(& $script:nodePath $browserScript 2>&1)
            $browserExitCode = $LASTEXITCODE
        }
        finally {
            $PSNativeCommandUseErrorActionPreference = $nativePreference
        }
        $browserOutput | Set-Content `
            -LiteralPath (Join-Path $script:runDirectory 'browser.out.log') `
            -Encoding utf8NoBOM
        $browserOutput | ForEach-Object { Write-Host $_ }
        if ($browserExitCode -ne 0) {
            throw "Real browser verification failed with exit code $browserExitCode."
        }
    }
    finally {
        foreach ($entry in $originalBrowserEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
    if (-not (Test-Path -LiteralPath $browserResultPath -PathType Leaf)) {
        throw 'Real browser verification did not write its result.'
    }
    $browserResult = Get-Content -Raw -LiteralPath $browserResultPath |
        ConvertFrom-Json
    $script:conversationId = [long]$browserResult.conversationId

    Wait-Until -Description 'both Chat outbox events to publish' -Condition {
        [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM outbox_event o
JOIN chat_conversation c ON c.conversation_no = o.aggregate_id
WHERE c.id = $($script:conversationId)
  AND o.status = 'PUBLISHED';
"@) -eq 2
    }

    $conversationCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM chat_conversation
WHERE id = $($script:conversationId)
  AND customer_id = $($script:customerId)
  AND assigned_agent_id = $($script:agentId);
"@)
    $memberCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM conversation_member
WHERE conversation_id = $($script:conversationId);
"@)
    $messageCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM chat_message
WHERE conversation_id = $($script:conversationId);
"@)
    $senderCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(DISTINCT sender_id)
FROM chat_message
WHERE conversation_id = $($script:conversationId);
"@)
    $customerMessageCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM chat_message
WHERE conversation_id = $($script:conversationId)
  AND sender_id = $($script:customerId)
  AND content = $(ConvertTo-MySqlLiteral -Value $script:customerContent);
"@)
    $agentMessageCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM chat_message
WHERE conversation_id = $($script:conversationId)
  AND sender_id = $($script:agentId)
  AND content = $(ConvertTo-MySqlLiteral -Value $script:agentContent);
"@)
    $readMemberCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM conversation_member
WHERE conversation_id = $($script:conversationId)
  AND last_read_message_id IS NOT NULL;
"@)
    $outboxContentLeakCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT COUNT(*)
FROM outbox_event o
JOIN chat_conversation c ON c.conversation_no = o.aggregate_id
WHERE c.id = $($script:conversationId)
  AND (
      o.payload LIKE CONCAT('%', $(ConvertTo-MySqlLiteral -Value $script:customerContent), '%')
      OR o.payload LIKE CONCAT('%', $(ConvertTo-MySqlLiteral -Value $script:agentContent), '%')
  );
"@)
    $attachmentCount = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT
    (SELECT COUNT(*)
     FROM chat_attachment a
     JOIN chat_message m ON m.id = a.message_id
     WHERE m.conversation_id = $($script:conversationId))
  + (SELECT COUNT(*)
     FROM chat_attachment_upload
     WHERE conversation_id = $($script:conversationId));
"@)

    Assert-Equal -Actual $conversationCount -Expected 1L `
        -Message 'Browser flow did not leave one assigned conversation fact.'
    Assert-Equal -Actual $memberCount -Expected 2L `
        -Message 'Conversation membership did not contain customer and claimed agent.'
    Assert-Equal -Actual $messageCount -Expected 2L `
        -Message 'Response-drop recovery duplicated or lost a text message.'
    Assert-Equal -Actual $senderCount -Expected 2L `
        -Message 'The two persisted messages did not belong to both actors.'
    Assert-Equal -Actual $customerMessageCount -Expected 1L `
        -Message 'Customer response-drop recovery was not idempotent.'
    Assert-Equal -Actual $agentMessageCount -Expected 1L `
        -Message 'Agent reply was not persisted exactly once.'
    Assert-Equal -Actual $readMemberCount -Expected 2L `
        -Message 'Both browser workspaces did not advance authoritative read positions.'
    Assert-Equal -Actual $outboxContentLeakCount -Expected 0L `
        -Message 'Chat outbox payload leaked private message content.'
    Assert-Equal -Actual $attachmentCount -Expected 0L `
        -Message 'Text-only M8.6 flow created attachment facts.'
    Assert-True -Condition ([bool]$browserResult.customerReceivedAgentReplyWithoutRefresh) `
        -Message 'Customer browser did not receive the agent reply in real time.'
    Assert-True -Condition ([bool]$browserResult.historyRecoveredAfterReload) `
        -Message 'Customer browser did not recover history after reload.'

    $businessEvidence = [ordered]@{
        conversationId = [string]$script:conversationId
        customerId = [string]$script:customerId
        agentId = [string]$script:agentId
        conversations = $conversationCount
        members = $memberCount
        messages = $messageCount
        distinctSenders = $senderCount
        readMembers = $readMemberCount
        publishedOutboxEvents = 2
        privateContentInOutbox = $outboxContentLeakCount
        attachments = $attachmentCount
    }

    Stop-AllApplications
    Remove-VerificationRocketMqConsumerGroups
    $rocketMqConsumerGroupResiduals = @(Get-ResidualRocketMqConsumerGroups)
    $rocketMqTopicResiduals = @(Get-ResidualRocketMqTopics)
    $consumerFailureRowsObserved = Get-ScopedConsumerFailureCount
    $currentConversationConsumerFailureRows =
        Get-CurrentConversationConsumerFailureCount
    Assert-Equal -Actual $currentConversationConsumerFailureRows -Expected 0L `
        -Message 'M8.6 current conversation produced a consumer failure.'
    Clear-ScopedConsumerFailures
    Clear-ChatData
    Clear-IdentityData
    Clear-RunRedis

    $chatCleanupRows = [long](Get-MySqlScalar -Target chat -Sql @"
SELECT
    (SELECT COUNT(*) FROM chat_conversation WHERE customer_id = $($script:customerId))
  + (SELECT COUNT(*)
     FROM conversation_member
     WHERE user_id IN ($($script:customerId), $($script:agentId)))
  + (SELECT COUNT(*)
     FROM chat_message
     WHERE sender_id IN ($($script:customerId), $($script:agentId)));
"@)
    $identityCleanupRows = [long](Get-MySqlScalar -Target identity -Sql @"
SELECT COUNT(*)
FROM user_account
WHERE email IN ($customerEmailLiteral, $agentEmailLiteral);
"@)
    $consumerFailureCleanupRows = Get-ScopedConsumerFailureCount
    $redisCleanupKeys = @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "ecommerce:$($script:runId):*"
        ) | Where-Object { $_ }).Count
    $portResidue = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $script:ports.Values }).Count
    $jvmResidue = @(Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like '*PlainJournal*' -and
                (
                    $_.CommandLine -like '*identity-service.jar*' -or
                    $_.CommandLine -like '*chat-service.jar*' -or
                    $_.CommandLine -like '*ecommerce-gateway.jar*'
                )
            }).Count
    $nodeResidue = @(Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'node.exe' -and
                $_.CommandLine -like '*PlainJournal*' -and
                $_.CommandLine -like '*vite.js*' -and
                (
                    $_.CommandLine -like '*18200*' -or
                    $_.CommandLine -like '*18201*'
                )
            }).Count
    Assert-Equal -Actual $chatCleanupRows -Expected 0L `
        -Message 'M8.6 verification left scoped Chat rows.'
    Assert-Equal -Actual $identityCleanupRows -Expected 0L `
        -Message 'M8.6 verification left scoped Identity users.'
    Assert-Equal -Actual $consumerFailureCleanupRows -Expected 0L `
        -Message 'M8.6 verification left scoped consumer failure rows.'
    Assert-Equal -Actual $redisCleanupKeys -Expected 0 `
        -Message 'M8.6 verification left scoped Redis keys.'
    Assert-Equal -Actual $portResidue -Expected 0 `
        -Message 'M8.6 verification left application ports listening.'
    Assert-Equal -Actual $jvmResidue -Expected 0 `
        -Message 'M8.6 verification left managed JVMs.'
    Assert-Equal -Actual $nodeResidue -Expected 0 `
        -Message 'M8.6 verification left managed Vite processes.'
    Assert-Equal -Actual $rocketMqConsumerGroupResiduals.Count -Expected 0 `
        -Message (
            'M8.6 verification left RocketMQ consumer groups: ' +
            ($rocketMqConsumerGroupResiduals -join ', '))
    Assert-Equal -Actual $rocketMqTopicResiduals.Count -Expected 0 `
        -Message (
            'M8.6 verification left RocketMQ topics: ' +
            ($rocketMqTopicResiduals -join ', '))

    $evidence = [ordered]@{
        runId = $script:runId
        startedAtUtc = $startedAtUtc.ToString('o')
        finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        services = [ordered]@{
            identity = 'real'
            gateway = 'real'
            chat = 'real'
            mysql = 'real'
            redis = 'real'
            nacos = 'real'
            rocketmq = 'real'
            browser = 'headless Chrome'
        }
        browser = $browserResult
        business = $businessEvidence
        consumerFailures = [ordered]@{
            dispatcherConsumerGroup = $script:dispatcherGroup
            deliveryConsumerGroupPrefix = $script:deliveryGroupPrefix
            preexistingRowsRemoved = $script:preexistingConsumerFailureRows
            observedBeforeCleanup = $consumerFailureRowsObserved
            currentConversationRows = $currentConversationConsumerFailureRows
        }
        cleanup = [ordered]@{
            chatRows = $chatCleanupRows
            identityUsers = $identityCleanupRows
            consumerFailureRows = $consumerFailureCleanupRows
            redisKeys = $redisCleanupKeys
            residualRocketMqConsumerGroups = @($rocketMqConsumerGroupResiduals)
            residualRocketMqTopics = @($rocketMqTopicResiduals)
            portListeners = $portResidue
            managedJvms = $jvmResidue
            managedViteProcesses = $nodeResidue
        }
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
    Write-Host "M8 Chat frontend workspace verification passed: $evidencePath"
}
catch {
    foreach ($name in @($script:processes.Keys)) {
        foreach ($suffix in @('out.log', 'err.log')) {
            $logPath = Join-Path $script:runDirectory "$name.$suffix"
            if (Test-Path -LiteralPath $logPath -PathType Leaf) {
                Write-Host "--- $logPath ---"
                Get-Content -LiteralPath $logPath -Tail 80
            }
        }
    }
    Write-Warning ("M8 Chat frontend workspace verification failed. Logs: " +
        "$($script:runDirectory). $($_.Exception.Message)")
    throw
}
finally {
    foreach ($manualPath in @(
            $ManualInspectionReadyFile,
            $ManualInspectionContinueFile
        )) {
        if (-not [string]::IsNullOrWhiteSpace($manualPath)) {
            Remove-Item -LiteralPath $manualPath -Force -ErrorAction SilentlyContinue
        }
    }
    try {
        Stop-AllApplications
    }
    catch {
        Write-Warning "Application cleanup failed: $($_.Exception.Message)"
    }
    try {
        Remove-VerificationRocketMqConsumerGroups
    }
    catch {
        Write-Warning "RocketMQ consumer group cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-ScopedConsumerFailures
    }
    catch {
        Write-Warning "Consumer failure cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-ChatData
    }
    catch {
        Write-Warning "Chat data cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-IdentityData
    }
    catch {
        Write-Warning "Identity data cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-RunRedis
    }
    catch {
        Write-Warning "Redis cleanup failed: $($_.Exception.Message)"
    }
}
