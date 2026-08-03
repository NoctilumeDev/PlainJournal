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
$script:runId = "m8att$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$script:processes = [ordered]@{}
$script:databaseReady = $false
$script:objectKeys = [System.Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal)
$script:clamAvStateCaptured = $false
$script:clamAvContainerExisted = $false
$script:clamAvInitiallyRunning = $false
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
            Write-Warning "Refused to stop PID $processId; command line no longer matches $name."
            continue
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
    $script:processes = [ordered]@{}

    $expectedByPort = @{
        $script:gatewayPort = 'ecommerce-gateway-0.1.0-SNAPSHOT.jar'
        $script:chatPort = 'chat-service-0.1.0-SNAPSHOT.jar'
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
    $conversationTable = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'chat_conversation';
"@)
    if ($conversationTable -eq 0) {
        return
    }
    $uploadTable = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'chat_attachment_upload';
"@)
    $scanAuditTable = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'chat_attachment_scan_retry_audit';
"@)
    Invoke-ChatMySql -Sql 'DELETE FROM message_receipt; DELETE FROM chat_attachment;' | Out-Null
    if ($scanAuditTable -eq 1) {
        Invoke-ChatMySql -Sql 'DELETE FROM chat_attachment_scan_retry_audit;' | Out-Null
    }
    if ($uploadTable -eq 1) {
        Invoke-ChatMySql -Sql 'DELETE FROM chat_attachment_upload;' | Out-Null
    }
    Invoke-ChatMySql -Sql @"
DELETE FROM outbox_event;
DELETE FROM chat_message;
DELETE FROM conversation_member;
DELETE FROM chat_conversation;
"@ | Out-Null
}

function Remove-ChatObjectKey {
    param([string]$ObjectKey)

    if (-not $ObjectKey) {
        return
    }
    $expectedPrefixes = @(
        "quarantine/chat/$($script:runId)/",
        "objects/chat/$($script:runId)/"
    )
    $matchesRunScope = @($expectedPrefixes | Where-Object {
            $ObjectKey.StartsWith($_, [StringComparison]::Ordinal)
        }).Count -gt 0
    if (-not $matchesRunScope) {
        throw "Refused to remove unexpected MinIO object key: $ObjectKey"
    }
    docker exec `
        -e "ATTACHMENT_OBJECT_KEY=$ObjectKey" `
        plainjournal-minio sh -c `
        'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && (mc rm --force "local/chat-attachments/$ATTACHMENT_OBJECT_KEY" >/dev/null 2>&1 || true)'
    if ($LASTEXITCODE -ne 0) {
        throw 'Chat attachment object cleanup failed.'
    }
}

function Remove-ChatObjects {
    foreach ($objectKey in @($script:objectKeys)) {
        Remove-ChatObjectKey -ObjectKey $objectKey
    }
    $script:objectKeys.Clear()
}

function Get-ChatObjectCount {
    $total = 0
    foreach ($prefix in @(
            "quarantine/chat/$($script:runId)/",
            "objects/chat/$($script:runId)/")) {
        $output = docker exec `
            -e "ATTACHMENT_PREFIX=$prefix" `
            plainjournal-minio sh -c `
            'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "local/chat-attachments/$ATTACHMENT_PREFIX" 2>/dev/null || true'
        if ($LASTEXITCODE -ne 0) {
            throw 'Chat attachment object count failed.'
        }
        $total += @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
    }
    return $total
}

function Get-ChatObjectCountForPrefix {
    param([Parameter(Mandatory)][string]$Prefix)

    $output = docker exec `
        -e "ATTACHMENT_PREFIX=$Prefix" `
        plainjournal-minio sh -c `
        'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "local/chat-attachments/$ATTACHMENT_PREFIX" 2>/dev/null || true'
    if ($LASTEXITCODE -ne 0) {
        throw 'Chat attachment object count failed.'
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
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

function Get-AttachmentUpload {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$ConversationId,
        [Parameter(Mandatory)][string]$UploadId,
        [Parameter(Mandatory)][hashtable]$Headers
    )

    return Invoke-RestMethod -Method Get `
        -Uri "$BaseUrl/conversations/$ConversationId/attachments/$UploadId" `
        -Headers $Headers
}

function Wait-AttachmentStatus {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$ConversationId,
        [Parameter(Mandatory)][string]$UploadId,
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ExpectedStatus,
        [int]$TimeoutSeconds = 60
    )

    $script:lastAttachmentUpload = $null
    Wait-Until -Description "attachment $UploadId to become $ExpectedStatus" `
        -TimeoutSeconds $TimeoutSeconds -Condition {
            $script:lastAttachmentUpload = Get-AttachmentUpload `
                -BaseUrl $BaseUrl `
                -ConversationId $ConversationId `
                -UploadId $UploadId `
                -Headers $Headers
            return [string]$script:lastAttachmentUpload.data.status -eq $ExpectedStatus
        }
    return $script:lastAttachmentUpload
}

