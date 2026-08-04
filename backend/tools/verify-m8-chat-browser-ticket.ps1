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
$script:runId = "m8ws$(([Guid]::NewGuid().ToString('N')).Substring(0, 12))"
$script:processes = [ordered]@{}
$script:webSockets = [System.Collections.Generic.List[Net.WebSockets.ClientWebSocket]]::new()
$script:databaseReady = $false
$script:chatPorts = @(18108, 18118, 18128)
$script:gatewayPort = 18000
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
        18000 = 'ecommerce-gateway.jar'
        18108 = 'chat-service.jar'
        18118 = 'chat-service.jar'
        18128 = 'chat-service.jar'
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
    Invoke-ChatMySql -Sql @"
DELETE FROM message_receipt;
DELETE FROM chat_attachment;
DELETE FROM chat_attachment_upload;
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

function Get-TicketRedisKeys {
    return @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "$($script:chatRedisPrefix)ws-ticket:*"
        ) | Where-Object { $_ })
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

function Invoke-Ticket {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Token
    )

    $response = Invoke-WebRequest `
        -Method Post `
        -Uri "$BaseUrl/websocket-tickets" `
        -Headers @{ Authorization = "Bearer $Token" } `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
    if ($response.StatusCode -ne 200) {
        throw "Ticket issuance failed with HTTP $($response.StatusCode)."
    }
    $body = $response.Content | ConvertFrom-Json
    return [pscustomobject]@{
        ticket = [string]$body.data.ticket
        targetPath = [string]$body.data.targetPath
        queryParameter = [string]$body.data.queryParameter
        expiresAt = [string]$body.data.expiresAt
        cacheControl = [string]($response.Headers['Cache-Control'] -join ',')
        pragma = [string]($response.Headers['Pragma'] -join ',')
    }
}

function Receive-WebSocketJson {
    param(
        [Parameter(Mandatory)][Net.WebSockets.ClientWebSocket]$Socket,
        [int]$TimeoutSeconds = 20
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
                throw "WebSocket closed before CONNECTED: $($Socket.CloseStatus)"
            }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        return [Text.Encoding]::UTF8.GetString($stream.ToArray()) | ConvertFrom-Json
    }
    finally {
        $timeout.Dispose()
        $stream.Dispose()
    }
}

function Open-ChatWebSocket {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [string]$Token
    )

    $socket = [Net.WebSockets.ClientWebSocket]::new()
    if ($Token) {
        $socket.Options.SetRequestHeader('Authorization', "Bearer $Token")
    }
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(20))
    try {
        $null = $socket.ConnectAsync([Uri]$Uri, $timeout.Token).GetAwaiter().GetResult()
    }
    finally {
        $timeout.Dispose()
    }
    $script:webSockets.Add($socket)
    $connected = Receive-WebSocketJson -Socket $socket
    if ($connected.type -ne 'CONNECTED') {
        throw "Expected CONNECTED WebSocket frame from $Uri."
    }
    return [pscustomobject]@{
        socket = $socket
        connected = $connected
    }
}

