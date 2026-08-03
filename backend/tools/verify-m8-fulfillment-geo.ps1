#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:deployDirectory = Join-Path $script:repositoryRoot 'deploy\docker'
$script:runId = "m8-fulfillment-geo-$([DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss'))"
$script:namespace = $script:runId.Replace('m8-fulfillment-geo-', 'geo-')
$script:runDirectory = Join-Path $script:backendRoot ".run\$($script:runId)"
$script:port = 18106
$script:process = $null
$script:jarPath = Join-Path $script:backendRoot `
    'services\fulfillment-service\target\fulfillment-service-0.1.0-SNAPSHOT.jar'
$script:javaPath = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and
    (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
}
else {
    (Get-Command java -ErrorAction Stop).Source
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

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
    $listener = Get-NetTCPConnection -State Listen -LocalPort $script:port `
        -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $($script:port) is already in use."
    }
}

function Invoke-FulfillmentMySql {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllRows
    )

    $lines = @(docker exec -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" `
            plainjournal-mysql mysql "-u$env:FULFILLMENT_DB_USER" `
            $env:FULFILLMENT_DB_NAME -N -B -e $Sql)
    if ($LASTEXITCODE -ne 0) {
        throw 'Fulfillment MySQL command failed.'
    }
    if ($AllRows) {
        return $lines
    }
    return $lines | Select-Object -Last 1
}

function Invoke-Redis {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $lines = @(docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" `
            plainjournal-redis redis-cli --raw @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis command failed.'
    }
    return $lines
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Token,
        [object]$Body
    )

    $parameters = @{
        Method = $Method
        Uri = "http://127.0.0.1:$($script:port)$Path"
        Headers = @{ Authorization = "Bearer $Token" }
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 8
    }
    return Invoke-RestMethod @parameters
}

function Invoke-ApiStatus {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Token
    )

    return Invoke-WebRequest `
        -Method Get `
        -Uri "http://127.0.0.1:$($script:port)$Path" `
        -Headers @{ Authorization = "Bearer $Token" } `
        -SkipHttpErrorCheck `
        -TimeoutSec 15
}

function Start-Fulfillment {
    $environment = @{
        MYSQL_HOST = '127.0.0.1'
        MYSQL_PORT = $env:MYSQL_PORT
        FULFILLMENT_DB_NAME = $env:FULFILLMENT_DB_NAME
        FULFILLMENT_DB_USER = $env:FULFILLMENT_DB_USER
        FULFILLMENT_DB_PASSWORD = $env:FULFILLMENT_DB_PASSWORD
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = $env:REDIS_PORT
        REDIS_PASSWORD = $env:REDIS_PASSWORD
        IDENTITY_JWT_SECRET = $env:IDENTITY_JWT_SECRET
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
        FULFILLMENT_GEO_NAMESPACE = $script:namespace
        FULFILLMENT_GEO_CACHE_ENABLED = 'true'
        FULFILLMENT_GEO_MYSQL_SPATIAL_ENABLED = 'true'
        FULFILLMENT_OUTBOX_ENABLED = 'false'
        FULFILLMENT_ORDER_CONSUMER_ENABLED = 'false'
        FULFILLMENT_AFTER_SALE_CONSUMER_ENABLED = 'false'
        FULFILLMENT_RECONCILIATION_ENABLED = 'false'
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
        $script:process = Start-Process `
            -FilePath $script:javaPath `
            -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory 'fulfillment.out.log') `
            -RedirectStandardError (Join-Path $script:runDirectory 'fulfillment.err.log') `
            -WindowStyle Hidden `
            -PassThru
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable(
                $entry.Key, $entry.Value, 'Process')
        }
    }
}

function Wait-Fulfillment {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        if ($script:process.HasExited) {
            throw "Fulfillment exited before readiness. ExitCode=$($script:process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$($script:port)/actuator/health/liveness" `
                -TimeoutSec 3
            if ($health.status -eq 'UP') {
                return
            }
        }
        catch {
            # Continue until the bounded readiness deadline.
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Fulfillment did not become ready.'
}

function Stop-Fulfillment {
    if ($null -ne $script:process -and -not $script:process.HasExited) {
        Stop-Process -Id $script:process.Id -Force
        Wait-Process -Id $script:process.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:port `
                -ErrorAction SilentlyContinue)
        if ($listeners.Count -eq 0) {
            return
        }
        foreach ($listener in $listeners) {
            $candidate = Get-CimInstance Win32_Process `
                -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction SilentlyContinue
            if ($null -ne $candidate -and
                [string]$candidate.CommandLine -like '*fulfillment-service-0.1.0-SNAPSHOT.jar*') {
                Stop-Process -Id $listener.OwningProcess -Force
            }
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if (Get-NetTCPConnection -State Listen -LocalPort $script:port `
            -ErrorAction SilentlyContinue) {
        throw "Fulfillment port $($script:port) remains open after cleanup."
    }
}

