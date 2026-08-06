[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [switch]$KeepRunning,
    [string]$StableTradeImage = 'plainjournal/trade-service:local',
    [string]$CandidateTradeImage = 'plainjournal/trade-service:local',
    [string]$TradeDatabaseName = '',
    [string]$EvidenceFileName = 'gateway-rolling-upgrade.json',
    [ValidateRange(120, 600)][int]$TimeoutSeconds = 300,
    [ValidateRange(50, 1000)][int]$ProbeIntervalMilliseconds = 100
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$envFile = Join-Path $composeDirectory '.env'
$runDirectory = Join-Path $PSScriptRoot '.run'
$evidencePath = Join-Path $runDirectory $EvidenceFileName
$networkCheck = Join-Path $PSScriptRoot 'tools\check-verification-host.ps1'
$runToken = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')
$gatewayContainer = 'plainjournal-m3-gateway'
$containerPrefix = "plainjournal-m3-trade-roll-$runToken"
$stableRelease = 'm3-stable'
$candidateRelease = 'm3-candidate'
$failedRelease = 'm3-failed'
$releaseNetwork = "plainjournal-m3-roll-net-$runToken"
$releaseNetworkPrefix = ''
$releaseNetworkCreated = $false
$stableContainers = @(
    "$containerPrefix-stable-1",
    "$containerPrefix-stable-2"
)
$candidateContainers = @(
    "$containerPrefix-candidate-1",
    "$containerPrefix-candidate-2"
)
$failedContainer = "$containerPrefix-failed"
$probePath = Join-Path $runDirectory "gateway-rolling-probes-$runToken.ndjson"
$probeStopPath = Join-Path $runDirectory "gateway-rolling-probes-$runToken.stop"
$probeStagePath = Join-Path $runDirectory "gateway-rolling-probes-$runToken.stage"
$requiredContainers = @('plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos')
$startedContainers = [System.Collections.Generic.List[string]]::new()
$experimentContainers = [System.Collections.Generic.List[string]]::new()
$nacosSnapshots = [System.Collections.Generic.List[object]]::new()
$probeJob = $null
$gatewayStarted = $false

function Read-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $values[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    return $values
}

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $baseArguments = @(
        'compose',
        '--env-file', $script:envFile,
        '--file', $script:composeFile,
        '--project-directory', $script:composeDirectory,
        '--profile', 'core',
        '--profile', 'm3-gateway'
    )
    & docker @baseArguments @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

function Start-ReleaseNetwork {
    if (-not $script:releaseNetwork.StartsWith('plainjournal-m3-roll-net-')) {
        throw "Refusing unexpected release network name: $($script:releaseNetwork)"
    }
    foreach ($thirdOctet in 240..250) {
        $subnet = "10.240.$thirdOctet.0/24"
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $id = docker network create `
                --driver bridge `
                --subnet $subnet `
                --label "plainjournal.m3.run-token=$($script:runToken)" `
                $script:releaseNetwork 2>&1
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($exitCode -eq 0 -and $id) {
            $script:releaseNetworkPrefix = "10.240.$thirdOctet"
            $script:releaseNetworkCreated = $true
            return
        }
    }
    throw 'Unable to allocate an isolated M3 release network without subnet overlap.'
}

function Stop-ReleaseNetwork {
    if (-not $script:releaseNetworkCreated) {
        return
    }
    if (-not $script:releaseNetwork.StartsWith('plainjournal-m3-roll-net-')) {
        throw "Refusing unexpected release network name: $($script:releaseNetwork)"
    }
    $exists = docker network inspect --format '{{.Id}}' $script:releaseNetwork 2>$null
    if ($LASTEXITCODE -eq 0 -and $exists) {
        docker network rm $script:releaseNetwork | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to remove release network: $($script:releaseNetwork)"
        }
    }
    $script:releaseNetworkCreated = $false
}

function Wait-ContainerHealth {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$WaitSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $state = docker inspect `
            --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' `
            $Container 2>$null
        if ($LASTEXITCODE -eq 0 -and $state -eq 'running|healthy') {
            return
        }
        if ($LASTEXITCODE -eq 0 -and $state -like 'exited*') {
            $logs = Get-DockerLogs -Container $Container
            throw "Container exited before becoming healthy: $Container`n$logs"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    $logs = Get-DockerLogs -Container $Container
    throw "Container health timed out: $Container state=$state`n$logs"
}

function Get-DockerLogs {
    param([Parameter(Mandatory)][string]$Container)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& docker logs $Container 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "Docker logs failed: $Container"
    }
    return (($output | ForEach-Object { $_.ToString() }) -join "`n")
}

