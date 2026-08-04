#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipNetworkPreflight,
    [ValidatePattern('^\d{8}$')]
    [string]$EvidenceDate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:deployDirectory = Join-Path $script:repositoryRoot 'deploy\docker'
$runTimestamp = [DateTimeOffset]::Now
$datePrefix = if ([string]::IsNullOrWhiteSpace($EvidenceDate)) {
    $runTimestamp.ToString('yyyyMMdd')
}
else {
    $EvidenceDate
}
$script:suffix = "$datePrefix$($runTimestamp.ToString('HHmmss'))$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
$script:runId = "m8-catalog-search-$($script:suffix)"
$script:runDirectory = Join-Path $script:backendRoot ".run\$($script:runId)"
$script:database = "ecom_catalog_search_$($script:suffix)"
$script:indexAlias = "plainjournal-search-$($script:suffix)".ToLowerInvariant()
$script:catalogPort = 18102
$script:openSearchPort = 19200
$script:catalogProcess = $null
$script:databaseCreated = $false
$script:databaseTouched = $false
$script:openSearchStateCaptured = $false
$script:openSearchWasPresent = $false
$script:openSearchWasRunning = $false
$script:openSearchStarted = $false
$script:openSearchFaultInjected = $false
$script:verification = [ordered]@{}
$script:failureContext = [ordered]@{}
$script:adminId = 895000000000000101L
$script:categoryId = 895000000000001001L
$script:brandId = 895000000000001101L
$script:firstProductId = $null
$script:secondProductId = $null

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
$script:jarPath = Join-Path $script:backendRoot `
    'services\catalog-service\target\catalog-service-1.0.2-SNAPSHOT.jar'

[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null
$script:tracePath = Join-Path $script:runDirectory 'script-trace.log'

function Write-VerificationTrace {
    param([Parameter(Mandatory)][string]$Message)

    [IO.File]::AppendAllText(
        $script:tracePath,
        "$([DateTimeOffset]::Now.ToString('o')) $Message`r`n",
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
            $name.Trim(), $value, 'Process')
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

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=')
        .Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$script:adminId
        iat = $now
        exp = $now + 3600
        jti = [Guid]::NewGuid().ToString()
        roles = @('ADMIN')
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

function Assert-PortAvailable {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $script:catalogPort `
        -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $($script:catalogPort) is already in use."
    }
}

function Invoke-CatalogSql {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [switch]$Root,
        [switch]$AllRows
    )

    $password = if ($Root) {
        $env:MYSQL_ROOT_PASSWORD
    }
    else {
        $env:CATALOG_DB_PASSWORD
    }
    $user = if ($Root) { 'root' } else { $env:CATALOG_DB_USER }
    $mysqlArguments = @(
        'mysql',
        "-u$user",
        '--default-character-set=utf8mb4',
        '-N',
        '-B')
    if (-not $Root) {
        $mysqlArguments += "--database=$($script:database)"
    }
    $mysqlArguments += @('-e', $Sql)
    $dockerArguments = @(
        'exec',
        '-e',
        "MYSQL_PWD=$password",
        'plainjournal-mysql') + $mysqlArguments
    $lines = @(& docker @dockerArguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $details = ($lines | ForEach-Object { [string]$_ }) -join ' | '
        if ($details.Length -gt 2000) {
            $details = $details.Substring(0, 2000)
        }
        throw "Catalog MySQL command failed: $details"
    }
    if ($AllRows) {
        return $lines
    }
    return $lines | Select-Object -Last 1
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body,
        [int]$TimeoutSec = 15
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = $TimeoutSec
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 12
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

function Invoke-CatalogApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [object]$Body
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }
    return Invoke-JsonRequest `
        -Method $Method `
        -Uri "http://127.0.0.1:$($script:catalogPort)$Path" `
        -Headers $headers `
        -Body $Body
}

function Invoke-OpenSearch {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body
    )

    return Invoke-JsonRequest `
        -Method $Method `
        -Uri "http://127.0.0.1:$($script:openSearchPort)$Path" `
        -Body $Body `
        -TimeoutSec 20
}

function Wait-OpenSearch {
    Wait-Until -Description 'OpenSearch health' -TimeoutSeconds 180 -Condition {
        try {
            $health = Invoke-OpenSearch -Method Get -Path '/_cluster/health'
            return $health.status -eq 200 -and
                $health.body.status -in @('green', 'yellow')
        }
        catch {
            return $false
        }
    }
}

