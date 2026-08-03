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
    [ValidateRange(1, 1000)]
    [int]$UserPoolSize = 1000,
    [switch]$SkipPackage,
    [switch]$SkipCorrectnessGates,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$ports = [ordered]@{
    gateway = 18000
    identity = 18101
    catalog = 18102
    inventory = 18103
    trade = 18104
    payment = 18105
    marketing = 18107
}
$fixture = [ordered]@{
    userBase = [long]7130000000000000000
    addressBase = [long]7140000000000000000
    productBase = [long]7110000000000000000
    skuBase = [long]7120000000000000000
    warehouseId = [long]7210000000000000001
    skuPerSpu = 3
    skuCount = 3000
}
$script:serviceProcesses = [ordered]@{}
$script:environmentRestores = [Collections.Generic.List[object]]::new()
$script:tokens = @()
$script:adminToken = $null
$script:runId = "M5W$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"

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
        'IDENTITY_JWT_SECRET', 'TRADE_INTERNAL_SERVICE_TOKEN',
        'PAYMENT_INTERNAL_SERVICE_TOKEN', 'METRICS_SCRAPE_TOKEN',
        'MOCK_PAYMENT_CALLBACK_SECRET',
        'TRADE_DB_NAME', 'TRADE_DB_USER', 'TRADE_DB_PASSWORD',
        'INVENTORY_DB_NAME', 'INVENTORY_DB_USER', 'INVENTORY_DB_PASSWORD',
        'MARKETING_DB_NAME', 'MARKETING_DB_USER', 'MARKETING_DB_PASSWORD',
        'PAYMENT_DB_NAME', 'PAYMENT_DB_USER', 'PAYMENT_DB_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
        })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
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

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-CustomerToken {
    param(
        [Parameter(Mandatory)][long]$UserId,
        [string[]]$Roles = @('CUSTOMER'),
        [int]$LifetimeSeconds = 21600
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + $LifetimeSeconds
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

function Get-HmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Secret
    )

    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        return [Convert]::ToHexString(
            $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
    }
    finally {
        $hmac.Dispose()
    }
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

function Start-CapacityService {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment,
        [hashtable]$SystemProperties = @{}
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing application artifact: $Jar"
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        Set-ProcessEnvironment -Name $entry.Key -Value ([string]$entry.Value)
    }
    Set-ProcessEnvironment -Name 'SERVICE_INSTANCE_ID' -Value "m5-$Name-1"
    Set-ProcessEnvironment -Name 'SERVICE_RELEASE_ID' -Value 'm5-write-capacity-v1'
    try {
        $gcLog = "$($script:gcLogDirectory)/$Name.gc.log"
        $arguments = @(
            "-Xms${HeapMiB}m",
            "-Xmx${HeapMiB}m",
            "-XX:ActiveProcessorCount=$ActiveProcessorCount",
            '-XX:+UseG1GC',
            '-XX:MaxGCPauseMillis=200',
            "-Xlog:gc*:file=$gcLog`:time,uptime,level,tags",
            "-Dspring.datasource.hikari.maximum-pool-size=$HikariMaximumPoolSize",
            '-Dspring.datasource.hikari.minimum-idle=2',
            "-Dserver.tomcat.threads.max=$TomcatMaximumThreads",
            '-Dserver.tomcat.threads.min-spare=10',
            '-Dmanagement.tracing.sampling.probability=0.1'
        )
        foreach ($entry in $SystemProperties.GetEnumerator() | Sort-Object Key) {
            $arguments += "-D$($entry.Key)=$($entry.Value)"
        }
        $arguments += @('-jar', $Jar)
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
    }
    finally {
        Restore-ProcessEnvironment
    }
}

