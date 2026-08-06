#requires -Version 7.0

[CmdletBinding()]
param(
    [int[]]$ConcurrencyLevels = @(1, 10, 50, 100, 300, 500, 1000),
    [ValidateRange(100, 100000)]
    [int]$RequestsPerLevel = 1000,
    [ValidateRange(0, 2147483639)]
    [int]$Seed = 20260806,
    [ValidateRange(2, 10000)]
    [int]$InventorySuccesses = 100,
    [ValidateRange(1, 10000)]
    [int]$TradeSuccesses = 100,
    [ValidateRange(0.1, 64.0)]
    [double]$MinimumAvailableMemoryGiB = 3.0,
    [ValidateRange(1, 99)]
    [int]$MaximumMemoryUtilizationPercent = 82,
    [ValidateRange(0.1, 0.99)]
    [double]$MaximumDynamicPortUtilization = 0.80,
    [ValidateRange(1, 1440)]
    [int]$RecentPortExhaustionMinutes = 15,
    [switch]$AllowDirtyWorktree,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$foundationScript = Join-Path $backendRoot 'run-foundation-smoke.ps1'
$hostPreflight = Join-Path $PSScriptRoot 'check-verification-host.ps1'
$capacityEvidence = Join-Path $backendRoot '.run\capacity-baseline.json'
$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\capacity-ladder-$runId"
$evidencePath = if ($OutputPath) {
    if ([IO.Path]::IsPathRooted($OutputPath)) {
        [IO.Path]::GetFullPath($OutputPath)
    }
    else {
        [IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputPath))
    }
}
else {
    Join-Path $runDirectory 'verification.json'
}
$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-redis',
    'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy',
    'plainjournal-minio'
)

function Invoke-HostPreflight {
    $output = @(& $hostPreflight `
            -RequiredContainers $requiredContainers `
            -MinimumAvailableMemoryGiB $MinimumAvailableMemoryGiB `
            -MaximumMemoryUtilizationPercent $MaximumMemoryUtilizationPercent `
            -MaximumDynamicPortUtilization $MaximumDynamicPortUtilization `
            -RecentPortExhaustionMinutes $RecentPortExhaustionMinutes `
            -AsJson 2>&1)
    $exitCode = $LASTEXITCODE
    $json = $output -join [Environment]::NewLine
    try {
        $result = $json | ConvertFrom-Json -Depth 10
    }
    catch {
        throw "Host preflight returned invalid JSON: $json"
    }
    return [pscustomobject]@{
        exitCode = $exitCode
        result = $result
    }
}

function Write-Evidence {
    param(
        [Parameter(Mandatory)][Collections.IEnumerable]$Levels,
        [Parameter(Mandatory)][bool]$Completed
    )

    $records = @($Levels)
    $evidence = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $runId
        method = 'resource-bounded-capacity-ladder'
        gitCommit = $gitCommit
        gitWorkingTreeDirty = $gitWorkingTreeDirty
        formalEvidenceEligible = -not $gitWorkingTreeDirty
        parameters = [ordered]@{
            requestsPerLevel = $RequestsPerLevel
            concurrencyLevels = $levels
            seed = $Seed
            inventorySuccesses = $InventorySuccesses
            tradeSuccesses = $TradeSuccesses
            minimumAvailableMemoryGiB = $MinimumAvailableMemoryGiB
            maximumMemoryUtilizationPercent = $MaximumMemoryUtilizationPercent
            maximumDynamicPortUtilization = $MaximumDynamicPortUtilization
            recentPortExhaustionMinutes = $RecentPortExhaustionMinutes
        }
        completed = $Completed
        levels = $records
        passed = $Completed -and @($records | Where-Object { -not $_.passed }).Count -eq 0
    }
    [IO.File]::WriteAllText(
        $evidencePath,
        ($evidence | ConvertTo-Json -Depth 20) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

foreach ($path in @($foundationScript, $hostPreflight)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing verification dependency: $path"
    }
}
foreach ($command in @('docker', 'git', 'java', 'node')) {
    if (-not (Get-Command $command -CommandType Application -ErrorAction SilentlyContinue)) {
        throw "Required command is not available on PATH: $command"
    }
}

