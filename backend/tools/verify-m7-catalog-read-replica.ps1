#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [ValidateRange(30, 300)]
    [int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$composeEnvFile = Join-Path $composeDirectory '.env'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$catalogJar = Join-Path $backendRoot `
    'services\catalog-service\target\catalog-service.jar'
$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\m7-catalog-read-replica-$timestamp"
$evidencePath = Join-Path $runDirectory 'verification.json'
$catalogLog = Join-Path $runDirectory 'catalog.log'
$catalogStdout = Join-Path $runDirectory 'catalog.stdout.log'
$catalogStderr = Join-Path $runDirectory 'catalog.stderr.log'
$replicaLog = Join-Path $runDirectory 'mysql-replica.log'
$replicaInspect = Join-Path $runDirectory 'mysql-replica-inspect.json'
$snapshotPath = Join-Path $runDirectory 'catalog-snapshot.sql'
$sourceSnapshotPath = "/tmp/m7-catalog-snapshot-$PID.sql"
$replicaSnapshotPath = "/tmp/m7-catalog-snapshot-$PID.sql"
$catalogPort = 18102
$replicationUser = 'm7_catalog_replica'
$probeId = 7601000000000000001L
$probeSlug = 'm7-catalog-replica-probe'
$startedAtUtc = [DateTimeOffset]::UtcNow
$settings = @{}
$catalogProcess = $null
$replicaContainerCreated = $false
$replicationUserCreated = $false
$executionError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$evidence = [ordered]@{
    schemaVersion = 1
    startedAtUtc = $startedAtUtc.ToString('o')
    replicationMode = 'binary-log-file-position'
    cacheEnabled = $false
    primaryReadHeader = 'X-Catalog-Read-Consistency: primary'
}

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

function ConvertTo-MySqlLiteral {
    param([Parameter(Mandatory)][string]$Value)

    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function Invoke-ContainerMySql {
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [string]$Database,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$Vertical,
        [switch]$AllowFailure
    )

    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @(
            'exec',
            '-e', "MYSQL_PWD=$Password",
            $Container,
            'mysql',
            "--user=$User",
            '--default-character-set=utf8mb4')) {
        $arguments.Add($argument)
    }
    if ($Vertical) {
        $arguments.Add('--vertical')
    }
    else {
        $arguments.Add('--batch')
        $arguments.Add('--skip-column-names')
    }
    if ($Database) {
        $arguments.Add("--database=$Database")
    }
    $arguments.Add("--execute=$Sql")

    $output = @(& docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed in $Container`: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-SourceRootSql {
    param([Parameter(Mandatory)][string]$Sql)

    return @(Invoke-ContainerMySql `
            -Container 'plainjournal-mysql' `
            -User 'root' `
            -Password $script:settings['MYSQL_ROOT_PASSWORD'] `
            -Sql $Sql)
}

function Invoke-ReplicaRootSql {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [switch]$Vertical,
        [switch]$AllowFailure
    )

    return @(Invoke-ContainerMySql `
            -Container 'plainjournal-mysql-replica' `
            -User 'root' `
            -Password $script:settings['MYSQL_ROOT_PASSWORD'] `
            -Sql $Sql `
            -Vertical:$Vertical `
            -AllowFailure:$AllowFailure)
}

