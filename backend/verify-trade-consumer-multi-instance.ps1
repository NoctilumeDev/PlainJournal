[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [switch]$KeepRunning,
    [ValidateRange(100, 5000)][int]$EventCount = 1000,
    [ValidateRange(120, 600)][int]$TimeoutSeconds = 300,
    [bool]$UseIsolatedRocketMq = $true,
    [ValidatePattern('^$|^[0-9]+\.[0-9]+\.[0-9]+(?:[-A-Za-z0-9.]*)?$')]
    [string]$RocketMqImageTag = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$envFile = Join-Path $composeDirectory '.env'
$tradeJar = Join-Path $PSScriptRoot 'services\trade-service\target\trade-service-1.0.2-SNAPSHOT.jar'
$runDirectory = Join-Path $PSScriptRoot '.run'
$evidencePath = Join-Path $runDirectory 'trade-consumer-multi-instance.json'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$runToken = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')
$topic = "plainjournal-m3-payment-consumer-$runToken"
$flashSaleTopic = "plainjournal-m3-flash-sale-outbox-$runToken"
$brokerConsumerGroup = "trade-payment-m3-$runToken"
$orderPrefix = "M3C-$runToken-"
$inputAggregatePrefix = "M3PaymentInput:${runToken}:"
$rocketMqNameServerContainer = if ($UseIsolatedRocketMq) {
    "plainjournal-m3-rmq-namesrv-$runToken"
} else {
    'plainjournal-rocketmq-namesrv'
}
$rocketMqBrokerContainer = if ($UseIsolatedRocketMq) {
    "plainjournal-m3-rmq-broker-$runToken"
} else {
    'plainjournal-rocketmq-broker'
}
$rocketMqProxyContainer = if ($UseIsolatedRocketMq) {
    "plainjournal-m3-rmq-proxy-$runToken"
} else {
    'plainjournal-rocketmq-proxy'
}
$rocketMqNameServerAddress = "${rocketMqNameServerContainer}:9876"
$rocketMqEndpoints = "${rocketMqBrokerContainer}:18082"
$rocketMqStoreVolume = "plainjournal-m3-rmq-store-$runToken"
$isolatedRocketMqStarted = $false
$isolatedRocketMqVolumeCreated = $false
$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-nacos'
)
if (-not $UseIsolatedRocketMq) {
    $requiredContainers += @(
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy'
    )
}
$startedContainers = [System.Collections.Generic.List[string]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$cleanupFailures = [System.Collections.Generic.List[string]]::new()
$processEnvironment = @{
    TRADE_CONTAINER_ROCKETMQ_ENDPOINTS = $rocketMqEndpoints
    TRADE_CONTAINER_OUTBOX_TOPIC = $topic
    TRADE_CONTAINER_FLASH_SALE_OUTBOX_TOPIC = $flashSaleTopic
    TRADE_CONTAINER_PAYMENT_CONSUMER_TOPIC = $topic
    TRADE_CONTAINER_PAYMENT_CONSUMER_GROUP = $brokerConsumerGroup
    TRADE_CONTAINER_OUTBOX_ENABLED = 'true'
    TRADE_CONTAINER_PAYMENT_CONSUMER_ENABLED = 'true'
    TRADE_CONTAINER_PAYMENT_CONSUMER_FIXED_DELAY = '200'
    TRADE_CONTAINER_PAYMENT_CONSUMER_INVISIBLE_DURATION = '30s'
    TRADE_CONTAINER_OUTBOX_LEASE_DURATION = '10s'
    TRADE_CONTAINER_FAULT_INJECTION_ENABLED = 'false'
    TRADE_CONTAINER_FAULT_INJECTION_POINT = ''
    TRADE_CONTAINER_FAULT_INJECTION_TARGET_EVENT_ID = ''
}
$previousEnvironment = @{}

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
        '--profile', 'm3-trade'
    )
    & docker @baseArguments @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

function Start-IsolatedRocketMq {
    $image = "apache/rocketmq:$($script:effectiveRocketMqImageTag)"
    $brokerConfig = Join-Path $script:composeDirectory 'rocketmq\broker.conf'
    $proxyConfig = Join-Path $script:composeDirectory 'rocketmq\rmq-proxy.json'

    if (-not (Test-Path -LiteralPath $brokerConfig) -or
        -not (Test-Path -LiteralPath $proxyConfig)) {
        throw 'Isolated RocketMQ configuration files are missing.'
    }
    if (-not $script:rocketMqStoreVolume.StartsWith('plainjournal-m3-rmq-store-')) {
        throw "Refusing unexpected RocketMQ volume name: $($script:rocketMqStoreVolume)"
    }

    docker volume create $script:rocketMqStoreVolume | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to create isolated RocketMQ store volume.'
    }
    $script:isolatedRocketMqVolumeCreated = $true
    docker run --rm `
        --user 0 `
        --mount "type=volume,source=$($script:rocketMqStoreVolume),target=/home/rocketmq/store" `
        $image sh -lc 'chown -R 3000:3000 /home/rocketmq/store' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to initialize isolated RocketMQ store volume ownership.'
    }

    docker run -d `
        --name $script:rocketMqNameServerContainer `
        --network plainjournal-network `
        --restart no `
        --memory 384m `
        --env TZ=Asia/Shanghai `
        --env 'JAVA_OPT_EXT=-Xms128m -Xmx256m -Xmn64m' `
        $image sh mqnamesrv | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to start isolated RocketMQ NameServer.'
    }

    docker run -d `
        --name $script:rocketMqBrokerContainer `
        --network plainjournal-network `
        --restart no `
        --memory 1g `
        --env TZ=Asia/Shanghai `
        --env "NAMESRV_ADDR=$($script:rocketMqNameServerAddress)" `
        --env 'JAVA_OPT_EXT=-Xms512m -Xmx768m -Xmn256m' `
        --mount "type=bind,source=$brokerConfig,target=/tmp/plainjournal-broker.conf,readonly" `
        --mount "type=volume,source=$($script:rocketMqStoreVolume),target=/home/rocketmq/store" `
        $image sh mqbroker -c /tmp/plainjournal-broker.conf | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to start isolated RocketMQ Broker.'
    }

    $proxyCommand = @"
until sh mqadmin clusterList -n $($script:rocketMqNameServerAddress) 2>/dev/null |
  grep -q EcommerceCluster; do
  sleep 1
done
exec sh mqproxy \
  -n $($script:rocketMqNameServerAddress) \
  -pc /tmp/plainjournal-rmq-proxy.json \
  -pm cluster
