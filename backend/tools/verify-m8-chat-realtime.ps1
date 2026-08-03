#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipPackage,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:runId = "m8rt$(([Guid]::NewGuid().ToString('N')).Substring(0, 12))"
$script:chatANodeId = "$($script:runId)-a"
$script:chatBNodeId = "$($script:runId)-b"
$script:dispatcherGroup = "ecommerce-chat-dispatcher-$($script:runId)"
$script:deliveryGroupPrefix = "ecommerce-chat-delivery-$($script:runId)"
$script:rocketMqConsumerGroups = @(
    $script:dispatcherGroup
    "$($script:deliveryGroupPrefix)-$($script:chatANodeId)"
    "$($script:deliveryGroupPrefix)-$($script:chatBNodeId)"
)
$script:processes = [ordered]@{}
$script:databaseReady = $false
$script:chatPorts = @(18108, 18118, 18128)
$script:gatewayPort = 18000
$script:webSockets = [System.Collections.Generic.List[Net.WebSockets.ClientWebSocket]]::new()
$script:chatRedisPrefix = "ecommerce:$($script:runId):chat:"

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
        [int]$TimeoutSeconds = 60,
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

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment
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
        $process = Start-Process -FilePath $script:javaPath `
            -ArgumentList @(
                '-Xms128m',
                '-Xmx256m',
                '-XX:ActiveProcessorCount=4',
                '-jar',
                $Jar
            ) `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            jar = $Jar
            port = if ($Environment.ContainsKey('CHAT_SERVICE_PORT')) {
                [int]$Environment['CHAT_SERVICE_PORT']
            }
            elseif ($Environment.ContainsKey('GATEWAY_PORT')) {
                [int]$Environment['GATEWAY_PORT']
            }
            else {
                $null
            }
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Stop-Application {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $script:processes.Contains($Name)) {
        return
    }
    $managed = $script:processes[$Name]
    $processId = [int]$managed.process.Id
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
        -ErrorAction SilentlyContinue
    if ($null -ne $process) {
        if ($process.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            throw "Refused to stop PID $processId; command line no longer matches $Name."
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            throw "Application process did not exit: $Name/$processId"
        }
    }
    if ($null -ne $managed.port) {
        foreach ($listener in @(Get-NetTCPConnection -State Listen -LocalPort $managed.port `
                    -ErrorAction SilentlyContinue)) {
            $listenerProcess = Get-CimInstance Win32_Process `
                -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction Stop
            $jarName = [IO.Path]::GetFileName($managed.jar)
            if ($listenerProcess.CommandLine -notlike "*PlainJournal*$jarName*") {
                throw "Refused to stop PID $($listenerProcess.ProcessId) on port "
                + "$($managed.port); command line mismatch."
            }
            Stop-Process -Id $listenerProcess.ProcessId -Force -ErrorAction Stop
            Wait-Process -Id $listenerProcess.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
        if (Get-NetTCPConnection -State Listen -LocalPort $managed.port `
                -ErrorAction SilentlyContinue) {
            throw "Application port remained active after cleanup: $Name/$($managed.port)"
        }
    }
    $script:processes.Remove($Name)
}

function Stop-AllApplications {
    foreach ($name in @($script:processes.Keys)) {
        Stop-Application -Name $name
    }
    $expectedByPort = @{
        18000 = 'ecommerce-gateway-1.0.1-SNAPSHOT.jar'
        18108 = 'chat-service-1.0.1-SNAPSHOT.jar'
        18118 = 'chat-service-1.0.1-SNAPSHOT.jar'
        18128 = 'chat-service-1.0.1-SNAPSHOT.jar'
    }
    foreach ($port in $expectedByPort.Keys) {
        foreach ($listener in @(Get-NetTCPConnection -State Listen -LocalPort $port `
                    -ErrorAction SilentlyContinue)) {
            $process = Get-CimInstance Win32_Process `
                -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction Stop
            if ($process.CommandLine -notlike "*PlainJournal*$($expectedByPort[$port])*") {
                throw "Refused to stop PID $($process.ProcessId) on port $port; command line mismatch."
            }
            Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
            Wait-Process -Id $process.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
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

function Get-ScopedConsumerFailureCount {
    if (-not $script:databaseReady) {
        return 0L
    }
    $consumerGroups = @($script:rocketMqConsumerGroups | ForEach-Object {
            "'" + $_.Replace("'", "''") + "'"
        }) -join ', '
    return [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM consumer_failure
WHERE consumer_group IN ($consumerGroups);
"@)
}

function Clear-ChatData {
    if (-not $script:databaseReady) {
        return
    }
    $tableExists = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'chat_conversation';
"@)
    if ($tableExists -eq 0) {
        return
    }
    $uploadTableExists = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'chat_attachment_upload';
"@)
    Invoke-ChatMySql -Sql @"
DELETE FROM message_receipt;
DELETE FROM chat_attachment;
"@ | Out-Null
    if ($uploadTableExists -eq 1) {
        Invoke-ChatMySql -Sql 'DELETE FROM chat_attachment_upload;' | Out-Null
    }
    $consumerGroups = @($script:rocketMqConsumerGroups | ForEach-Object {
            "'" + $_.Replace("'", "''") + "'"
        }) -join ', '
    Invoke-ChatMySql -Sql @"
DELETE FROM consumer_failure
WHERE consumer_group IN ($consumerGroups);
DELETE FROM outbox_event;
DELETE FROM chat_message;
DELETE FROM conversation_member;
DELETE FROM chat_conversation;
"@ | Out-Null
}

function Invoke-Redis {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis `
        redis-cli --no-auth-warning @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis command failed.'
    }
    return @($output)
}

function Clear-ChatRedis {
    $keys = @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "$($script:chatRedisPrefix)*"
        ))
    foreach ($key in $keys) {
        if ($key) {
            Invoke-Redis -Arguments @('DEL', $key) | Out-Null
        }
    }
}