function Start-OpenSearch {
    $running = docker inspect -f '{{.State.Running}}' plainjournal-opensearch 2>$null
    $script:openSearchWasPresent = $LASTEXITCODE -eq 0
    $script:openSearchStateCaptured = $true
    $script:openSearchWasRunning =
        $script:openSearchWasPresent -and $running -eq 'true'
    if ($script:openSearchWasPresent -and -not $script:openSearchWasRunning) {
        docker start plainjournal-opensearch *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'Existing OpenSearch container start failed.'
        }
    }
    elseif (-not $script:openSearchWasPresent) {
        Push-Location $script:deployDirectory
        try {
            docker compose --profile m8-search up -d opensearch
            $composeExitCode = $LASTEXITCODE
            docker inspect plainjournal-opensearch *> $null
            $script:openSearchStarted = $LASTEXITCODE -eq 0
            if ($composeExitCode -ne 0) {
                throw 'OpenSearch container start failed.'
            }
        }
        finally {
            Pop-Location
        }
    }
    Wait-OpenSearch
}

function Stop-OpenSearchForFault {
    docker stop plainjournal-opensearch *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'OpenSearch fault injection stop failed.'
    }
    $script:openSearchFaultInjected = $true
    Wait-Until -Description 'OpenSearch port to close' -TimeoutSeconds 30 -Condition {
        -not (Get-NetTCPConnection -State Listen -LocalPort $script:openSearchPort `
            -ErrorAction SilentlyContinue)
    }
}

function Restart-OpenSearchAfterFault {
    docker start plainjournal-opensearch *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'OpenSearch fault recovery start failed.'
    }
    Wait-OpenSearch
    $script:openSearchFaultInjected = $false
}

function Start-Catalog {
    $environment = @{
        CATALOG_SERVICE_PORT = [string]$script:catalogPort
        MYSQL_HOST = '127.0.0.1'
        MYSQL_PORT = $env:MYSQL_PORT
        CATALOG_DB_NAME = $script:database
        CATALOG_DB_USER = $env:CATALOG_DB_USER
        CATALOG_DB_PASSWORD = $env:CATALOG_DB_PASSWORD
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = $env:REDIS_PORT
        REDIS_PASSWORD = $env:REDIS_PASSWORD
        MINIO_HOST = '127.0.0.1'
        MINIO_API_PORT = $env:MINIO_API_PORT
        MINIO_ROOT_USER = $env:MINIO_ROOT_USER
        MINIO_ROOT_PASSWORD = $env:MINIO_ROOT_PASSWORD
        IDENTITY_JWT_SECRET = $env:IDENTITY_JWT_SECRET
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
        CATALOG_REVIEW_EVENTS_ENABLED = 'false'
        CATALOG_CACHE_ENABLED = 'false'
        CATALOG_READ_REPLICA_ENABLED = 'false'
        CATALOG_SEARCH_ENABLED = 'true'
        CATALOG_SEARCH_ENDPOINT = "http://127.0.0.1:$($script:openSearchPort)"
        CATALOG_SEARCH_INDEX_ALIAS = $script:indexAlias
        CATALOG_SEARCH_CONNECT_TIMEOUT = '1s'
        CATALOG_SEARCH_REQUEST_TIMEOUT = '5s'
        CATALOG_SEARCH_WORKER_ID = "catalog-search-$($script:suffix)"
        CATALOG_SEARCH_BATCH_SIZE = '20'
        CATALOG_SEARCH_MAX_ATTEMPTS = '3'
        CATALOG_SEARCH_RETRY_DELAY = '500ms'
        CATALOG_SEARCH_LEASE_DURATION = '15s'
        CATALOG_SEARCH_REBUILD_BATCH_SIZE = '50'
        CATALOG_SEARCH_RECONCILIATION_LIMIT = '1000'
        CATALOG_SEARCH_RECONCILIATION_ENABLED = 'false'
        CATALOG_SEARCH_PROJECTION_INITIAL_DELAY = '250'
        CATALOG_SEARCH_PROJECTION_FIXED_DELAY = '250'
        CATALOG_SEARCH_REBUILD_INITIAL_DELAY = '250'
        CATALOG_SEARCH_REBUILD_FIXED_DELAY = '250'
    }
    $original = @{}
    foreach ($entry in $environment.GetEnumerator()) {
        $original[$entry.Key] =
            [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key, [string]$entry.Value, 'Process')
    }
    try {
        $arguments = @(
            '-Xms128m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-jar',
            $script:jarPath,
            '--spring.cloud.nacos.discovery.enabled=false',
            '--spring.cloud.nacos.config.enabled=false',
            '--spring.config.import=optional:nacos:'
        )
        $script:catalogProcess = Start-Process `
            -FilePath $script:javaPath `
            -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (
                Join-Path $script:runDirectory 'catalog.out.log') `
            -RedirectStandardError (
                Join-Path $script:runDirectory 'catalog.err.log') `
            -WindowStyle Hidden `
            -PassThru
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable(
                $entry.Key, $entry.Value, 'Process')
        }
    }
    Wait-Until -Description 'Catalog readiness' -TimeoutSeconds 120 -Condition {
        if ($script:catalogProcess.HasExited) {
            throw "Catalog exited before readiness: $($script:catalogProcess.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$($script:catalogPort)/actuator/health/liveness" `
                -TimeoutSec 3
            return $health.status -eq 'UP'
        }
        catch {
            return $false
        }
    }
}

function Stop-Catalog {
    if ($null -ne $script:catalogProcess -and
        -not $script:catalogProcess.HasExited) {
        Stop-Process -Id $script:catalogProcess.Id -Force
        Wait-Process -Id $script:catalogProcess.Id -Timeout 15 `
            -ErrorAction SilentlyContinue
    }
}