"@
    docker run -d `
        --name $script:rocketMqProxyContainer `
        --network "container:$($script:rocketMqBrokerContainer)" `
        --restart no `
        --memory 512m `
        --env TZ=Asia/Shanghai `
        --env 'JAVA_OPT_EXT=-Xms128m -Xmx384m -Xmn128m' `
        --mount "type=bind,source=$proxyConfig,target=/tmp/plainjournal-rmq-proxy.json,readonly" `
        $image sh -lc $proxyCommand | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to start isolated RocketMQ Proxy.'
    }
    $script:isolatedRocketMqStarted = $true
}

function Stop-IsolatedRocketMq {
    if (-not $script:UseIsolatedRocketMq) {
        return
    }

    foreach ($container in @(
        $script:rocketMqProxyContainer,
        $script:rocketMqBrokerContainer,
        $script:rocketMqNameServerContainer
    )) {
        $exists = docker inspect --format '{{.Id}}' $container 2>$null
        if ($LASTEXITCODE -eq 0 -and $exists) {
            docker rm -f $container | Out-Null
        }
    }
    $script:isolatedRocketMqStarted = $false

    if ($script:isolatedRocketMqVolumeCreated) {
        if (-not $script:rocketMqStoreVolume.StartsWith('plainjournal-m3-rmq-store-')) {
            throw "Refusing unexpected RocketMQ volume name: $($script:rocketMqStoreVolume)"
        }
        docker volume rm $script:rocketMqStoreVolume | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to remove isolated RocketMQ volume: $($script:rocketMqStoreVolume)"
        }
        $script:isolatedRocketMqVolumeCreated = $false
    }
}

function Remove-SharedRocketMqArtifacts {
    if ($script:UseIsolatedRocketMq) {
        return
    }

    $residualTopics = @()
    $residualOffsets = @()
    $groupPresent = $false
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $groupOutput = @(docker exec $script:rocketMqBrokerContainer sh mqadmin deleteSubGroup `
                -n $script:rocketMqNameServerAddress `
                -c EcommerceCluster `
                -g $script:brokerConsumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($groupOutput -join "`n") -notmatch 'success') {
            throw (
                "Shared RocketMQ consumer-group deletion attempt $attempt failed: " +
                ($groupOutput -join "`n"))
        }

        $topicOutput = @(docker exec $script:rocketMqBrokerContainer sh mqadmin topicList `
                -n $script:rocketMqNameServerAddress 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to list shared RocketMQ topics: $($topicOutput -join "`n")"
        }
        $artifactTopics = @($topicOutput | ForEach-Object { $_.Trim() } |
            Where-Object {
                ($_ -eq $script:topic) -or
                ($_ -eq $script:flashSaleTopic) -or
                (($_.StartsWith('%RETRY%') -or $_.StartsWith('%DLQ%')) -and
                    ($_.Contains($script:topic) -or
                        $_.Contains($script:brokerConsumerGroup)))
            } | Sort-Object -Unique)
        foreach ($topicName in $artifactTopics) {
            $deleteOutput = @(
                docker exec $script:rocketMqBrokerContainer sh mqadmin deleteTopic `
                    -n $script:rocketMqNameServerAddress `
                    -c EcommerceCluster `
                    -t $topicName 2>&1
            )
            if ($LASTEXITCODE -ne 0 -or ($deleteOutput -join "`n") -notmatch 'success') {
                throw (
                    "Shared RocketMQ topic deletion attempt $attempt failed for " +
                    "${topicName}: $($deleteOutput -join "`n")")
            }
        }

        Start-Sleep -Seconds 3
        $remainingTopics = @(docker exec $script:rocketMqBrokerContainer sh mqadmin topicList `
                -n $script:rocketMqNameServerAddress 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to verify shared RocketMQ topics: $($remainingTopics -join "`n")"
        }
        $residualTopics = @($remainingTopics | ForEach-Object { $_.Trim() } |
            Where-Object {
                ($_ -eq $script:topic) -or
                ($_ -eq $script:flashSaleTopic) -or
                (($_.StartsWith('%RETRY%') -or $_.StartsWith('%DLQ%')) -and
                    ($_.Contains($script:topic) -or
                        $_.Contains($script:brokerConsumerGroup)))
            })

        $groupConfig = @(
            docker exec $script:rocketMqBrokerContainer sh mqadmin getConsumerConfig `
                -n $script:rocketMqNameServerAddress `
                -g $script:brokerConsumerGroup 2>&1
        )
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to verify shared RocketMQ consumer group: $($groupConfig -join "`n")"
        }
        $groupPresent = ($groupConfig -join "`n") -match (
            '(?m)^\s*groupName\s*=\s*' +
            [regex]::Escape($script:brokerConsumerGroup) +
            '\s*$')

        $offsetOutput = @(
            docker exec $script:rocketMqBrokerContainer sh -lc `
                'cat /home/rocketmq/store/config/consumerOffset.json' 2>&1
        )
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to inspect shared RocketMQ offsets: $($offsetOutput -join "`n")"
        }
        $offsetMetadata = ($offsetOutput -join "`n") | ConvertFrom-Json -AsHashtable
        $groupSuffix = "@$($script:brokerConsumerGroup)"
        $residualOffsets = @($offsetMetadata.offsetTable.Keys | Where-Object {
                $_.EndsWith($groupSuffix, [StringComparison]::Ordinal)
            })
        if ($residualTopics.Count -eq 0 -and
            $residualOffsets.Count -eq 0 -and
            -not $groupPresent) {
            return
        }
    }

    throw (
        'Shared RocketMQ cleanup did not converge after five attempts: ' +
        "topics=$($residualTopics -join ',') " +
        "offsets=$($residualOffsets -join ',') groupPresent=$groupPresent")
}

