[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [switch]$KeepDatabase,
    [ValidateRange(120, 600)][int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$envFile = Join-Path $composeDirectory '.env'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$runDirectory = Join-Path $PSScriptRoot '.run'
$runToken = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')
$workspace = Join-Path $runDirectory "m3-dual-version-$runToken"
$stableArchive = Join-Path $workspace 'stable-source.zip'
$stableSourceRoot = Join-Path $workspace 'stable-source'
$stableBackend = Join-Path $stableSourceRoot 'backend'
$stableImage = "plainjournal/trade-service:m3-stable-$runToken"
$candidateImage = "plainjournal/trade-service:m3-candidate-$runToken"
$candidateMigrationDirectory = Join-Path $PSScriptRoot `
    'services\trade-service\src\main\resources\db\migration'
$databaseName = "ecom_trade_m3compat_$runToken"
$containerPrefix = "plainjournal-m3-trade-compat-$runToken"
$evidencePath = Join-Path $runDirectory 'trade-dual-version-compatibility.json'
$rollingEvidenceName = 'trade-dual-version-rolling.json'
$rollingEvidencePath = Join-Path $runDirectory $rollingEvidenceName
$startedContainers = [System.Collections.Generic.List[string]]::new()
$experimentContainers = [System.Collections.Generic.List[string]]::new()
$createdTopics = [System.Collections.Generic.List[string]]::new()
$databaseCreated = $false
$settings = @{}
$stableJarHash = ''
$candidateJarHash = ''
$stableHead = ''

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

    & docker compose `
        --env-file $script:envFile `
        --file $script:composeFile `
        --project-directory $script:composeDirectory `
        --profile core `
        @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed: $($Arguments -join ' ')"
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$WaitSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 3
            if ([int]$response.StatusCode -eq 200) {
                return $response
            }
        }
        catch {
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "HTTP readiness timed out: $Uri"
}

function Save-TradeContainerLogs {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected compatibility container name: $Name"
    }
    $id = docker inspect --format '{{.Id}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $id) {
        return
    }
    $logDirectory = Join-Path $script:workspace 'container-logs'
    if (-not (Test-Path -LiteralPath $logDirectory)) {
        New-Item -ItemType Directory -Path $logDirectory | Out-Null
    }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& docker logs $Name 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -eq 0) {
        $output |
            ForEach-Object { $_.ToString() } |
            Set-Content -LiteralPath (Join-Path $logDirectory "$Name.log") -Encoding utf8
    }
}

function Wait-Middleware {
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $mysql = docker inspect --format '{{.State.Health.Status}}' plainjournal-mysql 2>$null
        $redis = docker inspect --format '{{.State.Health.Status}}' plainjournal-redis 2>$null
        $nacosReady = $false
        try {
            $nacos = Invoke-RestMethod `
                -Uri 'http://127.0.0.1:18080/v3/console/health/readiness' `
                -TimeoutSec 3
            $nacosReady = $nacos.code -eq 0 -and $nacos.data -eq 'ok'
        }
        catch {
        }
        $rocketMq = $false
        try {
            $client = [Net.Sockets.TcpClient]::new()
            $connect = $client.ConnectAsync('127.0.0.1', 18082)
            $rocketMq = $connect.Wait(1000) -and $client.Connected
            $client.Dispose()
        }
        catch {
        }
        if ($mysql -eq 'healthy' -and $redis -eq 'healthy' -and $nacosReady -and $rocketMq) {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Middleware readiness timed out: mysql=$mysql redis=$redis nacos=$nacosReady rocketmq=$rocketMq"
}

function Invoke-RootMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $Sql | docker exec `
        -i `
        -e "MYSQL_PWD=$($script:settings['MYSQL_ROOT_PASSWORD'])" `
        plainjournal-mysql mysql `
        --batch `
        --skip-column-names `
        --user=root
    if ($LASTEXITCODE -ne 0) {
        throw 'Root MySQL command failed.'
    }
}

function Invoke-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = docker exec `
        -e "MYSQL_PWD=$($script:settings['TRADE_DB_PASSWORD'])" `
        plainjournal-mysql mysql `
        --batch `
        --skip-column-names `
        "--user=$($script:settings['TRADE_DB_USER'])" `
        "--database=$($script:databaseName)" `
        "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade compatibility MySQL command failed.'
    }
    return @($output)
}

function Send-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $Sql | docker exec `
        -i `
        -e "MYSQL_PWD=$($script:settings['TRADE_DB_PASSWORD'])" `
        plainjournal-mysql mysql `
        --batch `
        --skip-column-names `
        "--user=$($script:settings['TRADE_DB_USER'])" `
        "--database=$($script:databaseName)"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade compatibility MySQL input failed.'
    }
}

