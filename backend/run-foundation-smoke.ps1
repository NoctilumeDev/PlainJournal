#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$EnableRedisFaultInjection,
    [switch]$EnableObservability,
    [switch]$EnableDistributedTracing,
    [switch]$EnableSynchronousResilienceFaultInjection,
    [switch]$EnableTradeMarketingResilienceFaultInjection,
    [switch]$EnableInventoryReservationResponseLossFaultInjection,
    [switch]$EnablePaymentInventoryConfirmationFaultInjection,
    [switch]$EnableExceptionalPaymentRecoveryVerification,
    [switch]$EnableCapacityBaseline,
    [ValidateRange(100, 100000)][int]$CapacityRequests = 1000,
    [ValidateRange(1, 1000)][int]$CapacityConcurrency = 100,
    [ValidateRange(2, 10000)][int]$CapacityInventorySuccesses = 100,
    [ValidateRange(1, 10000)][int]$CapacityTradeSuccesses = 100
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                return $response
            }
        }
        catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Milliseconds 750
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Uri. Last error: $lastError"
}

function Wait-TempoTrace {
    param(
        [Parameter(Mandatory)][string]$TraceId,
        [Parameter(Mandatory)][int]$Port,
        [string]$EventType = 'PaymentSucceeded',
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'trace not queryable'
    do {
        try {
            $trace = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/traces/$TraceId" -TimeoutSec 5
            $services = @()
            $spanNames = @()
            foreach ($batch in @($trace.batches)) {
                $serviceAttribute = @($batch.resource.attributes |
                        Where-Object key -eq 'service.name' | Select-Object -First 1)
                if ($serviceAttribute.Count -eq 1) {
                    $services += $serviceAttribute[0].value.stringValue
                }
                foreach ($scope in @($batch.scopeSpans)) {
                    $spanNames += @($scope.spans | ForEach-Object name)
                }
            }
            $lastState = "services=$($services -join ','), spans=$($spanNames -join ',')"
            if ($services -contains 'payment-service' -and
                $services -contains 'trade-service' -and
                $spanNames -contains "rocketmq publish $EventType" -and
                $spanNames -contains "rocketmq consume $EventType") {
                return [pscustomobject]@{
                    Services = @($services | Sort-Object -Unique)
                    SpanNames = @($spanNames | Sort-Object -Unique)
                }
            }
        }
        catch {
            $lastState = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw "Tempo did not expose the expected $EventType HTTP -> Outbox -> RocketMQ -> Trade trace. Last state: $lastState"
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Wait-PortAvailable {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    Assert-PortAvailable -Port $Port
}

function Wait-DistributedIdWorkerLeaseExpiry {
    param(
        [Parameter(Mandatory)]
        [ValidatePattern('^[A-Za-z0-9._:-]{1,64}$')]
        [string]$Namespace,
        [Parameter(Mandatory)]
        [ValidateRange(0, 1023)]
        [int]$WorkerId,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $available = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e @"
SELECT CASE
         WHEN COUNT(*) = 0 OR MAX(lease_until) <= CURRENT_TIMESTAMP(3) THEN 1
         ELSE 0
       END
FROM distributed_id_worker_lease
WHERE namespace = '$Namespace' AND worker_id = $WorkerId;
"@ 2>$null
        if ($LASTEXITCODE -eq 0 -and ($available | Select-Object -Last 1) -eq '1') {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for the crashed Trade worker lease to expire: namespace=$Namespace, workerId=$WorkerId."
}

function Show-LogTail {
    param([Parameter(Mandatory)][string]$Path)

    if (Test-Path -LiteralPath $Path) {
        Write-Host "--- $Path ---"
        Get-Content -LiteralPath $Path -Tail 60
    }
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Compress -Depth 10) -TimeoutSec 10
}

function Invoke-JsonPostRaw {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-WebRequest -Method Post -Uri $Uri -ContentType 'application/json' `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Compress -Depth 10) -TimeoutSec 10 -SkipHttpErrorCheck
}

function Get-MySqlSingleColumn {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Query
    )

    $values = @($Query | docker exec -i -e "MYSQL_PWD=$Password" plainjournal-mysql `
            mysql "-u$User" $Database -N -B 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL query failed for database '$Database'."
    }
    return @($values | Where-Object { $_ -and $_.Trim() } |
            ForEach-Object { $_.Trim() })
}

function Assert-JsonPostRejected {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [Parameter(Mandatory)][int]$ExpectedStatus
    )

    $response = Invoke-JsonPostRaw -Uri $Uri -Body $Body
    $actualStatus = [int]$response.StatusCode

    if ($actualStatus -ne $ExpectedStatus) {
        throw "Expected HTTP $ExpectedStatus from $Uri, received $actualStatus."
    }
}

function Get-Sha256Hex {
    param([Parameter(Mandatory)][string]$Value)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-HmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Secret
    )

    $algorithm = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-LatencySummary {
    param(
        [Parameter(Mandatory)][double[]]$Values,
        [Parameter(Mandatory)][TimeSpan]$Elapsed,
        [Parameter(Mandatory)][int]$Concurrency
    )

    if ($Values.Count -eq 0 -or $Elapsed.TotalSeconds -le 0) {
        throw 'Latency summary requires at least one measurement and a positive elapsed time.'
    }
    $sorted = @($Values | Sort-Object)
    $percentile = {
        param([double]$Ratio)
        $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $Ratio) - 1)
        return [Math]::Round($sorted[$index], 2)
    }
    return [pscustomobject]@{
        requests = $sorted.Count
        concurrency = $Concurrency
        elapsedSeconds = [Math]::Round($Elapsed.TotalSeconds, 2)
        requestsPerSecond = [Math]::Round($sorted.Count / $Elapsed.TotalSeconds, 2)
        minMs = [Math]::Round($sorted[0], 2)
        p50Ms = & $percentile 0.50
        p95Ms = & $percentile 0.95
        p99Ms = & $percentile 0.99
        maxMs = [Math]::Round($sorted[-1], 2)
    }
}

function Remove-RedisKeys {
    param([Parameter(Mandatory)][string[]]$Keys)

    if ($Keys.Count -eq 0) {
        return
    }
    docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis redis-cli DEL $Keys | Out-Null
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Container 2>$null
        if ($status -eq 'healthy' -or $status -eq 'running') {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for container $Container"
}

function Stop-FoundationServices {
    param([Parameter(Mandatory)][int[]]$Ports)

    $processIds = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object LocalPort -in $Ports |
        Select-Object -ExpandProperty OwningProcess -Unique

    foreach ($processId in $processIds) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }

        $isFoundationService = $process.CommandLine -like '*ecommerce-gateway-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*identity-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*catalog-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*inventory-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*trade-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*payment-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*fulfillment-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*marketing-service-0.1.0-SNAPSHOT.jar*'
        if ($isFoundationService) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $processId -Timeout 5 -ErrorAction SilentlyContinue
        }
    }
}

function Get-NacosApiHeaders {
    $login = Invoke-RestMethod `
        -Method Post `
        -Uri 'http://127.0.0.1:8848/nacos/v3/auth/user/login' `
        -Body @{
            username = 'nacos'
            password = $env:NACOS_ADMIN_PASSWORD
        } `
        -TimeoutSec 10
    return @{ Authorization = "Bearer $($login.accessToken)" }
}

function Test-NacosServiceExists {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $encodedServiceName = [Uri]::EscapeDataString($ServiceName)
    $response = Invoke-WebRequest `
        -Method Get `
        -Uri ('http://127.0.0.1:18080/v3/console/ns/service' +
            "?namespaceId=public&groupName=ECOMMERCE&serviceName=$encodedServiceName") `
        -Headers $Headers `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
    if ($response.StatusCode -eq 404) {
        return $false
    }
    if ($response.StatusCode -ne 200) {
        throw "Nacos service query failed for ${ServiceName}: HTTP $($response.StatusCode) $($response.Content)"
    }

    $payload = $response.Content | ConvertFrom-Json
    if ($payload.code -ne 0) {
        throw "Nacos service query failed for ${ServiceName}: code=$($payload.code) message=$($payload.message)"
    }
    return $true
}

function New-NacosService {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName
    )

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri 'http://127.0.0.1:18080/v3/console/ns/service' `
        -Headers $Headers `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            namespaceId = 'public'
            groupName = 'ECOMMERCE'
            serviceName = $ServiceName
            protectThreshold = '0'
            ephemeral = 'false'
        } `
        -TimeoutSec 10
    $accepted = $response.data -eq $true -or [string]$response.data -eq 'ok'
    if ($response.code -ne 0 -or -not $accepted) {
        throw "Nacos service creation failed: code=$($response.code) message=$($response.message)"
    }
}

function Remove-NacosService {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName,
        [int]$TimeoutSeconds = 15
    )

    $encodedServiceName = [Uri]::EscapeDataString($ServiceName)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = 'service remained visible'
    do {
        if (-not (Test-NacosServiceExists -Headers $Headers -ServiceName $ServiceName)) {
            return
        }
        try {
            $response = Invoke-RestMethod `
                -Method Delete `
                -Uri ('http://127.0.0.1:18080/v3/console/ns/service' +
                    "?namespaceId=public&groupName=ECOMMERCE&serviceName=$encodedServiceName") `
                -Headers $Headers `
                -TimeoutSec 10
            $accepted = $response.data -eq $true -or [string]$response.data -eq 'ok'
            if ($response.code -ne 0 -or -not $accepted) {
                $lastError = "code=$($response.code) message=$($response.message)"
            }
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Nacos service removal failed for ${ServiceName}: $lastError"
}

function Register-NacosFixedInstance {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName,
        [Parameter(Mandatory)][string]$Ip,
        [Parameter(Mandatory)][int]$Port
    )

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri 'http://127.0.0.1:8848/nacos/v3/admin/ns/instance' `
        -Headers $Headers `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            namespaceId = 'public'
            groupName = 'ECOMMERCE'
            serviceName = $ServiceName
            clusterName = 'DEFAULT'
            ip = $Ip
            port = $Port
            weight = '1.0'
            healthy = 'true'
            enabled = 'true'
            ephemeral = 'false'
            metadata = '{"instance-id":"inventory-response-loss-proxy","release-id":"m3-response-loss"}'
        } `
        -TimeoutSec 10
    $accepted = $response.data -eq $true -or [string]$response.data -eq 'ok'
    if ($response.code -ne 0 -or -not $accepted) {
        throw "Nacos fixed instance registration failed: code=$($response.code) message=$($response.message)"
    }
}

function Remove-NacosFixedInstance {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName,
        [Parameter(Mandatory)][string]$Ip,
        [Parameter(Mandatory)][int]$Port
    )

    $response = Invoke-RestMethod `
        -Method Delete `
        -Uri 'http://127.0.0.1:8848/nacos/v3/admin/ns/instance' `
        -Headers $Headers `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            namespaceId = 'public'
            groupName = 'ECOMMERCE'
            serviceName = $ServiceName
            clusterName = 'DEFAULT'
            ip = $Ip
            port = $Port
            ephemeral = 'false'
        } `
        -TimeoutSec 10
    $accepted = $response.data -eq $true -or [string]$response.data -eq 'ok'
    if ($response.code -ne 0 -or -not $accepted) {
        throw "Nacos fixed instance removal failed: code=$($response.code) message=$($response.message)"
    }
}

function Wait-NacosFixedInstance {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$ServiceName,
        [Parameter(Mandatory)][string]$Ip,
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-RestMethod `
                -Uri ('http://127.0.0.1:18080/v3/console/ns/instance/list' +
                    "?serviceName=$ServiceName&groupName=ECOMMERCE&pageNo=1&pageSize=20") `
                -Headers $Headers `
                -TimeoutSec 5
            $matching = @($response.data.pageItems | Where-Object {
                    $_.ip -eq $Ip -and [int]$_.port -eq $Port -and $_.enabled -eq $true
                })
            if ($response.code -eq 0 -and $matching.Count -eq 1) {
                return
            }
        }
        catch {
            # The service may not be queryable until the fixed instance is visible.
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Nacos did not expose fixed instance $ServiceName at ${Ip}:$Port."
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$inventoryCompetitionAttempts = if ($EnableCapacityBaseline) { $CapacityRequests } else { 100 }
$inventoryCompetitionSuccesses = if ($EnableCapacityBaseline) { $CapacityInventorySuccesses } else { 20 }
$inventoryCompetitionConcurrency = if ($EnableCapacityBaseline) { $CapacityConcurrency } else { 25 }
$tradeCompetitionAttempts = if ($EnableCapacityBaseline) { $CapacityRequests } else { 30 }
$tradeCompetitionSuccesses = if ($EnableCapacityBaseline) { $CapacityTradeSuccesses } else { 5 }
$tradeCompetitionConcurrency = if ($EnableCapacityBaseline) { $CapacityConcurrency } else { 15 }
$tradeStockBaseline = $tradeCompetitionSuccesses + 1
if ($inventoryCompetitionSuccesses -ge $inventoryCompetitionAttempts) {
    throw 'CapacityInventorySuccesses must be lower than CapacityRequests so oversell rejection is exercised.'
}
if ($tradeCompetitionSuccesses -ge $tradeCompetitionAttempts) {
    throw 'CapacityTradeSuccesses must be lower than CapacityRequests so out-of-stock closure is exercised.'
}
if ($EnableInventoryReservationResponseLossFaultInjection -and $EnableCapacityBaseline) {
    throw 'Inventory response-loss fault injection and the capacity baseline must run separately.'
}
if ($EnablePaymentInventoryConfirmationFaultInjection -and $EnableCapacityBaseline) {
    throw 'Payment inventory-confirmation fault injection and the capacity baseline must run separately.'
}
if ($EnableExceptionalPaymentRecoveryVerification -and $EnableCapacityBaseline) {
    throw 'Exceptional-payment recovery verification and the capacity baseline must run separately.'
}
if ($EnableExceptionalPaymentRecoveryVerification -and
    $EnablePaymentInventoryConfirmationFaultInjection) {
    throw 'Exceptional-payment recovery and payment inventory-confirmation fault injection must run separately.'
}
if ($EnableExceptionalPaymentRecoveryVerification -and
    ($EnableSynchronousResilienceFaultInjection -or
        $EnableTradeMarketingResilienceFaultInjection)) {
    throw 'Exceptional-payment recovery and Trade process fault injection scenarios must run separately.'
}
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
if (-not $SkipNetworkPreflight) {
    if (-not (Test-Path -LiteralPath $networkCheck)) {
        throw "Missing required local network diagnostic: $networkCheck. Use -SkipNetworkPreflight only after an equivalent manual check."
    }
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw "Local network preflight failed with exit code $LASTEXITCODE. The smoke test did not change middleware state."
    }
}

$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}

Import-DotEnv -Path $envFile
foreach ($internalTokenName in @(
        'TRADE_INTERNAL_SERVICE_TOKEN',
        'PAYMENT_INTERNAL_SERVICE_TOKEN')) {
    $internalTokenValue = [Environment]::GetEnvironmentVariable($internalTokenName, 'Process')
    if ([string]::IsNullOrWhiteSpace($internalTokenValue) -or $internalTokenValue.Length -lt 32) {
        throw "$internalTokenName must be a non-blank process secret with at least 32 characters."
    }
}
if ([string]::Equals(
        $env:TRADE_INTERNAL_SERVICE_TOKEN,
        $env:PAYMENT_INTERNAL_SERVICE_TOKEN,
        [StringComparison]::Ordinal)) {
    throw 'Trade and Payment internal trust zones must use different credentials.'
}
$javaExecutable = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $null
}
if (-not $javaExecutable -or -not (Test-Path -LiteralPath $javaExecutable)) {
    throw 'JAVA_HOME must point to the JDK 17 used by the PlainJournal backend.'
}
$javaVersion = (& $javaExecutable -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "17\.') {
    throw "PlainJournal foundation smoke requires JDK 17 from JAVA_HOME: $javaVersion"
}
[Environment]::SetEnvironmentVariable('NACOS_USERNAME', 'nacos', 'Process')
[Environment]::SetEnvironmentVariable('NACOS_HOST', '127.0.0.1', 'Process')
[Environment]::SetEnvironmentVariable('SERVICE_IP', '127.0.0.1', 'Process')
[Environment]::SetEnvironmentVariable('SERVICE_INSTANCE_ID', 'local', 'Process')
$tradeDistributedIdNamespace = if ($env:TRADE_DISTRIBUTED_ID_NAMESPACE) {
    $env:TRADE_DISTRIBUTED_ID_NAMESPACE.Trim()
} else {
    'trade-service'
}
$tradeDistributedIdWorkerId = if ($env:TRADE_DISTRIBUTED_ID_WORKER_ID) {
    [int]$env:TRADE_DISTRIBUTED_ID_WORKER_ID
} else {
    0
}
if ($EnableCapacityBaseline) {
    # The capacity path intentionally waits for a large real Outbox backlog to drain.
    # Keep the smoke identity valid for that bounded run without changing production defaults.
    [Environment]::SetEnvironmentVariable(
        'ECOMMERCE_SECURITY_TOKEN_ACCESS_TTL',
        '1h',
        'Process'
    )
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop is not ready. Start it only after Clash proxy validation, then rerun the smoke test.'
}
    foreach ($container in @(
            'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
            'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker',
            'plainjournal-rocketmq-proxy', 'plainjournal-minio')) {
    $running = docker inspect --format '{{.State.Running}}' $container 2>$null
    if ($running -ne 'true') {
        throw "Required container $container is not running. The smoke test will not start it automatically."
    }
}

$gatewayPort = 18000
$identityPort = 18101
$catalogPort = 18102
$inventoryPort = 18103
$tradePort = 18104
$paymentPort = 18105
$fulfillmentPort = 18106
$marketingPort = 18107
$inventoryResponseLossProxyPort = 18603
Assert-PortAvailable -Port $gatewayPort
Assert-PortAvailable -Port $identityPort
Assert-PortAvailable -Port $catalogPort
Assert-PortAvailable -Port $inventoryPort
Assert-PortAvailable -Port $tradePort
Assert-PortAvailable -Port $paymentPort
Assert-PortAvailable -Port $fulfillmentPort
Assert-PortAvailable -Port $marketingPort
if ($EnableInventoryReservationResponseLossFaultInjection) {
    Assert-PortAvailable -Port $inventoryResponseLossProxyPort
}

$backendPom = Join-Path $PSScriptRoot 'pom.xml'
if (-not (Test-Path -LiteralPath $backendPom)) {
    throw "Backend pom.xml was not found at $backendPom"
}

& mvn -q -f $backendPom -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Backend package failed with exit code $LASTEXITCODE"
}

$runDirectory = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$internalTrustZoneEvidencePath = Join-Path $runDirectory 'internal-trust-zones.json'
$databaseTimeContractEvidencePath = Join-Path $runDirectory 'database-time-contract.json'

$identityJar = Join-Path $PSScriptRoot 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
$catalogJar = Join-Path $PSScriptRoot 'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar'
$inventoryJar = Join-Path $PSScriptRoot 'services\inventory-service\target\inventory-service-0.1.0-SNAPSHOT.jar'
$tradeJar = Join-Path $PSScriptRoot 'services\trade-service\target\trade-service-0.1.0-SNAPSHOT.jar'
$paymentJar = Join-Path $PSScriptRoot 'services\payment-service\target\payment-service-0.1.0-SNAPSHOT.jar'
$fulfillmentJar = Join-Path $PSScriptRoot 'services\fulfillment-service\target\fulfillment-service-0.1.0-SNAPSHOT.jar'
$marketingJar = Join-Path $PSScriptRoot 'services\marketing-service\target\marketing-service-0.1.0-SNAPSHOT.jar'
$gatewayJar = Join-Path $PSScriptRoot 'ecommerce-gateway\target\ecommerce-gateway-0.1.0-SNAPSHOT.jar'
$identityOut = Join-Path $runDirectory 'identity.out.log'
$identityErr = Join-Path $runDirectory 'identity.err.log'
$catalogOut = Join-Path $runDirectory 'catalog.out.log'
$catalogErr = Join-Path $runDirectory 'catalog.err.log'
$inventoryOut = Join-Path $runDirectory 'inventory.out.log'
$inventoryErr = Join-Path $runDirectory 'inventory.err.log'
$inventoryRecoveryOut = Join-Path $runDirectory 'inventory-recovery.out.log'
$inventoryRecoveryErr = Join-Path $runDirectory 'inventory-recovery.err.log'
$tradeOut = Join-Path $runDirectory 'trade.out.log'
$tradeErr = Join-Path $runDirectory 'trade.err.log'
$tradeRecoveryOut = Join-Path $runDirectory 'trade-recovery.out.log'
$tradeRecoveryErr = Join-Path $runDirectory 'trade-recovery.err.log'
$paymentOut = Join-Path $runDirectory 'payment.out.log'
$paymentErr = Join-Path $runDirectory 'payment.err.log'
$fulfillmentOut = Join-Path $runDirectory 'fulfillment.out.log'
$fulfillmentErr = Join-Path $runDirectory 'fulfillment.err.log'
$marketingOut = Join-Path $runDirectory 'marketing.out.log'
$marketingErr = Join-Path $runDirectory 'marketing.err.log'
$marketingRecoveryOut = Join-Path $runDirectory 'marketing-recovery.out.log'
$marketingRecoveryErr = Join-Path $runDirectory 'marketing-recovery.err.log'
$gatewayOut = Join-Path $runDirectory 'gateway.out.log'
$gatewayErr = Join-Path $runDirectory 'gateway.err.log'
$inventoryResponseLossProxyOut = Join-Path $runDirectory 'inventory-response-loss-proxy.out.log'
$inventoryResponseLossProxyErr = Join-Path $runDirectory 'inventory-response-loss-proxy.err.log'
$inventoryResponseLossProxyReadyPath = Join-Path $runDirectory 'inventory-response-loss-proxy.ready'
$inventoryResponseLossArmPath = Join-Path $runDirectory 'inventory-response-loss.arm'
$inventoryResponseLossProxyEvidencePath = Join-Path $runDirectory 'inventory-response-loss-proxy.json'
$inventoryResponseLossEvidencePath = Join-Path $runDirectory 'inventory-reservation-response-loss.json'
$synchronousResilienceEvidencePath = Join-Path $runDirectory 'payment-trade-synchronous-resilience.json'
$tradeMarketingResilienceEvidencePath = Join-Path $runDirectory 'trade-marketing-synchronous-resilience.json'
$redisFallbackEvidencePath = Join-Path $runDirectory 'identity-redis-fallback.json'
$distributedTracingEvidencePath = Join-Path $runDirectory 'distributed-tracing.json'
$paymentInventoryCausalityEvidencePath = Join-Path $runDirectory 'payment-inventory-causality.json'
$fulfillmentExceptionEvidencePath = Join-Path $runDirectory 'fulfillment-exception-recovery.json'
$exceptionalPaymentEvidencePath = Join-Path $runDirectory 'exceptional-payment-recovery.json'
$inventoryResponseLossProxyService = 'inventory-response-loss-proxy'
$inventoryResponseLossProxyProcess = $null
$inventoryResponseLossNacosHeaders = $null
$inventoryResponseLossNacosRegistered = $false
$inventoryResponseLossNacosRegistrationAttempted = $false
$inventoryResponseLossNacosServiceCreated = $false
$smokeEmail = "identity-smoke-$([Guid]::NewGuid().ToString('N'))@example.invalid"
$warehouseEmail = "identity-warehouse-$([Guid]::NewGuid().ToString('N'))@example.invalid"
$riskEmail = "identity-risk-$([Guid]::NewGuid().ToString('N'))@example.invalid"
$riskHash = Get-Sha256Hex -Value $riskEmail
$gatewayClientHash = Get-Sha256Hex -Value '127.0.0.1'
$redisKeys = @(
    "ecommerce:local:identity:login:failures:$riskHash",
    "ecommerce:local:identity:login:lock:$riskHash",
    "ecommerce:local:gateway:rate:login:$gatewayClientHash",
    "ecommerce:local:gateway:rate:registration:$gatewayClientHash",
    "ecommerce:local:gateway:rate:refresh:$gatewayClientHash"
)
$redisStoppedBySmoke = $false
$catalogCategorySlug = "smoke-category-$([Guid]::NewGuid().ToString('N'))"
$catalogBrandSlug = "smoke-brand-$([Guid]::NewGuid().ToString('N'))"
$catalogSkuCode = "SMOKE-$([Guid]::NewGuid().ToString('N').ToUpperInvariant())"
$tradeSkuCode = "TRADE-$([Guid]::NewGuid().ToString('N').ToUpperInvariant())"
$exceptionSkuCode = "EXCEPTION-$([Guid]::NewGuid().ToString('N').ToUpperInvariant())"
$catalogProductTitle = "Smoke Product $([Guid]::NewGuid().ToString('N'))"
$catalogObjectKey = $null
$inventoryWarehouseCode = 'PRIMARY'
$inventoryMovementNo = "SMOKE-ADJ-$([Guid]::NewGuid().ToString('N'))"
$tradeMovementNo = "SMOKE-TRADE-ADJ-$([Guid]::NewGuid().ToString('N'))"
$exceptionMovementNo = "SMOKE-EXCEPTION-ADJ-$([Guid]::NewGuid().ToString('N'))"
$inventoryReservationPrefix = "SMOKE-RES-$([Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant())"
$inventoryWarehouseId = $null
$inventorySkuId = $null
$tradeSkuId = $null
$exceptionSkuId = $null
$tradeOrderNumbers = @()
$tradeReservationNumbers = @()
$tradeOrderSqlList = ''
$tradeReservationSqlList = ''
$paymentNo = $null
$resilienceProbePaymentNo = $null
$paymentEventId = $null
$paymentSucceededEventIds = @()
$orderPaidEventId = $null
$orderPaidEventIds = @()
$orderLifecycleEventIds = @()
$fulfillmentNo = $null
$fulfillmentEventIds = @()
$fulfillmentEventSqlList = ''
$fulfillmentLifecycleEventIds = @()
$afterSaleNo = $null
$returnReceiptNo = $null
$refundNo = $null
$exceptionOrderNo = $null
$exceptionReservationNo = $null
$exceptionPaymentNo = $null
$exceptionRefundNo = $null
$exceptionPaymentSucceededEventId = $null
$exceptionRefundSucceededEventId = $null
$afterSaleApprovedEventId = $null
$afterSaleApprovedEventIds = @()
$returnShipmentEventId = $null
$returnShipmentEventIds = @()
$returnReceivedEventId = $null
$returnReceivedEventIds = @()
$returnInspectedEventId = $null
$returnInspectedEventIds = @()
$returnStockedEventId = $null
$returnStockedEventIds = @()
$refundRequestedEventId = $null
$refundRequestedEventIds = @()
$refundResultEventId = $null
$refundResultEventIds = @()
$smokeAddressId = $null
$exceptionAddressId = $null
$smokeUserId = $null
$warehouseUserId = $null
$paymentInventoryCausalityEvidence = $null
$synchronousResilienceEvidence = $null
$tradeMarketingResilienceEvidence = $null
$redisFallbackEvidence = $null
$fulfillmentExceptionEvidence = $null
$exceptionalPaymentEvidence = $null
$marketingRulePrefix = "SMOKE-MKT-$([Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant())"
$marketingBenefitNos = @()
$marketingCancelBenefitNos = @()
$marketingExceptionBenefitNos = @()
$marketingOrderNo = $null
$marketingCancelOrderNo = $null
$tempoStartedBySmoke = $false
$tempoExistedBeforeSmoke = $false
$tempoWasRunningBeforeSmoke = $false
$tracingEvidence = $null
$refundTracingEvidence = $null
$inventoryLatencySummary = $null
$tradeLatencySummary = $null
$tradeIdempotencyLatencySummary = $null
$paymentCallbackLatencySummary = $null
$refundCallbackLatencySummary = $null
$returnChainConvergenceSeconds = $null
$inventoryOutboxUnpublishedAtReturnInspection = $null
$inventoryOutboxUnpublishedAtReturnChainConvergence = $null
$inventoryResponseLossEvidence = $null
$originalAddressDetail = 'No. 1 Original Smoke Street'
$updatedAddressDetail = 'No. 99 Updated Smoke Avenue'
Remove-RedisKeys -Keys $redisKeys