function Get-NacosHeaders {
    $login = Invoke-RestMethod `
        -Method Post `
        -Uri 'http://127.0.0.1:8848/nacos/v3/auth/user/login' `
        -Body @{
            username = 'nacos'
            password = $script:settings['NACOS_ADMIN_PASSWORD']
        }
    return @{ Authorization = "Bearer $($login.accessToken)" }
}

function Get-NacosTradeInstances {
    param([Parameter(Mandatory)][hashtable]$Headers)

    try {
        $response = Invoke-RestMethod `
            -Uri ('http://127.0.0.1:18080/v3/console/ns/instance/list' +
                '?serviceName=trade-service&groupName=ECOMMERCE&pageNo=1&pageSize=20') `
            -Headers $Headers `
            -TimeoutSec 5
    }
    catch {
        $details = [string]$_.ErrorDetails.Message
        if ($details -match 'service ECOMMERCE@@trade-service is not found') {
            return @()
        }
        throw
    }
    if ($response.code -ne 0) {
        throw "Nacos instance query failed: code=$($response.code) message=$($response.message)"
    }
    return @($response.data.pageItems)
}

function Get-MetadataValue {
    param(
        [Parameter(Mandatory)]$Instance,
        [Parameter(Mandatory)][string]$Name
    )

    if ($null -eq $Instance.metadata) {
        return ''
    }
    $property = $Instance.metadata.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return ''
    }
    return [string]$property.Value
}

function Wait-NacosReleaseCounts {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][hashtable]$Expected,
        [Parameter(Mandatory)][string]$Stage,
        [int]$WaitSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $instances = @(Get-NacosTradeInstances -Headers $Headers)
        $healthy = @($instances | Where-Object { $_.healthy -and $_.enabled })
        $actual = @{}
        foreach ($instance in $healthy) {
            $release = Get-MetadataValue -Instance $instance -Name 'release-id'
            if (-not $actual.ContainsKey($release)) {
                $actual[$release] = 0
            }
            $actual[$release]++
        }
        $matches = $healthy.Count -eq (($Expected.Values | Measure-Object -Sum).Sum)
        foreach ($release in $Expected.Keys) {
            $count = if ($actual.ContainsKey($release)) { $actual[$release] } else { 0 }
            $matches = $matches -and $count -eq $Expected[$release]
        }
        foreach ($release in $actual.Keys) {
            $matches = $matches -and $Expected.ContainsKey($release)
        }
        if ($matches) {
            $snapshot = [pscustomobject]@{
                Stage = $Stage
                CapturedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
                Releases = [pscustomobject]$actual
                Instances = @($healthy | ForEach-Object {
                    [pscustomobject]@{
                        Ip = $_.ip
                        Port = $_.port
                        InstanceId = Get-MetadataValue -Instance $_ -Name 'instance-id'
                        ReleaseId = Get-MetadataValue -Instance $_ -Name 'release-id'
                    }
                })
            }
            $script:nacosSnapshots.Add($snapshot)
            return $healthy
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw ("Nacos did not converge for stage '$Stage'. Expected=" +
        ($Expected | ConvertTo-Json -Compress) + " actual=" + ($actual | ConvertTo-Json -Compress))
}