function New-VerificationDatabase {
    $script:databaseTouched = $true
    $script:databaseCreated = $true
    Invoke-CatalogSql -Root -Sql @"
CREATE DATABASE $($script:database)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON $($script:database).*
    TO '$($env:CATALOG_DB_USER)'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
}

function New-Product {
    param(
        [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][string]$SkuCode,
        [Parameter(Mandatory)][string]$SkuName,
        [Parameter(Mandatory)][string]$Token
    )

    $created = Invoke-CatalogApi `
        -Method Post `
        -Path '/api/v1/catalog/admin/products' `
        -Token $Token `
        -Body @{
            categoryId = [string]$script:categoryId
            brandId = [string]$script:brandId
            title = $Title
            subtitle = 'M8 search projection verification'
            description = 'Search text is projected asynchronously from MySQL facts.'
            skus = @(@{
                skuCode = $SkuCode
                name = $SkuName
                specJson = '{"usage":"commute","color":"lotus-green"}'
                salePrice = '89.00'
                marketPrice = '109.00'
            })
        }
    if ($created.status -ne 200) {
        throw "Product creation failed: HTTP $($created.status)"
    }
    $productId = [string]$created.body.data.id
    $published = Invoke-CatalogApi `
        -Method Post `
        -Path "/api/v1/catalog/admin/products/$productId/publish" `
        -Token $Token `
        -Body @{ expectedVersion = 0 }
    if ($published.status -ne 200 -or
        $published.body.data.status -ne 'ACTIVE') {
        throw 'Product publication failed.'
    }
    return $productId
}

function Wait-OutboxConverged {
    param([int]$TimeoutSeconds = 90)

    Wait-Until -Description 'Catalog search Outbox convergence' `
        -TimeoutSeconds $TimeoutSeconds `
        -Condition {
            (Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_outbox
WHERE status <> 'PUBLISHED';
"@) -eq '0'
        }
}

function Wait-SearchContains {
    param(
        [Parameter(Mandatory)][string]$Query,
        [Parameter(Mandatory)][string]$ProductId,
        [int]$TimeoutSeconds = 60
    )

    Wait-Until -Description "OpenSearch result for $Query" `
        -TimeoutSeconds $TimeoutSeconds `
        -Condition {
            $encoded = [Uri]::EscapeDataString($Query)
            $response = Invoke-CatalogApi `
                -Method Get `
                -Path "/api/v1/catalog/search/products?q=$encoded"
            if ($response.status -ne 200 -or
                $response.body.data.source -ne 'OPENSEARCH') {
                return $false
            }
            $itemIds = @($response.body.data.items | ForEach-Object {
                [string]$_.id
            })
            return $itemIds -contains [string]$ProductId
        }
}

function Wait-RebuildSucceeded {
    param(
        [Parameter(Mandatory)][string]$RebuildId,
        [Parameter(Mandatory)][string]$Token
    )

    Wait-Until -Description "search rebuild $RebuildId" `
        -TimeoutSeconds 120 `
        -Condition {
            $response = Invoke-CatalogApi `
                -Method Get `
                -Path "/api/v1/catalog/admin/search/rebuilds/$RebuildId" `
                -Token $Token
            if ($response.status -ne 200) {
                return $false
            }
            if ($response.body.data.status -eq 'NEEDS_ATTENTION') {
                throw "Search rebuild entered NEEDS_ATTENTION: $($response.body.data.lastError)"
            }
            return $response.body.data.status -eq 'SUCCEEDED'
        }
}

function Remove-SearchIndices {
    if (-not (Get-NetTCPConnection -State Listen -LocalPort $script:openSearchPort `
        -ErrorAction SilentlyContinue)) {
        return
    }
    $indices = Invoke-OpenSearch `
        -Method Get `
        -Path "/_cat/indices/$($script:indexAlias)-*?format=json&h=index"
    if ($indices.status -eq 200) {
        foreach ($entry in @($indices.body)) {
            $name = [string]$entry.index
            if ($name.StartsWith("$($script:indexAlias)-")) {
                Invoke-OpenSearch -Method Delete -Path "/$name" | Out-Null
            }
        }
    }
}

