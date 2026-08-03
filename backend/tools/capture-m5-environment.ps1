#requires -Version 7.0

[CmdletBinding()]
param(
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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

function Invoke-VersionCommand {
    param(
        [Parameter(Mandatory)][string]$Command,
        [string[]]$Arguments = @()
    )

    try {
        $output = & $Command @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            return [ordered]@{
                available = $false
                output = ($output -join [Environment]::NewLine)
            }
        }
        return [ordered]@{
            available = $true
            output = ($output -join [Environment]::NewLine)
        }
    }
    catch {
        return [ordered]@{
            available = $false
            output = $_.Exception.Message
        }
    }
}

function Convert-KeyValueRows {
    param([string[]]$Rows)

    $result = [ordered]@{}
    foreach ($row in $Rows) {
        if (-not $row) {
            continue
        }
        $parts = $row -split "`t", 2
        if ($parts.Count -eq 2) {
            $result[$parts[0]] = $parts[1]
        }
    }
    return $result
}

function Get-MySqlSnapshot {
    param([System.Collections.Generic.List[string]]$Warnings)

    if (-not $env:MYSQL_ROOT_PASSWORD) {
        $Warnings.Add('MYSQL_ROOT_PASSWORD is unavailable; MySQL variables were not captured.')
        return $null
    }
    $query = @"
SHOW VARIABLES WHERE Variable_name IN (
  'max_connections',
  'innodb_buffer_pool_size',
  'innodb_flush_log_at_trx_commit',
  'transaction_isolation',
  'slow_query_log',
  'long_query_time'
);
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Threads_connected',
  'Threads_running',
  'Connections',
  'Max_used_connections',
  'Slow_queries',
  'Innodb_row_lock_current_waits',
  'Innodb_row_lock_time',
  'Innodb_row_lock_waits'
);
"@
    $rows = docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" plainjournal-mysql `
        mysql -uroot -N -B -e $query 2>$null
    if ($LASTEXITCODE -ne 0) {
        $Warnings.Add('MySQL runtime variables could not be captured.')
        return $null
    }
    return Convert-KeyValueRows -Rows @($rows)
}

