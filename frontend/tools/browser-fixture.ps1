param(
    [ValidateSet("start", "status", "stop")]
    [string] $Action = "status"
)

$ErrorActionPreference = "Stop"
$ports = @(18090, 18200)

function Get-PlainJournalBrowserListeners {
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $ports } |
        Sort-Object LocalPort
}

function Wait-PlainJournalBrowserUrl {
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

function Wait-PlainJournalBrowserPortsReleased {
    param(
        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $listeners = Get-PlainJournalBrowserListeners
        if (-not $listeners) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)

    $descriptions = $listeners |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
        Sort-Object -Unique
    throw "Browser fixture ports were not released: $($descriptions -join ', ')"
}

if ($Action -eq "status") {
    Get-PlainJournalBrowserListeners |
        Select-Object LocalAddress, LocalPort, OwningProcess
    exit 0
}

if ($Action -eq "stop") {
    $listeners = Get-PlainJournalBrowserListeners
    foreach ($processId in ($listeners.OwningProcess | Sort-Object -Unique)) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
    Wait-PlainJournalBrowserPortsReleased -TimeoutSeconds 5
    exit 0
}

$occupied = Get-PlainJournalBrowserListeners
if ($occupied) {
    $descriptions = $occupied |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" }
    throw "Browser fixture ports are already occupied: $($descriptions -join ', ')"
}

$env:PLAIN_JOURNAL_MOCK_API_PORT = "18090"
Start-Process `
    -FilePath "node.exe" `
    -ArgumentList @("e2e/mock-api.mjs") `
    -WorkingDirectory (Get-Location) `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $env:TEMP "plainjournal-browser-mock.log") `
    -RedirectStandardError (Join-Path $env:TEMP "plainjournal-browser-mock.err.log")
Wait-PlainJournalBrowserUrl `
    -Url "http://127.0.0.1:18090/api/v1/identity/me" `
    -TimeoutSeconds 20

$env:VITE_API_PROXY_TARGET = "http://127.0.0.1:18090"
Start-Process `
    -FilePath "pnpm.cmd" `
    -ArgumentList @("dev:storefront") `
    -WorkingDirectory (Get-Location) `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $env:TEMP "plainjournal-browser-storefront.log") `
    -RedirectStandardError (Join-Path $env:TEMP "plainjournal-browser-storefront.err.log")
Wait-PlainJournalBrowserUrl `
    -Url "http://127.0.0.1:18200/login" `
    -TimeoutSeconds 30

Get-PlainJournalBrowserListeners |
    Select-Object LocalAddress, LocalPort, OwningProcess