function Stop-CapacityServices {
    foreach ($port in @($ports.Values)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" `
                -ErrorAction SilentlyContinue
            if ($null -eq $process -or $process.CommandLine -notlike '*PlainJournal*') {
                Write-Warning "Refused to stop PID $($listener.OwningProcess) on port $port."
                continue
            }
            Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $listener.OwningProcess -Timeout 10 -ErrorAction SilentlyContinue
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
            Write-Warning "Refused to stop PID $($managed.process.Id); command line no longer matches."
            continue
        }
        Stop-Process -Id $managed.process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $managed.process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }

    $deadline = (Get-Date).AddSeconds(20)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
                Where-Object { $ports.Values -contains $_.LocalPort })
        if ($listeners.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    $details = $listeners | ForEach-Object { "$($_.LocalPort)/pid=$($_.OwningProcess)" }
    throw "M5 write-capacity services did not release ports: $($details -join ', ')"
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

function Invoke-DatabaseQuery {
    param(
        [Parameter(Mandatory)][ValidateSet('trade', 'inventory', 'marketing', 'payment')]
        [string]$Domain,
        [Parameter(Mandatory)][string]$Sql
    )

    $prefix = $Domain.ToUpperInvariant()
    $database = [Environment]::GetEnvironmentVariable("${prefix}_DB_NAME", 'Process')
    $user = [Environment]::GetEnvironmentVariable("${prefix}_DB_USER", 'Process')
    $password = [Environment]::GetEnvironmentVariable("${prefix}_DB_PASSWORD", 'Process')
    $output = @(
        $Sql | docker exec -i -e "MYSQL_PWD=$password" plainjournal-mysql `
            mysql "--user=$user" --batch --skip-column-names $database 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Database query failed for $Domain`: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Convert-PipeRow {
    param(
        [Parameter(Mandatory)][string]$Row,
        [Parameter(Mandatory)][string[]]$Names
    )

    $parts = $Row -split '\|', $Names.Count
    if ($parts.Count -ne $Names.Count) {
        throw "Unexpected database row: $Row"
    }
    $result = [ordered]@{}
    for ($index = 0; $index -lt $Names.Count; $index++) {
        $result[$Names[$index]] = $parts[$index]
    }
    return [pscustomobject]$result
}

function ConvertTo-SqlInList {
    param(
        [Parameter(Mandatory)][string[]]$Values
    )

    if ($Values.Count -eq 0) {
        throw 'Cannot build an empty SQL IN list.'
    }
    foreach ($value in $Values) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw 'Cannot build an SQL IN list containing an empty value.'
        }
    }
    return ($Values | ForEach-Object {
            "'" + $_.Replace("'", "''") + "'"
        }) -join ','
}

function Get-OrderBatchCrossDomainFacts {
    param(
        [Parameter(Mandatory)][string]$KeyPrefix,
        [Parameter(Mandatory)][int]$Expected
    )

    $rows = @(
        Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(order_no, '|', reservation_no, '|', marketing_lock_no)
FROM trade_order
WHERE idempotency_key LIKE '$KeyPrefix-%'
ORDER BY idempotency_key;
"@
    )
    if ($rows.Count -ne $Expected) {
        throw "Order batch $KeyPrefix cross-domain lookup expected $Expected Trade rows, found $($rows.Count)."
    }

    $orderNos = [Collections.Generic.List[string]]::new()
    $reservationNos = [Collections.Generic.List[string]]::new()
    $marketingLockNos = [Collections.Generic.List[string]]::new()
    foreach ($row in $rows) {
        $parts = $row -split '\|', 3
        if ($parts.Count -ne 3 -or
            [string]::IsNullOrWhiteSpace($parts[0]) -or
            [string]::IsNullOrWhiteSpace($parts[1]) -or
            [string]::IsNullOrWhiteSpace($parts[2])) {
            throw "Order batch $KeyPrefix contains a Trade row without stable cross-domain identifiers."
        }
        $orderNos.Add($parts[0])
        $reservationNos.Add($parts[1])
        $marketingLockNos.Add($parts[2])
    }

    if (@($orderNos | Sort-Object -Unique).Count -ne $Expected -or
        @($reservationNos | Sort-Object -Unique).Count -ne $Expected -or
        @($marketingLockNos | Sort-Object -Unique).Count -ne $Expected) {
        throw "Order batch $KeyPrefix contains duplicate cross-domain identifiers."
    }

    $reservationIn = ConvertTo-SqlInList -Values @($reservationNos)
    $marketingLockIn = ConvertTo-SqlInList -Values @($marketingLockNos)
    $marketing = Convert-PipeRow -Names @('locks', 'locked', 'distinctOrders') -Row (
        Invoke-DatabaseQuery -Domain marketing -Sql @"
SELECT CONCAT(
    COUNT(*), '|',
    COUNT(CASE WHEN status = 'LOCKED' THEN 1 END), '|',
    COUNT(DISTINCT order_no)
)
FROM pricing_lock
WHERE lock_no IN ($marketingLockIn);
"@ | Select-Object -Last 1)
    $inventory = Convert-PipeRow -Names @(
        'reservations', 'reserved', 'items', 'quantity', 'distinctOrders') -Row (
        Invoke-DatabaseQuery -Domain inventory -Sql @"
SELECT CONCAT(
    COUNT(DISTINCT r.reservation_no), '|',
    COUNT(DISTINCT CASE WHEN r.status = 'RESERVED' THEN r.reservation_no END), '|',
    COUNT(i.id), '|',
    COALESCE(SUM(i.quantity), 0), '|',
    COUNT(DISTINCT r.order_no)
)
FROM inventory_reservation r
LEFT JOIN inventory_reservation_item i ON i.reservation_id = r.id
WHERE r.reservation_no IN ($reservationIn);
"@ | Select-Object -Last 1)

    if ([int]$marketing.locks -ne $Expected -or
        [int]$marketing.locked -ne $Expected -or
        [int]$marketing.distinctOrders -ne $Expected -or
        [int]$inventory.reservations -ne $Expected -or
        [int]$inventory.reserved -ne $Expected -or
        [int]$inventory.items -ne $Expected -or
        [int]$inventory.quantity -ne $Expected -or
        [int]$inventory.distinctOrders -ne $Expected) {
        throw "Order batch $KeyPrefix cross-domain facts diverged: " +
            (@{
                expected = $Expected
                marketing = $marketing
                inventory = $inventory
            } | ConvertTo-Json -Compress)
    }

    return [pscustomobject]@{
        expected = $Expected
        tradeOrders = $Expected
        marketing = $marketing
        inventory = $inventory
    }
}

function Get-UserFixture {
    param([Parameter(Mandatory)][int]$Index)

    $skuIndex = ((($Index - 1) * $fixture.skuPerSpu) % $fixture.skuCount) + 1
    $productIndex = [int][Math]::Floor(($skuIndex - 1) / $fixture.skuPerSpu) + 1
    return [pscustomobject]@{
        index = $Index
        userId = $fixture.userBase + $Index
        addressId = $fixture.addressBase + $Index
        skuIndex = $skuIndex
        skuId = $fixture.skuBase + $skuIndex
        productId = $fixture.productBase + $productIndex
        price = [decimal]19.90 + [decimal](($skuIndex - 1) % 100)
        token = $script:tokens[$Index - 1]
    }
}

function New-CartVariants {
    param([Parameter(Mandatory)][long]$Quantity)

    return @(1..$UserPoolSize | ForEach-Object {
            $user = Get-UserFixture -Index $_
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/cart/items/$($user.skuId)"
                headers = @{ Authorization = "Bearer $($user.token)" }
                body = [ordered]@{
                    productId = [string]$user.productId
                    quantity = $Quantity
                    selected = $true
                }
            }
        })
}

function New-PreviewVariants {
    return @(1..$UserPoolSize | ForEach-Object {
            $user = Get-UserFixture -Index $_
            $amount = $user.price.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/marketing/pricing-previews"
                headers = @{ Authorization = "Bearer $($user.token)" }
                body = [ordered]@{
                    originalAmount = $amount
                    deliveryRegion = [ordered]@{
                        provinceCode = '330000'
                        cityCode = '330100'
                        districtCode = '330106'
                    }
                    lines = @([ordered]@{
                            lineNo = 1
                            skuId = [string]$user.skuId
                            lineAmount = $amount
                        })
                    benefitNos = @()
                }
            }
        })
}

function New-OrderVariants {
    param([Parameter(Mandatory)][string]$KeyPrefix)

    return @(1..$UserPoolSize | ForEach-Object {
            $user = Get-UserFixture -Index $_
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders"
                headers = @{
                    Authorization = "Bearer $($user.token)"
                    'Idempotency-Key' = "$KeyPrefix-$($_.ToString('0000'))"
                }
                body = [ordered]@{
                    addressId = [string]$user.addressId
                    items = @([ordered]@{
                            productId = [string]$user.productId
                            skuId = [string]$user.skuId
                            quantity = 1
                        })
                    benefitNos = @()
                }
            }
        })
}

function New-CatalogDetailUrls {
    return @(1..$UserPoolSize | ForEach-Object {
            $user = Get-UserFixture -Index $_
            "http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products/$($user.productId)"
        } | Select-Object -Unique)
}

function New-InventoryStockUrls {
    return @(1..$UserPoolSize | ForEach-Object {
            $user = Get-UserFixture -Index $_
            "http://127.0.0.1:$($ports.gateway)/api/v1/inventory/stocks/$($user.skuId)"
        })
}

function New-CartListVariants {
    return @(1..$UserPoolSize | ForEach-Object {
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/cart/items"
                headers = @{ Authorization = "Bearer $($script:tokens[$_ - 1])" }
            }
        })
}