function Get-TradeScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-TradeMySql -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one MySQL row, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function Resolve-TradeJar {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackendRoot
    )

    $targetDirectory = Join-Path $BackendRoot 'services\trade-service\target'
    $jars = @(
        Get-ChildItem -LiteralPath $targetDirectory -Filter 'trade-service-*.jar' `
            -File -ErrorAction Stop |
            Where-Object {
                $_.Name -notlike 'original-*' -and
                $_.Name -notlike '*-sources.jar' -and
                $_.Name -notlike '*-javadoc.jar'
            }
    )
    if ($jars.Count -ne 1) {
        $names = if ($jars.Count -eq 0) {
            '<none>'
        } else {
            ($jars.Name -join ', ')
        }
        throw "Expected one Trade service JAR in $targetDirectory, found: $names"
    }
    return $jars[0].FullName
}

function Start-TradeContainer {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Image,
        [Parameter(Mandatory)][int]$HostPort,
        [Parameter(Mandatory)][string]$InstanceId,
        [Parameter(Mandatory)][string]$ReleaseId,
        [bool]$OutboxEnabled = $false,
        [bool]$PaymentConsumerEnabled = $false,
        [string]$Topic = '',
        [string]$ConsumerGroup = ''
    )

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected compatibility container name: $Name"
    }
    $arguments = @(
        'run', '-d',
        '--name', $Name,
        '--label', "plainjournal.m3.run-token=$($script:runToken)",
        '--network', 'plainjournal-network',
        '--restart', 'no',
        '--init',
        '--memory', '768m',
        '--publish', "127.0.0.1:${HostPort}:18104",
        '--health-cmd', 'wget -q -O /dev/null http://127.0.0.1:18104/actuator/health || exit 1',
        '--health-interval', '10s',
        '--health-timeout', '3s',
        '--health-start-period', '60s',
        '--health-retries', '6',
        '--env', 'TZ=Asia/Shanghai',
        '--env', 'TRADE_SERVICE_PORT=18104',
        '--env', "SPRING_DATASOURCE_URL=jdbc:mysql://plainjournal-mysql:3306/$($script:databaseName)?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true",
        '--env', "SPRING_DATASOURCE_USERNAME=$($script:settings['TRADE_DB_USER'])",
        '--env', "SPRING_DATASOURCE_PASSWORD=$($script:settings['TRADE_DB_PASSWORD'])",
        '--env', 'NACOS_HOST=plainjournal-nacos',
        '--env', 'NACOS_CLIENT_PORT=8848',
        '--env', 'NACOS_USERNAME=nacos',
        '--env', "NACOS_ADMIN_PASSWORD=$($script:settings['NACOS_ADMIN_PASSWORD'])",
        '--env', "IDENTITY_JWT_SECRET=$($script:settings['IDENTITY_JWT_SECRET'])",
        # Git HEAD predates relationship-specific internal credentials. Supplying the
        # payment -> Trade credential under the legacy variable keeps that binary
        # runnable without reintroducing a shared-token fallback into current code.
        '--env', "INTERNAL_SERVICE_TOKEN=$($script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN'])",
        '--env', "TRADE_INTERNAL_SERVICE_TOKEN=$($script:settings['TRADE_INTERNAL_SERVICE_TOKEN'])",
        '--env', "PAYMENT_INTERNAL_SERVICE_TOKEN=$($script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN'])",
        '--env', "METRICS_SCRAPE_TOKEN=$($script:settings['METRICS_SCRAPE_TOKEN'])",
        '--env', "SERVICE_INSTANCE_ID=$InstanceId",
        '--env', "SERVICE_RELEASE_ID=$ReleaseId",
        '--env', 'SPRING_CLOUD_NACOS_DISCOVERY_REGISTER_ENABLED=false',
        '--env', ("ECOMMERCE_TRADE_OUTBOX_ENABLED=" +
            $OutboxEnabled.ToString().ToLowerInvariant()),
        '--env', 'ECOMMERCE_TRADE_OUTBOX_ENDPOINTS=plainjournal-rocketmq-broker:18082',
        '--env', ("ECOMMERCE_TRADE_PAYMENT_CONSUMER_ENABLED=" +
            $PaymentConsumerEnabled.ToString().ToLowerInvariant()),
        '--env', 'ECOMMERCE_TRADE_PAYMENT_CONSUMER_ENDPOINTS=plainjournal-rocketmq-broker:18082',
        '--env', 'ECOMMERCE_TRADE_FULFILLMENT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_REFUND_RESULT_CONSUMER_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_ORDER_RECOVERY_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_RECONCILIATION_ENABLED=false',
        '--env', 'MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false',
        '--env', 'ECOMMERCE_TRADE_OUTBOX_FIXED_DELAY=200',
        '--env', 'ECOMMERCE_TRADE_PAYMENT_CONSUMER_FIXED_DELAY=200'
    )
    if ($Topic) {
        $arguments += @(
            '--env', "ECOMMERCE_TRADE_OUTBOX_TOPIC=$Topic",
            '--env', "ECOMMERCE_TRADE_PAYMENT_CONSUMER_TOPIC=$Topic"
        )
    }
    if ($ConsumerGroup) {
        $arguments += @('--env', "ECOMMERCE_TRADE_PAYMENT_CONSUMER_CONSUMER_GROUP=$ConsumerGroup")
    }
    $arguments += @(
        $Image,
        "--spring.cloud.nacos.discovery.metadata.instance-id=$InstanceId",
        "--spring.cloud.nacos.discovery.metadata.release-id=$ReleaseId"
    )
    $id = & docker @arguments
    if ($LASTEXITCODE -ne 0 -or -not $id) {
        throw "Failed to start Trade compatibility container: $Name"
    }
    $script:experimentContainers.Add($Name)
    [void](Wait-HttpOk -Uri "http://127.0.0.1:$HostPort/actuator/health")
    return $id
}

function Remove-TradeContainer {
    param([Parameter(Mandatory)][string]$Name)

    if (-not $Name.StartsWith($script:containerPrefix)) {
        throw "Refusing unexpected compatibility container name: $Name"
    }
    $id = docker inspect --format '{{.Id}}' $Name 2>$null
    if ($LASTEXITCODE -eq 0 -and $id) {
        docker rm -f $Name | Out-Null
    }
}

function Get-PaymentContext {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$OrderNo
    )

    return Invoke-RestMethod `
        -Uri "http://127.0.0.1:$Port/api/v1/trade/internal/orders/$OrderNo/payment-context" `
        -Headers @{
            'X-Internal-Service' = 'payment-service'
            'X-Internal-Token' = $script:settings['PAYMENT_INTERNAL_SERVICE_TOKEN']
        } `
        -TimeoutSec 10
}

