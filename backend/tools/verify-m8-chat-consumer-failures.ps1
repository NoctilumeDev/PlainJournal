#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipPackage,
    [switch]$SkipResourceBootstrap,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:runId = "m8cf$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"
$script:chatPort = 18108
$script:process = $null
$script:databaseReady = $false
$script:redisStoppedByScript = $false
$script:conversationId = $null
$script:conversationNo = $null
$script:poisonReferenceMessageId = $null
$script:poisonOutboxId = [Guid]::NewGuid().ToString()
$script:chatNodeId = "$($script:runId)-chat"
$script:dispatcherGroup = "ecommerce-chat-dispatcher-$($script:runId)"
$script:deliveryGroupPrefix = "ecommerce-chat-delivery-$($script:runId)"
$script:rocketMqConsumerGroups = @(
    $script:dispatcherGroup
    "$($script:deliveryGroupPrefix)-$($script:chatNodeId)"
)
$script:verifierMarker = "-Dplainjournal.chat-consumer-failure-verifier=$($script:runId)"
$script:resourceBootstrapMode = if ($SkipResourceBootstrap) { 'reused' } else { 'full' }

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

function Resolve-JavaExecutable {
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    if ($javaHome) {
        $javaHomeExecutable = Join-Path $javaHome 'bin/java.exe'
        if (Test-Path -LiteralPath $javaHomeExecutable) {
            return [IO.Path]::GetFullPath($javaHomeExecutable)
        }
    }
    return (Get-Command java -ErrorAction Stop).Source
}

function Assert-RequiredEnvironment {
    $required = @(
        'CHAT_DB_NAME',
        'CHAT_DB_USER',
        'CHAT_DB_PASSWORD',
        'IDENTITY_JWT_SECRET',
        'METRICS_SCRAPE_TOKEN',
        'MYSQL_ROOT_PASSWORD',
        'REDIS_PASSWORD',
        'MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
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
    $encodedHeader = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($payload))
    $unsigned = "$encodedHeader.$encodedPayload"
    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($env:IDENTITY_JWT_SECRET))
    try {
        $signature = ConvertTo-Base64Url -Bytes (
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
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $lastState = 'no response'
    do {
        if ($null -ne $script:process -and $script:process.HasExited) {
            throw "Chat exited before $Uri became ready. ExitCode=$($script:process.ExitCode)"
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
    } while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Timed out waiting for $Uri. Last state: $lastState"
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][string]$Description,
        [int]$TimeoutSeconds = 60,
        [int]$IntervalMilliseconds = 250,
        [int]$ProgressIntervalSeconds = 0
    )

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $nextProgressAt = $ProgressIntervalSeconds
    do {
        if (& $Condition) {
            return
        }
        if ($ProgressIntervalSeconds -gt 0 -and
            $stopwatch.Elapsed.TotalSeconds -ge $nextProgressAt) {
            Write-Host (
                "Waiting for {0}: {1:n0}/{2} seconds elapsed." -f
                $Description,
                $stopwatch.Elapsed.TotalSeconds,
                $TimeoutSeconds
            )
            $nextProgressAt += $ProgressIntervalSeconds
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Timed out waiting for $Description."
}

function Wait-MySqlReady {
    Wait-Until -Description 'MySQL to accept root connections' -TimeoutSeconds 90 -Condition {
        docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" plainjournal-mysql `
            mysqladmin -uroot ping --silent 2>$null | Out-Null
        $LASTEXITCODE -eq 0
    }
}

function Start-Chat {
    param(
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing Chat artifact: $Jar"
    }
    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
    }
    try {
        $script:process = Start-Process -FilePath $script:javaPath `
            -ArgumentList @(
                '-Xms128m',
                '-Xmx256m',
                '-XX:ActiveProcessorCount=4',
                $script:verifierMarker,
                '-jar',
                $Jar
            ) `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory 'chat.out.log') `
            -RedirectStandardError (Join-Path $script:runDirectory 'chat.err.log') `
            -WindowStyle Hidden `
            -PassThru
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

function Get-ChatVerifierProcesses {
    return @(
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" `
            -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -like '*PlainJournal*chat-service-1.0.2-SNAPSHOT.jar*' -and
                $_.CommandLine -like '*-Dplainjournal.chat-consumer-failure-verifier=*'
            }
    )
}

function Stop-StaleChatVerifiers {
    foreach ($process in @(Get-ChatVerifierProcesses)) {
        $processId = [int]$process.ProcessId
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            throw "Stale Chat verification process did not exit: $processId"
        }
    }
}