function Get-ScheduledRequestCount {
    param(
        [Parameter(Mandatory)][object[]]$Scenarios,
        [Parameter(Mandatory)][string]$ScenarioName,
        [Parameter(Mandatory)][int]$Requests
    )

    $schedule = [Collections.Generic.List[string]]::new()
    foreach ($scenario in $Scenarios) {
        $weight = if ($scenario.weight) { [int]$scenario.weight } else { 1 }
        foreach ($unused in 1..$weight) {
            $schedule.Add([string]$scenario.name)
        }
    }
    $fullCycles = [Math]::Floor($Requests / $schedule.Count)
    $remainder = $Requests % $schedule.Count
    $count = $fullCycles * @($schedule | Where-Object { $_ -eq $ScenarioName }).Count
    for ($index = 0; $index -lt $remainder; $index++) {
        if ($schedule[$index] -eq $ScenarioName) {
            $count++
        }
    }
    return [int]$count
}

function Save-HostProcessSnapshot {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    $rows = foreach ($service in @($script:serviceProcesses.Keys)) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $ports[$service] `
                -ErrorAction SilentlyContinue)
        if ($listeners.Count -ne 1) {
            throw "Expected one listener for $service; found $($listeners.Count)."
        }
        $processId = [int]$listeners[0].OwningProcess
        $process = Get-Process -Id $processId -ErrorAction Stop
        [ordered]@{
            service = $service
            pid = $processId
            totalProcessorSeconds = [Math]::Round($process.TotalProcessorTime.TotalSeconds, 3)
            workingSetBytes = [long]$process.WorkingSet64
            privateMemoryBytes = [long]$process.PrivateMemorySize64
            threadCount = $process.Threads.Count
            handleCount = $process.HandleCount
        }
    }
    $rows | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $Directory "$Stage-host-processes.json") -Encoding utf8
}

function Save-ServiceMetrics {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    $selectedMetrics = @(
        'process.cpu.usage',
        'system.cpu.usage',
        'jvm.memory.used',
        'jvm.gc.pause',
        'hikaricp.connections.active',
        'hikaricp.connections.idle',
        'hikaricp.connections.pending',
        'hikaricp.connections.timeout',
        'http.server.requests'
    )
    $headers = @{ Authorization = "Bearer $script:adminToken" }
    foreach ($service in @('identity', 'catalog', 'inventory', 'trade', 'payment')) {
        $captured = [ordered]@{}
        foreach ($metric in $selectedMetrics) {
            $response = Invoke-WebRequest `
                -Uri "http://127.0.0.1:$($ports[$service])/actuator/metrics/$metric" `
                -Headers $headers -SkipHttpErrorCheck -TimeoutSec 15
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
            Set-Content -LiteralPath (
                Join-Path $Directory "$Stage-$service-metrics.json") -Encoding utf8
    }
}

function Save-EvidenceSnapshot {
    param(
        [Parameter(Mandatory)][string]$Stage,
        [Parameter(Mandatory)][string]$Directory
    )

    Save-HostProcessSnapshot -Stage $Stage -Directory $Directory
    Save-ServiceMetrics -Stage $Stage -Directory $Directory
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
        [Parameter(Mandatory)][object[]]$Scenarios,
        [string]$RunName,
        [int]$WarmupRequests = 0,
        [switch]$SkipEvidence
    )

    $resolvedRunName = if ([string]::IsNullOrWhiteSpace($RunName)) {
        "$Suite-c$Concurrency-r$Repetition"
    } else {
        $RunName
    }
    if ($resolvedRunName -notmatch '^[a-z0-9][a-z0-9-]*$') {
        throw "Invalid load run name: $resolvedRunName"
    }
    $directory = Join-Path $script:runDirectory $resolvedRunName
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configurationPath = Join-Path $directory 'config.runtime.json'
    $resultPath = Join-Path $directory 'result.json'
    $configuration = [ordered]@{
        schemaVersion = 1
        name = $resolvedRunName
        requests = $Requests
        concurrency = $Concurrency
        warmupRequests = $WarmupRequests
        timeoutMs = $TimeoutMs
        maxErrorRate = 0
        scenarios = $Scenarios
    }
    $configuration | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $configurationPath -Encoding utf8

    try {
        if (-not $SkipEvidence) {
            Save-EvidenceSnapshot -Stage 'before' -Directory $directory
        }
        $runnerOutput = @(
            & $script:nodePath (Join-Path $script:toolsRoot 'm5-http-load-runner.mjs') `
                $configurationPath $resultPath 2>&1
        )
        $runnerExitCode = $LASTEXITCODE
        $runnerOutput |
            Set-Content -LiteralPath (Join-Path $directory 'runner-console.log') -Encoding utf8
        if (-not $SkipEvidence) {
            Save-EvidenceSnapshot -Stage 'after' -Directory $directory
        }
        if ($runnerExitCode -ne 0) {
            throw "Load run failed: $resolvedRunName (exit $runnerExitCode). " +
                ($runnerOutput -join [Environment]::NewLine)
        }
    }
    finally {
        Remove-Item -LiteralPath $configurationPath -Force -ErrorAction SilentlyContinue
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

function Get-DomainTotals {
    $trade = Convert-PipeRow -Names @('orders', 'outbox') -Row (
        Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM trade_order
     WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
       AND idempotency_key LIKE 'M5W-%'),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT order_no FROM trade_order
         WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
           AND idempotency_key LIKE 'M5W-%'
     ))
);
"@ | Select-Object -Last 1)
    $inventory = Convert-PipeRow -Names @('reservations', 'reserved', 'outbox') -Row (
        Invoke-DatabaseQuery -Domain inventory -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM inventory_reservation
     WHERE warehouse_id = $($fixture.warehouseId)),
    '|',
    (SELECT COALESCE(SUM(reserved), 0) FROM inventory_balance
     WHERE warehouse_id = $($fixture.warehouseId)),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT reservation_no FROM inventory_reservation
         WHERE warehouse_id = $($fixture.warehouseId)
     ))
);
"@ | Select-Object -Last 1)
    $marketing = Convert-PipeRow -Names @('locks') -Row (
        Invoke-DatabaseQuery -Domain marketing -Sql @"
SELECT COUNT(*) FROM pricing_lock
WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize);
"@ | Select-Object -Last 1)
    $payment = Convert-PipeRow -Names @('payments', 'callbacks', 'transactions', 'outbox') -Row (
        Invoke-DatabaseQuery -Domain payment -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM payment_order
     WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
       AND idempotency_key LIKE 'M5W-%'),
    '|',
    (SELECT COUNT(*) FROM payment_callback_log
     WHERE payment_no IN (
         SELECT payment_no FROM payment_order
         WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
           AND idempotency_key LIKE 'M5W-%'
     )),
    '|',
    (SELECT COUNT(*) FROM payment_transaction
     WHERE payment_id IN (
         SELECT id FROM payment_order
         WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
           AND idempotency_key LIKE 'M5W-%'
     )),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT payment_no FROM payment_order
         WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize)
           AND idempotency_key LIKE 'M5W-%'
     ))
);
"@ | Select-Object -Last 1)
    return [pscustomobject]@{
        trade = $trade
        inventory = $inventory
        marketing = $marketing
        payment = $payment
    }
}