function Test-ContainerExists {
    param([Parameter(Mandatory)][string]$Name)

    docker inspect $Name *> $null
    return $LASTEXITCODE -eq 0
}

function Test-ContainerRunning {
    param([Parameter(Mandatory)][string]$Name)

    if (-not (Test-ContainerExists -Name $Name)) {
        return $false
    }
    $running = docker inspect -f '{{.State.Running}}' $Name
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to inspect container state: $Name"
    }
    return [string]$running -eq 'true'
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 300
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'unknown'
    do {
        if (-not (Test-ContainerExists -Name $Name)) {
            $lastState = 'missing'
        }
        else {
            $state = docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $Name
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to inspect container health: $Name"
            }
            $lastState = [string]$state
            if ($lastState -eq 'running|healthy') {
                return
            }
            if ($lastState.StartsWith('exited|', [StringComparison]::Ordinal) -or
                    $lastState.StartsWith('dead|', [StringComparison]::Ordinal)) {
                $logs = docker logs --tail 80 $Name 2>&1
                throw "Container $Name stopped before becoming healthy. State=$lastState Logs=$logs"
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for container $Name to become healthy. Last state=$lastState"
}

function Start-ClamAv {
    if (Test-ContainerExists -Name 'plainjournal-clamav') {
        if (-not (Test-ContainerRunning -Name 'plainjournal-clamav')) {
            docker start plainjournal-clamav | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Failed to start the existing ClamAV container.'
            }
        }
    }
    else {
        Push-Location $script:composeRoot
        try {
            docker compose --profile m8-malware-scan up -d clamav
            if ($LASTEXITCODE -ne 0) {
                throw 'Failed to create the on-demand ClamAV container.'
            }
        }
        finally {
            Pop-Location
        }
    }
    Wait-ContainerHealthy -Name 'plainjournal-clamav'
}

function Stop-ClamAvForFault {
    if (-not (Test-ContainerRunning -Name 'plainjournal-clamav')) {
        throw 'ClamAV was not running before fault injection.'
    }
    docker stop --time 20 plainjournal-clamav | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to stop ClamAV for fault injection.'
    }
    if (Test-ContainerRunning -Name 'plainjournal-clamav') {
        throw 'ClamAV remained running after fault injection.'
    }
}

function Restore-ClamAvInitialState {
    if (-not $script:clamAvStateCaptured) {
        return
    }
    if ($script:clamAvInitiallyRunning) {
        if (-not (Test-ContainerRunning -Name 'plainjournal-clamav')) {
            docker start plainjournal-clamav | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Failed to restore the initially running ClamAV container.'
            }
            Wait-ContainerHealthy -Name 'plainjournal-clamav'
        }
        return
    }
    if (-not (Test-ContainerExists -Name 'plainjournal-clamav')) {
        return
    }
    if (Test-ContainerRunning -Name 'plainjournal-clamav') {
        docker stop --time 20 plainjournal-clamav | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to stop the on-demand ClamAV container.'
        }
    }
    if (-not $script:clamAvContainerExisted) {
        Push-Location $script:composeRoot
        try {
            docker compose --profile m8-malware-scan rm -f clamav | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Failed to remove the on-demand ClamAV container.'
            }
        }
        finally {
            Pop-Location
        }
    }
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
    Join-Path $script:backendRoot ".run/m8-chat-attachments-$($script:runId)"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$script:composeRoot = Join-Path $script:repositoryRoot 'deploy/docker'