function New-RocketMqTopic {
    param([Parameter(Mandatory)][string]$Topic)

    $command = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateTopic " +
        "-n plainjournal-rocketmq-namesrv:9876 -c EcommerceCluster -t $Topic -r 4 -w 4"
    $result = docker exec plainjournal-rocketmq-broker sh -lc $command
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'success') {
        throw "Failed to create RocketMQ topic $Topic`: $($result -join ' ')"
    }
    $script:createdTopics.Add($Topic)
}

function Remove-RocketMqTopic {
    param([Parameter(Mandatory)][string]$Topic)

    if (-not $Topic.StartsWith("plainjournal-m3-compat-$($script:runToken)-")) {
        throw "Refusing unexpected RocketMQ topic name: $Topic"
    }
    $command = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin deleteTopic " +
        "-n plainjournal-rocketmq-namesrv:9876 -c EcommerceCluster -t $Topic"
    docker exec plainjournal-rocketmq-broker sh -lc $command | Out-Null
}

function New-EventScenarioSql {
    param(
        [Parameter(Mandatory)][string]$Scenario,
        [Parameter(Mandatory)][string]$EventId
    )

    $idBase = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
    $orderId = $idBase + [Math]::Abs($Scenario.GetHashCode() % 500)
    $orderNo = "M3V-$runToken-$Scenario"
    $reservationNo = "RSV-$orderNo"
    $paymentNo = "PAY-$orderNo"
    $userId = $orderId + 100000
    $payload = (
        "{`"eventId`":`"$EventId`",`"eventType`":`"PaymentSucceeded`"," +
        "`"aggregateType`":`"PaymentOrder`",`"aggregateId`":`"$paymentNo`"," +
        "`"aggregateVersion`":1,`"producer`":`"payment-service`",`"payloadVersion`":1," +
        "`"payload`":{`"paymentNo`":`"$paymentNo`",`"orderNo`":`"$orderNo`"," +
        "`"userId`":$userId,`"reservationNo`":`"$reservationNo`",`"amount`":39.80}}"
    )
    return [pscustomobject]@{
        OrderNo = $orderNo
        Sql = @"
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, original_amount, discount_amount,
     total_amount, marketing_lock_no, payment_deadline, close_reason,
     recovery_attempts, next_recovery_at, last_error, version, created_at, updated_at)
VALUES
    ($orderId,'$orderNo',$userId,'idem-$orderNo',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     '$reservationNo','PRIMARY',1,'PENDING_PAYMENT',39.80,0.00,39.80,NULL,
      DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),NULL,0,NULL,NULL,0,
      CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province, province_code,
     city, city_code, district, district_code, detail_address, postal_code, created_at)
VALUES
    ($($orderId + 1),$orderId,$($userId + 1),'M3 Compatibility','13800000000',
     'Zhejiang','330000','Hangzhou','330100','Xihu','330106',
     'PlainJournal compatibility address','310000',CURRENT_TIMESTAMP(3));
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
     published_at, last_error, created_at, updated_at)
VALUES
    ('$EventId','PaymentSucceeded','M3CompatibilityInput','$orderNo',1,
     '$payload','PENDING',0,CURRENT_TIMESTAMP(3),NULL,NULL,NULL,NULL,NULL,
     CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));
"@
    }
}

function Wait-EventScenario {
    param(
        [Parameter(Mandatory)][string]$OrderNo,
        [Parameter(Mandatory)][string]$EventId,
        [Parameter(Mandatory)][string]$ExpectedOrderStatus,
        [Parameter(Mandatory)][int]$ExpectedOrderPaidCount,
        [Parameter(Mandatory)][int]$ExpectedInventoryConfirmationRequestCount
    )

    $expected = (
        "PUBLISHED|$ExpectedOrderStatus|1|1|" +
        "$ExpectedOrderPaidCount|$ExpectedInventoryConfirmationRequestCount"
    )
    $deadline = (Get-Date).AddSeconds($script:TimeoutSeconds)
    do {
        $state = Get-TradeScalar -Sql @"
SELECT CONCAT(
    (SELECT status FROM outbox_event WHERE id = '$EventId'), '|',
    (SELECT status FROM trade_order WHERE order_no = '$OrderNo'), '|',
    (SELECT COUNT(*) FROM consumed_event WHERE event_id = '$EventId'), '|',
    (SELECT COUNT(*) FROM order_status_history h
       JOIN trade_order o ON o.id = h.order_id
      WHERE o.order_no = '$OrderNo' AND h.command = 'PAYMENT_SUCCEEDED'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_id = '$OrderNo' AND event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_id = '$OrderNo'
        AND event_type = 'PaymentInventoryConfirmationRequested')
);
"@
        if ($state -eq $expected) {
            return $state
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw (
        "Dual-version event scenario did not converge: order=$OrderNo " +
        "expected=$expected state=$state"
    )
}

function Wait-EventBuffered {
    param(
        [Parameter(Mandatory)][string]$OrderNo,
        [Parameter(Mandatory)][string]$EventId
    )

    $expected = 'PUBLISHED|PENDING_PAYMENT|0|0|0|0'
    $deadline = (Get-Date).AddSeconds($script:TimeoutSeconds)
    do {
        $state = Get-TradeScalar -Sql @"
SELECT CONCAT(
    (SELECT status FROM outbox_event WHERE id = '$EventId'), '|',
    (SELECT status FROM trade_order WHERE order_no = '$OrderNo'), '|',
    (SELECT COUNT(*) FROM consumed_event WHERE event_id = '$EventId'), '|',
    (SELECT COUNT(*) FROM order_status_history h
       JOIN trade_order o ON o.id = h.order_id
      WHERE o.order_no = '$OrderNo' AND h.command = 'PAYMENT_SUCCEEDED'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_id = '$OrderNo' AND event_type = 'OrderPaid'), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_id = '$OrderNo'
        AND event_type = 'PaymentInventoryConfirmationRequested')
);
"@
        if ($state -eq $expected) {
            return $state
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw (
        "Dual-version event was not safely buffered before consumer cutover: " +
        "order=$OrderNo expected=$expected state=$state"
    )
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing Docker environment file: $envFile"
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
    'MYSQL_ROOT_PASSWORD',
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
if ($databaseName -notmatch '^ecom_trade_m3compat_[0-9]+$') {
    throw "Refusing unexpected compatibility database name: $databaseName"
}
$candidateExpectedSchemaVersion = @(
    Get-ChildItem -LiteralPath $candidateMigrationDirectory -Filter 'V*__*.sql' -File |
        ForEach-Object {
            if ($_.BaseName -match '^V(?<Version>[0-9]+)__') {
                [int]$Matches.Version
            }
        }
    | Measure-Object -Maximum
).Maximum
if (-not $candidateExpectedSchemaVersion) {
    throw "No versioned Trade migrations were found in $candidateMigrationDirectory"
}

try {
    foreach ($container in @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy'
    )) {
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

    $stableHead = (& git -C $repositoryRoot rev-parse HEAD).Trim()
    if (-not $SkipBuild) {
        if (Test-Path -LiteralPath $workspace) {
            throw "Compatibility workspace already exists: $workspace"
        }
        New-Item -ItemType Directory -Path $workspace | Out-Null
        & git -C $repositoryRoot archive `
            --format=zip `
            "--output=$stableArchive" `
            HEAD `
            backend
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to archive stable source from Git HEAD.'
        }
        Expand-Archive -LiteralPath $stableArchive -DestinationPath $stableSourceRoot
        $stableDockerDirectory = Join-Path $stableBackend 'services\trade-service\docker'
        if (-not (Test-Path -LiteralPath $stableDockerDirectory)) {
            New-Item -ItemType Directory -Path $stableDockerDirectory | Out-Null
        }
        Copy-Item `
            -LiteralPath (Join-Path $PSScriptRoot 'services\trade-service\docker\entrypoint.sh') `
            -Destination (Join-Path $stableDockerDirectory 'entrypoint.sh') `
            -Force
        Copy-Item `
            -LiteralPath (Join-Path $PSScriptRoot 'services\trade-service\src\main\java\com\ecommerce\trade\interfaces\rest\SystemStatusController.java') `
            -Destination (Join-Path $stableBackend 'services\trade-service\src\main\java\com\ecommerce\trade\interfaces\rest\SystemStatusController.java') `
            -Force

        & mvn -f (Join-Path $stableBackend 'pom.xml') `
            -pl services/trade-service `
            -am `
            package `
            -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw 'Stable HEAD Trade build failed.'
        }
        & mvn -f (Join-Path $PSScriptRoot 'pom.xml') `
            -pl services/trade-service,ecommerce-gateway `
            -am `
            package `
            -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw 'Candidate Trade/Gateway build failed.'
        }

        docker build `
            --file (Join-Path $stableBackend 'services\trade-service\Dockerfile') `
            --tag $stableImage `
            $stableBackend
        if ($LASTEXITCODE -ne 0) {
            throw 'Stable Trade image build failed.'
        }
        docker build `
            --file (Join-Path $PSScriptRoot 'services\trade-service\Dockerfile') `
            --tag $candidateImage `
            $PSScriptRoot
        if ($LASTEXITCODE -ne 0) {
            throw 'Candidate Trade image build failed.'
        }
        docker build `
            --file (Join-Path $PSScriptRoot 'ecommerce-gateway\Dockerfile') `
            --tag 'plainjournal/ecommerce-gateway:local' `
            $PSScriptRoot
        if ($LASTEXITCODE -ne 0) {
            throw 'Gateway image build failed.'
        }
    }

    foreach ($image in @($stableImage, $candidateImage, 'plainjournal/ecommerce-gateway:local')) {
        docker image inspect $image *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "Missing required compatibility image: $image"
        }
    }
    $stableJar = if ($SkipBuild) {
        ''
    } else {
        Resolve-TradeJar -BackendRoot $stableBackend
    }
    $candidateJar = Resolve-TradeJar -BackendRoot $PSScriptRoot
    if ($stableJar) {
        $stableJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stableJar).Hash.ToLowerInvariant()
    }
    $candidateJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidateJar).Hash.ToLowerInvariant()
    if ($stableJarHash -and $stableJarHash -eq $candidateJarHash) {
        throw 'Stable and candidate JAR hashes are identical; dual-version verification is invalid.'
    }

    Invoke-RootMySql -Sql @"
