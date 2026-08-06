#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(1, 100000)]
    [int]$Requests = 1000,
    [ValidateRange(1, 1000)]
    [int]$Concurrency = 100,
    [switch]$EnableRedisFaultInjection,
    [switch]$SkipBackpressure,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$fixturePassword = 'M5-PlainJournal-2026!'
$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:toolsRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $script:backendRoot
$script:envRestores = [Collections.Generic.List[object]]::new()
$script:catalogProcesses = [Collections.Generic.List[object]]::new()
$script:redisStoppedByScript = $false

$nodeCandidate = Get-Command node -ErrorAction SilentlyContinue
$script:nodeExecutable = if ($nodeCandidate) {
    $nodeCandidate.Source
} else {
    throw 'Node.js was not found on PATH.'
}

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $script:backendRoot ('.run\m5-catalog-cache-smoke-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$script:runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null

$ports = [ordered]@{
    catalog1 = 18102
    catalog2 = 18112
}
$catalogJar = Join-Path $script:backendRoot 'services\catalog-service\target\catalog-service.jar'
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
$fixtureFile = Join-Path $script:backendRoot '.run\m5-baseline-data.json'

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

function Set-ProcessEnvironment {
    param(
        [Parameter(Mandatory)][string]$Name,
        [AllowNull()][string]$Value
    )
    $script:envRestores.Add([pscustomobject]@{
            name = $Name
            value = [Environment]::GetEnvironmentVariable($Name, 'Process')
        })
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnvironment {
    for ($index = $script:envRestores.Count - 1; $index -ge 0; $index--) {
        $entry = $script:envRestores[$index]
        [Environment]::SetEnvironmentVariable($entry.name, $entry.value, 'Process')
    }
    $script:envRestores.Clear()
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by PID $($listener[0].OwningProcess)."
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'no response'
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3 -SkipHttpErrorCheck
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri. Last state: $lastError"
}

function Start-CatalogInstance {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$AppEnvironment,
        [int]$RebuildMaxConcurrent = 16,
        [string]$RebuildWait = '2s',
        [string]$FreshTtl = '1s',
        [string]$StaleTtl = '10s'
    )
    foreach ($entry in @{
            CATALOG_SERVICE_PORT = $Port
            APP_ENV = $AppEnvironment
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics,prometheus'
            MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED = 'true'
            ECOMMERCE_SECURITY_METRICS_TOKEN = $script:metricsToken
            SPRING_APPLICATION_JSON = (
                @{ ecommerce = @{ security = @{ metrics = @{ token = $script:metricsToken } } } } |
                    ConvertTo-Json -Compress -Depth 8)
            ECOMMERCE_CATALOG_CACHE_FRESH_TTL = $FreshTtl
            ECOMMERCE_CATALOG_CACHE_STALE_TTL = $StaleTtl
            ECOMMERCE_CATALOG_CACHE_REBUILD_WAIT = $RebuildWait
            ECOMMERCE_CATALOG_CACHE_REBUILD_MAX_CONCURRENT = $RebuildMaxConcurrent
            ECOMMERCE_CATALOG_CACHE_REFRESH_THREADS = 1
            ECOMMERCE_CATALOG_CACHE_REFRESH_QUEUE_CAPACITY = 1
        }.GetEnumerator()) {
        Set-ProcessEnvironment -Name $entry.Key -Value ([string]$entry.Value)
    }
    Set-ProcessEnvironment -Name 'SERVICE_INSTANCE_ID' -Value "m5-$Name"
    Set-ProcessEnvironment -Name 'SERVICE_RELEASE_ID' -Value 'm5-catalog-cache-v1'
    try {
        $gcLog = Join-Path $script:runDirectory "$Name.gc.log"
        $outLog = Join-Path $script:runDirectory "$Name.out.log"
        $errLog = Join-Path $script:runDirectory "$Name.err.log"
        $arguments = @(
            '-Xms256m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-XX:+UseG1GC',
            '-XX:MaxGCPauseMillis=200',
            "-Xlog:gc*:file=$gcLog`:time,uptime,level,tags",
            '-Dspring.datasource.hikari.maximum-pool-size=10',
            '-Dspring.datasource.hikari.minimum-idle=2',
            '-Dserver.tomcat.threads.max=100',
            '-Dserver.tomcat.threads.min-spare=10',
            '-Dmanagement.tracing.sampling.probability=0.1',
            '-jar',
            $catalogJar
        )
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        $script:catalogProcesses.Add([pscustomobject]@{
                name = $Name
                port = $Port
                process = $process
            })
    } finally {
        Restore-ProcessEnvironment
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$Port/actuator/health/liveness"
}

function Stop-CatalogInstances {
    foreach ($managed in @($script:catalogProcesses)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($managed.process.Id)" `
            -ErrorAction SilentlyContinue
        if ($process -and $process.CommandLine -like "*catalog-service.jar*") {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($port in $ports.Values) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction SilentlyContinue
            if ($process -and $process.CommandLine -like '*catalog-service.jar*') {
                Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    foreach ($managed in @($script:catalogProcesses)) {
        Wait-Process -Id $managed.process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $listeners = @($ports.Values | ForEach-Object {
                Get-NetTCPConnection -State Listen -LocalPort $_ -ErrorAction SilentlyContinue
            })
        if ($listeners.Count -eq 0) {
            $script:catalogProcesses.Clear()
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Catalog cache smoke left ports in use: $($listeners.LocalPort -join ', ')"
}

function Invoke-RedisCli {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = @(
        docker exec plainjournal-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning @Arguments 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Redis command failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Wait-RedisReady {
    param([int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'no response'
    do {
        try {
            $response = @(Invoke-RedisCli -Arguments @('PING'))
            if (($response -join '').Trim() -eq 'PONG') {
                return
            }
            $lastError = $response -join [Environment]::NewLine
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Redis did not recover within $TimeoutSeconds seconds. Last state: $lastError"
}

function Get-CacheKey {
    param([Parameter(Mandatory)][string]$ProductId)
    return "ecommerce:${script:appEnvironment}:catalog:product-detail:v1:$ProductId"
}

function Clear-CacheRecord {
    param([Parameter(Mandatory)][string]$ProductId)
    $key = Get-CacheKey -ProductId $ProductId
    [void](Invoke-RedisCli -Arguments @('DEL', $key))
    [void](Invoke-RedisCli -Arguments @(
            'PUBLISH',
            "ecommerce:${script:appEnvironment}:catalog:product-detail:invalidate",
            $ProductId))
    Start-Sleep -Milliseconds 250
}

function Get-CatalogMetrics {
    param([Parameter(Mandatory)][int]$Port)
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(15)
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            "http://127.0.0.1:$Port/actuator/prometheus")
        [void]$request.Headers.TryAddWithoutValidation('X-Metrics-Token', $script:metricsToken)
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Catalog metrics endpoint returned HTTP $([int]$response.StatusCode): $content"
        }
        return $content
    } finally {
        $client.Dispose()
    }
}

function Get-MetricValue {
    param(
        [Parameter(Mandatory)][string]$Prometheus,
        [Parameter(Mandatory)][string]$Metric
    )
    $match = [regex]::Match(
        $Prometheus,
        "(?m)^$([regex]::Escape($Metric))(?:\{[^}]*\})?\s+([0-9.eE+-]+)\s*$")
    if (-not $match.Success) {
        return 0.0
    }
    return [double]$match.Groups[1].Value
}

function Invoke-CacheLoad {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Urls,
        [Parameter(Mandatory)][int[]]$ExpectedStatuses,
        [string]$ExpectedJsonCode,
        [int]$RequestCount = $Requests,
        [int]$RequestConcurrency = $Concurrency
    )
    $directory = Join-Path $script:runDirectory $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $scenario = [ordered]@{
        name = $Name
        urls = $Urls
        expectedStatuses = $ExpectedStatuses
        weight = 1
    }
    if ($ExpectedJsonCode) {
        $scenario.expectedJsonCode = $ExpectedJsonCode
    }
    $configuration = [ordered]@{
        schemaVersion = 1
        name = $Name
        requests = $RequestCount
        concurrency = $RequestConcurrency
        warmupRequests = 0
        timeoutMs = 15000
        maxErrorRate = 0
        scenarios = @($scenario)
    }
    $configPath = Join-Path $directory 'config.json'
    $resultPath = Join-Path $directory 'result.json'
    $configuration | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $configPath -Encoding utf8
    & $script:nodeExecutable (Join-Path $script:toolsRoot 'm5-http-load-runner.mjs') `
        $configPath $resultPath 2>&1 |
        Set-Content -LiteralPath (Join-Path $directory 'runner-console.log') -Encoding utf8
    $exitCode = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $resultPath)) {
        throw "Cache load $Name produced no result.json (exit $exitCode)."
    }
    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    if ($exitCode -ne 0 -or -not $result.passed) {
        throw "Cache load $Name failed; inspect $resultPath."
    }
    return $result
}

function Invoke-DirectCatalog {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$ProductId,
        [int[]]$ExpectedStatuses = @(200)
    )
    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/api/v1/catalog/products/$ProductId" `
        -TimeoutSec 15 -SkipHttpErrorCheck
    if ($ExpectedStatuses -notcontains $response.StatusCode) {
        throw "Catalog direct request returned HTTP $($response.StatusCode), expected $($ExpectedStatuses -join ',')."
    }
    return $response
}

function Assert-RequiredContainers {
    $required = @(
        'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy', 'plainjournal-minio'
    )
    $running = @(docker ps --format '{{.Names}}')
    $missing = @($required | Where-Object { $running -notcontains $_ })
    if ($missing.Count -gt 0) {
        throw "Required containers are not running: $($missing -join ', ')"
    }
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
if (-not (Test-Path -LiteralPath $catalogJar)) {
    throw "Missing Catalog artifact: $catalogJar"
}
if (-not (Test-Path -LiteralPath $fixtureFile)) {
    throw "Missing M5 fixture manifest: $fixtureFile"
}

Import-DotEnv -Path $envFile
$script:metricsToken = [Environment]::GetEnvironmentVariable('METRICS_SCRAPE_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($script:metricsToken) -or $script:metricsToken.Length -lt 32) {
    throw 'METRICS_SCRAPE_TOKEN is missing or shorter than 32 characters.'
}
Assert-RequiredContainers
foreach ($port in $ports.Values) {
    Assert-PortAvailable -Port $port
}
& (Join-Path $script:toolsRoot 'prepare-m5-baseline-data.ps1') -Action Verify | Out-Null

$fixture = Get-Content -LiteralPath $fixtureFile -Raw | ConvertFrom-Json
$productId = [string]$fixture.fixture.firstProductId
$missingId = '7110000000000009999'
$script:appEnvironment = 'm5-cache-baseline'
$summary = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('O')
    parameters = [ordered]@{
        requests = $Requests
        concurrency = $Concurrency
        appEnvironment = $script:appEnvironment
        node = (& $script:nodeExecutable --version)
        metricsTokenLength = $script:metricsToken.Length
        rebuildMaxConcurrent = 16
        rebuildWait = '2s'
    }
    checks = [ordered]@{}
    passed = $false
}

try {
    Start-CatalogInstance -Name 'catalog-1' -Port $ports.catalog1 `
        -AppEnvironment $script:appEnvironment | Out-Null
    Start-CatalogInstance -Name 'catalog-2' -Port $ports.catalog2 `
        -AppEnvironment $script:appEnvironment | Out-Null

    Clear-CacheRecord -ProductId $productId
    $beforeHot = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_database_loads_total'
    $hot = Invoke-CacheLoad -Name 'hotspot-1000' `
        -Urls @("http://127.0.0.1:$($ports.catalog1)/api/v1/catalog/products/$productId") `
        -ExpectedStatuses @(200) -ExpectedJsonCode 'OK'
    $afterHot = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_database_loads_total'
    $hotDelta = $afterHot - $beforeHot
    if ($hotDelta -gt 2) {
        throw "Hotspot cache single-flight failed: MySQL loads increased by $hotDelta."
    }
    $summary.checks.hotspot = [ordered]@{
        passed = $true
        result = 'hotspot-1000/result.json'
        databaseLoadDelta = $hotDelta
        statusCodes = $hot.aggregate.statusCodes
    }

    Clear-CacheRecord -ProductId $missingId
    $beforeNegative = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_database_loads_total'
    $negative = Invoke-CacheLoad -Name 'negative-cache-penetration-1000' `
        -Urls @("http://127.0.0.1:$($ports.catalog1)/api/v1/catalog/products/$missingId") `
        -ExpectedStatuses @(404) -ExpectedJsonCode 'RESOURCE_NOT_FOUND'
    $afterNegative = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_database_loads_total'
    $negativeDelta = $afterNegative - $beforeNegative
    $negativeKey = (Invoke-RedisCli -Arguments @('EXISTS', (Get-CacheKey -ProductId $missingId))).Trim()
    if ($negativeDelta -gt 2 -or $negativeKey -ne '1') {
        throw "Negative cache penetration guard failed: loads=$negativeDelta keyExists=$negativeKey."
    }
    $summary.checks.negativeCache = [ordered]@{
        passed = $true
        result = 'negative-cache-penetration-1000/result.json'
        databaseLoadDelta = $negativeDelta
        redisKeyExists = ($negativeKey -eq '1')
        statusCodes = $negative.aggregate.statusCodes
    }

    Clear-CacheRecord -ProductId $productId
    [void](Invoke-DirectCatalog -Port $ports.catalog1 -ProductId $productId)
    [void](Invoke-DirectCatalog -Port $ports.catalog2 -ProductId $productId)
    $cacheKey = Get-CacheKey -ProductId $productId
    $rawEnvelope = (@(Invoke-RedisCli -Arguments @('GET', $cacheKey)) -join '')
    if (-not $rawEnvelope) {
        throw 'Unable to read the catalog cache envelope for stale-value verification.'
    }
    $envelope = $rawEnvelope | ConvertFrom-Json
    $envelope.softExpiresAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 1000
    $envelope.hardExpiresAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 600000
    $staleJson = $envelope | ConvertTo-Json -Compress -Depth 8
    [void](Invoke-RedisCli -Arguments @('SET', $cacheKey, $staleJson, 'PX', '600000'))
    [void](Invoke-RedisCli -Arguments @(
            'PUBLISH',
            "ecommerce:${script:appEnvironment}:catalog:product-detail:invalidate",
            $productId))
    Start-Sleep -Seconds 1
    $beforeStale = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_stale_responses_total'
    $stale = Invoke-CacheLoad -Name 'logical-stale-refresh-1000' `
        -Urls @("http://127.0.0.1:$($ports.catalog1)/api/v1/catalog/products/$productId") `
        -ExpectedStatuses @(200) -ExpectedJsonCode 'OK'
    Start-Sleep -Seconds 3
    $afterStale = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog1) `
        'ecommerce_catalog_cache_stale_responses_total'
    if (($afterStale - $beforeStale) -lt 1) {
        throw 'Logical-stale response was not observed.'
    }
    $summary.checks.logicalStaleRefresh = [ordered]@{
        passed = $true
        result = 'logical-stale-refresh-1000/result.json'
        staleResponseDelta = ($afterStale - $beforeStale)
        statusCodes = $stale.aggregate.statusCodes
    }

    Clear-CacheRecord -ProductId $productId
    [void](Invoke-DirectCatalog -Port $ports.catalog1 -ProductId $productId)
    [void](Invoke-DirectCatalog -Port $ports.catalog2 -ProductId $productId)
    $beforeInvalidation = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog2) `
        'ecommerce_catalog_cache_invalidations_total'
    [void](Invoke-RedisCli -Arguments @('DEL', $cacheKey))
    [void](Invoke-RedisCli -Arguments @(
            'PUBLISH',
            "ecommerce:${script:appEnvironment}:catalog:product-detail:invalidate",
            $productId))
    Start-Sleep -Milliseconds 500
    [void](Invoke-DirectCatalog -Port $ports.catalog2 -ProductId $productId)
    $afterInvalidation = Get-MetricValue (Get-CatalogMetrics -Port $ports.catalog2) `
        'ecommerce_catalog_cache_invalidations_total'
    if (($afterInvalidation - $beforeInvalidation) -lt 1) {
        throw 'Second Catalog instance did not observe Redis Pub/Sub invalidation.'
    }
    $summary.checks.pubSubInvalidation = [ordered]@{
        passed = $true
        invalidationDeltaOnCatalog2 = ($afterInvalidation - $beforeInvalidation)
        sharedKeyDeletedBeforeRead = $true
    }

    if ($EnableRedisFaultInjection) {
        $redisState = (docker inspect -f '{{.State.Running}}' plainjournal-redis).Trim()
        if ($redisState -ne 'true') {
            throw 'Redis fault injection requested but plainjournal-redis was not running.'
        }
        Clear-CacheRecord -ProductId $productId
        docker stop plainjournal-redis | Out-Null
        $script:redisStoppedByScript = $true
        $redisDown = Invoke-DirectCatalog -Port $ports.catalog1 -ProductId $productId
        if ($redisDown.StatusCode -ne 200) {
            throw 'Catalog did not fall back to MySQL while Redis was stopped.'
        }
        docker start plainjournal-redis | Out-Null
        $script:redisStoppedByScript = $false
        Wait-RedisReady
        Clear-CacheRecord -ProductId $productId
        $redisRecovered = Invoke-DirectCatalog -Port $ports.catalog1 -ProductId $productId
        if ($redisRecovered.StatusCode -ne 200) {
            throw 'Catalog did not recover after Redis restart.'
        }
        $summary.checks.redisFaultInjection = [ordered]@{
            passed = $true
            stoppedContainer = 'plainjournal-redis'
            fallbackStatus = $redisDown.StatusCode
            recoveryStatus = $redisRecovered.StatusCode
        }
    }

    if (-not $SkipBackpressure) {
        Stop-CatalogInstances
        Start-CatalogInstance -Name 'catalog-1-backpressure' -Port $ports.catalog1 `
            -AppEnvironment $script:appEnvironment `
            -RebuildMaxConcurrent 1 -RebuildWait '0ms' -FreshTtl '4m' -StaleTtl '8m' | Out-Null
        $backpressureIds = 0..99 | ForEach-Object {
            "711000000000009$($_.ToString('0000'))"
        }
        $backpressure = Invoke-CacheLoad -Name 'rebuild-backpressure-100' `
            -Urls ($backpressureIds | ForEach-Object {
                    "http://127.0.0.1:$($ports.catalog1)/api/v1/catalog/products/$_"
                }) `
            -ExpectedStatuses @(404, 503) `
            -RequestCount 100 -RequestConcurrency 100
        $status503 = if ($backpressure.aggregate.statusCodes.PSObject.Properties.Name -contains '503') {
            [int]$backpressure.aggregate.statusCodes.'503'
        } else {
            0
        }
        if ($status503 -lt 1 -or $backpressure.aggregate.statusCodes.PSObject.Properties.Name -contains '500') {
            throw "Backpressure did not produce a bounded 503 rejection: statusCodes=$($backpressure.aggregate.statusCodes | ConvertTo-Json -Compress)"
        }
        $summary.checks.rebuildBackpressure = [ordered]@{
            passed = $true
            result = 'rebuild-backpressure-100/result.json'
            statusCodes = $backpressure.aggregate.statusCodes
            capacityProtection503 = $status503
        }
    }

    $summary.passed = $true
    $summary | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath (Join-Path $script:runDirectory 'summary.json') -Encoding utf8
    Write-Host "M5 Catalog cache smoke passed: $($script:runDirectory)"
}
catch {
    $summary.failure = $_.Exception.Message
    $summary | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath (Join-Path $script:runDirectory 'summary.json') -Encoding utf8
    throw
}
finally {
    if ($script:redisStoppedByScript) {
        docker start plainjournal-redis | Out-Null
        Wait-RedisReady
    }
    Stop-CatalogInstances
}
