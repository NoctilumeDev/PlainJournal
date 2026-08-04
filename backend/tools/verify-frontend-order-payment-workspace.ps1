#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipPackage,
    [ValidateSet('Payment', 'Fulfillment')]
    [string]$Scenario = 'Payment',
    [ValidateRange(90, 600)]
    [int]$BrowserHoldSeconds = 300,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:frontendRoot = Join-Path $script:repositoryRoot 'frontend'
$script:scenarioKey = $Scenario.ToLowerInvariant()
$script:runIdPrefix = if ($Scenario -eq 'Fulfillment') { 'fof' } else { 'fop' }
$script:runId = "$($script:runIdPrefix)-$(([Guid]::NewGuid().ToString('N')).Substring(0, 12))"
$script:tradeLeaseNamespace = $script:runId
$script:processes = [ordered]@{}
$script:ports = [ordered]@{
    gateway = 18000
    identity = 18101
    catalog = 18102
    inventory = 18103
    trade = 18104
    payment = 18105
    fulfillment = 18106
    marketing = 18107
    storefront = 18200
    responseLossProxy = if ($Scenario -eq 'Fulfillment') { 18602 } else { 18601 }
}

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        [Environment]::SetEnvironmentVariable(
            $trimmed.Substring(0, $separator).Trim(),
            $trimmed.Substring($separator + 1).Trim(),
            'Process')
    }
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)
    if ($listener.Count -gt 0) {
        throw "Port $Port is already in use by PID $($listener[0].OwningProcess)."
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$ProcessName,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'no response'
    do {
        $managed = $script:processes[$ProcessName]
        if ($managed.process.HasExited) {
            throw "$ProcessName exited before $Uri became ready. " +
                "ExitCode=$($managed.process.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
            $lastState = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastState = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri. Last state: $lastState"
}

function Start-ManagedProcess {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][hashtable]$Environment,
        [Parameter(Mandatory)][string]$CommandMarker,
        [Parameter(Mandatory)][int]$Port
    )

    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] = [Environment]::GetEnvironmentVariable(
            $entry.Key,
            'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
    }
    try {
        $process = Start-Process -FilePath $FilePath `
            -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            marker = $CommandMarker
            port = $Port
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Start-JavaApplication {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][string]$PortVariable,
        [Parameter(Mandatory)][int]$Port,
        [hashtable]$AdditionalEnvironment = @{}
    )

    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
        throw "Missing application artifact: $Jar"
    }
    $environment = @{
        APP_ENV = $script:runId
        SERVICE_INSTANCE_ID = "$($script:runId)-$Name"
        SERVICE_RELEASE_ID = "frontend-order-$($script:scenarioKey)-v1"
        SERVICE_IP = '127.0.0.1'
        OTLP_TRACING_EXPORT_ENABLED = 'false'
        TRACING_SAMPLING_PROBABILITY = '0'
    }
    $environment[$PortVariable] = [string]$Port
    foreach ($entry in $AdditionalEnvironment.GetEnumerator()) {
        $environment[$entry.Key] = $entry.Value
    }
    Start-ManagedProcess -Name $Name -FilePath $script:javaPath `
        -Arguments @(
            '-Xms64m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-jar',
            $Jar
        ) `
        -WorkingDirectory $script:backendRoot `
        -Environment $environment `
        -CommandMarker ([IO.Path]::GetFileName($Jar)) `
        -Port $Port
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$Port/actuator/health/liveness" `
        -ProcessName $Name
    Write-Output "APPLICATION_READY=$Name|$Port|$($script:processes[$Name].process.Id)"
}

function Stop-ManagedProcess {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $script:processes.Contains($Name)) {
        return
    }
    $managed = $script:processes[$Name]
    $processId = [int]$managed.process.Id
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
        -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return
    }
    if ($process.CommandLine -notlike "*$($managed.marker)*" -or
            $process.CommandLine -notlike "*$($script:repositoryRoot)*") {
        throw "Refused to stop PID $processId; command line no longer matches $Name."
    }
    Stop-Process -Id $processId -Force -ErrorAction Stop
    Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
}

function Remove-TradeLease {
    if (-not $env:TRADE_DB_USER -or -not $env:TRADE_DB_PASSWORD -or
            -not $env:TRADE_DB_NAME) {
        return
    }
    docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
        -e "DELETE FROM distributed_id_worker_lease
            WHERE namespace = '$($script:tradeLeaseNamespace)';" *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to remove the scoped Trade distributed-ID lease.'
    }
}

