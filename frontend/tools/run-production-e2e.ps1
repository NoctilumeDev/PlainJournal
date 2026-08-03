param(
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"
$fixtureStarted = $false

try {
    if (-not $SkipBuild) {
        & pnpm build
        if ($LASTEXITCODE -ne 0) {
            throw "Production build failed with exit code $LASTEXITCODE"
        }
    }

    & powershell.exe `
        -NoProfile `
        -ExecutionPolicy Bypass `
        -File tools/production-fixture.ps1 `
        -Action start `
        -SkipBuild
    if ($LASTEXITCODE -ne 0) {
        throw "Production fixture failed with exit code $LASTEXITCODE"
    }
    $fixtureStarted = $true

    & pnpm exec playwright test "--config=playwright.v7-3.config.ts"
    if ($LASTEXITCODE -ne 0) {
        throw "Production Playwright failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($fixtureStarted) {
        & powershell.exe `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File tools/production-fixture.ps1 `
            -Action stop
    }
}