function Test-WebSocketRejected {
    param([Parameter(Mandatory)][string]$Uri)

    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $connectTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(10))
    try {
        try {
            $null = $socket.ConnectAsync(
                [Uri]$Uri,
                $connectTimeout.Token).GetAwaiter().GetResult()
        }
        catch [Net.WebSockets.WebSocketException] {
            return $true
        }
        catch [OperationCanceledException] {
            throw "Timed out opening rejected WebSocket probe: $Uri"
        }
        $buffer = [byte[]]::new(4096)
        $receiveTimeout = [Threading.CancellationTokenSource]::new(
            [TimeSpan]::FromSeconds(5))
        try {
            try {
                $result = $socket.ReceiveAsync(
                    [ArraySegment[byte]]::new($buffer),
                    $receiveTimeout.Token).GetAwaiter().GetResult()
            }
            catch [Net.WebSockets.WebSocketException] {
                return $true
            }
            catch [OperationCanceledException] {
                throw "Rejected WebSocket probe stayed open without a decision: $Uri"
            }
            if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) {
                return $true
            }
            $payload = [Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
            $frame = $payload | ConvertFrom-Json
            return [string]$frame.type -ne 'CONNECTED'
        }
        finally {
            $receiveTimeout.Dispose()
        }
    }
    finally {
        $connectTimeout.Dispose()
        $socket.Dispose()
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
    Join-Path $script:backendRoot ".run/m8-chat-browser-ticket-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$chatJar = Join-Path $script:backendRoot 'services/chat-service/target/chat-service.jar'
$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway/target/ecommerce-gateway.jar'
$javaHomePath = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$javaHomeExecutable = if ($javaHomePath) {
    Join-Path $javaHomePath 'bin/java.exe'
}
else {
    $null
}
$script:javaPath = if ($javaHomeExecutable -and (Test-Path -LiteralPath $javaHomeExecutable)) {
    [IO.Path]::GetFullPath($javaHomeExecutable)
}
else {
    (Get-Command java -ErrorAction Stop).Source
}

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

    $customerId = [long]7800000000000000201
    $operatorId = [long]7800000000000000202
    $customerToken = New-AccessToken -UserId $customerId -Roles @('CUSTOMER')
    $operatorToken = New-AccessToken -UserId $operatorId -Roles @('OPERATOR')

    $sharedEnvironment = @{
        APP_ENV = $script:runId
        CHAT_REALTIME_ENABLED = 'true'
        CHAT_OUTBOX_ENABLED = 'false'
        ECOMMERCE_CHAT_WEBSOCKET_TICKET_ENABLED = 'true'
        ECOMMERCE_CHAT_WEBSOCKET_TICKET_NAMESPACE = $script:runId
        ECOMMERCE_CHAT_WEBSOCKET_TICKET_TTL = '10s'
        CHAT_PRESENCE_TTL = '6s'
        CHAT_PRESENCE_REFRESH_INTERVAL = '2s'
        CHAT_REALTIME_INITIAL_DELAY = '30000'
        ROCKETMQ_ENDPOINTS = '127.0.0.1:18082'
    }
    $chatAEnvironment = @{} + $sharedEnvironment
    $chatAEnvironment['CHAT_SERVICE_PORT'] = '18108'
    $chatAEnvironment['SERVICE_INSTANCE_ID'] = "$($script:runId)-a"
    $chatAEnvironment['SERVICE_RELEASE_ID'] = 'm8-chat-browser-ticket-v1'
    Start-Application -Name 'chat-a' -Jar $chatJar -Environment $chatAEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18108/actuator/health/liveness' -ProcessName 'chat-a'

    $chatBEnvironment = @{} + $sharedEnvironment
    $chatBEnvironment['CHAT_SERVICE_PORT'] = '18118'
    $chatBEnvironment['SERVICE_INSTANCE_ID'] = "$($script:runId)-b"
    $chatBEnvironment['SERVICE_RELEASE_ID'] = 'm8-chat-browser-ticket-v1'
    Start-Application -Name 'chat-b' -Jar $chatJar -Environment $chatBEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18118/actuator/health/liveness' -ProcessName 'chat-b'

    Start-Application -Name 'gateway' -Jar $gatewayJar -Environment @{
        APP_ENV = $script:runId
        GATEWAY_PORT = [string]$script:gatewayPort
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
        SERVICE_RELEASE_ID = 'm8-chat-browser-ticket-v1'
    }
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/actuator/health/liveness' `
        -ProcessName 'gateway'
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/api/v1/chat/status' `
        -ProcessName 'gateway'

    $gatewayBaseUrl = 'http://127.0.0.1:18000/api/v1/chat'
    $chatABaseUrl = 'http://127.0.0.1:18108/api/v1/chat'
    $gatewayTicket = Invoke-Ticket -BaseUrl $gatewayBaseUrl -Token $customerToken
    Assert-Equal -Actual $gatewayTicket.targetPath -Expected '/ws/chat' `
        -Message 'Ticket target path was not bound to the Chat WebSocket endpoint.'
    Assert-Equal -Actual $gatewayTicket.queryParameter -Expected 'ticket' `
        -Message 'Ticket query parameter contract changed.'
    Assert-True -Condition ($gatewayTicket.ticket -match '^[A-Za-z0-9_-]{43}$') `
        -Message 'Ticket is not a 32-byte opaque Base64URL value.'
    Assert-True -Condition ($gatewayTicket.ticket -notmatch '\.') `
        -Message 'Browser handshake ticket must not be a JWT.'
    Assert-True -Condition ($gatewayTicket.cacheControl -match 'no-store') `
        -Message 'Ticket response did not disable caching.'
    Assert-Equal -Actual $gatewayTicket.pragma -Expected 'no-cache' `
        -Message 'Ticket response did not include Pragma no-cache.'

    $issuedKeys = @(Get-TicketRedisKeys)
    Assert-Equal -Actual $issuedKeys.Count -Expected 1 `
        -Message 'Ticket issuance did not create exactly one Redis fact.'
    $storedPayloadLines = @(Invoke-Redis -Arguments @('GET', [string]$issuedKeys[0]))
    Assert-Equal -Actual $storedPayloadLines.Count -Expected 1 `
        -Message 'Ticket Redis payload was not a single value.'
    $storedPayload = [string]$storedPayloadLines[0]
    Assert-True -Condition (-not $storedPayload.Contains($gatewayTicket.ticket)) `
        -Message 'Redis ticket payload contains the raw bearer value.'
    $issuedTtlLines = @(Invoke-Redis -Arguments @('TTL', [string]$issuedKeys[0]))
    Assert-Equal -Actual $issuedTtlLines.Count -Expected 1 `
        -Message 'Ticket Redis TTL was not a single value.'
    $issuedTtl = [int]$issuedTtlLines[0]
    Assert-True -Condition ($issuedTtl -gt 0 -and $issuedTtl -le 10) `
        -Message 'Redis ticket TTL is outside the configured short lifetime.'

    $gatewayTicketUri = 'ws://127.0.0.1:18000/ws/chat?ticket=' `
        + [Uri]::EscapeDataString($gatewayTicket.ticket)
    $gatewaySocket = Open-ChatWebSocket -Uri $gatewayTicketUri
    $gatewayNode = [string]$gatewaySocket.connected.nodeId
    Assert-True -Condition ($gatewayNode -in @(
            "$($script:runId)-a",
            "$($script:runId)-b"
        )) -Message 'Gateway did not route the browser WebSocket to a managed Chat node.'
    Assert-True -Condition (
        @($gatewaySocket.connected.roles) -contains 'ROLE_CUSTOMER'
    ) -Message 'Browser ticket did not preserve the authenticated customer role.'
    $keysAfterConsumption = @(Get-TicketRedisKeys)
    Assert-Equal -Actual $keysAfterConsumption.Count -Expected 0 `
        -Message 'Consumed ticket remained in Redis.'
    $replayRejected = Test-WebSocketRejected -Uri $gatewayTicketUri
    Assert-True -Condition $replayRejected `
        -Message 'Consumed browser ticket was accepted a second time.'

    $crossTicket = Invoke-Ticket -BaseUrl $chatABaseUrl -Token $operatorToken
    $crossTicketUri = 'ws://127.0.0.1:18118/ws/chat?ticket=' `
        + [Uri]::EscapeDataString($crossTicket.ticket)
    $crossSocket = Open-ChatWebSocket -Uri $crossTicketUri
    Assert-Equal -Actual ([string]$crossSocket.connected.nodeId) `
        -Expected "$($script:runId)-b" `
        -Message 'Ticket issued by Chat node A was not consumed by Chat node B.'
    Assert-True -Condition (
        @($crossSocket.connected.roles) -contains 'ROLE_OPERATOR'
    ) -Message 'Cross-instance ticket did not preserve the operator role.'

    $headerSocket = Open-ChatWebSocket `
        -Uri 'ws://127.0.0.1:18108/ws/chat' `
        -Token $customerToken
    Assert-Equal -Actual ([string]$headerSocket.connected.nodeId) `
        -Expected "$($script:runId)-a" `
        -Message 'Existing Authorization-header WebSocket compatibility regressed.'

    $expiredTicket = Invoke-Ticket -BaseUrl $gatewayBaseUrl -Token $customerToken
    Start-Sleep -Seconds 11
    $expiredTicketUri = 'ws://127.0.0.1:18000/ws/chat?ticket=' `
        + [Uri]::EscapeDataString($expiredTicket.ticket)
    $expiredRejected = Test-WebSocketRejected -Uri $expiredTicketUri
    Assert-True -Condition $expiredRejected `
        -Message 'Expired browser ticket was accepted.'
    $keysAfterExpiry = @(Get-TicketRedisKeys)
    Assert-Equal -Actual $keysAfterExpiry.Count -Expected 0 `
        -Message 'Expired ticket key remained in Redis.'

    $chatFailureEnvironment = @{} + $sharedEnvironment
    $chatFailureEnvironment['CHAT_SERVICE_PORT'] = '18128'
    $chatFailureEnvironment['SERVICE_INSTANCE_ID'] = "$($script:runId)-redis-failure"
    $chatFailureEnvironment['SERVICE_RELEASE_ID'] = 'm8-chat-browser-ticket-v1'
    $chatFailureEnvironment['SPRING_CLOUD_NACOS_DISCOVERY_ENABLED'] = 'false'
    $chatFailureEnvironment['REDIS_PORT'] = '1'
    Start-Application -Name 'chat-redis-failure' -Jar $chatJar `
        -Environment $chatFailureEnvironment
    Wait-HttpOk -Uri 'http://127.0.0.1:18128/actuator/health/liveness' `
        -ProcessName 'chat-redis-failure'

    $failureIssue = Invoke-WebRequest `
        -Method Post `
        -Uri 'http://127.0.0.1:18128/api/v1/chat/websocket-tickets' `
        -Headers @{ Authorization = "Bearer $customerToken" } `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
    $failureIssueBody = $failureIssue.Content | ConvertFrom-Json
    Assert-Equal -Actual $failureIssue.StatusCode -Expected 503 `
        -Message 'Redis-unavailable ticket issuance did not fail closed.'
    Assert-Equal -Actual ([string]$failureIssueBody.code) `
        -Expected 'CHAT_REALTIME_UNAVAILABLE' `
        -Message 'Redis-unavailable ticket issuance returned the wrong error code.'

    $survivingTicket = Invoke-Ticket -BaseUrl $chatABaseUrl -Token $customerToken
    $failureTicketUri = 'ws://127.0.0.1:18128/ws/chat?ticket=' `
        + [Uri]::EscapeDataString($survivingTicket.ticket)
    $failureHandshakeRejected = Test-WebSocketRejected -Uri $failureTicketUri
    Assert-True -Condition $failureHandshakeRejected `
        -Message 'Redis-unavailable Chat node accepted a browser ticket.'
    $recoveredTicketUri = 'ws://127.0.0.1:18118/ws/chat?ticket=' `
        + [Uri]::EscapeDataString($survivingTicket.ticket)
    $recoveredTicketSocket = Open-ChatWebSocket -Uri $recoveredTicketUri
    Assert-Equal -Actual ([string]$recoveredTicketSocket.connected.nodeId) `
        -Expected "$($script:runId)-b" `
        -Message 'Redis failure consumed or corrupted the ticket before healthy-node recovery.'

    $ticketValues = @(
        $gatewayTicket.ticket,
        $crossTicket.ticket,
        $expiredTicket.ticket,
        $survivingTicket.ticket
    )
    Close-WebSockets
    Stop-AllApplications
    $ticketInLogs = 0
    foreach ($log in Get-ChildItem -LiteralPath $script:runDirectory -File -Filter '*.log') {
        $text = [IO.File]::ReadAllText($log.FullName)
        foreach ($ticketValue in $ticketValues) {
            if ($text.Contains($ticketValue)) {
                $ticketInLogs++
            }
        }
    }
    Assert-Equal -Actual $ticketInLogs -Expected 0 `
        -Message 'Raw browser ticket was written to an application log.'

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
    $redisCleanupKeys = @(Invoke-Redis -Arguments @(
            '--scan',
            '--pattern',
            "$($script:chatRedisPrefix)*"
        ) | Where-Object { $_ }).Count
    $portResidue = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object {
                $_.LocalPort -in (@($script:gatewayPort) + $script:chatPorts)
            }).Count
    $jvmResidue = @(Get-CimInstance Win32_Process |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like '*PlainJournal*' -and
                (
                    $_.CommandLine -like '*chat-service.jar*' -or
                    $_.CommandLine -like '*ecommerce-gateway.jar*'
                )
            }).Count
    Assert-Equal -Actual $cleanupRows -Expected 0L `
        -Message 'Browser ticket verification left Chat business rows.'
    Assert-Equal -Actual $redisCleanupKeys -Expected 0 `
        -Message 'Browser ticket verification left Redis keys.'
    Assert-Equal -Actual $portResidue -Expected 0 `
        -Message 'Browser ticket verification left application ports listening.'
    Assert-Equal -Actual $jvmResidue -Expected 0 `
        -Message 'Browser ticket verification left managed JVMs.'

    $evidence = [ordered]@{
        runId = $script:runId
        gateway = [ordered]@{
            status = 'routed'
            connectedNode = $gatewayNode
        }
        issuance = [ordered]@{
            opaqueTicketLength = $gatewayTicket.ticket.Length
            rawTicketStoredInRedis = $false
            cacheControlNoStore = $true
            pragmaNoCache = $true
            redisTtlSeconds = $issuedTtl
        }
        singleUse = [ordered]@{
            replayRejected = $replayRejected
            redisKeysAfterConsumption = 0
        }
        crossInstance = [ordered]@{
            issuedByNode = "$($script:runId)-a"
            consumedByNode = [string]$crossSocket.connected.nodeId
            operatorRolePreserved = $true
        }
        expiry = [ordered]@{
            expiredTicketRejected = $expiredRejected
            redisKeysAfterExpiry = 0
        }
        redisFailure = [ordered]@{
            issuanceStatus = $failureIssue.StatusCode
            issuanceCode = [string]$failureIssueBody.code
            handshakeRejected = $failureHandshakeRejected
            healthyNodeConsumedSameTicket = $true
        }
        compatibility = [ordered]@{
            authorizationHeaderAccepted = $true
        }
        privacy = [ordered]@{
            rawTicketInApplicationLogs = $ticketInLogs
        }
        cleanup = [ordered]@{
            mysqlRows = $cleanupRows
            redisKeys = $redisCleanupKeys
            portListeners = $portResidue
            managedJvms = $jvmResidue
        }
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 10 | Set-Content `
        -LiteralPath $evidencePath -Encoding utf8NoBOM
    Write-Host "M8 Chat browser ticket verification passed: $evidencePath"
}
catch {
    Write-Warning ("M8 Chat browser ticket verification failed. Logs: " +
        "$($script:runDirectory). $($_.Exception.Message)")
    throw
}
finally {
    Close-WebSockets
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
    Stop-AllApplications
}