$script:runDirectory = if ($OutputDirectory) {
    [IO.Path]::GetFullPath($OutputDirectory)
}
else {
    $defaultRunName = if ($Scenario -eq 'Fulfillment') {
        'frontend-order-fulfillment-eighth-20260730'
    }
    else {
        'frontend-order-payment-seventh-20260730'
    }
    Join-Path $script:backendRoot ".run/$defaultRunName"
}
[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null

$networkPreflight = 'D:\DevTools\Network\check-dev-network.ps1'
$envPath = Join-Path $script:repositoryRoot 'deploy/docker/.env'
$bootstrapPath = Join-Path $script:repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
$scenarioVerification = if ($Scenario -eq 'Fulfillment') {
    Join-Path $script:backendRoot 'verify-m4-fulfillment-timeline.ps1'
}
else {
    Join-Path $script:backendRoot 'verify-m4-payment-recovery.ps1'
}
$scenarioAction = if ($Scenario -eq 'Fulfillment') {
    'fulfillment-confirm'
}
else {
    'payment-create'
}
$armFile = Join-Path $script:runDirectory "$scenarioAction.arm"
$proxyEvidenceFile = Join-Path $script:runDirectory "$scenarioAction-proxy-evidence.json"
$browserFixtureFile = Join-Path $script:runDirectory 'browser-fixture.json'
$browserContinueFile = Join-Path $script:runDirectory 'browser-continue.signal'
$scenarioOutputFile = Join-Path $script:runDirectory "$($script:scenarioKey)-verification.out.log"
$jars = [ordered]@{
    identity = Join-Path $script:backendRoot `
        'services/identity-service/target/identity-service-1.0.2-SNAPSHOT.jar'
    catalog = Join-Path $script:backendRoot `
        'services/catalog-service/target/catalog-service-1.0.2-SNAPSHOT.jar'
    inventory = Join-Path $script:backendRoot `
        'services/inventory-service/target/inventory-service-1.0.2-SNAPSHOT.jar'
    marketing = Join-Path $script:backendRoot `
        'services/marketing-service/target/marketing-service-1.0.2-SNAPSHOT.jar'
    trade = Join-Path $script:backendRoot `
        'services/trade-service/target/trade-service-1.0.2-SNAPSHOT.jar'
    payment = Join-Path $script:backendRoot `
        'services/payment-service/target/payment-service-1.0.2-SNAPSHOT.jar'
    fulfillment = Join-Path $script:backendRoot `
        'services/fulfillment-service/target/fulfillment-service-1.0.2-SNAPSHOT.jar'
    gateway = Join-Path $script:backendRoot `
        'ecommerce-gateway/target/ecommerce-gateway-1.0.2-SNAPSHOT.jar'
}
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$script:javaPath = if ($javaHome -and
        (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe'))) {
    (Resolve-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe')).Path
}
else {
    (Get-Command java -ErrorAction Stop).Source
}
$script:nodePath = (Get-Command node -ErrorAction Stop).Source
$viteCandidates = @(Get-ChildItem `
        -Path (Join-Path $script:frontendRoot 'node_modules/.pnpm') `
        -Filter vite.js `
        -Recurse `
        -File `
        -ErrorAction Stop |
        Where-Object {
            $_.FullName -match '[\\/]node_modules[\\/]vite[\\/]bin[\\/]vite\.js$'
        })
if ($viteCandidates.Count -ne 1) {
    throw "Expected one Vite entry script, found $($viteCandidates.Count)."
}
$viteScript = $viteCandidates[0].FullName
$verificationError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()

try {
    if (-not $SkipNetworkPreflight) {
        & $networkPreflight
        if ($LASTEXITCODE -ne 0) {
            throw 'Network preflight failed.'
        }
    }
    & $bootstrapPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Local resource bootstrap failed.'
    }
    Import-DotEnv -Path $envPath
    foreach ($port in $script:ports.Values) {
        Assert-PortAvailable -Port $port
    }

    if (-not $SkipPackage) {
        Push-Location $script:backendRoot
        try {
            & mvn -pl (
                'ecommerce-gateway,services/identity-service,services/catalog-service,' +
                'services/inventory-service,services/trade-service,' +
                'services/payment-service,services/fulfillment-service,' +
                'services/marketing-service') -am -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw 'Maven packaging failed.'
            }
        }
        finally {
            Pop-Location
        }
    }

    Start-JavaApplication -Name identity -Jar $jars.identity `
        -PortVariable IDENTITY_SERVICE_PORT -Port $script:ports.identity
    Start-JavaApplication -Name catalog -Jar $jars.catalog `
        -PortVariable CATALOG_SERVICE_PORT -Port $script:ports.catalog
    Start-JavaApplication -Name inventory -Jar $jars.inventory `
        -PortVariable INVENTORY_SERVICE_PORT -Port $script:ports.inventory
    Start-JavaApplication -Name marketing -Jar $jars.marketing `
        -PortVariable MARKETING_SERVICE_PORT -Port $script:ports.marketing
    Start-JavaApplication -Name trade -Jar $jars.trade `
        -PortVariable TRADE_SERVICE_PORT -Port $script:ports.trade `
        -AdditionalEnvironment @{
        TRADE_DISTRIBUTED_ID_NAMESPACE = $script:tradeLeaseNamespace
    }
    Start-JavaApplication -Name payment -Jar $jars.payment `
        -PortVariable PAYMENT_SERVICE_PORT -Port $script:ports.payment
    Start-JavaApplication -Name fulfillment -Jar $jars.fulfillment `
        -PortVariable FULFILLMENT_SERVICE_PORT -Port $script:ports.fulfillment
    Start-JavaApplication -Name gateway -Jar $jars.gateway `
        -PortVariable GATEWAY_PORT -Port $script:ports.gateway

    Start-ManagedProcess -Name storefront -FilePath $script:nodePath `
        -Arguments @(
            $viteScript,
            '--host',
            '127.0.0.1',
            '--port',
            [string]$script:ports.storefront
        ) `
        -WorkingDirectory (Join-Path $script:frontendRoot 'storefront-web') `
        -Environment @{
        VITE_API_PROXY_TARGET = "http://127.0.0.1:$($script:ports.responseLossProxy)"
    } `
        -CommandMarker 'vite.js' `
        -Port $script:ports.storefront
    Wait-HttpOk -Uri "http://127.0.0.1:$($script:ports.storefront)" `
        -ProcessName storefront
    Write-Output "WORKSPACE_READY=http://127.0.0.1:$($script:ports.storefront)"

    $scenarioOutput = if ($Scenario -eq 'Fulfillment') {
        @(& $scenarioVerification `
                -BrowserConfirmsReceipt `
                -BrowserHoldSeconds $BrowserHoldSeconds `
                -ProxyPort $script:ports.responseLossProxy `
                -ArmFile $armFile `
                -ProxyEvidenceFile $proxyEvidenceFile `
                -BrowserFixtureFile $browserFixtureFile `
                -BrowserContinueFile $browserContinueFile)
    }
    else {
        @(& $scenarioVerification `
                -BrowserCreatesPayment `
                -BrowserHoldSeconds $BrowserHoldSeconds `
                -ProxyPort $script:ports.responseLossProxy `
                -ArmFile $armFile `
                -ProxyEvidenceFile $proxyEvidenceFile `
                -BrowserFixtureFile $browserFixtureFile `
                -BrowserContinueFile $browserContinueFile)
    }
    if (($scenarioOutput -join "`n") -match '"password"\s*:') {
        throw "$Scenario verification output contains a browser password field."
    }
    $scenarioOutput | Set-Content -LiteralPath $scenarioOutputFile -Encoding utf8
    $scenarioOutput | Write-Output
}
catch {
    $verificationError = $_
}
finally {
    foreach ($name in @(
            'storefront',
            'gateway',
            'fulfillment',
            'payment',
            'trade',
            'marketing',
            'inventory',
            'catalog',
            'identity')) {
        try {
            Stop-ManagedProcess -Name $name
        }
        catch {
            $cleanupErrors.Add("${name}: $($_.Exception.Message)")
        }
    }
    try {
        Remove-TradeLease
    }
    catch {
        $cleanupErrors.Add("trade lease: $($_.Exception.Message)")
    }
}

if ($verificationError) {
    throw $verificationError
}
if ($cleanupErrors.Count -gt 0) {
    throw "Frontend order/$script:scenarioKey workspace cleanup failed: " +
        ($cleanupErrors -join ' | ')
}

[ordered]@{
    schemaVersion = 1
    runId = $script:runId
    scenario = $Scenario
    storefrontUrl = "http://127.0.0.1:$($script:ports.storefront)"
    responseLossProxyPort = $script:ports.responseLossProxy
    applications = @($script:ports.Keys | Where-Object {
            $_ -notin @('storefront', 'responseLossProxy')
        })
    resourceBoundary = [ordered]@{
        javaHeapMin = '64m'
        javaHeapMax = '256m'
        activeProcessorCount = 4
        serialWorkspace = $true
    }
    responseLossEvidenceFile = $proxyEvidenceFile
} | ConvertTo-Json -Depth 5 |
    Set-Content -LiteralPath (
        Join-Path $script:runDirectory 'workspace-verification.json') -Encoding utf8