$primaryError = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()

try {
    Write-Host 'Stage 1/9: validating network, core middleware, ports, and OpenSearch.'
    Write-VerificationTrace 'stage 1 begin'
    if (-not $SkipNetworkPreflight) {
        & 'D:\DevTools\Network\check-dev-network.ps1'
        if ($LASTEXITCODE -ne 0) {
            throw 'Network preflight failed.'
        }
    }
    Import-LocalEnvironment
    Require-Environment -Names @(
        'MYSQL_PORT',
        'MYSQL_ROOT_PASSWORD',
        'CATALOG_DB_USER',
        'CATALOG_DB_PASSWORD',
        'REDIS_PORT',
        'REDIS_PASSWORD',
        'MINIO_API_PORT',
        'MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD',
        'IDENTITY_JWT_SECRET',
        'METRICS_SCRAPE_TOKEN')
    Assert-PortAvailable
    Start-OpenSearch

    Write-Host 'Stage 2/9: building Catalog and creating an isolated real MySQL schema.'
    Write-VerificationTrace 'stage 2 begin'
    if (-not $SkipBuild) {
        Push-Location $script:backendRoot
        try {
            & mvn -pl services/catalog-service -am -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw 'Catalog package failed.'
            }
        }
        finally {
            Pop-Location
        }
    }
    New-VerificationDatabase
    Start-Catalog
    $adminToken = New-AccessToken

    Write-Host 'Stage 3/9: creating two MySQL product facts and verifying incremental projection.'
    Write-VerificationTrace 'stage 3 begin'
    Invoke-CatalogSql -Sql @"
INSERT INTO catalog_category
    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
