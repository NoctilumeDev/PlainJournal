#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Small', 'Medium', 'Formal')]
    [string]$Scale = 'Small',
    [ValidateRange(10, 100000)]
    [int]$RequestsPerRun = 300,
    [ValidateRange(1, 10)]
    [int]$Repetitions = 3,
    [ValidateRange(1, 1000)]
    [int]$Concurrency = 20,
    [ValidateRange(128, 2048)]
    [int]$HeapMiB = 256,
    [ValidateRange(1, 24)]
    [int]$ActiveProcessorCount = 4,
    [ValidateRange(1, 200)]
    [int]$HikariMaximumPoolSize = 20,
    [ValidateRange(10, 500)]
    [int]$TomcatMaximumThreads = 100,
    [ValidateRange(20, 100)]
    [int]$PageSize = 100,
    [switch]$AllowFormal,
    [string]$FixtureManifestPath,
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

function Assert-ResourceBudget {
    $thresholds = @{
        Small = @{ freeMemoryBytes = 2500MB; freeDiskBytes = 15GB }
        Medium = @{ freeMemoryBytes = 3500MB; freeDiskBytes = 50GB }
        Formal = @{ freeMemoryBytes = 4500MB; freeDiskBytes = 100GB }
    }
    $threshold = $thresholds[$Scale]
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $freeMemoryBytes = [long]$operatingSystem.FreePhysicalMemory * 1KB
    $drive = Get-PSDrive -Name C
    if ($freeMemoryBytes -lt $threshold.freeMemoryBytes) {
        throw "M7 $Scale query run requires at least " +
            "$([Math]::Round($threshold.freeMemoryBytes / 1GB, 2)) GiB free host memory; " +
            "current free memory is $([Math]::Round($freeMemoryBytes / 1GB, 2)) GiB."
    }
    if ([long]$drive.Free -lt $threshold.freeDiskBytes) {
        throw "M7 $Scale query run requires at least " +
            "$([Math]::Round($threshold.freeDiskBytes / 1GB, 1)) GiB free space on C:; " +
            "current free space is $([Math]::Round([long]$drive.Free / 1GB, 2)) GiB."
    }
    $mysqlInspect = @(docker inspect plainjournal-mysql | ConvertFrom-Json)[0]
    if ($mysqlInspect.State.OOMKilled) {
        throw 'The MySQL container reports OOMKilled=true; recreate it before an M7 capacity run.'
    }
    $script:mysqlRestartCountBefore = [int]$mysqlInspect.RestartCount
    $script:mysqlStartedAtBefore = [string]$mysqlInspect.State.StartedAt
    $heavyContainers = @(
        'plainjournal-prometheus', 'plainjournal-alertmanager', 'plainjournal-grafana', 'plainjournal-tempo',
        'plainjournal-mysql-replica', 'plainjournal-mysql-trade-shard-1'
    )
    $running = @(docker ps --format '{{.Names}}')
    $conflicts = @($heavyContainers | Where-Object { $running -contains $_ })
    if ($conflicts.Count -gt 0) {
        throw "M7 query baseline uses an exclusive profile. Stop conflicting containers first: " +
            ($conflicts -join ', ')
    }
}

