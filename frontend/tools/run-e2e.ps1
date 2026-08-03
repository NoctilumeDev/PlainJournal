param(
    [string] $ConfigPath = "playwright.config.ts",
    [switch] $StorefrontOnly,
    [switch] $AdminOnly
)

$ErrorActionPreference = "Stop"

if ($StorefrontOnly -and $AdminOnly) {
    throw "StorefrontOnly and AdminOnly cannot be enabled together."
}

$ports = if ($StorefrontOnly) {
    @(18090, 18200)
} elseif ($AdminOnly) {
    @(18090, 18201)
} else {
    @(18090, 18200, 18201)
}
$occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in $ports }
if ($occupied) {
    $descriptions = $occupied |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
        Sort-Object -Unique
    throw "Browser test ports are already occupied: $($descriptions -join ', ')"
}

$runName = [IO.Path]::GetFileNameWithoutExtension($ConfigPath)
$mockLog = Join-Path $env:TEMP "plainjournal-$runName-mock.log"
$mockErrorLog = Join-Path $env:TEMP "plainjournal-$runName-mock.err.log"
$storefrontLog = Join-Path $env:TEMP "plainjournal-$runName-storefront.log"
$storefrontErrorLog = Join-Path $env:TEMP "plainjournal-$runName-storefront.err.log"
$adminLog = Join-Path $env:TEMP "plainjournal-$runName-admin.log"
$adminErrorLog = Join-Path $env:TEMP "plainjournal-$runName-admin.err.log"
$startedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Wait-PlainJournalHttp {
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
            # The next bounded probe determines whether the service is ready.
        }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Url"
}

function Wait-PlainJournalPortsReleased {
    param(
        [Parameter(Mandatory = $true)]
        [int] $TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $listeners = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in $ports }
        if (-not $listeners) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)

    $descriptions = $listeners |
        ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
        Sort-Object -Unique
    throw "Browser test ports were not released: $($descriptions -join ', ')"
}

function Start-PlainJournalProcess {
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

    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory (Get-Location) `
        -WindowStyle Hidden `
        -RedirectStandardOutput $StandardOutputPath `
        -RedirectStandardError $StandardErrorPath `
        -PassThru
    $startedProcesses.Add($process)
}

try {
    $env:PLAIN_JOURNAL_MOCK_API_PORT = "18090"
    Start-PlainJournalProcess `
        -FilePath "node.exe" `
        -ArgumentList @("e2e/mock-api.mjs") `
        -StandardOutputPath $mockLog `
        -StandardErrorPath $mockErrorLog
    Wait-PlainJournalHttp `
        -Url "http://127.0.0.1:18090/api/v1/identity/me" `
        -TimeoutSeconds 20

    $env:VITE_API_PROXY_TARGET = "http://127.0.0.1:18090"
    if (-not $AdminOnly) {
        Start-PlainJournalProcess `
            -FilePath "pnpm.cmd" `
            -ArgumentList @("dev:storefront") `
            -StandardOutputPath $storefrontLog `
            -StandardErrorPath $storefrontErrorLog
        Wait-PlainJournalHttp `
            -Url "http://127.0.0.1:18200/login" `
            -TimeoutSeconds 30
    }

    if (-not $StorefrontOnly) {
        Start-PlainJournalProcess `
            -FilePath "pnpm.cmd" `
            -ArgumentList @("dev:admin") `
            -StandardOutputPath $adminLog `
            -StandardErrorPath $adminErrorLog
        Wait-PlainJournalHttp `
            -Url "http://127.0.0.1:18201/login" `
            -TimeoutSeconds 30
    }

    & pnpm exec playwright test "--config=$ConfigPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Playwright failed with exit code $LASTEXITCODE"
    }
} finally {
    foreach ($process in $startedProcesses) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }

    Start-Sleep -Milliseconds 500
    $listeners = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $ports }
    foreach ($processId in ($listeners.OwningProcess | Sort-Object -Unique)) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    }
}

Wait-PlainJournalPortsReleased -TimeoutSeconds 5