function Stop-Chat {
    if ($null -eq $script:process) {
        return
    }
    $processId = [int]$script:process.Id
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
        -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        $jarName = 'chat-service-1.0.2-SNAPSHOT.jar'
        if ($process.CommandLine -notlike "*PlainJournal*$jarName*" -or
            $process.CommandLine -notlike "*$($script:verifierMarker)*") {
            throw "Refused to stop PID $processId; command line does not match the Chat verifier."
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            throw "Chat verification process did not exit: $processId"
        }
    }
    $script:process = $null
}

function Invoke-ChatMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i -e "MYSQL_PWD=$env:CHAT_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:CHAT_DB_USER" $env:CHAT_DB_NAME -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Chat MySQL command failed.'
    }
    return @($output)
}

function Get-ChatScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $lines = @(Invoke-ChatMySql -Sql $Sql)
    if ($lines.Count -ne 1) {
        throw "Expected one MySQL scalar row, received $($lines.Count)."
    }
    return [string]$lines[0]
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)]$Body
    )

    return Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers `
        -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 8 -Compress)
}

function Wait-RedisReady {
    Wait-Until -Description 'Redis to accept authenticated PING' -TimeoutSeconds 60 -Condition {
        $reply = @(
            docker exec plainjournal-redis redis-cli --no-auth-warning -a $env:REDIS_PASSWORD PING 2>$null
        )
        $LASTEXITCODE -eq 0 -and $reply.Count -eq 1 -and $reply[0] -eq 'PONG'
    }
}

function Stop-RedisForFault {
    $running = docker inspect --format '{{.State.Running}}' plainjournal-redis 2>$null
    if ($running -ne 'true') {
        throw 'Redis must be running before the controlled fault injection.'
    }
    docker stop --time 15 plainjournal-redis | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to stop Redis for controlled fault injection.'
    }
    $script:redisStoppedByScript = $true
}

function Restore-Redis {
    if (-not $script:redisStoppedByScript) {
        return
    }
    docker start plainjournal-redis | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to restart Redis after controlled fault injection.'
    }
    Wait-RedisReady
    $script:redisStoppedByScript = $false
}

function Get-RocketMqConsumerGroupConfig {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin getConsumerConfig `
            -n plainjournal-rocketmq-namesrv:9876 `
            -g $ConsumerGroup 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw (
            "Unable to inspect RocketMQ consumer group " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    return $output
}

function Test-RocketMqConsumerGroupPresent {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(Get-RocketMqConsumerGroupConfig -ConsumerGroup $ConsumerGroup)
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

function Initialize-RocketMqConsumerGroup {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateSubGroup `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -g $ConsumerGroup `
            -m false `
            -d false `
            -q 1 `
            -r 16 `
            -s true `
            -a true 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'success') {
        throw (
            "RocketMQ consumer group initialization failed for " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    Wait-Until -Description "RocketMQ consumer group $ConsumerGroup to stabilize" `
        -TimeoutSeconds 30 -Condition {
        $configuration = (
            Get-RocketMqConsumerGroupConfig -ConsumerGroup $ConsumerGroup
        ) -join "`n"
        $configuration -match (
            '(?m)^\s*groupName\s*=\s*' +
            [regex]::Escape($ConsumerGroup) +
            '\s*$') -and
        $configuration -match (
            '(?m)^\s*consumeFromMinEnable\s*=\s*false\s*$') -and
        $configuration -match (
            '(?m)^\s*consumeBroadcastEnable\s*=\s*false\s*$')
    }
    Start-Sleep -Seconds 3
    $stableConfiguration = (
        Get-RocketMqConsumerGroupConfig -ConsumerGroup $ConsumerGroup
    ) -join "`n"
    if ($stableConfiguration -notmatch (
            '(?m)^\s*consumeFromMinEnable\s*=\s*false\s*$')) {
        throw "RocketMQ consumer group did not retain latest-offset mode: $ConsumerGroup"
    }
}

