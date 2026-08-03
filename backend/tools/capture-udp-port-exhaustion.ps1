#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(5, 3600)]
    [int]$DurationSeconds = 300,

    [ValidateRange(200, 5000)]
    [int]$IntervalMilliseconds = 500,

    [string]$OutputDirectory,

    [switch]$ContinueAfterExhaustion
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$runToken = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDirectory = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
}
else {
    Join-Path $backendRoot ".run/udp-port-monitor-$runToken"
}

if (Test-Path -LiteralPath $runDirectory) {
    $existingItems = @(Get-ChildItem -LiteralPath $runDirectory -Force)
    if ($existingItems.Count -gt 0) {
        throw "Output directory is not empty: $runDirectory"
    }
}
else {
    [IO.Directory]::CreateDirectory($runDirectory) | Out-Null
}

$samplesPath = Join-Path $runDirectory 'udp-samples.ndjson'
$summaryPath = Join-Path $runDirectory 'summary.json'
$incidentPath = Join-Path $runDirectory 'incident.json'
$processNameCache = @{}

function Get-RecentPortExhaustionEvents {
    param([long]$AfterRecordId)

    return @(
        Get-WinEvent -FilterHashtable @{
            LogName = 'System'
            Id = 4231, 4266
            StartTime = (Get-Date).AddHours(-2)
        } -ErrorAction SilentlyContinue |
            Where-Object { $_.RecordId -gt $AfterRecordId } |
            Sort-Object RecordId
    )
}

function Resolve-ProcessName {
    param([Parameter(Mandatory)][int]$ProcessId)

    if ($processNameCache.ContainsKey($ProcessId)) {
        return $processNameCache[$ProcessId]
    }

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    $name = if ($process) { $process.ProcessName } else { 'exited' }
    $processNameCache[$ProcessId] = $name
    return $name
}

function Get-UdpSnapshot {
    $endpoints = @(Get-NetUDPEndpoint -ErrorAction SilentlyContinue)
    $dynamicEndpoints = @(
        $endpoints |
            Where-Object {
                $_.LocalPort -ge 49152 -and $_.LocalPort -le 65535
            }
    )
    $topOwners = @(
        $endpoints |
            Group-Object OwningProcess |
            Sort-Object Count -Descending |
            Select-Object -First 20 |
            ForEach-Object {
                $processId = [int]$_.Name
                [ordered]@{
                    processId = $processId
                    processName = Resolve-ProcessName -ProcessId $processId
                    endpointCount = $_.Count
                }
            }
    )

    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        endpointCount = $endpoints.Count
        dynamicEndpointCount = $dynamicEndpoints.Count
        dynamicUniquePortCount = @(
            $dynamicEndpoints |
                Select-Object -ExpandProperty LocalPort -Unique
        ).Count
        topOwners = $topOwners
        endpoints = $endpoints
    }
}

function Get-TcpSnapshot {
    $connections = @(Get-NetTCPConnection -ErrorAction SilentlyContinue)
    $dynamicConnections = @(
        $connections |
            Where-Object {
                $_.LocalPort -ge 49152 -and $_.LocalPort -le 65535
            }
    )
    $topOwners = @(
        $connections |
            Where-Object OwningProcess -gt 0 |
            Group-Object OwningProcess |
            Sort-Object Count -Descending |
            Select-Object -First 20 |
            ForEach-Object {
                $processId = [int]$_.Name
                [ordered]@{
                    processId = $processId
                    processName = Resolve-ProcessName -ProcessId $processId
                    connectionCount = $_.Count
                }
            }
    )
    $stateCounts = @(
        $connections |
            Group-Object State |
            Sort-Object Count -Descending |
            ForEach-Object {
                [ordered]@{
                    state = [string]$_.Name
                    count = $_.Count
                }
            }
    )

    return [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        connectionCount = $connections.Count
        dynamicConnectionCount = $dynamicConnections.Count
        dynamicUniquePortCount = @(
            $dynamicConnections |
                Select-Object -ExpandProperty LocalPort -Unique
        ).Count
        stateCounts = $stateCounts
        topOwners = $topOwners
        connections = $connections
    }
}

function Get-ProcessFacts {
    param([Parameter(Mandatory)][object[]]$Owners)

    $processIds = @(
        $Owners |
            ForEach-Object { [int]$_.processId } |
            Where-Object { $_ -gt 0 } |
            Select-Object -Unique
    )

    return @(
        foreach ($processId in $processIds) {
            $process = Get-CimInstance Win32_Process `
                -Filter "ProcessId=$processId" `
                -ErrorAction SilentlyContinue
            if ($process) {
                [ordered]@{
                    processId = [int]$process.ProcessId
                    parentProcessId = [int]$process.ParentProcessId
                    name = [string]$process.Name
                    executablePath = [string]$process.ExecutablePath
                    creationDate = $process.CreationDate
                }
            }
        }
    )
}

