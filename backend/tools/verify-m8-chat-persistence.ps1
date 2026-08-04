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
$script:runId = "m8chat$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"
$script:processes = [ordered]@{}
$script:databaseReady = $false
$script:chatPort = 18108
$script:gatewayPort = 18000

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
            $managedProcess = $script:processes[$ProcessName].process
            if ($managedProcess.HasExited) {
                throw "$ProcessName exited before $Uri became ready. ExitCode=$($managedProcess.ExitCode)"
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
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Stop-Applications {
    foreach ($entry in @($script:processes.GetEnumerator())) {
        $name = [string]$entry.Key
        $managed = $entry.Value
        $processId = [int]$managed.process.Id
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        if ($process.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            Write-Warning "Refused to stop PID $processId; command line no longer matches."
            continue
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            throw "Application process did not exit: $name/$processId"
        }
    }
    $script:processes = [ordered]@{}
    $expectedByPort = @{
        $script:gatewayPort = 'ecommerce-gateway.jar'
        $script:chatPort = 'chat-service.jar'
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
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "Managed application port remained active after cleanup: $port"
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
    Invoke-ChatMySql -Sql @"
DELETE FROM outbox_event;
DELETE FROM chat_message;
DELETE FROM conversation_member;
DELETE FROM chat_conversation;
"@ | Out-Null
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
    Join-Path $script:backendRoot ".run/m8-chat-persistence-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$chatJar = Join-Path $script:backendRoot 'services/chat-service/target/chat-service.jar'
$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway/target/ecommerce-gateway.jar'
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

    Assert-PortAvailable -Port $script:chatPort
    Assert-PortAvailable -Port $script:gatewayPort

    Start-Application -Name 'chat' -Jar $chatJar -Environment @{
        CHAT_SERVICE_PORT = [string]$script:chatPort
        SERVICE_INSTANCE_ID = "$($script:runId)-chat-1"
        SERVICE_RELEASE_ID = 'm8-chat-persistence-v1'
        CHAT_OUTBOX_ENABLED = 'false'
        CHAT_REALTIME_ENABLED = 'false'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:chatPort)/actuator/health/liveness" `
        -ProcessName 'chat'
    Clear-ChatData

    Start-Application -Name 'gateway' -Jar $gatewayJar -Environment @{
        GATEWAY_PORT = [string]$script:gatewayPort
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway-1"
        SERVICE_RELEASE_ID = 'm8-chat-persistence-v1'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:gatewayPort)/actuator/health/liveness" `
        -ProcessName 'gateway'
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:gatewayPort)/api/v1/chat/status" `
        -ProcessName 'gateway'

    $customerId = [long]7800000000000000001
    $agentId = [long]7800000000000000101
    $customerHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $customerId -Roles @('CUSTOMER'))"
    }
    $agentHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $agentId -Roles @('OPERATOR'))"
    }
    $baseUrl = "http://127.0.0.1:$($script:gatewayPort)/api/v1/chat"
    $conversationKey = "$($script:runId)-conversation"
    $customerMessageKey = "$($script:runId)-customer-message"
    $agentMessageKey = "$($script:runId)-agent-message"
    $customerContent = "M8 persistence proof $($script:runId)"

    $conversation = Invoke-JsonPost -Uri "$baseUrl/conversations" -Headers $customerHeaders -Body @{
        clientConversationId = $conversationKey
        subject = 'M8 reliable persistence verification'
        contextType = 'ORDER'
        contextId = "$($script:runId)-order"
    }
    $conversationId = [string]$conversation.data.id

    $customerMessageBody = @{
        clientMessageId = $customerMessageKey
        messageType = 'TEXT'
        content = $customerContent
    }
    $customerMessage = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders `
        -Body $customerMessageBody
    $customerReplay = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders `
        -Body $customerMessageBody
    Assert-Equal -Actual ([string]$customerReplay.data.id) `
        -Expected ([string]$customerMessage.data.id) `
        -Message 'Idempotent replay returned a different message.'

    $conflictResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders `
        -ContentType 'application/json' `
        -Body (@{
                clientMessageId = $customerMessageKey
                messageType = 'TEXT'
                content = "$customerContent changed"
            } | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck
    Assert-Equal -Actual $conflictResponse.StatusCode -Expected 409 `
        -Message 'Changed payload did not trigger an idempotency conflict.'

    $claim = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/claim" `
        -Headers $agentHeaders `
        -Body @{}
    Assert-Equal -Actual ([string]$claim.data.assignedAgentId) `
        -Expected ([string]$agentId) `
        -Message 'Conversation was not assigned to the expected agent.'

    Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/read" `
        -Headers $agentHeaders `
        -Body @{ lastReadMessageId = [string]$customerMessage.data.id } | Out-Null

    $agentMessage = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $agentHeaders `
        -Body @{
            clientMessageId = $agentMessageKey
            messageType = 'TEXT'
            content = 'The persisted customer message is visible to the assigned agent.'
        }
    Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/read" `
        -Headers $customerHeaders `
        -Body @{ lastReadMessageId = [string]$agentMessage.data.id } | Out-Null

    $conversationAfterRead = Invoke-RestMethod `
        -Method Get `
        -Uri "$baseUrl/conversations/$conversationId" `
        -Headers $customerHeaders
    Assert-Equal -Actual ([long]$conversationAfterRead.data.unreadCount) `
        -Expected 0L `
        -Message 'Customer unread count did not converge to zero.'

    $conversationCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM chat_conversation;')
    $messageCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM chat_message;')
    $outboxCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM outbox_event;')
    $pendingOutboxCount = [long](Get-ChatScalar "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING';")
    $readReceiptCount = [long](Get-ChatScalar "SELECT COUNT(*) FROM message_receipt WHERE state = 'READ';")
    $customerIdempotentCount = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM chat_message
WHERE conversation_id = $conversationId
  AND sender_id = $customerId
  AND client_message_id = '$customerMessageKey';
"@)
    $payloadContentLeakCount = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE payload LIKE '%$customerContent%';
"@)

    Assert-Equal -Actual $conversationCount -Expected 1L -Message 'Unexpected conversation count.'
    Assert-Equal -Actual $messageCount -Expected 2L -Message 'Unexpected message count.'
    Assert-Equal -Actual $outboxCount -Expected 2L -Message 'Unexpected Outbox count.'
    Assert-Equal -Actual $pendingOutboxCount -Expected 2L `
        -Message 'M8.1 Outbox rows must remain pending until the realtime publisher batch.'
    Assert-Equal -Actual $readReceiptCount -Expected 2L -Message 'Read receipts did not converge.'
    Assert-Equal -Actual $customerIdempotentCount -Expected 1L `
        -Message 'Client message idempotency did not hold in MySQL.'
    Assert-Equal -Actual $payloadContentLeakCount -Expected 0L `
        -Message 'Outbox payload unexpectedly copied private message content.'

    Clear-ChatData
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
    Assert-Equal -Actual $cleanupRows -Expected 0L -Message 'Chat verification rows were not cleaned.'

    $evidence = [ordered]@{
        runId = $script:runId
        verifiedAt = (Get-Date).ToUniversalTime().ToString('o')
        gatewayStatus = 'routed'
        mysql = [ordered]@{
            conversations = $conversationCount
            messages = $messageCount
            outboxPending = $pendingOutboxCount
            readReceipts = $readReceiptCount
            idempotentCustomerMessages = $customerIdempotentCount
            privateContentInOutbox = $payloadContentLeakCount
        }
        api = [ordered]@{
            conversationId = $conversationId
            customerMessageId = [string]$customerMessage.data.id
            agentMessageId = [string]$agentMessage.data.id
            duplicateReturnedSameMessage = $true
            changedPayloadConflictStatus = $conflictResponse.StatusCode
            finalCustomerUnreadCount = [long]$conversationAfterRead.data.unreadCount
        }
        cleanupRows = $cleanupRows
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
    Write-Host "M8 chat persistence verification passed: $evidencePath"
}
catch {
    foreach ($name in @($script:processes.Keys)) {
        $outLog = Join-Path $script:runDirectory "$name.out.log"
        $errLog = Join-Path $script:runDirectory "$name.err.log"
        if (Test-Path -LiteralPath $outLog) {
            Write-Host "--- $outLog ---"
            Get-Content -LiteralPath $outLog -Tail 60
        }
        if (Test-Path -LiteralPath $errLog) {
            Write-Host "--- $errLog ---"
            Get-Content -LiteralPath $errLog -Tail 60
        }
    }
    throw
}
finally {
    try {
        Clear-ChatData
    }
    catch {
        Write-Warning "Chat data cleanup failed: $($_.Exception.Message)"
    }
    Stop-Applications
}