CREATE DATABASE $databaseName
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON $databaseName.* TO '$($settings['TRADE_DB_USER'])'@'%';
FLUSH PRIVILEGES;
"@
    $databaseCreated = $true

    $legacyOrderNo = "M3V-$runToken-LEGACY"
    $legacyOrderId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
    $stableProbe = "$containerPrefix-stable-probe"
    [void](Start-TradeContainer `
        -Name $stableProbe `
        -Image $stableImage `
        -HostPort 19114 `
        -InstanceId 'trade-compat-stable-probe' `
        -ReleaseId 'm3-stable-head')
    $stableSchemaVersion = Get-TradeScalar `
        -Sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;'
    if ($stableSchemaVersion -ne '5') {
        throw "Stable binary did not establish the V5 schema: $stableSchemaVersion"
    }
    Send-TradeMySql -Sql @"
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, original_amount, discount_amount,
     total_amount, marketing_lock_no, payment_deadline, close_reason,
     recovery_attempts, next_recovery_at, last_error, version, created_at, updated_at)
VALUES
    ($legacyOrderId,'$legacyOrderNo',900001,'idem-$legacyOrderNo',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
     'RSV-$legacyOrderNo','PRIMARY',1,'PENDING_PAYMENT',39.80,0.00,39.80,NULL,
     DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR),NULL,0,NULL,NULL,0,
     CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, line_no,
     discount_amount, payable_amount, created_at)