function Get-MySqlScalar {
    param(
        [Parameter(Mandatory)][ValidateSet('primary', 'replica')]
        [string]$Target,
        [Parameter(Mandatory)][string]$Sql
    )

    $rows = @(
        if ($Target -eq 'primary') {
            Invoke-SourceRootSql -Sql $Sql
        }
        else {
            Invoke-ReplicaRootSql -Sql $Sql
        }
    )
    if ($rows.Count -ne 1) {
        throw "Expected one $Target MySQL scalar row, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function Get-ReplicaStatus {
    $rows = @(Invoke-ReplicaRootSql -Sql 'SHOW REPLICA STATUS;' -Vertical)
    $status = [ordered]@{}
    foreach ($row in $rows) {
        if ($row -match '^\s*(?<key>[A-Za-z0-9_]+):\s?(?<value>.*)$') {
            $status[$Matches.key] = $Matches.value.Trim()
        }
    }
    if ($status.Count -eq 0) {
        throw 'SHOW REPLICA STATUS returned no configured channel.'
    }
    return $status
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'not inspected'
    do {
        $raw = docker inspect $Container 2>$null
        if ($LASTEXITCODE -eq 0) {
            $inspect = @($raw | ConvertFrom-Json)[0]
            $healthProperty = $inspect.State.PSObject.Properties['Health']
            $health = if ($healthProperty) {
                $healthProperty.Value.Status
            }
            else {
                $null
            }
            $lastState = "status=$($inspect.State.Status), health=$health"
            if ($inspect.State.Status -eq 'running' -and
                    ($null -eq $health -or $health -eq 'healthy')) {
                return
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Container. Last state: $lastState"
}

function Wait-ReplicaRunning {
    param([int]$TimeoutSeconds = 120)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = $null
    do {
        $lastStatus = Get-ReplicaStatus
        if (($lastStatus.Replica_IO_Running -eq 'Yes') -and
                ($lastStatus.Replica_SQL_Running -eq 'Yes') -and
                [string]::IsNullOrWhiteSpace($lastStatus.Last_IO_Error) -and
                [string]::IsNullOrWhiteSpace($lastStatus.Last_SQL_Error)) {
            return $lastStatus
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Replica threads did not become healthy: $($lastStatus | ConvertTo-Json -Compress)"
}

function Wait-ReplicaScalar {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [Parameter(Mandatory)][string]$Expected,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastValue = $null
    do {
        $lastValue = Get-MySqlScalar -Target replica -Sql $Sql
        if ($lastValue -eq $Expected) {
            return
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw "Replica did not converge to '$Expected'. Last value: '$lastValue'."
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'not queried'
    do {
        if ($null -ne $script:catalogProcess) {
            $script:catalogProcess.Refresh()
            if ($script:catalogProcess.HasExited) {
                throw "Catalog exited with code $($script:catalogProcess.ExitCode)."
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3
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
    throw "Timed out waiting for $Uri. Last error: $lastError"
}

function Get-CatalogCategories {
    param([switch]$Primary)

    $headers = if ($Primary) {
        @{ 'X-Catalog-Read-Consistency' = 'primary' }
    }
    else {
        @{}
    }
    $response = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$catalogPort/api/v1/catalog/categories" `
        -Headers $headers `
        -TimeoutSec 10
    if ($response.code -ne 'OK') {
        throw "Catalog categories returned code $($response.code)."
    }
    return @($response.data)
}

function Test-ProbePresent {
    param([object[]]$Categories)

    return @($Categories | Where-Object { $_.slug -eq $probeSlug }).Count -eq 1
}

function Get-PrometheusText {
    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$catalogPort/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $script:settings['METRICS_SCRAPE_TOKEN'] } `
        -TimeoutSec 10
    return $response.Content
}

function Get-PrometheusMetric {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Metric,
        [string]$RequiredLabel
    )

    $total = 0.0
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line.StartsWith('#') -or -not $line.StartsWith($Metric)) {
            continue
        }
        if ($RequiredLabel -and $line -notmatch [regex]::Escape($RequiredLabel)) {
            continue
        }
        if ($line -match '\s(?<value>-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)$') {
            $total += [double]::Parse(
                $Matches.value,
                [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return $total
}

function Get-ResourceSnapshot {
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $stats = @(docker stats --no-stream --format '{{json .}}' `
            plainjournal-mysql plainjournal-mysql-replica 2>$null)
    return [ordered]@{
        freeHostMemoryGiB = [Math]::Round(
            [long]$operatingSystem.FreePhysicalMemory * 1KB / 1GB,
            2)
        containers = @($stats | ForEach-Object { $_ | ConvertFrom-Json })
    }
}

function Assert-PortFree {
    param([Parameter(Mandatory)][int]$Port)

    $listener = @(Get-NetTCPConnection `
            -State Listen `
            -LocalPort $Port `
            -ErrorAction SilentlyContinue)
    if ($listener.Count -gt 0) {
        throw "Port $Port is already in use by PID $($listener[0].OwningProcess)."
    }
}

function Start-Catalog {
    $environment = @{
        APP_ENV = 'm7-catalog-read-replica'
        SERVER_PORT = "$catalogPort"
        CATALOG_SERVICE_PORT = "$catalogPort"
        SPRING_CONFIG_IMPORT = 'optional:nacos:'
        SPRING_CLOUD_NACOS_DISCOVERY_ENABLED = 'false'
        SPRING_CLOUD_NACOS_CONFIG_ENABLED = 'false'
        SPRING_CLOUD_DISCOVERY_ENABLED = 'false'
        SPRING_DATASOURCE_URL =
            "jdbc:mysql://127.0.0.1:$($script:settings['MYSQL_PORT'])/" +
            "$($script:settings['CATALOG_DB_NAME'])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' +
            '&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = $script:settings['CATALOG_DB_USER']
        SPRING_DATASOURCE_PASSWORD = $script:settings['CATALOG_DB_PASSWORD']
        CATALOG_READ_REPLICA_ENABLED = 'true'
        CATALOG_READ_REPLICA_FALLBACK_TO_PRIMARY = 'true'
        CATALOG_REPLICA_HOST = '127.0.0.1'
        CATALOG_REPLICA_PORT = $script:settings['CATALOG_REPLICA_PORT']
        CATALOG_REPLICA_DB_NAME = $script:settings['CATALOG_DB_NAME']
        CATALOG_REPLICA_DB_USER = $script:settings['CATALOG_DB_USER']
        CATALOG_REPLICA_DB_PASSWORD = $script:settings['CATALOG_DB_PASSWORD']
        CATALOG_CACHE_ENABLED = 'false'
        IDENTITY_JWT_SECRET = $script:settings['IDENTITY_JWT_SECRET']
        MINIO_ROOT_USER = $script:settings['MINIO_ROOT_USER']
        MINIO_ROOT_PASSWORD = $script:settings['MINIO_ROOT_PASSWORD']
        ECOMMERCE_SECURITY_METRICS_TOKEN = $script:settings['METRICS_SCRAPE_TOKEN']
        MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,info,metrics,prometheus'
        MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED = 'true'
        MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED = 'false'
        LOGGING_FILE_NAME = $catalogLog
    }
    $script:catalogProcess = Start-Process `
        -FilePath ((Get-Command java.exe).Source) `
        -ArgumentList @('-jar', $catalogJar) `
        -WorkingDirectory $backendRoot `
        -Environment $environment `
        -RedirectStandardOutput $catalogStdout `
        -RedirectStandardError $catalogStderr `
        -WindowStyle Hidden `
        -PassThru
}

function Stop-Catalog {
    $targetIds = [System.Collections.Generic.HashSet[int]]::new()
    if ($null -ne $script:catalogProcess) {
        [void]$targetIds.Add($script:catalogProcess.Id)
    }
    $listeners = @(Get-NetTCPConnection `
            -State Listen `
            -LocalPort $catalogPort `
            -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        [void]$targetIds.Add([int]$listener.OwningProcess)
    }

    foreach ($targetId in $targetIds) {
        $process = Get-CimInstance Win32_Process `
            -Filter "ProcessId=$targetId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        if (($process.Name -ne 'java.exe') -or
                (-not $process.CommandLine.Contains(
                    $catalogJar,
                    [StringComparison]::OrdinalIgnoreCase))) {
            throw "Refusing to stop unexpected process $targetId on Catalog verification port."
        }
        Stop-Process -Id $targetId -Force
    }

    $deadline = (Get-Date).AddSeconds(10)
    do {
        $remaining = @(Get-NetTCPConnection `
                -State Listen `
                -LocalPort $catalogPort `
                -ErrorAction SilentlyContinue)
        if ($remaining.Count -eq 0) {
            $script:catalogProcess = $null
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if ($remaining.Count -gt 0) {
        throw "Catalog verification port $catalogPort is still listening."
    }
}

function Remove-ContainerSnapshotFiles {
    docker exec plainjournal-mysql rm -f $sourceSnapshotPath 2>$null
    if ((docker ps -a --format '{{.Names}}') -contains 'plainjournal-mysql-replica') {
        docker exec plainjournal-mysql-replica rm -f $replicaSnapshotPath 2>$null
    }
}

if (-not (Test-Path -LiteralPath $composeEnvFile)) {
    throw "Missing Docker environment file: $composeEnvFile"
}
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$settings = Read-DotEnv -Path $composeEnvFile
if (-not $settings.ContainsKey('CATALOG_REPLICA_PORT')) {
    $settings['CATALOG_REPLICA_PORT'] = '13316'
}
foreach ($required in @(
        'MYSQL_PORT', 'MYSQL_ROOT_PASSWORD',
        'CATALOG_DB_NAME', 'CATALOG_DB_USER', 'CATALOG_DB_PASSWORD',
        'IDENTITY_JWT_SECRET',
        'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'METRICS_SCRAPE_TOKEN')) {
    if ((-not $settings.ContainsKey($required)) -or
            [string]::IsNullOrWhiteSpace($settings[$required])) {
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

$runningContainers = @(docker ps --format '{{.Names}}')
if ($runningContainers -notcontains 'plainjournal-mysql') {
    throw 'The core plainjournal-mysql container must already be running.'
}
$conflictingContainers = @(
    'plainjournal-prometheus', 'plainjournal-alertmanager', 'plainjournal-grafana', 'plainjournal-tempo',
    'plainjournal-mysql-trade-shard-1'
)
$conflicts = @($conflictingContainers | Where-Object {
        $runningContainers -contains $_
    })
if ($conflicts.Count -gt 0) {
    throw "The M7 Catalog replica profile is exclusive. Stop: $($conflicts -join ', ')."
}
if ((docker ps -a --format '{{.Names}}') -contains 'plainjournal-mysql-replica') {
    throw 'plainjournal-mysql-replica already exists. Inspect and remove that experimental container before rerunning.'
}
Assert-PortFree -Port ([int]$settings['CATALOG_REPLICA_PORT'])
Assert-PortFree -Port $catalogPort

$operatingSystem = Get-CimInstance Win32_OperatingSystem
$freeMemoryBytes = [long]$operatingSystem.FreePhysicalMemory * 1KB
if ($freeMemoryBytes -lt 2GB) {
    throw "M7 Catalog replica verification requires 2 GiB free memory; current free memory is " +
        "$([Math]::Round($freeMemoryBytes / 1GB, 2)) GiB."
}

$primaryVariables = @(Invoke-SourceRootSql -Sql @"
SELECT @@version, @@server_id, @@log_bin, @@binlog_format, @@gtid_mode,
       @@enforce_gtid_consistency;
"@)
if ($primaryVariables.Count -ne 1) {
    throw 'Unable to read primary MySQL replication variables.'
}
$primaryVariableValues = $primaryVariables[0].ToString().Split("`t")
if ($primaryVariableValues[2] -ne '1' -or $primaryVariableValues[3] -ne 'ROW') {
    throw 'Primary MySQL must have binary logging enabled with ROW binlog format.'
}
$evidence.primary = [ordered]@{
    version = $primaryVariableValues[0]
    serverId = $primaryVariableValues[1]
    logBin = $primaryVariableValues[2]
    binlogFormat = $primaryVariableValues[3]
    gtidMode = $primaryVariableValues[4]
    enforceGtidConsistency = $primaryVariableValues[5]
}

if (-not $SkipBuild) {
    Push-Location $backendRoot
    try {
        & mvn -pl services/catalog-service -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Catalog Maven package failed.'
        }
    }
    finally {
        Pop-Location
    }
}
if (-not (Test-Path -LiteralPath $catalogJar)) {
    throw "Missing Catalog jar: $catalogJar"
}

try {
    $sourceDatabase = $settings['CATALOG_DB_NAME']
    $sourceDatabaseLiteral = ConvertTo-MySqlLiteral -Value $sourceDatabase
    $appUserLiteral = ConvertTo-MySqlLiteral -Value $settings['CATALOG_DB_USER']
    $appPasswordLiteral = ConvertTo-MySqlLiteral -Value $settings['CATALOG_DB_PASSWORD']
    $replicationPassword = [Convert]::ToHexString(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
    $replicationUserLiteral = ConvertTo-MySqlLiteral -Value $replicationUser
    $replicationPasswordLiteral = ConvertTo-MySqlLiteral -Value $replicationPassword

    [void](Invoke-SourceRootSql -Sql @"
DELETE FROM $sourceDatabase.catalog_category WHERE id = $probeId OR slug = '$probeSlug';
CREATE USER IF NOT EXISTS $replicationUserLiteral@'%' IDENTIFIED BY $replicationPasswordLiteral;
ALTER USER $replicationUserLiteral@'%' IDENTIFIED BY $replicationPasswordLiteral;
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO $replicationUserLiteral@'%';
"@)
    $replicationUserCreated = $true

    $dumpOutput = @(& docker exec `
            -e "MYSQL_PWD=$($settings['MYSQL_ROOT_PASSWORD'])" `
            plainjournal-mysql `
            mysqldump `
            --user=root `
            --single-transaction `
            --source-data=2 `
            --set-gtid-purged=OFF `
            --default-character-set=utf8mb4 `
            --hex-blob `
            --routines=false `
            --events=false `
            --triggers=true `
            --databases $sourceDatabase `
            "--result-file=$sourceSnapshotPath" 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Catalog snapshot failed: $($dumpOutput -join [Environment]::NewLine)"
    }
    & docker cp "plainjournal-mysql:$sourceSnapshotPath" $snapshotPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to copy the Catalog snapshot from primary MySQL.'
    }
    $snapshotHeader = Get-Content -LiteralPath $snapshotPath -TotalCount 120 |
        Out-String
    if ($snapshotHeader -notmatch
            "SOURCE_LOG_FILE='(?<file>[^']+)'\s*,\s*SOURCE_LOG_POS=(?<position>[0-9]+)") {
        throw 'The Catalog snapshot does not contain binary-log coordinates.'
    }
    $sourceLogFile = $Matches.file
    $sourceLogPosition = [long]$Matches.position
    $evidence.snapshot = [ordered]@{
        sourceLogFile = $sourceLogFile
        sourceLogPosition = $sourceLogPosition
        bytes = (Get-Item -LiteralPath $snapshotPath).Length
    }

    Push-Location $composeDirectory
    try {
        & docker compose `
            --env-file $composeEnvFile `
            --profile m7-catalog-replica `
            --project-directory $composeDirectory `
            -f $composeFile `
            config *> (Join-Path $runDirectory 'compose-config.txt')
        if ($LASTEXITCODE -ne 0) {
            throw 'M7 Catalog replica Compose validation failed.'
        }
        & docker compose `
            --env-file $composeEnvFile `
            --profile m7-catalog-replica `
            --project-directory $composeDirectory `
            -f $composeFile `
            up -d mysql-replica
        if ($LASTEXITCODE -ne 0) {
            throw 'M7 Catalog replica container startup failed.'
        }
        $replicaContainerCreated = $true
    }
    finally {
        Pop-Location
    }
    Wait-ContainerHealthy `
        -Container 'plainjournal-mysql-replica' `
        -TimeoutSeconds $StartupTimeoutSeconds

    [void](Invoke-ReplicaRootSql `
            -Sql 'STOP REPLICA;' `
            -AllowFailure)
    [void](Invoke-ReplicaRootSql -Sql @"
RESET REPLICA ALL;
SET GLOBAL super_read_only = OFF;
SET GLOBAL read_only = OFF;
DROP DATABASE IF EXISTS $sourceDatabase;
"@)
    & docker cp $snapshotPath "plainjournal-mysql-replica:$replicaSnapshotPath"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to copy the Catalog snapshot into replica MySQL.'
    }
    $importOutput = @(& docker exec `
            -e "MYSQL_PWD=$($settings['MYSQL_ROOT_PASSWORD'])" `
            plainjournal-mysql-replica `
            sh -lc `
            "mysql --user=root --default-character-set=utf8mb4 < $replicaSnapshotPath" 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Catalog replica snapshot import failed: $($importOutput -join [Environment]::NewLine)"
    }
    [void](Invoke-ReplicaRootSql -Sql @"
CREATE USER IF NOT EXISTS $appUserLiteral@'%' IDENTIFIED BY $appPasswordLiteral;
ALTER USER $appUserLiteral@'%' IDENTIFIED BY $appPasswordLiteral;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM $appUserLiteral@'%';
GRANT SELECT ON $sourceDatabase.* TO $appUserLiteral@'%';
CHANGE REPLICATION SOURCE TO
    SOURCE_HOST = 'plainjournal-mysql',
    SOURCE_PORT = 3306,
    SOURCE_USER = $replicationUserLiteral,
    SOURCE_PASSWORD = $replicationPasswordLiteral,
    SOURCE_LOG_FILE = '$sourceLogFile',
    SOURCE_LOG_POS = $sourceLogPosition,
    GET_SOURCE_PUBLIC_KEY = 1,
    SOURCE_CONNECT_RETRY = 2;
SET GLOBAL read_only = ON;
SET GLOBAL super_read_only = ON;
START REPLICA;
"@)
    $initialStatus = Wait-ReplicaRunning -TimeoutSeconds $StartupTimeoutSeconds
    $sourceCategoryCount = Get-MySqlScalar `
        -Target primary `
        -Sql "SELECT COUNT(*) FROM $sourceDatabase.catalog_category;"
    Wait-ReplicaScalar `
        -Sql "SELECT COUNT(*) FROM $sourceDatabase.catalog_category;" `
        -Expected $sourceCategoryCount `
        -TimeoutSeconds $StartupTimeoutSeconds
    $evidence.initialReplicaStatus = $initialStatus
    $evidence.resourcesBefore = Get-ResourceSnapshot

    Start-Catalog
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$catalogPort/actuator/health/liveness" `
        -TimeoutSeconds $StartupTimeoutSeconds

    [void](Invoke-ReplicaRootSql -Sql 'STOP REPLICA SQL_THREAD;')
    $pausedStatus = Get-ReplicaStatus
    if ($pausedStatus.Replica_SQL_Running -ne 'No') {
        throw 'Replica SQL thread did not stop for the lag experiment.'
    }
    [void](Invoke-SourceRootSql -Sql @"
INSERT INTO $sourceDatabase.catalog_category
    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
VALUES
    ($probeId, NULL, 'M7 Replica Probe', '$probeSlug', 'ACTIVE', 0, 0,
     CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
"@)

    $replicaReadDuringPause = @(Get-CatalogCategories)
    if (Test-ProbePresent -Categories $replicaReadDuringPause) {
        throw 'Paused replica unexpectedly exposed the new primary row.'
    }
    $primaryHintRead = @(Get-CatalogCategories -Primary)
    if (-not (Test-ProbePresent -Categories $primaryHintRead)) {
        throw 'Explicit primary-read consistency hint did not expose the new row.'
    }

    $resumeStarted = [Diagnostics.Stopwatch]::StartNew()
    [void](Invoke-ReplicaRootSql -Sql 'START REPLICA SQL_THREAD;')
    Wait-ReplicaScalar `
        -Sql "SELECT COUNT(*) FROM $sourceDatabase.catalog_category WHERE id = $probeId;" `
        -Expected '1' `
        -TimeoutSeconds $StartupTimeoutSeconds
    $resumeStarted.Stop()
    $caughtUpStatus = Wait-ReplicaRunning -TimeoutSeconds $StartupTimeoutSeconds
    $replicaReadAfterResume = @(Get-CatalogCategories)
    if (-not (Test-ProbePresent -Categories $replicaReadAfterResume)) {
        throw 'Catalog did not expose the probe after replica catch-up.'
    }

    $metricsBeforeFault = Get-PrometheusText
    $replicaAttempts = Get-PrometheusMetric `
        -Text $metricsBeforeFault `
        -Metric 'ecommerce_catalog_datasource_connection_attempts_total' `
        -RequiredLabel 'target="replica"'
    $primaryHints = Get-PrometheusMetric `
        -Text $metricsBeforeFault `
        -Metric 'ecommerce_catalog_datasource_primary_hints_total'
    if ($replicaAttempts -lt 1 -or $primaryHints -lt 1) {
        throw "Expected route metrics were not observed: replica=$replicaAttempts, hints=$primaryHints."
    }

    & docker stop --time 10 plainjournal-mysql-replica *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to stop the replica for fault injection.'
    }
    Start-Sleep -Seconds 1
    $fallbackRead = @(Get-CatalogCategories)
    if (-not (Test-ProbePresent -Categories $fallbackRead)) {
        throw 'Replica outage did not fall back to a successful primary read.'
    }
    $metricsAfterFault = Get-PrometheusText
    $fallbackCount = Get-PrometheusMetric `
        -Text $metricsAfterFault `
        -Metric 'ecommerce_catalog_datasource_replica_fallbacks_total'
    $replicaConnectionFailures = Get-PrometheusMetric `
        -Text $metricsAfterFault `
        -Metric 'ecommerce_catalog_datasource_replica_connection_failures_total'
    if ($fallbackCount -lt 1) {
        throw 'Replica outage fallback metric was not incremented.'
    }

    $restartStarted = [Diagnostics.Stopwatch]::StartNew()
    & docker start plainjournal-mysql-replica *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to restart the replica after fault injection.'
    }
    Wait-ContainerHealthy `
        -Container 'plainjournal-mysql-replica' `
        -TimeoutSeconds $StartupTimeoutSeconds
    [void](Invoke-ReplicaRootSql -Sql 'START REPLICA;')
    [void](Wait-ReplicaRunning -TimeoutSeconds $StartupTimeoutSeconds)
    Wait-ReplicaScalar `
        -Sql "SELECT COUNT(*) FROM $sourceDatabase.catalog_category WHERE id = $probeId;" `
        -Expected '1' `
        -TimeoutSeconds $StartupTimeoutSeconds
    $restartStarted.Stop()
    $postRecoveryRead = @(Get-CatalogCategories)
    if (-not (Test-ProbePresent -Categories $postRecoveryRead)) {
        throw 'Catalog did not return to replica reads after replica recovery.'
    }

    $evidence.routing = [ordered]@{
        pausedReplicaHidNewPrimaryRow = $true
        primaryHintExposedNewPrimaryRow = $true
        replicaExposedRowAfterCatchUp = $true
        catchUpMilliseconds = [Math]::Round($resumeStarted.Elapsed.TotalMilliseconds, 2)
        replicaConnectionAttempts = $replicaAttempts
        primaryHints = $primaryHints
    }
    $evidence.faultRecovery = [ordered]@{
        replicaStopReturnedPrimaryData = $true
        fallbackCount = $fallbackCount
        replicaConnectionFailures = $replicaConnectionFailures
        replicaRecovered = $true
        recoveryMilliseconds = [Math]::Round($restartStarted.Elapsed.TotalMilliseconds, 2)
    }
    $evidence.pausedReplicaStatus = $pausedStatus
    $evidence.caughtUpReplicaStatus = $caughtUpStatus
    $evidence.resourcesAfter = Get-ResourceSnapshot
    $evidence.verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $evidence.elapsedSeconds = [Math]::Round(
        ([DateTimeOffset]::UtcNow - $startedAtUtc).TotalSeconds,
        3)
    $evidence.passed = $true
    Write-Host 'M7 Catalog read-replica verification passed.'
}
catch {
    $executionError = $_
    $evidence.passed = $false
    $evidence.error = $_.Exception.Message
}
finally {
    try {
        if ((docker ps -a --format '{{.Names}}') -contains 'plainjournal-mysql-replica') {
            docker logs plainjournal-mysql-replica 2>&1 |
                Set-Content -LiteralPath $replicaLog -Encoding utf8
            docker inspect plainjournal-mysql-replica |
                Set-Content -LiteralPath $replicaInspect -Encoding utf8
        }
    }
    catch {
        $cleanupErrors.Add("Replica diagnostic capture failed: $($_.Exception.Message)")
    }
    try {
        if ($settings.ContainsKey('CATALOG_DB_NAME')) {
            [void](Invoke-SourceRootSql -Sql @"
DELETE FROM $($settings['CATALOG_DB_NAME']).catalog_category
WHERE id = $probeId OR slug = '$probeSlug';
"@)
        }
    }
    catch {
        $cleanupErrors.Add("Primary probe cleanup failed: $($_.Exception.Message)")
    }
    try {
        Stop-Catalog
    }
    catch {
        $cleanupErrors.Add("Catalog process cleanup failed: $($_.Exception.Message)")
    }
    try {
        Remove-ContainerSnapshotFiles
    }
    catch {
        $cleanupErrors.Add("Container snapshot cleanup failed: $($_.Exception.Message)")
    }
    try {
        if ($replicaContainerCreated) {
            Push-Location $composeDirectory
            try {
                & docker compose `
                    --env-file $composeEnvFile `
                    --profile m7-catalog-replica `
                    --project-directory $composeDirectory `
                    -f $composeFile `
                    rm -s -f mysql-replica *> $null
                if ($LASTEXITCODE -ne 0) {
                    throw 'Compose failed to remove the experimental replica container.'
                }
            }
            finally {
                Pop-Location
            }
        }
    }
    catch {
        $cleanupErrors.Add("Replica container cleanup failed: $($_.Exception.Message)")
    }
    try {
        if ($replicationUserCreated) {
            [void](Invoke-SourceRootSql -Sql `
                    "DROP USER IF EXISTS '$replicationUser'@'%';")
        }
    }
    catch {
        $cleanupErrors.Add("Replication user cleanup failed: $($_.Exception.Message)")
    }
    if (Test-Path -LiteralPath $snapshotPath) {
        Remove-Item -LiteralPath $snapshotPath -Force
    }
    $remainingProbeRows = try {
        $probeCountSql =
            "SELECT COUNT(*) FROM $($settings['CATALOG_DB_NAME']).catalog_category " +
            "WHERE id = $probeId OR slug = '$probeSlug';"
        [int](Get-MySqlScalar -Target primary -Sql $probeCountSql)
    }
    catch {
        $null
    }
    $evidence.cleanup = [ordered]@{
        catalogStopped = @(
            Get-NetTCPConnection `
                -State Listen `
                -LocalPort $catalogPort `
                -ErrorAction SilentlyContinue
        ).Count -eq 0
        replicaContainerExists =
            (docker ps -a --format '{{.Names}}') -contains 'plainjournal-mysql-replica'
        probeRowsRemaining = $remainingProbeRows
        errors = @($cleanupErrors)
    }
    $evidence.gitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
    $evidence.evidencePath = $evidencePath
    $evidence | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8
    Write-Host "Evidence: $evidencePath"
}

if ($null -ne $executionError) {
    throw $executionError
}
if ($cleanupErrors.Count -gt 0) {
    throw "Verification passed but cleanup failed: $($cleanupErrors -join '; ')"
}