function Set-NacosInstanceEnabled {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)]$Instance,
        [Parameter(Mandatory)][bool]$Enabled
    )

    $metadata = if ($null -eq $Instance.metadata) {
        '{}'
    } else {
        $Instance.metadata | ConvertTo-Json -Compress
    }
    $clusterNameProperty = $Instance.PSObject.Properties['clusterName']
    $weightProperty = $Instance.PSObject.Properties['weight']
    $ephemeralProperty = $Instance.PSObject.Properties['ephemeral']
    $body = @{
        namespaceId = 'public'
        groupName = 'ECOMMERCE'
        serviceName = 'trade-service'
        clusterName = if ($clusterNameProperty -and $clusterNameProperty.Value) {
            $clusterNameProperty.Value
        } else {
            'DEFAULT'
        }
        ip = [string]$Instance.ip
        port = [int]$Instance.port
        weight = if ($weightProperty -and $null -ne $weightProperty.Value) {
            [double]$weightProperty.Value
        } else {
            1.0d
        }
        enabled = $Enabled.ToString().ToLowerInvariant()
        ephemeral = if ($ephemeralProperty -and $null -ne $ephemeralProperty.Value) {
            ([bool]$ephemeralProperty.Value).ToString().ToLowerInvariant()
        } else {
            'true'
        }
        metadata = $metadata
    }
    $response = Invoke-RestMethod `
        -Method Put `
        -Uri 'http://127.0.0.1:18080/v3/console/ns/instance' `
        -Headers $Headers `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body $body `
        -TimeoutSec 5
    $accepted = $response.data -eq $true -or [string]$response.data -eq 'ok'
    if ($response.code -ne 0 -or -not $accepted) {
        throw ("Nacos instance update failed: code=$($response.code) " +
            "message=$($response.message) data=$($response.data)")
    }
}

function Wait-NacosInstanceById {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$InstanceId,
        [int]$WaitSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $matches = @(
            Get-NacosTradeInstances -Headers $Headers |
                Where-Object {
                    (Get-MetadataValue -Instance $_ -Name 'instance-id') -eq $InstanceId
                }
        )
        if ($matches.Count -eq 1) {
            return $matches[0]
        }
        if ($matches.Count -gt 1) {
            throw "Nacos contains duplicate instance identities: $InstanceId"
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Nacos did not register instance identity: $InstanceId"
}

function Enable-NacosInstanceAfterHealth {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][string]$InstanceId
    )

    $instance = Wait-NacosInstanceById -Headers $Headers -InstanceId $InstanceId
    Set-NacosInstanceEnabled -Headers $Headers -Instance $instance -Enabled $true
}

function Start-TradeContainer {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][string]$ReleaseId,
        [Parameter(Mandatory)][string]$Image,
        [Parameter(Mandatory)][string]$DatabasePassword,
        [Parameter(Mandatory)][string]$ServiceIp,
        [bool]$RegisterEnabled = $true
    )

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected Trade container name: $Name"
    }
    $arguments = @(
        'create',
        '--name', $Name,
        '--label', "plainjournal.m3.run-token=$($script:runToken)",
        '--network', $script:releaseNetwork,
        '--ip', $ServiceIp,
        '--restart', 'no',
        '--init',
        '--memory', '768m',
        '--health-cmd', 'wget -q -O /dev/null http://127.0.0.1:18104/actuator/health || exit 1',
        '--health-interval', '10s',
        '--health-timeout', '3s',
        '--health-start-period', '60s',
        '--health-retries', '6',
        '--env', 'TZ=Asia/Shanghai',
        '--env', 'APP_ENV=local',
        '--env', 'TRADE_SERVICE_PORT=18104',
        '--env', "SPRING_DATASOURCE_URL=jdbc:mysql://plainjournal-mysql:3306/$($script:tradeDatabaseName)?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true",
        '--env', "SPRING_DATASOURCE_USERNAME=$($script:settings['TRADE_DB_USER'])",
        '--env', "SPRING_DATASOURCE_PASSWORD=$DatabasePassword",
        '--env', 'NACOS_HOST=plainjournal-nacos',
        '--env', 'NACOS_CLIENT_PORT=8848',
        '--env', 'NACOS_USERNAME=nacos',
        '--env', "NACOS_ADMIN_PASSWORD=$($script:settings['NACOS_ADMIN_PASSWORD'])",
        '--env', "IDENTITY_JWT_SECRET=$($script:settings['IDENTITY_JWT_SECRET'])",
        # A supplied stable image may predate relationship-specific internal
        # credentials. Map only Payment -> Trade into its legacy variable; the
        # current binary ignores this variable and keeps the hardened boundary.
        '--env', "INTERNAL_SERVICE_TOKEN=$($script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN'])",
        '--env', "TRADE_INTERNAL_SERVICE_TOKEN=$($script:settings['TRADE_INTERNAL_SERVICE_TOKEN'])",
        '--env', "PAYMENT_INTERNAL_SERVICE_TOKEN=$($script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN'])",
        '--env', "METRICS_SCRAPE_TOKEN=$($script:settings['METRICS_SCRAPE_TOKEN'])",
        '--env', "SERVICE_IP=$ServiceIp",
        '--env', "SERVICE_INSTANCE_ID=$InstanceId",
        '--env', "SERVICE_RELEASE_ID=$ReleaseId",
        '--env', ("SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=" +
            $RegisterEnabled.ToString().ToLowerInvariant()),
        '--env', 'ECOMMERCE_TRADE_OUTBOX_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_PAYMENT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_FULFILLMENT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_REFUND_RESULT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_ORDER_RECOVERY_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_RECONCILIATION_ENABLED=false',
        '--env', 'MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false',
        '--env', 'SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=20s',
        $Image,
        "--spring.cloud.nacos.discovery.metadata.instance-id=$InstanceId",
        "--spring.cloud.nacos.discovery.metadata.release-id=$ReleaseId"
    )
    $id = & docker @arguments
    if ($LASTEXITCODE -ne 0 -or -not $id) {
        throw "Failed to start Trade container: $Name"
    }
    $script:experimentContainers.Add($Name)
    docker network connect plainjournal-network $Name
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to connect Trade container to the shared middleware network: $Name"
    }
    docker start $Name | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start Trade container: $Name"
    }
    return $id
}

function Stop-TradeContainer {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected Trade container name: $Name"
    }
    $exists = docker inspect --format '{{.Id}}' $Name 2>$null
    if ($LASTEXITCODE -eq 0 -and $exists) {
        $running = (docker inspect --format '{{.State.Running}}' $Name) -eq 'true'
        if ($running) {
            docker stop --time 30 $Name | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to stop Trade container: $Name"
            }
        }
    }
}

function Remove-TradeContainer {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected Trade container name: $Name"
    }
    $exists = docker inspect --format '{{.Id}}' $Name 2>$null
    if ($LASTEXITCODE -eq 0 -and $exists) {
        docker rm -f $Name | Out-Null
    }
}

function Set-ProbeStage {
    param([Parameter(Mandatory)][string]$Stage)

    Set-Content -LiteralPath $script:probeStagePath -Value $Stage -Encoding ascii
}

function Start-GatewayProbeJob {
    Set-ProbeStage -Stage 'baseline'
    $script:probeJob = Start-Job -ScriptBlock {
        param($Url, $OutputPath, $StopPath, $StagePath, $IntervalMilliseconds)

        while (-not (Test-Path -LiteralPath $StopPath)) {
            $stage = if (Test-Path -LiteralPath $StagePath) {
                (Get-Content -LiteralPath $StagePath -Raw).Trim()
            } else {
                'unknown'
            }
            if ([string]::IsNullOrWhiteSpace($stage)) {
                $stage = 'baseline'
            }
            $row = [ordered]@{
                TimestampUtc = (Get-Date).ToUniversalTime().ToString('O')
                Stage = $stage
                HttpStatus = 0
                Code = ''
                InstanceId = ''
                ReleaseId = ''
                Error = ''
            }
            try {
                $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
                $payload = $response.Content | ConvertFrom-Json
                $row.HttpStatus = [int]$response.StatusCode
                $row.Code = [string]$payload.code
                $row.InstanceId = [string]$payload.data.instanceId
                $row.ReleaseId = [string]$payload.data.releaseId
            }
            catch {
                if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                    $row.HttpStatus = [int]$_.Exception.Response.StatusCode
                }
                $row.Error = $_.Exception.Message
            }
            [IO.File]::AppendAllText(
                $OutputPath,
                (($row | ConvertTo-Json -Compress) + [Environment]::NewLine),
                [Text.UTF8Encoding]::new($false))
            Start-Sleep -Milliseconds $IntervalMilliseconds
        }
    } -ArgumentList @(
        'http://127.0.0.1:18000/api/v1/trade/status',
        $script:probePath,
        $script:probeStopPath,
        $script:probeStagePath,
        $script:ProbeIntervalMilliseconds
    )
}

function Stop-GatewayProbeJob {
    if ($null -eq $script:probeJob) {
        return
    }
    Set-Content -LiteralPath $script:probeStopPath -Value 'stop' -Encoding ascii
    Wait-Job -Job $script:probeJob -Timeout 15 | Out-Null
    Remove-Job -Job $script:probeJob -Force
    $script:probeJob = $null
}

function Wait-GatewayInstancesObserved {
    param(
        [Parameter(Mandatory)][string[]]$ExpectedInstanceIds,
        [Parameter(Mandatory)][string[]]$AllowedReleases,
        [int]$WaitSeconds = 60
    )

    $observed = @{}
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $response = Invoke-RestMethod `
            -Uri 'http://127.0.0.1:18000/api/v1/trade/status' `
            -TimeoutSec 5
        if ($response.code -ne 'OK' -or
            $AllowedReleases -notcontains [string]$response.data.releaseId) {
            throw "Gateway returned an unexpected Trade response."
        }
        $observed[[string]$response.data.instanceId] = [string]$response.data.releaseId
        $missing = @($ExpectedInstanceIds | Where-Object { -not $observed.ContainsKey($_) })
        if ($missing.Count -eq 0) {
            return [pscustomobject]$observed
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw "Gateway did not route to expected instances: $($missing -join ', ')"
}

function Wait-GatewayInstanceDrained {
    param(
        [Parameter(Mandatory)][string]$DrainedInstanceId,
        [Parameter(Mandatory)][string[]]$ExpectedRemainingInstanceIds,
        [Parameter(Mandatory)][string[]]$AllowedReleases,
        [int]$RequiredConsecutiveRequests = 30,
        [int]$WaitSeconds = 90
    )

    $remainingObserved = @{}
    $consecutive = 0
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        try {
            $response = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18000/api/v1/trade/status' `
                -TimeoutSec 5
            $instanceId = [string]$response.data.instanceId
            $releaseId = [string]$response.data.releaseId
            if ($response.code -ne 'OK' -or $AllowedReleases -notcontains $releaseId) {
                throw 'Gateway returned an unexpected Trade response during drain.'
            }
            if ($instanceId -eq $DrainedInstanceId) {
                $consecutive = 0
                $remainingObserved.Clear()
            } else {
                $remainingObserved[$instanceId] = $releaseId
                $consecutive++
            }
            $missing = @(
                $ExpectedRemainingInstanceIds |
                    Where-Object { -not $remainingObserved.ContainsKey($_) }
            )
            if ($consecutive -ge $RequiredConsecutiveRequests -and $missing.Count -eq 0) {
                return [pscustomobject]$remainingObserved
            }
        }
        catch {
            $consecutive = 0
            $remainingObserved.Clear()
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    throw ("Gateway did not drain instance '$DrainedInstanceId': " +
        "consecutive=$consecutive missing=$($missing -join ', ')")
}

