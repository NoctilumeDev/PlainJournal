#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [ValidateRange(10, 10000)][int]$IdsPerInstance = 1000,
    [ValidateRange(30, 300)][int]$StartupTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeEnvFile = Join-Path $composeDirectory '.env'
$tradeJar = Join-Path $backendRoot 'services\trade-service\target\trade-service-1.0.2-SNAPSHOT.jar'
$runDirectory = Join-Path $backendRoot '.run\m7-distributed-id'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$namespace = 'm7-distributed-id'
$leaseDurationSeconds = 30
$renewalIntervalSeconds = 5
$instanceDefinitions = @(
    [pscustomobject]@{ WorkerId = 0; InstanceId = 'm7-id-0'; Port = 18204 },
    [pscustomobject]@{ WorkerId = 1; InstanceId = 'm7-id-1'; Port = 18214 },
    [pscustomobject]@{ WorkerId = 2; InstanceId = 'm7-id-2'; Port = 18224 }
)
$processes = [System.Collections.Generic.List[object]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$evidencePath = Join-Path $runDirectory 'verification.json'
$startedAtUtc = (Get-Date).ToUniversalTime()
$settings = @{}
$cleanupState = [ordered]@{
    ProcessesStopped = $false
    StopTargets = @()
    PortsReleased = $false
    ActiveLeasesAfterStop = $null
    ExpiredRowsRemoved = $null
    RemainingRowsAfterCleanup = $null
}
$successfulEvidence = $null
$executionError = $null
$cleanupError = $null

function Read-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $values[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Invoke-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = docker exec `
        -e "MYSQL_PWD=$($script:settings['TRADE_DB_PASSWORD'])" `
        plainjournal-mysql mysql `
        --skip-column-names --batch `
        "--user=$($script:settings['TRADE_DB_USER'])" `
        "--database=$($script:settings['TRADE_DB_NAME'])" `
        "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade MySQL command failed.'
    }
    return @($output)
}

