#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$foundationScript = Join-Path $backendRoot 'run-foundation-smoke.ps1'

foreach ($command in @('docker', 'java')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is not available: $command"
    }
}

$composeFile = Join-Path (Split-Path -Parent $backendRoot) 'deploy\docker\compose.yml'
$environmentFile = Join-Path (Split-Path -Parent $backendRoot) 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $composeFile)) {
    throw "Missing Docker Compose file: $composeFile"
}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing deploy/docker/.env. Create it from .env.example and run the bootstrap script."
}

$arguments = @{}
if ($SkipNetworkPreflight) {
    $arguments.SkipNetworkPreflight = $true
}

Write-Host 'Starting PlainJournal Core Smoke.'
Write-Host 'Scope: core middleware and the Identity/Catalog/Inventory/Trade/Payment/Fulfillment/Marketing chain.'
Write-Host 'Full Lab options remain disabled; the underlying script retains cleanup ownership.'

& $foundationScript @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Core Smoke failed with exit code $LASTEXITCODE"
}