$chatJar = Join-Path $script:backendRoot 'services/chat-service/target/chat-service-0.1.0-SNAPSHOT.jar'
$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway/target/ecommerce-gateway-0.1.0-SNAPSHOT.jar'
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
    $script:clamAvContainerExisted = Test-ContainerExists -Name 'plainjournal-clamav'
    $script:clamAvInitiallyRunning = Test-ContainerRunning -Name 'plainjournal-clamav'
    $script:clamAvStateCaptured = $true
    Start-ClamAv

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
    Clear-ChatData
    Assert-Equal -Actual (Get-ChatObjectCount) -Expected 0 `
        -Message 'Run-scoped MinIO prefix was not empty before verification.'

    Start-Application -Name 'chat' -Jar $chatJar -Environment @{
        APP_ENV = $script:runId
        CHAT_SERVICE_PORT = [string]$script:chatPort
        SERVICE_INSTANCE_ID = "$($script:runId)-chat"
        SERVICE_RELEASE_ID = 'm8-chat-attachments-v1'
        CHAT_REALTIME_ENABLED = 'false'
        CHAT_OUTBOX_ENABLED = 'false'
        ECOMMERCE_CHAT_ATTACHMENTS_INTENT_TTL = '30m'
        ECOMMERCE_CHAT_ATTACHMENTS_CLEANUP_INITIAL_DELAY = '0'
        ECOMMERCE_CHAT_ATTACHMENTS_CLEANUP_FIXED_DELAY = '1000'
        ECOMMERCE_CHAT_ATTACHMENTS_CLEANUP_BATCH_SIZE = '20'
        ECOMMERCE_CHAT_ATTACHMENTS_CLEANUP_RECOVERY_AGE = '5s'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_ENABLED = 'true'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_INITIAL_DELAY = '0'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_FIXED_DELAY = '500'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_BATCH_SIZE = '5'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_LEASE_DURATION = '15s'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_MAXIMUM_ATTEMPTS = '2'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_CONNECT_TIMEOUT = '1s'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_READ_TIMEOUT = '30s'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_HOST = '127.0.0.1'
        ECOMMERCE_CHAT_ATTACHMENTS_SCAN_PORT = '13310'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:chatPort)/actuator/health/liveness" `
        -ProcessName 'chat'

    Start-Application -Name 'gateway' -Jar $gatewayJar -Environment @{
        APP_ENV = $script:runId
        GATEWAY_PORT = [string]$script:gatewayPort
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
        SERVICE_RELEASE_ID = 'm8-chat-attachments-v1'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:gatewayPort)/actuator/health/liveness" `
        -ProcessName 'gateway'
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:gatewayPort)/api/v1/chat/status" `
        -ProcessName 'gateway'

    $customerId = [long]7800000000000000201
    $otherCustomerId = [long]7800000000000000202
    $customerHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $customerId -Roles @('CUSTOMER'))"
    }
    $otherCustomerHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $otherCustomerId -Roles @('CUSTOMER'))"
    }
    $adminId = [long]7800000000000000901
    $adminHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId $adminId -Roles @('ADMIN'))"
    }
    $baseUrl = "http://127.0.0.1:$($script:gatewayPort)/api/v1/chat"
    $conversation = Invoke-JsonPost -Uri "$baseUrl/conversations" `
        -Headers $customerHeaders -Body @{
            clientConversationId = "$($script:runId)-conversation"
            subject = 'M8 attachment verification'
        }
    $conversationId = [string]$conversation.data.id

    $pngBytes = [byte[]]@(
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52
    )
    $uploadBody = @{
        clientUploadId = "$($script:runId)-upload"
        fileName = 'proof.png'
        mimeType = 'image/png'
        sizeBytes = $pngBytes.Length
    }
    $upload = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/upload-intents" `
        -Headers $customerHeaders -Body $uploadBody
    $uploadReplay = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/upload-intents" `
        -Headers $customerHeaders -Body $uploadBody
    Assert-Equal -Actual ([string]$uploadReplay.data.id) -Expected ([string]$upload.data.id) `
        -Message 'Attachment upload intent replay returned a different fact.'
    $uploadId = [string]$upload.data.id
    $cleanObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $uploadId;"
    $script:objectKeys.Add($cleanObjectKey) | Out-Null
    if (-not $cleanObjectKey.StartsWith(
            "quarantine/chat/$($script:runId)/",
            [StringComparison]::Ordinal)) {
        throw "Attachment object key is not quarantine scoped: $cleanObjectKey"
    }

    $missingObjectResponse = Invoke-WebRequest -Method Post `
        -Uri "$baseUrl/conversations/$conversationId/attachments/$uploadId/confirm" `
        -Headers $customerHeaders -SkipHttpErrorCheck
    Assert-Equal -Actual $missingObjectResponse.StatusCode -Expected 409 `
        -Message 'Confirming a missing object did not return result-not-ready.'

    $uploadResponse = Invoke-WebRequest -Method Put -Uri $upload.data.uploadUrl `
        -ContentType 'image/png' -Body $pngBytes -SkipHttpErrorCheck
    if ($uploadResponse.StatusCode -notin @(200, 204)) {
        throw "Pre-signed chat attachment upload failed: HTTP $($uploadResponse.StatusCode)"
    }
    $confirmed = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/$uploadId/confirm" `
        -Headers $customerHeaders -Body @{}
    Assert-Equal -Actual ([string]$confirmed.data.status) -Expected 'SCAN_PENDING' `
        -Message 'Confirmed attachment bypassed the malware scan queue.'
    $sealedObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $uploadId;"
    $script:objectKeys.Add($sealedObjectKey) | Out-Null
    if (-not $sealedObjectKey.StartsWith(
            "objects/chat/$($script:runId)/",
            [StringComparison]::Ordinal)) {
        throw "Attachment object key was not sealed: $sealedObjectKey"
    }
    $cleanScan = Wait-AttachmentStatus `
        -BaseUrl $baseUrl `
        -ConversationId $conversationId `
        -UploadId $uploadId `
        -Headers $customerHeaders `
        -ExpectedStatus 'READY'
    Assert-Equal -Actual ([int]$cleanScan.data.scanAttempts) -Expected 1 `
        -Message 'Clean attachment was not scanned exactly once.'
    Assert-Equal -Actual ([string]$cleanScan.data.scanEngine) -Expected 'ClamAV' `
        -Message 'Clean attachment did not record the real scanner engine.'

    $messageBody = @{
        clientMessageId = "$($script:runId)-message"
        messageType = 'IMAGE'
        content = 'Verified attachment'
        attachmentUploadIds = @($uploadId)
    }
    $message = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -Body $messageBody
    $messageReplay = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -Body $messageBody
    Assert-Equal -Actual ([string]$messageReplay.data.id) -Expected ([string]$message.data.id) `
        -Message 'Attachment message replay returned a different fact.'
    Assert-Equal -Actual $message.data.attachments.Count -Expected 1 `
        -Message 'Attachment metadata was not returned with the message.'
    $messageId = [string]$message.data.id
    $attachmentId = [string]$message.data.attachments[0].id

    $reuseResponse = Invoke-WebRequest -Method Post `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -ContentType 'application/json' `
        -Body (@{
                clientMessageId = "$($script:runId)-reuse"
                messageType = 'IMAGE'
                content = 'Reuse must fail'
                attachmentUploadIds = @($uploadId)
            } | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck
    Assert-Equal -Actual $reuseResponse.StatusCode -Expected 409 `
        -Message 'The same upload was attached to a second message.'

    $unauthorizedDownload = Invoke-WebRequest -Method Get `
        -Uri "$baseUrl/conversations/$conversationId/messages/$messageId/attachments/$attachmentId/download" `
        -Headers $otherCustomerHeaders -SkipHttpErrorCheck
    Assert-Equal -Actual $unauthorizedDownload.StatusCode -Expected 403 `
        -Message 'A non-member received an attachment download URL.'

    $downloadIntent = Invoke-RestMethod -Method Get `
        -Uri "$baseUrl/conversations/$conversationId/messages/$messageId/attachments/$attachmentId/download" `
        -Headers $customerHeaders
    $downloadPath = Join-Path $script:runDirectory 'downloaded-proof.png'
    Invoke-WebRequest -Method Get -Uri $downloadIntent.data.downloadUrl -OutFile $downloadPath
    $downloadedBytes = [IO.File]::ReadAllBytes($downloadPath)
    Assert-Equal -Actual ([Convert]::ToHexString($downloadedBytes)) `
        -Expected ([Convert]::ToHexString($pngBytes)) `
        -Message 'Downloaded attachment bytes differ from the uploaded object.'

    $tamperedBytes = $pngBytes.Clone()
    $tamperedBytes[$tamperedBytes.Length - 1] = 0x01
    $tamperUploadResponse = Invoke-WebRequest -Method Put -Uri $upload.data.uploadUrl `
        -ContentType 'image/png' -Body $tamperedBytes -SkipHttpErrorCheck
    if ($tamperUploadResponse.StatusCode -notin @(200, 204)) {
        throw "Tamper verification upload failed: HTTP $($tamperUploadResponse.StatusCode)"
    }
    $tamperedDownloadResponse = Invoke-WebRequest -Method Get `
        -Uri "$baseUrl/conversations/$conversationId/messages/$messageId/attachments/$attachmentId/download" `
        -Headers $customerHeaders -SkipHttpErrorCheck
    Assert-Equal -Actual $tamperedDownloadResponse.StatusCode -Expected 200 `
        -Message 'Overwriting the expired quarantine URL affected the sealed attachment.'
    $tamperedDownloadIntent = $tamperedDownloadResponse.Content | ConvertFrom-Json
    $tamperedDownloadPath = Join-Path $script:runDirectory 'downloaded-after-quarantine-overwrite.png'
    Invoke-WebRequest -Method Get `
        -Uri $tamperedDownloadIntent.data.downloadUrl `
        -OutFile $tamperedDownloadPath
    $tamperedDownloadedBytes = [IO.File]::ReadAllBytes($tamperedDownloadPath)
    Assert-Equal -Actual ([Convert]::ToHexString($tamperedDownloadedBytes)) `
        -Expected ([Convert]::ToHexString($pngBytes)) `
        -Message 'Sealed attachment bytes changed after the old quarantine URL was reused.'

    $uploadCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM chat_attachment_upload;')
    $attachmentCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM chat_attachment;')
    $messageCount = [long](Get-ChatScalar 'SELECT COUNT(*) FROM chat_message;')
    $attachedStatus = Get-ChatScalar `
        "SELECT status FROM chat_attachment_upload WHERE id = $uploadId;"
    $objectKeyLeak = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM outbox_event
WHERE payload LIKE '%quarantine/chat/$($script:runId)/%';
"@)
    $checksumMatch = [long](Get-ChatScalar @"
SELECT COUNT(*)
FROM chat_attachment a
JOIN chat_attachment_upload u ON u.id = a.upload_id
WHERE a.id = $attachmentId
  AND CHAR_LENGTH(a.sha256) = 64
  AND a.sha256 = u.verified_sha256;
"@)
    Assert-Equal -Actual $uploadCount -Expected 1L -Message 'Unexpected upload-intent count.'
    Assert-Equal -Actual $attachmentCount -Expected 1L -Message 'Unexpected attachment count.'
    Assert-Equal -Actual $messageCount -Expected 1L -Message 'Unexpected message count.'
    Assert-Equal -Actual $attachedStatus -Expected 'ATTACHED' `
        -Message 'Upload intent did not become ATTACHED.'
    Assert-Equal -Actual $objectKeyLeak -Expected 0L `
        -Message 'Attachment object key leaked into the Chat Outbox.'
    Assert-Equal -Actual $checksumMatch -Expected 1L `
        -Message 'Attachment checksum snapshots did not match.'

    $eicarBytes = [Text.Encoding]::ASCII.GetBytes(
        'X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*')
    $infectedUpload = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/upload-intents" `
        -Headers $customerHeaders -Body @{
            clientUploadId = "$($script:runId)-infected"
            fileName = 'eicar.txt'
            mimeType = 'text/plain'
            sizeBytes = $eicarBytes.Length
        }
    $infectedUploadId = [string]$infectedUpload.data.id
    $infectedObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $infectedUploadId;"
    $script:objectKeys.Add($infectedObjectKey) | Out-Null
    if (-not $infectedObjectKey.StartsWith(
            "quarantine/chat/$($script:runId)/",
            [StringComparison]::Ordinal)) {
        throw "Infected attachment object key is not quarantine scoped: $infectedObjectKey"
    }
    $infectedPut = Invoke-WebRequest -Method Put -Uri $infectedUpload.data.uploadUrl `
        -ContentType 'text/plain' -Body $eicarBytes -SkipHttpErrorCheck
    if ($infectedPut.StatusCode -notin @(200, 204)) {
        throw "EICAR upload failed: HTTP $($infectedPut.StatusCode)"
    }
    $infectedConfirmed = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/$infectedUploadId/confirm" `
        -Headers $customerHeaders -Body @{}
    Assert-Equal -Actual ([string]$infectedConfirmed.data.status) -Expected 'SCAN_PENDING' `
        -Message 'EICAR attachment bypassed the malware scan queue.'
    $infectedSealedObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $infectedUploadId;"
    $script:objectKeys.Add($infectedSealedObjectKey) | Out-Null
    if (-not $infectedSealedObjectKey.StartsWith(
            "objects/chat/$($script:runId)/",
            [StringComparison]::Ordinal)) {
        throw "Infected attachment object key was not sealed: $infectedSealedObjectKey"
    }
    $infectedScan = Wait-AttachmentStatus `
        -BaseUrl $baseUrl `
        -ConversationId $conversationId `
        -UploadId $infectedUploadId `
        -Headers $customerHeaders `
        -ExpectedStatus 'INFECTED'
    if ([string]::IsNullOrWhiteSpace([string]$infectedScan.data.scanSignature)) {
        throw 'ClamAV detected EICAR without recording a malware signature.'
    }
    $infectedMessageResponse = Invoke-WebRequest -Method Post `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -ContentType 'application/json' `
        -Body (@{
                clientMessageId = "$($script:runId)-infected-message"
                messageType = 'FILE'
                content = 'Infected content must remain blocked'
                attachmentUploadIds = @($infectedUploadId)
            } | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck
    Assert-Equal -Actual $infectedMessageResponse.StatusCode -Expected 409 `
        -Message 'An infected attachment was accepted into a message.'
    $infectedError = $infectedMessageResponse.Content | ConvertFrom-Json
    Assert-Equal -Actual ([string]$infectedError.code) -Expected 'ATTACHMENT_INFECTED' `
        -Message 'Infected attachment rejection returned the wrong error.'

    Stop-ClamAvForFault
    $retryBytes = [Text.Encoding]::UTF8.GetBytes('clean attachment after scanner recovery')
    $retryUpload = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/upload-intents" `
        -Headers $customerHeaders -Body @{
            clientUploadId = "$($script:runId)-retry"
            fileName = 'retry.txt'
            mimeType = 'text/plain'
            sizeBytes = $retryBytes.Length
        }
    $retryUploadId = [string]$retryUpload.data.id
    $retryObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $retryUploadId;"
    $script:objectKeys.Add($retryObjectKey) | Out-Null
    $retryPut = Invoke-WebRequest -Method Put -Uri $retryUpload.data.uploadUrl `
        -ContentType 'text/plain' -Body $retryBytes -SkipHttpErrorCheck
    if ($retryPut.StatusCode -notin @(200, 204)) {
        throw "Scanner recovery attachment upload failed: HTTP $($retryPut.StatusCode)"
    }
    $retryConfirmed = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/$retryUploadId/confirm" `
        -Headers $customerHeaders -Body @{}
    Assert-Equal -Actual ([string]$retryConfirmed.data.status) -Expected 'SCAN_PENDING' `
        -Message 'Scanner recovery attachment bypassed the scan queue.'
    $retrySealedObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $retryUploadId;"
    $script:objectKeys.Add($retrySealedObjectKey) | Out-Null
    if (-not $retrySealedObjectKey.StartsWith(
            "objects/chat/$($script:runId)/",
            [StringComparison]::Ordinal)) {
        throw "Retry attachment object key was not sealed: $retrySealedObjectKey"
    }
    $failedScan = Wait-AttachmentStatus `
        -BaseUrl $baseUrl `
        -ConversationId $conversationId `
        -UploadId $retryUploadId `
        -Headers $customerHeaders `
        -ExpectedStatus 'SCAN_NEEDS_ATTENTION' `
        -TimeoutSeconds 30
    Assert-Equal -Actual ([int]$failedScan.data.scanAttempts) -Expected 2 `
        -Message 'Unavailable scanner did not stop at the configured retry ceiling.'
    $scanLastError = Get-ChatScalar `
        "SELECT scan_last_error FROM chat_attachment_upload WHERE id = $retryUploadId;"
    if ([string]::IsNullOrWhiteSpace($scanLastError)) {
        throw 'Unavailable scanner did not persist a diagnostic error.'
    }

    $retryCommandBody = @{
        commandId = "$($script:runId)-scan-retry"
        reason = 'ClamAV connectivity restored during M8.7 verification'
    }
    $unauthorizedRetry = Invoke-WebRequest -Method Post `
        -Uri "$baseUrl/admin/attachments/$retryUploadId/scan-retries" `
        -Headers $customerHeaders -ContentType 'application/json' `
        -Body ($retryCommandBody | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck
    Assert-Equal -Actual $unauthorizedRetry.StatusCode -Expected 403 `
        -Message 'A customer was allowed to retry a terminal malware scan.'

    Start-ClamAv
    $retryAccepted = Invoke-JsonPost `
        -Uri "$baseUrl/admin/attachments/$retryUploadId/scan-retries" `
        -Headers $adminHeaders -Body $retryCommandBody
    Assert-Equal -Actual ([string]$retryAccepted.data.id) -Expected $retryUploadId `
        -Message 'Admin scan recovery returned the wrong upload.'
    if ([string]$retryAccepted.data.status -notin @('SCAN_PENDING', 'SCANNING', 'READY')) {
        throw "Admin scan recovery returned an invalid state: $($retryAccepted.data.status)"
    }
    $retryReplay = Invoke-JsonPost `
        -Uri "$baseUrl/admin/attachments/$retryUploadId/scan-retries" `
        -Headers $adminHeaders -Body $retryCommandBody
    Assert-Equal -Actual ([string]$retryReplay.data.id) -Expected $retryUploadId `
        -Message 'Admin scan recovery replay returned a different upload.'
    $recoveredScan = Wait-AttachmentStatus `
        -BaseUrl $baseUrl `
        -ConversationId $conversationId `
        -UploadId $retryUploadId `
        -Headers $customerHeaders `
        -ExpectedStatus 'READY'
    Assert-Equal -Actual ([int]$recoveredScan.data.scanAttempts) -Expected 1 `
        -Message 'Recovered attachment did not restart its retry budget.'
    $retryAudits = Invoke-RestMethod -Method Get `
        -Uri "$baseUrl/admin/attachments/$retryUploadId/scan-retries" `
        -Headers $adminHeaders
    Assert-Equal -Actual $retryAudits.data.Count -Expected 1 `
        -Message 'Idempotent admin recovery created duplicate audit rows.'
    Assert-Equal -Actual ([string]$retryAudits.data[0].outcome) -Expected 'ACCEPTED' `
        -Message 'Admin recovery audit did not record acceptance.'
    Assert-Equal -Actual ([string]$retryAudits.data[0].beforeStatus) `
        -Expected 'SCAN_NEEDS_ATTENTION' `
        -Message 'Admin recovery audit lost the terminal pre-state.'

    $recoveredMessage = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/messages" `
        -Headers $customerHeaders -Body @{
            clientMessageId = "$($script:runId)-recovered-message"
            messageType = 'FILE'
            content = 'Recovered only after an audited rescan'
            attachmentUploadIds = @($retryUploadId)
        }
    Assert-Equal -Actual $recoveredMessage.data.attachments.Count -Expected 1 `
        -Message 'Recovered attachment could not be bound after a clean rescan.'

    $orphanBytes = [Text.Encoding]::UTF8.GetBytes('orphan attachment')
    $orphanUpload = Invoke-JsonPost `
        -Uri "$baseUrl/conversations/$conversationId/attachments/upload-intents" `
        -Headers $customerHeaders -Body @{
            clientUploadId = "$($script:runId)-orphan"
            fileName = 'orphan.txt'
            mimeType = 'text/plain'
            sizeBytes = $orphanBytes.Length
    }
    $orphanUploadId = [string]$orphanUpload.data.id
    $orphanObjectKey = Get-ChatScalar `
        "SELECT object_key FROM chat_attachment_upload WHERE id = $orphanUploadId;"
    $script:objectKeys.Add($orphanObjectKey) | Out-Null
    $orphanPut = Invoke-WebRequest -Method Put -Uri $orphanUpload.data.uploadUrl `
        -ContentType 'text/plain' -Body $orphanBytes -SkipHttpErrorCheck
    if ($orphanPut.StatusCode -notin @(200, 204)) {
        throw "Orphan attachment upload failed: HTTP $($orphanPut.StatusCode)"
    }
    Invoke-ChatMySql -Sql @"
UPDATE chat_attachment_upload
SET expires_at = UTC_TIMESTAMP(3) - INTERVAL 60 SECOND
WHERE id = $orphanUploadId;
"@ | Out-Null
    Wait-Until -Description 'expired orphan attachment cleanup' -TimeoutSeconds 40 -Condition {
        (Get-ChatScalar `
            "SELECT status FROM chat_attachment_upload WHERE id = $orphanUploadId;") -eq 'DELETED'
    }
    $orphanCleanupAttempts = [long](Get-ChatScalar `
        "SELECT cleanup_attempts FROM chat_attachment_upload WHERE id = $orphanUploadId;")
    Assert-Equal -Actual $orphanCleanupAttempts -Expected 1L `
        -Message 'Expired orphan cleanup did not use one claimed attempt.'
    Assert-Equal -Actual (Get-ChatObjectCount) -Expected 4 `
        -Message 'Expired orphan object was not removed while four retained objects remained.'
    Assert-Equal -Actual (Get-ChatObjectCountForPrefix `
            -Prefix "quarantine/chat/$($script:runId)/") -Expected 1 `
        -Message 'Only the explicitly overwritten quarantine object should remain.'
    Assert-Equal -Actual (Get-ChatObjectCountForPrefix `
            -Prefix "objects/chat/$($script:runId)/") -Expected 3 `
        -Message 'All three sealed attachment objects should remain.'

    $evidence = [ordered]@{
        runId = $script:runId
        gatewayStatus = 'routed'
        upload = [ordered]@{
            uploadId = $uploadId
            replayReturnedSameUpload = $true
            missingObjectStatus = $missingObjectResponse.StatusCode
            confirmedStatus = [string]$confirmed.data.status
            readyStatus = [string]$cleanScan.data.status
            scanAttempts = [int]$cleanScan.data.scanAttempts
            scanEngine = [string]$cleanScan.data.scanEngine
            quarantineScopedObjectKey = $true
        }
        message = [ordered]@{
            messageId = $messageId
            attachmentId = $attachmentId
            replayReturnedSameMessage = $true
            reuseStatus = $reuseResponse.StatusCode
            attachmentCount = $attachmentCount
        }
        authorization = [ordered]@{
            nonMemberDownloadStatus = $unauthorizedDownload.StatusCode
            memberDownloadBytesMatched = $true
        }
        integrity = [ordered]@{
            checksumSnapshotsMatched = $true
            quarantineOverwriteDownloadStatus = $tamperedDownloadResponse.StatusCode
            sealedObjectDownloadAllowed = $true
        }
        malware = [ordered]@{
            uploadId = $infectedUploadId
            finalStatus = [string]$infectedScan.data.status
            signature = [string]$infectedScan.data.scanSignature
            bindStatus = $infectedMessageResponse.StatusCode
            bindErrorCode = [string]$infectedError.code
        }
        scannerFailure = [ordered]@{
            uploadId = $retryUploadId
            terminalStatus = [string]$failedScan.data.status
            attemptsAtCeiling = [int]$failedScan.data.scanAttempts
            diagnosticPersisted = $true
            customerRetryStatus = $unauthorizedRetry.StatusCode
        }
        auditedRecovery = [ordered]@{
            commandId = [string]$retryCommandBody.commandId
            replayReturnedSameUpload = $true
            auditRows = $retryAudits.data.Count
            auditOutcome = [string]$retryAudits.data[0].outcome
            beforeStatus = [string]$retryAudits.data[0].beforeStatus
            finalStatus = [string]$recoveredScan.data.status
            rescanAttempts = [int]$recoveredScan.data.scanAttempts
            messageId = [string]$recoveredMessage.data.id
        }
        orphanCleanup = [ordered]@{
            uploadId = $orphanUploadId
            finalStatus = 'DELETED'
            cleanupAttempts = $orphanCleanupAttempts
            retainedObjectCount = 4
        }
        privacy = [ordered]@{
            objectKeyInOutbox = $objectKeyLeak
        }
    }

    Remove-ChatObjects
    Clear-ChatData
    $cleanupRows = [long](Get-ChatScalar @"
SELECT
    (SELECT COUNT(*) FROM chat_conversation)
  + (SELECT COUNT(*) FROM conversation_member)
  + (SELECT COUNT(*) FROM chat_message)
  + (SELECT COUNT(*) FROM message_receipt)
  + (SELECT COUNT(*) FROM chat_attachment)
  + (SELECT COUNT(*) FROM chat_attachment_scan_retry_audit)
  + (SELECT COUNT(*) FROM chat_attachment_upload)
  + (SELECT COUNT(*) FROM outbox_event);
"@)
    $cleanupObjects = Get-ChatObjectCount
    Assert-Equal -Actual $cleanupRows -Expected 0L `
        -Message 'Chat attachment verification rows were not cleaned.'
    Assert-Equal -Actual $cleanupObjects -Expected 0 `
        -Message 'Chat attachment verification objects were not cleaned.'
    $evidence.cleanup = [ordered]@{
        mysqlRows = $cleanupRows
        minioObjects = $cleanupObjects
    }
    $evidencePath = Join-Path $script:runDirectory 'verification.json'
    $evidence | ConvertTo-Json -Depth 10 | Set-Content `
        -LiteralPath $evidencePath -Encoding utf8NoBOM
    Write-Host "M8 chat attachment verification passed: $evidencePath"
}
catch {
    Write-Error "M8 chat attachment verification failed. Logs: $($script:runDirectory). $($_.Exception.Message)"
    throw
}
finally {
    try {
        Remove-ChatObjects
    }
    catch {
        Write-Warning "Chat attachment object cleanup failed: $($_.Exception.Message)"
    }
    try {
        Clear-ChatData
    }
    catch {
        Write-Warning "Chat attachment data cleanup failed: $($_.Exception.Message)"
    }
    try {
        Stop-Applications
    }
    catch {
        Write-Warning "Managed application cleanup failed: $($_.Exception.Message)"
    }
    try {
        Restore-ClamAvInitialState
    }
    catch {
        Write-Warning "ClamAV initial-state restoration failed: $($_.Exception.Message)"
    }
}