function Get-HnsFacts {
    $networks = @()
    $endpoints = @()

    if (Get-Command Get-HnsNetwork -ErrorAction SilentlyContinue) {
        $networks = @(
            Get-HnsNetwork -ErrorAction SilentlyContinue |
                Select-Object Name, Type, State, Health, Id
        )
    }
    if (Get-Command Get-HnsEndpoint -ErrorAction SilentlyContinue) {
        $endpoints = @(
            Get-HnsEndpoint -ErrorAction SilentlyContinue |
                Select-Object Name, IPAddress, State, VirtualNetwork, Id
        )
    }

    return [ordered]@{
        networks = $networks
        endpoints = $endpoints
    }
}

function Save-Incident {
    param(
        [Parameter(Mandatory)][object[]]$Events,
        [Parameter(Mandatory)][System.Collections.IDictionary]$UdpSnapshot,
        [Parameter(Mandatory)][System.Collections.IDictionary]$TcpSnapshot
    )

    $eventFacts = @(
        $Events |
            ForEach-Object {
                [ordered]@{
                    timeCreated = $_.TimeCreated.ToString('o')
                    recordId = [long]$_.RecordId
                    id = [int]$_.Id
                    providerName = [string]$_.ProviderName
                    message = [string]$_.Message
                }
            }
    )
    $udpEndpointFacts = @(
        $UdpSnapshot.endpoints |
            ForEach-Object {
                [ordered]@{
                    localAddress = [string]$_.LocalAddress
                    localPort = [int]$_.LocalPort
                    processId = [int]$_.OwningProcess
                    processName = Resolve-ProcessName `
                        -ProcessId ([int]$_.OwningProcess)
                }
            }
    )
    $tcpConnectionFacts = @(
        $TcpSnapshot.connections |
            ForEach-Object {
                [ordered]@{
                    localAddress = [string]$_.LocalAddress
                    localPort = [int]$_.LocalPort
                    remoteAddress = [string]$_.RemoteAddress
                    remotePort = [int]$_.RemotePort
                    state = [string]$_.State
                    processId = [int]$_.OwningProcess
                    processName = Resolve-ProcessName `
                        -ProcessId ([int]$_.OwningProcess)
                }
            }
    )
    $fseWarnings = @(
        Get-WinEvent -FilterHashtable @{
            LogName = 'Microsoft-Windows-Hyper-V-VmSwitch-Operational'
            StartTime = (Get-Date).AddMinutes(-10)
        } -ErrorAction SilentlyContinue |
            Where-Object { $_.Level -le 3 } |
            Select-Object TimeCreated, Id, LevelDisplayName, ProviderName, Message
    )

    $incident = [ordered]@{
        schemaVersion = 2
        capturedAt = (Get-Date).ToString('o')
        events = $eventFacts
        udp = [ordered]@{
            endpointCount = $UdpSnapshot.endpointCount
            dynamicEndpointCount = $UdpSnapshot.dynamicEndpointCount
            dynamicUniquePortCount = $UdpSnapshot.dynamicUniquePortCount
            topOwners = $UdpSnapshot.topOwners
            endpoints = $udpEndpointFacts
        }
        tcp = [ordered]@{
            connectionCount = $TcpSnapshot.connectionCount
            dynamicConnectionCount = $TcpSnapshot.dynamicConnectionCount
            dynamicUniquePortCount = $TcpSnapshot.dynamicUniquePortCount
            stateCounts = $TcpSnapshot.stateCounts
            topOwners = $TcpSnapshot.topOwners
            connections = $tcpConnectionFacts
        }
        processes = Get-ProcessFacts -Owners @(
            $UdpSnapshot.topOwners
            $TcpSnapshot.topOwners
        )
        compartments = @(
            Get-NetCompartment -ErrorAction SilentlyContinue |
                Select-Object CompartmentId, CompartmentDescription,
                    CompartmentGuid, NamespaceGuid
        )
        adapters = @(
            Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue |
                Select-Object Name, InterfaceDescription, Status,
                    ifIndex, LinkSpeed
        )
        hns = Get-HnsFacts
        fseWarnings = $fseWarnings
    }

    $incident |
        ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $incidentPath -Encoding utf8
}

$baselineEvents = @(
    Get-WinEvent -FilterHashtable @{
        LogName = 'System'
        Id = 4231, 4266
        StartTime = (Get-Date).AddHours(-2)
    } -ErrorAction SilentlyContinue
)
$baselineRecordId = if ($baselineEvents.Count -gt 0) {
    [long](($baselineEvents | Measure-Object RecordId -Maximum).Maximum)
}
else {
    0L
}
$lastRecordId = $baselineRecordId