try {
    if ($EnableDistributedTracing) {
        $tempoContainer = 'plainjournal-tempo'
        docker inspect $tempoContainer *> $null
        $tempoExistedBeforeSmoke = $LASTEXITCODE -eq 0
        if ($tempoExistedBeforeSmoke) {
            $tempoWasRunningBeforeSmoke = (docker inspect --format '{{.State.Running}}' $tempoContainer 2>$null) -eq 'true'
        }
        $composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
        $composeFile = Join-Path $composeDirectory 'compose.yml'
        docker compose --project-directory $composeDirectory --env-file $envFile `
            --file $composeFile --profile observability up -d tempo
        if ($LASTEXITCODE -ne 0) {
            throw 'Tempo failed to start for distributed tracing verification.'
        }
        $tempoStartedBySmoke = $true
        $tempoHttpPort = if ($env:TEMPO_HTTP_PORT) { [int]$env:TEMPO_HTTP_PORT } else { 13200 }
        $tempoOtlpHttpPort = if ($env:TEMPO_OTLP_HTTP_PORT) { [int]$env:TEMPO_OTLP_HTTP_PORT } else { 14318 }
        Wait-HttpOk -Uri "http://127.0.0.1:$tempoHttpPort/ready" | Out-Null
        [Environment]::SetEnvironmentVariable('OTLP_TRACING_EXPORT_ENABLED', 'true', 'Process')
        [Environment]::SetEnvironmentVariable(
            'OTLP_TRACING_ENDPOINT', "http://127.0.0.1:$tempoOtlpHttpPort/v1/traces", 'Process')
        [Environment]::SetEnvironmentVariable('TRACING_SAMPLING_PROBABILITY', '1.0', 'Process')
    }

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $identityJar) `
        -WindowStyle Hidden -RedirectStandardOutput $identityOut -RedirectStandardError $identityErr
    Wait-HttpOk -Uri "http://127.0.0.1:$identityPort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $catalogJar) `
        -WindowStyle Hidden -RedirectStandardOutput $catalogOut -RedirectStandardError $catalogErr
    Wait-HttpOk -Uri "http://127.0.0.1:$catalogPort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $inventoryJar) `
        -WindowStyle Hidden -RedirectStandardOutput $inventoryOut -RedirectStandardError $inventoryErr
    Wait-HttpOk -Uri "http://127.0.0.1:$inventoryPort/actuator/health/liveness" | Out-Null

    if ($EnableInventoryReservationResponseLossFaultInjection) {
        Remove-Item -LiteralPath $inventoryResponseLossProxyReadyPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $inventoryResponseLossArmPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $inventoryResponseLossProxyEvidencePath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $inventoryResponseLossEvidencePath -Force -ErrorAction SilentlyContinue
        $proxyScript = Join-Path $PSScriptRoot 'tools\inventory-response-drop-proxy.ps1'
        $inventoryResponseLossProxyProcess = Start-Process `
            -FilePath (Get-Command pwsh).Source `
            -ArgumentList @(
                '-NoProfile',
                '-File', $proxyScript,
                '-ListenPort', $inventoryResponseLossProxyPort,
                '-UpstreamBaseUrl', "http://127.0.0.1:$inventoryPort",
                '-ArmFile', $inventoryResponseLossArmPath,
                '-EvidenceFile', $inventoryResponseLossProxyEvidencePath,
                '-ReadyFile', $inventoryResponseLossProxyReadyPath
            ) `
            -PassThru `
            -WindowStyle Hidden `
            -RedirectStandardOutput $inventoryResponseLossProxyOut `
            -RedirectStandardError $inventoryResponseLossProxyErr
        $proxyReadyDeadline = (Get-Date).AddSeconds(30)
        do {
            if ($inventoryResponseLossProxyProcess.HasExited) {
                throw "Inventory response-loss proxy exited with code $($inventoryResponseLossProxyProcess.ExitCode)."
            }
            if (Test-Path -LiteralPath $inventoryResponseLossProxyReadyPath) {
                break
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $proxyReadyDeadline)
        if (-not (Test-Path -LiteralPath $inventoryResponseLossProxyReadyPath)) {
            throw 'Inventory response-loss proxy did not become ready.'
        }
        Wait-HttpOk -Uri "http://127.0.0.1:$inventoryResponseLossProxyPort/actuator/health/liveness" |
            Out-Null

        $inventoryResponseLossNacosHeaders = Get-NacosApiHeaders
        if (-not (Test-NacosServiceExists `
                -Headers $inventoryResponseLossNacosHeaders `
                -ServiceName $inventoryResponseLossProxyService)) {
            New-NacosService `
                -Headers $inventoryResponseLossNacosHeaders `
                -ServiceName $inventoryResponseLossProxyService
            $inventoryResponseLossNacosServiceCreated = $true
        }
        try {
            Remove-NacosFixedInstance `
                -Headers $inventoryResponseLossNacosHeaders `
                -ServiceName $inventoryResponseLossProxyService `
                -Ip '127.0.0.1' `
                -Port $inventoryResponseLossProxyPort
        }
        catch {
            # A previous fixed instance normally does not exist.
        }
        $inventoryResponseLossNacosRegistrationAttempted = $true
        Register-NacosFixedInstance `
            -Headers $inventoryResponseLossNacosHeaders `
            -ServiceName $inventoryResponseLossProxyService `
            -Ip '127.0.0.1' `
            -Port $inventoryResponseLossProxyPort
        $inventoryResponseLossNacosRegistered = $true
        Wait-NacosFixedInstance `
            -Headers $inventoryResponseLossNacosHeaders `
            -ServiceName $inventoryResponseLossProxyService `
            -Ip '127.0.0.1' `
            -Port $inventoryResponseLossProxyPort
        [Environment]::SetEnvironmentVariable(
            'TRADE_INVENTORY_BASE_URL',
            "http://$inventoryResponseLossProxyService",
            'Process')
    }

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $marketingJar) `
        -WindowStyle Hidden -RedirectStandardOutput $marketingOut -RedirectStandardError $marketingErr
    Wait-HttpOk -Uri "http://127.0.0.1:$marketingPort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $tradeJar) `
        -WindowStyle Hidden -RedirectStandardOutput $tradeOut -RedirectStandardError $tradeErr
    Wait-HttpOk -Uri "http://127.0.0.1:$tradePort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $paymentJar) `
        -WindowStyle Hidden -RedirectStandardOutput $paymentOut -RedirectStandardError $paymentErr
    Wait-HttpOk -Uri "http://127.0.0.1:$paymentPort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $fulfillmentJar) `
        -WindowStyle Hidden -RedirectStandardOutput $fulfillmentOut -RedirectStandardError $fulfillmentErr
    Wait-HttpOk -Uri "http://127.0.0.1:$fulfillmentPort/actuator/health/liveness" | Out-Null

    Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $gatewayJar) `
        -WindowStyle Hidden -RedirectStandardOutput $gatewayOut -RedirectStandardError $gatewayErr
    Wait-HttpOk -Uri "http://127.0.0.1:$gatewayPort/actuator/health/liveness" | Out-Null

    $requestId = 'foundation_smoke_001'
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$gatewayPort/api/v1/identity/status" `
        -Headers @{ 'X-Request-Id' = $requestId } -TimeoutSec 10
    $payload = $response.Content | ConvertFrom-Json

    if ($payload.code -ne 'OK' -or $payload.data.service -ne 'identity-service') {
        throw "Unexpected gateway response: $($response.Content)"
    }
    if ($payload.data.configurationSource -ne 'nacos') {
        throw 'Nacos configuration was not loaded. Run deploy/docker/bootstrap-resources.ps1 first.'
    }
    if ($response.Headers['X-Request-Id'] -ne $requestId) {
        throw 'Gateway did not preserve the valid request ID.'
    }

    $identityBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/identity"
    $password = 'SmokeTestPass123'
    $registration = Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
        email = $smokeEmail
        password = $password
        displayName = 'Identity Smoke Test'
    }
    if ($registration.code -ne 'OK' -or $registration.data.roles[0] -ne 'CUSTOMER') {
        throw 'Identity registration did not create an active customer account.'
    }

    $login = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $smokeEmail
        password = $password
    }
    $accessToken = $login.data.accessToken
    $firstRefreshToken = $login.data.refreshToken
    if (-not $accessToken -or -not $firstRefreshToken) {
        throw 'Identity login did not issue both token types.'
    }
    if ($EnableCapacityBaseline -and [int]$login.data.expiresIn -lt 3600) {
        throw 'Capacity smoke access-token TTL override was not applied.'
    }

    $profile = Invoke-RestMethod -Method Get -Uri "$identityBaseUrl/me" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($profile.data.email -ne $smokeEmail -or $profile.data.roles[0] -ne 'CUSTOMER') {
        throw 'The authenticated profile does not match the registered account.'
    }
    $smokeUserId = docker exec -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME -N -B `
        -e "SELECT id FROM user_account WHERE email = '$smokeEmail'"
    if (-not $smokeUserId) {
        throw 'Unable to resolve the temporary smoke user ID.'
    }

    $smokeAddress = Invoke-JsonPost -Uri "$identityBaseUrl/addresses" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            recipientName = 'Smoke Customer'
            phone = '+86 13800000000'
            province = 'Zhejiang'
            provinceCode = '330000'
            city = 'Hangzhou'
            cityCode = '330100'
            district = 'Xihu'
            districtCode = '330106'
            detailAddress = $originalAddressDetail
            postalCode = '310000'
            setDefault = $true
        }
    $smokeAddressId = $smokeAddress.data.id
    if (-not $smokeAddressId -or -not $smokeAddress.data.defaultAddress) {
        throw 'Identity address creation did not produce a default delivery address.'
    }
    $identityWrongZoneResponse = Invoke-WebRequest -Method Get `
        -Uri "http://127.0.0.1:$identityPort/api/v1/identity/internal/users/$smokeUserId/addresses/$smokeAddressId" `
        -Headers @{
            'X-Internal-Service' = 'trade-service'
            'X-Internal-Token' = $env:PAYMENT_INTERNAL_SERVICE_TOKEN
        } -SkipHttpErrorCheck -TimeoutSec 10
    $identityWrongZoneStatus = [int]$identityWrongZoneResponse.StatusCode
    if ($identityWrongZoneStatus -ne 401) {
        throw "Identity accepted the Payment trust-zone credential as Trade: HTTP $identityWrongZoneStatus."
    }

    $catalogBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/catalog"
    $customerCatalogAttempt = Invoke-JsonPostRaw -Uri "$catalogBaseUrl/admin/categories" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{ name = 'Forbidden Category'; slug = 'forbidden-category'; sortOrder = 0 }
    if ([int]$customerCatalogAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to write catalog data.'
    }

    $refresh = Invoke-JsonPost -Uri "$identityBaseUrl/auth/refresh" -Body @{
        refreshToken = $firstRefreshToken
    }
    $secondRefreshToken = $refresh.data.refreshToken
    if (-not $secondRefreshToken -or $secondRefreshToken -eq $firstRefreshToken) {
        throw 'Refresh token rotation did not issue a new token.'
    }
    Assert-JsonPostRejected -Uri "$identityBaseUrl/auth/refresh" `
        -Body @{ refreshToken = $firstRefreshToken } -ExpectedStatus 401

    Invoke-JsonPost -Uri "$identityBaseUrl/auth/logout" -Body @{
        refreshToken = $secondRefreshToken
    } | Out-Null
    Assert-JsonPostRejected -Uri "$identityBaseUrl/auth/refresh" `
        -Body @{ refreshToken = $secondRefreshToken } -ExpectedStatus 401

    $grantAdminSql = @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT user_account.id, identity_role.id, CURRENT_TIMESTAMP(3)
FROM user_account
JOIN identity_role ON identity_role.code = 'ADMIN'
WHERE user_account.email = '$smokeEmail';
"@
    $grantAdminSql | docker exec -i -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to grant the temporary smoke account an administrator role.'
    }

    $adminLogin = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $smokeEmail
        password = $password
    }
    $adminAccessToken = $adminLogin.data.accessToken
    $adminProfile = Invoke-RestMethod -Method Get -Uri "$identityBaseUrl/me" `
        -Headers @{ Authorization = "Bearer $adminAccessToken" } -TimeoutSec 10
    if ($adminProfile.data.roles -notcontains 'ADMIN') {
        throw 'The temporary administrator role was not included in the new access token.'
    }
    $adminHeaders = @{ Authorization = "Bearer $adminAccessToken" }

    $warehouseRegistration = Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
        email = $warehouseEmail
        password = $password
        displayName = 'Warehouse Smoke Test'
    }
    if ($warehouseRegistration.data.roles[0] -ne 'CUSTOMER') {
        throw 'Warehouse smoke registration did not begin as a customer.'
    }
    $warehouseUserId = docker exec -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME -N -B `
        -e "SELECT id FROM user_account WHERE email = '$warehouseEmail'"
    if (-not $warehouseUserId) {
        throw 'Unable to resolve the temporary warehouse user ID.'
    }
    $grantWarehouseSql = @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT user_account.id, identity_role.id, CURRENT_TIMESTAMP(3)
FROM user_account
JOIN identity_role ON identity_role.code = 'WAREHOUSE'
WHERE user_account.email = '$warehouseEmail';
"@
    $grantWarehouseSql | docker exec -i -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to grant the temporary warehouse role.'
    }
    $warehouseLogin = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $warehouseEmail
        password = $password
    }
    $warehouseAccessToken = $warehouseLogin.data.accessToken
    $warehouseProfile = Invoke-RestMethod -Method Get -Uri "$identityBaseUrl/me" `
        -Headers @{ Authorization = "Bearer $warehouseAccessToken" } -TimeoutSec 10
    if ($warehouseProfile.data.roles -notcontains 'WAREHOUSE' -or
        $warehouseProfile.data.roles -contains 'ADMIN') {
        throw 'The temporary warehouse token did not preserve the intended role boundary.'
    }
    $warehouseHeaders = @{ Authorization = "Bearer $warehouseAccessToken" }

    foreach ($operationalService in @(
            @{ Name = 'inventory'; Port = $inventoryPort; HasBusinessProcesses = $false },
            @{ Name = 'trade'; Port = $tradePort; HasBusinessProcesses = $true },
            @{ Name = 'payment'; Port = $paymentPort; HasBusinessProcesses = $true },
            @{ Name = 'fulfillment'; Port = $fulfillmentPort; HasBusinessProcesses = $false })) {
        $metricsUri = "http://127.0.0.1:$($operationalService.Port)/actuator/metrics"
        $customerMetrics = Invoke-WebRequest -Uri $metricsUri -SkipHttpErrorCheck `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ([int]$customerMetrics.StatusCode -ne 403) {
            throw "A customer token could read $($operationalService.Name) operational metrics."
        }
        $adminMetrics = Invoke-RestMethod -Uri $metricsUri -Headers $adminHeaders -TimeoutSec 10
        $metricNames = $adminMetrics.names
        if ($metricNames -notcontains 'ecommerce.outbox.pending' -or
            $metricNames -notcontains 'ecommerce.outbox.oldest.age' -or
            $metricNames -notcontains 'ecommerce.consumer.failure.active' -or
            $metricNames -notcontains 'ecommerce.consumer.failure.oldest.age') {
            throw "$($operationalService.Name) did not register the shared operational backlog metrics."
        }

        $prometheusUri = "http://127.0.0.1:$($operationalService.Port)/actuator/prometheus"
        $anonymousPrometheus = Invoke-WebRequest -Uri $prometheusUri -SkipHttpErrorCheck -TimeoutSec 10
        if ([int]$anonymousPrometheus.StatusCode -ne 401) {
            throw "$($operationalService.Name) exposed Prometheus metrics anonymously."
        }
        $invalidPrometheus = Invoke-WebRequest -Uri $prometheusUri -SkipHttpErrorCheck `
            -Headers @{ 'X-Metrics-Token' = ('0' * 64) } -TimeoutSec 10
        if ([int]$invalidPrometheus.StatusCode -ne 401) {
            throw "$($operationalService.Name) accepted an invalid metrics scrape credential."
        }
        $prometheusMetrics = Invoke-WebRequest -Uri $prometheusUri `
            -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } -TimeoutSec 10
        if ($prometheusMetrics.Content -notmatch 'ecommerce_consumer_failure_active_events') {
            throw "$($operationalService.Name) Prometheus export omitted shared operational metrics."
        }

        $consumerFailuresUri = "http://127.0.0.1:$($operationalService.Port)/actuator/consumerfailures"
        $customerConsumerFailures = Invoke-WebRequest -Uri $consumerFailuresUri -SkipHttpErrorCheck `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ([int]$customerConsumerFailures.StatusCode -ne 403) {
            throw "A customer token could read $($operationalService.Name) consumer failures."
        }
        $consumerFailures = Invoke-RestMethod -Uri $consumerFailuresUri -Headers $adminHeaders -TimeoutSec 10
        if ($consumerFailures.service -ne "$($operationalService.Name)-service" -or
            $null -eq $consumerFailures.activeFailures) {
            throw "$($operationalService.Name) consumer failure report was incomplete."
        }

        if ($operationalService.HasBusinessProcesses) {
            if ($metricNames -notcontains 'ecommerce.business.process.active' -or
                $metricNames -notcontains 'ecommerce.business.process.oldest.age') {
                throw "$($operationalService.Name) did not register business process metrics."
            }
            $businessProcessesUri = "http://127.0.0.1:$($operationalService.Port)/actuator/businessprocesses"
            $customerBusinessProcesses = Invoke-WebRequest -Uri $businessProcessesUri -SkipHttpErrorCheck `
                -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
            if ([int]$customerBusinessProcesses.StatusCode -ne 403) {
                throw "A customer token could read $($operationalService.Name) business processes."
            }
            $businessProcesses = Invoke-RestMethod -Uri $businessProcessesUri `
                -Headers $adminHeaders -TimeoutSec 10
            if ($businessProcesses.service -ne "$($operationalService.Name)-service" -or
                $null -eq $businessProcesses.states -or
                $null -eq $businessProcesses.activeProcesses) {
                throw "$($operationalService.Name) business process report was incomplete."
            }
        }
    }
    if ($EnableObservability) {
        & (Join-Path $repositoryRoot 'deploy\docker\verify-observability.ps1') -SkipNetworkPreflight
        if ($LASTEXITCODE -ne 0) {
            throw "Observability smoke verification failed with exit code $LASTEXITCODE"
        }
    }
    $category = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/categories" -Headers $adminHeaders -Body @{
        name = 'Smoke Category'
        slug = $catalogCategorySlug
        sortOrder = 9999
    }
    $brand = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/brands" -Headers $adminHeaders -Body @{
        name = 'Smoke Brand'
        slug = $catalogBrandSlug
    }
    $product = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products" -Headers $adminHeaders -Body @{
        categoryId = $category.data.id
        brandId = $brand.data.id
        title = $catalogProductTitle
        subtitle = 'Temporary catalog verification data'
        description = 'Created and removed by run-foundation-smoke.ps1.'
        skus = @(
            @{
                skuCode = $catalogSkuCode
                name = 'Default SKU'
                specJson = '{"variant":"default"}'
                salePrice = 129.90
                marketPrice = 159.90
            },
            @{
                skuCode = $tradeSkuCode
                name = 'Trade Competition SKU'
                specJson = '{"variant":"trade"}'
                salePrice = 49.90
                marketPrice = 69.90
            },
            @{
                skuCode = $exceptionSkuCode
                name = 'Exceptional Payment Recovery SKU'
                specJson = '{"variant":"exceptional-payment"}'
                salePrice = 49.90
                marketPrice = 69.90
            }
        )
    }
    if ($product.data.status -ne 'DRAFT') {
        throw 'A newly created catalog product was not a draft.'
    }
    $productId = $product.data.id

    $draftRead = Invoke-WebRequest -Uri "$catalogBaseUrl/products/$productId" -SkipHttpErrorCheck -TimeoutSec 10
    if ([int]$draftRead.StatusCode -ne 404) {
        throw 'A draft catalog product was visible through the public API.'
    }
    $publishedProduct = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/publish" `
        -Headers $adminHeaders -Body @{ expectedVersion = $product.data.version }
    if ($publishedProduct.data.status -ne 'ACTIVE') {
        throw 'Catalog product publication failed.'
    }

    $pngBytes = [Convert]::FromBase64String(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
    $uploadIntent = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/media/upload-intents" `
        -Headers $adminHeaders -Body @{ contentType = 'image/png'; sizeBytes = $pngBytes.Length }
    $catalogObjectKey = $uploadIntent.data.objectKey
    $uploadResponse = Invoke-WebRequest -Method Put -Uri $uploadIntent.data.uploadUrl `
        -Body $pngBytes -ContentType 'image/png' -TimeoutSec 10
    if ([int]$uploadResponse.StatusCode -notin @(200, 204)) {
        throw 'The pre-signed MinIO product upload failed.'
    }
    Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/media" -Headers $adminHeaders -Body @{
        objectKey = $catalogObjectKey
        sortOrder = 0
    } | Out-Null

    $publicProduct = Invoke-RestMethod -Method Get -Uri "$catalogBaseUrl/products/$productId" -TimeoutSec 10
    $publicDefaultSku = $publicProduct.data.skus | Where-Object skuCode -eq $catalogSkuCode
    if ([decimal]$publicDefaultSku.salePrice -ne [decimal]129.90 -or
        -not $publicProduct.data.media[0].url) {
        throw 'The published product price or signed media URL was not returned correctly.'
    }
    $mediaRead = Invoke-WebRequest -Uri $publicProduct.data.media[0].url -TimeoutSec 10
    if ([int]$mediaRead.StatusCode -ne 200) {
        throw 'The pre-signed MinIO product download failed.'
    }

    $inventoryBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/inventory"
    $inventoryInternalBaseUrl = "http://127.0.0.1:$inventoryPort/api/v1/inventory/internal"
    $internalHeaders = @{
        'X-Internal-Service' = 'trade-service'
        'X-Internal-Token' = $env:TRADE_INTERNAL_SERVICE_TOKEN
    }
    $inventoryReservationExpiresAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    $customerInventoryAttempt = Invoke-JsonPostRaw -Uri "$inventoryBaseUrl/internal/reservations" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{
            reservationNo = "$inventoryReservationPrefix-FORBIDDEN"
            orderNo = "$inventoryReservationPrefix-FORBIDDEN"
            warehouseId = 1
            expiresAt = $inventoryReservationExpiresAt
            items = @(@{ skuId = 1; quantity = 1 })
        }
    if ([int]$customerInventoryAttempt.StatusCode -ne 404) {
        throw 'The public gateway exposed an internal inventory command.'
    }

    $warehouse = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/warehouses" -Headers $adminHeaders -Body @{
        code = $inventoryWarehouseCode
        name = 'Smoke Test Warehouse'
    }
    $inventoryWarehouseId = $warehouse.data.id
    $wrongZoneReservationNo = "$inventoryReservationPrefix-WRONG-ZONE"
    $inventoryWrongZoneResponse = Invoke-JsonPostRaw `
        -Uri "$inventoryInternalBaseUrl/reservations" `
        -Headers @{
            'X-Internal-Service' = 'trade-service'
            'X-Internal-Token' = $env:PAYMENT_INTERNAL_SERVICE_TOKEN
        } -Body @{
            reservationNo = $wrongZoneReservationNo
            orderNo = $wrongZoneReservationNo
            warehouseId = $inventoryWarehouseId
            expiresAt = $inventoryReservationExpiresAt
            items = @(@{ skuId = 1; quantity = 1 })
        }
    $inventoryWrongZoneStatus = [int]$inventoryWrongZoneResponse.StatusCode
    $inventoryWrongZoneRows = [int](Get-MySqlSingleColumn `
            -Database $env:INVENTORY_DB_NAME `
            -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no = '$wrongZoneReservationNo';" |
            Select-Object -Last 1)
    if ($inventoryWrongZoneStatus -ne 401 -or $inventoryWrongZoneRows -ne 0) {
        throw "Inventory trust-zone isolation failed: HTTP $inventoryWrongZoneStatus, rows=$inventoryWrongZoneRows."
    }
    $inventorySkuId = ($product.data.skus | Where-Object skuCode -eq $catalogSkuCode).id
    $tradeSkuId = ($product.data.skus | Where-Object skuCode -eq $tradeSkuCode).id
    $exceptionSkuId = ($product.data.skus | Where-Object skuCode -eq $exceptionSkuCode).id
    if (-not $inventorySkuId -or -not $tradeSkuId -or -not $exceptionSkuId) {
        throw 'Catalog product creation did not return all three smoke SKUs.'
    }
    $initialStock = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = $inventoryMovementNo
            warehouseId = $inventoryWarehouseId
            skuId = $inventorySkuId
            quantityDelta = $inventoryCompetitionSuccesses
            reason = 'Real middleware concurrency smoke stock'
        }
    if ($initialStock.data.available -ne $inventoryCompetitionSuccesses) {
        throw "Inventory adjustment did not create $inventoryCompetitionSuccesses available units."
    }

    $warehouseIdForThreads = $inventoryWarehouseId
    $skuIdForThreads = $inventorySkuId
    $inventoryCompetitionTimer = [Diagnostics.Stopwatch]::StartNew()
    $reservationResults = 0..($inventoryCompetitionAttempts - 1) | ForEach-Object -Parallel {
        $index = $_
        $body = @{
            reservationNo = "$using:inventoryReservationPrefix-$index"
            orderNo = "SMOKE-ORDER-$index"
            warehouseId = $using:warehouseIdForThreads
            expiresAt = $using:inventoryReservationExpiresAt
            items = @(@{ skuId = $using:skuIdForThreads; quantity = 1 })
        } | ConvertTo-Json -Compress -Depth 10
        $requestTimer = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod -Method Post `
            -Uri "$using:inventoryInternalBaseUrl/reservations" `
            -Headers $using:internalHeaders `
            -ContentType 'application/json' -Body $body -TimeoutSec 30
        $requestTimer.Stop()
        [pscustomobject]@{
            ReservationNo = $response.data.reservationNo
            Status = $response.data.status
            LatencyMs = $requestTimer.Elapsed.TotalMilliseconds
        }
    } -ThrottleLimit $inventoryCompetitionConcurrency
    $inventoryCompetitionTimer.Stop()
    $inventoryLatencySummary = Get-LatencySummary `
        -Values @($reservationResults | Select-Object -ExpandProperty LatencyMs) `
        -Elapsed $inventoryCompetitionTimer.Elapsed `
        -Concurrency $inventoryCompetitionConcurrency

    $reservedNumbers = @($reservationResults | Where-Object Status -eq 'RESERVED' |
        Select-Object -ExpandProperty ReservationNo)
    $rejectedCount = @($reservationResults | Where-Object Status -eq 'REJECTED').Count
    $expectedInventoryRejections = $inventoryCompetitionAttempts - $inventoryCompetitionSuccesses
    if ($reservedNumbers.Count -ne $inventoryCompetitionSuccesses -or
        $rejectedCount -ne $expectedInventoryRejections) {
        throw "Real MySQL inventory competition was incorrect: reserved=$($reservedNumbers.Count), rejected=$rejectedCount."
    }

    $idempotentRetry = Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations" `
        -Headers $internalHeaders -Body @{
            reservationNo = $reservedNumbers[0]
            orderNo = "SMOKE-ORDER-$($reservedNumbers[0].Split('-')[-1])"
            warehouseId = $inventoryWarehouseId
            expiresAt = $inventoryReservationExpiresAt
            items = @(@{ skuId = $inventorySkuId; quantity = 1 })
        }
    if ($idempotentRetry.data.status -ne 'RESERVED') {
        throw 'An idempotent reservation retry did not return the original result.'
    }

    Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations/$($reservedNumbers[0])/confirm" `
        -Headers $internalHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations/$($reservedNumbers[1])/release" `
        -Headers $internalHeaders -Body @{} | Out-Null
    $stockAfterTransitions = Invoke-RestMethod -Method Get `
        -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$inventorySkuId" `
        -Headers $adminHeaders -TimeoutSec 10
    if ($stockAfterTransitions.data.onHand -ne ($inventoryCompetitionSuccesses - 1) -or
        $stockAfterTransitions.data.reserved -ne ($inventoryCompetitionSuccesses - 2) -or
        $stockAfterTransitions.data.available -ne 1) {
        throw 'Inventory confirm/release transitions broke the stock equation.'
    }

    $tradeStock = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = $tradeMovementNo
            warehouseId = $inventoryWarehouseId
            skuId = $tradeSkuId
            quantityDelta = $tradeStockBaseline
            reason = 'End-to-end trade competition stock'
        }
    if ($tradeStock.data.available -ne $tradeStockBaseline) {
        throw "Trade smoke stock did not create $tradeStockBaseline available units."
    }
    if ($EnableExceptionalPaymentRecoveryVerification) {
        $exceptionStock = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
            -Headers $adminHeaders -Body @{
                movementNo = $exceptionMovementNo
                warehouseId = $inventoryWarehouseId
                skuId = $exceptionSkuId
                quantityDelta = 1
                reason = 'Late payment and exceptional refund recovery verification'
            }
        if ($exceptionStock.data.onHand -ne 1 -or
            $exceptionStock.data.reserved -ne 0 -or
            $exceptionStock.data.available -ne 1) {
            throw 'Exceptional-payment verification stock did not initialize to 1|0|1.'
        }
    }

    $tradeBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/trade"
    $marketingBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/marketing"
    if ($EnableInventoryReservationResponseLossFaultInjection) {
        [DateTimeOffset]::UtcNow.ToString('o') |
            Set-Content -LiteralPath $inventoryResponseLossArmPath -Encoding ascii
        $responseLossOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-inventory-response-loss-$inventoryReservationPrefix"
        } -Body @{
            addressId = $smokeAddressId
            items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        }
        $responseLossOrderNo = [string]$responseLossOrder.data.orderNo
        $responseLossReservationNo = $responseLossOrderNo -replace '^ORD', 'RSV'
        $tradeOrderNumbers += $responseLossOrderNo
        $tradeReservationNumbers += $responseLossReservationNo
        $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
        $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
        if ($responseLossOrder.data.status -ne 'PENDING_PAYMENT') {
            throw "The response-loss order did not recover immediately: status=$($responseLossOrder.data.status)."
        }

        $proxyEvidenceDeadline = (Get-Date).AddSeconds(15)
        do {
            if (Test-Path -LiteralPath $inventoryResponseLossProxyEvidencePath) {
                break
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $proxyEvidenceDeadline)
        if (-not (Test-Path -LiteralPath $inventoryResponseLossProxyEvidencePath)) {
            throw 'The inventory response-loss proxy did not record a dropped response.'
        }
        $proxyEvidence = Get-Content -Raw -LiteralPath $inventoryResponseLossProxyEvidencePath |
            ConvertFrom-Json
        if ($proxyEvidence.method -ne 'POST' -or
            $proxyEvidence.path -ne '/api/v1/inventory/internal/reservations' -or
            $proxyEvidence.reservationNo -ne $responseLossReservationNo -or
            [int]$proxyEvidence.upstreamStatus -ne 200 -or
            [int]$proxyEvidence.upstreamResponseBytes -le 0) {
            throw 'The proxy evidence did not prove a committed reservation response was dropped.'
        }

        $responseLossTradeFact = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e @"
SELECT CONCAT(
    o.status, '|',
    o.recovery_attempts, '|',
    (SELECT COUNT(*) FROM order_status_history h
     WHERE h.order_id = o.id
       AND h.command = 'RESOLVE_STOCK_RESULT'
       AND h.reason = 'RESERVE_RESPONSE_UNKNOWN'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = o.order_no
       AND e.event_type = 'OrderAwaitingPayment'))
FROM trade_order o
WHERE o.order_no = '$responseLossOrderNo';
"@
        if ($LASTEXITCODE -ne 0 -or
            ($responseLossTradeFact | Select-Object -Last 1) -ne 'PENDING_PAYMENT|0|1|1') {
            throw "Trade did not persist the recovered unknown result exactly once: $responseLossTradeFact"
        }

        $responseLossInventoryFact = docker exec -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME -N -B `
            -e @"
SELECT CONCAT(
    r.status, '|',
    (SELECT COUNT(*) FROM stock_movement m
     WHERE m.reservation_no = r.reservation_no
       AND m.movement_type = 'RESERVE'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = r.reservation_no
       AND e.event_type = 'InventoryReserved'))
FROM inventory_reservation r
WHERE r.reservation_no = '$responseLossReservationNo';
"@
        if ($LASTEXITCODE -ne 0 -or
            ($responseLossInventoryFact | Select-Object -Last 1) -ne 'RESERVED|1|1') {
            throw "Inventory committed duplicate or incomplete reservation facts: $responseLossInventoryFact"
        }

        $responseLossMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 10 `
            -Uri ('http://127.0.0.1:' + $tradePort +
                '/actuator/metrics/ecommerce.trade.inventory.reservation.unknown.result.resolutions' +
                '?tag=service%3Atrade-service&tag=dependency%3Ainventory-service' +
                '&tag=operation%3Areserve&tag=outcome%3Arecovered')
        $responseLossRecoveredCount = [double](($responseLossMetric.measurements |
                    Where-Object statistic -eq 'COUNT' | Select-Object -First 1).value)
        if ($responseLossRecoveredCount -lt 1.0) {
            throw 'Trade did not expose the recovered inventory response-loss metric.'
        }

        $inventoryResponseLossEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -Last 1)
            orderNo = $responseLossOrderNo
            reservationNo = $responseLossReservationNo
            proxy = $proxyEvidence
            tradeFact = ($responseLossTradeFact | Select-Object -Last 1)
            inventoryFact = ($responseLossInventoryFact | Select-Object -Last 1)
            recoveredMetricCount = $responseLossRecoveredCount
        }
        $inventoryResponseLossEvidence | ConvertTo-Json -Depth 8 |
            Set-Content -LiteralPath $inventoryResponseLossEvidencePath -Encoding utf8

        $responseLossCanceled = Invoke-JsonPost `
            -Uri "$tradeBaseUrl/orders/$responseLossOrderNo/cancel" `
            -Headers @{ Authorization = "Bearer $accessToken" } `
            -Body @{}
        if ($responseLossCanceled.data.status -ne 'CANCELED') {
            throw 'The response-loss verification order did not cancel cleanly.'
        }
        $responseLossStock = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders `
            -TimeoutSec 10
        if ($responseLossStock.data.onHand -ne $tradeStockBaseline -or
            $responseLossStock.data.reserved -ne 0 -or
            $responseLossStock.data.available -ne $tradeStockBaseline) {
            throw 'The response-loss verification did not restore the inventory baseline after cancellation.'
        }
    }
    if ($EnableTradeMarketingResilienceFaultInjection) {
        Stop-FoundationServices -Ports @($marketingPort)
        Wait-PortAvailable -Port $marketingPort

        $marketingOutageOrderNumbers = @()
        foreach ($outageIndex in 1..4) {
            $marketingOutageOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
                Authorization = "Bearer $accessToken"
                'Idempotency-Key' = "smoke-trade-marketing-outage-$outageIndex-$inventoryReservationPrefix"
            } -Body @{
                addressId = $smokeAddressId
                items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
            }
            if ($marketingOutageOrder.data.status -ne 'PENDING_STOCK') {
                throw "Trade outage order $outageIndex did not remain recoverable while Marketing was unavailable."
            }
            $marketingOutageOrderNumbers += $marketingOutageOrder.data.orderNo
            $tradeOrderNumbers += $marketingOutageOrder.data.orderNo
            $tradeReservationNumbers += ($marketingOutageOrder.data.orderNo -replace '^ORD', 'RSV')

            if ($outageIndex -eq 1) {
                $schedulerIsolationStartedAt = Get-Date
                $schedulerIsolationDeadline = $schedulerIsolationStartedAt.AddSeconds(12)
                $isolatedRecoveryAttempts = 1
                do {
                    $attemptRows = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
                        mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
                        -e "SELECT recovery_attempts FROM trade_order WHERE order_no = '$($marketingOutageOrder.data.orderNo)';"
                    $isolatedRecoveryAttempts = [int]($attemptRows | Select-Object -Last 1)
                    if ($isolatedRecoveryAttempts -ge 2) { break }
                    Start-Sleep -Milliseconds 250
                } while ((Get-Date) -lt $schedulerIsolationDeadline)
                $schedulerIsolationElapsed = ((Get-Date) - $schedulerIsolationStartedAt).TotalSeconds
                if ($isolatedRecoveryAttempts -lt 2 -or $schedulerIsolationElapsed -gt 12.0) {
                    throw "The isolated order-recovery scheduler did not retry within 12 seconds: attempts=$isolatedRecoveryAttempts, elapsed=$schedulerIsolationElapsed."
                }

                $schedulerExecutionsMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                    -Uri "http://127.0.0.1:$tradePort/actuator/metrics/ecommerce.task.scheduler.executions?tag=service%3Atrade-service&tag=task%3Aorder_recovery&tag=result%3Asuccess"
                $schedulerExecutions = [double](($schedulerExecutionsMetric.measurements |
                            Where-Object statistic -eq 'COUNT' | Select-Object -First 1).value)
                $schedulerAgeMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                    -Uri "http://127.0.0.1:$tradePort/actuator/metrics/ecommerce.task.scheduler.completion.age?tag=service%3Atrade-service&tag=task%3Aorder_recovery"
                $schedulerCompletionAge = [double](($schedulerAgeMetric.measurements |
                            Where-Object statistic -eq 'VALUE' | Select-Object -First 1).value)
                $schedulerExecutorMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                    -Uri "http://127.0.0.1:$tradePort/actuator/metrics/executor.active?tag=name%3AtradeOrderRecoveryScheduler"
                if ($schedulerExecutions -lt 1.0 -or $schedulerCompletionAge -gt 5.0 -or
                    $null -eq ($schedulerExecutorMetric.measurements | Where-Object statistic -eq 'VALUE' |
                        Select-Object -First 1)) {
                    throw "Order-recovery scheduler metrics were incomplete: executions=$schedulerExecutions, completionAge=$schedulerCompletionAge."
                }
            }
        }
        $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
        $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
        $marketingOutageOrderSqlList = ($marketingOutageOrderNumbers | ForEach-Object { "'$_'" }) -join ','

        $outageTradeFact = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e "SELECT CONCAT(COUNT(*), '|', SUM(status = 'PENDING_STOCK'), '|', SUM(marketing_lock_no IS NULL), '|', SUM(recovery_attempts >= 1)) FROM trade_order WHERE order_no IN ($marketingOutageOrderSqlList);"
        if ($LASTEXITCODE -ne 0 -or ($outageTradeFact | Select-Object -Last 1) -ne '4|4|4|4') {
            throw 'Trade did not persist four explicit recovery facts for the Marketing outage.'
        }
        $outageReservationCount = docker exec -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME -N -B `
            -e "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no IN ($tradeReservationSqlList);"
        if ($LASTEXITCODE -ne 0 -or [int]($outageReservationCount | Select-Object -Last 1) -ne 0) {
            throw 'Trade reserved inventory before the Marketing pricing fact was available.'
        }

        $marketingCircuitDeadline = (Get-Date).AddSeconds(10)
        $marketingCircuitOpen = 0.0
        do {
            try {
                $openMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                    -Uri "http://127.0.0.1:$tradePort/actuator/metrics/resilience4j.circuitbreaker.state?tag=name%3AtradeMarketingPricingLock&tag=state%3Aopen"
                $marketingCircuitOpen = [double](($openMetric.measurements | Where-Object statistic -eq 'VALUE' |
                            Select-Object -First 1).value)
            }
            catch {
                $marketingCircuitOpen = 0.0
            }
            if ($marketingCircuitOpen -eq 1.0) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $marketingCircuitDeadline)
        if ($marketingCircuitOpen -ne 1.0) {
            throw 'Five Trade to Marketing failures did not open the circuit before the deadline.'
        }

        $marketingRejectedOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-trade-marketing-rejected-$inventoryReservationPrefix"
        } -Body @{
            addressId = $smokeAddressId
            items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        }
        if ($marketingRejectedOrder.data.status -ne 'PENDING_STOCK') {
            throw 'The order rejected by the open Marketing circuit did not remain recoverable.'
        }
        $marketingRejectedOrderNo = $marketingRejectedOrder.data.orderNo
        $tradeOrderNumbers += $marketingRejectedOrderNo
        $tradeReservationNumbers += ($marketingRejectedOrderNo -replace '^ORD', 'RSV')
        $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
        $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
        $marketingResilienceOrderNumbers = @($marketingOutageOrderNumbers) + @($marketingRejectedOrderNo)
        $resilienceOrderSqlList = ($marketingResilienceOrderNumbers | ForEach-Object { "'$_'" }) -join ','

        $marketingRejectionDeadline = (Get-Date).AddSeconds(10)
        $marketingCircuitRejections = 0.0
        do {
            $rejectionMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                -Uri "http://127.0.0.1:$tradePort/actuator/metrics/ecommerce.http.client.resilience.rejections?tag=service%3Atrade-service&tag=dependency%3Amarketing-service&tag=operation%3Apricing_lock&tag=guard%3Acircuit"
            $marketingCircuitRejections = [double](($rejectionMetric.measurements |
                        Where-Object statistic -eq 'COUNT' | Select-Object -First 1).value)
            if ($marketingCircuitRejections -ge 1.0) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $marketingRejectionDeadline)
        if ($marketingCircuitRejections -lt 1.0) {
            throw 'The open Trade to Marketing circuit did not expose a rejected recovery call.'
        }

        Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $marketingJar) `
            -WindowStyle Hidden -RedirectStandardOutput $marketingRecoveryOut `
            -RedirectStandardError $marketingRecoveryErr
        Wait-HttpOk -Uri "http://127.0.0.1:$marketingPort/actuator/health/liveness" | Out-Null
        Start-Sleep -Seconds 11

        $marketingRecoveryDeadline = (Get-Date).AddSeconds(75)
        $recoveredMarketingOrders = 0
        $marketingCircuitClosed = 0.0
        do {
            $recoveredRows = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
                mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
                -e "SELECT COUNT(*) FROM trade_order WHERE order_no IN ($resilienceOrderSqlList) AND status = 'PENDING_PAYMENT' AND marketing_lock_no IS NOT NULL;"
            $recoveredMarketingOrders = [int]($recoveredRows | Select-Object -Last 1)
            $closedMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 5 `
                -Uri "http://127.0.0.1:$tradePort/actuator/metrics/resilience4j.circuitbreaker.state?tag=name%3AtradeMarketingPricingLock&tag=state%3Aclosed"
            $marketingCircuitClosed = [double](($closedMetric.measurements | Where-Object statistic -eq 'VALUE' |
                        Select-Object -First 1).value)
            if ($recoveredMarketingOrders -eq 5 -and $marketingCircuitClosed -eq 1.0) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $marketingRecoveryDeadline)
        if ($recoveredMarketingOrders -ne 5 -or $marketingCircuitClosed -ne 1.0) {
            throw "Trade to Marketing did not converge after recovery: orders=$recoveredMarketingOrders, closed=$marketingCircuitClosed."
        }

        $marketingLockFacts = docker exec -e "MYSQL_PWD=$env:MARKETING_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:MARKETING_DB_USER" $env:MARKETING_DB_NAME -N -B `
            -e "SELECT CONCAT(COUNT(*), '|', COUNT(DISTINCT lock_no)) FROM pricing_lock WHERE order_no IN ($resilienceOrderSqlList);"
        if ($LASTEXITCODE -ne 0 -or ($marketingLockFacts | Select-Object -Last 1) -ne '5|5') {
            throw 'Marketing recovery did not preserve one idempotent pricing lock per order.'
        }

        foreach ($resilienceOrderNo in $marketingResilienceOrderNumbers) {
            $canceledResilienceOrder = Invoke-JsonPost `
                -Uri "$tradeBaseUrl/orders/$resilienceOrderNo/cancel" `
                -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
            if ($canceledResilienceOrder.data.status -ne 'CANCELED') {
                throw "Recovered resilience order $resilienceOrderNo did not cancel cleanly."
            }
        }
        $stockAfterMarketingResilience = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders -TimeoutSec 10
        if ($stockAfterMarketingResilience.data.onHand -ne $tradeStockBaseline -or
            $stockAfterMarketingResilience.data.reserved -ne 0 -or
            $stockAfterMarketingResilience.data.available -ne $tradeStockBaseline) {
            throw 'Trade to Marketing recovery orders did not restore the inventory baseline after cancellation.'
        }
        $tradeMarketingResilienceEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            dependency = 'trade-service -> marketing-service'
            outage = [ordered]@{
                recoverableOrders = $marketingOutageOrderNumbers.Count
                ownerTradeFact = [string]($outageTradeFact | Select-Object -Last 1)
                prematureInventoryReservations = [int](
                    $outageReservationCount | Select-Object -Last 1)
                schedulerRecoveryAttempts = $isolatedRecoveryAttempts
                schedulerIsolationElapsedSeconds = [Math]::Round(
                    $schedulerIsolationElapsed, 3)
                schedulerExecutionsMetric = $schedulerExecutions
                schedulerCompletionAgeSeconds = $schedulerCompletionAge
                circuitOpenMetric = $marketingCircuitOpen
                circuitRejectionCount = $marketingCircuitRejections
            }
            recovery = [ordered]@{
                recoveredOrders = $recoveredMarketingOrders
                distinctPricingLockFact = [string](
                    $marketingLockFacts | Select-Object -Last 1)
                finalCircuitClosedMetric = $marketingCircuitClosed
                canceledOrders = $marketingResilienceOrderNumbers.Count
                stockEquation = [ordered]@{
                    onHand = $stockAfterMarketingResilience.data.onHand
                    reserved = $stockAfterMarketingResilience.data.reserved
                    available = $stockAfterMarketingResilience.data.available
                }
            }
        }
    }

    $validFrom = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToString('o')
    $validUntil = [DateTimeOffset]::UtcNow.AddHours(2).ToString('o')
    $marketingRules = @(
        @{ Code = "$marketingRulePrefix-COUPON"; Type = 'COUPON'; Threshold = 40.00; Discount = 5.00; Order = 10; Regions = @(@{ level = 'DISTRICT'; regionCode = '330106' }) },
        @{ Code = "$marketingRulePrefix-RED"; Type = 'RED_PACKET'; Threshold = 0.00; Discount = 2.00; Order = 20; Regions = @(@{ level = 'PROVINCE'; regionCode = '330000' }) },
        @{ Code = "$marketingRulePrefix-SUBSIDY"; Type = 'SUBSIDY'; Threshold = 0.00; Discount = 1.00; Order = 30; Regions = @() }
    )
    foreach ($rule in $marketingRules) {
        Invoke-JsonPost -Uri "$marketingBaseUrl/admin/rules" -Headers $adminHeaders -Body @{
            ruleCode = $rule.Code
            name = $rule.Code
            benefitType = $rule.Type
            thresholdAmount = $rule.Threshold
            discountAmount = $rule.Discount
            stackOrder = $rule.Order
            validFrom = $validFrom
            validUntil = $validUntil
            regions = $rule.Regions
        } | Out-Null
        $paidBenefit = Invoke-JsonPost -Uri "$marketingBaseUrl/admin/benefits" -Headers $adminHeaders -Body @{
            userId = [long]$smokeUserId
            ruleCode = $rule.Code
            grantKey = "$marketingRulePrefix-PAID-$($rule.Type)"
        }
        $cancelBenefit = Invoke-JsonPost -Uri "$marketingBaseUrl/admin/benefits" -Headers $adminHeaders -Body @{
            userId = [long]$smokeUserId
            ruleCode = $rule.Code
            grantKey = "$marketingRulePrefix-CANCEL-$($rule.Type)"
        }
        $marketingBenefitNos += $paidBenefit.data.benefitNo
        $marketingCancelBenefitNos += $cancelBenefit.data.benefitNo
    }
    if ($EnableExceptionalPaymentRecoveryVerification) {
        $exceptionBenefit = Invoke-JsonPost `
            -Uri "$marketingBaseUrl/admin/benefits" -Headers $adminHeaders -Body @{
                userId = [long]$smokeUserId
                ruleCode = "$marketingRulePrefix-SUBSIDY"
                grantKey = "$marketingRulePrefix-PAYMENT-EXCEPTION"
            }
        $marketingExceptionBenefitNos += $exceptionBenefit.data.benefitNo
        if ($marketingExceptionBenefitNos.Count -ne 1) {
            throw 'Exceptional-payment verification did not create exactly one isolated benefit.'
        }
    }

    $wrongZoneMarketingOrderNo = "$marketingRulePrefix-WRONG-ZONE"
    $marketingWrongZoneResponse = Invoke-JsonPostRaw `
        -Uri "http://127.0.0.1:$marketingPort/api/v1/marketing/internal/pricing-locks" `
        -Headers @{
            'X-Internal-Service' = 'trade-service'
            'X-Internal-Token' = $env:PAYMENT_INTERNAL_SERVICE_TOKEN
        } -Body @{
            orderNo = $wrongZoneMarketingOrderNo
            userId = [long]$smokeUserId
            originalAmount = 49.90
            deliveryRegion = @{
                provinceCode = '330000'
                cityCode = '330100'
                districtCode = '330106'
            }
            lines = @(@{ lineNo = 1; skuId = $tradeSkuId; lineAmount = 49.90 })
            benefitNos = $marketingBenefitNos
        }
    $marketingWrongZoneStatus = [int]$marketingWrongZoneResponse.StatusCode
    $marketingWrongZoneRows = [int](Get-MySqlSingleColumn `
            -Database $env:MARKETING_DB_NAME `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM pricing_lock WHERE order_no = '$wrongZoneMarketingOrderNo';" |
            Select-Object -Last 1)
    if ($marketingWrongZoneStatus -ne 401 -or $marketingWrongZoneRows -ne 0) {
        throw "Marketing trust-zone isolation failed: HTTP $marketingWrongZoneStatus, rows=$marketingWrongZoneRows."
    }

    $marketingPreview = Invoke-JsonPostRaw -Uri "$marketingBaseUrl/pricing-previews" -Headers @{
        Authorization = "Bearer $accessToken"
    } -Body @{
        originalAmount = 49.90
        deliveryRegion = @{
            provinceCode = '330000'
            cityCode = '330100'
            districtCode = '330106'
        }
        lines = @(@{ lineNo = 1; skuId = $tradeSkuId; lineAmount = 49.90 })
        benefitNos = $marketingCancelBenefitNos
    }
    if ([int]$marketingPreview.StatusCode -ne 200) {
        $marketingCancelBenefitSqlList = ($marketingCancelBenefitNos |
                ForEach-Object { "'$_'" }) -join ','
        $eligibilityFacts = @(Get-MySqlSingleColumn `
                -Database $env:MARKETING_DB_NAME `
                -User $env:MARKETING_DB_USER `
                -Password $env:MARKETING_DB_PASSWORD `
                -Query @"
SELECT CONCAT_WS('|',
    ub.benefit_no,
    ub.user_id = $smokeUserId,
    ub.status = 'AVAILABLE',
    r.status = 'ACTIVE',
    CURRENT_TIMESTAMP(3) >= r.valid_from,
    CURRENT_TIMESTAMP(3) < r.valid_until,
    49.90 >= r.threshold_amount,
    (
        NOT EXISTS (
            SELECT 1 FROM marketing_rule_region empty_region
            WHERE empty_region.rule_id = r.id
        )
        OR EXISTS (
            SELECT 1 FROM marketing_rule_region matched_region
            WHERE matched_region.rule_id = r.id
              AND (
                  (matched_region.region_level = 'PROVINCE' AND matched_region.region_code = '330000')
                  OR (matched_region.region_level = 'CITY' AND matched_region.region_code = '330100')
                  OR (matched_region.region_level = 'DISTRICT' AND matched_region.region_code = '330106')
              )
        )
    ),
    DATE_FORMAT(r.valid_from, '%Y-%m-%dT%H:%i:%s.%f'),
    DATE_FORMAT(CURRENT_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%f'),
    DATE_FORMAT(r.valid_until, '%Y-%m-%dT%H:%i:%s.%f')
)
FROM user_benefit ub
JOIN marketing_rule r ON r.id = ub.rule_id
WHERE ub.benefit_no IN ($marketingCancelBenefitSqlList)
ORDER BY ub.benefit_no;
"@)
        throw "Marketing preview rejected freshly granted stacked benefits: HTTP=$($marketingPreview.StatusCode), body=$($marketingPreview.Content), facts=$($eligibilityFacts -join ';')."
    }
    $marketingPreviewPayload = $marketingPreview.Content | ConvertFrom-Json -Depth 20
    if ([decimal]$marketingPreviewPayload.data.discountAmount -ne 8.00) {
        throw "Marketing preview returned an unexpected discount: body=$($marketingPreview.Content)."
    }
    $marketingRuleWindowFact = Get-MySqlSingleColumn `
        -Database $env:MARKETING_DB_NAME `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -Query @"
SET time_zone = '+00:00';
SELECT CONCAT(
    MIN(UNIX_TIMESTAMP(valid_from)), '|',
    MAX(UNIX_TIMESTAMP(valid_until)), '|',
    COUNT(*)
)
FROM marketing_rule
WHERE rule_code LIKE '$marketingRulePrefix-%';
"@ | Select-Object -Last 1
    $marketingRuleWindowParts = $marketingRuleWindowFact -split '\|'
    $expectedValidFromEpoch = [DateTimeOffset]::Parse($validFrom).ToUnixTimeSeconds()
    $expectedValidUntilEpoch = [DateTimeOffset]::Parse($validUntil).ToUnixTimeSeconds()
    $persistedValidFromEpoch = [decimal]$marketingRuleWindowParts[0]
    $persistedValidUntilEpoch = [decimal]$marketingRuleWindowParts[1]
    $persistedRuleCount = [int]$marketingRuleWindowParts[2]
    $calculatedAtMatch = [regex]::Match(
        $marketingPreview.Content,
        '"calculatedAt"\s*:\s*"(?<value>[^"]+)"')
    if (-not $calculatedAtMatch.Success) {
        throw "Marketing preview omitted its database evaluation time: body=$($marketingPreview.Content)."
    }
    $roundTripStyles = [Globalization.DateTimeStyles]::RoundtripKind
    $calculatedAt = [DateTimeOffset]::Parse(
        $calculatedAtMatch.Groups['value'].Value,
        [Globalization.CultureInfo]::InvariantCulture,
        $roundTripStyles)
    $requestedValidFrom = [DateTimeOffset]::Parse(
        $validFrom,
        [Globalization.CultureInfo]::InvariantCulture,
        $roundTripStyles)
    $requestedValidUntil = [DateTimeOffset]::Parse(
        $validUntil,
        [Globalization.CultureInfo]::InvariantCulture,
        $roundTripStyles)
    if ([Math]::Abs($persistedValidFromEpoch - $expectedValidFromEpoch) -gt 1 -or
        [Math]::Abs($persistedValidUntilEpoch - $expectedValidUntilEpoch) -gt 1 -or
        $persistedRuleCount -ne 3 -or
        $calculatedAt -lt $requestedValidFrom -or
        $calculatedAt -ge $requestedValidUntil) {
        throw "Marketing database time contract diverged: persisted=$marketingRuleWindowFact, calculatedAt=$calculatedAt."
    }
    [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        jdbcContract = [ordered]@{
            connectionTimeZone = 'UTC'
            forceConnectionTimeZoneToSession = $true
        }
        requestedWindow = [ordered]@{
            validFromUtc = $validFrom
            validUntilUtc = $validUntil
        }
        ownerDatabaseFacts = [ordered]@{
            ruleCount = $persistedRuleCount
            validFromEpoch = $persistedValidFromEpoch
            validUntilEpoch = $persistedValidUntilEpoch
        }
        applicationEvaluation = [ordered]@{
            calculatedAtUtc = $calculatedAt.ToString('o')
            withinPersistedWindow = $true
            appliedBenefits = @($marketingPreviewPayload.data.appliedBenefits).Count
            discountAmount = [decimal]$marketingPreviewPayload.data.discountAmount
        }
    } | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $databaseTimeContractEvidencePath -Encoding utf8

    $marketingCancelOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-marketing-cancel-$inventoryReservationPrefix"
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        benefitNos = $marketingCancelBenefitNos
    }
    $marketingCancelOrderNo = $marketingCancelOrder.data.orderNo
    $tradeOrderNumbers += $marketingCancelOrderNo
    $tradeReservationNumbers += ($marketingCancelOrderNo -replace '^ORD', 'RSV')
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    if ($marketingCancelOrder.data.status -ne 'PENDING_PAYMENT' -or
        [decimal]$marketingCancelOrder.data.priceSnapshot.discountAmount -ne 8.00) {
        $actualResponse = $marketingCancelOrder | ConvertTo-Json -Depth 12 -Compress
        throw "Marketing cancellation order did not lock the stacked benefits: response=$actualResponse."
    }
    $marketingCanceled = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$marketingCancelOrderNo/cancel" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ($marketingCanceled.data.status -ne 'CANCELED') {
        throw 'Marketing cancellation order did not cancel.'
    }
    $marketingReleaseDeadline = (Get-Date).AddSeconds(45)
    do {
        $customerBenefits = Invoke-RestMethod -Method Get -Uri "$marketingBaseUrl/benefits" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $releasedBenefits = @($customerBenefits.data | Where-Object {
                $_.benefitNo -in $marketingCancelBenefitNos -and $_.status -eq 'AVAILABLE'
            }).Count
        if ($releasedBenefits -eq 3) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $marketingReleaseDeadline)
    if ($releasedBenefits -ne 3) {
        throw 'OrderCanceled did not release all marketing benefits.'
    }

    $marketingPaidOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-marketing-paid-$inventoryReservationPrefix"
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        benefitNos = $marketingBenefitNos
    }
    $marketingOrderNo = $marketingPaidOrder.data.orderNo
    $tradeOrderNumbers += $marketingOrderNo
    $tradeReservationNumbers += ($marketingOrderNo -replace '^ORD', 'RSV')
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    if ($marketingPaidOrder.data.status -ne 'PENDING_PAYMENT' -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.originalAmount -ne 49.90 -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.discountAmount -ne 8.00 -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.payableAmount -ne 41.90 -or
        @($marketingPaidOrder.data.priceSnapshot.allocations).Count -ne 3) {
        throw 'Marketing price and allocation snapshots were not persisted correctly.'
    }
    $tradeWrongZoneResponse = Invoke-WebRequest -Method Get `
        -Uri "http://127.0.0.1:$tradePort/api/v1/trade/internal/orders/$marketingOrderNo/payment-context" `
        -Headers @{
            'X-Internal-Service' = 'payment-service'
            'X-Internal-Token' = $env:TRADE_INTERNAL_SERVICE_TOKEN
        } -SkipHttpErrorCheck -TimeoutSec 10
    $tradeWrongZoneStatus = [int]$tradeWrongZoneResponse.StatusCode
    if ($tradeWrongZoneStatus -ne 401) {
        throw "Trade accepted the Trade trust-zone credential as Payment: HTTP $tradeWrongZoneStatus."
    }

    $tradeProductIdForThreads = $productId
    $tradeSkuIdForThreads = $tradeSkuId
    $tradeCompetitionTimer = [Diagnostics.Stopwatch]::StartNew()
    $tradeResults = 0..($tradeCompetitionAttempts - 1) | ForEach-Object -Parallel {
        $index = $_
        $idempotencyKey = "smoke-trade-$index-$using:inventoryReservationPrefix"
        $body = @{
            addressId = $using:smokeAddressId
            items = @(@{
                productId = $using:tradeProductIdForThreads
                skuId = $using:tradeSkuIdForThreads
                quantity = 1
            })
        } | ConvertTo-Json -Compress -Depth 10
        $requestTimer = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod -Method Post -Uri "$using:tradeBaseUrl/orders" `
            -Headers @{
                Authorization = "Bearer $using:accessToken"
                'Idempotency-Key' = $idempotencyKey
            } -ContentType 'application/json' -Body $body -TimeoutSec 30
        $requestTimer.Stop()
        [pscustomobject]@{
            Key = $idempotencyKey
            OrderNo = $response.data.orderNo
            Status = $response.data.status
            LatencyMs = $requestTimer.Elapsed.TotalMilliseconds
        }
    } -ThrottleLimit $tradeCompetitionConcurrency
    $tradeCompetitionTimer.Stop()
    $tradeLatencySummary = Get-LatencySummary `
        -Values @($tradeResults | Select-Object -ExpandProperty LatencyMs) `
        -Elapsed $tradeCompetitionTimer.Elapsed `
        -Concurrency $tradeCompetitionConcurrency

    $payableOrders = @($tradeResults | Where-Object Status -eq 'PENDING_PAYMENT')
    $unexpectedTradeResponses = @($tradeResults |
        Where-Object Status -notin @('PENDING_PAYMENT', 'PENDING_STOCK', 'CLOSED'))
    $competitionOrderNumbers = @($tradeResults | Select-Object -ExpandProperty OrderNo)
    $competitionOrderSqlList = ($competitionOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeOrderNumbers += $competitionOrderNumbers
    $tradeReservationNumbers = @($tradeOrderNumbers | ForEach-Object { $_ -replace '^ORD', 'RSV' })
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    $expectedClosedTrades = $tradeCompetitionAttempts - $tradeCompetitionSuccesses
    if ($payableOrders.Count -ne $tradeCompetitionSuccesses -or $unexpectedTradeResponses.Count -ne 0) {
        throw "Trade competition returned an invalid immediate result: payable=$($payableOrders.Count), unexpected=$($unexpectedTradeResponses.Count)."
    }

    # A losing order may be returned while its synchronous inventory rejection is still being
    # persisted as CLOSED. Treat PENDING_STOCK as an explicit recovery state, then require every
    # order to converge in MySQL before accepting the capacity result.
    $tradeCompetitionDeadline = (Get-Date).AddSeconds(60)
    $tradeCompetitionFact = ''
    do {
        $tradeCompetitionFact = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e "SELECT CONCAT(SUM(status = 'PENDING_PAYMENT'), '|', SUM(status = 'CLOSED'), '|', SUM(status = 'PENDING_STOCK'), '|', COUNT(*)) FROM trade_order WHERE order_no IN ($competitionOrderSqlList);"
        if ($LASTEXITCODE -ne 0) {
            throw 'Trade competition final-state query failed.'
        }
        $tradeCompetitionParts = ($tradeCompetitionFact | Select-Object -Last 1) -split '\|'
        $closedTradeCount = [int]$tradeCompetitionParts[1]
        $pendingStockTradeCount = [int]$tradeCompetitionParts[2]
        if ($closedTradeCount -eq $expectedClosedTrades -and $pendingStockTradeCount -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $tradeCompetitionDeadline)

    if ([int]$tradeCompetitionParts[0] -ne $tradeCompetitionSuccesses -or
        $closedTradeCount -ne $expectedClosedTrades -or
        $pendingStockTradeCount -ne 0 -or
        [int]$tradeCompetitionParts[3] -ne $tradeCompetitionAttempts) {
        throw "Trade competition did not converge: fact=$($tradeCompetitionParts -join '|')."
    }

    if ($EnableCapacityBaseline) {
        $concurrentOrderKey = "capacity-same-key-$inventoryReservationPrefix"
        $concurrentOrderBody = @{
            addressId = $smokeAddressId
            items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        } | ConvertTo-Json -Compress -Depth 10
        $sameKeyTimer = [Diagnostics.Stopwatch]::StartNew()
        $sameKeyResults = 1..100 | ForEach-Object -Parallel {
            $requestTimer = [Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-RestMethod -Method Post -Uri "$using:tradeBaseUrl/orders" `
                -Headers @{
                    Authorization = "Bearer $using:accessToken"
                    'Idempotency-Key' = $using:concurrentOrderKey
                } -ContentType 'application/json' -Body $using:concurrentOrderBody -TimeoutSec 30
            $requestTimer.Stop()
            [pscustomobject]@{
                OrderNo = $response.data.orderNo
                Status = $response.data.status
                LatencyMs = $requestTimer.Elapsed.TotalMilliseconds
            }
        } -ThrottleLimit 100
        $sameKeyTimer.Stop()
        $tradeIdempotencyLatencySummary = Get-LatencySummary `
            -Values @($sameKeyResults | Select-Object -ExpandProperty LatencyMs) `
            -Elapsed $sameKeyTimer.Elapsed -Concurrency 100
        $sameKeyOrderNumbers = @($sameKeyResults | Select-Object -ExpandProperty OrderNo -Unique)
        if ($sameKeyOrderNumbers.Count -ne 1 -or
            @($sameKeyResults | Where-Object Status -notin @('PENDING_STOCK', 'CLOSED')).Count -ne 0) {
            throw "Concurrent order idempotency diverged: distinctOrders=$($sameKeyOrderNumbers.Count)."
        }
        $sameKeyOrderNo = $sameKeyOrderNumbers[0]
        $sameKeyReservationNo = $sameKeyOrderNo -replace '^ORD', 'RSV'
        $sameKeyDeadline = (Get-Date).AddSeconds(30)
        do {
            $sameKeyFinal = Invoke-RestMethod -Method Get `
                -Uri "$tradeBaseUrl/orders/$sameKeyOrderNo" `
                -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
            if ($sameKeyFinal.data.status -eq 'CLOSED') { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $sameKeyDeadline)
        if ($sameKeyFinal.data.status -ne 'CLOSED') {
            throw 'The concurrently submitted idempotent order did not converge to CLOSED.'
        }
        $sameKeyTradeFacts = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e "SELECT CONCAT(COUNT(*), '|', SUM(status = 'CLOSED')) FROM trade_order WHERE user_id = $smokeUserId AND idempotency_key = '$concurrentOrderKey';"
        $sameKeyMarketingFacts = docker exec -e "MYSQL_PWD=$env:MARKETING_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:MARKETING_DB_USER" $env:MARKETING_DB_NAME -N -B `
            -e "SELECT COUNT(*) FROM pricing_lock WHERE order_no = '$sameKeyOrderNo';"
        $sameKeyInventoryFacts = docker exec -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME -N -B `
            -e "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no = '$sameKeyReservationNo';"
        if (($sameKeyTradeFacts | Select-Object -Last 1) -ne '1|1' -or
            [int]($sameKeyMarketingFacts | Select-Object -Last 1) -ne 1 -or
            [int]($sameKeyInventoryFacts | Select-Object -Last 1) -ne 1) {
            throw 'Concurrent order idempotency created duplicate Trade, Marketing, or Inventory facts.'
        }
        $tradeOrderNumbers += $sameKeyOrderNo
        $tradeReservationNumbers += $sameKeyReservationNo
        $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
        $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    }

    $retryOrder = $payableOrders[0]
    $tradeRetry = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = $retryOrder.Key
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
    }
    if ($tradeRetry.data.orderNo -ne $retryOrder.OrderNo -or $tradeRetry.data.status -ne 'PENDING_PAYMENT') {
        throw 'Trade order idempotency did not return the original order.'
    }

    $canceledTrade = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$($retryOrder.OrderNo)/cancel" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ($canceledTrade.data.status -ne 'CANCELED') {
        throw 'Trade cancellation did not complete its inventory release.'
    }
    $tradeStockAfterCancel = Invoke-RestMethod -Method Get `
        -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
        -Headers $adminHeaders -TimeoutSec 10
    if ($tradeStockAfterCancel.data.onHand -ne $tradeStockBaseline -or
        $tradeStockAfterCancel.data.reserved -ne $tradeCompetitionSuccesses -or
        $tradeStockAfterCancel.data.available -ne 1) {
        throw 'Trade cancellation broke the inventory equation.'
    }

    $paymentOrder = [pscustomobject]@{ OrderNo = $marketingOrderNo }
    $paymentBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/payment"
    if ($EnableSynchronousResilienceFaultInjection) {
        Stop-FoundationServices -Ports @($tradePort)
        Wait-PortAvailable -Port $tradePort

        $failureLatenciesSeconds = @()
        foreach ($failureIndex in 1..5) {
            $failureTimer = [Diagnostics.Stopwatch]::StartNew()
            $failureResponse = Invoke-JsonPostRaw -Uri "$paymentBaseUrl/payments" -Headers @{
                Authorization = "Bearer $accessToken"
                'Idempotency-Key' = "smoke-payment-resilience-$failureIndex-$inventoryReservationPrefix"
            } -Body @{
                orderNo = $paymentOrder.OrderNo
                channel = 'MOCK'
            }
            $failureTimer.Stop()
            $failurePayload = $failureResponse.Content | ConvertFrom-Json
            if ([int]$failureResponse.StatusCode -ne 503 -or
                $failurePayload.code -ne 'REMOTE_DEPENDENCY_UNAVAILABLE') {
                throw "Trade outage attempt $failureIndex did not return the explicit unavailable result."
            }
            if ($failureTimer.Elapsed.TotalSeconds -gt 8) {
                throw "Trade outage attempt $failureIndex exceeded the bounded synchronous-call budget."
            }
            $failureLatenciesSeconds += $failureTimer.Elapsed.TotalSeconds
        }

        $rejectionTimer = [Diagnostics.Stopwatch]::StartNew()
        $rejectionResponse = Invoke-JsonPostRaw -Uri "$paymentBaseUrl/payments" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-payment-resilience-rejected-$inventoryReservationPrefix"
        } -Body @{
            orderNo = $paymentOrder.OrderNo
            channel = 'MOCK'
        }
        $rejectionTimer.Stop()
        $rejectionPayload = $rejectionResponse.Content | ConvertFrom-Json
        if ([int]$rejectionResponse.StatusCode -ne 503 -or
            $rejectionPayload.code -ne 'REMOTE_DEPENDENCY_UNAVAILABLE' -or
            $rejectionTimer.Elapsed.TotalSeconds -gt 2) {
            throw 'The open payment-to-trade circuit did not reject the next call promptly and explicitly.'
        }

        $openStateMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 10 `
            -Uri "http://127.0.0.1:$paymentPort/actuator/metrics/resilience4j.circuitbreaker.state?tag=name%3ApaymentTradePaymentContext&tag=state%3Aopen"
        $openStateValue = [double](($openStateMetric.measurements | Where-Object statistic -eq 'VALUE' |
                    Select-Object -First 1).value)
        if ($openStateValue -ne 1.0) {
            throw "The payment-to-trade circuit did not expose OPEN through Actuator: value=$openStateValue."
        }

        $rejectionMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 10 `
            -Uri "http://127.0.0.1:$paymentPort/actuator/metrics/ecommerce.http.client.resilience.rejections?tag=service%3Apayment-service&tag=dependency%3Atrade-service&tag=operation%3Apayment_context&tag=guard%3Acircuit"
        $circuitRejections = [double](($rejectionMetric.measurements | Where-Object statistic -eq 'COUNT' |
                    Select-Object -First 1).value)
        if ($circuitRejections -lt 1.0) {
            throw 'The payment-to-trade circuit rejection counter was not incremented.'
        }

        $unexpectedPaymentRows = docker exec -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME -N -B `
            -e "SELECT COUNT(*) FROM payment_order WHERE order_no = '$($paymentOrder.OrderNo)';"
        $unexpectedPaymentRowCount = [int]($unexpectedPaymentRows | Select-Object -Last 1)
        if ($LASTEXITCODE -ne 0 -or $unexpectedPaymentRowCount -ne 0) {
            throw 'Payment persisted data while the authoritative Trade fact was unavailable.'
        }

        # The outage above is an intentional hard process termination. Preserve the
        # distributed-ID split-brain fence and wait for its MySQL lease to expire;
        # never delete the lease or start another process with the same active worker.
        Wait-DistributedIdWorkerLeaseExpiry `
            -Namespace $tradeDistributedIdNamespace `
            -WorkerId $tradeDistributedIdWorkerId
        Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $tradeJar) `
            -WindowStyle Hidden -RedirectStandardOutput $tradeRecoveryOut -RedirectStandardError $tradeRecoveryErr
        Wait-HttpOk -Uri "http://127.0.0.1:$tradePort/actuator/health/liveness" | Out-Null
        Start-Sleep -Seconds 11
    }

    $createdPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-payment-$inventoryReservationPrefix"
    } -Body @{
        orderNo = $paymentOrder.OrderNo
        channel = 'MOCK'
    }
    $paymentNo = $createdPayment.data.paymentNo
    if (-not $paymentNo -or $createdPayment.data.status -ne 'PROCESSING') {
        throw 'Payment creation did not produce a processing payment order.'
    }
    if ($EnableSynchronousResilienceFaultInjection) {
        $halfOpenStateMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 10 `
            -Uri "http://127.0.0.1:$paymentPort/actuator/metrics/resilience4j.circuitbreaker.state?tag=name%3ApaymentTradePaymentContext&tag=state%3Ahalf_open"
        $halfOpenStateValue = [double](($halfOpenStateMetric.measurements | Where-Object statistic -eq 'VALUE' |
                    Select-Object -First 1).value)
        if ($halfOpenStateValue -ne 1.0) {
            throw "The first successful Trade recovery probe did not leave the circuit HALF_OPEN: value=$halfOpenStateValue."
        }

        $recoveredRetry = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-payment-$inventoryReservationPrefix"
        } -Body @{
            orderNo = $paymentOrder.OrderNo
            channel = 'MOCK'
        }
        if ($recoveredRetry.data.paymentNo -ne $paymentNo -or
            $recoveredRetry.data.status -ne 'PROCESSING') {
            throw 'The recovered payment request did not preserve payment idempotency.'
        }

        if ($payableOrders.Count -lt 2) {
            throw 'The synchronous resilience scenario requires a second payable order for HALF_OPEN recovery.'
        }
        $resilienceProbePayment = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-payment-resilience-probe-$inventoryReservationPrefix"
        } -Body @{
            orderNo = $payableOrders[1].OrderNo
            channel = 'MOCK'
        }
        $resilienceProbePaymentNo = $resilienceProbePayment.data.paymentNo
        if (-not $resilienceProbePaymentNo -or
            $resilienceProbePayment.data.status -ne 'PROCESSING') {
            throw 'The second HALF_OPEN Trade recovery probe did not create a processing payment.'
        }

        $closedStateMetric = Invoke-RestMethod -Method Get -Headers $adminHeaders -TimeoutSec 10 `
            -Uri "http://127.0.0.1:$paymentPort/actuator/metrics/resilience4j.circuitbreaker.state?tag=name%3ApaymentTradePaymentContext&tag=state%3Aclosed"
        $closedStateValue = [double](($closedStateMetric.measurements | Where-Object statistic -eq 'VALUE' |
                    Select-Object -First 1).value)
        if ($closedStateValue -ne 1.0) {
            throw "The payment-to-trade circuit did not close after Trade recovered: value=$closedStateValue."
        }
        $synchronousResilienceEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            dependency = 'payment-service -> trade-service'
            outage = [ordered]@{
                attempts = $failureLatenciesSeconds.Count
                httpStatus = 503
                responseCode = 'REMOTE_DEPENDENCY_UNAVAILABLE'
                maximumLatencySeconds = [Math]::Round(
                    ($failureLatenciesSeconds | Measure-Object -Maximum).Maximum, 3)
                openCircuitRejectionLatencySeconds = [Math]::Round(
                    $rejectionTimer.Elapsed.TotalSeconds, 3)
                circuitOpenMetric = $openStateValue
                circuitRejectionCount = $circuitRejections
                paymentRowsWritten = $unexpectedPaymentRowCount
            }
            recovery = [ordered]@{
                distributedIdNamespace = $tradeDistributedIdNamespace
                distributedIdWorkerId = $tradeDistributedIdWorkerId
                firstProbeCircuitHalfOpenMetric = $halfOpenStateValue
                firstPaymentNo = $paymentNo
                idempotentRetryPaymentNo = $recoveredRetry.data.paymentNo
                secondProbePaymentNo = $resilienceProbePaymentNo
                finalCircuitClosedMetric = $closedStateValue
            }
        }
    }

    $callbackEventId = "SMOKE-PAY-EVT-$([Guid]::NewGuid().ToString('N'))"
    $callbackTransactionNo = "SMOKE-PAY-TXN-$([Guid]::NewGuid().ToString('N'))"
    $callbackTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $callbackAmount = ([decimal]$createdPayment.data.amount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $callbackCanonical = "$paymentNo|$callbackEventId|$callbackTransactionNo|SUCCESS|$callbackAmount|$callbackTimestamp"
    $callbackSignature = Get-HmacSha256Hex -Value $callbackCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
    $callbackBody = @{
        paymentNo = $paymentNo
        externalEventId = $callbackEventId
        externalTransactionNo = $callbackTransactionNo
        status = 'SUCCESS'
        amount = [decimal]$createdPayment.data.amount
        timestamp = $callbackTimestamp
        signature = $callbackSignature
    }
    if ($EnablePaymentInventoryConfirmationFaultInjection) {
        Stop-FoundationServices -Ports @($inventoryPort)
        Wait-PortAvailable -Port $inventoryPort
    }
    if ($EnableCapacityBaseline) {
        $callbackJson = $callbackBody | ConvertTo-Json -Compress -Depth 10
        $callbackTimer = [Diagnostics.Stopwatch]::StartNew()
        $paymentCallbackResults = 1..100 | ForEach-Object -Parallel {
            $requestTimer = [Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-RestMethod -Method Post -Uri "$using:paymentBaseUrl/callbacks/mock" `
                -ContentType 'application/json' -Body $using:callbackJson -TimeoutSec 30
            $requestTimer.Stop()
            [pscustomobject]@{
                PaymentNo = $response.data.paymentNo
                Status = $response.data.status
                LatencyMs = $requestTimer.Elapsed.TotalMilliseconds
            }
        } -ThrottleLimit 100
        $callbackTimer.Stop()
        $paymentCallbackLatencySummary = Get-LatencySummary `
            -Values @($paymentCallbackResults | Select-Object -ExpandProperty LatencyMs) `
            -Elapsed $callbackTimer.Elapsed -Concurrency 100
        $paidPayment = [pscustomobject]@{ data = $paymentCallbackResults[0] }
        $duplicateCallback = [pscustomobject]@{ data = $paymentCallbackResults[-1] }
        if (@($paymentCallbackResults | Where-Object {
                    $_.PaymentNo -ne $paymentNo -or $_.Status -ne 'SUCCESS'
                }).Count -ne 0) {
            throw 'One or more concurrent signed payment callbacks diverged from the idempotent result.'
        }
    }
    else {
        $paidPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock" -Body $callbackBody
        $duplicateCallback = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock" -Body $callbackBody
    }
    if ($paidPayment.data.status -ne 'SUCCESS' -or $duplicateCallback.data.status -ne 'SUCCESS') {
        throw 'Signed payment callback or its idempotent retry did not succeed.'
    }
    if ($EnablePaymentInventoryConfirmationFaultInjection) {
        $paymentEventId = $null
        $preRecoveryTradeStatus = $null
        $preRecoveryReservationStatus = $null
        $preRecoveryOrderPaidCount = -1
        $preRecoveryConsumedCount = -1
        $preRecoveryConfirmingHistoryCount = -1
        $confirmationFailureDeadline = (Get-Date).AddSeconds(90)
        do {
            $paymentEventId = Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT id FROM outbox_event WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded' AND status = 'PUBLISHED';" |
                Select-Object -Last 1
            $preRecoveryTradeStatus = Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query "SELECT status FROM trade_order WHERE order_no = '$($paymentOrder.OrderNo)';" |
                Select-Object -Last 1
            $preRecoveryReservationStatus = Get-MySqlSingleColumn `
                -Database $env:INVENTORY_DB_NAME `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Query "SELECT status FROM inventory_reservation WHERE reservation_no = '$($paymentOrder.OrderNo -replace '^ORD', 'RSV')';" |
                Select-Object -Last 1
            $preRecoveryOrderPaidCount = [int](Get-MySqlSingleColumn `
                    -Database $env:TRADE_DB_NAME `
                    -User $env:TRADE_DB_USER `
                    -Password $env:TRADE_DB_PASSWORD `
                    -Query "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = '$($paymentOrder.OrderNo)' AND event_type = 'OrderPaid';" |
                    Select-Object -Last 1)
            $preRecoveryConfirmingHistoryCount = [int](Get-MySqlSingleColumn `
                    -Database $env:TRADE_DB_NAME `
                    -User $env:TRADE_DB_USER `
                    -Password $env:TRADE_DB_PASSWORD `
                    -Query "SELECT COUNT(*) FROM order_status_history WHERE order_id = (SELECT id FROM trade_order WHERE order_no = '$($paymentOrder.OrderNo)') AND to_status = 'PAYMENT_CONFIRMING';" |
                    Select-Object -Last 1)
            $preRecoveryConsumedCount = if ($paymentEventId) {
                [int](Get-MySqlSingleColumn `
                        -Database $env:TRADE_DB_NAME `
                        -User $env:TRADE_DB_USER `
                        -Password $env:TRADE_DB_PASSWORD `
                        -Query "SELECT COUNT(*) FROM consumed_event WHERE event_id = '$paymentEventId' AND consumer_group = 'trade-payment-succeeded-v1';" |
                        Select-Object -Last 1)
            } else {
                0
            }
            if ($paymentEventId -and
                $preRecoveryTradeStatus -eq 'PAYMENT_CONFIRMING' -and
                $preRecoveryReservationStatus -eq 'RESERVED' -and
                $preRecoveryOrderPaidCount -eq 0 -and
                $preRecoveryConfirmingHistoryCount -eq 1 -and
                $preRecoveryConsumedCount -eq 1) {
                break
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $confirmationFailureDeadline)
        if (-not $paymentEventId -or
            $preRecoveryTradeStatus -ne 'PAYMENT_CONFIRMING' -or
            $preRecoveryReservationStatus -ne 'RESERVED' -or
            $preRecoveryOrderPaidCount -ne 0 -or
            $preRecoveryConfirmingHistoryCount -ne 1 -or
            $preRecoveryConsumedCount -ne 1) {
            throw "Inventory outage did not preserve the payment confirmation boundary: event=$paymentEventId, trade=$preRecoveryTradeStatus, reservation=$preRecoveryReservationStatus, OrderPaid=$preRecoveryOrderPaidCount, confirmingHistory=$preRecoveryConfirmingHistoryCount, consumed=$preRecoveryConsumedCount."
        }
        $paymentInventoryCausalityEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            paymentNo = $paymentNo
            orderNo = $paymentOrder.OrderNo
            reservationNo = $paymentOrder.OrderNo -replace '^ORD', 'RSV'
            paymentSucceededEventId = $paymentEventId
            whileInventoryUnavailable = [ordered]@{
                paymentStatus = [string]$paidPayment.data.status
                paymentSucceededOutboxPublished = $true
                tradePaymentEventConsumedCount = $preRecoveryConsumedCount
                tradeStatus = $preRecoveryTradeStatus
                paymentConfirmingHistoryCount = $preRecoveryConfirmingHistoryCount
                inventoryReservationStatus = $preRecoveryReservationStatus
                orderPaidOutboxCount = $preRecoveryOrderPaidCount
            }
        }
        Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $inventoryJar) `
            -WindowStyle Hidden -RedirectStandardOutput $inventoryRecoveryOut `
            -RedirectStandardError $inventoryRecoveryErr
        Wait-HttpOk -Uri "http://127.0.0.1:$inventoryPort/actuator/health/liveness" | Out-Null
        Wait-HttpOk -Uri "http://127.0.0.1:$gatewayPort/api/v1/inventory/status" `
            -TimeoutSeconds 60 | Out-Null
    }

    $paymentChainBudgetSeconds = if ($EnableCapacityBaseline) { 1800 } else { 75 }
    $paymentChainTimer = [Diagnostics.Stopwatch]::StartNew()
    $paymentChainDeadline = (Get-Date).AddSeconds($paymentChainBudgetSeconds)
    $tradeOutboxUnpublishedAtPayment = 0
    $tradeOutboxUnpublishedAtConvergence = 0
    $expectedActiveTradeReservations = $tradeCompetitionSuccesses - 1
    if ($EnableCapacityBaseline) {
        $tradeOutboxAtPaymentRows = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e "SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED' AND aggregate_id IN ($tradeOrderSqlList);"
        if ($LASTEXITCODE -ne 0) {
            throw 'Trade Outbox backlog query failed at the payment boundary.'
        }
        $tradeOutboxUnpublishedAtPayment = [int]($tradeOutboxAtPaymentRows | Select-Object -Last 1)
        $tradeOutboxUnpublishedAtConvergence = $tradeOutboxUnpublishedAtPayment
    }
    $fulfillmentBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/fulfillment"
    do {
        $paidOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $tradeStockAfterPayment = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders -TimeoutSec 10
        $fulfillmentRead = Invoke-WebRequest -Method Get `
            -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -SkipHttpErrorCheck -TimeoutSec 10
        $fulfillmentPayload = if ([int]$fulfillmentRead.StatusCode -eq 200) {
            $fulfillmentRead.Content | ConvertFrom-Json
        } else {
            $null
        }
        $fulfillmentStatus = if ($null -ne $fulfillmentPayload) {
            [string]$fulfillmentPayload.data.status
        } else {
            'NOT_FOUND'
        }
        if ($EnableCapacityBaseline) {
            $tradeOutboxBacklogRows = docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
                mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
                -e "SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED' AND aggregate_id IN ($tradeOrderSqlList);"
            if ($LASTEXITCODE -ne 0) {
                throw 'Trade Outbox backlog query failed during payment-chain convergence.'
            }
            $tradeOutboxUnpublishedAtConvergence = [int]($tradeOutboxBacklogRows | Select-Object -Last 1)
            $activeReservationRows = docker exec -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
                mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME -N -B `
                -e "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no IN ($tradeReservationSqlList) AND status = 'RESERVED';"
            if ($LASTEXITCODE -ne 0) {
                throw 'Active capacity reservation query failed during payment-chain convergence.'
            }
            $expectedActiveTradeReservations = [int]($activeReservationRows | Select-Object -Last 1)
        }
        $paidMarketingBenefits = Invoke-RestMethod -Method Get -Uri "$marketingBaseUrl/benefits" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $redeemedMarketingBenefits = @($paidMarketingBenefits.data | Where-Object {
                $_.benefitNo -in $marketingBenefitNos -and $_.status -eq 'REDEEMED' -and
                $_.redeemedOrderNo -eq $marketingOrderNo
            }).Count
        if ($paidOrder.data.status -eq 'FULFILLING' -and
            $fulfillmentStatus -eq 'CREATED' -and
            $tradeStockAfterPayment.data.onHand -eq ($tradeStockBaseline - 1) -and
            $tradeStockAfterPayment.data.reserved -eq $expectedActiveTradeReservations -and
            $tradeStockAfterPayment.data.available -eq `
                (($tradeStockBaseline - 1) - $expectedActiveTradeReservations) -and
            $redeemedMarketingBenefits -eq 3) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $paymentChainDeadline)
    $paymentChainTimer.Stop()
    $paymentChainConvergenceSeconds = [Math]::Round($paymentChainTimer.Elapsed.TotalSeconds, 3)
    if ($paidOrder.data.status -ne 'FULFILLING' -or
        $fulfillmentStatus -ne 'CREATED' -or
        $tradeStockAfterPayment.data.onHand -ne ($tradeStockBaseline - 1) -or
        $tradeStockAfterPayment.data.reserved -ne $expectedActiveTradeReservations -or
        $tradeStockAfterPayment.data.available -ne `
            (($tradeStockBaseline - 1) - $expectedActiveTradeReservations) -or
        $redeemedMarketingBenefits -ne 3) {
        throw "PaymentSucceeded -> OrderPaid did not converge within ${paymentChainBudgetSeconds}s: order=$($paidOrder.data.status), fulfillment=$fulfillmentStatus, onHand=$($tradeStockAfterPayment.data.onHand), reserved=$($tradeStockAfterPayment.data.reserved), available=$($tradeStockAfterPayment.data.available), benefits=$redeemedMarketingBenefits, tradeOutboxUnpublished=$tradeOutboxUnpublishedAtConvergence."
    }
    $fulfillmentNo = $fulfillmentPayload.data.fulfillmentNo
    $confirmedReservationStatus = Get-MySqlSingleColumn `
        -Database $env:INVENTORY_DB_NAME `
        -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD `
        -Query "SELECT status FROM inventory_reservation WHERE reservation_no = '$($marketingOrderNo -replace '^ORD', 'RSV')';" |
        Select-Object -Last 1
    $redeemedPricingLockStatus = Get-MySqlSingleColumn `
        -Database $env:MARKETING_DB_NAME `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -Query "SELECT status FROM pricing_lock WHERE order_no = '$marketingOrderNo';" |
        Select-Object -Last 1
    if ($confirmedReservationStatus -ne 'CONFIRMED' -or
        $redeemedPricingLockStatus -ne 'REDEEMED') {
        throw "Positive internal trust-zone chain lacked owner facts: reservation=$confirmedReservationStatus, pricingLock=$redeemedPricingLockStatus."
    }
    $internalTrustZoneEvidence = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        credentials = [ordered]@{
            tradeCredentialPresent = $env:TRADE_INTERNAL_SERVICE_TOKEN.Length -ge 32
            paymentCredentialPresent = $env:PAYMENT_INTERNAL_SERVICE_TOKEN.Length -ge 32
            credentialsDifferent = $true
            secretValuesRecorded = $false
        }
        rejectedCrossZoneRequests = [ordered]@{
            identityResourceUserId = [string]$smokeUserId
            identityResourceAddressId = [string]$smokeAddressId
            identityCorrectCallerWrongZoneHttpStatus = $identityWrongZoneStatus
            inventoryCorrectCallerWrongZoneHttpStatus = $inventoryWrongZoneStatus
            inventoryRowsAfterRejection = $inventoryWrongZoneRows
            marketingCorrectCallerWrongZoneHttpStatus = $marketingWrongZoneStatus
            marketingRowsAfterRejection = $marketingWrongZoneRows
            tradeCorrectCallerWrongZoneHttpStatus = $tradeWrongZoneStatus
        }
        acceptedPositiveChain = [ordered]@{
            tradeOrderNo = $marketingOrderNo
            paymentNo = $paymentNo
            tradeStatusAfterPayment = [string]$paidOrder.data.status
            paymentStatus = [string]$paidPayment.data.status
            inventoryReservationStatus = [string]$confirmedReservationStatus
            marketingPricingLockStatus = [string]$redeemedPricingLockStatus
            redeemedBenefits = $redeemedMarketingBenefits
            fulfillmentStatus = $fulfillmentStatus
            fulfillmentNo = $fulfillmentNo
        }
    }
    if ($EnablePaymentInventoryConfirmationFaultInjection) {
        $finalOrderPaidCount = [int](Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = '$($paymentOrder.OrderNo)' AND event_type = 'OrderPaid';" |
                Select-Object -Last 1)
        $paidHistoryCount = [int](Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM order_status_history WHERE order_id = (SELECT id FROM trade_order WHERE order_no = '$($paymentOrder.OrderNo)') AND to_status = 'PAID';" |
                Select-Object -Last 1)
        if ($finalOrderPaidCount -ne 1 -or $paidHistoryCount -ne 1) {
            throw "Recovered payment confirmation did not produce exactly one PAID transition and OrderPaid event: paidHistory=$paidHistoryCount, OrderPaid=$finalOrderPaidCount."
        }
        $paymentInventoryCausalityEvidence['afterInventoryRecovery'] = [ordered]@{
            tradeStatus = [string]$paidOrder.data.status
            paidHistoryCount = $paidHistoryCount
            inventoryReservationStatus = [string]$confirmedReservationStatus
            orderPaidOutboxCount = $finalOrderPaidCount
            marketingPricingLockStatus = [string]$redeemedPricingLockStatus
            redeemedBenefits = $redeemedMarketingBenefits
            fulfillmentStatus = $fulfillmentStatus
        }
    }

    if ($EnableDistributedTracing) {
        $paymentTraceId = @"
SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.traceId'))
FROM outbox_event
WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
        if ($paymentTraceId -notmatch '^[0-9a-f]{32}$') {
            throw "PaymentSucceeded outbox did not persist a valid W3C trace ID: $paymentTraceId"
        }
        $tracingEvidence = Wait-TempoTrace -TraceId $paymentTraceId -Port $tempoHttpPort
    }

    if ($paidOrder.data.deliveryAddress.detailAddress -ne $originalAddressDetail -or
        $fulfillmentPayload.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Trade or fulfillment did not preserve the original delivery address snapshot.'
    }
    $updatedAddress = Invoke-RestMethod -Method Put -Uri "$identityBaseUrl/addresses/$smokeAddressId" `
        -Headers @{ Authorization = "Bearer $accessToken" } -ContentType 'application/json' `
        -Body (@{
            recipientName = 'Smoke Customer'
            phone = '+86 13800000000'
            province = 'Zhejiang'
            provinceCode = '330000'
            city = 'Hangzhou'
            cityCode = '330100'
            district = 'Xihu'
            districtCode = '330106'
            detailAddress = $updatedAddressDetail
            postalCode = '310000'
            setDefault = $false
        } | ConvertTo-Json -Compress) -TimeoutSec 10
    if ($updatedAddress.data.detailAddress -ne $updatedAddressDetail) {
        throw 'Identity address update did not persist.'
    }
    $orderAfterAddressUpdate = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    $fulfillmentAfterAddressUpdate = Invoke-RestMethod -Method Get `
        -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterAddressUpdate.data.deliveryAddress.detailAddress -ne $originalAddressDetail -or
        $fulfillmentAfterAddressUpdate.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Editing the source address changed an immutable order or fulfillment snapshot.'
    }
    Invoke-RestMethod -Method Delete -Uri "$identityBaseUrl/addresses/$smokeAddressId" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10 | Out-Null
    $orderAfterAddressDelete = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterAddressDelete.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Deleting the source address changed the immutable trade snapshot.'
    }

    $customerFulfillmentAttempt = Invoke-JsonPostRaw `
        -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/picking" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ([int]$customerFulfillmentAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to operate a fulfillment order.'
    }

    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/picking" `
        -Headers $adminHeaders -Body @{} | Out-Null
    $exceptionReason = 'Scanner unavailable during real pre-M9 verification'
    $exceptionState = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/exception" `
        -Headers $warehouseHeaders -Body @{ reason = $exceptionReason }
    if ($exceptionState.data.status -ne 'EXCEPTION') {
        throw 'The real fulfillment exception command did not enter EXCEPTION.'
    }

    $deniedResolutionKey = "FUL-RESOLVE-DENIED-$inventoryReservationPrefix"
    $warehouseResolution = Invoke-JsonPostRaw `
        -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/exception/resolve" `
        -Headers @{
            Authorization = "Bearer $warehouseAccessToken"
            'Idempotency-Key' = $deniedResolutionKey
        } -Body @{ reason = 'Warehouse must not self-authorize exception recovery' }
    $warehouseResolutionStatus = [int]$warehouseResolution.StatusCode
    $statusAfterWarehouseDenial = Get-MySqlSingleColumn `
        -Database $env:FULFILLMENT_DB_NAME `
        -User $env:FULFILLMENT_DB_USER `
        -Password $env:FULFILLMENT_DB_PASSWORD `
        -Query "SELECT status FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';" |
        Select-Object -Last 1
    $auditAfterWarehouseDenial = [int](Get-MySqlSingleColumn `
            -Database $env:FULFILLMENT_DB_NAME `
            -User $env:FULFILLMENT_DB_USER `
            -Password $env:FULFILLMENT_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM fulfillment_exception_resolution WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');" |
            Select-Object -Last 1)
    if ($warehouseResolutionStatus -ne 403 -or
        $statusAfterWarehouseDenial -ne 'EXCEPTION' -or
        $auditAfterWarehouseDenial -ne 0) {
        throw "Warehouse exception resolution crossed the owner boundary: HTTP=$warehouseResolutionStatus, status=$statusAfterWarehouseDenial, audit=$auditAfterWarehouseDenial."
    }

    $resolutionCandidates = @(
        [pscustomobject]@{
            CommandId = "FUL-RESOLVE-A-$inventoryReservationPrefix"
            Reason = 'Admin recovery path A after parcel inspection'
        },
        [pscustomobject]@{
            CommandId = "FUL-RESOLVE-B-$inventoryReservationPrefix"
            Reason = 'Admin recovery path B after parcel inspection'
        }
    )
    $resolutionUri = "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/exception/resolve"
    $resolutionResults = $resolutionCandidates | ForEach-Object -Parallel {
        $candidate = $_
        $body = @{ reason = $candidate.Reason } | ConvertTo-Json -Compress
        $response = Invoke-WebRequest -Method Post -Uri $using:resolutionUri `
            -Headers @{
                Authorization = "Bearer $using:adminAccessToken"
                'Idempotency-Key' = $candidate.CommandId
            } -ContentType 'application/json' -Body $body -SkipHttpErrorCheck -TimeoutSec 15
        [pscustomobject]@{
            CommandId = $candidate.CommandId
            Reason = $candidate.Reason
            StatusCode = [int]$response.StatusCode
            ResponseCode = if ($response.Content) {
                ($response.Content | ConvertFrom-Json).code
            } else {
                $null
            }
        }
    } -ThrottleLimit 2
    $successfulResolutions = @($resolutionResults | Where-Object StatusCode -eq 200)
    $rejectedResolutions = @($resolutionResults | Where-Object StatusCode -eq 409)
    if ($successfulResolutions.Count -ne 1 -or $rejectedResolutions.Count -ne 1) {
        throw "Concurrent fulfillment exception recovery did not choose exactly one winner: $($resolutionResults | ConvertTo-Json -Compress)."
    }
    $winningResolution = $successfulResolutions[0]

    $resolutionReplay = Invoke-JsonPostRaw -Uri $resolutionUri -Headers @{
        Authorization = "Bearer $adminAccessToken"
        'Idempotency-Key' = $winningResolution.CommandId
    } -Body @{ reason = $winningResolution.Reason }
    $resolutionConflict = Invoke-JsonPostRaw -Uri $resolutionUri -Headers @{
        Authorization = "Bearer $adminAccessToken"
        'Idempotency-Key' = $winningResolution.CommandId
    } -Body @{ reason = "$($winningResolution.Reason) with changed payload" }
    $replayPayload = $resolutionReplay.Content | ConvertFrom-Json
    $conflictPayload = $resolutionConflict.Content | ConvertFrom-Json
    if ([int]$resolutionReplay.StatusCode -ne 200 -or
        $replayPayload.data.status -ne 'PICKING' -or
        [int]$resolutionConflict.StatusCode -ne 409 -or
        $conflictPayload.code -ne 'IDEMPOTENCY_CONFLICT') {
        throw "Fulfillment exception replay/conflict contract failed: replay=$($resolutionReplay.StatusCode), conflict=$($resolutionConflict.StatusCode)/$($conflictPayload.code)."
    }

    $resolvedFulfillmentStatus = Get-MySqlSingleColumn `
        -Database $env:FULFILLMENT_DB_NAME `
        -User $env:FULFILLMENT_DB_USER `
        -Password $env:FULFILLMENT_DB_PASSWORD `
        -Query "SELECT status FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';" |
        Select-Object -Last 1
    $resolutionAuditCount = [int](Get-MySqlSingleColumn `
            -Database $env:FULFILLMENT_DB_NAME `
            -User $env:FULFILLMENT_DB_USER `
            -Password $env:FULFILLMENT_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM fulfillment_exception_resolution WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');" |
            Select-Object -Last 1)
    $resolutionHistoryCount = [int](Get-MySqlSingleColumn `
            -Database $env:FULFILLMENT_DB_NAME `
            -User $env:FULFILLMENT_DB_USER `
            -Password $env:FULFILLMENT_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM fulfillment_status_history WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo') AND command = 'RESOLVE_EXCEPTION';" |
            Select-Object -Last 1)
    $resolutionOutboxCount = [int](Get-MySqlSingleColumn `
            -Database $env:FULFILLMENT_DB_NAME `
            -User $env:FULFILLMENT_DB_USER `
            -Password $env:FULFILLMENT_DB_PASSWORD `
            -Query "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = '$fulfillmentNo' AND event_type = 'FulfillmentExceptionResolved';" |
            Select-Object -Last 1)
    $persistedResolutionCommand = Get-MySqlSingleColumn `
        -Database $env:FULFILLMENT_DB_NAME `
        -User $env:FULFILLMENT_DB_USER `
        -Password $env:FULFILLMENT_DB_PASSWORD `
        -Query "SELECT command_id FROM fulfillment_exception_resolution WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');" |
        Select-Object -Last 1
    if ($resolvedFulfillmentStatus -ne 'PICKING' -or
        $resolutionAuditCount -ne 1 -or
        $resolutionHistoryCount -ne 1 -or
        $resolutionOutboxCount -ne 1 -or
        $persistedResolutionCommand -ne $winningResolution.CommandId) {
        throw "Fulfillment exception owner facts diverged: status=$resolvedFulfillmentStatus, audit=$resolutionAuditCount, history=$resolutionHistoryCount, outbox=$resolutionOutboxCount, command=$persistedResolutionCommand."
    }
    $fulfillmentExceptionEvidence = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        fulfillmentNo = $fulfillmentNo
        exceptionReason = $exceptionReason
        warehouseAttempt = [ordered]@{
            httpStatus = $warehouseResolutionStatus
            statusAfterAttempt = $statusAfterWarehouseDenial
            auditRowsAfterAttempt = $auditAfterWarehouseDenial
        }
        concurrentAdminAttempts = @($resolutionResults)
        winnerCommandId = $winningResolution.CommandId
        replay = [ordered]@{
            httpStatus = [int]$resolutionReplay.StatusCode
            status = [string]$replayPayload.data.status
        }
        changedPayload = [ordered]@{
            httpStatus = [int]$resolutionConflict.StatusCode
            responseCode = [string]$conflictPayload.code
        }
        ownerFacts = [ordered]@{
            fulfillmentStatus = $resolvedFulfillmentStatus
            auditRows = $resolutionAuditCount
            resolveHistoryRows = $resolutionHistoryCount
            resolvedOutboxRows = $resolutionOutboxCount
            persistedCommandId = $persistedResolutionCommand
        }
    }

    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/packed" `
        -Headers $adminHeaders -Body @{} | Out-Null
    $shipped = Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/ship" `
        -Headers $adminHeaders -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "SMOKE-TRACK-$inventoryReservationPrefix"
        }
    if ($shipped.data.status -ne 'SHIPPED') {
        throw 'Fulfillment shipping command did not reach SHIPPED.'
    }

    $shippingDeadline = (Get-Date).AddSeconds(45)
    do {
        $shippedOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($shippedOrder.data.status -eq 'SHIPPED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $shippingDeadline)
    if ($shippedOrder.data.status -ne 'SHIPPED') {
        throw 'ShipmentDispatched did not advance the trade order to SHIPPED.'
    }

    $traceTime = [DateTimeOffset]::UtcNow.ToString('o')
    foreach ($trace in @(
        @{ externalEventId = "SMOKE-TRACE-1-$inventoryReservationPrefix"; nodeType = 'TRANSIT';
           description = 'Arrived at sorting center'; locationName = 'Nanjing' },
        @{ externalEventId = "SMOKE-TRACE-2-$inventoryReservationPrefix"; nodeType = 'DELIVERING';
           description = 'Courier is delivering'; locationName = 'Shanghai' },
        @{ externalEventId = "SMOKE-TRACE-3-$inventoryReservationPrefix"; nodeType = 'SIGNED';
           description = 'Recipient signed'; locationName = 'Shanghai' }
    )) {
        Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/traces" `
            -Headers $adminHeaders -Body @{
                externalEventId = $trace.externalEventId
                nodeType = $trace.nodeType
                description = $trace.description
                locationName = $trace.locationName
                longitude = 118.796877
                latitude = 32.060255
                occurredAt = $traceTime
            } | Out-Null
    }

    $completionDeadline = (Get-Date).AddSeconds(45)
    do {
        $completedOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($completedOrder.data.status -eq 'COMPLETED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $completionDeadline)
    if ($completedOrder.data.status -ne 'COMPLETED') {
        throw 'ShipmentSigned did not advance the trade order to COMPLETED.'
    }

    $finalFulfillment = Invoke-RestMethod -Method Get `
        -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($finalFulfillment.data.status -ne 'SIGNED' -or $finalFulfillment.data.traces.Count -ne 3) {
        throw 'Fulfillment tracking history was not append-only or did not reach SIGNED.'
    }

    $afterSale = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)/after-sales" `
        -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-after-sale-$inventoryReservationPrefix"
        } -Body @{
            reason = 'Real middleware whole-order return and refund smoke'
        }
    $afterSaleNo = $afterSale.data.afterSaleNo
    if (-not $afterSaleNo -or $afterSale.data.status -ne 'APPLIED' -or
        [decimal]$afterSale.data.refundAmount -ne [decimal]$createdPayment.data.amount -or
        @($afterSale.data.items).Count -ne 1 -or
        [decimal]$afterSale.data.items[0].refundableAmount -ne [decimal]$createdPayment.data.amount) {
        throw 'Whole-order after-sale did not preserve the original payable allocation snapshot.'
    }
    $tradeBusinessProcesses = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$tradePort/actuator/businessprocesses?limit=100" `
        -Headers $adminHeaders -TimeoutSec 10
    $activeAfterSale = @($tradeBusinessProcesses.activeProcesses |
        Where-Object { $_.referenceNo -eq $afterSaleNo -and $_.status -eq 'APPLIED' })
    if ($activeAfterSale.Count -ne 1) {
        throw 'Trade business process diagnostics did not expose the active after-sale.'
    }

    $customerReviewAttempt = Invoke-JsonPostRaw `
        -Uri "$tradeBaseUrl/admin/after-sales/$afterSaleNo/review" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            approved = $true
            reason = 'Customer must not review after-sales'
        }
    if ([int]$customerReviewAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to review an after-sale request.'
    }

    $approvedAfterSale = Invoke-JsonPost -Uri "$tradeBaseUrl/admin/after-sales/$afterSaleNo/review" `
        -Headers $adminHeaders -Body @{
            approved = $true
            reason = 'Approved by real middleware smoke'
        }
    if ($approvedAfterSale.data.status -ne 'WAIT_RETURN') {
        throw 'After-sale approval did not reach WAIT_RETURN.'
    }

    $returnReceiptDeadline = (Get-Date).AddSeconds(60)
    $returnReceipt = $null
    do {
        $returnList = Invoke-RestMethod -Method Get -Uri "$fulfillmentBaseUrl/returns" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $returnReceipt = $returnList.data | Where-Object { $_.afterSaleNo -eq $afterSaleNo } |
            Select-Object -First 1
        if ($null -ne $returnReceipt -and $returnReceipt.status -eq 'WAIT_SHIPMENT') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $returnReceiptDeadline)
    if ($null -eq $returnReceipt -or $returnReceipt.status -ne 'WAIT_SHIPMENT') {
        throw 'AfterSaleApproved did not create a return receipt.'
    }
    $returnReceiptNo = $returnReceipt.returnReceiptNo

    $returningReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/returns/$returnReceiptNo/shipment" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "SMOKE-RETURN-TRACK-$inventoryReservationPrefix"
        }
    if ($returningReceipt.data.status -ne 'RETURNING') {
        throw 'Customer return shipment did not reach RETURNING.'
    }
    $receivedReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/receive" `
        -Headers $adminHeaders -Body @{}
    if ($receivedReceipt.data.status -ne 'RECEIVED') {
        throw 'Warehouse return receipt did not reach RECEIVED.'
    }
    $inspectedReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/inspect" `
        -Headers $adminHeaders -Body @{
            remark = 'All returned goods accepted by real middleware smoke'
        }
    if ($inspectedReceipt.data.status -ne 'INSPECTED') {
        throw 'Warehouse inspection did not reach INSPECTED.'
    }

    $inventoryOutboxUnpublishedAtReturnInspection = @"
SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    $inventoryOutboxUnpublishedAtReturnInspection = [int]$inventoryOutboxUnpublishedAtReturnInspection
    $returnChainBudgetSeconds = if ($EnableCapacityBaseline) { 300 } else { 75 }
    $returnChainTimer = [Diagnostics.Stopwatch]::StartNew()
    $refundCreationDeadline = (Get-Date).AddSeconds($returnChainBudgetSeconds)
    $expectedActiveReturnReservations = $tradeCompetitionSuccesses - 1
    do {
        $afterSaleProgress = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $returnStock = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders -TimeoutSec 10
        $refundProgress = $null
        try {
            $refundProgress = Invoke-RestMethod -Method Get `
                -Uri "$paymentBaseUrl/refunds/by-after-sale/$afterSaleNo" `
                -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        }
        catch {
            $refundProgress = $null
        }
        if ($EnableCapacityBaseline) {
            $activeReturnReservationRows = docker exec -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
                mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME -N -B `
                -e "SELECT COUNT(*) FROM inventory_reservation WHERE reservation_no IN ($tradeReservationSqlList) AND status = 'RESERVED';"
            if ($LASTEXITCODE -ne 0) {
                throw 'Active capacity reservation query failed during return-chain convergence.'
            }
            $expectedActiveReturnReservations = [int]($activeReturnReservationRows | Select-Object -Last 1)
        }
        if ($null -ne $refundProgress) {
            $refundNo = $refundProgress.data.refundNo
        }
        if ($afterSaleProgress.data.status -eq 'REFUNDING' -and $refundNo -and
            $refundProgress.data.status -eq 'PROCESSING' -and
            $refundProgress.data.requestStatus -eq 'SENT' -and
            $returnStock.data.onHand -eq $tradeStockBaseline -and
            $returnStock.data.reserved -eq $expectedActiveReturnReservations -and
            $returnStock.data.available -eq `
                ($tradeStockBaseline - $expectedActiveReturnReservations)) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $refundCreationDeadline)
    $returnChainTimer.Stop()
    $returnChainConvergenceSeconds = [Math]::Round($returnChainTimer.Elapsed.TotalSeconds, 3)
    if ($afterSaleProgress.data.status -ne 'REFUNDING' -or -not $refundNo -or
        $null -eq $refundProgress -or $refundProgress.data.status -ne 'PROCESSING' -or
        $refundProgress.data.requestStatus -ne 'SENT' -or
        $returnStock.data.onHand -ne $tradeStockBaseline -or
        $returnStock.data.reserved -ne $expectedActiveReturnReservations -or
        $returnStock.data.available -ne `
            ($tradeStockBaseline - $expectedActiveReturnReservations)) {
        $returnOutboxFact = @"
SELECT CONCAT(COUNT(*), '|',
              COALESCE(GROUP_CONCAT(status ORDER BY id SEPARATOR ','), ''), '|',
              COALESCE(MIN(created_at), ''))
FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'ReturnStocked';
SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME
        $returnOutboxLines = @($returnOutboxFact)
        $returnOutboxState = $returnOutboxLines | Select-Object -First 1
        $inventoryUnpublished = $returnOutboxLines | Select-Object -Last 1
        $refundStatus = if ($null -eq $refundProgress) { 'MISSING' } else { $refundProgress.data.status }
        $refundRequestStatus = if ($null -eq $refundProgress) { 'MISSING' } else {
            $refundProgress.data.requestStatus
        }
        throw "ReturnInspected -> ReturnStocked -> RefundRequested did not converge within " +
            "${returnChainBudgetSeconds}s: afterSale=$($afterSaleProgress.data.status), " +
            "refund=$refundStatus, request=$refundRequestStatus, " +
            "stock=$($returnStock.data.onHand)|$($returnStock.data.reserved)|$($returnStock.data.available), " +
            "expected=$tradeStockBaseline|$expectedActiveReturnReservations|" +
            "$($tradeStockBaseline - $expectedActiveReturnReservations), " +
            "returnStockedOutbox=$returnOutboxState, inventoryOutboxUnpublished=$inventoryUnpublished."
    }
    $inventoryOutboxUnpublishedAtReturnChainConvergence = @"
SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    $inventoryOutboxUnpublishedAtReturnChainConvergence = `
        [int]$inventoryOutboxUnpublishedAtReturnChainConvergence
    $paymentBusinessProcesses = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$paymentPort/actuator/businessprocesses?limit=100" `
        -Headers $adminHeaders -TimeoutSec 10
    $activeRefund = @($paymentBusinessProcesses.activeProcesses |
        Where-Object { $_.referenceNo -eq $refundNo -and $_.status -eq 'PROCESSING' -and $_.stage -eq 'SENT' })
    if ($activeRefund.Count -ne 1) {
        throw 'Payment business process diagnostics did not expose the processing refund.'
    }

    # Fault injection: represent a channel dispatch that exhausted its automatic retries.
    # MySQL remains the fact source; the service must authorize and audit the recovery command.
    $faultInjected = @"
UPDATE refund_order
SET request_status = 'NEEDS_ATTENTION', request_attempts = 10,
    next_request_at = NULL, request_claimed_at = NULL,
    last_request_error = 'Foundation smoke injected channel timeout',
    updated_at = CURRENT_TIMESTAMP(3)
WHERE refund_no = '$refundNo' AND status = 'PROCESSING' AND request_status = 'SENT';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
    if ($faultInjected -ne '1') {
        throw 'Unable to inject the exhausted refund dispatch state in MySQL.'
    }

    $retryCommandId = "SMOKE-REFUND-RETRY-$([Guid]::NewGuid().ToString('N'))"
    $retryBody = @{ reason = 'Channel connectivity restored during foundation smoke' }
    $customerRetry = Invoke-JsonPostRaw `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch" `
        -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = $retryCommandId
        } -Body $retryBody
    if ([int]$customerRetry.StatusCode -ne 403) {
        throw 'A customer token was allowed to retry a refund dispatch.'
    }
    $retriedRefund = Invoke-JsonPost `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch" `
        -Headers @{
            Authorization = "Bearer $adminAccessToken"
            'Idempotency-Key' = $retryCommandId
        } -Body $retryBody
    if ($retriedRefund.data.status -ne 'PROCESSING' -or
        $retriedRefund.data.requestStatus -ne 'PENDING' -or
        $retriedRefund.data.requestAttempts -ne 0) {
        throw 'The domain-authorized refund retry did not reset the exhausted dispatch safely.'
    }

    $retryDispatchDeadline = (Get-Date).AddSeconds(20)
    do {
        $refundProgress = Invoke-RestMethod -Method Get `
            -Uri "$paymentBaseUrl/refunds/by-after-sale/$afterSaleNo" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($refundProgress.data.requestStatus -eq 'SENT') {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $retryDispatchDeadline)
    if ($refundProgress.data.requestStatus -ne 'SENT' -or $refundProgress.data.requestAttempts -ne 1) {
        throw 'The retried refund dispatch did not converge back to SENT.'
    }

    $idempotentRetry = Invoke-JsonPost `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch" `
        -Headers @{
            Authorization = "Bearer $adminAccessToken"
            'Idempotency-Key' = $retryCommandId
        } -Body $retryBody
    if ($idempotentRetry.data.requestStatus -ne 'SENT' -or
        $idempotentRetry.data.requestAttempts -ne 1) {
        throw 'An idempotent retry command replayed the refund side effect.'
    }

    $conflictingRetry = Invoke-JsonPostRaw `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch" `
        -Headers @{
            Authorization = "Bearer $adminAccessToken"
            'Idempotency-Key' = $retryCommandId
        } -Body @{ reason = 'Conflicting reuse of an existing command' }
    $conflictingRetryPayload = $conflictingRetry.Content | ConvertFrom-Json
    if ([int]$conflictingRetry.StatusCode -ne 409 -or
        $conflictingRetryPayload.code -ne 'IDEMPOTENCY_CONFLICT') {
        throw 'A refund retry idempotency-key conflict was not rejected.'
    }

    $rejectedRetryCommandId = "SMOKE-REFUND-REJECT-$([Guid]::NewGuid().ToString('N'))"
    $prematureRetry = Invoke-JsonPostRaw `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch" `
        -Headers @{
            Authorization = "Bearer $adminAccessToken"
            'Idempotency-Key' = $rejectedRetryCommandId
        } -Body @{ reason = 'Attempt to redeliver an in-flight refund' }
    $prematureRetryPayload = $prematureRetry.Content | ConvertFrom-Json
    if ([int]$prematureRetry.StatusCode -ne 409 -or
        $prematureRetryPayload.code -ne 'REFUND_RETRY_NOT_ALLOWED') {
        throw 'A non-recoverable refund dispatch state was allowed to retry.'
    }

    $retryAudits = Invoke-RestMethod -Method Get `
        -Uri "$paymentBaseUrl/admin/refunds/$refundNo/retry-dispatch/audits?limit=10" `
        -Headers $adminHeaders -TimeoutSec 10
    $acceptedAudit = @($retryAudits.data | Where-Object {
            $_.commandId -eq $retryCommandId -and $_.outcome -eq 'ACCEPTED' -and
            $_.beforeRequestStatus -eq 'NEEDS_ATTENTION' -and $_.afterRequestStatus -eq 'PENDING'
        })
    $rejectedAudit = @($retryAudits.data | Where-Object {
            $_.commandId -eq $rejectedRetryCommandId -and $_.outcome -eq 'REJECTED' -and
            $_.errorCode -eq 'REFUND_RETRY_NOT_ALLOWED'
        })
    if ($acceptedAudit.Count -ne 1 -or $rejectedAudit.Count -ne 1) {
        throw 'Refund dispatch retry audit records were incomplete.'
    }

    $refundEventId = "SMOKE-REFUND-EVT-$([Guid]::NewGuid().ToString('N'))"
    $channelRefundNo = "SMOKE-REFUND-TXN-$([Guid]::NewGuid().ToString('N'))"
    $refundTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $refundAmount = ([decimal]$afterSale.data.refundAmount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $refundCanonical = "$refundNo|$refundEventId|$channelRefundNo|SUCCESS|$refundAmount|$refundTimestamp"
    $refundSignature = Get-HmacSha256Hex -Value $refundCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
    $refundCallbackBody = @{
        refundNo = $refundNo
        externalEventId = $refundEventId
        externalRefundNo = $channelRefundNo
        status = 'SUCCESS'
        amount = [decimal]$afterSale.data.refundAmount
        timestamp = $refundTimestamp
        signature = $refundSignature
    }
    if ($EnableCapacityBaseline) {
        $refundCallbackJson = $refundCallbackBody | ConvertTo-Json -Compress -Depth 10
        $refundCallbackTimer = [Diagnostics.Stopwatch]::StartNew()
        $refundCallbackResults = 1..100 | ForEach-Object -Parallel {
            $requestTimer = [Diagnostics.Stopwatch]::StartNew()
            $response = Invoke-RestMethod -Method Post `
                -Uri "$using:paymentBaseUrl/callbacks/mock/refunds" `
                -ContentType 'application/json' -Body $using:refundCallbackJson -TimeoutSec 30
            $requestTimer.Stop()
            [pscustomobject]@{
                RefundNo = $response.data.refundNo
                Status = $response.data.status
                LatencyMs = $requestTimer.Elapsed.TotalMilliseconds
            }
        } -ThrottleLimit 100
        $refundCallbackTimer.Stop()
        $refundCallbackLatencySummary = Get-LatencySummary `
            -Values @($refundCallbackResults | Select-Object -ExpandProperty LatencyMs) `
            -Elapsed $refundCallbackTimer.Elapsed -Concurrency 100
        $refunded = [pscustomobject]@{ data = $refundCallbackResults[0] }
        $duplicateRefund = [pscustomobject]@{ data = $refundCallbackResults[-1] }
        if (@($refundCallbackResults | Where-Object {
                    $_.RefundNo -ne $refundNo -or $_.Status -ne 'SUCCESS'
                }).Count -ne 0) {
            throw 'One or more concurrent signed refund callbacks diverged from the idempotent result.'
        }
    }
    else {
        $refunded = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $refundCallbackBody
        $duplicateRefund = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $refundCallbackBody
    }
    if ($refunded.data.status -ne 'SUCCESS' -or $duplicateRefund.data.status -ne 'SUCCESS') {
        throw 'Signed refund callback or its idempotent retry did not succeed.'
    }

    $afterSaleCompletionDeadline = (Get-Date).AddSeconds(60)
    do {
        $completedAfterSale = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($completedAfterSale.data.status -eq 'COMPLETED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $afterSaleCompletionDeadline)
    if ($completedAfterSale.data.status -ne 'COMPLETED' -or
        $completedAfterSale.data.refundNo -ne $refundNo) {
        throw 'RefundSucceeded did not complete the trade after-sale.'
    }
    if ($EnableDistributedTracing) {
        $refundTraceId = @"
SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.traceId'))
FROM outbox_event
WHERE aggregate_id = '$refundNo' AND event_type = 'RefundSucceeded'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
        if ($refundTraceId -notmatch '^[0-9a-f]{32}$') {
            throw "RefundSucceeded outbox did not persist a valid W3C trace ID: $refundTraceId"
        }
        $refundTracingEvidence = Wait-TempoTrace `
            -TraceId $refundTraceId -Port $tempoHttpPort -EventType 'RefundSucceeded'
    }
    $settledTradeProcesses = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$tradePort/actuator/businessprocesses?limit=100" `
        -Headers $adminHeaders -TimeoutSec 10
    $settledPaymentProcesses = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$paymentPort/actuator/businessprocesses?limit=100" `
        -Headers $adminHeaders -TimeoutSec 10
    if (@($settledTradeProcesses.activeProcesses |
            Where-Object { $_.referenceNo -eq $afterSaleNo }).Count -ne 0 -or
        @($settledPaymentProcesses.activeProcesses |
            Where-Object { $_.referenceNo -eq $refundNo }).Count -ne 0) {
        throw 'Business process diagnostics did not converge after the refund completed.'
    }
    $orderAfterRefund = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterRefund.data.status -ne 'COMPLETED') {
        throw 'After-sale completion rewrote the original completed order state.'
    }

    if ($EnableExceptionalPaymentRecoveryVerification) {
        $exceptionAddress = Invoke-JsonPost -Uri "$identityBaseUrl/addresses" `
            -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
                recipientName = 'Exceptional Payment Customer'
                phone = '+86 13800000001'
                province = 'Zhejiang'
                provinceCode = '330000'
                city = 'Hangzhou'
                cityCode = '330100'
                district = 'Xihu'
                districtCode = '330106'
                detailAddress = 'No. 2 Exceptional Payment Street'
                postalCode = '310000'
                setDefault = $true
            }
        $exceptionAddressId = $exceptionAddress.data.id
        if (-not $exceptionAddressId) {
            throw 'Exceptional-payment verification address was not created.'
        }
        $exceptionOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-payment-exception-order-$inventoryReservationPrefix"
        } -Body @{
            addressId = $exceptionAddressId
            items = @(@{
                    productId = $productId
                    skuId = $exceptionSkuId
                    quantity = 1
                })
            benefitNos = $marketingExceptionBenefitNos
        }
        $exceptionOrderNo = [string]$exceptionOrder.data.orderNo
        $exceptionReservationNo = $exceptionOrderNo -replace '^ORD', 'RSV'
        $tradeOrderNumbers += $exceptionOrderNo
        $tradeReservationNumbers += $exceptionReservationNo
        $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
        $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
        if ($exceptionOrder.data.status -ne 'PENDING_PAYMENT' -or
            [decimal]$exceptionOrder.data.priceSnapshot.originalAmount -ne 49.90 -or
            [decimal]$exceptionOrder.data.priceSnapshot.discountAmount -ne 1.00 -or
            [decimal]$exceptionOrder.data.priceSnapshot.payableAmount -ne 48.90) {
            throw 'Exceptional-payment order did not persist the expected isolated price snapshot.'
        }

        $createdExceptionPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-payment-exception-$inventoryReservationPrefix"
        } -Body @{
            orderNo = $exceptionOrderNo
            channel = 'MOCK'
        }
        $exceptionPaymentNo = [string]$createdExceptionPayment.data.paymentNo
        if (-not $exceptionPaymentNo -or
            $createdExceptionPayment.data.status -ne 'PROCESSING' -or
            [decimal]$createdExceptionPayment.data.amount -ne 48.90) {
            throw 'Exceptional-payment verification did not create the expected processing payment.'
        }

        $canceledExceptionOrder = Invoke-JsonPost `
            -Uri "$tradeBaseUrl/orders/$exceptionOrderNo/cancel" `
            -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
        if ($canceledExceptionOrder.data.status -ne 'CANCELED') {
            throw 'Exceptional-payment order did not reach CANCELED before the late callback.'
        }

        $exceptionCancelDeadline = (Get-Date).AddSeconds(75)
        do {
            $exceptionCanceledTradeStatus = Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query "SELECT status FROM trade_order WHERE order_no = '$exceptionOrderNo';" |
                Select-Object -Last 1
            $exceptionCanceledReservationStatus = Get-MySqlSingleColumn `
                -Database $env:INVENTORY_DB_NAME `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Query "SELECT status FROM inventory_reservation WHERE reservation_no = '$exceptionReservationNo';" |
                Select-Object -Last 1
            $exceptionCanceledPricingLockStatus = Get-MySqlSingleColumn `
                -Database $env:MARKETING_DB_NAME `
                -User $env:MARKETING_DB_USER `
                -Password $env:MARKETING_DB_PASSWORD `
                -Query "SELECT status FROM pricing_lock WHERE order_no = '$exceptionOrderNo';" |
                Select-Object -Last 1
            $exceptionCanceledBenefitStatus = Get-MySqlSingleColumn `
                -Database $env:MARKETING_DB_NAME `
                -User $env:MARKETING_DB_USER `
                -Password $env:MARKETING_DB_PASSWORD `
                -Query "SELECT status FROM user_benefit WHERE benefit_no = '$($marketingExceptionBenefitNos[0])';" |
                Select-Object -Last 1
            if ($exceptionCanceledTradeStatus -eq 'CANCELED' -and
                $exceptionCanceledReservationStatus -eq 'RELEASED' -and
                $exceptionCanceledPricingLockStatus -eq 'RELEASED' -and
                $exceptionCanceledBenefitStatus -eq 'AVAILABLE') {
                break
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $exceptionCancelDeadline)
        if ($exceptionCanceledTradeStatus -ne 'CANCELED' -or
            $exceptionCanceledReservationStatus -ne 'RELEASED' -or
            $exceptionCanceledPricingLockStatus -ne 'RELEASED' -or
            $exceptionCanceledBenefitStatus -ne 'AVAILABLE') {
            throw "Canceled-order owner facts did not converge before the late payment: trade=$exceptionCanceledTradeStatus, inventory=$exceptionCanceledReservationStatus, lock=$exceptionCanceledPricingLockStatus, benefit=$exceptionCanceledBenefitStatus."
        }

        $exceptionCallbackEventId = "SMOKE-LATE-PAY-EVT-$([Guid]::NewGuid().ToString('N'))"
        $exceptionCallbackTransactionNo = "SMOKE-LATE-PAY-TXN-$([Guid]::NewGuid().ToString('N'))"
        $exceptionCallbackTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $exceptionCallbackAmount = ([decimal]$createdExceptionPayment.data.amount).ToString(
            '0.############################', [Globalization.CultureInfo]::InvariantCulture)
        $exceptionCallbackCanonical = "$exceptionPaymentNo|$exceptionCallbackEventId|$exceptionCallbackTransactionNo|SUCCESS|$exceptionCallbackAmount|$exceptionCallbackTimestamp"
        $exceptionCallbackSignature = Get-HmacSha256Hex `
            -Value $exceptionCallbackCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
        $exceptionCallbackBody = @{
            paymentNo = $exceptionPaymentNo
            externalEventId = $exceptionCallbackEventId
            externalTransactionNo = $exceptionCallbackTransactionNo
            status = 'SUCCESS'
            amount = [decimal]$createdExceptionPayment.data.amount
            timestamp = $exceptionCallbackTimestamp
            signature = $exceptionCallbackSignature
        }
        $latePayment = Invoke-JsonPost `
            -Uri "$paymentBaseUrl/callbacks/mock" -Body $exceptionCallbackBody
        $duplicateLatePayment = Invoke-JsonPost `
            -Uri "$paymentBaseUrl/callbacks/mock" -Body $exceptionCallbackBody
        if ($latePayment.data.status -ne 'SUCCESS' -or
            $duplicateLatePayment.data.status -ne 'SUCCESS') {
            throw 'Late signed payment callback or its exact replay did not persist SUCCESS.'
        }

        $latePaymentDeadline = (Get-Date).AddSeconds(90)
        do {
            $exceptionLateTradeFact = Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query @"
SELECT CONCAT(
    status, '|',
    COALESCE(payment_no, ''), '|',
    (SELECT COUNT(*) FROM order_status_history h
     WHERE h.order_id = trade_order.id AND h.command = 'LATE_PAYMENT_DETECTED'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = trade_order.order_no AND e.event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = trade_order.order_no AND e.event_type = 'PaymentReviewRequired'))
FROM trade_order
WHERE order_no = '$exceptionOrderNo';
"@ | Select-Object -Last 1
            $exceptionPaymentSucceededEventId = Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT id FROM outbox_event WHERE aggregate_id = '$exceptionPaymentNo' AND event_type = 'PaymentSucceeded' AND status = 'PUBLISHED';" |
                Select-Object -Last 1
            $exceptionPaymentConsumedCount = if ($exceptionPaymentSucceededEventId) {
                [int](Get-MySqlSingleColumn `
                        -Database $env:TRADE_DB_NAME `
                        -User $env:TRADE_DB_USER `
                        -Password $env:TRADE_DB_PASSWORD `
                        -Query "SELECT COUNT(*) FROM consumed_event WHERE event_id = '$exceptionPaymentSucceededEventId' AND consumer_group = 'trade-payment-succeeded-v1';" |
                        Select-Object -Last 1)
            } else {
                0
            }
            if ($exceptionLateTradeFact -eq "$('PAYMENT_EXCEPTION')|$exceptionPaymentNo|1|0|1" -and
                $exceptionPaymentSucceededEventId -and
                $exceptionPaymentConsumedCount -eq 1) {
                break
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $latePaymentDeadline)
        if ($exceptionLateTradeFact -ne "$('PAYMENT_EXCEPTION')|$exceptionPaymentNo|1|0|1" -or
            -not $exceptionPaymentSucceededEventId -or
            $exceptionPaymentConsumedCount -ne 1) {
            throw "Late payment did not converge to one review fact without OrderPaid: trade=$exceptionLateTradeFact, event=$exceptionPaymentSucceededEventId, consumed=$exceptionPaymentConsumedCount."
        }

        $exceptionCustomerCommandId = "SMOKE-PEX-CUSTOMER-$([Guid]::NewGuid().ToString('N'))"
        $customerExceptionalRefund = Invoke-JsonPostRaw `
            -Uri "$paymentBaseUrl/admin/payments/$exceptionPaymentNo/exception-refunds" `
            -Headers @{
                Authorization = "Bearer $accessToken"
                'Idempotency-Key' = $exceptionCustomerCommandId
            } -Body @{ reason = 'Customer must not repair a financial exception' }
        $customerExceptionalAuditCount = [int](Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM payment_exception_refund_audit WHERE command_id = '$exceptionCustomerCommandId';" |
                Select-Object -Last 1)
        if ([int]$customerExceptionalRefund.StatusCode -ne 403 -or
            $customerExceptionalAuditCount -ne 0) {
            throw "Customer exceptional-refund boundary failed: HTTP $($customerExceptionalRefund.StatusCode), auditRows=$customerExceptionalAuditCount."
        }

        $exceptionAdminCommandIds = @(
            "SMOKE-PEX-ADMIN-A-$([Guid]::NewGuid().ToString('N'))",
            "SMOKE-PEX-ADMIN-B-$([Guid]::NewGuid().ToString('N'))"
        )
        $exceptionAdminToken = $adminAccessToken
        $exceptionAdminResponses = @($exceptionAdminCommandIds | ForEach-Object -Parallel {
                $commandId = $_
                $body = @{ reason = 'Authorized late-payment financial recovery' } |
                    ConvertTo-Json -Compress
                $response = Invoke-WebRequest -Method Post `
                    -Uri "$using:paymentBaseUrl/admin/payments/$using:exceptionPaymentNo/exception-refunds" `
                    -Headers @{
                        Authorization = "Bearer $using:exceptionAdminToken"
                        'Idempotency-Key' = $commandId
                    } -ContentType 'application/json' -Body $body `
                    -SkipHttpErrorCheck -TimeoutSec 30
                $payload = $response.Content | ConvertFrom-Json
                [pscustomobject]@{
                    commandId = $commandId
                    statusCode = [int]$response.StatusCode
                    code = [string]$payload.code
                    refundNo = [string]$payload.data.refundNo
                }
            } -ThrottleLimit 2)
        $exceptionRefundNos = @($exceptionAdminResponses |
            Select-Object -ExpandProperty refundNo -Unique)
        if (@($exceptionAdminResponses | Where-Object {
                    $_.statusCode -ne 200 -or $_.code -ne 'OK' -or -not $_.refundNo
                }).Count -ne 0 -or $exceptionRefundNos.Count -ne 1) {
            throw "Concurrent ADMIN exceptional-refund commands diverged: $($exceptionAdminResponses | ConvertTo-Json -Compress)."
        }
        $exceptionRefundNo = [string]$exceptionRefundNos[0]
        $exceptionAcceptedAuditCount = [int](Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM payment_exception_refund_audit WHERE payment_no = '$exceptionPaymentNo' AND outcome = 'ACCEPTED';" |
                Select-Object -Last 1)
        $exceptionRefundRowCount = [int](Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM refund_order WHERE payment_no = '$exceptionPaymentNo' AND refund_no = '$exceptionRefundNo';" |
                Select-Object -Last 1)
        if ($exceptionAcceptedAuditCount -ne 2 -or $exceptionRefundRowCount -ne 1) {
            throw "Concurrent exceptional-refund commands did not produce two audits and one refund: audits=$exceptionAcceptedAuditCount, refunds=$exceptionRefundRowCount."
        }

        Stop-FoundationServices -Ports @($tradePort)
        Wait-PortAvailable -Port $tradePort
        $exceptionReplay = Invoke-JsonPost `
            -Uri "$paymentBaseUrl/admin/payments/$exceptionPaymentNo/exception-refunds" `
            -Headers @{
                Authorization = "Bearer $adminAccessToken"
                'Idempotency-Key' = $exceptionAdminCommandIds[0]
            } -Body @{ reason = 'Authorized late-payment financial recovery' }
        if ($exceptionReplay.data.refundNo -ne $exceptionRefundNo) {
            throw 'Persisted exceptional-refund command could not replay while Trade was unavailable.'
        }
        $exceptionConflict = Invoke-JsonPostRaw `
            -Uri "$paymentBaseUrl/admin/payments/$exceptionPaymentNo/exception-refunds" `
            -Headers @{
                Authorization = "Bearer $adminAccessToken"
                'Idempotency-Key' = $exceptionAdminCommandIds[0]
            } -Body @{ reason = 'Conflicting reuse while Trade is unavailable' }
        $exceptionConflictPayload = $exceptionConflict.Content | ConvertFrom-Json
        if ([int]$exceptionConflict.StatusCode -ne 409 -or
            $exceptionConflictPayload.code -ne 'IDEMPOTENCY_CONFLICT') {
            throw 'Changed exceptional-refund command payload did not fail from persisted state.'
        }
        $exceptionUnavailableCommandId =
            "SMOKE-PEX-UNAVAILABLE-$([Guid]::NewGuid().ToString('N'))"
        $exceptionUnavailableCommand = Invoke-JsonPostRaw `
            -Uri "$paymentBaseUrl/admin/payments/$exceptionPaymentNo/exception-refunds" `
            -Headers @{
                Authorization = "Bearer $adminAccessToken"
                'Idempotency-Key' = $exceptionUnavailableCommandId
            } -Body @{ reason = 'New command must require authoritative Trade state' }
        $exceptionUnavailablePayload = $exceptionUnavailableCommand.Content | ConvertFrom-Json
        $exceptionUnavailableAuditCount = [int](Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM payment_exception_refund_audit WHERE command_id = '$exceptionUnavailableCommandId';" |
                Select-Object -Last 1)
        if ([int]$exceptionUnavailableCommand.StatusCode -ne 503 -or
            $exceptionUnavailablePayload.code -ne 'REMOTE_DEPENDENCY_UNAVAILABLE' -or
            $exceptionUnavailableAuditCount -ne 0) {
            throw "A new exceptional-refund command did not fail closed without Trade: HTTP $($exceptionUnavailableCommand.StatusCode), code=$($exceptionUnavailablePayload.code), auditRows=$exceptionUnavailableAuditCount."
        }

        Wait-DistributedIdWorkerLeaseExpiry `
            -Namespace $tradeDistributedIdNamespace `
            -WorkerId $tradeDistributedIdWorkerId
        Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', $tradeJar) `
            -WindowStyle Hidden -RedirectStandardOutput $tradeRecoveryOut `
            -RedirectStandardError $tradeRecoveryErr
        Wait-HttpOk -Uri "http://127.0.0.1:$tradePort/actuator/health/liveness" | Out-Null
        Wait-HttpOk -Uri "http://127.0.0.1:$gatewayPort/api/v1/trade/status" `
            -TimeoutSeconds 60 | Out-Null

        $exceptionRefundDispatchDeadline = (Get-Date).AddSeconds(30)
        do {
            $exceptionRefundProgress = Invoke-RestMethod -Method Get `
                -Uri "$paymentBaseUrl/refunds/$exceptionRefundNo" `
                -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
            if ($exceptionRefundProgress.data.requestStatus -eq 'SENT') {
                break
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $exceptionRefundDispatchDeadline)
        if ($exceptionRefundProgress.data.status -ne 'PROCESSING' -or
            $exceptionRefundProgress.data.requestStatus -ne 'SENT') {
            throw 'Exceptional refund did not reach the persisted SENT dispatch stage.'
        }

        $exceptionRefundEventId = "SMOKE-PEX-REFUND-EVT-$([Guid]::NewGuid().ToString('N'))"
        $exceptionChannelRefundNo = "SMOKE-PEX-REFUND-TXN-$([Guid]::NewGuid().ToString('N'))"
        $exceptionRefundTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $exceptionRefundAmount = ([decimal]$exceptionRefundProgress.data.amount).ToString(
            '0.############################', [Globalization.CultureInfo]::InvariantCulture)
        $exceptionRefundCanonical = "$exceptionRefundNo|$exceptionRefundEventId|$exceptionChannelRefundNo|SUCCESS|$exceptionRefundAmount|$exceptionRefundTimestamp"
        $exceptionRefundSignature = Get-HmacSha256Hex `
            -Value $exceptionRefundCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
        $exceptionRefundCallbackBody = @{
            refundNo = $exceptionRefundNo
            externalEventId = $exceptionRefundEventId
            externalRefundNo = $exceptionChannelRefundNo
            status = 'SUCCESS'
            amount = [decimal]$exceptionRefundProgress.data.amount
            timestamp = $exceptionRefundTimestamp
            signature = $exceptionRefundSignature
        }
        $exceptionRefunded = Invoke-JsonPost `
            -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $exceptionRefundCallbackBody
        $exceptionRefundedReplay = Invoke-JsonPost `
            -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $exceptionRefundCallbackBody
        if ($exceptionRefunded.data.status -ne 'SUCCESS' -or
            $exceptionRefundedReplay.data.status -ne 'SUCCESS') {
            throw 'Exceptional refund callback or its exact replay did not persist SUCCESS.'
        }

        $exceptionRecoveryDeadline = (Get-Date).AddSeconds(90)
        do {
            $exceptionRecoveredOrder = Invoke-RestMethod -Method Get `
                -Uri "$tradeBaseUrl/orders/$exceptionOrderNo" `
                -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
            $exceptionRecoveredRefundNo = Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query "SELECT exception_refund_no FROM trade_order WHERE order_no = '$exceptionOrderNo';" |
                Select-Object -Last 1
            if ($exceptionRecoveredOrder.data.status -eq 'CLOSED' -and
                $exceptionRecoveredRefundNo -eq $exceptionRefundNo) {
                break
            }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $exceptionRecoveryDeadline)
        if ($exceptionRecoveredOrder.data.status -ne 'CLOSED' -or
            $exceptionRecoveredRefundNo -ne $exceptionRefundNo) {
            throw 'RefundSucceeded did not close the late-payment exception.'
        }

        $exceptionRefundSucceededEventId = Get-MySqlSingleColumn `
            -Database $env:PAYMENT_DB_NAME `
            -User $env:PAYMENT_DB_USER `
            -Password $env:PAYMENT_DB_PASSWORD `
            -Query "SELECT id FROM outbox_event WHERE aggregate_id = '$exceptionRefundNo' AND event_type = 'RefundSucceeded' AND status = 'PUBLISHED';" |
            Select-Object -Last 1
        $exceptionTradeFacts = Get-MySqlSingleColumn `
            -Database $env:TRADE_DB_NAME `
            -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD `
            -Query @"
SELECT CONCAT(
    status, '|', payment_no, '|', exception_refund_no, '|',
    (SELECT COUNT(*) FROM order_status_history h
     WHERE h.order_id = trade_order.id AND h.command = 'LATE_PAYMENT_DETECTED'), '|',
    (SELECT COUNT(*) FROM order_status_history h
     WHERE h.order_id = trade_order.id AND h.command = 'PAYMENT_EXCEPTION_REFUNDED'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = trade_order.order_no AND e.event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = trade_order.order_no AND e.event_type = 'PaymentReviewRequired'), '|',
    (SELECT COUNT(*) FROM outbox_event e
     WHERE e.aggregate_id = trade_order.order_no AND e.event_type = 'OrderClosed'))
FROM trade_order
WHERE order_no = '$exceptionOrderNo';
"@ | Select-Object -Last 1
        $exceptionPaymentFacts = Get-MySqlSingleColumn `
            -Database $env:PAYMENT_DB_NAME `
            -User $env:PAYMENT_DB_USER `
            -Password $env:PAYMENT_DB_PASSWORD `
            -Query @"
SELECT CONCAT(
    (SELECT status FROM payment_order WHERE payment_no = '$exceptionPaymentNo'), '|',
    (SELECT COUNT(*) FROM payment_transaction
     WHERE payment_id = (SELECT id FROM payment_order WHERE payment_no = '$exceptionPaymentNo')), '|',
    (SELECT COUNT(*) FROM payment_callback_log WHERE payment_no = '$exceptionPaymentNo'), '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id = '$exceptionPaymentNo' AND event_type = 'PaymentSucceeded'), '|',
    (SELECT status FROM refund_order WHERE refund_no = '$exceptionRefundNo'), '|',
    (SELECT COUNT(*) FROM refund_transaction
     WHERE refund_id = (SELECT id FROM refund_order WHERE refund_no = '$exceptionRefundNo')), '|',
    (SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = '$exceptionRefundNo'), '|',
    (SELECT COUNT(*) FROM payment_exception_refund_audit
     WHERE payment_no = '$exceptionPaymentNo' AND outcome = 'ACCEPTED'), '|',
    (SELECT COUNT(*) FROM refund_order WHERE payment_no = '$exceptionPaymentNo'), '|',
    (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_id = '$exceptionRefundNo' AND event_type = 'RefundSucceeded'))
"@ | Select-Object -Last 1
        $exceptionInventoryFacts = Get-MySqlSingleColumn `
            -Database $env:INVENTORY_DB_NAME `
            -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD `
            -Query @"
SELECT CONCAT(
    r.status, '|',
    (SELECT COUNT(*) FROM stock_movement m
     WHERE m.reservation_no = r.reservation_no AND m.movement_type = 'RELEASE'), '|',
    (SELECT COUNT(*) FROM stock_movement m
     WHERE m.reservation_no = r.reservation_no AND m.movement_type = 'CONFIRM'), '|',
    b.on_hand, '|', b.reserved, '|', (b.on_hand - b.reserved))
FROM inventory_reservation r
JOIN inventory_balance b
  ON b.warehouse_id = r.warehouse_id AND b.sku_id = $exceptionSkuId
WHERE r.reservation_no = '$exceptionReservationNo';
"@ | Select-Object -Last 1
        $exceptionMarketingFacts = Get-MySqlSingleColumn `
            -Database $env:MARKETING_DB_NAME `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Query @"
SELECT CONCAT(
    (SELECT status FROM pricing_lock WHERE order_no = '$exceptionOrderNo'), '|',
    (SELECT status FROM user_benefit
     WHERE benefit_no = '$($marketingExceptionBenefitNos[0])'), '|',
    COALESCE((SELECT redeemed_order_no FROM user_benefit
              WHERE benefit_no = '$($marketingExceptionBenefitNos[0])'), ''))
"@ | Select-Object -Last 1
        $exceptionFulfillmentRows = [int](Get-MySqlSingleColumn `
                -Database $env:FULFILLMENT_DB_NAME `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Query "SELECT COUNT(*) FROM fulfillment_order WHERE order_no = '$exceptionOrderNo';" |
                Select-Object -Last 1)
        $exceptionRefundConsumedCount = if ($exceptionRefundSucceededEventId) {
            [int](Get-MySqlSingleColumn `
                    -Database $env:TRADE_DB_NAME `
                    -User $env:TRADE_DB_USER `
                    -Password $env:TRADE_DB_PASSWORD `
                    -Query "SELECT COUNT(*) FROM consumed_event WHERE event_id = '$exceptionRefundSucceededEventId' AND consumer_group = 'trade-payment-exception-refund-v1';" |
                    Select-Object -Last 1)
        } else {
            0
        }
        if ($exceptionTradeFacts -ne "CLOSED|$exceptionPaymentNo|$exceptionRefundNo|1|1|0|1|1" -or
            $exceptionPaymentFacts -ne 'SUCCESS|1|1|1|SUCCESS|1|1|2|1|1' -or
            $exceptionInventoryFacts -ne 'RELEASED|1|0|1|0|1' -or
            $exceptionMarketingFacts -ne 'RELEASED|AVAILABLE|' -or
            $exceptionFulfillmentRows -ne 0 -or
            -not $exceptionRefundSucceededEventId -or
            $exceptionRefundConsumedCount -ne 1) {
            throw "Exceptional-payment final facts diverged: trade=$exceptionTradeFacts, payment=$exceptionPaymentFacts, inventory=$exceptionInventoryFacts, marketing=$exceptionMarketingFacts, fulfillment=$exceptionFulfillmentRows, refundEvent=$exceptionRefundSucceededEventId, refundConsumed=$exceptionRefundConsumedCount."
        }

        $exceptionalPaymentEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            logsUsedAsProof = $false
            identifiers = [ordered]@{
                orderNo = $exceptionOrderNo
                reservationNo = $exceptionReservationNo
                paymentNo = $exceptionPaymentNo
                refundNo = $exceptionRefundNo
                paymentSucceededEventId = $exceptionPaymentSucceededEventId
                refundSucceededEventId = $exceptionRefundSucceededEventId
            }
            accessBoundary = [ordered]@{
                customerHttpStatus = [int]$customerExceptionalRefund.StatusCode
                customerAuditRows = $customerExceptionalAuditCount
                concurrentAdminHttpStatuses = @($exceptionAdminResponses.statusCode)
                concurrentAdminAudits = $exceptionAcceptedAuditCount
                refundRowsAfterConcurrentCommands = $exceptionRefundRowCount
            }
            replayWhileTradeUnavailable = [ordered]@{
                storedCommandHttpResult = 'OK'
                storedCommandRefundNo = [string]$exceptionReplay.data.refundNo
                changedPayloadHttpStatus = [int]$exceptionConflict.StatusCode
                changedPayloadCode = [string]$exceptionConflictPayload.code
                newCommandHttpStatus = [int]$exceptionUnavailableCommand.StatusCode
                newCommandCode = [string]$exceptionUnavailablePayload.code
                newCommandAuditRows = $exceptionUnavailableAuditCount
            }
            latePaymentIntermediate = [ordered]@{
                tradeFact = $exceptionLateTradeFact
                paymentSucceededConsumedCount = $exceptionPaymentConsumedCount
                inventoryReservationStatus = $exceptionCanceledReservationStatus
                pricingLockStatus = $exceptionCanceledPricingLockStatus
                benefitStatus = $exceptionCanceledBenefitStatus
            }
            finalOwnerFacts = [ordered]@{
                trade = $exceptionTradeFacts
                payment = $exceptionPaymentFacts
                inventory = $exceptionInventoryFacts
                marketing = $exceptionMarketingFacts
                fulfillmentRows = $exceptionFulfillmentRows
                refundSucceededConsumedCount = $exceptionRefundConsumedCount
            }
        }
    }

    $paymentEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $paymentEventId = $paymentEventId | Select-Object -Last 1
    $orderPaidEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$($paymentOrder.OrderNo)' AND event_type = 'OrderPaid'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME
    $orderPaidEventId = $orderPaidEventId | Select-Object -Last 1
    if (-not $paymentEventId -or -not $orderPaidEventId) {
        throw 'The payment/order event chain was not persisted to the outboxes.'
    }

    $fulfillmentEventIds = @(@"
SELECT id FROM outbox_event
WHERE aggregate_id = '$fulfillmentNo'
  AND event_type IN ('FulfillmentCreated', 'ShipmentDispatched', 'ShipmentSigned')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME)
    $fulfillmentEventIds = @($fulfillmentEventIds | Where-Object { $_ -and $_.Trim() })
    if ($fulfillmentEventIds.Count -ne 3) {
        throw "Expected three fulfillment lifecycle events, found $($fulfillmentEventIds.Count)."
    }
    $fulfillmentEventSqlList = ($fulfillmentEventIds | ForEach-Object { "'$($_.Trim())'" }) -join ','

    $afterSaleEventRows = @(@"
SELECT event_type, id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo'
  AND event_type IN ('AfterSaleApproved', 'RefundRequested')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME)
    foreach ($row in $afterSaleEventRows) {
        $parts = $row -split "`t"
        if ($parts[0] -eq 'AfterSaleApproved') { $afterSaleApprovedEventId = $parts[1] }
        if ($parts[0] -eq 'RefundRequested') { $refundRequestedEventId = $parts[1] }
    }
    $returnEventRows = @(@"
SELECT event_type, id FROM outbox_event
WHERE aggregate_id = '$returnReceiptNo'
  AND event_type IN ('ReturnShipmentSubmitted', 'ReturnReceived', 'ReturnInspected')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME)
    foreach ($row in $returnEventRows) {
        $parts = $row -split "`t"
        if ($parts[0] -eq 'ReturnShipmentSubmitted') { $returnShipmentEventId = $parts[1] }
        if ($parts[0] -eq 'ReturnReceived') { $returnReceivedEventId = $parts[1] }
        if ($parts[0] -eq 'ReturnInspected') { $returnInspectedEventId = $parts[1] }
    }
    $returnStockedEventId = @"
SELECT event_id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'ReturnStocked' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    $refundResultEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$refundNo' AND event_type = 'RefundSucceeded' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
    if (-not $afterSaleApprovedEventId -or -not $refundRequestedEventId -or
        -not $returnShipmentEventId -or -not $returnReceivedEventId -or
        -not $returnInspectedEventId -or -not $returnStockedEventId -or -not $refundResultEventId) {
        throw 'The return/refund event chain was not persisted across all four service outboxes.'
    }

    $paymentRowCounts = @"
SELECT
  (SELECT COUNT(*) FROM payment_order WHERE payment_no = '$paymentNo'),
  (SELECT COUNT(*) FROM payment_transaction WHERE payment_id =
      (SELECT id FROM payment_order WHERE payment_no = '$paymentNo')),
  (SELECT COUNT(*) FROM payment_callback_log WHERE payment_no = '$paymentNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $paymentCounts = ($paymentRowCounts | Select-Object -Last 1) -split "`t"
    if ($paymentCounts[0] -ne '1' -or $paymentCounts[1] -ne '1' -or $paymentCounts[2] -ne '1') {
        throw "Payment callback idempotency rows were incorrect: $($paymentCounts -join '/')."
    }

    $refundRowCounts = @"
SELECT
  (SELECT COUNT(*) FROM refund_order WHERE refund_no = '$refundNo' AND status = 'SUCCESS'),
  (SELECT COUNT(*) FROM refund_transaction WHERE refund_id =
      (SELECT id FROM refund_order WHERE refund_no = '$refundNo')),
  (SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = '$refundNo'),
  (SELECT COUNT(*) FROM refund_dispatch_retry_audit
      WHERE refund_no = '$refundNo' AND outcome = 'ACCEPTED'),
  (SELECT COUNT(*) FROM refund_dispatch_retry_audit
      WHERE refund_no = '$refundNo' AND outcome = 'REJECTED');
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $refundCounts = ($refundRowCounts | Select-Object -Last 1) -split "`t"
    if ($refundCounts[0] -ne '1' -or $refundCounts[1] -ne '1' -or
        $refundCounts[2] -ne '1' -or $refundCounts[3] -ne '1' -or $refundCounts[4] -ne '1') {
        throw "Refund callback or retry audit rows were incorrect: $($refundCounts -join '/')."
    }

    $customerReconciliation = Invoke-WebRequest -Method Get -SkipHttpErrorCheck `
        -Uri "$paymentBaseUrl/admin/reconciliation/issues" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ([int]$customerReconciliation.StatusCode -ne 403) {
        throw 'A customer token was allowed to read financial reconciliation issues.'
    }

    $reconciliationFault = @"
UPDATE outbox_event
SET event_type = 'RefundSucceededFaultInjected'
WHERE id = '$refundResultEventId' AND aggregate_id = '$refundNo'
  AND event_type = 'RefundSucceeded' AND status = 'PUBLISHED';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
    if ($reconciliationFault -ne '1') {
        throw 'Unable to inject the missing refund success event reconciliation fault.'
    }

    # Reconciliation jobs share bounded schedulers with long-poll consumers in several services.
    # Allow one delayed scan cycle plus HTTP polling jitter without weakening the state assertions.
    $reconciliationVerificationBudgetSeconds = 45
    $reconciliationDeadline = (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $openReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$paymentBaseUrl/admin/reconciliation/issues?status=OPEN&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $refundReconciliationIssue = @($openReconciliation.data | Where-Object {
                $_.domain -eq 'REFUND' -and $_.referenceNo -eq $refundNo -and
                $_.issueType -eq 'REFUND_SUCCESS_EVENT_MISSING'
            })
        if ($refundReconciliationIssue.Count -eq 1) {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $reconciliationDeadline)
    if ($refundReconciliationIssue.Count -ne 1) {
        throw 'Payment reconciliation did not persist the injected refund inconsistency.'
    }
    $reconciliationMetric = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$paymentPort/actuator/metrics/ecommerce.reconciliation.issue.open" `
        -Headers $adminHeaders -TimeoutSec 10
    if ([double]$reconciliationMetric.measurements[0].value -lt 1) {
        throw 'Payment reconciliation did not expose its open issue metric.'
    }

    $reconciliationRestore = @"
UPDATE outbox_event
SET event_type = 'RefundSucceeded'
WHERE id = '$refundResultEventId' AND aggregate_id = '$refundNo'
  AND event_type = 'RefundSucceededFaultInjected';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
    if ($reconciliationRestore -ne '1') {
        throw 'Unable to restore the injected refund reconciliation fact.'
    }

    $reconciliationRecoveryDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $resolvedReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$paymentBaseUrl/admin/reconciliation/issues?status=RESOLVED&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $resolvedRefundIssue = @($resolvedReconciliation.data | Where-Object {
                $_.domain -eq 'REFUND' -and $_.referenceNo -eq $refundNo -and
                $_.issueType -eq 'REFUND_SUCCESS_EVENT_MISSING' -and $_.resolvedAt
            })
        if ($resolvedRefundIssue.Count -eq 1) {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $reconciliationRecoveryDeadline)
    if ($resolvedRefundIssue.Count -ne 1) {
        throw 'Payment reconciliation did not close the issue after the fact was restored.'
    }

    $customerInventoryReconciliation = Invoke-WebRequest -Method Get -SkipHttpErrorCheck `
        -Uri "$inventoryBaseUrl/admin/reconciliation/issues" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ([int]$customerInventoryReconciliation.StatusCode -ne 403) {
        throw 'A customer token was allowed to read inventory reconciliation issues.'
    }

    $inventoryReconciliationFault = @"
UPDATE outbox_event
SET event_type = 'ReturnStockedFaultInjected'
WHERE event_id = '$returnStockedEventId' AND aggregate_id = '$afterSaleNo'
  AND event_type = 'ReturnStocked' AND status = 'PUBLISHED';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    if ($inventoryReconciliationFault -ne '1') {
        throw 'Unable to inject the missing return-stocked event reconciliation fault.'
    }

    $inventoryReconciliationDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $openInventoryReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/reconciliation/issues?status=OPEN&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $returnReconciliationIssue = @($openInventoryReconciliation.data | Where-Object {
                $_.domain -eq 'RETURN' -and $_.referenceNo -eq $afterSaleNo -and
                $_.issueType -eq 'RETURN_EVENT_MISSING'
            })
        if ($returnReconciliationIssue.Count -eq 1) {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $inventoryReconciliationDeadline)
    if ($returnReconciliationIssue.Count -ne 1) {
        throw 'Inventory reconciliation did not persist the injected return inconsistency.'
    }
    $inventoryReconciliationMetric = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$inventoryPort/actuator/metrics/ecommerce.reconciliation.issue.open" `
        -Headers $adminHeaders -TimeoutSec 10
    if ([double]$inventoryReconciliationMetric.measurements[0].value -lt 1) {
        throw 'Inventory reconciliation did not expose its open issue metric.'
    }

    $inventoryReconciliationRestore = @"
UPDATE outbox_event
SET event_type = 'ReturnStocked'
WHERE event_id = '$returnStockedEventId' AND aggregate_id = '$afterSaleNo'
  AND event_type = 'ReturnStockedFaultInjected';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    if ($inventoryReconciliationRestore -ne '1') {
        throw 'Unable to restore the injected inventory reconciliation fact.'
    }

    $inventoryReconciliationRecoveryDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $resolvedInventoryReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/reconciliation/issues?status=RESOLVED&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $resolvedReturnIssue = @($resolvedInventoryReconciliation.data | Where-Object {
                $_.domain -eq 'RETURN' -and $_.referenceNo -eq $afterSaleNo -and
                $_.issueType -eq 'RETURN_EVENT_MISSING' -and $_.resolvedAt
            })
        if ($resolvedReturnIssue.Count -eq 1) {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $inventoryReconciliationRecoveryDeadline)
    if ($resolvedReturnIssue.Count -ne 1) {
        throw 'Inventory reconciliation did not close the issue after the fact was restored.'
    }

    $customerTradeReconciliation = Invoke-WebRequest -Method Get -SkipHttpErrorCheck `
        -Uri "$tradeBaseUrl/admin/reconciliation/issues" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ([int]$customerTradeReconciliation.StatusCode -ne 403) {
        throw 'A customer token was allowed to read trade reconciliation issues.'
    }
    $orderCompletedEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$($paymentOrder.OrderNo)' AND event_type = 'OrderCompleted' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME | Select-Object -Last 1
    $tradeReconciliationFault = @"
UPDATE outbox_event
SET event_type = 'OrderCompletedFaultInjected'
WHERE id = '$orderCompletedEventId' AND aggregate_id = '$($paymentOrder.OrderNo)'
  AND event_type = 'OrderCompleted' AND status = 'PUBLISHED';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME | Select-Object -Last 1
    if ($tradeReconciliationFault -ne '1') {
        throw 'Unable to inject the missing completed-order event reconciliation fault.'
    }
    $tradeReconciliationDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $openTradeReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/admin/reconciliation/issues?status=OPEN&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $orderReconciliationIssue = @($openTradeReconciliation.data | Where-Object {
                $_.domain -eq 'ORDER' -and $_.referenceNo -eq $paymentOrder.OrderNo -and
                $_.issueType -eq 'ORDER_STATE_EVENT_MISSING'
            })
        if ($orderReconciliationIssue.Count -eq 1) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $tradeReconciliationDeadline)
    if ($orderReconciliationIssue.Count -ne 1) {
        throw 'Trade reconciliation did not persist the injected completed-order inconsistency.'
    }
    $tradeReconciliationMetric = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$tradePort/actuator/metrics/ecommerce.reconciliation.issue.open" `
        -Headers $adminHeaders -TimeoutSec 10
    if ([double]$tradeReconciliationMetric.measurements[0].value -lt 1) {
        throw 'Trade reconciliation did not expose its open issue metric.'
    }
    $tradeReconciliationRestore = @"
UPDATE outbox_event
SET event_type = 'OrderCompleted'
WHERE id = '$orderCompletedEventId' AND aggregate_id = '$($paymentOrder.OrderNo)'
  AND event_type = 'OrderCompletedFaultInjected';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME | Select-Object -Last 1
    if ($tradeReconciliationRestore -ne '1') {
        throw 'Unable to restore the injected trade reconciliation fact.'
    }
    $tradeReconciliationRecoveryDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $resolvedTradeReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/admin/reconciliation/issues?status=RESOLVED&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $resolvedOrderIssue = @($resolvedTradeReconciliation.data | Where-Object {
                $_.domain -eq 'ORDER' -and $_.referenceNo -eq $paymentOrder.OrderNo -and
                $_.issueType -eq 'ORDER_STATE_EVENT_MISSING' -and $_.resolvedAt
            })
        if ($resolvedOrderIssue.Count -eq 1) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $tradeReconciliationRecoveryDeadline)
    if ($resolvedOrderIssue.Count -ne 1) {
        throw 'Trade reconciliation did not close the issue after the fact was restored.'
    }

    $customerFulfillmentReconciliation = Invoke-WebRequest -Method Get -SkipHttpErrorCheck `
        -Uri "$fulfillmentBaseUrl/admin/reconciliation/issues" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ([int]$customerFulfillmentReconciliation.StatusCode -ne 403) {
        throw 'A customer token was allowed to read fulfillment reconciliation issues.'
    }
    $shipmentSignedEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$fulfillmentNo' AND event_type = 'ShipmentSigned' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME | Select-Object -Last 1
    $fulfillmentReconciliationFault = @"
UPDATE outbox_event
SET event_type = 'ShipmentSignedFaultInjected'
WHERE id = '$shipmentSignedEventId' AND aggregate_id = '$fulfillmentNo'
  AND event_type = 'ShipmentSigned' AND status = 'PUBLISHED';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME | Select-Object -Last 1
    if ($fulfillmentReconciliationFault -ne '1') {
        throw 'Unable to inject the missing signed-shipment event reconciliation fault.'
    }
    $fulfillmentReconciliationDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $openFulfillmentReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$fulfillmentBaseUrl/admin/reconciliation/issues?status=OPEN&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $fulfillmentReconciliationIssue = @($openFulfillmentReconciliation.data | Where-Object {
                $_.domain -eq 'FULFILLMENT' -and $_.referenceNo -eq $fulfillmentNo -and
                $_.issueType -eq 'FULFILLMENT_STATE_EVENT_MISSING'
            })
        if ($fulfillmentReconciliationIssue.Count -eq 1) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $fulfillmentReconciliationDeadline)
    if ($fulfillmentReconciliationIssue.Count -ne 1) {
        throw 'Fulfillment reconciliation did not persist the injected signed-shipment inconsistency.'
    }
    $fulfillmentReconciliationMetric = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$fulfillmentPort/actuator/metrics/ecommerce.reconciliation.issue.open" `
        -Headers $adminHeaders -TimeoutSec 10
    if ([double]$fulfillmentReconciliationMetric.measurements[0].value -lt 1) {
        throw 'Fulfillment reconciliation did not expose its open issue metric.'
    }
    $fulfillmentReconciliationRestore = @"
UPDATE outbox_event
SET event_type = 'ShipmentSigned'
WHERE id = '$shipmentSignedEventId' AND aggregate_id = '$fulfillmentNo'
  AND event_type = 'ShipmentSignedFaultInjected';
SELECT ROW_COUNT();
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME | Select-Object -Last 1
    if ($fulfillmentReconciliationRestore -ne '1') {
        throw 'Unable to restore the injected fulfillment reconciliation fact.'
    }
    $fulfillmentReconciliationRecoveryDeadline = `
        (Get-Date).AddSeconds($reconciliationVerificationBudgetSeconds)
    do {
        $resolvedFulfillmentReconciliation = Invoke-RestMethod -Method Get `
            -Uri "$fulfillmentBaseUrl/admin/reconciliation/issues?status=RESOLVED&limit=100" `
            -Headers $adminHeaders -TimeoutSec 10
        $resolvedFulfillmentIssue = @($resolvedFulfillmentReconciliation.data | Where-Object {
                $_.domain -eq 'FULFILLMENT' -and $_.referenceNo -eq $fulfillmentNo -and
                $_.issueType -eq 'FULFILLMENT_STATE_EVENT_MISSING' -and $_.resolvedAt
            })
        if ($resolvedFulfillmentIssue.Count -eq 1) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $fulfillmentReconciliationRecoveryDeadline)
    if ($resolvedFulfillmentIssue.Count -ne 1) {
        throw 'Fulfillment reconciliation did not close the issue after the fact was restored.'
    }

    for ($attempt = 1; $attempt -lt 5; $attempt++) {
        $failedLogin = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/login" -Body @{
            email = $riskEmail
            password = 'WrongPassword123'
        }
        $failedPayload = $failedLogin.Content | ConvertFrom-Json
        if ([int]$failedLogin.StatusCode -ne 401 -or $failedPayload.code -ne 'INVALID_CREDENTIALS') {
            throw "Unexpected login-risk response at attempt $attempt."
        }
    }
    $lockedLogin = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $riskEmail
        password = 'WrongPassword123'
    }
    $lockedPayload = $lockedLogin.Content | ConvertFrom-Json
    if ([int]$lockedLogin.StatusCode -ne 429 -or $lockedPayload.code -ne 'LOGIN_TEMPORARILY_LOCKED') {
        throw 'Identity login-attempt locking did not trigger on the fifth failure.'
    }
    $identityLockExists = docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis `
        redis-cli EXISTS "ecommerce:local:identity:login:lock:$riskHash"
    if (($identityLockExists | Select-Object -Last 1) -ne '1') {
        throw 'Identity login lock was not stored in Redis.'
    }

    $gatewayLimited = $false
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $registrationProbe = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/register" -Body @{
            email = 'invalid'
        }
        $registrationPayload = $registrationProbe.Content | ConvertFrom-Json
        if ([int]$registrationProbe.StatusCode -eq 429 -and $registrationPayload.code -eq 'GATEWAY_RATE_LIMITED') {
            if ($registrationProbe.Headers['X-RateLimit-Policy'] -ne 'registration') {
                throw 'Gateway rate-limit response did not identify its policy.'
            }
            $gatewayLimited = $true
            break
        }
    }
    if (-not $gatewayLimited) {
        throw 'Gateway registration rate limit did not trigger.'
    }

    if ($EnableRedisFaultInjection) {
        docker stop plainjournal-redis | Out-Null
        $redisStoppedBySmoke = $true
        $redisRunningDuringLogin = (docker inspect --format '{{.State.Running}}' plainjournal-redis) -eq 'true'
        if ($redisRunningDuringLogin) {
            throw 'Redis fault injection did not stop the owner cache before login.'
        }
        $degradedLogin = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
            email = $smokeEmail
            password = $password
        }
        if (-not $degradedLogin.data.accessToken) {
            throw 'Login failed instead of using local fallback while Redis was unavailable.'
        }
        docker start plainjournal-redis | Out-Null
        Wait-ContainerHealthy -Container 'plainjournal-redis'
        $redisStoppedBySmoke = $false
        $redisFallbackEvidence = [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            dependency = 'identity-service -> redis'
            outage = [ordered]@{
                redisContainerRunning = $redisRunningDuringLogin
                loginAccessTokenIssued = [bool]$degradedLogin.data.accessToken
                loginRefreshTokenIssued = [bool]$degradedLogin.data.refreshToken
            }
            recovery = [ordered]@{
                redisContainerRunning = (
                    (docker inspect --format '{{.State.Running}}' plainjournal-redis) -eq 'true')
                redisContainerHealth = [string](
                    docker inspect --format '{{.State.Health.Status}}' plainjournal-redis)
            }
        }
    }

    $outboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $unpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND (aggregate_id LIKE '$inventoryReservationPrefix%'
       OR aggregate_id IN ('$inventoryWarehouseId`:$inventorySkuId', '$inventoryWarehouseId`:$tradeSkuId', '$inventoryWarehouseId`:$exceptionSkuId')
       OR aggregate_id IN ($tradeReservationSqlList)
       OR aggregate_id = '$afterSaleNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME
        $unpublishedCount = [int]($unpublished | Select-Object -Last 1)
        if ($unpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $outboxDeadline)
    if ($unpublishedCount -ne 0) {
        throw "$unpublishedCount inventory outbox events were not published before the deadline."
    }

    $tradeOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $tradeUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND (aggregate_id IN ($tradeOrderSqlList) OR aggregate_id = '$afterSaleNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME
        $tradeUnpublishedCount = [int]($tradeUnpublished | Select-Object -Last 1)
        if ($tradeUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $tradeOutboxDeadline)
    if ($tradeUnpublishedCount -ne 0) {
        throw "$tradeUnpublishedCount trade outbox events were not published before the deadline."
    }

    $fulfillmentOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $fulfillmentUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED' AND aggregate_id IN ('$fulfillmentNo', '$returnReceiptNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME
        $fulfillmentUnpublishedCount = [int]($fulfillmentUnpublished | Select-Object -Last 1)
        if ($fulfillmentUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $fulfillmentOutboxDeadline)
    if ($fulfillmentUnpublishedCount -ne 0) {
        throw "$fulfillmentUnpublishedCount fulfillment outbox events were not published before the deadline."
    }

    $paymentOutboxAggregateSqlList = @(
        $paymentNo, $refundNo, $exceptionPaymentNo, $exceptionRefundNo
    ) | Where-Object { $_ } | ForEach-Object { "'$_'" } | Join-String -Separator ','
    $paymentOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $paymentUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED' AND aggregate_id IN ($paymentOutboxAggregateSqlList);
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
            mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
        $paymentUnpublishedCount = [int]($paymentUnpublished | Select-Object -Last 1)
        if ($paymentUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $paymentOutboxDeadline)
    if ($paymentUnpublishedCount -ne 0) {
        throw "$paymentUnpublishedCount payment outbox events were not published before the deadline."
    }

    if ($EnableCapacityBaseline) {
        $capacityEvidencePath = Join-Path $runDirectory 'capacity-baseline.json'
        $gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -Last 1)
        $capacityEvidence = [ordered]@{
            schemaVersion = 3
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            gitCommit = $gitCommit
            parameters = [ordered]@{
                requestsPerScenario = $CapacityRequests
                concurrency = $CapacityConcurrency
                inventorySuccesses = $CapacityInventorySuccesses
                tradeSuccesses = $CapacityTradeSuccesses
            }
            inventoryReservation = $inventoryLatencySummary
            tradeOrder = $tradeLatencySummary
            tradeOrderSameKey = $tradeIdempotencyLatencySummary
            paymentCallbackSameEvent = $paymentCallbackLatencySummary
            refundCallbackSameEvent = $refundCallbackLatencySummary
            paymentChainConvergenceSeconds = $paymentChainConvergenceSeconds
            tradeOutboxUnpublishedAtPayment = $tradeOutboxUnpublishedAtPayment
            tradeOutboxUnpublishedAtPaymentChainConvergence = $tradeOutboxUnpublishedAtConvergence
            returnChainConvergenceSeconds = $returnChainConvergenceSeconds
            inventoryOutboxUnpublishedAtReturnInspection = `
                $inventoryOutboxUnpublishedAtReturnInspection
            inventoryOutboxUnpublishedAtReturnChainConvergence = `
                $inventoryOutboxUnpublishedAtReturnChainConvergence
            correctness = [ordered]@{
                inventoryReserved = $inventoryCompetitionSuccesses
                inventoryRejected = $inventoryCompetitionAttempts - $inventoryCompetitionSuccesses
                tradeInitiallyPayable = $tradeCompetitionSuccesses
                tradeClosed = $tradeCompetitionAttempts - $tradeCompetitionSuccesses
                activeReservationsAtPaymentChainConvergence = $expectedActiveTradeReservations
                unpaidReservationsExpiredDuringConvergence = `
                    (($tradeCompetitionSuccesses - 1) - $expectedActiveTradeReservations)
                transportErrors = 0
                stockEquationVerified = $true
                idempotencyVerified = $true
            }
        }
        $capacityEvidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $capacityEvidencePath -Encoding utf8
    }
    $internalTrustZoneEvidence | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $internalTrustZoneEvidencePath -Encoding utf8
    $fulfillmentExceptionEvidence | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $fulfillmentExceptionEvidencePath -Encoding utf8
    if ($EnablePaymentInventoryConfirmationFaultInjection) {
        $paymentInventoryCausalityEvidence | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $paymentInventoryCausalityEvidencePath -Encoding utf8
    }
    if ($EnableSynchronousResilienceFaultInjection) {
        $synchronousResilienceEvidence | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $synchronousResilienceEvidencePath -Encoding utf8
    }
    if ($EnableTradeMarketingResilienceFaultInjection) {
        $tradeMarketingResilienceEvidence | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $tradeMarketingResilienceEvidencePath -Encoding utf8
    }
    if ($EnableRedisFaultInjection) {
        $redisFallbackEvidence | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $redisFallbackEvidencePath -Encoding utf8
    }
    if ($EnableDistributedTracing) {
        [ordered]@{
            schemaVersion = 1
            generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            logsUsedAsProof = $false
            backend = 'tempo'
            payment = [ordered]@{
                traceId = $paymentTraceId
                services = $tracingEvidence.Services
                spanNames = $tracingEvidence.SpanNames
                eventType = 'PaymentSucceeded'
            }
            refund = [ordered]@{
                traceId = $refundTraceId
                services = $refundTracingEvidence.Services
                spanNames = $refundTracingEvidence.SpanNames
                eventType = 'RefundSucceeded'
            }
        } | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $distributedTracingEvidencePath -Encoding utf8
    }
    if ($EnableExceptionalPaymentRecoveryVerification) {
        $exceptionalPaymentEvidence | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $exceptionalPaymentEvidencePath -Encoding utf8
    }

    Write-Output 'Foundation smoke test: PASS'
    Write-Output '  Gateway health: UP'
    Write-Output '  Identity health: UP'
    Write-Output '  Catalog health: UP'
    Write-Output '  Inventory health: UP'
    Write-Output '  Trade health: UP'
    Write-Output '  Payment health: UP'
    Write-Output '  Fulfillment health: UP'
    Write-Output '  Marketing health: UP'
    Write-Output '  Nacos discovery route: PASS'
    Write-Output '  Request ID propagation: PASS'
    Write-Output "  Configuration source: $($payload.data.configurationSource)"
    Write-Output '  MySQL/Flyway identity schema: PASS'
    Write-Output '  MySQL/Flyway catalog schema: PASS'
    Write-Output '  MySQL/Flyway inventory schema: PASS'
    Write-Output '  MySQL/Flyway trade schema: PASS'
    Write-Output '  MySQL/Flyway payment schema: PASS'
    Write-Output '  MySQL/Flyway fulfillment schema: PASS'
    Write-Output '  MySQL/Flyway marketing schema: PASS'
    Write-Output '  Register/login/JWT profile: PASS'
    Write-Output '  Delivery address CRUD/internal ownership: PASS'
    Write-Output '  Refresh rotation/logout revocation: PASS'
    Write-Output '  Catalog customer/admin RBAC: PASS'
    Write-Output '  Category/brand/SPU/SKU publication: PASS'
    Write-Output '  MinIO pre-signed product media: PASS'
    Write-Output '  Inventory customer/internal RBAC: PASS'
    Write-Output "  Internal trust-zone evidence: $internalTrustZoneEvidencePath"
    Write-Output "  Forced-UTC database time contract evidence: $databaseTimeContractEvidencePath"
    Write-Output "  Real MySQL stock competition ($inventoryCompetitionSuccesses/$inventoryCompetitionAttempts): PASS"
    Write-Output "    P50=$($inventoryLatencySummary.p50Ms) ms, P95=$($inventoryLatencySummary.p95Ms) ms, P99=$($inventoryLatencySummary.p99Ms) ms, RPS=$($inventoryLatencySummary.requestsPerSecond)"
    Write-Output '  Reservation idempotency/confirm/release: PASS'
    if ($EnableInventoryReservationResponseLossFaultInjection) {
        Write-Output '  Inventory committed reservation -> dropped HTTP response -> authoritative query recovery: PASS'
        Write-Output "    Evidence: $inventoryResponseLossEvidencePath"
    }
    else {
        Write-Output '  Inventory reservation response-loss recovery: SKIPPED (use -EnableInventoryReservationResponseLossFaultInjection)'
    }
    Write-Output '  RocketMQ inventory outbox publication: PASS'
    Write-Output "  Trade order competition ($tradeCompetitionSuccesses/$tradeCompetitionAttempts): PASS"
    Write-Output "    P50=$($tradeLatencySummary.p50Ms) ms, P95=$($tradeLatencySummary.p95Ms) ms, P99=$($tradeLatencySummary.p99Ms) ms, RPS=$($tradeLatencySummary.requestsPerSecond)"
    if ($EnableCapacityBaseline) {
        Write-Output "  Capacity evidence: $capacityEvidencePath"
        Write-Output "  Same order key x100: P95=$($tradeIdempotencyLatencySummary.p95Ms) ms, one cross-domain fact: PASS"
        Write-Output "  Same payment callback x100: P95=$($paymentCallbackLatencySummary.p95Ms) ms, one effective result: PASS"
        Write-Output "  Same refund callback x100: P95=$($refundCallbackLatencySummary.p95Ms) ms, one effective result: PASS"
        Write-Output "  Payment-chain convergence: $paymentChainConvergenceSeconds s; active unpaid reservations at convergence: $expectedActiveTradeReservations"
    }
    Write-Output '  Trade snapshot/idempotency/cancellation: PASS'
    Write-Output '  Coupon/red packet/subsidy stacking and allocation: PASS'
    Write-Output '  Marketing cancel release/payment redemption: PASS'
    Write-Output '  Address edit/delete -> immutable trade/fulfillment snapshot: PASS'
    Write-Output '  RocketMQ trade outbox publication: PASS'
    Write-Output '  Signed payment callback/idempotency: PASS'
    Write-Output '  PaymentSucceeded -> PAYMENT_CONFIRMING -> inventory confirm -> OrderPaid/fulfillment create: PASS'
    if ($EnablePaymentInventoryConfirmationFaultInjection) {
        Write-Output "    Causality evidence: $paymentInventoryCausalityEvidencePath"
    }
    Write-Output '  Picking -> packed -> shipped -> logistics -> signed: PASS'
    Write-Output "  Fulfillment exception authorization/idempotency/concurrency evidence: $fulfillmentExceptionEvidencePath"
    Write-Output '  Fulfillment events -> trade COMPLETED: PASS'
    Write-Output '  Whole-order after-sale price allocation snapshot: PASS'
    Write-Output '  After-sale approval -> return receipt -> warehouse inspection: PASS'
    Write-Output '  Original confirmed reservation validation -> idempotent return stock: PASS'
    Write-Output '  RefundRequested -> persisted channel dispatch -> signed callback -> COMPLETED: PASS'
    Write-Output '  Domain-authorized refund retry/idempotency/audit: PASS'
    if ($EnableExceptionalPaymentRecoveryVerification) {
        Write-Output '  Canceled order -> late payment -> authorized exceptional refund -> CLOSED: PASS'
        Write-Output "    Evidence: $exceptionalPaymentEvidencePath"
    }
    else {
        Write-Output '  Exceptional-payment recovery verification: SKIPPED (use -EnableExceptionalPaymentRecoveryVerification)'
    }
    Write-Output '  Payment/refund reconciliation detection and recovery: PASS'
    Write-Output '  Inventory reconciliation detection and recovery: PASS'
    Write-Output '  Trade order/after-sale reconciliation detection and recovery: PASS'
    Write-Output '  Fulfillment/return reconciliation detection and recovery: PASS'
    Write-Output '  Redis email failure lock: PASS'
    Write-Output '  Gateway authentication rate limit: PASS'
    Write-Output '  ADMIN-only diagnostics and authenticated Prometheus export: PASS'
    if ($EnableSynchronousResilienceFaultInjection) {
        Write-Output '  Payment -> Trade timeout/retry/circuit recovery and zero dirty writes: PASS'
        Write-Output '  Crashed Trade distributed-ID lease expiry and same-worker restart: PASS'
        Write-Output "    Evidence: $synchronousResilienceEvidencePath"
    }
    else {
        Write-Output '  Payment -> Trade synchronous resilience fault injection: SKIPPED (use -EnableSynchronousResilienceFaultInjection)'
    }
    if ($EnableTradeMarketingResilienceFaultInjection) {
        Write-Output '  Trade order-recovery scheduler isolation and age metrics: PASS'
        Write-Output '  Trade -> Marketing idempotent command/circuit recovery and stock isolation: PASS'
        Write-Output "    Evidence: $tradeMarketingResilienceEvidencePath"
    }
    else {
        Write-Output '  Trade -> Marketing synchronous resilience fault injection: SKIPPED (use -EnableTradeMarketingResilienceFaultInjection)'
    }
    if ($EnableRedisFaultInjection) {
        Write-Output '  Redis outage local fallback: PASS'
        Write-Output "    Evidence: $redisFallbackEvidencePath"
    }
    else {
        Write-Output '  Redis outage local fallback: SKIPPED (use -EnableRedisFaultInjection)'
    }
    if ($EnableDistributedTracing) {
        Write-Output '  Payment HTTP -> persisted Outbox context -> RocketMQ -> Trade trace in Tempo: PASS'
        Write-Output "    Services: $($tracingEvidence.Services -join ', ')"
        Write-Output '  Refund HTTP -> persisted Outbox context -> RocketMQ -> Trade trace in Tempo: PASS'
        Write-Output "    Services: $($refundTracingEvidence.Services -join ', ')"
        Write-Output "    Evidence: $distributedTracingEvidencePath"
    }
    else {
        Write-Output '  Distributed tracing real backend verification: SKIPPED (use -EnableDistributedTracing)'
    }
}
catch {
    Show-LogTail -Path $identityOut
    Show-LogTail -Path $identityErr
    Show-LogTail -Path $catalogOut
    Show-LogTail -Path $catalogErr
    Show-LogTail -Path $inventoryOut
    Show-LogTail -Path $inventoryErr
    Show-LogTail -Path $inventoryRecoveryOut
    Show-LogTail -Path $inventoryRecoveryErr
    Show-LogTail -Path $tradeOut
    Show-LogTail -Path $tradeErr
    Show-LogTail -Path $tradeRecoveryOut
    Show-LogTail -Path $tradeRecoveryErr
    Show-LogTail -Path $paymentOut
    Show-LogTail -Path $paymentErr
    Show-LogTail -Path $fulfillmentOut
    Show-LogTail -Path $fulfillmentErr
    Show-LogTail -Path $marketingOut
    Show-LogTail -Path $marketingErr
    Show-LogTail -Path $marketingRecoveryOut
    Show-LogTail -Path $marketingRecoveryErr
    Show-LogTail -Path $gatewayOut
    Show-LogTail -Path $gatewayErr
    Show-LogTail -Path $inventoryResponseLossProxyOut
    Show-LogTail -Path $inventoryResponseLossProxyErr
    throw
}
finally {
    Stop-FoundationServices -Ports @(
        $gatewayPort,
        $identityPort,
        $catalogPort,
        $inventoryPort,
        $tradePort,
        $paymentPort,
        $fulfillmentPort,
        $marketingPort
    )

    # Capture source event IDs before deleting any Outbox rows. The main flow can fail
    # before the normal evidence queries run, but dependent consumed_event rows still
    # need exact cleanup.
    if ($tradeOrderNumbers.Count -gt 0) {
        $tradeOrderSqlList = ($tradeOrderNumbers | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $orderPaidEventIds = @(Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id IN ($tradeOrderSqlList) AND event_type = 'OrderPaid';
"@)
        $orderPaidEventId = $orderPaidEventIds | Select-Object -Last 1
        $orderLifecycleEventIds = @(Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id IN ($tradeOrderSqlList)
  AND event_type IN ('OrderPaid', 'OrderCanceled', 'OrderClosed');
"@)
    }
    if ($paymentNo) {
        $paymentSucceededEventIds = @(Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded';
"@)
        if ($exceptionPaymentSucceededEventId) {
            $paymentSucceededEventIds += $exceptionPaymentSucceededEventId
        }
        $paymentEventId = $paymentSucceededEventIds | Select-Object -Last 1
    }
    if ($fulfillmentNo) {
        $fulfillmentLifecycleEventIds = @(Get-MySqlSingleColumn `
                -Database $env:FULFILLMENT_DB_NAME `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$fulfillmentNo'
  AND event_type IN ('FulfillmentCreated', 'ShipmentDispatched', 'ShipmentSigned');
"@)
        $fulfillmentEventIds = $fulfillmentLifecycleEventIds
        $fulfillmentEventSqlList = ($fulfillmentEventIds |
                ForEach-Object { "'$_'" }) -join ','
    }
    if ($afterSaleNo) {
        $afterSaleApprovedEventIds = @(Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'AfterSaleApproved';
"@)
        $afterSaleApprovedEventId = $afterSaleApprovedEventIds | Select-Object -Last 1
        $refundRequestedEventIds = @(Get-MySqlSingleColumn `
                -Database $env:TRADE_DB_NAME `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'RefundRequested';
"@)
        $refundRequestedEventId = $refundRequestedEventIds | Select-Object -Last 1
        $returnStockedEventIds = @(Get-MySqlSingleColumn `
                -Database $env:INVENTORY_DB_NAME `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Query @"
SELECT event_id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'ReturnStocked';
"@)
        $returnStockedEventId = $returnStockedEventIds | Select-Object -Last 1
    }
    if ($returnReceiptNo) {
        $returnShipmentEventIds = @(Get-MySqlSingleColumn `
                -Database $env:FULFILLMENT_DB_NAME `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$returnReceiptNo' AND event_type = 'ReturnShipmentSubmitted';
"@)
        $returnShipmentEventId = $returnShipmentEventIds | Select-Object -Last 1
        $returnReceivedEventIds = @(Get-MySqlSingleColumn `
                -Database $env:FULFILLMENT_DB_NAME `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$returnReceiptNo' AND event_type = 'ReturnReceived';
"@)
        $returnReceivedEventId = $returnReceivedEventIds | Select-Object -Last 1
        $returnInspectedEventIds = @(Get-MySqlSingleColumn `
                -Database $env:FULFILLMENT_DB_NAME `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$returnReceiptNo' AND event_type = 'ReturnInspected';
"@)
        $returnInspectedEventId = $returnInspectedEventIds | Select-Object -Last 1
    }
    if ($refundNo) {
        $refundResultEventIds = @(Get-MySqlSingleColumn `
                -Database $env:PAYMENT_DB_NAME `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Query @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$refundNo' AND event_type = 'RefundSucceeded';
"@)
        if ($exceptionRefundSucceededEventId) {
            $refundResultEventIds += $exceptionRefundSucceededEventId
        }
        $refundResultEventId = $refundResultEventIds | Select-Object -Last 1
    }

    if ($inventoryResponseLossNacosRegistrationAttempted -and
        $null -ne $inventoryResponseLossNacosHeaders) {
        try {
            Remove-NacosFixedInstance `
                -Headers $inventoryResponseLossNacosHeaders `
                -ServiceName $inventoryResponseLossProxyService `
                -Ip '127.0.0.1' `
                -Port $inventoryResponseLossProxyPort
        }
        catch {
            Write-Warning "Unable to remove the response-loss proxy from Nacos: $($_.Exception.Message)"
        }
    }
    if ($inventoryResponseLossNacosServiceCreated -and
        $null -ne $inventoryResponseLossNacosHeaders) {
        try {
            Remove-NacosService `
                -Headers $inventoryResponseLossNacosHeaders `
                -ServiceName $inventoryResponseLossProxyService
        }
        catch {
            Write-Warning "Unable to remove the response-loss proxy service from Nacos: $($_.Exception.Message)"
        }
    }
    if ($null -ne $inventoryResponseLossProxyProcess -and
        -not $inventoryResponseLossProxyProcess.HasExited) {
        Stop-Process -Id $inventoryResponseLossProxyProcess.Id -Force
        $inventoryResponseLossProxyProcess.WaitForExit(5000)
    }
    [Environment]::SetEnvironmentVariable('TRADE_INVENTORY_BASE_URL', $null, 'Process')
    Remove-Item -LiteralPath $inventoryResponseLossProxyReadyPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $inventoryResponseLossArmPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $inventoryResponseLossProxyEvidencePath -Force -ErrorAction SilentlyContinue

    if ($redisStoppedBySmoke) {
        docker start plainjournal-redis | Out-Null
        Wait-ContainerHealthy -Container 'plainjournal-redis'
    }
    Remove-RedisKeys -Keys $redisKeys

    if ($catalogObjectKey) {
        docker exec -e "SMOKE_OBJECT_KEY=$catalogObjectKey" plainjournal-minio sh -c `
            'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc rm --force "local/product-media/$SMOKE_OBJECT_KEY" >/dev/null' 2>$null
    }

    $catalogCleanupSql = @"
DELETE FROM product_media WHERE object_key = '$catalogObjectKey';
DELETE FROM product_sku WHERE spu_id IN (SELECT id FROM product_spu WHERE title = '$catalogProductTitle');
DELETE FROM product_spu WHERE title = '$catalogProductTitle';
DELETE FROM catalog_brand WHERE slug = '$catalogBrandSlug';
DELETE FROM catalog_category WHERE slug = '$catalogCategorySlug';
"@
    $catalogCleanupSql | docker exec -i -e "MYSQL_PWD=$env:CATALOG_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:CATALOG_DB_USER" $env:CATALOG_DB_NAME 2>$null

    if ($inventoryWarehouseId) {
        $inventoryAggregateId = "$inventoryWarehouseId`:$inventorySkuId"
        $tradeInventoryAggregateId = "$inventoryWarehouseId`:$tradeSkuId"
        $exceptionInventoryAggregateId = "$inventoryWarehouseId`:$exceptionSkuId"
        $inventoryConsumedEventIds = @($orderLifecycleEventIds) + @($returnInspectedEventIds)
        $inventoryConsumedEventSqlList = ($inventoryConsumedEventIds |
                Where-Object { $_ } | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $inventoryConsumedEventDelete = if ($inventoryConsumedEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($inventoryConsumedEventSqlList);"
        } else {
            ''
        }
        $reservationFilter = if ($tradeReservationNumbers.Count -gt 0) {
            " OR aggregate_id IN ($tradeReservationSqlList)"
        } else {
            ''
        }
        $inventoryCleanupSql = @"
$inventoryConsumedEventDelete
DELETE FROM outbox_event
WHERE aggregate_id LIKE '$inventoryReservationPrefix%'
   OR aggregate_id IN ('$inventoryAggregateId', '$tradeInventoryAggregateId', '$exceptionInventoryAggregateId', '$afterSaleNo')$reservationFilter;
DELETE FROM reconciliation_record
WHERE reference_no = '$afterSaleNo'
   OR reference_no LIKE '$inventoryWarehouseId`:%';
DELETE FROM stock_movement WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_return WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_reservation_item
WHERE reservation_id IN (SELECT id FROM inventory_reservation WHERE warehouse_id = $inventoryWarehouseId);
DELETE FROM inventory_reservation WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM stock_adjustment WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_balance WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM warehouse WHERE id = $inventoryWarehouseId;
"@
        $inventoryCleanupSql | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME 2>$null
    }

    if ($fulfillmentNo) {
        $fulfillmentConsumedEventIds = @($orderPaidEventIds) + @($afterSaleApprovedEventIds)
        $fulfillmentConsumedEventSqlList = ($fulfillmentConsumedEventIds |
                Where-Object { $_ } | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $fulfillmentConsumedEventDelete = if ($fulfillmentConsumedEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($fulfillmentConsumedEventSqlList);"
        } else {
            ''
        }
        $fulfillmentCleanupSql = @"
$fulfillmentConsumedEventDelete
DELETE FROM outbox_event WHERE aggregate_id IN ('$fulfillmentNo', '$returnReceiptNo');
DELETE FROM reconciliation_record WHERE reference_no IN ('$fulfillmentNo', '$returnReceiptNo');
DELETE FROM return_status_history
WHERE return_receipt_id IN (SELECT id FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo');
DELETE FROM return_item
WHERE return_receipt_id IN (SELECT id FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo');
DELETE FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo';
DELETE FROM logistics_trace
WHERE fulfillment_id IN (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_exception_resolution
WHERE fulfillment_id IN (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_status_history
WHERE fulfillment_id IN (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';
"@
        $fulfillmentCleanupSql | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME 2>$null
    }

    if ($tradeOrderNumbers.Count -gt 0) {
        $marketingLifecycleEventSqlList = ($orderLifecycleEventIds |
                Where-Object { $_ } | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $marketingConsumedDelete = if ($marketingLifecycleEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($marketingLifecycleEventSqlList);"
        } else {
            ''
        }
        $allMarketingBenefitNos = @($marketingBenefitNos) +
            @($marketingCancelBenefitNos) +
            @($marketingExceptionBenefitNos)
        $marketingBenefitSqlList = ($allMarketingBenefitNos | Where-Object { $_ } |
            ForEach-Object { "'$_'" }) -join ','
        $marketingBenefitDelete = if ($marketingBenefitSqlList) {
            "DELETE FROM user_benefit WHERE benefit_no IN ($marketingBenefitSqlList);"
        } else {
            ''
        }
        $marketingCleanupSql = @"
$marketingConsumedDelete
DELETE FROM pricing_lock_allocation
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM pricing_lock_benefit
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList);
$marketingBenefitDelete
DELETE FROM marketing_rule_region
WHERE rule_id IN (SELECT id FROM marketing_rule WHERE rule_code LIKE '$marketingRulePrefix%');
DELETE FROM marketing_rule WHERE rule_code LIKE '$marketingRulePrefix%';
"@
        $marketingCleanupSql | docker exec -i -e "MYSQL_PWD=$env:MARKETING_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:MARKETING_DB_USER" $env:MARKETING_DB_NAME 2>$null
    }

    if ($tradeOrderNumbers.Count -gt 0) {
        $tradeConsumedEventIds = @($paymentSucceededEventIds) +
            @($fulfillmentLifecycleEventIds) +
            @($returnShipmentEventIds) +
            @($returnReceivedEventIds) +
            @($returnStockedEventIds) +
            @($refundResultEventIds) +
            @($exceptionPaymentSucceededEventId) +
            @($exceptionRefundSucceededEventId)
        $tradeConsumedEventSqlList = ($tradeConsumedEventIds |
                Where-Object { $_ } | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $tradeConsumedEventDelete = if ($tradeConsumedEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($tradeConsumedEventSqlList);"
        } else {
            ''
        }
        $tradeConsumerFailurePredicates = @(
            "JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '`$.payload.orderNo')) IN ($tradeOrderSqlList)"
        )
        if ($afterSaleNo) {
            $tradeConsumerFailurePredicates +=
                "JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '`$.payload.afterSaleNo')) = '$afterSaleNo'"
        }
        $tradeConsumerFailureDelete = "DELETE FROM consumer_failure WHERE " +
            ($tradeConsumerFailurePredicates -join ' OR ') + ';'
        $tradeCleanupSql = @"
$tradeConsumedEventDelete
$tradeConsumerFailureDelete
DELETE FROM outbox_event
WHERE aggregate_id IN ($tradeOrderSqlList) OR aggregate_id = '$afterSaleNo';
DELETE FROM reconciliation_record
WHERE reference_no IN ($tradeOrderSqlList) OR reference_no = '$afterSaleNo';
DELETE FROM after_sale_history
WHERE after_sale_id IN (SELECT id FROM after_sale_order WHERE after_sale_no = '$afterSaleNo');
DELETE FROM after_sale_item
WHERE after_sale_id IN (SELECT id FROM after_sale_order WHERE after_sale_no = '$afterSaleNo');
DELETE FROM after_sale_order WHERE after_sale_no = '$afterSaleNo';
DELETE FROM order_status_history
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_address_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_discount_allocation
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_price_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_benefit_selection
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_item
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM trade_order WHERE order_no IN ($tradeOrderSqlList);
"@
        $tradeCleanupSql | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME 2>$null
    }

    if ($paymentNo) {
        $paymentCleanupSqlList = @(
            $paymentNo, $resilienceProbePaymentNo, $exceptionPaymentNo
        ) |
            Where-Object { $_ } |
            ForEach-Object { "'$_'" } |
            Join-String -Separator ','
        $refundCleanupSqlList = @($refundNo, $exceptionRefundNo) |
            Where-Object { $_ } |
            ForEach-Object { "'$_'" } |
            Join-String -Separator ','
        $paymentAggregateCleanupSqlList = @(
            $paymentNo, $resilienceProbePaymentNo, $exceptionPaymentNo,
            $refundNo, $exceptionRefundNo
        ) |
            Where-Object { $_ } |
            ForEach-Object { "'$_'" } |
            Join-String -Separator ','
        $paymentConsumedEventSqlList = ($refundRequestedEventIds |
                Where-Object { $_ } | Sort-Object -Unique |
                ForEach-Object { "'$_'" }) -join ','
        $paymentConsumedEventDelete = if ($paymentConsumedEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($paymentConsumedEventSqlList);"
        } else {
            ''
        }
        $paymentCleanupSql = @"
$paymentConsumedEventDelete
DELETE FROM outbox_event WHERE aggregate_id IN ($paymentAggregateCleanupSqlList);
DELETE FROM reconciliation_record WHERE reference_no IN ($paymentAggregateCleanupSqlList);
DELETE FROM payment_exception_refund_audit WHERE payment_no IN ($paymentCleanupSqlList);
DELETE FROM refund_dispatch_retry_audit WHERE refund_no IN ($refundCleanupSqlList);
DELETE FROM refund_callback_log WHERE refund_no IN ($refundCleanupSqlList);
DELETE FROM refund_transaction
WHERE refund_id IN (SELECT id FROM refund_order WHERE refund_no IN ($refundCleanupSqlList));
DELETE FROM refund_order WHERE refund_no IN ($refundCleanupSqlList);
DELETE FROM payment_callback_log WHERE payment_no IN ($paymentCleanupSqlList);
DELETE FROM payment_transaction
WHERE payment_id IN (SELECT id FROM payment_order WHERE payment_no IN ($paymentCleanupSqlList));
DELETE FROM payment_order WHERE payment_no IN ($paymentCleanupSqlList);
"@
        $paymentCleanupSql | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" plainjournal-mysql `
            mysql "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME 2>$null
    }

    $cleanupSql = @"
DELETE FROM refresh_token
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ('$smokeEmail', '$warehouseEmail'));
DELETE FROM user_role
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ('$smokeEmail', '$warehouseEmail'));
DELETE FROM user_address
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ('$smokeEmail', '$warehouseEmail'));
DELETE FROM user_account WHERE email IN ('$smokeEmail', '$warehouseEmail');
DELETE FROM login_record WHERE normalized_email IN ('$smokeEmail', '$warehouseEmail');
DELETE FROM login_record WHERE normalized_email = '$riskEmail';
"@
    $cleanupSql | docker exec -i -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" plainjournal-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME 2>$null

    if ($tempoStartedBySmoke -and -not $tempoWasRunningBeforeSmoke) {
        docker stop plainjournal-tempo *> $null
        if (-not $tempoExistedBeforeSmoke) {
            docker rm plainjournal-tempo *> $null
        }
    }
}