$levels = @($ConcurrencyLevels | Sort-Object -Unique)
if ($levels.Count -eq 0 -or @($levels | Where-Object { $_ -lt 1 -or $_ -gt 1000 }).Count -gt 0) {
    throw 'ConcurrencyLevels must contain values between 1 and 1000.'
}
if (@($levels | Where-Object { $_ -gt $RequestsPerLevel }).Count -gt 0) {
    throw 'Every concurrency level must be less than or equal to RequestsPerLevel.'
}
if ($InventorySuccesses -ge $RequestsPerLevel -or $TradeSuccesses -ge $RequestsPerLevel) {
    throw 'InventorySuccesses and TradeSuccesses must be lower than RequestsPerLevel.'
}

$gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -Last 1)
$gitWorkingTreeDirty = @(& git -C $repositoryRoot status --porcelain 2>$null).Count -gt 0
if ($gitWorkingTreeDirty -and -not $AllowDirtyWorktree) {
    throw 'The capacity ladder requires a clean working tree. Use -AllowDirtyWorktree only for non-formal development runs.'
}

[IO.Directory]::CreateDirectory((Split-Path -Parent $evidencePath)) | Out-Null
[IO.Directory]::CreateDirectory($runDirectory) | Out-Null
$results = [Collections.Generic.List[object]]::new()

foreach ($level in $levels) {
    $record = [ordered]@{
        concurrency = $level
        passed = $false
        startedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        completedAtUtc = $null
        preflightBefore = $null
        preflightAfter = $null
        foundationEvidence = $null
        log = $null
        error = $null
    }
    $before = Invoke-HostPreflight
    $record.preflightBefore = $before.result
    if ($before.exitCode -ne 0 -or -not $before.result.passed) {
        $record.error = 'Host preflight rejected the level before business traffic.'
        $record.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $results.Add($record)
        Write-Evidence -Levels $results -Completed $false
        throw "Capacity ladder stopped before concurrency $level. Evidence: $evidencePath"
    }

    $logPath = Join-Path $runDirectory "concurrency-$level.log"
    $levelEvidencePath = Join-Path $runDirectory "concurrency-$level.json"
    $record.log = [IO.Path]::GetRelativePath($repositoryRoot, $logPath).Replace('\', '/')
    Remove-Item -LiteralPath $capacityEvidence -Force -ErrorAction SilentlyContinue

    try {
        & $foundationScript `
            -EnableCapacityBaseline `
            -CapacityRequests $RequestsPerLevel `
            -CapacityConcurrency $level `
            -CapacitySeed $Seed `
            -CapacityInventorySuccesses $InventorySuccesses `
            -CapacityTradeSuccesses $TradeSuccesses *>&1 |
            Tee-Object -FilePath $logPath | Out-Host

        if (-not (Test-Path -LiteralPath $capacityEvidence -PathType Leaf)) {
            throw "Foundation smoke produced no capacity evidence for concurrency $level."
        }
        $levelEvidence = Get-Content -LiteralPath $capacityEvidence -Raw |
            ConvertFrom-Json -Depth 30
        if (-not $levelEvidence.correctness.stockEquationVerified -or
            -not $levelEvidence.correctness.idempotencyVerified -or
            [int]$levelEvidence.parameters.concurrency -ne $level) {
            throw "Foundation evidence did not satisfy the capacity contract for concurrency $level."
        }
        Copy-Item -LiteralPath $capacityEvidence -Destination $levelEvidencePath -Force
        $record.foundationEvidence = [IO.Path]::GetRelativePath(
            $repositoryRoot,
            $levelEvidencePath).Replace('\', '/')
    }
    catch {
        $record.error = $_.Exception.Message
    }

    $after = Invoke-HostPreflight
    $record.preflightAfter = $after.result
    if (($after.exitCode -ne 0 -or -not $after.result.passed) -and -not $record.error) {
        $record.error = 'Host preflight rejected the level after business traffic.'
    }
    $record.passed = -not $record.error
    $record.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $results.Add($record)
    Write-Evidence -Levels $results -Completed $false

    if (-not $record.passed) {
        throw "Capacity ladder stopped at concurrency $level. Evidence: $evidencePath"
    }
}

Write-Evidence -Levels $results -Completed $true
Write-Host "Capacity ladder passed: $($levels -join ' -> ')"
Write-Host "Evidence: $evidencePath"