function Get-ChatRedisKeyCount {
    return @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "$($script:chatRedisPrefix)*"
        )).Count
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

function Open-ChatWebSocket {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Token
    )

    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $socket.Options.SetRequestHeader('Authorization', "Bearer $Token")
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(20))
    try {
        $null = $socket.ConnectAsync([Uri]$Uri, $timeout.Token).GetAwaiter().GetResult()
    }
    finally {
        $timeout.Dispose()
    }
    $script:webSockets.Add($socket)
    $connected = Receive-WebSocketJson -Socket $socket -TimeoutSeconds 20
    if ($connected.type -ne 'CONNECTED') {
        throw "Expected CONNECTED WebSocket frame from $Uri."
    }
    return [pscustomobject]@{
        socket = $socket
        connected = $connected
    }
}

function Receive-WebSocketJson {
    param(
        [Parameter(Mandatory)][Net.WebSockets.ClientWebSocket]$Socket,
        [int]$TimeoutSeconds = 30
    )

    $buffer = [byte[]]::new(8192)
    $stream = [IO.MemoryStream]::new()
    $timeout = [Threading.CancellationTokenSource]::new(
        [TimeSpan]::FromSeconds($TimeoutSeconds))
    try {
        do {
            $segment = [ArraySegment[byte]]::new($buffer)
            $result = $Socket.ReceiveAsync($segment, $timeout.Token).GetAwaiter().GetResult()
            if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) {
                throw "WebSocket closed before the expected frame: $($Socket.CloseStatus)"
            }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        $json = [Text.Encoding]::UTF8.GetString($stream.ToArray())
        return $json | ConvertFrom-Json
    }
    finally {
        $timeout.Dispose()
        $stream.Dispose()
    }
}