VALUES
    ($($script:categoryId), NULL, 'M8 Search Category', 'm8-search-category',
     'ACTIVE', 10, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
INSERT INTO catalog_brand
    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
VALUES
    ($($script:brandId), 'M8 Search Brand', 'm8-search-brand', NULL,
     'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
"@ | Out-Null
    $script:firstProductId = New-Product `
        -Title '青荷通勤收纳包' `
        -SkuCode "SEARCH-A-$($script:suffix)" `
        -SkuName '青荷灰绿' `
        -Token $adminToken
    $script:secondProductId = New-Product `
        -Title '素白桌面收纳盒' `
        -SkuCode "SEARCH-B-$($script:suffix)" `
        -SkuName '素白小号' `
        -Token $adminToken
    Wait-OutboxConverged
    Wait-SearchContains -Query '青荷通勤' -ProductId $script:firstProductId
    $script:verification.incrementalProjection = [ordered]@{
        activeProducts = 2
        publishedOutbox = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_outbox WHERE status = 'PUBLISHED';
"@)
        firstProductSearchSource = 'OPENSEARCH'
    }

    Write-Host 'Stage 4/9: stopping OpenSearch and proving explicit MySQL fallback plus governed failure.'
    Write-VerificationTrace 'stage 4 begin'
    Stop-OpenSearchForFault
    $updated = Invoke-CatalogApi `
        -Method Put `
        -Path "/api/v1/catalog/admin/products/$($script:firstProductId)" `
        -Token $adminToken `
        -Body @{
            categoryId = [string]$script:categoryId
            brandId = [string]$script:brandId
            title = '青荷通勤收纳包故障恢复版'
            subtitle = 'OpenSearch outage recovery'
            description = 'The MySQL fact commits while the derived search index is unavailable.'
            expectedVersion = 1
        }
    if ($updated.status -ne 200) {
        throw 'Product update during OpenSearch outage did not commit to MySQL.'
    }
    Wait-Until -Description 'search Outbox NEEDS_ATTENTION' `
        -TimeoutSeconds 30 `
        -Condition {
            (Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_outbox
WHERE product_id = $($script:firstProductId)
  AND status = 'NEEDS_ATTENTION';
"@) -eq '1'
        }
    $fallback = Invoke-CatalogApi `
        -Method Get `
        -Path '/api/v1/catalog/search/products?q=%E6%95%85%E9%9A%9C%E6%81%A2%E5%A4%8D'
    if ($fallback.status -ne 200 -or
        $fallback.body.data.source -ne 'MYSQL_FALLBACK' -or
        -not $fallback.body.data.degraded) {
        throw 'OpenSearch outage was not exposed as explicit MySQL fallback.'
    }
    $needsAttentionId = Invoke-CatalogSql -Sql @"
SELECT id FROM catalog_search_outbox
WHERE product_id = $($script:firstProductId)
  AND status = 'NEEDS_ATTENTION'
ORDER BY created_at DESC LIMIT 1;
"@
    $attemptsDuringOutage = [int](Invoke-CatalogSql -Sql @"
SELECT attempts FROM catalog_search_outbox WHERE id = '$needsAttentionId';
"@)
    $script:verification.outage = [ordered]@{
        mysqlUpdateCommitted = $true
        searchSource = 'MYSQL_FALLBACK'
        degraded = $true
        outboxStatus = 'NEEDS_ATTENTION'
        attempts = $attemptsDuringOutage
    }

    Write-Host 'Stage 5/9: restoring OpenSearch through idempotent audited recovery.'
    Write-VerificationTrace 'stage 5 begin'
    Restart-OpenSearchAfterFault
    $recoveryBody = @{
        commandId = "recover-$($script:suffix)"
        reason = 'OpenSearch health recovered; retry the persisted projection fact.'
    }
    $recovered = Invoke-CatalogApi `
        -Method Post `
        -Path "/api/v1/catalog/admin/search/outbox/$needsAttentionId/recover" `
        -Token $adminToken `
        -Body $recoveryBody
    $replayed = Invoke-CatalogApi `
        -Method Post `
        -Path "/api/v1/catalog/admin/search/outbox/$needsAttentionId/recover" `
        -Token $adminToken `
        -Body $recoveryBody
    if ($recovered.status -ne 200 -or
        $replayed.status -ne 200 -or
        $recovered.body.data.commandId -ne $replayed.body.data.commandId) {
        throw 'Search projection recovery was not idempotent.'
    }
    Wait-OutboxConverged
    Wait-SearchContains `
        -Query '故障恢复版' `
        -ProductId $script:firstProductId
    $script:verification.recovery = [ordered]@{
        recoveryAuditRows = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_recovery_audit
WHERE command_id = 'recover-$($script:suffix)';
"@)
        finalOutboxStatus = 'PUBLISHED'
        finalSearchSource = 'OPENSEARCH'
    }

    Write-Host 'Stage 6/9: running an idempotent audited blue-green full rebuild.'
    Write-VerificationTrace 'stage 6 begin'
    $rebuildBody = @{
        commandId = "rebuild-$($script:suffix)"
        reason = 'Build the complete disposable product search projection from MySQL facts.'
    }
    $rebuild = Invoke-CatalogApi `
        -Method Post `
        -Path '/api/v1/catalog/admin/search/rebuilds' `
        -Token $adminToken `
        -Body $rebuildBody
    $rebuildReplay = Invoke-CatalogApi `
        -Method Post `
        -Path '/api/v1/catalog/admin/search/rebuilds' `
        -Token $adminToken `
        -Body $rebuildBody
    if ($rebuild.status -ne 200 -or
        $rebuildReplay.status -ne 200 -or
        $rebuild.body.data.id -ne $rebuildReplay.body.data.id) {
        throw 'Search rebuild command was not idempotent.'
    }
    $rebuildId = [string]$rebuild.body.data.id
    Wait-RebuildSucceeded -RebuildId $rebuildId -Token $adminToken
    $rebuildState = Invoke-CatalogApi `
        -Method Get `
        -Path "/api/v1/catalog/admin/search/rebuilds/$rebuildId" `
        -Token $adminToken
    if ([long]$rebuildState.body.data.indexedCount -ne 2) {
        throw 'Full search rebuild did not index exactly two ACTIVE products.'
    }
    $aliasState = Invoke-OpenSearch `
        -Method Get `
        -Path "/_alias/$($script:indexAlias)"
    $targetIndex = @($aliasState.body.psobject.Properties.Name)[0]
    $script:verification.rebuild = [ordered]@{
        rebuildId = $rebuildId
        status = 'SUCCEEDED'
        indexedCount = 2
        targetIndex = $targetIndex
        commandRows = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_rebuild
WHERE command_id = 'rebuild-$($script:suffix)';
"@)
    }

    Write-Host 'Stage 7/9: injecting missing, stale, and orphan index divergence.'
    Write-VerificationTrace 'stage 7 begin'
    Invoke-OpenSearch `
        -Method Delete `
        -Path "/$targetIndex/_doc/$($script:firstProductId)?refresh=true" | Out-Null
    Invoke-CatalogSql -Sql @"
UPDATE product_spu
SET search_revision = search_revision + 1,
    updated_at = CURRENT_TIMESTAMP(3)
WHERE id = $($script:secondProductId);
"@ | Out-Null
    $orphanProductId = 895000000099999999L
    Invoke-OpenSearch `
        -Method Put `
        -Path "/$targetIndex/_doc/${orphanProductId}?refresh=true" `
        -Body @{
            productId = $orphanProductId
            revision = 7
            categoryId = $script:categoryId
            categoryName = 'Orphan'
            brandId = $script:brandId
            brandName = 'Orphan'
            title = 'Orphan search document'
            subtitle = ''
            description = ''
            skuNames = @()
            skuSpecs = @()
            updatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        } | Out-Null

    $reconciliation = Invoke-CatalogApi `
        -Method Post `
        -Path '/api/v1/catalog/admin/search/reconciliation' `
        -Token $adminToken `
        -Body @{ repair = $true }
    if ($reconciliation.status -ne 200 -or
        [int]$reconciliation.body.data.missing -ne 1 -or
        [int]$reconciliation.body.data.stale -ne 1 -or
        [int]$reconciliation.body.data.orphan -ne 1 -or
        [int]$reconciliation.body.data.repairEvents -ne 3) {
        throw 'Search reconciliation did not detect all injected divergence.'
    }
    Wait-OutboxConverged
    $refresh = Invoke-OpenSearch `
        -Method Post `
        -Path "/$targetIndex/_refresh"
    if ($refresh.status -ne 200) {
        throw 'OpenSearch refresh after reconciliation repair failed.'
    }
    $converged = Invoke-CatalogApi `
        -Method Post `
        -Path '/api/v1/catalog/admin/search/reconciliation' `
        -Token $adminToken `
        -Body @{ repair = $false }
    if ($converged.status -ne 200 -or
        [int]$converged.body.data.missing -ne 0 -or
        [int]$converged.body.data.stale -ne 0 -or
        [int]$converged.body.data.orphan -ne 0 -or
        [int]$converged.body.data.resolved -ne 3) {
        throw 'Search reconciliation repair did not converge.'
    }
    $orphanAfterRepair = Invoke-OpenSearch `
        -Method Get `
        -Path "/$targetIndex/_doc/$orphanProductId"
    if ($orphanAfterRepair.status -ne 404) {
        throw 'Orphan search document was not removed.'
    }
    $script:verification.reconciliation = [ordered]@{
        missingDetected = 1
        staleDetected = 1
        orphanDetected = 1
        repairEvents = 3
        openIssuesAfterRepair = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_reconciliation WHERE status = 'OPEN';
"@)
        resolvedIssues = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_reconciliation WHERE status = 'RESOLVED';
"@)
        orphanHttpStatusAfterRepair = $orphanAfterRepair.status
    }

    Write-Host 'Stage 8/9: unpublishing a product and proving the index cannot keep it public.'
    Write-VerificationTrace 'stage 8 begin'
    $unpublished = Invoke-CatalogApi `
        -Method Post `
        -Path "/api/v1/catalog/admin/products/$($script:secondProductId)/unpublish" `
        -Token $adminToken `
        -Body @{ expectedVersion = 1 }
    if ($unpublished.status -ne 200 -or
        $unpublished.body.data.status -ne 'INACTIVE') {
        throw 'Product unpublication failed.'
    }
    Wait-OutboxConverged
    Wait-Until -Description 'unpublished document deletion' `
        -TimeoutSeconds 60 `
        -Condition {
            $document = Invoke-OpenSearch `
                -Method Get `
                -Path "/$targetIndex/_doc/$($script:secondProductId)"
            return $document.status -eq 404
        }
    $publicSearch = Invoke-CatalogApi `
        -Method Get `
        -Path '/api/v1/catalog/search/products?q=%E7%B4%A0%E7%99%BD%E6%A1%8C%E9%9D%A2'
    $publicItemIds = @($publicSearch.body.data.items | ForEach-Object {
        [string]$_.id
    })
    if ($publicItemIds -contains [string]$script:secondProductId) {
        throw 'An INACTIVE MySQL product remained publicly visible through search.'
    }
    $script:verification.unpublish = [ordered]@{
        mysqlStatus = 'INACTIVE'
        indexDocumentHttpStatus = 404
        publicItems = @($publicSearch.body.data.items).Count
    }

    Write-Host 'Stage 9/9: recording final facts and metrics.'
    Write-VerificationTrace 'stage 9 begin'
    $metrics = Invoke-WebRequest `
        -Method Get `
        -Uri "http://127.0.0.1:$($script:catalogPort)/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } `
        -SkipHttpErrorCheck
    if ([int]$metrics.StatusCode -ne 200) {
        throw "Catalog metrics request failed: HTTP $([int]$metrics.StatusCode)"
    }
    $metricNames = @(
        'ecommerce_catalog_search_projection_total',
        'ecommerce_catalog_search_outbox_pending',
        'ecommerce_catalog_search_outbox_needs_attention',
        'ecommerce_catalog_search_rebuilds_total',
        'ecommerce_catalog_search_reconciliation_open',
        'ecommerce_catalog_search_requests_total'
    )
    foreach ($requiredMetric in $metricNames) {
        if ($metrics.Content -notmatch "(?m)^# (?:HELP|TYPE) $([regex]::Escape($requiredMetric))\b") {
            throw "Missing Catalog search Prometheus metric: $requiredMetric"
        }
    }
    $script:verification.environment = [ordered]@{
        runId = $script:runId
        database = $script:database
        indexAlias = $script:indexAlias
        catalogPort = $script:catalogPort
        openSearchPort = $script:openSearchPort
        openSearchImage = (
            docker inspect -f '{{.Config.Image}}' plainjournal-opensearch)
    }
    $script:verification.finalState = [ordered]@{
        activeProducts = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM product_spu WHERE status = 'ACTIVE';
"@)
        inactiveProducts = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM product_spu WHERE status = 'INACTIVE';
"@)
        unpublishedOutbox = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_outbox WHERE status <> 'PUBLISHED';
"@)
        needsAttentionOutbox = [long](Invoke-CatalogSql -Sql @"
SELECT COUNT(*) FROM catalog_search_outbox WHERE status = 'NEEDS_ATTENTION';
"@)
        searchMetricNames = @($metricNames)
    }
    if ($script:verification.finalState.unpublishedOutbox -ne 0 -or
        $script:verification.finalState.needsAttentionOutbox -ne 0) {
        throw 'Catalog search Outbox did not fully converge.'
    }
    $verificationPath = Join-Path $script:runDirectory 'verification.json'
    $script:verification | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $verificationPath -Encoding utf8
    Write-VerificationTrace "verification succeeded: $verificationPath"
}
catch {
    $primaryError = $_
    Write-VerificationTrace "verification failed: $($_.Exception.Message)"
    [ordered]@{
        message = $_.Exception.Message
        scriptStackTrace = $_.ScriptStackTrace
        position = [string]$_.InvocationInfo.PositionMessage
        context = $script:failureContext
    } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'failure.json') -Encoding utf8
}
finally {
    try {
        Stop-Catalog
    }
    catch {
        $cleanupErrors.Add("Catalog cleanup: $($_.Exception.Message)")
    }
    try {
        if ($script:openSearchFaultInjected) {
            Restart-OpenSearchAfterFault
        }
        Remove-SearchIndices
    }
    catch {
        $cleanupErrors.Add("OpenSearch index cleanup: $($_.Exception.Message)")
    }
    try {
        if ($script:databaseCreated) {
            Invoke-CatalogSql -Root -Sql @"
DROP DATABASE IF EXISTS $($script:database);
REVOKE ALL PRIVILEGES ON $($script:database).*
    FROM '$($env:CATALOG_DB_USER)'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
            $script:databaseCreated = $false
        }
    }
    catch {
        $cleanupErrors.Add("database cleanup: $($_.Exception.Message)")
    }
    try {
        if ($script:openSearchStarted -and -not $script:openSearchWasPresent) {
            Push-Location $script:deployDirectory
            try {
                docker compose --profile m8-search rm -sf opensearch *> $null
                if ($LASTEXITCODE -ne 0) {
                    throw 'OpenSearch container removal returned a non-zero exit code.'
                }
            }
            finally {
                Pop-Location
            }
        }
        elseif ($script:openSearchWasPresent -and
            -not $script:openSearchWasRunning) {
            docker stop plainjournal-opensearch *> $null
            if ($LASTEXITCODE -ne 0) {
                throw 'Existing OpenSearch container state restoration failed.'
            }
        }
    }
    catch {
        $cleanupErrors.Add("OpenSearch container cleanup: $($_.Exception.Message)")
    }

    $residualDatabaseSchemas = 0L
    $residualDatabaseGrants = 0L
    if ($script:databaseTouched) {
        try {
            $databaseResiduals = @(Invoke-CatalogSql -Root -AllRows -Sql @"
SELECT COUNT(*) FROM information_schema.schemata
WHERE schema_name = '$($script:database)';
SELECT COUNT(*) FROM mysql.db
WHERE Db = '$($script:database)'
  AND User = '$($env:CATALOG_DB_USER)';
"@)
            $residualDatabaseSchemas = [long]$databaseResiduals[0]
            $residualDatabaseGrants = [long]$databaseResiduals[1]
        }
        catch {
            $cleanupErrors.Add(
                "database residual inspection: $($_.Exception.Message)")
        }
    }
    $residualPorts = @(Get-NetTCPConnection -State Listen `
            -LocalPort $script:catalogPort -ErrorAction SilentlyContinue)
    $residualJvms = @(Get-CimInstance Win32_Process `
            -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            [string]$_.CommandLine -like '*catalog-service-1.0.2-SNAPSHOT.jar*'
        })
    $residualIndices = @()
    if (Get-NetTCPConnection -State Listen -LocalPort $script:openSearchPort `
        -ErrorAction SilentlyContinue) {
        try {
            $indexResponse = Invoke-OpenSearch `
                -Method Get `
                -Path "/_cat/indices/$($script:indexAlias)-*?format=json&h=index"
            if ($indexResponse.status -eq 200) {
                $residualIndices = @($indexResponse.body | ForEach-Object {
                    [string]$_.index
                })
            }
        }
        catch {
            $cleanupErrors.Add(
                "OpenSearch residual inspection: $($_.Exception.Message)")
        }
    }
    docker inspect plainjournal-opensearch *> $null
    $openSearchContainerPresent = $LASTEXITCODE -eq 0
    $openSearchContainerRunning = $false
    if ($openSearchContainerPresent) {
        $openSearchContainerRunning = (
            docker inspect -f '{{.State.Running}}' plainjournal-opensearch) -eq 'true'
    }
    $coreContainersRunning = [long](@(
        docker ps --format '{{.Names}}' |
        Where-Object {
            $_ -in @(
                'plainjournal-mysql',
                'plainjournal-redis',
                'plainjournal-nacos',
                'plainjournal-rocketmq-namesrv',
                'plainjournal-rocketmq-broker',
                'plainjournal-rocketmq-proxy',
                'plainjournal-minio')
        }).Count)
    if ($residualPorts.Count -gt 0) {
        $cleanupErrors.Add("residual Catalog port: $($script:catalogPort)")
    }
    if ($residualJvms.Count -gt 0) {
        $cleanupErrors.Add("residual Catalog JVMs: $($residualJvms.Count)")
    }
    if ($residualIndices.Count -gt 0) {
        $cleanupErrors.Add(
            "residual OpenSearch indices: $($residualIndices -join ', ')")
    }
    if ($residualDatabaseSchemas -gt 0 -or $residualDatabaseGrants -gt 0) {
        $cleanupErrors.Add(
            "residual Catalog database state: schemas=$residualDatabaseSchemas, grants=$residualDatabaseGrants")
    }
    if ($script:openSearchStateCaptured -and
        $openSearchContainerPresent -ne $script:openSearchWasPresent) {
        $cleanupErrors.Add(
            "OpenSearch container presence was not restored to its initial state")
    }
    if ($script:openSearchStateCaptured -and
        $script:openSearchWasPresent -and
        $openSearchContainerRunning -ne $script:openSearchWasRunning) {
        $cleanupErrors.Add(
            "OpenSearch container running state was not restored to its initial state")
    }
    if ($coreContainersRunning -ne 7) {
        $cleanupErrors.Add(
            "core middleware running count changed: expected=7, actual=$coreContainersRunning")
    }
    [ordered]@{
        cleanupErrors = @($cleanupErrors)
        residualDatabaseSchemas = $residualDatabaseSchemas
        residualDatabaseGrants = $residualDatabaseGrants
        residualCatalogPorts = $residualPorts.Count
        residualCatalogJvms = $residualJvms.Count
        residualSearchIndices = @($residualIndices)
        openSearchContainerPresent = $openSearchContainerPresent
        openSearchContainerRunning = $openSearchContainerRunning
        coreContainersRunning = $coreContainersRunning
    } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'cleanup.json') -Encoding utf8
}

if ($cleanupErrors.Count -gt 0) {
    $message = "Verification cleanup failed: $($cleanupErrors -join ' | ')"
    if ($null -ne $primaryError) {
        throw "$($primaryError.Exception.Message) | $message"
    }
    throw $message
}
if ($null -ne $primaryError) {
    throw $primaryError
}

$resultPath = Join-Path $script:runDirectory 'verification.json'
Write-Host "M8 Catalog search verification passed: $resultPath"
Get-Content -LiteralPath $resultPath -Raw