Import-LocalEnvironment
Require-Environment -Names @(
    'MYSQL_PORT',
    'REDIS_PORT',
    'REDIS_PASSWORD',
    'FULFILLMENT_DB_NAME',
    'FULFILLMENT_DB_USER',
    'FULFILLMENT_DB_PASSWORD',
    'IDENTITY_JWT_SECRET',
    'METRICS_SCRAPE_TOKEN')

if (-not $SkipNetworkPreflight) {
    & 'D:\DevTools\Network\check-dev-network.ps1'
}
foreach ($container in @('plainjournal-mysql', 'plainjournal-redis')) {
    if ((docker inspect --format '{{.State.Running}}' $container 2>$null) -ne 'true') {
        throw "Required container is not running: $container"
    }
}
Assert-PortAvailable

if (-not $SkipBuild) {
    & mvn -pl services/fulfillment-service -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'Fulfillment package build failed.'
    }
}
if (-not (Test-Path -LiteralPath $script:jarPath)) {
    throw "Missing Fulfillment artifact: $($script:jarPath)"
}

$tokenSuffix = [Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant()
$baseId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 100000L +
    (Get-Random -Minimum 1000 -Maximum 9999)
$fulfillmentId = $baseId
$userId = $baseId + 1
$otherUserId = $baseId + 2
$fulfillmentNo = "FULGEO$tokenSuffix"
$orderNo = "ORDGEO$tokenSuffix"
$trackingNo = "TRACKGEO$tokenSuffix"
$geoKey = "ecommerce:$($script:namespace):fulfillment:geo:latest"
$metadataKey = "ecommerce:$($script:namespace):fulfillment:geo:position:$fulfillmentNo"
$customerToken = New-AccessToken -UserId $userId -Roles @('CUSTOMER')
$otherToken = New-AccessToken -UserId $otherUserId -Roles @('CUSTOMER')
$adminToken = New-AccessToken -UserId ($baseId + 3) -Roles @('ADMIN', 'WAREHOUSE')
$verification = [ordered]@{}
$verificationError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()

try {
    Start-Fulfillment
    Wait-Fulfillment

    $nowSql = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
    Invoke-FulfillmentMySql -Sql @"
INSERT INTO fulfillment_order (
    id, fulfillment_no, order_no, user_id, status, carrier, tracking_no,
    version, created_at, updated_at, picked_at, packed_at, shipped_at, signed_at,
    source_address_id, recipient_name, phone, province, city, district,
    detail_address, postal_code, province_code, city_code, district_code,
    latest_position_trace_id, latest_position_at
)
VALUES (
    $fulfillmentId, '$fulfillmentNo', '$orderNo', $userId, 'CREATED', NULL, NULL,
    0, '$nowSql', '$nowSql', NULL, NULL, NULL, NULL,
    $($baseId + 4), 'M8 GEO Customer', '+86 13800000000',
    '浙江省', '杭州市', '西湖区', '文三路 1 号', '310000',
    '330000', '330100', '330106', NULL, NULL
);
"@ | Out-Null

    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/picking" `
        -Token $adminToken | Out-Null
    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/packed" `
        -Token $adminToken | Out-Null
    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/ship" `
        -Token $adminToken `
        -Body @{ carrier = 'MOCK_EXPRESS'; trackingNo = $trackingNo } | Out-Null

    $nanjingAt = [DateTimeOffset]::UtcNow.AddMinutes(-10)
    $shanghaiAt = [DateTimeOffset]::UtcNow.AddMinutes(-5)
    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/traces" `
        -Token $adminToken `
        -Body @{
            externalEventId = "GEO-NANJING-$tokenSuffix"
            nodeType = 'TRANSIT'
            description = 'Arrived at Nanjing sorting center'
            locationName = 'Nanjing'
            longitude = 118.796877
            latitude = 32.060255
            occurredAt = $nanjingAt.ToString('o')
        } | Out-Null
    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/traces" `
        -Token $adminToken `
        -Body @{
            externalEventId = "GEO-SHANGHAI-$tokenSuffix"
            nodeType = 'DELIVERING'
            description = 'Courier is delivering in Shanghai'
            locationName = 'Shanghai'
            longitude = 121.473700
            latitude = 31.230400
            occurredAt = $shanghaiAt.ToString('o')
        } | Out-Null
    Invoke-JsonApi -Method Post `
        -Path "/api/v1/fulfillment/admin/orders/$fulfillmentNo/traces" `
        -Token $adminToken `
        -Body @{
            externalEventId = "GEO-OLDER-$tokenSuffix"
            nodeType = 'DELIVERING'
            description = 'Delayed older carrier event'
            locationName = 'Suzhou'
            longitude = 120.585300
            latitude = 31.298900
            occurredAt = $nanjingAt.AddMinutes(1).ToString('o')
        } | Out-Null

    $owner = Invoke-JsonApi -Method Get `
        -Path "/api/v1/fulfillment/orders/$orderNo/position" `
        -Token $customerToken
    if ($owner.data.locationName -ne 'Shanghai') {
        throw 'Latest position did not retain the newest occurredAt fact.'
    }
    $hidden = Invoke-ApiStatus `
        -Path "/api/v1/fulfillment/orders/$orderNo/position" `
        -Token $otherToken
    if ($hidden.StatusCode -ne 404) {
        throw "Cross-account position lookup returned HTTP $($hidden.StatusCode)."
    }

    $nearby = Invoke-JsonApi -Method Get `
        -Path '/api/v1/fulfillment/admin/geo/nearby?longitude=121.473700&latitude=31.230400&radiusMeters=10000&limit=10' `
        -Token $adminToken
    if (@($nearby.data).Count -ne 1 -or
        $nearby.data[0].fulfillmentNo -ne $fulfillmentNo) {
        throw 'MySQL spatial nearby query did not return the expected shipment.'
    }

    $spatialSchema = Invoke-FulfillmentMySql -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'shipment_latest_position'
       AND column_name = 'coordinates'
       AND data_type = 'point'
       AND srs_id = 4326), '|',
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'shipment_latest_position'
       AND index_name = 'idx_shipment_latest_position_coordinates'
       AND index_type = 'SPATIAL')
);
"@
    if ($spatialSchema -ne '1|1') {
        throw "MySQL spatial schema verification failed: $spatialSchema"
    }
    $mysqlPosition = Invoke-FulfillmentMySql -Sql @"
