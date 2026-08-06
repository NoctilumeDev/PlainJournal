#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(1, 100000)]
    [int]$RequestsPerRun = 1000,
    [ValidateRange(1, 10)]
    [int]$Repetitions = 3,
    [int[]]$ConcurrencyLevels = @(1, 5, 10, 20, 50, 100),
    [ValidateRange(128, 2048)]
    [int]$HeapMiB = 256,
    [ValidateRange(1, 24)]
    [int]$ActiveProcessorCount = 4,
    [ValidateRange(1, 200)]
    [int]$HikariMaximumPoolSize = 20,
    [ValidateRange(10, 500)]
    [int]$TomcatMaximumThreads = 100,
    [switch]$SkipDenseBoundary,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$fixturePassword = 'M5-PlainJournal-2026!'
$ports = [ordered]@{
    gateway = 18000
    identity = 18101
    catalog = 18102
    trade = 18104
}
$script:serviceProcesses = [ordered]@{}
$script:environmentRestores = [Collections.Generic.List[object]]::new()

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

    $script:environmentRestores.Add([pscustomobject]@{
            name = $Name
            value = [Environment]::GetEnvironmentVariable($Name, 'Process')
        })
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnvironment {
    for ($index = $script:environmentRestores.Count - 1; $index -ge 0; $index--) {
        $entry = $script:environmentRestores[$index]
        [Environment]::SetEnvironmentVariable($entry.name, $entry.value, 'Process')
    }
    $script:environmentRestores.Clear()
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
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Uri. Last state: $lastError"
}

function Start-BaselineService {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][string]$GcLog,
        [hashtable]$Environment = @{}
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing application artifact: $Jar"
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        Set-ProcessEnvironment -Name $entry.Key -Value ([string]$entry.Value)
    }
    Set-ProcessEnvironment -Name 'SERVICE_INSTANCE_ID' -Value $InstanceId
    Set-ProcessEnvironment -Name 'SERVICE_RELEASE_ID' -Value 'm5-query-baseline-v1'
    try {
        $arguments = @(
            "-Xms${HeapMiB}m",
            "-Xmx${HeapMiB}m",
            "-XX:ActiveProcessorCount=$ActiveProcessorCount",
            '-XX:+UseG1GC',
            '-XX:MaxGCPauseMillis=200',
            "-Xlog:gc*:file=$GcLog`:time,uptime,level,tags",
            "-Dspring.datasource.hikari.maximum-pool-size=$HikariMaximumPoolSize",
            '-Dspring.datasource.hikari.minimum-idle=2',
            "-Dserver.tomcat.threads.max=$TomcatMaximumThreads",
            '-Dserver.tomcat.threads.min-spare=10',
            '-Dmanagement.tracing.sampling.probability=0.1',
            '-jar',
            $Jar
        )
        $outLog = Join-Path $script:runDirectory "$Name.out.log"
        $errLog = Join-Path $script:runDirectory "$Name.err.log"
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        $script:serviceProcesses[$Name] = [pscustomobject]@{
            process = $process
            jar = $Jar
        }
        return $process
    }
    finally {
        Restore-ProcessEnvironment
    }
}

function Stop-BaselineServices {
    $expectedJarByPort = @{
        18000 = 'ecommerce-gateway.jar'
        18101 = 'identity-service.jar'
        18102 = 'catalog-service.jar'
        18104 = 'trade-service.jar'
    }

    foreach ($port in @($expectedJarByPort.Keys)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction SilentlyContinue
            if ($null -eq $process) {
                continue
            }
            $expectedJar = $expectedJarByPort[$port]
            if ($process.CommandLine -notlike "*$expectedJar*") {
                Write-Warning "Refused to stop PID $($process.ProcessId) on port $port; it does not match $expectedJar."
                continue
            }
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $process.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        }
    }

    foreach ($name in @($script:serviceProcesses.Keys)) {
        $managed = $script:serviceProcesses[$name]
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($managed.process.Id)" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        if ($process.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            Write-Warning "Refused to stop PID $($process.ProcessId); its command line no longer matches $($managed.jar)."
            continue
        }
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $process.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }

    $deadline = (Get-Date).AddSeconds(15)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
                Where-Object { $ports.Values -contains $_.LocalPort })
        if ($listeners.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    $details = $listeners | ForEach-Object { "$($_.LocalPort)/pid=$($_.OwningProcess)" }
    throw "M5 baseline services did not release their ports: $($details -join ', ')"
}