function Wait-FailedContainerExit {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$WaitSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $state = docker inspect --format '{{.State.Status}}|{{.State.ExitCode}}' $Container
        $parts = $state -split '\|'
        if ($parts[0] -eq 'exited') {
            if ([int]$parts[1] -eq 0) {
                throw "Failed candidate exited successfully instead of being rejected: $Container"
            }
            return [int]$parts[1]
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Failed candidate did not exit within $WaitSeconds seconds: $Container"
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing Docker environment file: $envFile"
}
if (-not (Test-Path -LiteralPath $runDirectory)) {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
}
foreach ($path in @($probePath, $probeStopPath, $probeStagePath)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

if (-not $SkipNetworkPreflight) {
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw 'Host preflight failed.'
    }
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is not ready.'
}

$settings = Read-DotEnv -Path $envFile
foreach ($name in @(
    'TRADE_DB_NAME',
    'TRADE_DB_USER',
    'TRADE_DB_PASSWORD',
    'NACOS_ADMIN_PASSWORD',
    'REDIS_PASSWORD',
    'IDENTITY_JWT_SECRET',
    'TRADE_INTERNAL_SERVICE_TOKEN',
    'PAYMENT_INTERNAL_SERVICE_TOKEN',
    'METRICS_SCRAPE_TOKEN'
)) {
    if (-not $settings.ContainsKey($name) -or -not $settings[$name]) {
        throw "Missing required value in deploy/docker/.env: $name"
    }
}
$tradeDatabaseName = if ([string]::IsNullOrWhiteSpace($TradeDatabaseName)) {
    $settings['TRADE_DB_NAME']
} else {
    $TradeDatabaseName
}
if ($tradeDatabaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw "Trade database name contains unsupported characters: $tradeDatabaseName"
}
foreach ($image in @($StableTradeImage, $CandidateTradeImage)) {
    docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Missing Trade image: $image"
    }
}