$startedAt = Get-Date
$deadline = $startedAt.AddSeconds($DurationSeconds)
$peakEndpointCount = 0
$peakDynamicUniquePortCount = 0
$peakTcpConnectionCount = 0
$peakTcpDynamicUniquePortCount = 0
$sampleCount = 0
$triggeredEvents = [System.Collections.Generic.List[object]]::new()
$writer = [IO.StreamWriter]::new(
    $samplesPath,
    $false,
    [Text.UTF8Encoding]::new($false)
)

try {
    do {
        $iterationStarted = Get-Date
        $snapshot = Get-UdpSnapshot
        $tcpSnapshot = Get-TcpSnapshot
        $sampleCount++
        $peakEndpointCount = [Math]::Max(
            $peakEndpointCount,
            [int]$snapshot.endpointCount
        )
        $peakDynamicUniquePortCount = [Math]::Max(
            $peakDynamicUniquePortCount,
            [int]$snapshot.dynamicUniquePortCount
        )
        $peakTcpConnectionCount = [Math]::Max(
            $peakTcpConnectionCount,
            [int]$tcpSnapshot.connectionCount
        )
        $peakTcpDynamicUniquePortCount = [Math]::Max(
            $peakTcpDynamicUniquePortCount,
            [int]$tcpSnapshot.dynamicUniquePortCount
        )

        $sampleRecord = [ordered]@{
            capturedAt = $snapshot.capturedAt
            udp = [ordered]@{
                endpointCount = $snapshot.endpointCount
                dynamicEndpointCount = $snapshot.dynamicEndpointCount
                dynamicUniquePortCount = $snapshot.dynamicUniquePortCount
                topOwners = $snapshot.topOwners
            }
            tcp = [ordered]@{
                connectionCount = $tcpSnapshot.connectionCount
                dynamicConnectionCount = $tcpSnapshot.dynamicConnectionCount
                dynamicUniquePortCount = $tcpSnapshot.dynamicUniquePortCount
                stateCounts = $tcpSnapshot.stateCounts
                topOwners = $tcpSnapshot.topOwners
            }
        }
        $sampleJson = $sampleRecord | ConvertTo-Json -Compress -Depth 5
        $writer.WriteLine($sampleJson)
        $writer.Flush()

        $newEvents = @(
            Get-RecentPortExhaustionEvents -AfterRecordId $lastRecordId
        )
        if ($newEvents.Count -gt 0) {
            foreach ($event in $newEvents) {
                $triggeredEvents.Add($event)
            }
            $lastRecordId = [long](
                ($newEvents | Measure-Object RecordId -Maximum).Maximum
            )
            Save-Incident `
                -Events $newEvents `
                -UdpSnapshot $snapshot `
                -TcpSnapshot $tcpSnapshot
            if (-not $ContinueAfterExhaustion) {
                break
            }
        }

        $elapsedMilliseconds = ((Get-Date) - $iterationStarted).TotalMilliseconds
        $remainingMilliseconds = $IntervalMilliseconds - $elapsedMilliseconds
        if ($remainingMilliseconds -gt 0) {
            Start-Sleep -Milliseconds ([int]$remainingMilliseconds)
        }
    } while ((Get-Date) -lt $deadline)
}
finally {
    $writer.Dispose()
}

$completedAt = Get-Date
$summary = [ordered]@{
    schemaVersion = 2
    startedAt = $startedAt.ToString('o')
    completedAt = $completedAt.ToString('o')
    requestedDurationSeconds = $DurationSeconds
    actualDurationSeconds = [Math]::Round(
        ($completedAt - $startedAt).TotalSeconds,
        3
    )
    intervalMilliseconds = $IntervalMilliseconds
    sampleCount = $sampleCount
    peakUdpEndpointCount = $peakEndpointCount
    peakUdpDynamicUniquePortCount = $peakDynamicUniquePortCount
    peakTcpConnectionCount = $peakTcpConnectionCount
    peakTcpDynamicUniquePortCount = $peakTcpDynamicUniquePortCount
    triggered = $triggeredEvents.Count -gt 0
    triggeredEventCount = $triggeredEvents.Count
    baselineRecordId = $baselineRecordId
    finalRecordId = $lastRecordId
    samplesFile = $samplesPath
    incidentFile = if ($triggeredEvents.Count -gt 0) {
        $incidentPath
    }
    else {
        $null
    }
}
$summary |
    ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath $summaryPath -Encoding utf8

$summary | ConvertTo-Json -Depth 5