function Wait-OrderBatchConverged {
    param(
        [Parameter(Mandatory)][string]$KeyPrefix,
        [Parameter(Mandatory)][int]$Expected,
        [int]$TimeoutSeconds = 360
    )

    $immediate = $null
    $final = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $row = Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(
    COUNT(*), '|',
    SUM(status = 'PENDING_PAYMENT'), '|',
    SUM(status = 'PENDING_STOCK'), '|',
    SUM(status = 'CLOSED'), '|',
    COUNT(DISTINCT CONCAT(user_id, ':', idempotency_key))
)
FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%';
"@ | Select-Object -Last 1
        $fact = Convert-PipeRow -Row $row `
            -Names @('total', 'pendingPayment', 'pendingStock', 'closed', 'distinctKeys')
        if ($null -eq $immediate) {
            $immediate = $fact
        }
        $final = $fact
        if ([int]$fact.total -eq $Expected -and
            [int]$fact.pendingPayment -eq $Expected -and
            [int]$fact.pendingStock -eq 0 -and
            [int]$fact.closed -eq 0 -and
            [int]$fact.distinctKeys -eq $Expected) {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    if ([int]$final.total -ne $Expected -or
        [int]$final.pendingPayment -ne $Expected -or
        [int]$final.pendingStock -ne 0 -or
        [int]$final.closed -ne 0 -or
        [int]$final.distinctKeys -ne $Expected) {
        throw "Order batch $KeyPrefix did not converge: $($final | ConvertTo-Json -Compress)."
    }

    $integrity = Convert-PipeRow -Names @(
        'items', 'addresses', 'snapshots', 'history', 'outbox',
        'publishedOutbox', 'amountMismatches') -Row (
        Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM order_item
     WHERE order_id IN (SELECT id FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%')),
    '|',
    (SELECT COUNT(*) FROM order_address_snapshot
     WHERE order_id IN (SELECT id FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%')),
    '|',
    (SELECT COUNT(*) FROM order_price_snapshot
     WHERE order_id IN (SELECT id FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%')),
    '|',
    (SELECT COUNT(*) FROM order_status_history
     WHERE order_id IN (SELECT id FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%')),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT order_no FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%'
     )),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT order_no FROM trade_order WHERE idempotency_key LIKE '$KeyPrefix-%'
     ) AND status = 'PUBLISHED'),
    '|',
    (SELECT COUNT(*)
     FROM trade_order o
     JOIN order_item i ON i.order_id = o.id
     JOIN order_price_snapshot p ON p.order_id = o.id
     WHERE o.idempotency_key LIKE '$KeyPrefix-%'
       AND (o.original_amount <> i.line_amount
         OR o.discount_amount <> i.discount_amount
         OR o.total_amount <> i.payable_amount
         OR p.original_amount <> o.original_amount
         OR p.discount_amount <> o.discount_amount
         OR p.payable_amount <> o.total_amount))
);
"@ | Select-Object -Last 1)
    if ([int]$integrity.items -ne $Expected -or
        [int]$integrity.addresses -ne $Expected -or
        [int]$integrity.snapshots -ne $Expected -or
        [int]$integrity.history -ne ($Expected * 2) -or
        [int]$integrity.outbox -ne ($Expected * 2) -or
        [int]$integrity.publishedOutbox -ne 0 -or
        [int]$integrity.amountMismatches -ne 0) {
        throw "Order batch $KeyPrefix failed integrity checks: " +
            ($integrity | ConvertTo-Json -Compress)
    }
    $crossDomain = Get-OrderBatchCrossDomainFacts -KeyPrefix $KeyPrefix -Expected $Expected
    return [pscustomobject]@{
        immediate = $immediate
        final = $final
        integrity = $integrity
        crossDomain = $crossDomain
    }
}

function New-PaymentSetupVariants {
    param(
        [Parameter(Mandatory)][string]$OrderKeyPrefix,
        [Parameter(Mandatory)][string]$PaymentKeyPrefix
    )

    $rows = Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(user_id, '|', order_no, '|', idempotency_key)
FROM trade_order
WHERE idempotency_key LIKE '$OrderKeyPrefix-%'
ORDER BY idempotency_key;
"@
    return @($rows | ForEach-Object {
            $row = Convert-PipeRow -Row $_ -Names @('userId', 'orderNo', 'orderKey')
            $index = [int]($row.orderKey.Substring($row.orderKey.LastIndexOf('-') + 1))
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/payment/payments"
                headers = @{
                    Authorization = "Bearer $($script:tokens[$index - 1])"
                    'Idempotency-Key' = "$PaymentKeyPrefix-$($index.ToString('0000'))"
                }
                body = [ordered]@{
                    orderNo = $row.orderNo
                    channel = 'MOCK'
                }
            }
        })
}

function New-PaymentCallbackVariants {
    param([Parameter(Mandatory)][string]$PaymentKeyPrefix)

    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $rows = Invoke-DatabaseQuery -Domain payment -Sql @"
SELECT CONCAT(payment_no, '|', amount, '|', idempotency_key)
FROM payment_order
WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
ORDER BY idempotency_key;
"@
    return @($rows | ForEach-Object {
            $row = Convert-PipeRow -Row $_ -Names @('paymentNo', 'amount', 'paymentKey')
            $index = [int]($row.paymentKey.Substring($row.paymentKey.LastIndexOf('-') + 1))
            $eventId = "$($script:runId)-PAYEVT-$($index.ToString('0000'))-$PaymentKeyPrefix"
            $transactionNo = "$($script:runId)-PAYTXN-$($index.ToString('0000'))-$PaymentKeyPrefix"
            $amount = ([decimal]$row.amount).ToString(
                '0.############################', [Globalization.CultureInfo]::InvariantCulture)
            $canonical = "$($row.paymentNo)|$eventId|$transactionNo|SUCCESS|$amount|$timestamp"
            [ordered]@{
                url = "http://127.0.0.1:$($ports.gateway)/api/v1/payment/callbacks/mock"
                body = [ordered]@{
                    paymentNo = $row.paymentNo
                    externalEventId = $eventId
                    externalTransactionNo = $transactionNo
                    status = 'SUCCESS'
                    amount = $amount
                    timestamp = $timestamp
                    signature = Get-HmacSha256Hex -Value $canonical `
                        -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
                }
            }
        })
}