function Assert-MySqlStayedStable {
    $mysqlInspect = @(docker inspect plainjournal-mysql | ConvertFrom-Json)[0]
    if ($mysqlInspect.State.OOMKilled `
            -or [int]$mysqlInspect.RestartCount -ne $script:mysqlRestartCountBefore `
            -or [string]$mysqlInspect.State.StartedAt -ne $script:mysqlStartedAtBefore) {
        throw 'The MySQL container restarted or was OOM-killed during the M7 query baseline.'
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
    Set-ProcessEnvironment -Name 'SERVICE_RELEASE_ID' -Value 'm7-scale-baseline-v1'
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
            '-Dmanagement.tracing.sampling.probability=0.05',
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
        18000 = 'ecommerce-gateway-1.0.1-SNAPSHOT.jar'
        18101 = 'identity-service-1.0.1-SNAPSHOT.jar'
        18102 = 'catalog-service-1.0.1-SNAPSHOT.jar'
        18104 = 'trade-service-1.0.1-SNAPSHOT.jar'
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
            Write-Warning "Refused to stop PID $($process.ProcessId); command line no longer matches."
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
    throw "M7 baseline services did not release their ports: $($details -join ', ')"
}

function Show-ServiceLogTails {
    foreach ($name in $script:serviceProcesses.Keys) {
        foreach ($suffix in @('out', 'err')) {
            $path = Join-Path $script:runDirectory "$name.$suffix.log"
            if (Test-Path -LiteralPath $path) {
                Write-Host "--- $path ---"
                Get-Content -LiteralPath $path -Tail 80
            }
        }
    }
}

function Invoke-CatalogMigrationPreflight {
    param([Parameter(Mandatory)][string]$Jar)

    $primaryError = $null
    try {
        Start-BaselineService -Name 'catalogMigration' -Jar $Jar `
            -InstanceId 'm7-catalog-migration-preflight' `
            -GcLog "$($script:gcLogDirectory)/catalog-migration.gc.log" `
            -Environment @{
                APP_ENV = 'm7-scale-baseline'
                MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
            } | Out-Null
        Wait-HttpOk -Uri "http://127.0.0.1:$($ports.catalog)/actuator/health/liveness"
    }
    catch {
        $primaryError = $_
        Show-ServiceLogTails
    }
    finally {
        try {
            Stop-BaselineServices
        }
        catch {
            if ($null -ne $primaryError) {
                Write-Warning "Catalog migration preflight cleanup also failed: $($_.Exception.Message)"
            }
            else {
                throw
            }
        }
        $script:serviceProcesses.Clear()
    }
    if ($null -ne $primaryError) {
        throw $primaryError
    }
}

function Invoke-DatabaseSql {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Sql
    )

    $output = @(
        $Sql | docker exec -i -e "MYSQL_PWD=$Password" plainjournal-mysql `
            mysql "--user=$User" --default-character-set=utf8mb4 `
            --batch --skip-column-names $Database 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed for $Database`: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-DeepOffset {
    param([Parameter(Mandatory)][long]$Rows)

    if ($Rows -lt ($PageSize * 3)) {
        throw "M7 fixture needs at least $($PageSize * 3) rows for a deep-page baseline; found $Rows."
    }
    $candidate = [long][Math]::Floor((($Rows - $PageSize) * 0.8) / $PageSize) * $PageSize
    return [Math]::Max([long]$PageSize, $candidate)
}

function Get-CursorBoundary {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$WhereSql,
        [Parameter(Mandatory)][long]$Offset
    )

    $rows = @(Invoke-DatabaseSql -Database $Database -User $User -Password $Password -Sql @"
SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'),
       id,
       CAST(UNIX_TIMESTAMP(created_at) * 1000 AS UNSIGNED)
FROM $Table
WHERE $WhereSql
ORDER BY created_at DESC, id DESC
LIMIT $($Offset - 1), 1;
"@)
    if ($rows.Count -ne 1) {
        throw "Unable to resolve cursor boundary for $Database.$Table at offset $Offset."
    }
    $parts = $rows[0] -split "`t"
    return [ordered]@{
        createdAt = $parts[0]
        id = [long]$parts[1]
        epochMillis = [long]$parts[2]
    }
}

function ConvertTo-ApiCursor {
    param([Parameter(Mandatory)][object]$Boundary)

    $instantValue = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Boundary.epochMillis)
    $instant = $instantValue.ToString(
        "yyyy-MM-dd'T'HH:mm:ss.fff'Z'",
        [Globalization.CultureInfo]::InvariantCulture)
    $payload = "v1|$instant|$($Boundary.id)"
    $base64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
    return $base64.TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-QuerySequence {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Sql
    )

    return @(
        Invoke-DatabaseSql -Database $Database -User $User -Password $Password -Sql $Sql |
            ForEach-Object { [string]$_ }
    )
}

function Assert-SameSequence {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Expected,
        [Parameter(Mandatory)][string[]]$Actual
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "$Name row count mismatch: expected $($Expected.Count), actual $($Actual.Count)."
    }
    for ($index = 0; $index -lt $Expected.Count; $index++) {
        if ($Expected[$index] -ne $Actual[$index]) {
            throw "$Name sequence mismatch at index $index`: expected $($Expected[$index]), actual $($Actual[$index])."
        }
    }
}

function Get-Percentile {
    param(
        [Parameter(Mandatory)][double[]]$Values,
        [Parameter(Mandatory)][double]$Ratio
    )

    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $Ratio) - 1)
    return [Math]::Round($sorted[$index], 3)
}