function Assert-BaselinePortsReleased {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $ports.Values -contains $_.LocalPort })
    if ($listeners.Count -gt 0) {
        $details = $listeners | ForEach-Object { "$($_.LocalPort)/pid=$($_.OwningProcess)" }
        throw "M5 query run left business ports in use: $($details -join ', ')"
    }
}

function Show-ServiceLogTails {
    foreach ($name in $script:serviceProcesses.Keys) {
        foreach ($suffix in @('out', 'err')) {
            $path = Join-Path $script:runDirectory "$name.$suffix.log"
            if (Test-Path -LiteralPath $path) {
                Write-Host "--- $path ---"
                Get-Content -LiteralPath $path -Tail 60
            }
        }
    }
}

function Invoke-FixtureLogin {
    param([Parameter(Mandatory)][string]$Email)

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:$($ports.identity)/api/v1/identity/auth/login" `
        -ContentType 'application/json' `
        -Body (@{
                email = $Email
                password = $fixturePassword
            } | ConvertTo-Json -Compress) `
        -TimeoutSec 15
    if ($response.code -ne 'OK' -or -not $response.data.accessToken) {
        throw "M5 fixture login failed for $Email."
    }
    return [string]$response.data.accessToken
}

function Refresh-FixtureTokens {
    param([switch]$IncludeDenseUser)

    $env:M5_NORMAL_TOKEN = Invoke-FixtureLogin -Email 'm5.user.0002@plainjournal.local'
    if ($IncludeDenseUser) {
        $env:M5_DENSE_TOKEN = Invoke-FixtureLogin -Email 'm5.user.0001@plainjournal.local'
    }
}

function Invoke-TradeQuery {
    param([Parameter(Mandatory)][string]$Sql)

    $output = @(
        $Sql | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "--user=$env:TRADE_DB_USER" --batch --skip-column-names $env:TRADE_DB_NAME 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to query M5 Trade fixture: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Save-ServiceMetrics {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    $tradeResponse = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$($ports.trade)/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } `
        -TimeoutSec 15
    Set-Content -LiteralPath (Join-Path $Directory "$Stage-trade.prom") `
        -Value $tradeResponse.Content -Encoding utf8

    $selectedMetrics = @(
        'process.cpu.usage',
        'system.cpu.usage',
        'jvm.memory.used',
        'jvm.gc.pause',
        'hikaricp.connections.active',
        'hikaricp.connections.idle',
        'hikaricp.connections.pending',
        'http.server.requests'
    )
    $tokenHeaders = @{ Authorization = "Bearer $env:M5_NORMAL_TOKEN" }
    foreach ($service in @('identity', 'catalog')) {
        $captured = [ordered]@{}
        foreach ($metric in $selectedMetrics) {
            $uri = "http://127.0.0.1:$($ports[$service])/actuator/metrics/$metric"
            $response = Invoke-WebRequest -Uri $uri -Headers $tokenHeaders `
                -SkipHttpErrorCheck -TimeoutSec 15
            $captured[$metric] = [ordered]@{
                status = [int]$response.StatusCode
                data = if ($response.StatusCode -eq 200) {
                    $response.Content | ConvertFrom-Json
                } else {
                    $null
                }
            }
        }
        $captured | ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath (Join-Path $Directory "$Stage-$service-metrics.json") -Encoding utf8
    }
}

function Save-HostProcessSnapshot {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    $rows = foreach ($service in @($script:serviceProcesses.Keys)) {
        $managed = $script:serviceProcesses[$service]
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $ports[$service] `
                -ErrorAction SilentlyContinue)
        if ($listeners.Count -ne 1) {
            throw "Expected one listener for $service on port $($ports[$service]); found $($listeners.Count)."
        }
        $processId = [int]$listeners[0].OwningProcess
        $command = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction Stop
        if ($command.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            throw "Listener PID $processId for $service does not match $($managed.jar)."
        }
        $process = Get-Process -Id $processId -ErrorAction Stop
        [ordered]@{
            service = $service
            pid = $processId
            totalProcessorSeconds = [Math]::Round($process.TotalProcessorTime.TotalSeconds, 3)
            workingSetBytes = [long]$process.WorkingSet64
            privateMemoryBytes = [long]$process.PrivateMemorySize64
            pagedMemoryBytes = [long]$process.PagedMemorySize64
            threadCount = $process.Threads.Count
            handleCount = $process.HandleCount
        }
    }
    $rows | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $Directory "$Stage-host-processes.json") -Encoding utf8
}