function Get-TradeMySqlScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-TradeMySql -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar result, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function Test-LeaseTableExists {
    return [int](Get-TradeMySqlScalar -Sql @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'distributed_id_worker_lease';
"@) -eq 1
}

function Wait-HttpHealth {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 90
    )

    $uri = "http://127.0.0.1:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'not queried'
    do {
        try {
            $response = Invoke-WebRequest -Uri $uri -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $uri. Last error: $lastError"
}

function Assert-PortFree {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function New-TradeEnvironment {
    param(
        [Parameter(Mandatory)][int]$WorkerId,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][int]$Port
    )

    return @{
        APP_ENV = 'm7-id-verification'
        SPRING_PROFILES_ACTIVE = 'm7-id-verification'
        SERVER_PORT = "$Port"
        TRADE_SERVICE_PORT = "$Port"
        SERVER_SHUTDOWN = 'graceful'
        SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE = '20s'
        SPRING_DATASOURCE_URL =
            "jdbc:mysql://127.0.0.1:$($script:settings['MYSQL_PORT'])/$($script:settings['TRADE_DB_NAME'])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = $script:settings['TRADE_DB_USER']
        SPRING_DATASOURCE_PASSWORD = $script:settings['TRADE_DB_PASSWORD']
        SPRING_CONFIG_IMPORT = 'optional:nacos:'
        SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = 'false'
        SPRING_CLOUD_NACOS_CONFIG_ENABLED = 'false'
        SPRING_CLOUD_DISCOVERY_ENABLED = 'false'
        NACOS_HOST = '127.0.0.1'
        NACOS_CLIENT_PORT = $script:settings['NACOS_CLIENT_PORT']
        NACOS_USERNAME = 'nacos'
        NACOS_ADMIN_PASSWORD = $script:settings['NACOS_ADMIN_PASSWORD']
        IDENTITY_JWT_SECRET = $script:settings['IDENTITY_JWT_SECRET']
        TRADE_INTERNAL_SERVICE_TOKEN = $script:settings['TRADE_INTERNAL_SERVICE_TOKEN']
        PAYMENT_INTERNAL_SERVICE_TOKEN = $script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN']
        METRICS_SCRAPE_TOKEN = $script:settings['METRICS_SCRAPE_TOKEN']
        SERVICE_IP = '127.0.0.1'
        SERVICE_INSTANCE_ID = $InstanceId
        ECOMMERCE_TRADE_CLIENT_SERVICE_DISCOVERY_ENABLED = 'false'
        ECOMMERCE_TRADE_OUTBOX_ENABLED = 'false'
        ECOMMERCE_TRADE_PAYMENT_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_FULFILLMENT_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_REFUND_RESULT_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_FLASH_SALE_CONSUMER_ENABLED = 'false'
        ECOMMERCE_TRADE_RECONCILIATION_ENABLED = 'false'
        ECOMMERCE_TRADE_ORDER_RECOVERY_ENABLED = 'false'
        ECOMMERCE_TRADE_DISTRIBUTED_ID_ENABLED = 'true'
        ECOMMERCE_TRADE_DISTRIBUTED_ID_WORKER_ID = "$WorkerId"
        ECOMMERCE_TRADE_DISTRIBUTED_ID_NAMESPACE = $script:namespace
        ECOMMERCE_TRADE_DISTRIBUTED_ID_LEASE_DURATION = "${script:leaseDurationSeconds}s"
        ECOMMERCE_TRADE_DISTRIBUTED_ID_RENEWAL_INTERVAL = "${script:renewalIntervalSeconds}s"
        ECOMMERCE_TRADE_DISTRIBUTED_ID_INSTANCE_ID = $InstanceId
        MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED = 'false'
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info'
        LOGGING_LEVEL_ROOT = 'INFO'
    }
}

function Start-TradeInstance {
    param(
        [Parameter(Mandatory)][int]$WorkerId,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$LogPrefix
    )

    Assert-PortFree -Port $Port
    $logPath = Join-Path $runDirectory "$LogPrefix.log"
    if (Test-Path -LiteralPath $logPath) {
        Remove-Item -LiteralPath $logPath -Force
    }
    $environment = New-TradeEnvironment -WorkerId $WorkerId -InstanceId $InstanceId -Port $Port
    $environment['LOGGING_FILE_NAME'] = $logPath
    $process = Start-Process `
        -FilePath ((Get-Command java.exe).Source) `
        -ArgumentList @('-jar', $tradeJar) `
        -WorkingDirectory $backendRoot `
        -Environment $environment `
        -WindowStyle Hidden `
        -PassThru
    $record = [pscustomobject]@{
        WorkerId = $WorkerId
        InstanceId = $InstanceId
        Port = $Port
        Process = $process
        Log = $logPath
    }
    $processes.Add($record)
    return $record
}

function Stop-TradeProcesses {
    $targetIds = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($record in @($processes)) {
        [void]$targetIds.Add($record.Process.Id)
        $listeners = @(Get-NetTCPConnection `
                -State Listen `
                -LocalPort $record.Port `
                -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            [void]$targetIds.Add([int]$listener.OwningProcess)
        }
    }
    $stopped = [System.Collections.Generic.List[int]]::new()
    foreach ($targetId in $targetIds) {
        $targetProcess = Get-Process -Id $targetId -ErrorAction SilentlyContinue
        if ($null -eq $targetProcess) {
            continue
        }
        if ($targetProcess.ProcessName -ne 'java') {
            throw "Refusing to stop non-Java process $targetId ($($targetProcess.ProcessName))."
        }
        Stop-Process -Id $targetId -Force
        $stopped.Add($targetId)
    }
    $cleanupState.StopTargets = @($stopped)

    $deadline = (Get-Date).AddSeconds(10)
    do {
        $remaining = @($processes | Where-Object {
                Get-NetTCPConnection `
                    -State Listen `
                    -LocalPort $_.Port `
                    -ErrorAction SilentlyContinue
            })
        if ($remaining.Count -eq 0) {
            $cleanupState.ProcessesStopped = $true
            $cleanupState.PortsReleased = $true
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    throw "Trade verification ports are still listening: $($remaining.Port -join ',')"
}

function Wait-ForLeaseExpiry {
    $deadline = (Get-Date).AddSeconds($leaseDurationSeconds + 10)
    do {
        $active = [int](Get-TradeMySqlScalar -Sql @"
SELECT COUNT(*)
FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND lease_until > CURRENT_TIMESTAMP(3);
"@)
        if ($active -eq 0) {
            $cleanupState.ActiveLeasesAfterStop = 0
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    $cleanupState.ActiveLeasesAfterStop = $active
    throw "Distributed ID leases did not expire after stopping the JVMs: active=$active"
}

function Remove-ExpiredLeases {
    $cleanupState.ExpiredRowsRemoved = [int](Invoke-TradeMySql -Sql @"
DELETE FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND lease_until <= CURRENT_TIMESTAMP(3);
SELECT ROW_COUNT();
"@ | Select-Object -Last 1)
    $cleanupState.RemainingRowsAfterCleanup = [int](Get-TradeMySqlScalar -Sql @"
SELECT COUNT(*)
FROM distributed_id_worker_lease
WHERE namespace = '$namespace';
"@)
}

function Assert-StrictlyIncreasing {
    param([Parameter(Mandatory)][long[]]$Ids)

    for ($index = 1; $index -lt $Ids.Count; $index++) {
        if ($Ids[$index] -le $Ids[$index - 1]) {
            throw "IDs are not strictly increasing at index $index."
        }
    }
}

function Get-WorkerBits {
    param([Parameter(Mandatory)][long]$Id)

    return (($Id -shr 12) -band 1023)
}

function Get-InstanceIds {
    param([Parameter(Mandatory)][object]$Record)

    $response = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$($Record.Port)/api/v1/trade/status/distributed-id?count=$IdsPerInstance" `
        -TimeoutSec 30
    if ($null -eq $response.data) {
        throw "Distributed ID endpoint returned no data for worker $($Record.WorkerId)."
    }
    $ids = @($response.data.ids | ForEach-Object { [long]$_ })
    if ($ids.Count -ne $IdsPerInstance) {
        throw "Worker $($Record.WorkerId) returned $($ids.Count) IDs, expected $IdsPerInstance."
    }
    if ($response.data.workerId -ne $Record.WorkerId) {
        throw "Worker $($Record.WorkerId) reported workerId=$($response.data.workerId)."
    }
    Assert-StrictlyIncreasing -Ids $ids
    foreach ($id in $ids) {
        if ((Get-WorkerBits -Id $id) -ne $Record.WorkerId) {
            throw "ID $id from worker $($Record.WorkerId) has the wrong worker bits."
        }
    }
    return $ids
}

if (-not (Test-Path -LiteralPath $composeEnvFile)) {
    throw "Missing Docker environment file: $composeEnvFile"
}
if ($SkipBuild -and -not (Test-Path -LiteralPath $tradeJar)) {
    throw "Missing Trade jar: $tradeJar. Build it with Maven first or omit -SkipBuild only after packaging."
}
if (-not (Test-Path -LiteralPath $runDirectory)) {
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
}

$settings = Read-DotEnv -Path $composeEnvFile
foreach ($required in @(
        'MYSQL_PORT', 'TRADE_DB_NAME', 'TRADE_DB_USER', 'TRADE_DB_PASSWORD',
        'NACOS_CLIENT_PORT', 'NACOS_ADMIN_PASSWORD', 'IDENTITY_JWT_SECRET',
        'TRADE_INTERNAL_SERVICE_TOKEN', 'PAYMENT_INTERNAL_SERVICE_TOKEN',
        'METRICS_SCRAPE_TOKEN')) {
    if (-not $settings.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($settings[$required])) {
        throw "Missing required value in deploy/docker/.env: $required"
    }
}

if (-not $SkipNetworkPreflight) {
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw 'Local development network preflight failed.'
    }
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is not ready.'
}

if (-not $SkipBuild) {
    Push-Location $backendRoot
    try {
        & mvn -pl services/trade-service -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Trade Maven package failed.'
        }
    }
    finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath $tradeJar)) {
    throw "Trade package completed without creating the expected jar: $tradeJar"
}

try {
    if (Test-LeaseTableExists) {
        $staleRows = [int](Get-TradeMySqlScalar -Sql @"
SELECT COUNT(*)
FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND lease_until > CURRENT_TIMESTAMP(3);
"@)
        if ($staleRows -ne 0) {
            throw "Namespace $namespace already has $staleRows active lease rows."
        }
        [void](Invoke-TradeMySql -Sql @"
DELETE FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND lease_until <= CURRENT_TIMESTAMP(3);
"@)
    }

    foreach ($definition in $instanceDefinitions) {
        $record = Start-TradeInstance `
            -WorkerId $definition.WorkerId `
            -InstanceId $definition.InstanceId `
            -Port $definition.Port `
            -LogPrefix $definition.InstanceId
        Wait-HttpHealth -Port $record.Port -TimeoutSeconds $StartupTimeoutSeconds
        $ids = @(Get-InstanceIds -Record $record)
        $results.Add([pscustomobject]@{
            WorkerId = $record.WorkerId
            InstanceId = $record.InstanceId
            Port = $record.Port
            Count = $ids.Count
            FirstId = $ids[0]
            LastId = $ids[-1]
            StrictlyIncreasing = $true
            WorkerBitsValid = $true
            Ids = $ids
        })
    }

    $allIds = @($results | ForEach-Object { $_.Ids })
    $uniqueIds = @($allIds | Sort-Object -Unique)
    if ($uniqueIds.Count -ne $allIds.Count) {
        throw "Distributed ID collision detected: total=$($allIds.Count), unique=$($uniqueIds.Count)."
    }

    $workerZeroLeaseBefore = Get-TradeMySqlScalar -Sql @"
SELECT CONCAT(lease_owner, '|', lease_version)
FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND worker_id = 0;
"@
    $duplicate = Start-TradeInstance `
        -WorkerId 0 `
        -InstanceId 'm7-id-duplicate-worker-0' `
        -Port 18234 `
        -LogPrefix 'm7-id-duplicate-worker-0'
    $duplicateDeadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        $duplicate.Process.Refresh()
        if ($duplicate.Process.HasExited) {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $duplicateDeadline)
    $duplicate.Process.Refresh()
    if (-not $duplicate.Process.HasExited) {
        throw 'Duplicate worker JVM unexpectedly remained running.'
    }
    $workerZeroLeaseAfter = Get-TradeMySqlScalar -Sql @"
SELECT CONCAT(lease_owner, '|', lease_version)
FROM distributed_id_worker_lease
WHERE namespace = '$namespace'
  AND worker_id = 0;
"@
    $workerZeroOwnerBefore = ($workerZeroLeaseBefore -split '\|', 2)[0]
    $workerZeroOwnerAfter = ($workerZeroLeaseAfter -split '\|', 2)[0]
    $duplicatePortListening = @(Get-NetTCPConnection `
            -State Listen `
            -LocalPort 18234 `
            -ErrorAction SilentlyContinue).Count -gt 0
    if ($duplicate.Process.ExitCode -eq 0 -or
        $workerZeroOwnerAfter -ne $workerZeroOwnerBefore -or
        $duplicatePortListening) {
        throw "Duplicate worker did not fail with the expected lease conflict. exit=$($duplicate.Process.ExitCode)"
    }

    $results.Add([pscustomobject]@{
        Scenario = 'duplicate-worker-rejected'
        WorkerId = 0
        Port = 18234
        ProcessExitCode = $duplicate.Process.ExitCode
        LeaseConflictObserved = $true
        WorkerZeroOwnerUnchanged = $workerZeroOwnerAfter -eq $workerZeroOwnerBefore
        DuplicatePortListening = $duplicatePortListening
    })

    $successfulEvidence = [ordered]@{
        StartedAtUtc = $startedAtUtc.ToString('O')
        VerifiedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
        GitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
        Namespace = $namespace
        IdsPerInstance = $IdsPerInstance
        InstanceCount = 3
        TotalIds = $allIds.Count
        UniqueIds = $uniqueIds.Count
        CollisionCount = $allIds.Count - $uniqueIds.Count
        DuplicateWorkerRejected = $true
        LogsUsedAsProof = $false
        ElapsedSeconds = [math]::Round(
            ((Get-Date).ToUniversalTime() - $startedAtUtc).TotalSeconds,
            3)
        Results = $results
    }
    Write-Host "Distributed ID verification passed: $($allIds.Count) unique IDs across 3 JVMs."
}
catch {
    $executionError = $_
}
finally {
    Stop-TradeProcesses
    try {
        if ($processes.Count -gt 0 -and (Test-LeaseTableExists)) {
            Wait-ForLeaseExpiry
            Remove-ExpiredLeases
        }
    }
    catch {
        $cleanupError = $_
    }
    $finalEvidence = if ($null -ne $successfulEvidence) {
        $successfulEvidence
    } else {
        [ordered]@{
            StartedAtUtc = $startedAtUtc.ToString('O')
            VerifiedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
            GitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
            Namespace = $namespace
            Failed = $true
            Error = if ($null -ne $executionError) {
                $executionError.Exception.Message
            } else {
                'Verification did not reach the success state.'
            }
            Results = $results
        }
    }
    if ($null -ne $cleanupError) {
        $finalEvidence['CleanupError'] = $cleanupError.Exception.Message
    }
    $finalEvidence['Cleanup'] = $cleanupState
    $finalEvidence | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8
    Write-Host "Evidence: $evidencePath"
}

if ($null -ne $executionError) {
    throw $executionError
}
if ($null -ne $cleanupError) {
    throw $cleanupError
}
