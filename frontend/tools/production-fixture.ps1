param(
    [ValidateSet("start", "status", "stop")]
    [string] $Action = "status",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"
$ports = @(18090, 18300, 18301)

function Get-PlainJournalProductionListeners {
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $ports } |
        Sort-Object LocalPort
}

function Wait-PlainJournalProductionUrl {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url,
        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            # The next bounded probe determines readiness.
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Url"
}

function Wait-PlainJournalProductionPortsReleased {
    param(
        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $listeners = Get-PlainJournalProductionListeners
        if (-not $listeners) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)

    $descriptions = $listeners |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
        Sort-Object -Unique
    throw "Production fixture ports were not released: $($descriptions -join ', ')"
}

function Stop-PlainJournalProductionListeners {
    $listeners = Get-PlainJournalProductionListeners
    foreach ($processId in ($listeners.OwningProcess | Sort-Object -Unique)) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
    Wait-PlainJournalProductionPortsReleased -TimeoutSeconds 5
}

function Start-PlainJournalProductionProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,
        [Parameter(Mandatory = $true)]
        [string[]] $ArgumentList,
        [Parameter(Mandatory = $true)]
        [string] $StandardOutputPath,
        [Parameter(Mandatory = $true)]
        [string] $StandardErrorPath
    )

    Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory (Get-Location) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $StandardOutputPath `
        -RedirectStandardError $StandardErrorPath
}

if ($Action -eq "status") {
    Get-PlainJournalProductionListeners |
        Select-Object LocalAddress, LocalPort, OwningProcess
    exit 0
}

if ($Action -eq "stop") {
    Stop-PlainJournalProductionListeners
    exit 0
}

$occupied = Get-PlainJournalProductionListeners
if ($occupied) {
    $descriptions = $occupied |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
        Sort-Object -Unique
    throw "Production fixture ports are already occupied: $($descriptions -join ', ')"
}

if (-not $SkipBuild) {
    & pnpm build
    if ($LASTEXITCODE -ne 0) {
        throw "Production build failed with exit code $LASTEXITCODE"
    }
}

try {
    $env:PLAIN_JOURNAL_MOCK_API_PORT = "18090"
    Start-PlainJournalProductionProcess `
        -FilePath "node.exe" `
        -ArgumentList @("e2e/mock-api.mjs") `
        -StandardOutputPath (Join-Path $env:TEMP "plainjournal-production-mock.log") `
        -StandardErrorPath (Join-Path $env:TEMP "plainjournal-production-mock.err.log")
    Wait-PlainJournalProductionUrl `
        -Url "http://127.0.0.1:18090/api/v1/identity/me" `
        -TimeoutSeconds 20

    $env:VITE_API_PROXY_TARGET = "http://127.0.0.1:18090"
    Start-PlainJournalProductionProcess `
        -FilePath "pnpm.cmd" `
        -ArgumentList @("preview:storefront") `
        -StandardOutputPath (Join-Path $env:TEMP "plainjournal-production-storefront.log") `
        -StandardErrorPath (Join-Path $env:TEMP "plainjournal-production-storefront.err.log")
    Wait-PlainJournalProductionUrl `
        -Url "http://127.0.0.1:18300/products/2079000000000000001" `
        -TimeoutSeconds 30

    Start-PlainJournalProductionProcess `
        -FilePath "pnpm.cmd" `
        -ArgumentList @("preview:admin") `
        -StandardOutputPath (Join-Path $env:TEMP "plainjournal-production-admin.log") `
        -StandardErrorPath (Join-Path $env:TEMP "plainjournal-production-admin.err.log")
    Wait-PlainJournalProductionUrl `
        -Url "http://127.0.0.1:18301/governance" `
        -TimeoutSeconds 30
} catch {
    Stop-PlainJournalProductionListeners
    throw
}

Get-PlainJournalProductionListeners |
    Select-Object LocalAddress, LocalPort, OwningProcess