function Close-WebSockets {
    foreach ($socket in $script:webSockets) {
        try {
            if ($socket.State -eq [Net.WebSockets.WebSocketState]::Open) {
                $timeout = [Threading.CancellationTokenSource]::new(
                    [TimeSpan]::FromSeconds(3))
                try {
                    $null = $socket.CloseAsync(
                        [Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                        'verification complete',
                        $timeout.Token).GetAwaiter().GetResult()
                }
                finally {
                    $timeout.Dispose()
                }
            }
        }
        catch {
            Write-Debug "WebSocket cleanup ignored: $($_.Exception.Message)"
        }
        finally {
            $socket.Dispose()
        }
    }
    $script:webSockets.Clear()
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

$script:runDirectory = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
}
else {
    Join-Path $script:backendRoot ".run/m8-chat-realtime-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$chatJar = Join-Path $script:backendRoot 'services/chat-service/target/chat-service-1.0.1-SNAPSHOT.jar'
$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway/target/ecommerce-gateway-1.0.1-SNAPSHOT.jar'
$script:javaPath = (Get-Command java -ErrorAction Stop).Source

try {
    if (-not $SkipNetworkPreflight) {
        if (-not (Test-Path -LiteralPath $networkPreflight)) {
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

    if (-not $SkipPackage) {
        Push-Location $script:backendRoot
        try {
            & mvn '-pl' 'ecommerce-gateway,services/chat-service' '-am' '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven packaging failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    foreach ($port in @($script:gatewayPort) + $script:chatPorts) {
        Assert-PortAvailable -Port $port
    }
    Clear-ChatData
    Clear-ChatRedis

    $customerId = [long]7800000000000000001
    $agentId = [long]7800000000000000101
    $gatewayProbeUserId = [long]7800000000000000002
    $customerToken = New-AccessToken -UserId $customerId -Roles @('CUSTOMER')
    $agentToken = New-AccessToken -UserId $agentId -Roles @('OPERATOR')
    $probeToken = New-AccessToken -UserId $gatewayProbeUserId -Roles @('CUSTOMER')
    $customerHeaders = @{ Authorization = "Bearer $customerToken" }
    $agentHeaders = @{ Authorization = "Bearer $agentToken" }

    Start-Application -Name 'chat-failure' -Jar $chatJar -Environment @{
        APP_ENV = $script:runId
        CHAT_SERVICE_PORT = '18128'
        SERVICE_INSTANCE_ID = "$($script:runId)-failure"
        SERVICE_RELEASE_ID = 'm8-chat-realtime-failure'
        SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = 'false'
        CHAT_REALTIME_ENABLED = 'false'
        CHAT_OUTBOX_ENABLED = 'true'
        CHAT_OUTBOX_INITIAL_DELAY = '0'
        CHAT_OUTBOX_FIXED_DELAY = '500'
        CHAT_OUTBOX_RETRY_DELAY = '1s'
        ROCKETMQ_ENDPOINTS = '127.0.0.1:1'
    }
    Wait-HttpOk -Uri 'http://127.0.0.1:18128/actuator/health/liveness' `
        -ProcessName 'chat-failure'

    $failureBaseUrl = 'http://127.0.0.1:18128/api/v1/chat'
    $conversation = Invoke-JsonPost -Uri "$failureBaseUrl/conversations" `
        -Headers $customerHeaders -Body @{
            clientConversationId = "$($script:runId)-conversation"
            subject = 'M8 realtime recovery verification'
            contextType = 'ORDER'
            contextId = "$($script:runId)-order"
        }
    $conversationId = [string]$conversation.data.id
    $failureMessage = Invoke-JsonPost `
        -Uri "$failureBaseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -Body @{
            clientMessageId = "$($script:runId)-broker-outage"
            messageType = 'TEXT'
            content = 'Persisted while the configured RocketMQ endpoint is unavailable.'
        }
    $failureMessageId = [string]$failureMessage.data.id
    Wait-Until -Description 'failed Outbox attempt to return to PENDING' -Condition {
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE status = 'PENDING' AND attempts >= 1;
"@) -eq 1
    }
    $failureOutboxStatus = Get-ChatScalar 'SELECT status FROM outbox_event;'
    $failureOutboxAttempts = [long](Get-ChatScalar 'SELECT attempts FROM outbox_event;')
    $failureMessageStatus = Get-ChatScalar "SELECT status FROM chat_message WHERE id = $failureMessageId;"
    Assert-Equal -Actual $failureOutboxStatus -Expected 'PENDING' `
        -Message 'Broker failure must keep the Chat Outbox pending.'
    Assert-Equal -Actual $failureMessageStatus -Expected 'STORED' `
        -Message 'Broker failure must not advance the message beyond STORED.'
    Stop-Application -Name 'chat-failure'

    $sharedRealtimeEnvironment = @{
        APP_ENV = $script:runId
        CHAT_REALTIME_ENABLED = 'true'
        CHAT_OUTBOX_ENABLED = 'true'
        CHAT_OUTBOX_INITIAL_DELAY = '25000'
        CHAT_OUTBOX_FIXED_DELAY = '500'
        CHAT_OUTBOX_RETRY_DELAY = '1s'
        CHAT_REALTIME_INITIAL_DELAY = '500'
        CHAT_REALTIME_FIXED_DELAY = '300'
        CHAT_REALTIME_AWAIT_DURATION = '5s'
        CHAT_REALTIME_INVISIBLE_DURATION = '10s'
        CHAT_PRESENCE_TTL = '6s'
        CHAT_PRESENCE_REFRESH_INTERVAL = '2s'
        CHAT_DISPATCHER_CONSUMER_GROUP = $script:dispatcherGroup
        CHAT_DELIVERY_CONSUMER_GROUP_PREFIX = $script:deliveryGroupPrefix
        ROCKETMQ_ENDPOINTS = '127.0.0.1:18082'
    }
    $chatAEnvironment = @{} + $sharedRealtimeEnvironment
    $chatAEnvironment['CHAT_SERVICE_PORT'] = '18108'
    $chatAEnvironment['SERVICE_INSTANCE_ID'] = $script:chatANodeId
    $chatAEnvironment['SERVICE_RELEASE_ID'] = 'm8-chat-realtime-v1'
    Start-Application -Name 'chat-a' -Jar $chatJar -Environment $chatAEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18108/actuator/health/liveness' -ProcessName 'chat-a'

    $chatBEnvironment = @{} + $sharedRealtimeEnvironment
    $chatBEnvironment['CHAT_SERVICE_PORT'] = '18118'
    $chatBEnvironment['SERVICE_INSTANCE_ID'] = $script:chatBNodeId
    $chatBEnvironment['SERVICE_RELEASE_ID'] = 'm8-chat-realtime-v1'
    Start-Application -Name 'chat-b' -Jar $chatJar -Environment $chatBEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18118/actuator/health/liveness' -ProcessName 'chat-b'

    Start-Application -Name 'gateway' -Jar $gatewayJar -Environment @{
        APP_ENV = $script:runId
        GATEWAY_PORT = [string]$script:gatewayPort
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
        SERVICE_RELEASE_ID = 'm8-chat-realtime-v1'
    }
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/actuator/health/liveness' `
        -ProcessName 'gateway'
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/api/v1/chat/status' `
        -ProcessName 'gateway'

    $customerSocket = Open-ChatWebSocket `
        -Uri 'ws://127.0.0.1:18108/ws/chat' -Token $customerToken
    $agentSocket = Open-ChatWebSocket `
        -Uri 'ws://127.0.0.1:18118/ws/chat' -Token $agentToken
    Assert-Equal -Actual $customerSocket.connected.nodeId `
        -Expected $script:chatANodeId -Message 'Customer was not connected to Chat node A.'
    Assert-Equal -Actual $agentSocket.connected.nodeId `
        -Expected $script:chatBNodeId -Message 'Agent was not connected to Chat node B.'

    $gatewayProbeSocket = Open-ChatWebSocket `
        -Uri 'ws://127.0.0.1:18000/ws/chat' -Token $probeToken
    $gatewayWebSocketNode = [string]$gatewayProbeSocket.connected.nodeId
    $gatewayProbeSocket.socket.Dispose()
    $null = $script:webSockets.Remove($gatewayProbeSocket.socket)

    $chatABaseUrl = 'http://127.0.0.1:18108/api/v1/chat'
    $gatewayBaseUrl = 'http://127.0.0.1:18000/api/v1/chat'
    Invoke-JsonPost -Uri "$gatewayBaseUrl/conversations/$conversationId/claim" `
        -Headers $agentHeaders -Body @{} | Out-Null
    $recoveredFrame = Receive-WebSocketJson -Socket $agentSocket.socket -TimeoutSeconds 60
    Assert-Equal -Actual $recoveredFrame.type -Expected 'CHAT_MESSAGE' `
        -Message 'Recovered Outbox event did not reach the agent socket.'
    Assert-Equal -Actual ([string]$recoveredFrame.message.id) -Expected $failureMessageId `
        -Message 'Recovered socket frame referenced the wrong message.'
    Assert-Equal -Actual ([string]$recoveredFrame.nodeId) -Expected $script:chatBNodeId `
        -Message 'Recovered message was not delivered by Chat node B.'
    Wait-Until -Description 'recovered message to become DELIVERED' -Condition {
        (Get-ChatScalar "SELECT status FROM chat_message WHERE id = $failureMessageId;") -eq 'DELIVERED'
    }
    $recoveredOutboxStatus = Get-ChatScalar 'SELECT status FROM outbox_event;'
    Assert-Equal -Actual $recoveredOutboxStatus -Expected 'PUBLISHED' `
        -Message 'Recovered Outbox event was not marked PUBLISHED.'

    $agentReply = Invoke-JsonPost `
        -Uri "$gatewayBaseUrl/conversations/$conversationId/messages" `
        -Headers $agentHeaders -Body @{
            clientMessageId = "$($script:runId)-agent-reply"
            messageType = 'TEXT'
            content = 'This reply must cross RocketMQ from node B to the customer on node A.'
        }
    $agentReplyId = [string]$agentReply.data.id
    $customerFrame = Receive-WebSocketJson -Socket $customerSocket.socket -TimeoutSeconds 30
    Assert-Equal -Actual ([string]$customerFrame.message.id) -Expected $agentReplyId `
        -Message 'Cross-node reply did not reach the customer socket.'
    Assert-Equal -Actual ([string]$customerFrame.nodeId) -Expected "$($script:runId)-a" `
        -Message 'Cross-node reply was not delivered by Chat node A.'

    Stop-Application -Name 'chat-b'
    Wait-Until -Description 'Chat node B presence lease to expire' -TimeoutSeconds 20 -Condition {
        $exists = @(Invoke-Redis -Arguments @(
                'EXISTS',
                "$($script:chatRedisPrefix)node:$($script:chatBNodeId)"
            ))
        $exists.Count -eq 1 -and [string]$exists[0] -eq '0'
    }

    $offlineMessage = Invoke-JsonPost `
        -Uri "$chatABaseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -Body @{
            clientMessageId = "$($script:runId)-offline-after-node-exit"
            messageType = 'TEXT'
            content = 'This message must remain offline after node B exits.'
        }
    $offlineMessageId = [string]$offlineMessage.data.id
    Wait-Until -Description 'offline message Outbox publication' -Condition {
        [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE status = 'PUBLISHED'
  AND payload LIKE '%"messageId":$offlineMessageId%';
"@) -eq 1
    }
    Start-Sleep -Seconds 2
    $expiredRouteScore = @(Invoke-Redis -Arguments @(
                'ZSCORE',
                "$($script:chatRedisPrefix)user:$agentId:nodes",
                $script:chatBNodeId
            ))
    if ($expiredRouteScore.Count -gt 0 -and $expiredRouteScore[0]) {
        throw 'Expired Chat node B remained in the agent route after dispatch lookup.'
    }
    $offlineReceiptState = Get-ChatScalar @"
SELECT state
FROM message_receipt
WHERE message_id = $offlineMessageId AND recipient_id = $agentId;
"@
    $offlineMessageStatus = Get-ChatScalar `
        "SELECT status FROM chat_message WHERE id = $offlineMessageId;"
    Assert-Equal -Actual $offlineReceiptState -Expected 'OFFLINE' `
        -Message 'Node exit must leave the recipient receipt OFFLINE.'
    Assert-Equal -Actual $offlineMessageStatus -Expected 'DISPATCHED' `
        -Message 'Broker publication without a live route must stop at DISPATCHED.'

    $restartEnvironment = @{} + $chatBEnvironment
    $restartEnvironment['CHAT_OUTBOX_INITIAL_DELAY'] = '0'
    Start-Application -Name 'chat-b' -Jar $chatJar -Environment $restartEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18118/actuator/health/liveness' -ProcessName 'chat-b'
    $agentReplaySocket = Open-ChatWebSocket `
        -Uri 'ws://127.0.0.1:18118/ws/chat' -Token $agentToken
    $offlineReplayFrame = Receive-WebSocketJson `
        -Socket $agentReplaySocket.socket -TimeoutSeconds 20
    Assert-Equal -Actual ([string]$offlineReplayFrame.message.id) -Expected $offlineMessageId `
        -Message 'Reconnect did not replay the authoritative offline message.'
    Wait-Until -Description 'offline replay to become DELIVERED' -Condition {
        (Get-ChatScalar @"
SELECT state
FROM message_receipt
WHERE message_id = $offlineMessageId AND recipient_id = $agentId;
"@) -eq 'DELIVERED'
    }

    $replayedReceiptState = Get-ChatScalar @"
SELECT state
FROM message_receipt
WHERE message_id = $offlineMessageId AND recipient_id = $agentId;
"@
    $privateContentInOutbox = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE payload LIKE '%Persisted while the configured RocketMQ endpoint is unavailable.%'
   OR payload LIKE '%This reply must cross RocketMQ%'
   OR payload LIKE '%This message must remain offline%';
"@)
    Assert-Equal -Actual $privateContentInOutbox -Expected 0L `
        -Message 'Chat Outbox payload copied private message content.'

    $evidence = [ordered]@{
        runId = $script:runId
        verifiedAt = (Get-Date).ToUniversalTime().ToString('o')
        topology = [ordered]@{
            customerNode = [string]$customerSocket.connected.nodeId
            agentNode = [string]$agentSocket.connected.nodeId
            gatewayWebSocketNode = $gatewayWebSocketNode
            crossNodeDelivery = $true
            redisKeyPrefix = $script:chatRedisPrefix
            dispatcherConsumerGroup = $script:dispatcherGroup
            deliveryConsumerGroupPrefix = $script:deliveryGroupPrefix
        }
        brokerFailure = [ordered]@{
            outboxStatus = $failureOutboxStatus
            attempts = $failureOutboxAttempts
            messageStatus = $failureMessageStatus
        }
        recovery = [ordered]@{
            outboxStatus = $recoveredOutboxStatus
            recoveredMessageId = $failureMessageId
            recoveredDeliveryNode = [string]$recoveredFrame.nodeId
            replyMessageId = $agentReplyId
        }
        nodeExit = [ordered]@{
            expiredNodeId = $script:chatBNodeId
            offlineMessageId = $offlineMessageId
            offlineReceiptState = $offlineReceiptState
            offlineMessageStatus = $offlineMessageStatus
            replayedReceiptState = $replayedReceiptState
        }
        privacy = [ordered]@{
            privateContentInOutbox = $privateContentInOutbox
        }
    }

    Close-WebSockets
    Stop-AllApplications
    Remove-VerificationRocketMqConsumerGroups
    Clear-ChatData
    Clear-ChatRedis
    $cleanupRows = [long](Get-ChatScalar @"
SELECT
    (SELECT COUNT(*) FROM chat_conversation)
  + (SELECT COUNT(*) FROM conversation_member)
  + (SELECT COUNT(*) FROM chat_message)
  + (SELECT COUNT(*) FROM message_receipt)
  + (SELECT COUNT(*) FROM chat_attachment)
  + (SELECT COUNT(*) FROM chat_attachment_upload)
  + (SELECT COUNT(*) FROM outbox_event);
"@)
    $redisCleanupKeys = Get-ChatRedisKeyCount
    $consumerFailureCleanupRows = Get-ScopedConsumerFailureCount
    $rocketMqConsumerGroupResiduals = @(Get-ResidualRocketMqConsumerGroups)
    $rocketMqTopicResiduals = @(Get-ResidualRocketMqTopics)
    Assert-Equal -Actual $cleanupRows -Expected 0L `
        -Message 'Chat realtime verification rows were not cleaned.'
    Assert-Equal -Actual $redisCleanupKeys -Expected 0 `
        -Message 'Chat realtime Redis keys were not cleaned.'
    Assert-Equal -Actual $consumerFailureCleanupRows -Expected 0L `
        -Message 'Chat realtime consumer failure rows were not cleaned.'
    Assert-Equal -Actual $rocketMqConsumerGroupResiduals.Count -Expected 0 `
        -Message (
            'Chat realtime verification left RocketMQ consumer groups: ' +
            ($rocketMqConsumerGroupResiduals -join ', '))
    Assert-Equal -Actual $rocketMqTopicResiduals.Count -Expected 0 `
        -Message (
            'Chat realtime verification left RocketMQ topics: ' +
            ($rocketMqTopicResiduals -join ', '))
    $evidence.cleanup = [ordered]@{
        mysqlRows = $cleanupRows
        redisKeys = $redisCleanupKeys
        consumerFailureRows = $consumerFailureCleanupRows
        residualRocketMqConsumerGroups = @($rocketMqConsumerGroupResiduals)
        residualRocketMqTopics = @($rocketMqTopicResiduals)
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 10 | Set-Content `
        -LiteralPath $evidencePath -Encoding utf8NoBOM
    Write-Host "M8 chat realtime verification passed: $evidencePath"
}
catch {
    Write-Error "M8 chat realtime verification failed. Logs: $($script:runDirectory). $($_.Exception.Message)"
    throw
}
finally {
    Close-WebSockets
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
        Clear-ChatData
    }
    catch {
        Write-Warning "Chat data cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-ChatRedis
    }
    catch {
        Write-Warning "Chat Redis cleanup failed: $($_.Exception.Message)"
    }
}