function Wait-RocketMq {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $brokerRunning =
            (docker inspect --format '{{.State.Running}}' $script:rocketMqBrokerContainer 2>$null) -eq
            'true'
        $proxyRunning =
            (docker inspect --format '{{.State.Running}}' $script:rocketMqProxyContainer 2>$null) -eq
            'true'
        if ($brokerRunning -and $proxyRunning) {
            $cluster = docker exec $script:rocketMqBrokerContainer sh -lc (
                "sh mqadmin clusterList -n $($script:rocketMqNameServerAddress) 2>/dev/null")
            $clusterExitCode = $LASTEXITCODE
            docker exec $script:rocketMqBrokerContainer sh -lc (
                "grep -qi ':46A2 .* 0A ' /proc/net/tcp /proc/net/tcp6") | Out-Null
            $proxyExitCode = $LASTEXITCODE
            if ($clusterExitCode -eq 0 -and
                $proxyExitCode -eq 0 -and
                ($cluster -join "`n") -match 'EcommerceCluster' -and
                $proxyRunning) {
                return
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'RocketMQ Broker/Proxy readiness timed out.'
}

function Get-DockerLogs {
    param([Parameter(Mandatory)][string]$ContainerId)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell surfaces a native process's stderr as ErrorRecord objects.
        # Docker logs legitimately writes container stderr there, so capture it without
        # allowing the script-wide Stop preference to turn normal JVM output into failure.
        $ErrorActionPreference = 'Continue'
        $output = @(& docker logs $ContainerId 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Docker logs command failed for container $ContainerId."
    }
    return (($output | ForEach-Object { $_.ToString() }) -join "`n")
}

function Wait-TcpPort {
    param(
        [Parameter(Mandatory)][string]$HostName,
        [Parameter(Mandatory)][int]$Port,
        [int]$WaitSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync($HostName, $Port)
            if ($connect.Wait(1000) -and $client.Connected) {
                return
            }
        }
        catch {
        }
        finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for ${HostName}:$Port"
}

function Wait-Middleware {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $mysqlHealth = docker inspect --format '{{.State.Health.Status}}' plainjournal-mysql 2>$null
        $nacosReady = $false
        try {
            $response = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/v3/console/health/readiness' `
                -TimeoutSec 3
            $nacosReady = $response.code -eq 0 -and $response.data -eq 'ok'
        }
        catch {
        }
        if ($mysqlHealth -eq 'healthy' -and $nacosReady) {
            if ($script:UseIsolatedRocketMq) {
                Wait-RocketMq
            } else {
                Wait-TcpPort -HostName '127.0.0.1' -Port 18082 -WaitSeconds 30
            }
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Middleware readiness timed out: mysql=$mysqlHealth nacos=$nacosReady"
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

    $response = Invoke-RestMethod `
        -Uri ('http://127.0.0.1:18080/v3/console/ns/instance/list' +
            '?serviceName=trade-service&groupName=ECOMMERCE&pageNo=1&pageSize=20') `
        -Headers $Headers `
        -TimeoutSec 5
    if ($response.code -ne 0) {
        throw "Nacos instance query failed: code=$($response.code) message=$($response.message)"
    }
    return @($response.data.pageItems)
}

function Wait-NacosInstanceCount {
    param(
        [Parameter(Mandatory)][hashtable]$Headers,
        [Parameter(Mandatory)][int]$ExpectedCount,
        [int]$WaitSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $instances = @(Get-NacosTradeInstances -Headers $Headers)
        $healthy = @($instances | Where-Object { $_.healthy -and $_.enabled })
        if ($instances.Count -eq $ExpectedCount -and $healthy.Count -eq $ExpectedCount) {
            return $instances
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Nacos did not converge to $ExpectedCount healthy Trade instances."
}

function Get-TradeContainerIds {
    $ids = @(Invoke-Compose -Arguments @('ps', '-q', 'trade-service'))
    return @($ids | Where-Object { $_ })
}

function Get-AllTradeContainerIds {
    $ids = @(Invoke-Compose -Arguments @('ps', '-aq', 'trade-service'))
    return @($ids | Where-Object { $_ })
}

function Remove-TradeContainers {
    $ids = @(Get-AllTradeContainerIds)
    if ($ids.Count -gt 0) {
        [void](Invoke-Compose -Arguments @('rm', '-sf', 'trade-service'))
    }
}

function Set-ExperimentMode {
    param(
        [Parameter(Mandatory)][bool]$OutboxEnabled,
        [Parameter(Mandatory)][bool]$PaymentConsumerEnabled,
        [Parameter(Mandatory)][bool]$FaultEnabled,
        [string]$FaultPoint = '',
        [string]$TargetEventId = ''
    )

    $values = @{
        TRADE_CONTAINER_OUTBOX_ENABLED = $OutboxEnabled.ToString().ToLowerInvariant()
        TRADE_CONTAINER_PAYMENT_CONSUMER_ENABLED =
            $PaymentConsumerEnabled.ToString().ToLowerInvariant()
        TRADE_CONTAINER_FAULT_INJECTION_ENABLED =
            $FaultEnabled.ToString().ToLowerInvariant()
        TRADE_CONTAINER_FAULT_INJECTION_POINT = $FaultPoint
        TRADE_CONTAINER_FAULT_INJECTION_TARGET_EVENT_ID = $TargetEventId
    }
    foreach ($name in $values.Keys) {
        $script:processEnvironment[$name] = $values[$name]
        [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
    }
}

function Start-SingleTradeContainer {
    [void](Invoke-Compose -Arguments @(
        'up', '-d', '--no-deps', '--force-recreate', '--scale', 'trade-service=1', 'trade-service'
    ))
    $ids = @(Get-AllTradeContainerIds)
    if ($ids.Count -ne 1) {
        throw "Expected one Trade container, found $($ids.Count)."
    }
    return $ids[0]
}

function Wait-ContainerExit {
    param(
        [Parameter(Mandatory)][string]$ContainerId,
        [Parameter(Mandatory)][int]$ExpectedExitCode,
        [int]$WaitSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $state = docker inspect --format '{{.State.Status}}|{{.State.ExitCode}}' $ContainerId
        $parts = $state -split '\|'
        if ($parts[0] -eq 'exited') {
            if ([int]$parts[1] -ne $ExpectedExitCode) {
                docker logs --tail 160 $ContainerId
                throw "Trade container exited with $($parts[1]), expected $ExpectedExitCode."
            }
            return $state
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    docker logs --tail 160 $ContainerId
    throw "Trade container did not exit within $WaitSeconds seconds."
}

function Wait-TradeContainers {
    param([Parameter(Mandatory)][int]$ExpectedCount)

    $deadline = (Get-Date).AddSeconds(120)
    do {
        $ids = @(Get-TradeContainerIds)
        $healthy = 0
        foreach ($id in $ids) {
            $state = docker inspect `
                --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' `
                $id
            if ($state -eq 'running|healthy') {
                $healthy++
            }
        }
        if ($ids.Count -eq $ExpectedCount -and $healthy -eq $ExpectedCount) {
            return $ids
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    foreach ($id in $ids) {
        docker logs --tail 120 $id
    }
    throw "Only $healthy/$ExpectedCount Trade containers became healthy."
}

function Get-ContainerName {
    param([Parameter(Mandatory)][string]$ContainerId)

    return (docker inspect --format '{{.Name}}' $ContainerId).TrimStart('/')
}

function Invoke-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = docker exec `
        -e "MYSQL_PWD=$($script:settings['TRADE_DB_PASSWORD'])" `
        plainjournal-mysql mysql `
        --skip-column-names --batch `
        "--user=$($script:settings['TRADE_DB_USER'])" `
        "--database=$($script:settings['TRADE_DB_NAME'])" `
        "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade MySQL command failed.'
    }
    return @($output)
}

function Send-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $Sql | docker exec `
        -i `
        -e "MYSQL_PWD=$($script:settings['TRADE_DB_PASSWORD'])" `
        plainjournal-mysql mysql `
        --skip-column-names --batch `
        "--user=$($script:settings['TRADE_DB_USER'])" `
        "--database=$($script:settings['TRADE_DB_NAME'])"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade MySQL input script failed.'
    }
}

