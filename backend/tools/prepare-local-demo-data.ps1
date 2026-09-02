#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Seed', 'Verify', 'Remove')]
    [string]$Action = 'Verify',
    [string]$ManifestPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$baselineScript = Join-Path $PSScriptRoot 'prepare-m5-baseline-data.ps1'
if (-not (Test-Path -LiteralPath $baselineScript)) {
    throw "Missing deterministic fixture owner: $baselineScript"
}

if (-not $ManifestPath) {
    $ManifestPath = Join-Path $backendRoot '.run\local-demo-data.json'
}

$fixture = @{
    SpuCount = 12
    SkuPerSpu = 2
    UserCount = 4
    CartItemsPerUser = 2
    OrderCount = 12
    DenseUserOrderCount = 4
    StockPerSku = 100
    ManifestPath = $ManifestPath
}

& $baselineScript -Action $Action @fixture
