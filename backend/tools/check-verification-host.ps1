#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipDocker,
    [string[]]$RequiredContainers = @(),
    [ValidateRange(0.1, 64.0)]
    [double]$MinimumAvailableMemoryGiB = 3.0,
    [ValidateRange(1, 99)]
    [int]$MaximumMemoryUtilizationPercent = 82,
    [ValidateRange(0.1, 0.99)]
    [double]$MaximumDynamicPortUtilization = 0.80,
    [ValidateRange(1, 1440)]
    [int]$RecentPortExhaustionMinutes = 15,
    [switch]$AsJson
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Add-Check {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Passed,
        [Parameter(Mandatory)][string]$Details
    )

    $script:checks.Add([pscustomobject]@{
        name = $Name
        passed = $Passed
        details = $Details
    })
}

function Get-DynamicPortRange {
    param([Parameter(Mandatory)][ValidateSet('tcp', 'udp')][string]$Protocol)

    $lines = @(netsh int ipv4 show dynamicport $Protocol 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "netsh could not read the IPv4 $Protocol dynamic port range."
    }
    $values = @(
        $lines |
            Where-Object { $_ -match ':\s*(\d+)\s*$' } |
            ForEach-Object { [int]$Matches[1] }
    )
    if ($values.Count -lt 2 -or $values[1] -le 0) {
        throw "The IPv4 $Protocol dynamic port range could not be parsed."
    }
    return [pscustomobject]@{
        start = $values[0]
        count = $values[1]
    }
}

function Add-DynamicPortCheck {
    param([Parameter(Mandatory)][ValidateSet('tcp', 'udp')][string]$Protocol)

    try {
        $range = Get-DynamicPortRange -Protocol $Protocol
        $end = $range.start + $range.count - 1
        $ports = if ($Protocol -eq 'tcp') {
            @(Get-NetTCPConnection -ErrorAction Stop |
                    Where-Object { $_.LocalPort -ge $range.start -and $_.LocalPort -le $end } |
                    Select-Object -ExpandProperty LocalPort -Unique)
        }
        else {
            @(Get-NetUDPEndpoint -ErrorAction Stop |
                    Where-Object { $_.LocalPort -ge $range.start -and $_.LocalPort -le $end } |
                    Select-Object -ExpandProperty LocalPort -Unique)
        }
        $utilization = $ports.Count / $range.count
        Add-Check `
            -Name "$($Protocol.ToUpperInvariant()) dynamic port headroom" `
            -Passed ($utilization -lt $MaximumDynamicPortUtilization) `
            -Details ('{0}/{1} unique ports ({2:P1}), range={3}-{4}' -f
                $ports.Count,
                $range.count,
                $utilization,
                $range.start,
                $end)
    }
    catch {
        Add-Check `
            -Name "$($Protocol.ToUpperInvariant()) dynamic port headroom" `
            -Passed $false `
            -Details "Unable to inspect dynamic ports: $($_.Exception.Message)"
    }
}

$checks = [System.Collections.Generic.List[object]]::new()

$operatingSystem = Get-CimInstance Win32_OperatingSystem
$totalMemoryGiB = [math]::Round($operatingSystem.TotalVisibleMemorySize / 1MB, 2)
$availableMemoryGiB = [math]::Round($operatingSystem.FreePhysicalMemory / 1MB, 2)
$memoryUtilization = if ($totalMemoryGiB -le 0) {
    1.0
}
else {
    1.0 - ($availableMemoryGiB / $totalMemoryGiB)
}
$memoryPassed = $availableMemoryGiB -ge $MinimumAvailableMemoryGiB -and
    $memoryUtilization -lt ($MaximumMemoryUtilizationPercent / 100.0)
Add-Check `
    -Name 'Host memory headroom' `
    -Passed $memoryPassed `
    -Details ('available={0:N2} GiB, total={1:N2} GiB, used={2:P1}, requiredAvailable={3:N2} GiB, maximumUsed={4}%' -f
        $availableMemoryGiB,
        $totalMemoryGiB,
        $memoryUtilization,
        $MinimumAvailableMemoryGiB,
        $MaximumMemoryUtilizationPercent)

Add-DynamicPortCheck -Protocol tcp
Add-DynamicPortCheck -Protocol udp

$recentPortExhaustion = @()
$eventLogReadError = $null
try {
    $recentPortExhaustion = @(
        Get-WinEvent -FilterHashtable @{
            LogName = 'System'
            Id = 4231, 4266
            StartTime = (Get-Date).AddMinutes(-$RecentPortExhaustionMinutes)
        } -ErrorAction Stop
    )
}
catch {
    if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') {
        $eventLogReadError = $_.Exception.Message
    }
}

if (-not $eventLogReadError) {
    Add-Check `
        -Name 'No recent dynamic port exhaustion' `
        -Passed ($recentPortExhaustion.Count -eq 0) `
        -Details $(if ($recentPortExhaustion.Count -eq 0) {
            "No TCP 4231 or UDP 4266 event in the last $RecentPortExhaustionMinutes minute(s)"
        }
        else {
            "$($recentPortExhaustion.Count) event(s) in the last $RecentPortExhaustionMinutes minute(s)"
        })
}
else {
    Add-Check `
        -Name 'No recent dynamic port exhaustion' `
        -Passed $false `
        -Details "Unable to read the Windows System event log: $eventLogReadError"
}

if (-not $SkipDocker) {
    $dockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue
    $dockerReady = $null -ne $dockerCommand
    if ($dockerReady) {
        docker info *> $null
        $dockerReady = $LASTEXITCODE -eq 0
    }
    Add-Check `
        -Name 'Docker engine' `
        -Passed $dockerReady `
        -Details $(if ($dockerReady) { 'Ready' } else { 'Not ready' })

    if ($dockerReady -and $RequiredContainers.Count -gt 0) {
        $runningContainers = @(docker ps --format '{{.Names}}')
        $missingContainers = @(
            $RequiredContainers |
                Where-Object { $_ -notin $runningContainers }
        )
        Add-Check `
            -Name 'Required middleware containers' `
            -Passed ($missingContainers.Count -eq 0) `
            -Details $(if ($missingContainers.Count -eq 0) {
                "$($RequiredContainers.Count) required container(s) are running"
            }
            else {
                "Missing: $($missingContainers -join ', ')"
            })
    }
}

$result = [pscustomobject]@{
    schemaVersion = 1
    checkedAt = (Get-Date).ToUniversalTime().ToString('o')
    computerName = $env:COMPUTERNAME
    checks = @($checks)
    passed = $checks.Where({ -not $_.passed }).Count -eq 0
}

if ($AsJson) {
    $result | ConvertTo-Json -Depth 5
}
else {
    $checks | Format-Table -Wrap -AutoSize
}

if (-not $result.passed) {
    exit 1
}