function Measure-ServerQuery {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$SelectSql
    )

    $query = $SelectSql.Trim().TrimEnd(';')
    $blocks = [Text.StringBuilder]::new()
    foreach ($iteration in 0..$Repetitions) {
        [void]$blocks.AppendLine('SET @m7_started_at = NOW(6);')
        [void]$blocks.AppendLine(@"
SELECT COUNT(*), COALESCE(SUM(id), 0)
INTO @m7_row_count, @m7_checksum
FROM (
$query
) measured_rows;
"@)
        [void]$blocks.AppendLine(@"
SELECT $iteration,
       TIMESTAMPDIFF(MICROSECOND, @m7_started_at, NOW(6)),
       @m7_row_count,
       @m7_checksum;
"@)
    }
    $rows = @(Invoke-DatabaseSql -Database $Database -User $User -Password $Password `
            -Sql $blocks.ToString())
    $samples = [Collections.Generic.List[object]]::new()
    foreach ($row in $rows) {
        $parts = $row -split "`t"
        if ([int]$parts[0] -eq 0) {
            continue
        }
        $samples.Add([ordered]@{
                iteration = [int]$parts[0]
                elapsedMs = [Math]::Round(([double]$parts[1] / 1000.0), 3)
                rows = [long]$parts[2]
                checksum = [string]$parts[3]
            })
    }
    $latencies = [double[]]@($samples | ForEach-Object { $_.elapsedMs })
    return [ordered]@{
        name = $Name
        repetitions = $Repetitions
        p50Ms = Get-Percentile -Values $latencies -Ratio 0.50
        p95Ms = Get-Percentile -Values $latencies -Ratio 0.95
        p99Ms = Get-Percentile -Values $latencies -Ratio 0.99
        samples = $samples
    }
}

function Save-Explain {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$SelectSql
    )

    $query = $SelectSql.Trim().TrimEnd(';')
    $treePath = Join-Path $script:runDirectory "$Name-explain-analyze.txt"
    $jsonPath = Join-Path $script:runDirectory "$Name-explain.json"
    Invoke-DatabaseSql -Database $Database -User $User -Password $Password `
        -Sql "EXPLAIN ANALYZE $query;" |
        Set-Content -LiteralPath $treePath -Encoding utf8
    Invoke-DatabaseSql -Database $Database -User $User -Password $Password `
        -Sql "EXPLAIN FORMAT=JSON $query;" |
        Set-Content -LiteralPath $jsonPath -Encoding utf8
    return [ordered]@{
        analyze = $treePath
        json = $jsonPath
    }
}

function Invoke-FixtureLogin {
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:$($ports.identity)/api/v1/identity/auth/login" `
        -ContentType 'application/json' `
        -Body (@{
                email = [string]$script:fixture.fixture.denseUserEmail
                password = $fixturePassword
            } | ConvertTo-Json -Compress) `
        -TimeoutSec 20
    if ($response.code -ne 'OK' -or -not $response.data.accessToken) {
        throw 'M7 dense fixture login failed.'
    }
    return [string]$response.data.accessToken
}

function Invoke-LoadRun {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][object[]]$Scenarios
    )

    $directory = Join-Path $script:runDirectory $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configurationPath = Join-Path $directory 'config.json'
    $resultPath = Join-Path $directory 'result.json'
    [ordered]@{
        schemaVersion = 1
        name = $Name
        requests = $RequestsPerRun
        concurrency = $Concurrency
        warmupRequests = [Math]::Min([Math]::Max($Concurrency * 2, 20), $RequestsPerRun)
        timeoutMs = 30000
        maxErrorRate = 0
        scenarios = $Scenarios
    } | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $configurationPath -Encoding utf8

    $console = @(
        & $script:nodeExecutable (Join-Path $script:toolsRoot 'm5-http-load-runner.mjs') `
            $configurationPath $resultPath 2>&1
    )
    $exitCode = $LASTEXITCODE
    $console | Set-Content -LiteralPath (Join-Path $directory 'runner-console.log') -Encoding utf8
    if ($exitCode -ne 0) {
        throw "M7 API load failed for $Name`: $($console -join [Environment]::NewLine)"
    }
    return Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
}