VALUES
    ($($legacyOrderId + 1),$legacyOrderId,1001,2001,'Legacy Product','SKU-LEGACY',
     'Legacy SKU','{}',NULL,39.80,1,39.80,NULL,0.00,NULL,CURRENT_TIMESTAMP(3));
"@
    $stableBeforeMigration = Get-PaymentContext -Port 19114 -OrderNo $legacyOrderNo
    if ($stableBeforeMigration.data.status -ne 'PENDING_PAYMENT' -or
        [decimal]$stableBeforeMigration.data.totalAmount -ne [decimal]39.80) {
        throw 'Stable HTTP payment-context contract failed before candidate migration.'
    }
    Remove-TradeContainer -Name $stableProbe

    $candidateProbe = "$containerPrefix-candidate-probe"
    [void](Start-TradeContainer `
        -Name $candidateProbe `
        -Image $candidateImage `
        -HostPort 19115 `
        -InstanceId 'trade-compat-candidate-probe' `
        -ReleaseId 'm3-candidate-working-tree')
    $candidateSchemaVersion = Get-TradeScalar `
        -Sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;'
    $leaseMigrationCount = Get-TradeScalar -Sql @"
SELECT COUNT(*)
FROM flyway_schema_history
WHERE version = '10'
  AND success = 1;
"@
    $legacyBackfill = Get-TradeScalar -Sql @"
SELECT CONCAT(line_no, '|', payable_amount)
FROM order_item
WHERE order_id = $legacyOrderId;
"@
    if ([int]$candidateSchemaVersion -ne [int]$candidateExpectedSchemaVersion -or
        $leaseMigrationCount -ne '1' -or
        $legacyBackfill -ne '1|39.80') {
        throw (
            "Candidate migration did not converge: version=$candidateSchemaVersion " +
            "expected=$candidateExpectedSchemaVersion leaseMigration=$leaseMigrationCount " +
            "legacyBackfill=$legacyBackfill")
    }
    $candidateContext = Get-PaymentContext -Port 19115 -OrderNo $legacyOrderNo
    if ($candidateContext.data.orderNo -ne $stableBeforeMigration.data.orderNo -or
        $candidateContext.data.reservationNo -ne $stableBeforeMigration.data.reservationNo -or
        [decimal]$candidateContext.data.totalAmount -ne [decimal]$stableBeforeMigration.data.totalAmount) {
        throw 'Candidate HTTP payment-context response is incompatible with the stable response.'
    }
    Remove-TradeContainer -Name $candidateProbe

    $stableAfterMigration = "$containerPrefix-stable-after-candidate"
    [void](Start-TradeContainer `
        -Name $stableAfterMigration `
        -Image $stableImage `
        -HostPort 19114 `
        -InstanceId 'trade-compat-stable-after-candidate' `
        -ReleaseId 'm3-stable-head')
    $stableContextAfterMigration = Get-PaymentContext -Port 19114 -OrderNo $legacyOrderNo
    if ($stableContextAfterMigration.data.orderNo -ne $candidateContext.data.orderNo -or
        [decimal]$stableContextAfterMigration.data.totalAmount -ne [decimal]$candidateContext.data.totalAmount) {
        throw (
            "Stable binary could not read the V$candidateSchemaVersion schema " +
            'after candidate migration.')
    }
    Remove-TradeContainer -Name $stableAfterMigration

    & (Join-Path $PSScriptRoot 'verify-gateway-rolling-upgrade.ps1') `
        -SkipNetworkPreflight `
        -SkipBuild `
        -StableTradeImage $stableImage `
        -CandidateTradeImage $candidateImage `
        -TradeDatabaseName $databaseName `
        -EvidenceFileName $rollingEvidenceName `
        -TimeoutSeconds $TimeoutSeconds `
        -ProbeIntervalMilliseconds 100
    if ($LASTEXITCODE -ne 0) {
        throw 'Dual-version Gateway rolling verification failed.'
    }

    $stableToCandidateTopic = "plainjournal-m3-compat-$runToken-stable-to-candidate"
    New-RocketMqTopic -Topic $stableToCandidateTopic
    $stableToCandidateEventId = [Guid]::NewGuid().ToString()
    $stableToCandidateScenario = New-EventScenarioSql `
        -Scenario 'STABLE-CANDIDATE' `
        -EventId $stableToCandidateEventId
    Send-TradeMySql -Sql $stableToCandidateScenario.Sql
    $candidateConsumer = "$containerPrefix-candidate-consumer"
    $stableProducer = "$containerPrefix-stable-producer"
    [void](Start-TradeContainer `
        -Name $stableProducer `
        -Image $stableImage `
        -HostPort 19117 `
        -InstanceId 'trade-compat-stable-producer' `
        -ReleaseId 'm3-stable-head' `
        -OutboxEnabled $true `
        -Topic $stableToCandidateTopic)
    $stableToCandidateBufferedState = Wait-EventBuffered `
        -OrderNo $stableToCandidateScenario.OrderNo `
        -EventId $stableToCandidateEventId
    Remove-TradeContainer -Name $stableProducer
    [void](Start-TradeContainer `
        -Name $candidateConsumer `
        -Image $candidateImage `
        -HostPort 19116 `
        -InstanceId 'trade-compat-candidate-consumer' `
        -ReleaseId 'm3-candidate-working-tree' `
        -PaymentConsumerEnabled $true `
        -Topic $stableToCandidateTopic `
        -ConsumerGroup "m3-compat-candidate-$runToken")
    $stableToCandidateState = Wait-EventScenario `
        -OrderNo $stableToCandidateScenario.OrderNo `
        -EventId $stableToCandidateEventId `
        -ExpectedOrderStatus 'PAYMENT_CONFIRMING' `
        -ExpectedOrderPaidCount 0 `
        -ExpectedInventoryConfirmationRequestCount 1
    Remove-TradeContainer -Name $candidateConsumer

    $candidateToStableTopic = "plainjournal-m3-compat-$runToken-candidate-to-stable"
    New-RocketMqTopic -Topic $candidateToStableTopic
    $candidateToStableEventId = [Guid]::NewGuid().ToString()
    $candidateToStableScenario = New-EventScenarioSql `
        -Scenario 'CANDIDATE-STABLE' `
        -EventId $candidateToStableEventId
    Send-TradeMySql -Sql $candidateToStableScenario.Sql
    $stableConsumer = "$containerPrefix-stable-consumer"
    $candidateProducer = "$containerPrefix-candidate-producer"
    [void](Start-TradeContainer `
        -Name $candidateProducer `
        -Image $candidateImage `
        -HostPort 19119 `
        -InstanceId 'trade-compat-candidate-producer' `
        -ReleaseId 'm3-candidate-working-tree' `
        -OutboxEnabled $true `
        -Topic $candidateToStableTopic)
    $candidateToStableBufferedState = Wait-EventBuffered `
        -OrderNo $candidateToStableScenario.OrderNo `
        -EventId $candidateToStableEventId
    Remove-TradeContainer -Name $candidateProducer
    [void](Start-TradeContainer `
        -Name $stableConsumer `
        -Image $stableImage `
        -HostPort 19118 `
        -InstanceId 'trade-compat-stable-consumer' `
        -ReleaseId 'm3-stable-head' `
        -PaymentConsumerEnabled $true `
        -Topic $candidateToStableTopic `
        -ConsumerGroup "m3-compat-stable-$runToken")
    $candidateToStableState = Wait-EventScenario `
        -OrderNo $candidateToStableScenario.OrderNo `
        -EventId $candidateToStableEventId `
        -ExpectedOrderStatus 'PAID' `
        -ExpectedOrderPaidCount 1 `
        -ExpectedInventoryConfirmationRequestCount 0
    Remove-TradeContainer -Name $stableConsumer

    $rollingEvidence = Get-Content -Raw -LiteralPath $rollingEvidencePath | ConvertFrom-Json
    $evidence = [ordered]@{
        schemaVersion = 1
        verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        stable = [ordered]@{
            gitHead = $stableHead
            image = $stableImage
            imageId = (docker image inspect $stableImage --format '{{.Id}}')
            jarSha256 = $stableJarHash
            schemaVersionBeforeUpgrade = [int]$stableSchemaVersion
        }
        candidate = [ordered]@{
            source = 'working-tree'
            image = $candidateImage
            imageId = (docker image inspect $candidateImage --format '{{.Id}}')
            jarSha256 = $candidateJarHash
            expectedSchemaVersion = [int]$candidateExpectedSchemaVersion
            schemaVersionAfterUpgrade = [int]$candidateSchemaVersion
        }
        database = [ordered]@{
            name = $databaseName
            legacyBackfill = $legacyBackfill
            stableReadBeforeMigration = $stableBeforeMigration.data
            candidateReadAfterMigration = $candidateContext.data
            stableReadAfterMigration = $stableContextAfterMigration.data
        }
        httpAndRelease = $rollingEvidence
        eventCompatibility = [ordered]@{
            payloadVersion = 1
            stableEventBufferedBeforeCandidateConsumer = $stableToCandidateBufferedState
            stableProducerToCandidateConsumer = $stableToCandidateState
            candidateEventBufferedBeforeStableConsumer = $candidateToStableBufferedState
            candidateProducerToStableConsumer = $candidateToStableState
            wireEnvelopeCompatible = $true
            workflowSemanticsEquivalent = $false
            concurrentMixedConsumerRollingAllowed = $false
            requiredConsumerReleaseStrategy = (
                'stop old PaymentSucceeded consumers; let RocketMQ buffer; ' +
                'start candidate consumers only after old consumers are gone'
            )
            rollbackBoundary = (
                'after the candidate PaymentSucceeded workflow is enabled, ' +
                'rolling the consumer back to stable requires explicit recovery'
            )
        }
    }
    $evidence | ConvertTo-Json -Depth 14 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8

    Write-Host 'Trade database/HTTP/wire compatibility and consumer cutover boundary passed.'
    Write-Host "  Stable schema: V$stableSchemaVersion"
    Write-Host "  Candidate schema: V$candidateSchemaVersion"
    Write-Host "  Legacy backfill: $legacyBackfill"
    Write-Host "  Stable -> Candidate event: $stableToCandidateState"
    Write-Host "  Candidate -> Stable event: $candidateToStableState"
    Write-Host '  Concurrent mixed PaymentSucceeded consumers: FORBIDDEN'
    Write-Host "Evidence: $evidencePath"
}
finally {
    foreach ($container in @($experimentContainers)) {
        try {
            Save-TradeContainerLogs -Name $container
        }
        catch {
            Write-Warning "Compatibility container log capture failed for $container`: $($_.Exception.Message)"
        }
        try {
            Remove-TradeContainer -Name $container
        }
        catch {
            Write-Warning "Compatibility container cleanup failed for $container`: $($_.Exception.Message)"
        }
    }
    foreach ($topic in @($createdTopics)) {
        try {
            Remove-RocketMqTopic -Topic $topic
        }
        catch {
            Write-Warning "RocketMQ topic cleanup failed for $topic`: $($_.Exception.Message)"
        }
    }
    if ($databaseCreated -and -not $KeepDatabase) {
        try {
            if ($databaseName -notmatch '^ecom_trade_m3compat_[0-9]+$') {
                throw "Refusing unexpected compatibility database cleanup: $databaseName"
            }
            Invoke-RootMySql -Sql "DROP DATABASE $databaseName;"
            $databaseCreated = $false
        }
        catch {
            Write-Warning "Compatibility database cleanup failed: $($_.Exception.Message)"
        }
    }
    foreach ($container in @($startedContainers)) {
        docker stop $container | Out-Null
    }
}