function Get-RedisSnapshot {
    param([System.Collections.Generic.List[string]]$Warnings)

    if (-not $env:REDIS_PASSWORD) {
        $Warnings.Add('REDIS_PASSWORD is unavailable; Redis INFO was not captured.')
        return $null
    }
    $rows = docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis `
        redis-cli INFO memory stats 2>$null
    if ($LASTEXITCODE -ne 0) {
        $Warnings.Add('Redis INFO could not be captured.')
        return $null
    }
    $selected = @(
        'used_memory',
        'used_memory_peak',
        'used_memory_rss',
        'maxmemory',
        'mem_fragmentation_ratio',
        'total_connections_received',
        'total_commands_processed',
        'instantaneous_ops_per_sec',
        'keyspace_hits',
        'keyspace_misses',
        'evicted_keys',
        'expired_keys'
    )
    $result = [ordered]@{}
    foreach ($row in $rows) {
        if ($row -notmatch '^(?<key>[^:#]+):(?<value>.*)$') {
            continue
        }
        $key = $Matches.key.Trim()
        if ($selected -contains $key) {
            $result[$key] = $Matches.value.Trim()
        }
    }
    return $result
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile

if (-not $OutputPath) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $backendRoot ".run\m5-environment-$timestamp.json"
}
$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$warnings = [System.Collections.Generic.List[string]]::new()
$coreContainers = @(
    'plainjournal-mysql',
    'plainjournal-redis',
    'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy',
    'plainjournal-minio'
)

$containerConfigurations = @()
foreach ($containerName in $coreContainers) {
    $rawInspect = docker inspect $containerName 2>$null
    if ($LASTEXITCODE -ne 0) {
        $warnings.Add("Container is unavailable: $containerName")
        continue
    }
    $inspect = @($rawInspect | ConvertFrom-Json)[0]
    $healthProperty = $inspect.State.PSObject.Properties['Health']
    $containerConfigurations += [ordered]@{
        name = $containerName
        image = $inspect.Config.Image
        status = $inspect.State.Status
        health = if ($healthProperty) { $healthProperty.Value.Status } else { $null }
        restartCount = [int]$inspect.RestartCount
        memoryLimitBytes = [long]$inspect.HostConfig.Memory
        memorySwapLimitBytes = [long]$inspect.HostConfig.MemorySwap
        nanoCpus = [long]$inspect.HostConfig.NanoCpus
        cpuQuota = [long]$inspect.HostConfig.CpuQuota
        cpuPeriod = [long]$inspect.HostConfig.CpuPeriod
        pidsLimit = $inspect.HostConfig.PidsLimit
    }
}

$containerRuntime = @()
$statsRows = docker stats --no-stream --format '{{json .}}' 2>$null
if ($LASTEXITCODE -eq 0) {
    foreach ($row in $statsRows) {
        $stats = $row | ConvertFrom-Json
        if ($coreContainers -notcontains $stats.Name) {
            continue
        }
        $containerRuntime += [ordered]@{
            name = $stats.Name
            cpuPercent = $stats.CPUPerc
            memoryUsage = $stats.MemUsage
            memoryPercent = $stats.MemPerc
            networkIo = $stats.NetIO
            blockIo = $stats.BlockIO
            pids = $stats.PIDs
        }
    }
}
else {
    $warnings.Add('Docker runtime stats could not be captured.')
}

$processors = @(Get-CimInstance Win32_Processor | ForEach-Object {
        [ordered]@{
            name = $_.Name
            physicalCores = [int]$_.NumberOfCores
            logicalProcessors = [int]$_.NumberOfLogicalProcessors
            maxClockMhz = [int]$_.MaxClockSpeed
        }
    })
$operatingSystem = Get-CimInstance Win32_OperatingSystem
$computerSystem = Get-CimInstance Win32_ComputerSystem
$disks = @(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object {
        [ordered]@{
            deviceId = $_.DeviceID
            sizeBytes = [long]$_.Size
            freeBytes = [long]$_.FreeSpace
        }
    })

$gitCommit = (git -C $repositoryRoot rev-parse HEAD).Trim()
$gitStatus = @(git -C $repositoryRoot status --porcelain)
$snapshot = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    repository = [ordered]@{
        root = $repositoryRoot
        commit = $gitCommit
        workingTreeEntries = $gitStatus.Count
    }
    host = [ordered]@{
        manufacturer = $computerSystem.Manufacturer
        model = $computerSystem.Model
        processors = $processors
        totalPhysicalMemoryBytes = [long]$computerSystem.TotalPhysicalMemory
        operatingSystem = [ordered]@{
            caption = $operatingSystem.Caption
            version = $operatingSystem.Version
            buildNumber = $operatingSystem.BuildNumber
            totalVisibleMemoryBytes = [long]$operatingSystem.TotalVisibleMemorySize * 1KB
            freePhysicalMemoryBytes = [long]$operatingSystem.FreePhysicalMemory * 1KB
        }
        disks = $disks
    }
    runtimes = [ordered]@{
        java = Invoke-VersionCommand -Command 'java' -Arguments @('-version')
        maven = Invoke-VersionCommand -Command 'mvn' -Arguments @('-version')
        node = Invoke-VersionCommand -Command 'node' -Arguments @('--version')
        pnpm = Invoke-VersionCommand -Command 'pnpm' -Arguments @('--version')
        docker = Invoke-VersionCommand -Command 'docker' -Arguments @('version', '--format', '{{json .}}')
        dockerCompose = Invoke-VersionCommand -Command 'docker' -Arguments @('compose', 'version')
    }
    middleware = [ordered]@{
        containerConfiguration = $containerConfigurations
        containerRuntime = $containerRuntime
        mysql = Get-MySqlSnapshot -Warnings $warnings
        redis = Get-RedisSnapshot -Warnings $warnings
    }
    warnings = @($warnings)
}

$snapshot | ConvertTo-Json -Depth 12 |
    Set-Content -LiteralPath $resolvedOutputPath -Encoding utf8

[ordered]@{
    output = $resolvedOutputPath
    commit = $gitCommit
    workingTreeEntries = $gitStatus.Count
    physicalMemoryGiB = [Math]::Round($computerSystem.TotalPhysicalMemory / 1GB, 2)
    coreContainersCaptured = $containerConfigurations.Count
    warnings = $warnings.Count
} | ConvertTo-Json -Depth 3