function Initialize-VerificationRocketMqConsumerGroups {
    foreach ($consumerGroup in $script:rocketMqConsumerGroups) {
        Initialize-RocketMqConsumerGroup -ConsumerGroup $consumerGroup
    }
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

function Remove-RunData {
    if (-not $script:databaseReady) {
        return
    }
    $tableExists = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'consumer_failure';
"@)
    if ($tableExists -eq 0) {
        return
    }
    $dispatcherGroup = $script:dispatcherGroup.Replace("'", "''")
    $deliveryPrefix = $script:deliveryGroupPrefix.Replace("'", "''")
    $poisonOutboxId = $script:poisonOutboxId.Replace("'", "''")
    Invoke-ChatMySql -Sql @"
DELETE FROM consumer_failure
WHERE consumer_group = '$dispatcherGroup'
   OR consumer_group LIKE '$deliveryPrefix%';
DELETE FROM outbox_event
WHERE id = '$poisonOutboxId';
"@ | Out-Null

    if ($null -eq $script:conversationId) {
        return
    }
    if ([string]$script:conversationId -notmatch '^[0-9]+$') {
        throw 'Refused to clean Chat data because the conversation ID is not numeric.'
    }
    $conversationId = [string]$script:conversationId
    $conversationNo = ([string]$script:conversationNo).Replace("'", "''")
    Invoke-ChatMySql -Sql @"
DELETE FROM message_receipt
WHERE message_id IN (
    SELECT id FROM chat_message WHERE conversation_id = $conversationId
);
DELETE FROM chat_attachment
WHERE message_id IN (
    SELECT id FROM chat_message WHERE conversation_id = $conversationId
);
DELETE FROM outbox_event
WHERE aggregate_id = '$conversationNo';
DELETE FROM chat_message
WHERE conversation_id = $conversationId;
DELETE FROM conversation_member
WHERE conversation_id = $conversationId;
DELETE FROM chat_conversation
WHERE id = $conversationId;
"@ | Out-Null
}

function Get-RunRowCount {
    $dispatcherGroup = $script:dispatcherGroup.Replace("'", "''")
    $deliveryPrefix = $script:deliveryGroupPrefix.Replace("'", "''")
    $poisonOutboxId = $script:poisonOutboxId.Replace("'", "''")
    return [long](Get-ChatScalar @"
SELECT
    (SELECT COUNT(*) FROM consumer_failure
     WHERE consumer_group = '$dispatcherGroup'
        OR consumer_group LIKE '$deliveryPrefix%')
  + (SELECT COUNT(*) FROM outbox_event WHERE id = '$poisonOutboxId')
  + (SELECT COUNT(*) FROM chat_conversation
     WHERE client_conversation_id = '$($script:runId)-conversation');
"@)
}

$script:runDirectory = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
}
else {
    Join-Path $script:backendRoot ".run/m8-chat-consumer-failures-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$chatJar = Join-Path $script:backendRoot `
    'services/chat-service/target/chat-service-1.0.2-SNAPSHOT.jar'
$script:javaPath = Resolve-JavaExecutable
$verificationSucceeded = $false

try {
    Stop-StaleChatVerifiers

    if (-not $SkipNetworkPreflight) {
        Write-Host 'Stage 1/7: validating the local development network and middleware.'
        if (-not (Test-Path -LiteralPath $networkPreflight)) {
            throw "Network preflight script not found: $networkPreflight"
        }
        & $networkPreflight
        if ($LASTEXITCODE -ne 0) {
            throw 'Network preflight failed.'
        }
    }

    Import-DotEnv -Path $envPath
    Assert-RequiredEnvironment
    Wait-MySqlReady

    if ($SkipResourceBootstrap) {
        Write-Host 'Stage 2/7: reusing already-bootstrapped local service resources.'
        Get-ChatScalar -Sql 'SELECT 1;' | Out-Null
    }
    else {
        Write-Host 'Stage 2/7: bootstrapping local service resources.'
        & $bootstrapPath
        if ($LASTEXITCODE -ne 0) {
            throw 'Local resource bootstrap failed.'
        }
    }
    $script:databaseReady = $true

    if (-not $SkipPackage) {
        Push-Location $script:backendRoot
        try {
            & mvn '-pl' 'services/chat-service' '-am' '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven packaging failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    Write-Host 'Stage 3/7: precreating isolated consumer groups and starting Chat.'
    Initialize-VerificationRocketMqConsumerGroups
    Assert-PortAvailable -Port $script:chatPort
    $customerId = [long]7800000000000000201
    $agentId = [long]7800000000000000202
    $adminId = [long]7800000000000000203
    $customerHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $customerId -Roles @('CUSTOMER'))"
    }
    $agentHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $agentId -Roles @('OPERATOR'))"
    }
    $adminHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $adminId -Roles @('ADMIN'))"
    }

    Start-Chat -Jar $chatJar -Environment @{
        APP_ENV = $script:runId
        CHAT_SERVICE_PORT = [string]$script:chatPort
        SERVICE_INSTANCE_ID = $script:chatNodeId
        SERVICE_RELEASE_ID = 'm8-chat-consumer-failure-v1'
        SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = 'false'
        SPRING_CLOUD_NACOS_CONFIG_ENABLED = 'false'
        CHAT_ATTACHMENT_CLEANUP_ENABLED = 'false'
        CHAT_WEBSOCKET_TICKET_ENABLED = 'false'
        CHAT_OUTBOX_ENABLED = 'true'
        CHAT_OUTBOX_INITIAL_DELAY = '0'
        CHAT_OUTBOX_FIXED_DELAY = '250'
        CHAT_OUTBOX_RETRY_DELAY = '1s'
        CHAT_REALTIME_ENABLED = 'true'
        CHAT_REALTIME_INITIAL_DELAY = '0'
        CHAT_REALTIME_FIXED_DELAY = '250'
        CHAT_REALTIME_AWAIT_DURATION = '5s'
        CHAT_REALTIME_INVISIBLE_DURATION = '15s'
        CHAT_DISPATCHER_CONSUMER_GROUP = $script:dispatcherGroup
        CHAT_DELIVERY_CONSUMER_GROUP_PREFIX = $script:deliveryGroupPrefix
        CHAT_CONSUMER_FAILURE_MAX_DELIVERY_ATTEMPTS = '3'
        CHAT_CONSUMER_FAILURE_RETRY_INITIAL_DELAY = '0'
        CHAT_CONSUMER_FAILURE_RETRY_FIXED_DELAY = '250'
        CHAT_CONSUMER_FAILURE_RETRY_DELAY = '10s'
        CHAT_CONSUMER_FAILURE_RETRY_LEASE_DURATION = '15s'
        ROCKETMQ_ENDPOINTS = '127.0.0.1:18082'
        MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED = 'false'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:chatPort)/actuator/health/liveness"

    Write-Host 'Stage 4/7: verifying poison-message durable terminal handling.'
    $baselineReport = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$($script:chatPort)/actuator/consumerfailures" `
        -Headers $adminHeaders
    $baseUrl = "http://127.0.0.1:$($script:chatPort)/api/v1/chat"
    $conversation = Invoke-JsonPost -Uri "$baseUrl/conversations" `
        -Headers $customerHeaders -Body @{
            clientConversationId = "$($script:runId)-conversation"
            subject = 'M8 consumer failure verification'
            contextType = 'ORDER'
            contextId = "$($script:runId)-order"
        }
    $script:conversationId = [string]$conversation.data.id
    $script:conversationNo = [string]$conversation.data.conversationNo
    Invoke-JsonPost -Uri "$baseUrl/conversations/$($script:conversationId)/claim" `
        -Headers $agentHeaders -Body @{} | Out-Null

    $poisonReferenceMessage = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$($script:conversationId)/messages" `
        -Headers $customerHeaders -Body @{
            clientMessageId = "$($script:runId)-poison-reference"
            messageType = 'TEXT'
            content = 'This valid message anchors the poison-event contract verification.'
        }
    $script:poisonReferenceMessageId = [string]$poisonReferenceMessage.data.id
    Wait-Until -Description 'reference message Outbox to publish normally' `
        -TimeoutSeconds 20 -Condition {
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE aggregate_id = '$($script:conversationNo)'
  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.messageId'))
      = '$($script:poisonReferenceMessageId)'
  AND status = 'PUBLISHED';
"@) -eq 1
    }

    $poisonMarker = "$($script:runId)-private-payload-marker"
    $poisonPayload = [ordered]@{
        eventId = $script:poisonOutboxId
        eventType = 'ChatMessageStored'
        payloadVersion = 99
        payload = [ordered]@{
            messageId = [long]$script:poisonReferenceMessageId
            privatePayloadMarker = $poisonMarker
        }
    } | ConvertTo-Json -Depth 5 -Compress
    $escapedPoisonPayload = $poisonPayload.Replace("'", "''")
    Invoke-ChatMySql -Sql @"
INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    destination_topic, payload, status, attempts, next_attempt_at,
    claimed_at, claim_owner, claim_until, published_at, last_error,
    created_at, updated_at
) VALUES (
    '$($script:poisonOutboxId)', 'ChatMessageStored', 'M8ConsumerFailureVerification',
    '$($script:runId)-poison', 1, 'ecommerce-chat-events', '$escapedPoisonPayload',
    'PENDING', 0, UTC_TIMESTAMP(3), NULL, NULL, NULL, NULL, NULL,
    UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
);
"@ | Out-Null

    Wait-Until -Description 'poison event to enter NEEDS_ATTENTION' -TimeoutSeconds 20 -Condition {
        $escapedMarker = $poisonMarker.Replace("'", "''")
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'NEEDS_ATTENTION'
  AND raw_payload LIKE '%$escapedMarker%';
"@) -eq 1
    }
    $poisonOutboxStatus = Get-ChatScalar @"
SELECT status FROM outbox_event WHERE id = '$($script:poisonOutboxId)';
"@
    if ($poisonOutboxStatus -ne 'PUBLISHED') {
        throw "Poison verification Outbox was not published: $poisonOutboxStatus"
    }

    $attentionReport = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$($script:chatPort)/actuator/consumerfailures" `
        -Headers $adminHeaders
    if ([long]$attentionReport.needsAttention -lt
        ([long]$baselineReport.needsAttention + 1)) {
        throw 'Actuator did not expose the durable Chat NEEDS_ATTENTION record.'
    }
    $attentionJson = $attentionReport | ConvertTo-Json -Depth 10 -Compress
    if ($attentionJson.Contains($poisonMarker)) {
        throw 'Actuator exposed the raw poison-message payload.'
    }

    Write-Host 'Stage 5/7: injecting a controlled Redis outage.'
    Stop-RedisForFault
    $transientMessage = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$($script:conversationId)/messages" `
        -Headers $customerHeaders -Body @{
            clientMessageId = "$($script:runId)-redis-failure"
            messageType = 'TEXT'
            content = 'This message remains authoritative while Redis is unavailable.'
        }
    $transientMessageId = [string]$transientMessage.data.id
    Wait-Until -Description 'transient dispatch failure to enter RETRYING' `
        -TimeoutSeconds 30 -IntervalMilliseconds 100 -Condition {
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'RETRYING'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId'))
      = '$transientMessageId';
"@) -eq 1
    }
    $retryAttempts = [long](Get-ChatScalar @"
SELECT attempts
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'RETRYING'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId'))
      = '$transientMessageId';
"@)
    $durableRetryOwnership = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'RETRYING'
  AND next_attempt_at IS NOT NULL
  AND claim_owner IS NULL
  AND claim_until IS NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId'))
      = '$transientMessageId';
"@)
    if ($durableRetryOwnership -ne 1) {
        throw 'Transient Chat failure did not establish durable MySQL retry ownership.'
    }
    $chatLogPath = Join-Path $script:runDirectory 'chat.out.log'
    Wait-Until -Description 'original message acknowledgement after durable retry recording' `
        -TimeoutSeconds 20 -Condition {
        Select-String -LiteralPath $chatLogPath `
            -SimpleMatch 'durable MySQL retry now owns recovery' `
            -Quiet
    }
    Restore-Redis

    Write-Host 'Stage 6/7: verifying MySQL lease retry recovery and observability.'
    Wait-Until -Description 'transient dispatch failure to recover after Redis restart' `
        -TimeoutSeconds 90 -ProgressIntervalSeconds 15 -Condition {
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'RECOVERED'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId'))
      = '$transientMessageId';
"@) -eq 1
    }
    $finalAttempts = [long](Get-ChatScalar @"
SELECT attempts
FROM consumer_failure
WHERE consumer_group = '$($script:dispatcherGroup)'
  AND status = 'RECOVERED'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.payload.messageId'))
      = '$transientMessageId';
"@)
    $finalReport = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$($script:chatPort)/actuator/consumerfailures" `
        -Headers $adminHeaders
    if ([long]$finalReport.needsAttention -lt
            ([long]$baselineReport.needsAttention + 1) -or
        [long]$finalReport.recovered -lt
            ([long]$baselineReport.recovered + 1)) {
        throw 'Chat consumer failure report did not converge to one terminal and one recovered record.'
    }
    $metrics = Invoke-WebRequest -Method Get `
        -Uri "http://127.0.0.1:$($script:chatPort)/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN }
    if ($metrics.Content -notmatch 'ecommerce_consumer_failure_active_events' -or
        $metrics.Content -notmatch 'service="chat-service"') {
        throw 'Chat consumer failure Prometheus metrics were not exposed.'
    }

    $evidence = [ordered]@{
        runId = $script:runId
        completedDate = '2026-07-25'
        topology = [ordered]@{
            chatInstances = 1
            mysql = 'plainjournal-mysql'
            redis = 'plainjournal-redis'
            rocketmq = 'plainjournal-rocketmq-proxy:18082'
            dispatcherConsumerGroup = $script:dispatcherGroup
            consumerGroupInitialization = 'precreated-latest-offset'
            resourceBootstrap = $script:resourceBootstrapMode
        }
        poisonMessage = [ordered]@{
            referenceMessageId = [long]$script:poisonReferenceMessageId
            outboxStatus = $poisonOutboxStatus
            durableStatus = 'NEEDS_ATTENTION'
            acknowledgedAfterRecording = $true
            rawPayloadExposedByActuator = $false
        }
        transientRedisFailure = [ordered]@{
            messageId = $transientMessageId
            firstStatus = 'RETRYING'
            firstRecordedAttempts = $retryAttempts
            finalStatus = 'RECOVERED'
            finalAttempts = $finalAttempts
            redisRestored = $true
            originalAcknowledgedAfterDurableRetry = $true
            recoveryOwner = 'mysql-lease-retry'
            brokerRedeliveryRequired = $false
        }
        observability = [ordered]@{
            baseline = [ordered]@{
                retrying = [long]$baselineReport.retrying
                needsAttention = [long]$baselineReport.needsAttention
                recovered = [long]$baselineReport.recovered
            }
            runDelta = [ordered]@{
                retrying = [long]$finalReport.retrying - [long]$baselineReport.retrying
                needsAttention = (
                    [long]$finalReport.needsAttention -
                    [long]$baselineReport.needsAttention
                )
                recovered = [long]$finalReport.recovered - [long]$baselineReport.recovered
            }
            prometheusMetricsPresent = $true
        }
    }

    Write-Host 'Stage 7/7: stopping the verifier and checking zero residual state.'
    Stop-Chat
    Remove-VerificationRocketMqConsumerGroups
    Remove-RunData
    $cleanupRows = Get-RunRowCount
    $rocketMqConsumerGroupResiduals = @(Get-ResidualRocketMqConsumerGroups)
    $rocketMqTopicResiduals = @(Get-ResidualRocketMqTopics)
    $portListeners = @(
        Get-NetTCPConnection -State Listen -LocalPort $script:chatPort `
            -ErrorAction SilentlyContinue
    ).Count
    $managedJvms = @(Get-ChatVerifierProcesses).Count
    if ($cleanupRows -ne 0 -or
        $rocketMqConsumerGroupResiduals.Count -ne 0 -or
        $rocketMqTopicResiduals.Count -ne 0 -or
        $portListeners -ne 0 -or
        $managedJvms -ne 0) {
        throw (
            "Verification cleanup failed: rows=$cleanupRows " +
            "consumerGroups=$($rocketMqConsumerGroupResiduals -join ',') " +
            "topics=$($rocketMqTopicResiduals -join ',') " +
            "ports=$portListeners jvms=$managedJvms")
    }
    $evidence.cleanup = [ordered]@{
        mysqlRows = $cleanupRows
        residualRocketMqConsumerGroups = @($rocketMqConsumerGroupResiduals)
        residualRocketMqTopics = @($rocketMqTopicResiduals)
        portListeners = $portListeners
        managedJvms = $managedJvms
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 10 | Set-Content `
        -LiteralPath $evidencePath -Encoding utf8NoBOM
    $verificationSucceeded = $true
    Write-Host "M8 Chat consumer-failure verification passed: $evidencePath"
}
catch {
    Write-Error -ErrorAction Continue `
        "M8 Chat consumer-failure verification failed. Logs: $($script:runDirectory). $($_.Exception.Message)"
    throw
}
finally {
    try {
        Restore-Redis
    }
    catch {
        Write-Warning "Redis restoration failed: $($_.Exception.Message)"
    }
    try {
        Stop-Chat
    }
    catch {
        Write-Warning "Chat process cleanup failed: $($_.Exception.Message)"
    }
    try {
        Remove-VerificationRocketMqConsumerGroups
    }
    catch {
        Write-Warning "RocketMQ consumer group cleanup failed: $($_.Exception.Message)"
    }
    try {
        Remove-RunData
    }
    catch {
        Write-Warning "Chat verification data cleanup failed: $($_.Exception.Message)"
    }
    if (-not $verificationSucceeded) {
        Write-Warning "Verification did not complete successfully. Inspect $($script:runDirectory)."
    }
}