function Assert-PaymentBatch {
    param(
        [Parameter(Mandatory)][string]$PaymentKeyPrefix,
        [Parameter(Mandatory)][int]$Expected
    )

    $fact = Convert-PipeRow -Names @(
        'payments', 'successful', 'callbacks', 'processed',
        'transactions', 'outbox', 'publishedOutbox') -Row (
        Invoke-DatabaseQuery -Domain payment -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'),
    '|',
    (SELECT COUNT(*) FROM payment_order
     WHERE idempotency_key LIKE '$PaymentKeyPrefix-%' AND status = 'SUCCESS'),
    '|',
    (SELECT COUNT(*) FROM payment_callback_log
     WHERE payment_no IN (
         SELECT payment_no FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
     )),
    '|',
    (SELECT COUNT(*) FROM payment_callback_log
     WHERE payment_no IN (
         SELECT payment_no FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
     ) AND processing_status = 'PROCESSED'),
    '|',
    (SELECT COUNT(*) FROM payment_transaction
     WHERE payment_id IN (
         SELECT id FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
     ) AND transaction_type = 'PAYMENT' AND status = 'SUCCESS'),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT payment_no FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
     ) AND event_type = 'PaymentSucceeded')
    ,
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id IN (
         SELECT payment_no FROM payment_order WHERE idempotency_key LIKE '$PaymentKeyPrefix-%'
     ) AND event_type = 'PaymentSucceeded' AND status = 'PUBLISHED')
);
"@ | Select-Object -Last 1)
    if ([int]$fact.payments -ne $Expected -or
        [int]$fact.successful -ne $Expected -or
        [int]$fact.callbacks -ne $Expected -or
        [int]$fact.processed -ne $Expected -or
        [int]$fact.transactions -ne $Expected -or
        [int]$fact.outbox -ne $Expected -or
        [int]$fact.publishedOutbox -ne 0) {
        throw "Payment batch $PaymentKeyPrefix failed invariants: $($fact | ConvertTo-Json -Compress)."
    }
    return $fact
}