$existingGateway = docker inspect --format '{{.Id}}' $gatewayContainer 2>$null
if ($LASTEXITCODE -eq 0 -and $existingGateway) {
    throw "Gateway experiment container already exists: $gatewayContainer"
}
$existingTrade = @(docker ps -a --filter 'name=plainjournal-m3-trade-roll-' --format '{{.Names}}')
if ($existingTrade.Count -gt 0) {
    throw "Rolling-upgrade Trade experiment containers already exist: $($existingTrade -join ', ')"
}

try {
    foreach ($container in $requiredContainers) {
        $running = (docker inspect --format '{{.State.Running}}' $container 2>$null) -eq 'true'
        if (-not $running) {
            docker start $container | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to start required container: $container"
            }
            $startedContainers.Add($container)
        }
    }

    if (-not $SkipBuild) {
        & mvn -pl ecommerce-gateway,services/trade-service -am package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw 'Gateway/Trade package build failed.'
        }
        docker build `
            --file ecommerce-gateway/Dockerfile `
            --tag plainjournal/ecommerce-gateway:local `
            .
        if ($LASTEXITCODE -ne 0) {
            throw 'Gateway image build failed.'
        }
        docker build `
            --file services/trade-service/Dockerfile `
            --tag plainjournal/trade-service:local `
            .
        if ($LASTEXITCODE -ne 0) {
            throw 'Trade image build failed.'
        }
    }

    $imageUser = docker image inspect plainjournal/ecommerce-gateway:local `
        --format '{{.Config.User}}|{{.Config.StopSignal}}'
    if ($imageUser -ne '10001:10001|SIGTERM') {
        throw "Gateway image runtime contract is invalid: $imageUser"
    }

    $nacosHeaders = Get-NacosHeaders
    $preexistingInstances = @(Get-NacosTradeInstances -Headers $nacosHeaders)
    if ($preexistingInstances.Count -gt 0) {
        throw 'trade-service already has Nacos instances; rolling verification requires an isolated service set.'
    }
    Start-ReleaseNetwork

    [void](Start-TradeContainer `
        -Name $stableContainers[0] `
        -InstanceId 'trade-stable-1' `
        -ReleaseId $stableRelease `
        -Image $StableTradeImage `
        -DatabasePassword $settings['TRADE_DB_PASSWORD'] `
        -ServiceIp "$releaseNetworkPrefix.11")
    [void](Start-TradeContainer `
        -Name $stableContainers[1] `
        -InstanceId 'trade-stable-2' `
        -ReleaseId $stableRelease `
        -Image $StableTradeImage `
        -DatabasePassword $settings['TRADE_DB_PASSWORD'] `
        -ServiceIp "$releaseNetworkPrefix.12")
    Wait-ContainerHealth -Container $stableContainers[0]
    Wait-ContainerHealth -Container $stableContainers[1]
    Enable-NacosInstanceAfterHealth `
        -Headers $nacosHeaders `
        -InstanceId 'trade-stable-1'
    Enable-NacosInstanceAfterHealth `
        -Headers $nacosHeaders `
        -InstanceId 'trade-stable-2'
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $stableRelease = 2 } `
        -Stage 'stable-two')

    [void](Invoke-Compose -Arguments @(
        'up', '-d', '--no-deps', '--force-recreate', 'ecommerce-gateway'
    ))
    $gatewayStarted = $true
    Wait-ContainerHealth -Container $gatewayContainer
    docker network connect $releaseNetwork $gatewayContainer
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to connect Gateway to the isolated release network.'
    }
    [void](Wait-GatewayInstancesObserved `
        -ExpectedInstanceIds @('trade-stable-1', 'trade-stable-2') `
        -AllowedReleases @($stableRelease))
    Start-GatewayProbeJob
    Start-Sleep -Seconds 2

    Set-ProbeStage -Stage 'candidate-one-added'
    [void](Start-TradeContainer `
        -Name $candidateContainers[0] `
        -InstanceId 'trade-candidate-1' `
        -ReleaseId $candidateRelease `
        -Image $CandidateTradeImage `
        -DatabasePassword $settings['TRADE_DB_PASSWORD'] `
        -ServiceIp "$releaseNetworkPrefix.13")
    Wait-ContainerHealth -Container $candidateContainers[0]
    Enable-NacosInstanceAfterHealth `
        -Headers $nacosHeaders `
        -InstanceId 'trade-candidate-1'
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $stableRelease = 2; $candidateRelease = 1 } `
        -Stage 'candidate-one-added')
    [void](Wait-GatewayInstancesObserved `
        -ExpectedInstanceIds @('trade-stable-1', 'trade-stable-2', 'trade-candidate-1') `
        -AllowedReleases @($stableRelease, $candidateRelease))

    Set-ProbeStage -Stage 'stable-one-draining'
    $stableOneInstance = @(
        Get-NacosTradeInstances -Headers $nacosHeaders |
            Where-Object {
                (Get-MetadataValue -Instance $_ -Name 'instance-id') -eq 'trade-stable-1'
            }
    )
    if ($stableOneInstance.Count -ne 1) {
        throw "Expected one Nacos instance for trade-stable-1, found $($stableOneInstance.Count)."
    }
    Set-NacosInstanceEnabled `
        -Headers $nacosHeaders `
        -Instance $stableOneInstance[0] `
        -Enabled $false
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $stableRelease = 1; $candidateRelease = 1 } `
        -Stage 'stable-one-draining')
    [void](Wait-GatewayInstanceDrained `
        -DrainedInstanceId 'trade-stable-1' `
        -ExpectedRemainingInstanceIds @('trade-stable-2', 'trade-candidate-1') `
        -AllowedReleases @($stableRelease, $candidateRelease))
    Set-ProbeStage -Stage 'stable-one-removed'
    Stop-TradeContainer -Name $stableContainers[0]

    Set-ProbeStage -Stage 'candidate-two-added'
    [void](Start-TradeContainer `
        -Name $candidateContainers[1] `
        -InstanceId 'trade-candidate-2' `
        -ReleaseId $candidateRelease `
        -Image $CandidateTradeImage `
        -DatabasePassword $settings['TRADE_DB_PASSWORD'] `
        -ServiceIp "$releaseNetworkPrefix.14")
    Wait-ContainerHealth -Container $candidateContainers[1]
    Enable-NacosInstanceAfterHealth `
        -Headers $nacosHeaders `
        -InstanceId 'trade-candidate-2'
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $stableRelease = 1; $candidateRelease = 2 } `
        -Stage 'candidate-two-added')
    [void](Wait-GatewayInstancesObserved `
        -ExpectedInstanceIds @('trade-stable-2', 'trade-candidate-1', 'trade-candidate-2') `
        -AllowedReleases @($stableRelease, $candidateRelease))

    Set-ProbeStage -Stage 'stable-two-draining'
    $stableTwoInstance = @(
        Get-NacosTradeInstances -Headers $nacosHeaders |
            Where-Object {
                (Get-MetadataValue -Instance $_ -Name 'instance-id') -eq 'trade-stable-2'
            }
    )
    if ($stableTwoInstance.Count -ne 1) {
        throw "Expected one Nacos instance for trade-stable-2, found $($stableTwoInstance.Count)."
    }
    Set-NacosInstanceEnabled `
        -Headers $nacosHeaders `
        -Instance $stableTwoInstance[0] `
        -Enabled $false
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $candidateRelease = 2 } `
        -Stage 'stable-two-draining')
    [void](Wait-GatewayInstanceDrained `
        -DrainedInstanceId 'trade-stable-2' `
        -ExpectedRemainingInstanceIds @('trade-candidate-1', 'trade-candidate-2') `
        -AllowedReleases @($candidateRelease))
    Set-ProbeStage -Stage 'candidate-rollout-complete'
    Stop-TradeContainer -Name $stableContainers[1]
    Start-Sleep -Seconds 2

    Set-ProbeStage -Stage 'failed-candidate-rollback'
    $invalidPassword = 'invalid-' + [Guid]::NewGuid().ToString('N')
    [void](Start-TradeContainer `
        -Name $failedContainer `
        -InstanceId 'trade-failed-1' `
        -ReleaseId $failedRelease `
        -Image $CandidateTradeImage `
        -DatabasePassword $invalidPassword `
        -ServiceIp "$releaseNetworkPrefix.15" `
        -RegisterEnabled $false)
    $failedExitCode = Wait-FailedContainerExit `
        -Container $failedContainer `
        -WaitSeconds ([Math]::Min(120, $TimeoutSeconds))
    [void](Wait-NacosReleaseCounts `
        -Headers $nacosHeaders `
        -Expected @{ $candidateRelease = 2 } `
        -Stage 'failed-candidate-rollback')
    [void](Wait-GatewayInstancesObserved `
        -ExpectedInstanceIds @('trade-candidate-1', 'trade-candidate-2') `
        -AllowedReleases @($candidateRelease))
    Remove-TradeContainer -Name $failedContainer
    Start-Sleep -Seconds 2

    Stop-GatewayProbeJob
    $probeRows = @(
        Get-Content -LiteralPath $probePath |
            Where-Object { $_ } |
            ForEach-Object { $_ | ConvertFrom-Json }
    )
    $probeFailures = @($probeRows | Where-Object {
        $_.HttpStatus -ne 200 -or $_.Code -ne 'OK' -or $_.Error
    })
    $unexpectedReleaseRows = @($probeRows | Where-Object {
        $_.ReleaseId -and $_.ReleaseId -notin @($stableRelease, $candidateRelease)
    })
    if ($probeRows.Count -lt 20 -or
        $probeFailures.Count -gt 0 -or
        $unexpectedReleaseRows.Count -gt 0) {
        throw ("Gateway continuity failed: requests=$($probeRows.Count) " +
            "failures=$($probeFailures.Count) unexpectedReleases=$($unexpectedReleaseRows.Count)")
    }

    $stageSummaries = @(
        $probeRows |
            Group-Object Stage |
            ForEach-Object {
                [pscustomobject]@{
                    Stage = $_.Name
                    Requests = $_.Count
                    Failures = @($_.Group | Where-Object {
                        $_.HttpStatus -ne 200 -or $_.Code -ne 'OK' -or $_.Error
                    }).Count
                    Releases = @($_.Group.ReleaseId | Where-Object { $_ } | Sort-Object -Unique)
                    Instances = @($_.Group.InstanceId | Where-Object { $_ } | Sort-Object -Unique)
                }
            }
    )
    $evidence = [pscustomobject]@{
        VerifiedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
        GitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
        Environment = [pscustomobject]@{
            GatewayImage = 'plainjournal/ecommerce-gateway:local'
            StableTradeImage = $StableTradeImage
            CandidateTradeImage = $CandidateTradeImage
            StableTradeImageId = (docker image inspect $StableTradeImage --format '{{.Id}}')
            CandidateTradeImageId = (docker image inspect $CandidateTradeImage --format '{{.Id}}')
            TradeDatabaseName = $tradeDatabaseName
            Nacos = 'nacos/nacos-server:v3.2.2'
            MySql = 'mysql:8.4.10'
            Redis = 'redis:7.4.9-alpine'
            ReleaseNetwork = "$releaseNetworkPrefix.0/24"
        }
        Releases = [pscustomobject]@{
            Stable = $stableRelease
            Candidate = $candidateRelease
            Failed = $failedRelease
            RollbackTarget = $candidateRelease
        }
        Gateway = [pscustomobject]@{
            RuntimeUserAndStopSignal = $imageUser
            RequestCount = $probeRows.Count
            FailureCount = $probeFailures.Count
            UnexpectedReleaseCount = $unexpectedReleaseRows.Count
            Stages = $stageSummaries
        }
        Nacos = [pscustomobject]@{
            Snapshots = $nacosSnapshots
        }
        FailedCandidate = [pscustomobject]@{
            ExitCode = $failedExitCode
            InvalidDatabaseCredentialsInjected = $true
            StartupRejected = $failedExitCode -ne 0
            RegisteredInNacos = $false
            GatewayContinuityPreserved = $probeFailures.Count -eq 0
        }
        LogsUsedAsProof = $false
    }
    $evidence | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8

    Write-Host 'Gateway/Nacos rolling upgrade and failed-candidate rollback verification passed.'
    $stageSummaries | Format-Table -AutoSize
    Write-Host "Evidence: $evidencePath"
}
finally {
    try {
        Stop-GatewayProbeJob
    }
    catch {
        Write-Warning "Gateway probe cleanup failed: $($_.Exception.Message)"
    }
    if (-not $KeepRunning) {
        foreach ($container in @($experimentContainers)) {
            try {
                Stop-TradeContainer -Name $container
                Remove-TradeContainer -Name $container
            }
            catch {
                Write-Warning "Trade container cleanup failed for $container`: $($_.Exception.Message)"
            }
        }
        if ($gatewayStarted) {
            try {
                [void](Invoke-Compose -Arguments @('rm', '-sf', 'ecommerce-gateway'))
            }
            catch {
                Write-Warning "Gateway container cleanup failed: $($_.Exception.Message)"
            }
        }
        try {
            Stop-ReleaseNetwork
        }
        catch {
            Write-Warning "Release network cleanup failed: $($_.Exception.Message)"
        }
        foreach ($container in @($startedContainers)) {
            docker stop $container | Out-Null
        }
    }
    foreach ($path in @($probeStopPath, $probeStagePath)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}