function Save-EvidenceSnapshot {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    Save-ServiceMetrics -Stage $Stage -Directory $Directory
    Save-HostProcessSnapshot -Stage $Stage -Directory $Directory
    & (Join-Path $script:toolsRoot 'capture-m5-environment.ps1') `
        -OutputPath (Join-Path $Directory "$Stage-environment.json") | Out-Null
}

function Invoke-LoadRun {
    param(
        [Parameter(Mandatory)][string]$Suite,
        [Parameter(Mandatory)][int]$Concurrency,
        [Parameter(Mandatory)][int]$Repetition,
        [Parameter(Mandatory)][int]$Requests,
        [Parameter(Mandatory)][int]$TimeoutMs,
        [Parameter(Mandatory)][object[]]$Scenarios
    )

    $runName = "$Suite-c$Concurrency-r$Repetition"
    $directory = Join-Path $script:runDirectory $runName
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Refresh-FixtureTokens -IncludeDenseUser:($Suite -eq 'trade-order-list-dense-boundary')
    $configurationPath = Join-Path $directory 'config.json'
    $resultPath = Join-Path $directory 'result.json'
    $configuration = [ordered]@{
        schemaVersion = 1
        name = $runName
        requests = $Requests
        concurrency = $Concurrency
        warmupRequests = [Math]::Min([Math]::Max($Concurrency * 2, 10), $Requests)
        timeoutMs = $TimeoutMs
        maxErrorRate = 0
        scenarios = $Scenarios
    }
    $configuration | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $configurationPath -Encoding utf8

    Save-EvidenceSnapshot -Stage 'before' -Directory $directory
    $runnerOutput = @(
        & $script:nodeExecutable (Join-Path $script:toolsRoot 'm5-http-load-runner.mjs') `
            $configurationPath $resultPath 2>&1
    )
    $runnerExitCode = $LASTEXITCODE
    $runnerOutput |
        Set-Content -LiteralPath (Join-Path $directory 'runner-console.log') -Encoding utf8
    Save-EvidenceSnapshot -Stage 'after' -Directory $directory
    if ($runnerExitCode -ne 0) {
        throw "Load run failed: $runName (exit $runnerExitCode). " +
            ($runnerOutput -join [Environment]::NewLine)
    }
    $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
    return [ordered]@{
        suite = $Suite
        concurrency = $Concurrency
        repetition = $Repetition
        requests = $result.aggregate.requests
        requestsPerSecond = $result.aggregate.requestsPerSecond
        p50Ms = $result.aggregate.latency.p50Ms
        p95Ms = $result.aggregate.latency.p95Ms
        p99Ms = $result.aggregate.latency.p99Ms
        errors = $result.aggregate.transportErrors + $result.aggregate.validationErrors
        result = $resultPath
    }
}

if ($ConcurrencyLevels.Count -eq 0) {
    throw 'At least one concurrency level is required.'
}
foreach ($level in $ConcurrencyLevels) {
    if ($level -lt 1 -or $level -gt $RequestsPerRun) {
        throw "Invalid concurrency level $level for $RequestsPerRun requests."
    }
}

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:toolsRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $script:backendRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile

$nodeCandidate = Get-Command node -ErrorAction SilentlyContinue
$script:nodeExecutable = if ($nodeCandidate) {
    $nodeCandidate.Source
} else {
    throw 'Node.js was not found on PATH.'
}

if (-not $OutputDirectory) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $script:backendRoot ".run\m5-query-capacity-$timestamp"
}
$script:runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
$script:gcLogDirectory = [IO.Path]::GetRelativePath(
    $script:backendRoot,
    $script:runDirectory
).Replace('\', '/')

$requiredContainers = @(
    'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker', 'plainjournal-rocketmq-proxy', 'plainjournal-minio'
)
$runningContainers = @(docker ps --format '{{.Names}}')
$missingContainers = @($requiredContainers | Where-Object { $runningContainers -notcontains $_ })
if ($missingContainers.Count -gt 0) {
    throw "Required containers are not running: $($missingContainers -join ', ')"
}

foreach ($port in $ports.Values) {
    Assert-PortAvailable -Port $port
}

& (Join-Path $script:toolsRoot 'prepare-m5-baseline-data.ps1') -Action Verify | Out-Null

$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway\target\ecommerce-gateway.jar'
$identityJar = Join-Path $script:backendRoot 'services\identity-service\target\identity-service.jar'
$catalogJar = Join-Path $script:backendRoot 'services\catalog-service\target\catalog-service.jar'
$tradeJar = Join-Path $script:backendRoot 'services\trade-service\target\trade-service.jar'

$summaries = [Collections.Generic.List[object]]::new()
$primaryError = $null
try {
    Start-BaselineService -Name 'identity' -Jar $identityJar `
        -InstanceId 'm5-identity-1' -GcLog "$($script:gcLogDirectory)/identity.gc.log" `
        -Environment @{
            APP_ENV = 'm5-query-baseline'
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.identity)/actuator/health/liveness"

    Start-BaselineService -Name 'catalog' -Jar $catalogJar `
        -InstanceId 'm5-catalog-1' -GcLog "$($script:gcLogDirectory)/catalog.gc.log" `
        -Environment @{
            APP_ENV = 'm5-query-baseline'
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.catalog)/actuator/health/liveness"

    Start-BaselineService -Name 'trade' -Jar $tradeJar `
        -InstanceId 'm5-trade-1' -GcLog "$($script:gcLogDirectory)/trade.gc.log" `
        -Environment @{
            TRADE_OUTBOX_ENABLED = 'false'
            TRADE_ORDER_RECOVERY_ENABLED = 'false'
            TRADE_RECONCILIATION_ENABLED = 'false'
            TRADE_PAYMENT_CONSUMER_ENABLED = 'false'
            TRADE_FULFILLMENT_CONSUMER_ENABLED = 'false'
            TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED = 'false'
            TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED = 'false'
            TRADE_REFUND_RESULT_CONSUMER_ENABLED = 'false'
            ECOMMERCE_SECURITY_METRICS_TOKEN = $env:METRICS_SCRAPE_TOKEN
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE =
                'health,info,metrics,prometheus,consumerfailures,businessprocesses'
            MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED = 'true'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.trade)/actuator/health/liveness"

    Start-BaselineService -Name 'gateway' -Jar $gatewayJar `
        -InstanceId 'm5-gateway-1' -GcLog "$($script:gcLogDirectory)/gateway.gc.log" `
        -Environment @{
            APP_ENV = 'm5-query-baseline'
            JAVA_TOOL_OPTIONS = "-Dreactor.netty.ioWorkerCount=$ActiveProcessorCount"
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.gateway)/actuator/health/liveness"
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products?page=1&size=1"

    Refresh-FixtureTokens -IncludeDenseUser

    $catalogDetailUrls = 1..1000 | ForEach-Object {
        "http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products/$([long]7110000000000000000 + $_)"
    }
    $catalogListUrls = @(1, 2, 10, 25, 50) | ForEach-Object {
        "http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products?page=$_&size=20"
    }
    $catalogScenarios = @(
        [ordered]@{
            name = 'catalog-list'
            urls = $catalogListUrls
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'catalog-detail'
            urls = $catalogDetailUrls
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        }
    )

    $normalUserId = [long]7130000000000000002
    $normalOrderNumbers = @(Invoke-TradeQuery -Sql @"
SELECT order_no FROM trade_order
WHERE user_id = $normalUserId AND order_no LIKE 'M5-ORD-%'
ORDER BY created_at, order_no;
"@)
    if ($normalOrderNumbers.Count -lt 1) {
        throw 'The normal M5 fixture user has no historical orders.'
    }
    $tradeDetailUrls = $normalOrderNumbers | ForEach-Object {
        "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/$_"
    }
    $tradeScenarios = @(
        [ordered]@{
            name = 'trade-cart-list'
            url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/cart/items"
            headers = @{ Authorization = 'Bearer ${ENV:M5_NORMAL_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'trade-order-list-normal'
            url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page?page=1&size=20"
            headers = @{ Authorization = 'Bearer ${ENV:M5_NORMAL_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'trade-order-detail'
            urls = $tradeDetailUrls
            headers = @{ Authorization = 'Bearer ${ENV:M5_NORMAL_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        }
    )

    foreach ($concurrency in $ConcurrencyLevels) {
        foreach ($repetition in 1..$Repetitions) {
            $summaries.Add((Invoke-LoadRun `
                        -Suite 'catalog-query-mix' `
                        -Concurrency $concurrency `
                        -Repetition $repetition `
                        -Requests $RequestsPerRun `
                        -TimeoutMs 10000 `
                        -Scenarios $catalogScenarios))
            $summaries.Add((Invoke-LoadRun `
                        -Suite 'trade-query-mix' `
                        -Concurrency $concurrency `
                        -Repetition $repetition `
                        -Requests $RequestsPerRun `
                        -TimeoutMs 15000 `
                        -Scenarios $tradeScenarios))
        }
    }

    if (-not $SkipDenseBoundary) {
        $denseScenario = @(
            [ordered]@{
                name = 'trade-order-list-dense'
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page?page=1&size=100"
                headers = @{ Authorization = 'Bearer ${ENV:M5_DENSE_TOKEN}' }
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                weight = 1
            }
        )
        foreach ($repetition in 1..3) {
            $summaries.Add((Invoke-LoadRun `
                        -Suite 'trade-order-list-dense-boundary' `
                        -Concurrency 1 `
                        -Repetition $repetition `
                        -Requests 1 `
                        -TimeoutMs 120000 `
                        -Scenarios $denseScenario))
        }
    }

    $summary = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        parameters = [ordered]@{
            requestsPerRun = $RequestsPerRun
            repetitions = $Repetitions
            concurrencyLevels = $ConcurrencyLevels
            heapMiB = $HeapMiB
            activeProcessorCount = $ActiveProcessorCount
            hikariMaximumPoolSize = $HikariMaximumPoolSize
            tomcatMaximumThreads = $TomcatMaximumThreads
            denseBoundaryIncluded = -not $SkipDenseBoundary
            tradeBackgroundWorkDisabled = $true
        }
        runs = $summaries
    }
    $summaryPath = Join-Path $script:runDirectory 'summary.json'
    $summary | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
    $summary | ConvertTo-Json -Depth 10
}
catch {
    $primaryError = $_
    Show-ServiceLogTails
}
finally {
    [Environment]::SetEnvironmentVariable('M5_NORMAL_TOKEN', $null, 'Process')
    [Environment]::SetEnvironmentVariable('M5_DENSE_TOKEN', $null, 'Process')
    try {
        Stop-BaselineServices
        Assert-BaselinePortsReleased
    }
    catch {
        if ($null -ne $primaryError) {
            Write-Warning "M5 cleanup also failed: $($_.Exception.Message)"
        }
        else {
            throw
        }
    }
}

if ($null -ne $primaryError) {
    throw $primaryError
}