function Invoke-SameOrderKeyGate {
    $keyPrefix = "M5W-SAME-$($script:runId)"
    $variant = New-OrderVariants -KeyPrefix $keyPrefix | Select-Object -First 1
    $summary = Invoke-LoadRun -Suite 'same-order-key-gate' -Concurrency 100 -Repetition 1 `
        -Requests 100 -TimeoutMs 30000 -Scenarios @([ordered]@{
                name = 'same-order-key'
                method = 'POST'
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                variants = @($variant)
            }) -SkipEvidence
    $assertion = Wait-OrderBatchConverged -KeyPrefix $keyPrefix -Expected 1
    $order = Convert-PipeRow -Names @('orderNo', 'reservationNo') -Row (
        Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT CONCAT(order_no, '|', reservation_no)
FROM trade_order WHERE idempotency_key = '$keyPrefix-0001';
"@ | Select-Object -Last 1)
    $marketingLocks = [int](Invoke-DatabaseQuery -Domain marketing -Sql @"
SELECT COUNT(*) FROM pricing_lock WHERE order_no = '$($order.orderNo)';
"@ | Select-Object -Last 1)
    $inventoryReservations = [int](Invoke-DatabaseQuery -Domain inventory -Sql @"
SELECT COUNT(*) FROM inventory_reservation
WHERE reservation_no = '$($order.reservationNo)' AND order_no = '$($order.orderNo)';
"@ | Select-Object -Last 1)
    if ($marketingLocks -ne 1 -or $inventoryReservations -ne 1) {
        throw 'Concurrent same-key order created duplicate or missing cross-domain facts.'
    }
    return [pscustomobject]@{
        summary = $summary
        assertion = $assertion
        crossDomain = [ordered]@{
            marketingLocks = $marketingLocks
            inventoryReservations = $inventoryReservations
        }
    }
}

function Invoke-SamePaymentCallbackGate {
    $orderKeyPrefix = "M5W-CB-O-$($script:runId)"
    $orderVariant = New-OrderVariants -KeyPrefix $orderKeyPrefix | Select-Object -First 1
    Invoke-LoadRun -Suite 'same-callback-order-setup' -Concurrency 1 -Repetition 1 `
        -Requests 1 -TimeoutMs 30000 -Scenarios @([ordered]@{
                name = 'order'
                method = 'POST'
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                variants = @($orderVariant)
            }) -SkipEvidence | Out-Null
    Wait-OrderBatchConverged -KeyPrefix $orderKeyPrefix -Expected 1 | Out-Null

    $paymentKeyPrefix = "M5W-CB-P-$($script:runId)"
    $paymentVariants = New-PaymentSetupVariants `
        -OrderKeyPrefix $orderKeyPrefix -PaymentKeyPrefix $paymentKeyPrefix
    Invoke-LoadRun -Suite 'same-callback-payment-setup' -Concurrency 1 -Repetition 1 `
        -Requests 1 -TimeoutMs 15000 -Scenarios @([ordered]@{
                name = 'payment'
                method = 'POST'
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                variants = @($paymentVariants)
            }) -SkipEvidence | Out-Null

    $callbackVariant = New-PaymentCallbackVariants `
        -PaymentKeyPrefix $paymentKeyPrefix | Select-Object -First 1
    $summary = Invoke-LoadRun -Suite 'same-payment-callback-gate' `
        -Concurrency 100 -Repetition 1 -Requests 100 -TimeoutMs 30000 `
        -Scenarios @([ordered]@{
                name = 'same-payment-callback'
                method = 'POST'
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                variants = @($callbackVariant)
            }) -SkipEvidence
    $fact = Assert-PaymentBatch -PaymentKeyPrefix $paymentKeyPrefix -Expected 1
    return [pscustomobject]@{ summary = $summary; fact = $fact }
}

function Invoke-InventoryCompetitionGate {
    $skuId = [long]7990000000000000001
    $movementNo = "M5W-INV-ADJ-$($script:runId)"
    $adminHeaders = @{ Authorization = "Bearer $script:adminToken" }
    $adjustment = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:$($ports.gateway)/api/v1/inventory/admin/stocks/adjustments" `
        -Headers $adminHeaders -ContentType 'application/json' `
        -Body (@{
                movementNo = $movementNo
                warehouseId = [string]$fixture.warehouseId
                skuId = [string]$skuId
                quantityDelta = 100
                reason = 'M5 1000-concurrency correctness gate'
            } | ConvertTo-Json -Compress) -TimeoutSec 30
    if ($adjustment.code -ne 'OK' -or [long]$adjustment.data.available -ne 100) {
        throw 'Unable to prepare the M5 inventory competition stock.'
    }

    $expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    $variants = @(0..999 | ForEach-Object {
            [ordered]@{
                url = "http://127.0.0.1:$($ports.inventory)/api/v1/inventory/internal/reservations"
                headers = @{
                    'X-Internal-Service' = 'trade-service'
                    'X-Internal-Token' = $env:TRADE_INTERNAL_SERVICE_TOKEN
                }
                body = [ordered]@{
                    reservationNo = "$($script:runId)-INV-$($_.ToString('0000'))"
                    orderNo = "$($script:runId)-INV-ORDER-$($_.ToString('0000'))"
                    warehouseId = [string]$fixture.warehouseId
                    expiresAt = $expiresAt
                    items = @([ordered]@{
                            skuId = [string]$skuId
                            quantity = 1
                        })
                }
            }
        })
    $summary = Invoke-LoadRun -Suite 'inventory-1000-correctness-gate' `
        -Concurrency 100 -Repetition 1 -Requests 1000 -TimeoutMs 30000 `
        -Scenarios @([ordered]@{
                name = 'inventory-reserve'
                method = 'POST'
                expectedStatuses = @(200)
                expectedJsonCode = 'OK'
                variants = @($variants)
            }) -SkipEvidence
    $fact = Convert-PipeRow -Names @(
        'reservedReservations', 'rejectedReservations', 'reservedUnits',
        'reserveMovements', 'outboxEvents', 'publishedOutboxEvents') -Row (
        Invoke-DatabaseQuery -Domain inventory -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM inventory_reservation
     WHERE reservation_no LIKE '$($script:runId)-INV-%' AND status = 'RESERVED'),
    '|',
    (SELECT COUNT(*) FROM inventory_reservation
     WHERE reservation_no LIKE '$($script:runId)-INV-%' AND status = 'REJECTED'),
    '|',
    (SELECT reserved FROM inventory_balance
     WHERE warehouse_id = $($fixture.warehouseId) AND sku_id = $skuId),
    '|',
    (SELECT COUNT(*) FROM stock_movement
     WHERE warehouse_id = $($fixture.warehouseId) AND sku_id = $skuId
       AND movement_type = 'RESERVE'),
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id LIKE '$($script:runId)-INV-%')
    ,
    '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id LIKE '$($script:runId)-INV-%' AND status = 'PUBLISHED')
);
"@ | Select-Object -Last 1)
    if ([int]$fact.reservedReservations -ne 100 -or
        [int]$fact.rejectedReservations -ne 900 -or
        [int]$fact.reservedUnits -ne 100 -or
        [int]$fact.reserveMovements -ne 100 -or
        [int]$fact.outboxEvents -ne 1000 -or
        [int]$fact.publishedOutboxEvents -ne 0) {
        throw "Inventory 1000-request gate failed: $($fact | ConvertTo-Json -Compress)."
    }
    return [pscustomobject]@{ summary = $summary; fact = $fact }
}

if ($ConcurrencyLevels.Count -eq 0) {
    throw 'At least one concurrency level is required.'
}
foreach ($level in $ConcurrencyLevels) {
    if ($level -lt 1 -or $level -gt $RequestsPerRun) {
        throw "Invalid concurrency level $level for $RequestsPerRun requests."
    }
}
if ($UserPoolSize -gt 1000) {
    throw 'The current deterministic M5 fixture contains at most 1000 users.'
}
if ($RequestsPerRun -gt $UserPoolSize) {
    throw 'Write-capacity runs require RequestsPerRun <= UserPoolSize for one request per user.'
}

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:toolsRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $script:backendRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile
Assert-RequiredEnvironment
[Environment]::SetEnvironmentVariable('NACOS_USERNAME', 'nacos', 'Process')
[Environment]::SetEnvironmentVariable('NACOS_HOST', '127.0.0.1', 'Process')
[Environment]::SetEnvironmentVariable('SERVICE_IP', '127.0.0.1', 'Process')

$nodeCandidate = Get-Command node -ErrorAction SilentlyContinue
$script:nodePath = if ($nodeCandidate) {
    $nodeCandidate.Source
} elseif (Test-Path -LiteralPath 'D:\Node.js\current\node.exe') {
    'D:\Node.js\current\node.exe'
} else {
    throw 'Node.js was not found on PATH or at D:\Node.js\current\node.exe.'
}

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $script:backendRoot ".run\m5-write-capacity-$($script:runId)"
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
if (-not $SkipPackage) {
    & mvn -q -f (Join-Path $script:backendRoot 'pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Backend package failed with exit code $LASTEXITCODE."
    }
}

$jars = [ordered]@{
    identity = Join-Path $script:backendRoot 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
    catalog = Join-Path $script:backendRoot 'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar'
    inventory = Join-Path $script:backendRoot 'services\inventory-service\target\inventory-service-0.1.0-SNAPSHOT.jar'
    trade = Join-Path $script:backendRoot 'services\trade-service\target\trade-service-0.1.0-SNAPSHOT.jar'
    payment = Join-Path $script:backendRoot 'services\payment-service\target\payment-service-0.1.0-SNAPSHOT.jar'
    marketing = Join-Path $script:backendRoot 'services\marketing-service\target\marketing-service-0.1.0-SNAPSHOT.jar'
    gateway = Join-Path $script:backendRoot 'ecommerce-gateway\target\ecommerce-gateway-0.1.0-SNAPSHOT.jar'
}

$summaries = [Collections.Generic.List[object]]::new()
$assertions = [Collections.Generic.List[object]]::new()
$primaryError = $null
try {
    $script:tokens = @(1..$UserPoolSize | ForEach-Object {
            New-CustomerToken -UserId ($fixture.userBase + $_)
        })
    $script:adminToken = New-CustomerToken -UserId ($fixture.userBase + 1) `
        -Roles @('CUSTOMER', 'ADMIN')

    Start-CapacityService -Name identity -Jar $jars.identity -Environment @{
        APP_ENV = 'm5-write-capacity'
        ECOMMERCE_SECURITY_TOKEN_ACCESS_TTL = '6h'
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.identity)/actuator/health/liveness"

    Start-CapacityService -Name catalog -Jar $jars.catalog -Environment @{
        APP_ENV = 'm5-write-capacity'
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.catalog)/actuator/health/liveness"

    Start-CapacityService -Name inventory -Jar $jars.inventory -Environment @{
        ECOMMERCE_SECURITY_METRICS_TOKEN = $env:METRICS_SCRAPE_TOKEN
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics,prometheus,consumerfailures'
    } -SystemProperties @{
        'ecommerce.inventory.outbox.enabled' = 'false'
        'ecommerce.inventory.order-consumer.enabled' = 'false'
        'ecommerce.inventory.return-consumer.enabled' = 'false'
        'ecommerce.inventory.reconciliation.enabled' = 'false'
        'ecommerce.inventory.reservation.expiry-enabled' = 'false'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.inventory)/actuator/health/liveness"

    Start-CapacityService -Name marketing -Jar $jars.marketing -Environment @{
        APP_ENV = 'm5-write-capacity'
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics'
    } -SystemProperties @{
        'ecommerce.marketing.order-consumer.enabled' = 'false'
        'ecommerce.marketing.flash-sale.redis-enabled' = 'false'
        'ecommerce.marketing.flash-sale-outbox.enabled' = 'false'
        'ecommerce.marketing.flash-sale-result-consumer.enabled' = 'false'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.marketing)/actuator/health/liveness"

    Start-CapacityService -Name trade -Jar $jars.trade -Environment @{
        ECOMMERCE_SECURITY_METRICS_TOKEN = $env:METRICS_SCRAPE_TOKEN
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE =
            'health,info,metrics,prometheus,consumerfailures,businessprocesses'
    } -SystemProperties @{
        'ecommerce.trade.outbox.enabled' = 'false'
        'ecommerce.trade.payment-consumer.enabled' = 'false'
        'ecommerce.trade.fulfillment-consumer.enabled' = 'false'
        'ecommerce.trade.after-sale-fulfillment-consumer.enabled' = 'false'
        'ecommerce.trade.after-sale-inventory-consumer.enabled' = 'false'
        'ecommerce.trade.refund-result-consumer.enabled' = 'false'
        'ecommerce.trade.reconciliation.enabled' = 'false'
        'ecommerce.trade.order.payment-timeout' = '24h'
        'ecommerce.trade.client.synchronous-boundary.query-max-concurrent-calls' = '128'
        'ecommerce.trade.client.synchronous-boundary.command-max-concurrent-calls' = '128'
        'ecommerce.trade.client.marketing-pricing-lock.max-concurrent-calls' = '64'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.trade)/actuator/health/liveness"

    Start-CapacityService -Name payment -Jar $jars.payment -Environment @{
        ECOMMERCE_SECURITY_METRICS_TOKEN = $env:METRICS_SCRAPE_TOKEN
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE =
            'health,info,metrics,prometheus,consumerfailures,businessprocesses'
    } -SystemProperties @{
        'ecommerce.payment.outbox.enabled' = 'false'
        'ecommerce.payment.refund-consumer.enabled' = 'false'
        'ecommerce.payment.refund-dispatch.enabled' = 'false'
        'ecommerce.payment.reconciliation.enabled' = 'false'
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.payment)/actuator/health/liveness"

    Start-CapacityService -Name gateway -Jar $jars.gateway -Environment @{
        APP_ENV = 'm5-write-capacity'
        JAVA_TOOL_OPTIONS = "-Dreactor.netty.ioWorkerCount=$ActiveProcessorCount"
    }
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.gateway)/actuator/health/liveness"
    Wait-HttpOk -Uri "http://127.0.0.1:$($ports.gateway)/api/v1/catalog/products?page=1&size=1"

    $cartVariantsEven = New-CartVariants -Quantity 2
    $cartVariantsOdd = New-CartVariants -Quantity 1
    $previewVariants = New-PreviewVariants
    $catalogUrls = New-CatalogDetailUrls
    $inventoryUrls = New-InventoryStockUrls
    $cartListVariants = New-CartListVariants

    foreach ($concurrency in $ConcurrencyLevels) {
        foreach ($repetition in 1..$Repetitions) {
            $runTag = "C$($concurrency.ToString('000'))R$repetition-$($script:runId)"
            $cartQuantity = if (($concurrency + $repetition) % 2 -eq 0) { 2 } else { 1 }
            $cartVariants = if ($cartQuantity -eq 2) { $cartVariantsEven } else { $cartVariantsOdd }
            $cartSummary = Invoke-LoadRun -Suite 'cart-update' -Concurrency $concurrency `
                -Repetition $repetition -Requests $RequestsPerRun -TimeoutMs 15000 `
                -Scenarios @([ordered]@{
                        name = 'cart-update'
                        method = 'PUT'
                        expectedStatuses = @(200)
                        expectedJsonCode = 'OK'
                        variants = @($cartVariants)
                    })
            $summaries.Add($cartSummary)
            $cartCount = [int](Invoke-DatabaseQuery -Domain trade -Sql @"
SELECT COUNT(*) FROM cart_item
WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $RequestsPerRun)
  AND sku_id = $($fixture.skuBase + 1) + ((user_id - $($fixture.userBase + 1)) * $($fixture.skuPerSpu))
  AND quantity = $cartQuantity AND selected = TRUE;
"@ | Select-Object -Last 1)
            if ($cartCount -ne $RequestsPerRun) {
                throw "Cart update assertion failed: expected $RequestsPerRun, actual $cartCount."
            }

            $pricingBefore = [int](Invoke-DatabaseQuery -Domain marketing -Sql @"
SELECT COUNT(*) FROM pricing_lock
WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize);
"@ | Select-Object -Last 1)
            $previewSummary = Invoke-LoadRun -Suite 'checkout-pricing-preview' `
                -Concurrency $concurrency -Repetition $repetition -Requests $RequestsPerRun `
                -TimeoutMs 15000 -WarmupRequests ([Math]::Min(20, $RequestsPerRun)) `
                -Scenarios @([ordered]@{
                        name = 'pricing-preview'
                        method = 'POST'
                        expectedStatuses = @(200)
                        expectedJsonCode = 'OK'
                        variants = @($previewVariants)
                    })
            $summaries.Add($previewSummary)
            $pricingAfter = [int](Invoke-DatabaseQuery -Domain marketing -Sql @"
SELECT COUNT(*) FROM pricing_lock
WHERE user_id BETWEEN $($fixture.userBase + 1) AND $($fixture.userBase + $UserPoolSize);
"@ | Select-Object -Last 1)
            if ($pricingAfter -ne $pricingBefore) {
                throw 'Read-only checkout pricing preview persisted a pricing lock.'
            }

            $orderKeyPrefix = "M5W-O-$runTag"
            $orderSummary = Invoke-LoadRun -Suite 'ordinary-order' -Concurrency $concurrency `
                -Repetition $repetition -Requests $RequestsPerRun -TimeoutMs 30000 `
                -Scenarios @([ordered]@{
                        name = 'ordinary-order'
                        method = 'POST'
                        expectedStatuses = @(200)
                        expectedJsonCode = 'OK'
                        variants = (New-OrderVariants -KeyPrefix $orderKeyPrefix)
                    })
            $summaries.Add($orderSummary)
            $orderAssertion = Wait-OrderBatchConverged -KeyPrefix $orderKeyPrefix `
                -Expected $RequestsPerRun
            $assertions.Add([pscustomobject]@{
                    suite = 'ordinary-order'
                    runTag = $runTag
                    expected = $RequestsPerRun
                    result = $orderAssertion
                })

            $paymentKeyPrefix = "M5W-P-$runTag"
            $paymentSetupVariants = New-PaymentSetupVariants `
                -OrderKeyPrefix $orderKeyPrefix -PaymentKeyPrefix $paymentKeyPrefix
            if ($paymentSetupVariants.Count -ne $RequestsPerRun) {
                throw "Payment setup expected $RequestsPerRun orders; found $($paymentSetupVariants.Count)."
            }
            $paymentSetupSummary = Invoke-LoadRun -Suite 'payment-create-setup' `
                -RunName "payment-create-setup-parent-c$concurrency-worker-c$([Math]::Min(8, $RequestsPerRun))-r$repetition" `
                -Concurrency ([Math]::Min(8, $RequestsPerRun)) -Repetition $repetition `
                -Requests $RequestsPerRun -TimeoutMs 15000 `
                -Scenarios @([ordered]@{
                        name = 'payment-create'
                        method = 'POST'
                        expectedStatuses = @(200)
                        expectedJsonCode = 'OK'
                        variants = @($paymentSetupVariants)
                    }) -SkipEvidence
            $summaries.Add($paymentSetupSummary)
            $processingPayments = [int](Invoke-DatabaseQuery -Domain payment -Sql @"
SELECT COUNT(*) FROM payment_order
WHERE idempotency_key LIKE '$paymentKeyPrefix-%' AND status = 'PROCESSING';
"@ | Select-Object -Last 1)
            if ($processingPayments -ne $RequestsPerRun) {
                throw "Payment setup did not create $RequestsPerRun processing payments."
            }

            $callbackSummary = Invoke-LoadRun -Suite 'payment-callback' `
                -Concurrency $concurrency -Repetition $repetition -Requests $RequestsPerRun `
                -TimeoutMs 30000 -Scenarios @([ordered]@{
                        name = 'payment-callback'
                        method = 'POST'
                        expectedStatuses = @(200)
                        expectedJsonCode = 'OK'
                        variants = (New-PaymentCallbackVariants -PaymentKeyPrefix $paymentKeyPrefix)
                    })
            $summaries.Add($callbackSummary)
            $paymentAssertion = Assert-PaymentBatch `
                -PaymentKeyPrefix $paymentKeyPrefix -Expected $RequestsPerRun
            $assertions.Add([pscustomobject]@{
                    suite = 'payment-callback'
                    runTag = $runTag
                    expected = $RequestsPerRun
                    result = $paymentAssertion
                })

            $mixedOrderKeyPrefix = "M5W-M-$runTag"
            $mixedScenarios = @(
                [ordered]@{
                    name = 'catalog-detail'
                    urls = $catalogUrls
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    weight = 3
                },
                [ordered]@{
                    name = 'inventory-stock'
                    urls = $inventoryUrls
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    weight = 2
                },
                [ordered]@{
                    name = 'cart-list'
                    variants = @($cartListVariants)
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    weight = 1
                },
                [ordered]@{
                    name = 'pricing-preview'
                    method = 'POST'
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    variants = @($previewVariants)
                    weight = 1
                },
                [ordered]@{
                    name = 'cart-update'
                    method = 'PUT'
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    variants = @($cartVariants)
                    weight = 1
                },
                [ordered]@{
                    name = 'order-list'
                    url = "http://127.0.0.1:$($ports.gateway)/api/v1/trade/orders/page?page=1&size=20"
                    headers = @{ Authorization = "Bearer $($script:tokens[0])" }
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    weight = 1
                },
                [ordered]@{
                    name = 'ordinary-order'
                    method = 'POST'
                    expectedStatuses = @(200)
                    expectedJsonCode = 'OK'
                    variants = (New-OrderVariants -KeyPrefix $mixedOrderKeyPrefix)
                    weight = 1
                }
            )
            $mixedOrderCount = Get-ScheduledRequestCount -Scenarios $mixedScenarios `
                -ScenarioName 'ordinary-order' -Requests $RequestsPerRun
            $mixedSummary = Invoke-LoadRun -Suite 'mixed-read-write' `
                -Concurrency $concurrency -Repetition $repetition -Requests $RequestsPerRun `
                -TimeoutMs 30000 -Scenarios $mixedScenarios
            $summaries.Add($mixedSummary)
            $mixedAssertion = Wait-OrderBatchConverged -KeyPrefix $mixedOrderKeyPrefix `
                -Expected $mixedOrderCount
            $assertions.Add([pscustomobject]@{
                    suite = 'mixed-read-write'
                    runTag = $runTag
                    expectedOrders = $mixedOrderCount
                    result = $mixedAssertion
                })
        }
    }

    $correctness = $null
    if (-not $SkipCorrectnessGates) {
        $correctness = [ordered]@{
            sameOrderKey = Invoke-SameOrderKeyGate
            samePaymentCallback = Invoke-SamePaymentCallbackGate
            inventoryCompetition = Invoke-InventoryCompetitionGate
        }
    }

    $summary = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $script:runId
        parameters = [ordered]@{
            requestsPerRun = $RequestsPerRun
            repetitions = $Repetitions
            concurrencyLevels = $ConcurrencyLevels
            userPoolSize = $UserPoolSize
            heapMiB = $HeapMiB
            activeProcessorCount = $ActiveProcessorCount
            hikariMaximumPoolSize = $HikariMaximumPoolSize
            tomcatMaximumThreads = $TomcatMaximumThreads
            outboxPublishersEnabled = $false
            messageConsumersEnabled = $false
            tradeSynchronousBoundaryCapacity = [ordered]@{
                queryMaxConcurrentCalls = 128
                commandMaxConcurrentCalls = 128
                marketingPricingLockMaxConcurrentCalls = 64
            }
        }
        runs = $summaries
        assertions = $assertions
        correctness = $correctness
        finalTotals = Get-DomainTotals
    }
    $summaryPath = Join-Path $script:runDirectory 'summary.json'
    $summary | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
    $summary | ConvertTo-Json -Depth 20
}
catch {
    $primaryError = $_
    Show-ServiceLogTails
}
finally {
    try {
        Stop-CapacityServices
    }
    catch {
        if ($null -ne $primaryError) {
            Write-Warning "M5 write-capacity cleanup also failed: $($_.Exception.Message)"
        }
        else {
            throw
        }
    }
    $script:tokens = @()
    $script:adminToken = $null
}

if ($null -ne $primaryError) {
    throw $primaryError
}