SELECT CONCAT(
    COUNT(*), '|', MAX(external_event_id), '|',
    ROUND(MAX(ST_Distance_Sphere(
        coordinates,
        ST_GeomFromText(
            'POINT(121.473700 31.230400)',
            4326,
            'axis-order=long-lat'))), 2))
FROM shipment_latest_position
WHERE fulfillment_id = $fulfillmentId;
"@
    $mysqlPositionParts = $mysqlPosition -split '\|', 3
    if ($mysqlPositionParts.Count -ne 3 -or
        $mysqlPositionParts[0] -ne '1' -or
        $mysqlPositionParts[1] -ne "GEO-SHANGHAI-$tokenSuffix" -or
        [decimal]$mysqlPositionParts[2] -ne 0) {
        throw "MySQL latest position fact was unexpected: $mysqlPosition"
    }

    $geoPosition = @(Invoke-Redis -Arguments @('GEOPOS', $geoKey, $fulfillmentNo))
    $metadataExists = [int]((Invoke-Redis -Arguments @('EXISTS', $metadataKey)) |
            Select-Object -Last 1)
    if ($geoPosition.Count -lt 2 -or
        [string]::IsNullOrWhiteSpace($geoPosition[0]) -or
        $metadataExists -ne 1) {
        throw 'Redis GEO projection was not written after commit.'
    }

    Invoke-Redis -Arguments @('DEL', $geoKey, $metadataKey) | Out-Null
    $fallbackAfterLoss = Invoke-JsonApi -Method Get `
        -Path "/api/v1/fulfillment/orders/$orderNo/position" `
        -Token $customerToken
    if ($fallbackAfterLoss.data.locationName -ne 'Shanghai') {
        throw 'Redis loss did not fall back to the MySQL position fact.'
    }

    Invoke-Redis -Arguments @('CLIENT', 'PAUSE', '1500', 'ALL') | Out-Null
    $fallbackDuringPause = Invoke-JsonApi -Method Get `
        -Path "/api/v1/fulfillment/orders/$orderNo/position" `
        -Token $customerToken
    if ($fallbackDuringPause.data.locationName -ne 'Shanghai') {
        throw 'Redis unavailability did not fall back to MySQL.'
    }
    Start-Sleep -Milliseconds 1750

    $rebuild = Invoke-JsonApi -Method Post `
        -Path '/api/v1/fulfillment/admin/geo/cache/rebuild?limit=5000' `
        -Token $adminToken
    if ([int]$rebuild.data.cached -lt 1) {
        throw 'Redis GEO rebuild did not restore the MySQL projection.'
    }
    $rebuiltExists = [int]((Invoke-Redis -Arguments @('EXISTS', $metadataKey)) |
            Select-Object -Last 1)
    if ($rebuiltExists -ne 1) {
        throw 'Redis GEO metadata was not present after rebuild.'
    }

    $verification.mysql = [ordered]@{
        spatialColumnAndIndex = $spatialSchema
        latestPosition = $mysqlPosition
        nearbyResults = @($nearby.data).Count
        olderEventIgnored = $true
    }
    $verification.redis = [ordered]@{
        afterCommitProjection = $true
        lossFallbackToMysql = $true
        pauseFallbackToMysql = $true
        rebuildCached = [int]$rebuild.data.cached
    }
    $verification.authorization = [ordered]@{
        ownerRead = $true
        crossAccountHttpStatus = [int]$hidden.StatusCode
    }
}
catch {
    $verificationError = $_
}
finally {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Invoke-Redis -Arguments @('DEL', $geoKey, $metadataKey) | Out-Null
    }
    catch {
        $cleanupErrors.Add("Redis: $($_.Exception.Message)")
    }
    try {
        Invoke-FulfillmentMySql -Sql @"
DELETE FROM outbox_event WHERE aggregate_id = '$fulfillmentNo';
DELETE FROM shipment_latest_position WHERE fulfillment_id = $fulfillmentId;
DELETE FROM logistics_trace WHERE fulfillment_id = $fulfillmentId;
DELETE FROM fulfillment_status_history WHERE fulfillment_id = $fulfillmentId;
DELETE FROM fulfillment_order WHERE id = $fulfillmentId;
"@ | Out-Null
    }
    catch {
        $cleanupErrors.Add("MySQL: $($_.Exception.Message)")
    }
    try {
        Stop-Fulfillment
    }
    catch {
        $cleanupErrors.Add("Process: $($_.Exception.Message)")
    }
    $ErrorActionPreference = $previousErrorAction
}