function Save-HostProcesses {
    param([Parameter(Mandatory)][string]$Stage)

    $rows = foreach ($name in @($script:serviceProcesses.Keys)) {
        $port = [int]$ports[$name]
        $listener = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop)
        if ($listener.Count -ne 1) {
            throw "Expected one listener for $name on $port; found $($listener.Count)."
        }
        $process = Get-Process -Id $listener[0].OwningProcess -ErrorAction Stop
        [ordered]@{
            service = $name
            pid = $process.Id
            totalProcessorSeconds = [Math]::Round($process.TotalProcessorTime.TotalSeconds, 3)
            workingSetBytes = [long]$process.WorkingSet64
            privateMemoryBytes = [long]$process.PrivateMemorySize64
            threads = $process.Threads.Count
            handles = $process.HandleCount
        }
    }
    $rows | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $script:runDirectory "$Stage-host-processes.json") -Encoding utf8
}

function Assert-ApiCorrectness {
    param(
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][long]$CatalogDeepPage,
        [Parameter(Mandatory)][long]$TradeDeepPage,
        [Parameter(Mandatory)][string]$CatalogCursor,
        [Parameter(Mandatory)][string]$TradeCursor,
        [Parameter(Mandatory)][string[]]$ExpectedCatalogIds,
        [Parameter(Mandatory)][string[]]$ExpectedOrderNumbers
    )

    $catalog = Invoke-RestMethod `
        -Uri ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products" +
            "?page=$CatalogDeepPage&size=$PageSize&categoryId=$($script:fixture.fixture.categoryId)") `
        -TimeoutSec 30
    $catalogIds = @($catalog.data.items | ForEach-Object { [string]$_.id })
    Assert-SameSequence -Name 'Catalog API deep page' `
        -Expected $ExpectedCatalogIds -Actual $catalogIds
    if ([long]$catalog.data.total -ne [long]$script:fixture.requested.spus) {
        throw "Catalog API total mismatch: $($catalog.data.total)."
    }
    $catalogCursorPage = Invoke-RestMethod `
        -Uri ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products/cursor" +
            "?size=$PageSize&categoryId=$($script:fixture.fixture.categoryId)" +
            "&cursor=$([Uri]::EscapeDataString($CatalogCursor))") `
        -TimeoutSec 30
    $catalogCursorIds = @($catalogCursorPage.data.items | ForEach-Object { [string]$_.id })
    Assert-SameSequence -Name 'Catalog API cursor page' `
        -Expected $ExpectedCatalogIds -Actual $catalogCursorIds

    $headers = @{ Authorization = "Bearer $Token" }
    $trade = Invoke-RestMethod `
        -Uri ("http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page" +
            "?page=$TradeDeepPage&size=$PageSize") `
        -Headers $headers `
        -TimeoutSec 60
    $orderNumbers = @($trade.data.items | ForEach-Object { [string]$_.orderNo })
    Assert-SameSequence -Name 'Trade API deep page' `
        -Expected $ExpectedOrderNumbers -Actual $orderNumbers
    if ([long]$trade.data.total -ne [long]$script:fixture.requested.denseUserOrders) {
        throw "Trade API total mismatch: $($trade.data.total)."
    }
    $tradeCursorPage = Invoke-RestMethod `
        -Uri ("http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/cursor" +
            "?size=$PageSize&cursor=$([Uri]::EscapeDataString($TradeCursor))") `
        -Headers $headers `
        -TimeoutSec 60
    $tradeCursorOrders = @($tradeCursorPage.data.items | ForEach-Object { [string]$_.orderNo })
    Assert-SameSequence -Name 'Trade API cursor page' `
        -Expected $ExpectedOrderNumbers -Actual $tradeCursorOrders
    return [ordered]@{
        catalog = [ordered]@{
            page = $CatalogDeepPage
            size = $PageSize
            total = [long]$catalog.data.total
            firstId = $catalogIds[0]
            lastId = $catalogIds[-1]
            cursorMatched = $true
        }
        trade = [ordered]@{
            page = $TradeDeepPage
            size = $PageSize
            total = [long]$trade.data.total
            firstOrderNo = $orderNumbers[0]
            lastOrderNo = $orderNumbers[-1]
            cursorMatched = $true
        }
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
} elseif (Test-Path -LiteralPath 'D:\Node.js\current\node.exe') {
    'D:\Node.js\current\node.exe'
} else {
    throw 'Node.js was not found on PATH or at D:\Node.js\current\node.exe.'
}