function Get-MySqlScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-TradeMySql -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one MySQL scalar row, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function Wait-MySqlScalar {
    param(
        [Parameter(Mandatory)][string]$Sql,
        [Parameter(Mandatory)][string]$Expected,
        [int]$WaitSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        $actual = Get-MySqlScalar -Sql $Sql
        if ($actual -eq $Expected) {
            return $actual
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "MySQL state did not converge. Expected '$Expected', observed '$actual'."
}

function New-PaymentScenarioSql {
    param(
        [Parameter(Mandatory)][int]$Scale,
        [Parameter(Mandatory)][string]$ScenarioOrderPrefix,
        [Parameter(Mandatory)][string]$InputAggregateType,
        [Parameter(Mandatory)][int]$Count
    )

    $orderRows = [Text.StringBuilder]::new()
    $addressRows = [Text.StringBuilder]::new()
    $eventRows = [Text.StringBuilder]::new()
    $baseId = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 100000) +
        ($Scale * 10000)
    for ($index = 1; $index -le $Count; $index++) {
        if ($index -gt 1) {
            [void]$orderRows.AppendLine(',')
            [void]$addressRows.AppendLine(',')
            [void]$eventRows.AppendLine(',')
        }
        $orderId = $baseId + $index
        $addressId = $baseId + 500000 + $index
        $orderNo = "$ScenarioOrderPrefix$('{0:D5}' -f $index)"
        $reservationNo = "RSV-$orderNo"
        $paymentNo = "PAY-$orderNo"
        $eventId = [Guid]::NewGuid().ToString()
        $userId = 700000000 + ($Scale * 10000) + $index
        $payload = (
            "{`"eventId`":`"$eventId`",`"eventType`":`"PaymentSucceeded`"," +
            "`"aggregateType`":`"PaymentOrder`",`"aggregateId`":`"$paymentNo`"," +
            "`"aggregateVersion`":1,`"producer`":`"payment-service`",`"payloadVersion`":1," +
            "`"payload`":{`"paymentNo`":`"$paymentNo`",`"orderNo`":`"$orderNo`"," +
            "`"userId`":$userId,`"reservationNo`":`"$reservationNo`",`"amount`":39.80}}"
        )
        [void]$orderRows.Append(
            "($orderId,'$orderNo',$userId,'idem-$orderNo'," +
            "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'," +
            "'$reservationNo','PRIMARY',1,'PENDING_PAYMENT',39.80,0.00,39.80,NULL," +
            "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),NULL,0,NULL,NULL,0," +
            "CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
        [void]$addressRows.Append(
            "($addressId,$orderId,$addressId,'M3 Recipient','13800000000'," +
            "'Beijing','110000','Beijing','110100','Haidian','110108'," +
            "'PlainJournal M3 verification','100000',CURRENT_TIMESTAMP(3))")
        [void]$eventRows.Append(
            "('$eventId','PaymentSucceeded','$InputAggregateType','$orderNo',1," +
            "'$payload','PENDING',0,CURRENT_TIMESTAMP(3),NULL,NULL,NULL,NULL,NULL," +
            "CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
    }

    return @"
START TRANSACTION;
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, original_amount, discount_amount,
     total_amount, marketing_lock_no, payment_deadline, close_reason,
     recovery_attempts, next_recovery_at, last_error, version, created_at, updated_at)
VALUES
$orderRows;
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province,
     province_code, city, city_code, district, district_code, detail_address,
     postal_code, created_at)
VALUES
$addressRows;
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
     published_at, last_error, created_at, updated_at)
VALUES
$eventRows;
COMMIT;
"@
}

function Get-ScenarioState {
    param(
        [Parameter(Mandatory)][string]$ScenarioOrderPrefix,
        [Parameter(Mandatory)][string]$InputAggregateType
    )

    return Get-MySqlScalar -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_type = '$InputAggregateType' AND status = 'PUBLISHED'), '|',
    (SELECT COUNT(*) FROM trade_order
      WHERE order_no LIKE '$ScenarioOrderPrefix%' AND status = 'PAYMENT_CONFIRMING'), '|',
    (SELECT COUNT(*) FROM consumed_event consumed
      JOIN outbox_event input_event ON input_event.id = consumed.event_id
      WHERE input_event.aggregate_type = '$InputAggregateType'
        AND consumed.consumer_group = 'trade-payment-succeeded-v1'), '|',
    (SELECT COUNT(*) FROM order_status_history history
      JOIN trade_order trade ON trade.id = history.order_id
      WHERE trade.order_no LIKE '$ScenarioOrderPrefix%'
        AND history.command = 'PAYMENT_SUCCEEDED'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_type = 'TradeOrder'
        AND aggregate_id LIKE '$ScenarioOrderPrefix%'
        AND event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_type = 'TradeOrder'
        AND aggregate_id LIKE '$ScenarioOrderPrefix%'
        AND event_type = 'OrderPaid'
        AND status = 'PUBLISHED')
);
"@
}

function Wait-Scenario {
    param(
        [Parameter(Mandatory)][string]$ScenarioOrderPrefix,
        [Parameter(Mandatory)][string]$InputAggregateType,
        [Parameter(Mandatory)][int]$ExpectedCount
    )

    $deadline = (Get-Date).AddSeconds($script:TimeoutSeconds)
    do {
        $state = Get-ScenarioState `
            -ScenarioOrderPrefix $ScenarioOrderPrefix `
            -InputAggregateType $InputAggregateType
        $parts = $state -split '\|'
        $expectedState = "$ExpectedCount|$ExpectedCount|$ExpectedCount|$ExpectedCount|0|0"
        if ($state -eq $expectedState) {
            return $state
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Payment consumer scenario did not converge: $state"
}

function Get-FaultScenarioState {
    param(
        [Parameter(Mandatory)][string]$ScenarioOrderPrefix,
        [Parameter(Mandatory)][string]$InputAggregateType
    )

    return Get-MySqlScalar -Sql @"
SELECT CONCAT(
    (SELECT status FROM outbox_event
      WHERE aggregate_type = '$InputAggregateType' LIMIT 1), '|',
    (SELECT status FROM trade_order
      WHERE order_no LIKE '$ScenarioOrderPrefix%' LIMIT 1), '|',
    (SELECT COUNT(*) FROM consumed_event consumed
      JOIN outbox_event input_event ON input_event.id = consumed.event_id
      WHERE input_event.aggregate_type = '$InputAggregateType'
        AND consumed.consumer_group = 'trade-payment-succeeded-v1'), '|',
    (SELECT COUNT(*) FROM order_status_history history
      JOIN trade_order trade ON trade.id = history.order_id
      WHERE trade.order_no LIKE '$ScenarioOrderPrefix%'
        AND history.command = 'PAYMENT_SUCCEEDED'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_type = 'TradeOrder'
        AND aggregate_id LIKE '$ScenarioOrderPrefix%'
        AND event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_type = 'TradeOrder'
        AND aggregate_id LIKE '$ScenarioOrderPrefix%'
        AND event_type = 'OrderPaid'
        AND status = 'PUBLISHED')
);
"@
}

function Get-PrometheusCounter {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Metric
    )

    $matches = [regex]::Matches(
        $Content,
        "(?m)^$([regex]::Escape($Metric))\{[^}]*\}\s+([0-9.eE+-]+)$")
    $sum = 0.0
    foreach ($match in $matches) {
        $sum += [double]::Parse(
            $match.Groups[1].Value,
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture)
    }
    return $sum
}

function Get-ConsumerMetrics {
    param([Parameter(Mandatory)][string[]]$ContainerIds)

    $metrics = @()
    foreach ($containerId in $ContainerIds) {
        $content = docker exec $containerId wget `
            -q `
            -O - `
            "--header=X-Metrics-Token: $($script:settings['METRICS_SCRAPE_TOKEN'])" `
            http://127.0.0.1:18104/actuator/prometheus
        if ($LASTEXITCODE -ne 0) {
            throw "Metrics scrape failed: $(Get-ContainerName -ContainerId $containerId)"
        }
        $text = $content -join "`n"
        $metrics += [pscustomobject]@{
            Name = Get-ContainerName -ContainerId $containerId
            Acknowledgements = Get-PrometheusCounter `
                -Content $text `
                -Metric 'ecommerce_messaging_consumer_acknowledgements_total'
            RedeliveryAcknowledgements = Get-PrometheusCounter `
                -Content $text `
                -Metric 'ecommerce_messaging_consumer_redelivery_acknowledgements_total'
        }
    }
    return $metrics
}

function Get-MetricDelta {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Before,
        [Parameter(Mandatory)][object[]]$After
    )

    $delta = @()
    foreach ($afterMetric in $After) {
        $beforeMetric = $Before | Where-Object Name -eq $afterMetric.Name | Select-Object -First 1
        $beforeAcknowledgements = if ($beforeMetric) { $beforeMetric.Acknowledgements } else { 0 }
        $beforeRedeliveries = if ($beforeMetric) { $beforeMetric.RedeliveryAcknowledgements } else { 0 }
        $delta += [pscustomobject]@{
            Name = $afterMetric.Name
            Acknowledgements = $afterMetric.Acknowledgements - $beforeAcknowledgements
            RedeliveryAcknowledgements =
                $afterMetric.RedeliveryAcknowledgements - $beforeRedeliveries
        }
    }
    return $delta
}

function Wait-ConsumerMetricsStable {
    param(
        [Parameter(Mandatory)][string[]]$ContainerIds,
        [int]$StableSamples = 3
    )

    $deadline = (Get-Date).AddSeconds(30)
    $previousTotal = -1.0
    $stable = 0
    do {
        $metrics = @(Get-ConsumerMetrics -ContainerIds $ContainerIds)
        $total = [double](($metrics | Measure-Object Acknowledgements -Sum).Sum)
        if ($total -eq $previousTotal) {
            $stable++
            if ($stable -ge $StableSamples) {
                return $metrics
            }
        } else {
            $stable = 0
            $previousTotal = $total
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Consumer metrics did not stabilize; last acknowledgement total was $previousTotal."
}

function Wait-AcknowledgementDelta {
    param(
        [Parameter(Mandatory)][string[]]$ContainerIds,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Before,
        [Parameter(Mandatory)][int]$ExpectedCount
    )

    $deadline = (Get-Date).AddSeconds($script:TimeoutSeconds)
    do {
        $after = @(Get-ConsumerMetrics -ContainerIds $ContainerIds)
        $delta = @(Get-MetricDelta -Before $Before -After $after)
        $total = [int](($delta | Measure-Object Acknowledgements -Sum).Sum)
        if ($total -ge $ExpectedCount) {
            return $delta
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Consumer acknowledgement metrics did not reach $ExpectedCount; observed $total."
}

function Remove-ProbeData {
    Send-TradeMySql -Sql @"
DELETE FROM consumer_failure
WHERE consumer_group = '$brokerConsumerGroup';
DELETE consumed
FROM consumed_event consumed
JOIN outbox_event input_event ON input_event.id = consumed.event_id
WHERE input_event.aggregate_type LIKE '$inputAggregatePrefix%';
DELETE FROM outbox_event
WHERE aggregate_type LIKE '$inputAggregatePrefix%'
   OR (aggregate_type = 'TradeOrder' AND aggregate_id LIKE '$orderPrefix%');
DELETE history
FROM order_status_history history
JOIN trade_order trade ON trade.id = history.order_id
WHERE trade.order_no LIKE '$orderPrefix%';
DELETE address_snapshot
FROM order_address_snapshot address_snapshot
JOIN trade_order trade ON trade.id = address_snapshot.order_id
WHERE trade.order_no LIKE '$orderPrefix%';
DELETE FROM trade_order WHERE order_no LIKE '$orderPrefix%';
"@
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing Docker environment file: $envFile"
}
if (-not (Test-Path -LiteralPath $tradeJar)) {
    throw "Missing Trade executable jar: $tradeJar. Run mvn -pl services/trade-service -am package first."
}
if (-not (Test-Path -LiteralPath $runDirectory)) {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
}

if (-not $SkipNetworkPreflight) {
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw 'Local development network preflight failed.'
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
    'IDENTITY_JWT_SECRET',
    'TRADE_INTERNAL_SERVICE_TOKEN',
    'PAYMENT_INTERNAL_SERVICE_TOKEN',
    'METRICS_SCRAPE_TOKEN'
)) {
    if (-not $settings.ContainsKey($name) -or -not $settings[$name]) {
        throw "Missing required value in deploy/docker/.env: $name"
    }
}
if ($UseIsolatedRocketMq -and
    [string]::IsNullOrWhiteSpace($RocketMqImageTag) -and
    (-not $settings.ContainsKey('ROCKETMQ_IMAGE_TAG') -or
        [string]::IsNullOrWhiteSpace($settings['ROCKETMQ_IMAGE_TAG']))) {
    throw 'Missing required value in deploy/docker/.env: ROCKETMQ_IMAGE_TAG'
}
$effectiveRocketMqImageTag = if ([string]::IsNullOrWhiteSpace($RocketMqImageTag)) {
    $settings['ROCKETMQ_IMAGE_TAG']
} else {
    $RocketMqImageTag.Trim()
}

$existingTradeContainers = @(Invoke-Compose -Arguments @('ps', '-aq', 'trade-service'))
if (@($existingTradeContainers | Where-Object { $_ }).Count -gt 0) {
    throw 'Trade Compose containers already exist. Remove them before running this verification.'
}

foreach ($name in $processEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $processEnvironment[$name], 'Process')
}

try {
    if ($UseIsolatedRocketMq) {
        Start-IsolatedRocketMq
    }
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
    Wait-Middleware

    if (-not $SkipBuild) {
        Invoke-Compose -Arguments @('build', 'trade-service')
    }

    foreach ($requiredTopic in @($topic, $flashSaleTopic)) {
        $topicCommand = "sh mqadmin updateTopic -n $rocketMqNameServerAddress " +
            "-c EcommerceCluster -t $requiredTopic -r 8 -w 8"
        $topicResult = docker exec $rocketMqBrokerContainer sh -lc $topicCommand
        if ($LASTEXITCODE -ne 0 -or ($topicResult -join "`n") -notmatch 'success') {
            throw "Failed to create RocketMQ topic ${requiredTopic}: $($topicResult -join ' ')"
        }
    }

    Remove-ProbeData
    $nacosHeaders = Get-NacosHeaders
    foreach ($scale in @(1, 2, 3)) {
        Invoke-Compose -Arguments @(
            'up', '-d', '--no-deps', '--scale', "trade-service=$scale", 'trade-service'
        )
        $containerIds = @(Wait-TradeContainers -ExpectedCount $scale)
        $nacosInstances = @(Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount $scale)
        $beforeMetrics = @(Wait-ConsumerMetricsStable -ContainerIds $containerIds)
        $scenarioOrderPrefix = "$orderPrefix$scale-"
        $inputAggregateType = "$inputAggregatePrefix$scale"
        $timer = [Diagnostics.Stopwatch]::StartNew()
        Send-TradeMySql -Sql (New-PaymentScenarioSql `
            -Scale $scale `
            -ScenarioOrderPrefix $scenarioOrderPrefix `
            -InputAggregateType $inputAggregateType `
            -Count $EventCount)
        $state = Wait-Scenario `
            -ScenarioOrderPrefix $scenarioOrderPrefix `
            -InputAggregateType $inputAggregateType `
            -ExpectedCount $EventCount
        $timer.Stop()
        $metricDelta = @(Wait-AcknowledgementDelta `
            -ContainerIds $containerIds `
            -Before $beforeMetrics `
            -ExpectedCount $EventCount)
        $acknowledged = [int](($metricDelta | Measure-Object Acknowledgements -Sum).Sum)
        $activeConsumers = @($metricDelta | Where-Object Acknowledgements -gt 0).Count
        if ($acknowledged -lt $EventCount -or $activeConsumers -ne $scale) {
            throw ("Scale $scale consumer competition failed: acknowledged=$acknowledged " +
                "activeConsumers=$activeConsumers minimumExpected=$EventCount/$scale")
        }

        $results.Add([pscustomobject]@{
            Scenario = 'consumer-competition'
            InstanceCount = $scale
            EventCount = $EventCount
            AcknowledgementCount = $acknowledged
            DuplicateAcknowledgementCount = $acknowledged - $EventCount
            ElapsedMilliseconds = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
            ThroughputEventsPerSecond = [math]::Round(
                $EventCount / $timer.Elapsed.TotalSeconds, 3)
            DatabaseState = $state
            NacosEndpoints = @($nacosInstances | ForEach-Object { "$($_.ip):$($_.port)" })
            ConsumerMetrics = $metricDelta
        })

        if ($scale -eq 3) {
            $duplicateBefore = @(Wait-ConsumerMetricsStable -ContainerIds $containerIds)
            Send-TradeMySql -Sql @"
UPDATE outbox_event
SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP(3),
    claimed_at = NULL, claim_owner = NULL, claim_until = NULL,
    published_at = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
WHERE aggregate_type = '$inputAggregateType';
"@
            $duplicateMetrics = @(Wait-AcknowledgementDelta `
                -ContainerIds $containerIds `
                -Before $duplicateBefore `
                -ExpectedCount $EventCount)
            $duplicateState = Wait-Scenario `
                -ScenarioOrderPrefix $scenarioOrderPrefix `
                -InputAggregateType $inputAggregateType `
                -ExpectedCount $EventCount
            $historyCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM order_status_history history
JOIN trade_order trade ON trade.id = history.order_id
WHERE trade.order_no LIKE '$scenarioOrderPrefix%'
  AND history.command = 'PAYMENT_SUCCEEDED';
"@)
            $paymentConfirmingCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM trade_order
WHERE order_no LIKE '$scenarioOrderPrefix%'
  AND status = 'PAYMENT_CONFIRMING';
"@)
            $paidCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM trade_order
WHERE order_no LIKE '$scenarioOrderPrefix%'
  AND status = 'PAID';
"@)
            $outputCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM outbox_event
WHERE aggregate_type = 'TradeOrder'
  AND aggregate_id LIKE '$scenarioOrderPrefix%'
  AND event_type = 'OrderPaid';
"@)
            $consumedCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM consumed_event consumed
JOIN outbox_event input_event ON input_event.id = consumed.event_id
WHERE input_event.aggregate_type = '$inputAggregateType'
  AND consumed.consumer_group = 'trade-payment-succeeded-v1';
"@)
            if ($historyCount -ne $EventCount -or
                $paymentConfirmingCount -ne $EventCount -or
                $paidCount -ne 0 -or
                $outputCount -ne 0 -or
                $consumedCount -ne $EventCount) {
                throw ("Duplicate delivery changed business side effects: history=$historyCount " +
                    "paymentConfirming=$paymentConfirmingCount paid=$paidCount " +
                    "output=$outputCount consumed=$consumedCount")
            }
            $results.Add([pscustomobject]@{
                Scenario = 'duplicate-delivery-idempotency'
                InstanceCount = 3
                DuplicateEventCount = $EventCount
                DatabaseState = $duplicateState
                HistoryCount = $historyCount
                PaymentConfirmingOrderCount = $paymentConfirmingCount
                PaidOrderCount = $paidCount
                OrderPaidOutboxCount = $outputCount
                ConsumedEventCount = $consumedCount
                ConsumerMetrics = $duplicateMetrics
            })
        }
    }

    Remove-ProbeData
    Remove-TradeContainers

    $beforePublishOrderPrefix = "$orderPrefix" + 'fault-before-publish-'
    $beforePublishAggregate = "$inputAggregatePrefix" + 'fault-before-publish'
    Send-TradeMySql -Sql (New-PaymentScenarioSql `
        -Scale 11 `
        -ScenarioOrderPrefix $beforePublishOrderPrefix `
        -InputAggregateType $beforePublishAggregate `
        -Count 1)
    $beforePublishEventId = Get-MySqlScalar -Sql @"
SELECT id FROM outbox_event WHERE aggregate_type = '$beforePublishAggregate' LIMIT 1;
"@
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $false `
        -FaultEnabled $true `
        -FaultPoint 'OUTBOX_BEFORE_PUBLISH' `
        -TargetEventId $beforePublishEventId
    $beforePublishContainer = Start-SingleTradeContainer
    $beforePublishExit = Wait-ContainerExit `
        -ContainerId $beforePublishContainer `
        -ExpectedExitCode 91
    $beforePublishCrashState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $beforePublishOrderPrefix `
        -InputAggregateType $beforePublishAggregate
    if ($beforePublishCrashState -ne 'PUBLISHING|PENDING_PAYMENT|0|0|0|0') {
        throw "Before-publish crash boundary was not observed: $beforePublishCrashState"
    }
    Remove-TradeContainers
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $true `
        -FaultEnabled $false
    $beforePublishRecoveryContainer = Start-SingleTradeContainer
    [void](Wait-TradeContainers -ExpectedCount 1)
    [void](Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount 1 -WaitSeconds 45)
    [void](Wait-Scenario `
        -ScenarioOrderPrefix $beforePublishOrderPrefix `
        -InputAggregateType $beforePublishAggregate `
        -ExpectedCount 1)
    $beforePublishFinalState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $beforePublishOrderPrefix `
        -InputAggregateType $beforePublishAggregate
    if ($beforePublishFinalState -ne 'PUBLISHED|PAYMENT_CONFIRMING|1|1|0|0') {
        throw "Before-publish recovery did not converge: $beforePublishFinalState"
    }
    $results.Add([pscustomobject]@{
        Scenario = 'process-termination'
        Point = 'OUTBOX_BEFORE_PUBLISH'
        TargetEventId = $beforePublishEventId
        ExitState = $beforePublishExit
        CrashDatabaseState = $beforePublishCrashState
        FinalDatabaseState = $beforePublishFinalState
        RecoveryContainer = Get-ContainerName -ContainerId $beforePublishRecoveryContainer
    })
    Remove-TradeContainers

    $afterBrokerAckOrderPrefix = "$orderPrefix" + 'fault-after-broker-ack-'
    $afterBrokerAckAggregate = "$inputAggregatePrefix" + 'fault-after-broker-ack'
    Send-TradeMySql -Sql (New-PaymentScenarioSql `
        -Scale 12 `
        -ScenarioOrderPrefix $afterBrokerAckOrderPrefix `
        -InputAggregateType $afterBrokerAckAggregate `
        -Count 1)
    $afterBrokerAckEventId = Get-MySqlScalar -Sql @"
SELECT id FROM outbox_event WHERE aggregate_type = '$afterBrokerAckAggregate' LIMIT 1;
"@
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $false `
        -FaultEnabled $true `
        -FaultPoint 'OUTBOX_AFTER_BROKER_ACK' `
        -TargetEventId $afterBrokerAckEventId
    $afterBrokerAckContainer = Start-SingleTradeContainer
    $afterBrokerAckExit = Wait-ContainerExit `
        -ContainerId $afterBrokerAckContainer `
        -ExpectedExitCode 91
    $afterBrokerAckCrashState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $afterBrokerAckOrderPrefix `
        -InputAggregateType $afterBrokerAckAggregate
    if ($afterBrokerAckCrashState -ne 'PUBLISHING|PENDING_PAYMENT|0|0|0|0') {
        throw "After-broker-ACK crash boundary was not observed: $afterBrokerAckCrashState"
    }
    Remove-TradeContainers
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $true `
        -FaultEnabled $false
    $afterBrokerAckRecoveryContainer = Start-SingleTradeContainer
    $afterBrokerAckRecoveryIds = @(Wait-TradeContainers -ExpectedCount 1)
    [void](Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount 1 -WaitSeconds 45)
    [void](Wait-Scenario `
        -ScenarioOrderPrefix $afterBrokerAckOrderPrefix `
        -InputAggregateType $afterBrokerAckAggregate `
        -ExpectedCount 1)
    $afterBrokerAckMetrics = @(Wait-AcknowledgementDelta `
        -ContainerIds $afterBrokerAckRecoveryIds `
        -Before @() `
        -ExpectedCount 2)
    $afterBrokerAckAcknowledged =
        [int](($afterBrokerAckMetrics | Measure-Object Acknowledgements -Sum).Sum)
    $afterBrokerAckFinalState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $afterBrokerAckOrderPrefix `
        -InputAggregateType $afterBrokerAckAggregate
    if ($afterBrokerAckFinalState -ne 'PUBLISHED|PAYMENT_CONFIRMING|1|1|0|0' -or
        $afterBrokerAckAcknowledged -lt 2) {
        throw ("After-broker-ACK recovery did not prove duplicate delivery: " +
            "state=$afterBrokerAckFinalState acknowledgements=$afterBrokerAckAcknowledged")
    }
    $results.Add([pscustomobject]@{
        Scenario = 'process-termination'
        Point = 'OUTBOX_AFTER_BROKER_ACK'
        TargetEventId = $afterBrokerAckEventId
        ExitState = $afterBrokerAckExit
        CrashDatabaseState = $afterBrokerAckCrashState
        FinalDatabaseState = $afterBrokerAckFinalState
        DuplicateAcknowledgements = $afterBrokerAckAcknowledged
        ConsumerMetrics = $afterBrokerAckMetrics
        RecoveryContainer = Get-ContainerName -ContainerId $afterBrokerAckRecoveryContainer
    })
    Remove-TradeContainers

    $consumerAfterCommitOrderPrefix = "$orderPrefix" + 'fault-consumer-after-commit-'
    $consumerAfterCommitAggregate = "$inputAggregatePrefix" + 'fault-consumer-after-commit'
    Send-TradeMySql -Sql (New-PaymentScenarioSql `
        -Scale 13 `
        -ScenarioOrderPrefix $consumerAfterCommitOrderPrefix `
        -InputAggregateType $consumerAfterCommitAggregate `
        -Count 1)
    $consumerAfterCommitEventId = Get-MySqlScalar -Sql @"
SELECT id FROM outbox_event WHERE aggregate_type = '$consumerAfterCommitAggregate' LIMIT 1;
"@
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $false `
        -FaultEnabled $false
    $consumerProducerContainer = Start-SingleTradeContainer
    [void](Wait-TradeContainers -ExpectedCount 1)
    [void](Wait-MySqlScalar `
        -Sql "SELECT status FROM outbox_event WHERE id = '$consumerAfterCommitEventId';" `
        -Expected 'PUBLISHED' `
        -WaitSeconds $TimeoutSeconds)
    Remove-TradeContainers
    Set-ExperimentMode `
        -OutboxEnabled $false `
        -PaymentConsumerEnabled $true `
        -FaultEnabled $true `
        -FaultPoint 'CONSUMER_AFTER_COMMIT' `
        -TargetEventId $consumerAfterCommitEventId
    $consumerAfterCommitContainer = Start-SingleTradeContainer
    $consumerAfterCommitExit = Wait-ContainerExit `
        -ContainerId $consumerAfterCommitContainer `
        -ExpectedExitCode 91
    $consumerAfterCommitCrashState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $consumerAfterCommitOrderPrefix `
        -InputAggregateType $consumerAfterCommitAggregate
    if ($consumerAfterCommitCrashState -ne 'PUBLISHED|PAYMENT_CONFIRMING|1|1|0|0') {
        throw "Consumer-after-commit crash boundary was not observed: $consumerAfterCommitCrashState"
    }
    Remove-TradeContainers
    Set-ExperimentMode `
        -OutboxEnabled $true `
        -PaymentConsumerEnabled $true `
        -FaultEnabled $false
    $consumerRecoveryContainer = Start-SingleTradeContainer
    $consumerRecoveryIds = @(Wait-TradeContainers -ExpectedCount 1)
    [void](Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount 1 -WaitSeconds 45)
    $consumerRecoveryMetrics = @(Wait-AcknowledgementDelta `
        -ContainerIds $consumerRecoveryIds `
        -Before @() `
        -ExpectedCount 1)
    [void](Wait-Scenario `
        -ScenarioOrderPrefix $consumerAfterCommitOrderPrefix `
        -InputAggregateType $consumerAfterCommitAggregate `
        -ExpectedCount 1)
    $redeliveryAcknowledged = [int]((
        $consumerRecoveryMetrics |
            Measure-Object RedeliveryAcknowledgements -Sum
    ).Sum)
    $consumerAfterCommitFinalState = Get-FaultScenarioState `
        -ScenarioOrderPrefix $consumerAfterCommitOrderPrefix `
        -InputAggregateType $consumerAfterCommitAggregate
    if ($consumerAfterCommitFinalState -ne 'PUBLISHED|PAYMENT_CONFIRMING|1|1|0|0' -or
        $redeliveryAcknowledged -lt 1) {
        throw ("Consumer-after-commit recovery did not prove redelivery: " +
            "state=$consumerAfterCommitFinalState redeliveries=$redeliveryAcknowledged")
    }
    $results.Add([pscustomobject]@{
        Scenario = 'process-termination'
        Point = 'CONSUMER_AFTER_COMMIT'
        TargetEventId = $consumerAfterCommitEventId
        ExitState = $consumerAfterCommitExit
        CrashDatabaseState = $consumerAfterCommitCrashState
        FinalDatabaseState = $consumerAfterCommitFinalState
        RedeliveryAcknowledgements = $redeliveryAcknowledged
        ConsumerMetrics = $consumerRecoveryMetrics
        RecoveryContainer = Get-ContainerName -ContainerId $consumerRecoveryContainer
    })

    $brokerStoreMount = docker inspect $rocketMqBrokerContainer `
        --format '{{range .Mounts}}{{if eq .Destination "/home/rocketmq/store"}}{{.Type}}|{{.Name}}|{{.Source}}{{end}}{{end}}'
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($brokerStoreMount)) {
        throw 'Unable to verify the RocketMQ Broker store mount.'
    }
    $evidence = [pscustomobject]@{
        VerifiedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
        GitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
        Topic = $topic
        FlashSaleTopic = $flashSaleTopic
        BrokerConsumerGroup = $brokerConsumerGroup
        Environment = [pscustomobject]@{
            MySql = 'mysql:8.4.10'
            Nacos = 'nacos/nacos-server:v3.2.2'
            RocketMq = "apache/rocketmq:$effectiveRocketMqImageTag"
            RocketMqMode = if ($UseIsolatedRocketMq) { 'isolated-ephemeral' } else { 'shared' }
            RocketMqBrokerStoreMount = $brokerStoreMount.Trim()
            TradeImage = 'plainjournal/trade-service:local'
        }
        LogsUsedAsProof = $false
        Results = $results
    }
}
finally {
    try {
        Remove-ProbeData
    }
    catch {
        $cleanupFailures.Add("Consumer probe-row cleanup failed: $($_.Exception.Message)")
    }
    try {
        $tradeContainers = @(Invoke-Compose -Arguments @('ps', '-aq', 'trade-service'))
        if (@($tradeContainers | Where-Object { $_ }).Count -gt 0 -and -not $KeepRunning) {
            Invoke-Compose -Arguments @('rm', '-sf', 'trade-service')
        }
    }
    catch {
        $cleanupFailures.Add("Trade container cleanup failed: $($_.Exception.Message)")
    }
    try {
        Remove-SharedRocketMqArtifacts
    }
    catch {
        $cleanupFailures.Add("Shared RocketMQ cleanup failed: $($_.Exception.Message)")
    }
    foreach ($name in $processEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (-not $KeepRunning) {
        try {
            Stop-IsolatedRocketMq
        }
        catch {
            $cleanupFailures.Add("Isolated RocketMQ cleanup failed: $($_.Exception.Message)")
        }
        foreach ($container in @($startedContainers)) {
            docker stop $container | Out-Null
        }
    }
    if ($cleanupFailures.Count -gt 0) {
        throw "Consumer verification cleanup failed: $($cleanupFailures -join ' | ')"
    }
}

$remainingProbeRows = [int](Get-MySqlScalar -Sql @"
SELECT
    (SELECT COUNT(*) FROM consumer_failure
     WHERE consumer_group = '$brokerConsumerGroup')
  + (SELECT COUNT(*) FROM outbox_event
     WHERE aggregate_type LIKE '$inputAggregatePrefix%'
        OR (aggregate_type = 'TradeOrder' AND aggregate_id LIKE '$orderPrefix%'))
  + (SELECT COUNT(*) FROM trade_order
     WHERE order_no LIKE '$orderPrefix%');
"@)
$remainingTradeContainers = @(
    Invoke-Compose -Arguments @('ps', '-aq', 'trade-service') |
        Where-Object { $_ }
).Count
if ($remainingProbeRows -ne 0 -or
    (-not $KeepRunning -and $remainingTradeContainers -ne 0)) {
    throw (
        'Consumer verification post-cleanup assertion failed: ' +
        "probeRows=$remainingProbeRows tradeContainers=$remainingTradeContainers")
}
$evidence | Add-Member -NotePropertyName Cleanup -NotePropertyValue ([pscustomobject]@{
        ProbeRowsRemaining = $remainingProbeRows
        TradeContainersRemaining = $remainingTradeContainers
        RocketMqArtifactsVerifiedAbsent = -not $UseIsolatedRocketMq
        IsolatedRocketMqRemoved = $UseIsolatedRocketMq -and -not $KeepRunning
    })
$evidence | ConvertTo-Json -Depth 12 |
    Set-Content -LiteralPath $evidencePath -Encoding utf8

Write-Host 'Trade PaymentSucceeded consumer 1/2/3 instance verification passed.'
$results | Format-Table -AutoSize
Write-Host "Evidence: $evidencePath"