$remainingRows = [int](Invoke-FulfillmentMySql -Sql @"
SELECT
    (SELECT COUNT(*) FROM fulfillment_order WHERE id = $fulfillmentId)
  + (SELECT COUNT(*) FROM shipment_latest_position WHERE fulfillment_id = $fulfillmentId)
  + (SELECT COUNT(*) FROM logistics_trace WHERE fulfillment_id = $fulfillmentId)
  + (SELECT COUNT(*) FROM fulfillment_status_history WHERE fulfillment_id = $fulfillmentId)
  + (SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = '$fulfillmentNo');
"@)
$remainingRedisKeys = [int]((Invoke-Redis -Arguments @(
            'EXISTS', $geoKey, $metadataKey)) | Select-Object -Last 1)
$remainingListeners = @(Get-NetTCPConnection -State Listen -LocalPort $script:port `
        -ErrorAction SilentlyContinue).Count
$verification.cleanup = [ordered]@{
    databaseRows = $remainingRows
    redisKeys = $remainingRedisKeys
    portListeners = $remainingListeners
}
$verification.runId = $script:runId
$verification.generatedAt = [DateTimeOffset]::Now.ToString('o')
$verificationPath = Join-Path $script:runDirectory 'verification.json'
$verification | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $verificationPath -Encoding utf8

if ($verificationError) {
    throw $verificationError
}
if ($cleanupErrors.Count -gt 0) {
    throw "M8 Fulfillment GEO cleanup failed: $($cleanupErrors -join ' | ')"
}
if ($remainingRows -ne 0 -or $remainingRedisKeys -ne 0 -or $remainingListeners -ne 0) {
    throw 'M8 Fulfillment GEO verification left transient residue.'
}

Write-Host "M8 Fulfillment GEO verification passed: $verificationPath"
$verification | ConvertTo-Json -Depth 8