if (-not $FixtureManifestPath) {
    $FixtureManifestPath = Join-Path $script:backendRoot `
        ".run\m7-scale-data-$($Scale.ToLowerInvariant()).json"
}
if ($Scale -eq 'Formal' -and -not $AllowFormal) {
    throw 'Formal is an explicit upper-bound experiment. Re-run with -AllowFormal after Small and Medium pass.'
}
$resolvedFixtureManifest = [IO.Path]::GetFullPath($FixtureManifestPath)
if (-not (Test-Path -LiteralPath $resolvedFixtureManifest)) {
    throw "Missing M7 fixture manifest: $resolvedFixtureManifest"
}
$script:fixture = Get-Content -LiteralPath $resolvedFixtureManifest -Raw | ConvertFrom-Json
$manifestScale = [string]$script:fixture.scale
if ($manifestScale -ne $Scale) {
    throw "Fixture scale $manifestScale does not match requested scale $Scale."
}
if ($script:fixture.action -ne 'Seed') {
    throw "M7 fixture manifest does not describe a Seed action: $resolvedFixtureManifest"
}

if (-not $OutputDirectory) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $script:backendRoot `
        ".run\m7-scale-query-$($Scale.ToLowerInvariant())-$timestamp"
}
$script:runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null
$script:gcLogDirectory = [IO.Path]::GetRelativePath(
    $script:backendRoot, $script:runDirectory).Replace('\', '/')
$gatewayJar = Join-Path $script:backendRoot 'ecommerce-gateway\target\ecommerce-gateway-1.0.1-SNAPSHOT.jar'
$identityJar = Join-Path $script:backendRoot 'services\identity-service\target\identity-service-1.0.1-SNAPSHOT.jar'
$catalogJar = Join-Path $script:backendRoot 'services\catalog-service\target\catalog-service-1.0.1-SNAPSHOT.jar'
$tradeJar = Join-Path $script:backendRoot 'services\trade-service\target\trade-service-1.0.1-SNAPSHOT.jar'

$requiredContainers = @(
    'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker', 'plainjournal-rocketmq-proxy', 'plainjournal-minio'
)
$runningContainers = @(docker ps --format '{{.Names}}')
$missingContainers = @($requiredContainers | Where-Object { $runningContainers -notcontains $_ })
if ($missingContainers.Count -gt 0) {
    throw "Required containers are not running: $($missingContainers -join ', ')"
}
Assert-ResourceBudget
foreach ($port in $ports.Values) {
    Assert-PortAvailable -Port $port
}

& (Join-Path $script:toolsRoot 'prepare-m7-scale-data.ps1') `
    -Action Verify `
    -Scale $Scale `
    -SpuCount ([int]$script:fixture.requested.spus) `
    -SkuPerSpu ([int]($script:fixture.requested.skus / $script:fixture.requested.spus)) `
    -UserCount ([int]$script:fixture.requested.users) `
    -OrderCount ([int]$script:fixture.requested.orders) `
    -DenseUserOrderCount ([int]$script:fixture.requested.denseUserOrders) `
    -ItemsPerOrder ([int]$script:fixture.requested.itemsPerOrder) `
    -DiscountEvery ([int]$script:fixture.requested.discountEvery) `
    -BatchSize ([int]$script:fixture.requested.batchSize) `
    -ManifestPath (Join-Path $script:runDirectory 'fixture-verify.json') | Out-Null

# Flyway runs inside the service. Apply pending Catalog migrations before collecting
# SQL timings and execution plans so a first run cannot archive pre-migration evidence.
Invoke-CatalogMigrationPreflight -Jar $catalogJar

$catalogCount = [long]$script:fixture.requested.spus
$denseOrderCount = [long]$script:fixture.requested.denseUserOrders
$catalogOffset = Get-DeepOffset -Rows $catalogCount
$tradeOffset = Get-DeepOffset -Rows $denseOrderCount
$catalogPage = [long]($catalogOffset / $PageSize) + 1
$tradePage = [long]($tradeOffset / $PageSize) + 1
$categoryId = [long]$script:fixture.fixture.categoryId
$denseUserId = [long]$script:fixture.fixture.denseUserId

$catalogBoundary = Get-CursorBoundary `
    -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
    -Password $env:CATALOG_DB_PASSWORD -Table 'product_spu' `
    -WhereSql "status = 'ACTIVE' AND category_id = $categoryId" -Offset $catalogOffset
$tradeBoundary = Get-CursorBoundary `
    -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
    -Password $env:TRADE_DB_PASSWORD -Table 'trade_order' `
    -WhereSql "user_id = $denseUserId" -Offset $tradeOffset
$catalogApiCursor = ConvertTo-ApiCursor -Boundary $catalogBoundary
$tradeApiCursor = ConvertTo-ApiCursor -Boundary $tradeBoundary

$catalogOffsetSql = @"
SELECT id FROM product_spu
WHERE status = 'ACTIVE' AND category_id = $categoryId
ORDER BY created_at DESC, id DESC
LIMIT $catalogOffset, $PageSize
"@
$catalogKeysetSql = @"
SELECT id FROM product_spu
WHERE status = 'ACTIVE' AND category_id = $categoryId
  AND (created_at < '$($catalogBoundary.createdAt)'
       OR (created_at = '$($catalogBoundary.createdAt)' AND id < $($catalogBoundary.id)))
ORDER BY created_at DESC, id DESC
LIMIT $PageSize
"@
$tradeOffsetSql = @"
SELECT id FROM trade_order
WHERE user_id = $denseUserId
ORDER BY created_at DESC, id DESC
LIMIT $tradeOffset, $PageSize
"@
$tradeKeysetSql = @"
SELECT id FROM trade_order
WHERE user_id = $denseUserId
  AND (created_at < '$($tradeBoundary.createdAt)'
       OR (created_at = '$($tradeBoundary.createdAt)' AND id < $($tradeBoundary.id)))
ORDER BY created_at DESC, id DESC
LIMIT $PageSize
"@
$catalogOffsetIds = Get-QuerySequence `
    -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
    -Password $env:CATALOG_DB_PASSWORD -Sql $catalogOffsetSql
$catalogKeysetIds = Get-QuerySequence `
    -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
    -Password $env:CATALOG_DB_PASSWORD -Sql $catalogKeysetSql
Assert-SameSequence -Name 'Catalog offset/keyset' `
    -Expected $catalogOffsetIds -Actual $catalogKeysetIds
$tradeOffsetIds = Get-QuerySequence `
    -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
    -Password $env:TRADE_DB_PASSWORD -Sql $tradeOffsetSql
$tradeKeysetIds = Get-QuerySequence `
    -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
    -Password $env:TRADE_DB_PASSWORD -Sql $tradeKeysetSql
Assert-SameSequence -Name 'Trade offset/keyset' `
    -Expected $tradeOffsetIds -Actual $tradeKeysetIds

$expectedOrderNumbers = Get-QuerySequence `
    -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
    -Password $env:TRADE_DB_PASSWORD -Sql ($tradeOffsetSql -replace 'SELECT id', 'SELECT order_no')
$sqlMeasurements = [Collections.Generic.List[object]]::new()
$explainFiles = [ordered]@{}
$queries = [ordered]@{
    catalogFirst = @"
SELECT id FROM product_spu
WHERE status = 'ACTIVE' AND category_id = $categoryId
ORDER BY created_at DESC, id DESC
LIMIT $PageSize
"@
    catalogDeepOffset = $catalogOffsetSql
    catalogDeepKeyset = $catalogKeysetSql
    catalogPoint = "SELECT id FROM product_spu WHERE id = $($script:fixture.fixture.lastProductId) LIMIT 1"
    tradeFirst = @"
SELECT id FROM trade_order
WHERE user_id = $denseUserId
ORDER BY created_at DESC, id DESC
LIMIT $PageSize
"@
    tradeDeepOffset = $tradeOffsetSql
    tradeDeepKeyset = $tradeKeysetSql
    tradePoint = "SELECT id FROM trade_order WHERE order_no = '$($script:fixture.fixture.firstOrderNo)' LIMIT 1"
}
foreach ($entry in $queries.GetEnumerator()) {
    $isCatalog = $entry.Key.StartsWith('catalog')
    $database = if ($isCatalog) { $env:CATALOG_DB_NAME } else { $env:TRADE_DB_NAME }
    $user = if ($isCatalog) { $env:CATALOG_DB_USER } else { $env:TRADE_DB_USER }
    $password = if ($isCatalog) { $env:CATALOG_DB_PASSWORD } else { $env:TRADE_DB_PASSWORD }
    $sqlMeasurements.Add((Measure-ServerQuery -Name $entry.Key `
                -Database $database -User $user -Password $password -SelectSql $entry.Value))
    $explainFiles[$entry.Key] = Save-Explain -Name $entry.Key `
        -Database $database -User $user -Password $password -SelectSql $entry.Value
}
Invoke-DatabaseSql -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
    -Password $env:CATALOG_DB_PASSWORD -Sql 'SHOW INDEX FROM product_spu;' |
    Set-Content -LiteralPath (Join-Path $script:runDirectory 'catalog-product-spu-indexes.txt') -Encoding utf8
Invoke-DatabaseSql -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
    -Password $env:TRADE_DB_PASSWORD -Sql 'SHOW INDEX FROM trade_order;' |
    Set-Content -LiteralPath (Join-Path $script:runDirectory 'trade-order-indexes.txt') -Encoding utf8

& (Join-Path $script:toolsRoot 'capture-m5-environment.ps1') `
    -OutputPath (Join-Path $script:runDirectory 'before-environment.json') | Out-Null

$primaryError = $null
try {
    Start-BaselineService -Name 'identity' -Jar $identityJar `
        -InstanceId 'm7-identity-1' -GcLog "$($script:gcLogDirectory)/identity.gc.log" `
        -Environment @{
            APP_ENV = 'm7-scale-baseline'
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.identity)/actuator/health/liveness"

    Start-BaselineService -Name 'catalog' -Jar $catalogJar `
        -InstanceId 'm7-catalog-1' -GcLog "$($script:gcLogDirectory)/catalog.gc.log" `
        -Environment @{
            APP_ENV = 'm7-scale-baseline'
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.catalog)/actuator/health/liveness"

    Start-BaselineService -Name 'trade' -Jar $tradeJar `
        -InstanceId 'm7-trade-1' -GcLog "$($script:gcLogDirectory)/trade.gc.log" `
        -Environment @{
            APP_ENV = 'm7-scale-baseline'
            TRADE_OUTBOX_ENABLED = 'false'
            TRADE_ORDER_RECOVERY_ENABLED = 'false'
            TRADE_RECONCILIATION_ENABLED = 'false'
            TRADE_PAYMENT_CONSUMER_ENABLED = 'false'
            TRADE_FULFILLMENT_CONSUMER_ENABLED = 'false'
            TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED = 'false'
            TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED = 'false'
            TRADE_REFUND_RESULT_CONSUMER_ENABLED = 'false'
            TRADE_FLASH_SALE_CONSUMER_ENABLED = 'false'
            TRADE_FLASH_SALE_RECOVERY_ENABLED = 'false'
            ECOMMERCE_SECURITY_METRICS_TOKEN = $env:METRICS_SCRAPE_TOKEN
            MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE =
                'health,info,metrics,prometheus,consumerfailures,businessprocesses'
            MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED = 'true'
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.trade)/actuator/health/liveness"

    Start-BaselineService -Name 'gateway' -Jar $gatewayJar `
        -InstanceId 'm7-gateway-1' -GcLog "$($script:gcLogDirectory)/gateway.gc.log" `
        -Environment @{
            APP_ENV = 'm7-scale-baseline'
            JAVA_TOOL_OPTIONS = "-Dreactor.netty.ioWorkerCount=$ActiveProcessorCount"
        } | Out-Null
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.gateway)/actuator/health/liveness"
    Wait-HttpOk -Uri ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products" +
        "?page=1&size=1&categoryId=$categoryId")

    $token = Invoke-FixtureLogin
    [Environment]::SetEnvironmentVariable('M7_DENSE_TOKEN', $token, 'Process')
    Save-HostProcesses -Stage 'before'
    $apiCorrectness = Assert-ApiCorrectness -Token $token `
        -CatalogDeepPage $catalogPage -TradeDeepPage $tradePage `
        -CatalogCursor $catalogApiCursor -TradeCursor $tradeApiCursor `
        -ExpectedCatalogIds $catalogOffsetIds -ExpectedOrderNumbers $expectedOrderNumbers

    $catalogLoad = Invoke-LoadRun -Name 'catalog-scale-pages' -Scenarios @(
        [ordered]@{
            name = 'catalog-first-page'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products" +
                "?page=1&size=$PageSize&categoryId=$categoryId")
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'catalog-deep-page'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products" +
                "?page=$catalogPage&size=$PageSize&categoryId=$categoryId")
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'catalog-cursor-page'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products/cursor" +
                "?size=$PageSize&categoryId=$categoryId" +
                "&cursor=$([Uri]::EscapeDataString($catalogApiCursor))")
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'catalog-point'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products/" +
                "$($script:fixture.fixture.lastProductId)")
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        }
    )
    $tradeLoad = Invoke-LoadRun -Name 'trade-scale-pages' -Scenarios @(
        [ordered]@{
            name = 'trade-first-page'
            url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page?page=1&size=$PageSize"
            headers = @{ Authorization = 'Bearer ${ENV:M7_DENSE_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'trade-deep-page'
            url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page?page=$tradePage&size=$PageSize"
            headers = @{ Authorization = 'Bearer ${ENV:M7_DENSE_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'trade-cursor-page'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/cursor" +
                "?size=$PageSize&cursor=$([Uri]::EscapeDataString($tradeApiCursor))")
            headers = @{ Authorization = 'Bearer ${ENV:M7_DENSE_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        },
        [ordered]@{
            name = 'trade-point'
            url = ("http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/" +
                "$($script:fixture.fixture.firstOrderNo)")
            headers = @{ Authorization = 'Bearer ${ENV:M7_DENSE_TOKEN}' }
            expectedStatuses = @(200)
            expectedJsonCode = 'OK'
            weight = 1
        }
    )
    Save-HostProcesses -Stage 'after'
    & (Join-Path $script:toolsRoot 'capture-m5-environment.ps1') `
        -OutputPath (Join-Path $script:runDirectory 'after-environment.json') | Out-Null
    Assert-MySqlStayedStable

    $summary = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        scale = $Scale
        fixtureManifest = $resolvedFixtureManifest
        parameters = [ordered]@{
            requestsPerRun = $RequestsPerRun
            repetitions = $Repetitions
            concurrency = $Concurrency
            pageSize = $PageSize
            heapMiB = $HeapMiB
            activeProcessorCount = $ActiveProcessorCount
            hikariMaximumPoolSize = $HikariMaximumPoolSize
            tomcatMaximumThreads = $TomcatMaximumThreads
            formalExplicitlyAllowed = [bool]$AllowFormal
            catalogRows = $catalogCount
            denseUserOrders = $denseOrderCount
            catalogDeepOffset = $catalogOffset
            tradeDeepOffset = $tradeOffset
        }
        correctness = [ordered]@{
            catalogMigrationsAppliedBeforeSqlEvidence = $true
            catalogOffsetMatchesKeyset = $true
            tradeOffsetMatchesKeyset = $true
            catalogRowsCompared = $catalogOffsetIds.Count
            tradeRowsCompared = $tradeOffsetIds.Count
            api = $apiCorrectness
        }
        cursorBoundaries = [ordered]@{
            catalog = $catalogBoundary
            trade = $tradeBoundary
        }
        sqlMeasurements = $sqlMeasurements
        explainFiles = $explainFiles
        apiLoads = [ordered]@{
            catalog = $catalogLoad.aggregate
            trade = $tradeLoad.aggregate
            catalogScenarios = $catalogLoad.scenarios
            tradeScenarios = $tradeLoad.scenarios
            catalogResult = Join-Path $script:runDirectory 'catalog-scale-pages\result.json'
            tradeResult = Join-Path $script:runDirectory 'trade-scale-pages\result.json'
        }
        evidence = [ordered]@{
            beforeEnvironment = Join-Path $script:runDirectory 'before-environment.json'
            afterEnvironment = Join-Path $script:runDirectory 'after-environment.json'
            beforeHostProcesses = Join-Path $script:runDirectory 'before-host-processes.json'
            afterHostProcesses = Join-Path $script:runDirectory 'after-host-processes.json'
        }
    }
    $summaryPath = Join-Path $script:runDirectory 'summary.json'
    $summary | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
    $summary | ConvertTo-Json -Depth 12
}
catch {
    $primaryError = $_
    Show-ServiceLogTails
}
finally {
    [Environment]::SetEnvironmentVariable('M7_DENSE_TOKEN', $null, 'Process')
    try {
        Stop-BaselineServices
    }
    catch {
        if ($null -ne $primaryError) {
            Write-Warning "M7 cleanup also failed: $($_.Exception.Message)"
        }
        else {
            throw
        }
    }
}
if ($null -ne $primaryError) {
    throw $primaryError
}
