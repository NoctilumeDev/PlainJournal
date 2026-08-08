#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('mysql', 'redis', 'nacos', 'rocketmq', 'minio')]
    [string[]]$Stage = @('mysql', 'redis', 'nacos', 'rocketmq', 'minio'),
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 180,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeRoot = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeRoot 'compose.yml'
$environmentFile = Join-Path $composeRoot '.env'
$hostPreflight = Join-Path $PSScriptRoot 'check-verification-host.ps1'
$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\middleware-isolation-$runId"
$evidencePath = if ($OutputPath) {
    if ([IO.Path]::IsPathRooted($OutputPath)) {
        $OutputPath
    }
    else {
        Join-Path $repositoryRoot $OutputPath
    }
}
else {
    Join-Path $runDirectory 'verification.json'
}

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$IgnoreExitCode
    )

    $output = @(& docker compose --env-file $environmentFile --file $composeFile `
            --profile core @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $IgnoreExitCode -and $exitCode -ne 0) {
        throw "Docker Compose failed with exit code ${exitCode}: $($output -join [Environment]::NewLine)"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

function Wait-Probe {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][scriptblock]$Probe
    )

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $lastError = $null
    do {
        try {
            if (& $Probe) {
                return
            }
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Description. Last error: $lastError"
}

function Get-HostSnapshot {
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    return [ordered]@{
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        totalMemoryGiB = [math]::Round($operatingSystem.TotalVisibleMemorySize / 1MB, 2)
        availableMemoryGiB = [math]::Round($operatingSystem.FreePhysicalMemory / 1MB, 2)
    }
}

function Get-ContainerStats {
    param([Parameter(Mandatory)][string[]]$Containers)

    $stats = @()
    foreach ($line in @(& docker stats --no-stream --format '{{json .}}' @Containers 2>$null)) {
        if ($line) {
            $stats += $line | ConvertFrom-Json
        }
    }
    return $stats
}

function Assert-ContainersAbsent {
    param([Parameter(Mandatory)][string[]]$Containers)

    $existing = @(
        $Containers | Where-Object {
            & docker inspect $_ *> $null
            $LASTEXITCODE -eq 0
        }
    )
    if ($existing.Count -ne 0) {
        throw "Isolation stage does not own pre-existing container(s): $($existing -join ', ')"
    }
}

function Write-Evidence {
    param(
        [Parameter(Mandatory)][Collections.IEnumerable]$Results,
        [Parameter(Mandatory)][bool]$Completed
    )

    $records = @($Results)
    $evidence = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $runId
        method = 'serial-controlled-variable'
        completed = $Completed
        stages = $records
        passed = $Completed -and
            @($records | Where-Object { -not $_.passed }).Count -eq 0
    }
    [IO.File]::WriteAllText(
        $evidencePath,
        ($evidence | ConvertTo-Json -Depth 10) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing local environment file: $environmentFile"
}
foreach ($path in @($composeFile, $hostPreflight)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing verification dependency: $path"
    }
}
foreach ($command in @('docker')) {
    if (-not (Get-Command $command -CommandType Application -ErrorAction SilentlyContinue)) {
        throw "Required command is not available on PATH: $command"
    }
}

Import-DotEnv -Path $environmentFile
[IO.Directory]::CreateDirectory((Split-Path -Parent $evidencePath)) | Out-Null

$definitions = [ordered]@{
    mysql = [ordered]@{
        startServices = @('mysql')
        cleanupServices = @('mysql')
        containers = @('plainjournal-mysql')
        probe = {
            $output = @(& docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" `
                    plainjournal-mysql mysql -uroot --batch --skip-column-names `
                    -e 'SELECT 1' 2>$null)
            return $LASTEXITCODE -eq 0 -and ($output | Select-Object -Last 1) -eq '1'
        }
    }
    redis = [ordered]@{
        startServices = @('redis')
        cleanupServices = @('redis')
        containers = @('plainjournal-redis')
        probe = {
            $key = "plainjournal:isolation:$runId"
            $set = & docker exec plainjournal-redis redis-cli --no-auth-warning `
                -a $env:REDIS_PASSWORD SET $key ok EX 60 2>$null
            $get = & docker exec plainjournal-redis redis-cli --no-auth-warning `
                -a $env:REDIS_PASSWORD GET $key 2>$null
            & docker exec plainjournal-redis redis-cli --no-auth-warning `
                -a $env:REDIS_PASSWORD DEL $key *> $null
            return $set -eq 'OK' -and $get -eq 'ok'
        }
    }
    nacos = [ordered]@{
        startServices = @('nacos')
        cleanupServices = @('nacos')
        containers = @('plainjournal-nacos')
        probe = {
            $response = Invoke-WebRequest `
                -Uri "http://127.0.0.1:$env:NACOS_CONSOLE_PORT/v3/console/health/readiness" `
                -TimeoutSec 5 -SkipHttpErrorCheck
            return $response.StatusCode -eq 200
        }
    }
    rocketmq = [ordered]@{
        startServices = @('rocketmq-proxy')
        cleanupServices = @(
            'rocketmq-proxy',
            'rocketmq-broker',
            'rocketmq-store-init',
            'rocketmq-namesrv'
        )
        containers = @(
            'plainjournal-rocketmq-proxy',
            'plainjournal-rocketmq-broker',
            'plainjournal-rocketmq-namesrv'
        )
        probe = {
            $output = @(& docker exec plainjournal-rocketmq-namesrv sh mqadmin clusterList `
                    -n plainjournal-rocketmq-namesrv:9876 2>$null)
            return $LASTEXITCODE -eq 0 -and ($output -match 'EcommerceCluster').Count -gt 0
        }
    }
    minio = [ordered]@{
        startServices = @('minio')
        cleanupServices = @('minio')
        containers = @('plainjournal-minio')
        probe = {
            $response = Invoke-WebRequest `
                -Uri "http://127.0.0.1:$env:MINIO_API_PORT/minio/health/live" `
                -TimeoutSec 5 -SkipHttpErrorCheck
            return $response.StatusCode -eq 200
        }
    }
}

$selectedStages = @($Stage | Select-Object -Unique)
$results = [Collections.Generic.List[object]]::new()

foreach ($stageName in $selectedStages) {
    $definition = $definitions[$stageName]
    Assert-ContainersAbsent -Containers $definition.containers
    & $hostPreflight
    if ($LASTEXITCODE -ne 0) {
        throw "Host preflight rejected middleware stage: $stageName"
    }

    $startedAt = [DateTimeOffset]::UtcNow
    $before = Get-HostSnapshot
    $during = $null
    $stats = @()
    $stageError = $null
    $cleanupError = $null
    try {
        Invoke-Compose -Arguments (@('up', '-d') + $definition.startServices) | Out-Null
        Wait-Probe -Description $stageName -Probe $definition.probe
        $during = Get-HostSnapshot
        $stats = @(Get-ContainerStats -Containers $definition.containers)
    }
    catch {
        $stageError = $_.Exception.Message
    }
    finally {
        $cleanup = Invoke-Compose `
            -Arguments (@('rm', '-s', '-f') + $definition.cleanupServices) `
            -IgnoreExitCode
        if ($cleanup.ExitCode -ne 0) {
            $cleanupError = "Compose cleanup exited with $($cleanup.ExitCode): $($cleanup.Output -join '; ')"
        }
        try {
            Assert-ContainersAbsent -Containers $definition.containers
        }
        catch {
            $cleanupError = if ($cleanupError) {
                "$cleanupError; $($_.Exception.Message)"
            }
            else {
                $_.Exception.Message
            }
        }
    }
    $after = Get-HostSnapshot
    $stagePassed = -not $stageError -and -not $cleanupError

    $results.Add([ordered]@{
        stage = $stageName
        passed = $stagePassed
        startedAtUtc = $startedAt.ToString('o')
        elapsedSeconds = [math]::Round(
            ([DateTimeOffset]::UtcNow - $startedAt).TotalSeconds,
            2)
        hostBefore = $before
        hostDuring = $during
        hostAfter = $after
        containerStats = $stats
        cleanupVerified = -not $cleanupError
        error = $stageError
        cleanupError = $cleanupError
    })
    Write-Evidence -Results $results -Completed $false

    if (-not $stagePassed) {
        throw "Middleware isolation stage failed: stage=$stageName, error=$stageError, cleanupError=$cleanupError, evidence=$evidencePath"
    }
}

Write-Evidence -Results $results -Completed $true

Write-Host "Middleware isolation baseline passed: $($selectedStages -join ', ')"
Write-Host "Evidence: $evidencePath"
