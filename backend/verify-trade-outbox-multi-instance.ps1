[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$StartRequiredContainers,
    [switch]$KeepContainersRunning,
    [ValidateRange(100, 10000)][int]$EventCount = 1000,
    [ValidateRange(30, 300)][int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($EventCount % 2 -ne 0) {
    throw 'EventCount must be even because every probe aggregate contains versions 1 and 2.'
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
$tradeJar = Join-Path $PSScriptRoot 'services\trade-service\target\trade-service-1.0.2-SNAPSHOT.jar'
$runDirectory = Join-Path $PSScriptRoot '.run'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$topic = 'plainjournal-m3-outbox-probe-v1'
$aggregatePrefix = 'M3OutboxProbe:'
$ports = @(18211, 18212, 18213)
$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy'
)
$startedContainers = [System.Collections.Generic.List[string]]::new()
$activeProcessIds = [System.Collections.Generic.List[int]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$topicCreated = $false
$probeEnvironmentNames = @(
    'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD',
    'IDENTITY_JWT_SECRET',
    'TRADE_INTERNAL_SERVICE_TOKEN',
    'PAYMENT_INTERNAL_SERVICE_TOKEN',
    'METRICS_SCRAPE_TOKEN'
)
$previousEnvironment = @{}
$javaExecutable = if ($env:JAVA_HOME -and
    (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    (Get-Command java -CommandType Application -ErrorAction Stop).Source
}
$javaVersionOutput = (& $javaExecutable -version 2>&1 | Out-String).Trim()
$javaVersionLine = $javaVersionOutput -split "\r?\n" |
    Where-Object { $_ -match 'version "' } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($javaVersionLine) -or
    $javaVersionLine -notmatch 'version "17(\.|")') {
    throw "JDK 17 is required, but $javaExecutable reports: $javaVersionOutput"
}

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
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        $values[$name] = $value
    }
    return $values
}

function Remove-ProbeTopic {
    if (-not $script:topicCreated) {
        return
    }
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $script:topic 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Failed to delete RocketMQ probe topic $($script:topic): $($output -join "`n")"
    }
    $topics = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0 -or $topics -contains $script:topic) {
        throw "RocketMQ probe topic remained after cleanup: $($script:topic)"
    }
    $script:topicCreated = $false
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
        [int]$WaitSeconds = 20
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Assert-PortAvailable -Port $Port
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$WaitSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $lastError = 'not attempted'
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) {
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri. Last error: $lastError"
}

function Show-LogTail {
    param([Parameter(Mandatory)][string]$Path)

    if (Test-Path -LiteralPath $Path) {
        Write-Host "--- $Path ---"
        Get-Content -LiteralPath $Path -Tail 80
    }
}

function Invoke-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = docker exec -e "MYSQL_PWD=$script:tradeDbPassword" plainjournal-mysql mysql `
        --skip-column-names --batch `
        "--user=$script:tradeDbUser" `
        "--database=$script:tradeDbName" `
        "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade MySQL command failed.'
    }
    return @($output)
}

function Send-TradeMySql {
    param([Parameter(Mandatory)][string]$Sql)

    $Sql | docker exec -i -e "MYSQL_PWD=$script:tradeDbPassword" plainjournal-mysql mysql `
        --skip-column-names --batch `
        "--user=$script:tradeDbUser" `
        "--database=$script:tradeDbName"
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

function Get-PrometheusCounter {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Metric,
        [Parameter(Mandatory)][string]$Outcome
    )

    $pattern = "(?m)^$([regex]::Escape($Metric))\{(?=[^}]*outcome=`"$([regex]::Escape($Outcome))`")[^}]*\}\s+([0-9.eE+-]+)$"
    $match = [regex]::Match($Content, $pattern)
    if (-not $match.Success) {
        return 0.0
    }
    return [double]::Parse(
        $match.Groups[1].Value,
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture)
}

function Stop-TradeInstances {
    $processIds = @($script:activeProcessIds | ForEach-Object { [int]$_ })
    if ($processIds.Count -gt 0) {
        Write-Host "Stopping Trade probe process IDs: $($processIds -join ', ')"
    }
    foreach ($processId in $processIds) {
        try {
            $current = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($current) {
                Stop-Process -Id $processId -Force -ErrorAction Stop
                Wait-Process -Id $processId -ErrorAction SilentlyContinue
            }
        }
        catch {
            Write-Warning "Failed to stop Trade probe process ${processId}: $($_.Exception.Message)"
        }
    }
    $script:activeProcessIds.Clear()
    foreach ($port in $script:ports) {
        try {
            Wait-PortAvailable -Port $port
        }
        catch {
            Write-Warning "Trade probe port $port did not close cleanly: $($_.Exception.Message)"
        }
    }
}

function Start-TradeInstances {
    param(
        [Parameter(Mandatory)][int]$Count,
        [Parameter(Mandatory)][string]$ScenarioId
    )

    Stop-TradeInstances
    for ($index = 0; $index -lt $Count; $index++) {
        $port = $script:ports[$index]
        Assert-PortAvailable -Port $port
        $publisherId = "$ScenarioId-publisher-$($index + 1)"
        $stdout = Join-Path $script:runDirectory "$ScenarioId-instance-$($index + 1).out.log"
        $stderr = Join-Path $script:runDirectory "$ScenarioId-instance-$($index + 1).err.log"
        $arguments = @(
            '-jar',
            $script:tradeJar,
            "--server.port=$port",
            '--spring.cloud.nacos.discovery.enabled=false',
            '--spring.cloud.nacos.config.enabled=false',
            '--ecommerce.trade.order.recovery-enabled=false',
            '--ecommerce.trade.reconciliation.enabled=false',
            '--ecommerce.trade.payment-consumer.enabled=false',
            '--ecommerce.trade.fulfillment-consumer.enabled=false',
            '--ecommerce.trade.after-sale-fulfillment-consumer.enabled=false',
            '--ecommerce.trade.after-sale-inventory-consumer.enabled=false',
            '--ecommerce.trade.refund-result-consumer.enabled=false',
            # This probe publishes pre-seeded Outbox rows and never allocates an
            # order ID. Keep the experiment focused on Claim/lease fencing instead
            # of making three publisher-only JVMs contend for the local ID worker.
            '--ecommerce.trade.distributed-id.enabled=false',
            '--ecommerce.trade.outbox.enabled=true',
            '--ecommerce.trade.outbox.endpoints=127.0.0.1:18082',
            "--ecommerce.trade.outbox.topic=$($script:topic)",
            '--ecommerce.trade.outbox.fixed-delay=100',
            '--ecommerce.trade.outbox.retry-delay=500ms',
            '--ecommerce.trade.outbox.batch-size=500',
            '--ecommerce.trade.outbox.parallelism=8',
            "--ecommerce.trade.outbox.publisher-id=$publisherId",
            '--ecommerce.trade.outbox.lease-duration=10s',
            '--management.tracing.enabled=false',
            '--management.otlp.tracing.export.enabled=false',
            '--management.endpoints.web.exposure.include=health,metrics,prometheus',
            '--spring.main.banner-mode=off',
            '--logging.level.root=WARN'
        )
        $process = Start-Process -FilePath $script:javaExecutable -ArgumentList $arguments `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
        $script:activeProcessIds.Add($process.Id)
        Write-Host "Started Trade probe instance $($index + 1)/${Count}: pid=$($process.Id), port=$port"
    }

    try {
        for ($index = 0; $index -lt $Count; $index++) {
            Wait-HttpOk -Uri "http://127.0.0.1:$($script:ports[$index])/actuator/health/liveness"
            Write-Host "Trade probe instance is live: port=$($script:ports[$index])"
        }
    }
    catch {
        for ($index = 0; $index -lt $Count; $index++) {
            Show-LogTail -Path (Join-Path $script:runDirectory "$ScenarioId-instance-$($index + 1).out.log")
            Show-LogTail -Path (Join-Path $script:runDirectory "$ScenarioId-instance-$($index + 1).err.log")
        }
        throw
    }
}

function New-ProbeInsertSql {
    param(
        [Parameter(Mandatory)][string]$AggregateType,
        [Parameter(Mandatory)][int]$Count
    )

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.AppendLine(@"
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
     published_at, last_error, created_at, updated_at)
VALUES
"@)
    $aggregateCount = $Count / 2
    $rowIndex = 0
    for ($aggregateIndex = 1; $aggregateIndex -le $aggregateCount; $aggregateIndex++) {
        $aggregateId = "probe-$($AggregateType.Split(':')[-1])-order-$('{0:D5}' -f $aggregateIndex)"
        foreach ($version in @(1, 2)) {
            if ($rowIndex -gt 0) {
                [void]$builder.AppendLine(',')
            }
            $eventId = [Guid]::NewGuid().ToString()
            $payload = "{`"aggregateId`":`"$aggregateId`",`"version`":$version}"
            [void]$builder.Append(
                "('$eventId','M3OutboxProbe','$AggregateType','$aggregateId',$version," +
                "'$payload','PENDING',0,CURRENT_TIMESTAMP(3),NULL,NULL,NULL,NULL,NULL," +
                "CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
            $rowIndex++
        }
    }
    [void]$builder.AppendLine(';')
    return $builder.ToString()
}

function Wait-ProbePublished {
    param(
        [Parameter(Mandatory)][string]$AggregateType,
        [Parameter(Mandatory)][int]$ExpectedCount
    )

    $deadline = (Get-Date).AddSeconds($script:TimeoutSeconds)
    $lastState = ''
    do {
        $lastState = Get-MySqlScalar -Sql @"
SELECT CONCAT(
    SUM(status = 'PUBLISHED'), '|',
    SUM(status = 'PENDING'), '|',
    SUM(status = 'PUBLISHING'), '|',
    SUM(attempts)
)
FROM outbox_event
WHERE aggregate_type = '$AggregateType';
"@
        $parts = $lastState -split '\|'
        if ($parts.Count -eq 4 -and
            [int]$parts[0] -eq $ExpectedCount -and
            [int]$parts[1] -eq 0 -and
            [int]$parts[2] -eq 0) {
            return $parts
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Outbox probe did not converge within $($script:TimeoutSeconds)s: $lastState"
}

function Get-MetricSnapshot {
    param([Parameter(Mandatory)][int]$Count)

    $snapshot = @()
    for ($index = 0; $index -lt $Count; $index++) {
        $port = $script:ports[$index]
        $response = Invoke-WebRequest `
            -Uri "http://127.0.0.1:$port/actuator/prometheus" `
            -Headers @{ 'X-Metrics-Token' = $script:metricsToken } `
            -TimeoutSec 10 `
            -UseBasicParsing
        $content = if ($response.Content -is [byte[]]) {
            [Text.Encoding]::UTF8.GetString($response.Content)
        } else {
            [string]$response.Content
        }
        $snapshot += [pscustomobject]@{
            Port = $port
            Success = Get-PrometheusCounter `
                -Content $content `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'success'
            Failure = Get-PrometheusCounter `
                -Content $content `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'failure'
            StateConflict = Get-PrometheusCounter `
                -Content $content `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'state_conflict'
            StaleRecovered = Get-PrometheusCounter `
                -Content $content `
                -Metric 'ecommerce_outbox_claims_total' `
                -Outcome 'stale_recovered'
            Contended = Get-PrometheusCounter `
                -Content $content `
                -Metric 'ecommerce_outbox_claims_total' `
                -Outcome 'contended'
        }
    }
    return $snapshot
}

function Get-MetricDelta {
    param(
        [Parameter(Mandatory)][object[]]$Before,
        [Parameter(Mandatory)][object[]]$After
    )

    $delta = @()
    for ($index = 0; $index -lt $After.Count; $index++) {
        $delta += [pscustomobject]@{
            Port = $After[$index].Port
            Success = $After[$index].Success - $Before[$index].Success
            Failure = $After[$index].Failure - $Before[$index].Failure
            StateConflict = $After[$index].StateConflict - $Before[$index].StateConflict
            StaleRecovered = $After[$index].StaleRecovered - $Before[$index].StaleRecovered
            Contended = $After[$index].Contended - $Before[$index].Contended
        }
    }
    return $delta
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
    if (-not (Test-Path -LiteralPath $networkCheck)) {
        throw "Network diagnostic script was not found: $networkCheck"
    }
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw 'Local development network preflight failed.'
    }
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker engine is not ready.'
}

foreach ($container in $requiredContainers) {
    docker inspect $container *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Required container does not exist: $container"
    }
    $running = (docker inspect --format '{{.State.Running}}' $container 2>$null) -eq 'true'
    if (-not $running) {
        if (-not $StartRequiredContainers) {
            throw "Required container is stopped: $container. Use -StartRequiredContainers to start it."
        }
        docker start $container | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to start required container: $container"
        }
        $startedContainers.Add($container)
    }
}
Write-Host "Required containers are running: $($requiredContainers -join ', ')"

$mysqlDeadline = (Get-Date).AddSeconds(60)
do {
    $mysqlHealth = docker inspect --format '{{.State.Health.Status}}' plainjournal-mysql 2>$null
    if ($mysqlHealth -eq 'healthy') {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $mysqlDeadline)
if ($mysqlHealth -ne 'healthy') {
    throw "MySQL did not become healthy: $mysqlHealth"
}

$settings = Read-DotEnv -Path $envFile
$requiredSettings = @(
    'MYSQL_PORT',
    'TRADE_DB_NAME',
    'TRADE_DB_USER',
    'TRADE_DB_PASSWORD'
)
foreach ($name in $requiredSettings) {
    if (-not $settings.ContainsKey($name) -or -not $settings[$name]) {
        throw "Missing required value in deploy/docker/.env: $name"
    }
}

$script:tradeDbName = $settings['TRADE_DB_NAME']
$script:tradeDbUser = $settings['TRADE_DB_USER']
$script:tradeDbPassword = $settings['TRADE_DB_PASSWORD']
$script:metricsToken = 'm3-probe-metrics-token-with-at-least-32-characters'

foreach ($name in $probeEnvironmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
[Environment]::SetEnvironmentVariable(
    'SPRING_DATASOURCE_URL',
    "jdbc:mysql://127.0.0.1:$($settings['MYSQL_PORT'])/$($settings['TRADE_DB_NAME'])" +
    '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false',
    'Process')
[Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_USERNAME', $script:tradeDbUser, 'Process')
[Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD', $script:tradeDbPassword, 'Process')
[Environment]::SetEnvironmentVariable(
    'IDENTITY_JWT_SECRET',
    'm3-probe-jwt-secret-with-at-least-32-characters',
    'Process')
[Environment]::SetEnvironmentVariable(
    'TRADE_INTERNAL_SERVICE_TOKEN',
    'm3-probe-trade-internal-token-with-at-least-32-characters',
    'Process')
[Environment]::SetEnvironmentVariable(
    'PAYMENT_INTERNAL_SERVICE_TOKEN',
    'm3-probe-payment-internal-token-with-at-least-32-characters',
    'Process')
[Environment]::SetEnvironmentVariable('METRICS_SCRAPE_TOKEN', $script:metricsToken, 'Process')

$verifiedAt = (Get-Date).ToUniversalTime()
$gitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
$evidencePath = Join-Path $runDirectory 'trade-outbox-multi-instance.json'

try {
    $unrelatedBacklog = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND aggregate_type NOT LIKE '$aggregatePrefix%';
"@)
    if ($unrelatedBacklog -ne 0) {
        throw "Trade contains $unrelatedBacklog unrelated unpublished Outbox events; refusing to mix a probe with business backlog."
    }

    Send-TradeMySql -Sql "DELETE FROM outbox_event WHERE aggregate_type LIKE '$aggregatePrefix%';"

    $topicCommand = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateTopic " +
        "-n plainjournal-rocketmq-namesrv:9876 -c EcommerceCluster -t $topic -r 8 -w 8"
    $topicResult = docker exec plainjournal-rocketmq-broker sh -lc $topicCommand
    if ($LASTEXITCODE -ne 0 -or ($topicResult -join "`n") -notmatch 'success') {
        throw "Failed to create or update RocketMQ probe topic: $($topicResult -join ' ')"
    }
    $script:topicCreated = $true

    foreach ($instanceCount in @(1, 2, 3)) {
        $scenarioId = "m3-$instanceCount-$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff'))"
        $aggregateType = "$aggregatePrefix$scenarioId"
        Write-Host "Starting $instanceCount-instance equivalence run with $EventCount events."
        Start-TradeInstances -Count $instanceCount -ScenarioId $scenarioId

        $flywayVersion = Get-MySqlScalar -Sql @"
SELECT version
FROM flyway_schema_history
WHERE success = 1
ORDER BY installed_rank DESC
LIMIT 1;
"@
        $leaseMigrationCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM flyway_schema_history
WHERE version = '10'
  AND success = 1;
"@)
        $leaseColumnCount = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'outbox_event'
  AND column_name IN ('claim_owner', 'claim_until');
"@)
        if ($leaseMigrationCount -ne 1 -or $leaseColumnCount -ne 2) {
            throw (
                "Trade schema is missing the M3 lease migration: " +
                "latestVersion=$flywayVersion migrationCount=$leaseMigrationCount " +
                "columns=$leaseColumnCount")
        }
        Write-Host "Trade schema includes Flyway V10 lease migration; latest version is V$flywayVersion."

        $beforeMetrics = @(Get-MetricSnapshot -Count $instanceCount)
        $insertSql = New-ProbeInsertSql -AggregateType $aggregateType -Count $EventCount
        $timer = [Diagnostics.Stopwatch]::StartNew()
        Send-TradeMySql -Sql $insertSql
        Write-Host "Inserted $EventCount Outbox probe events for $instanceCount instance(s)."
        $finalState = Wait-ProbePublished -AggregateType $aggregateType -ExpectedCount $EventCount
        $timer.Stop()
        Write-Host "Outbox probe converged for $instanceCount instance(s) in $([math]::Round($timer.Elapsed.TotalMilliseconds, 3)) ms."
        $afterMetrics = @(Get-MetricSnapshot -Count $instanceCount)
        $metricDelta = @(Get-MetricDelta -Before $beforeMetrics -After $afterMetrics)

        $successCount = [int](($metricDelta | Measure-Object -Property Success -Sum).Sum)
        $failureCount = [int](($metricDelta | Measure-Object -Property Failure -Sum).Sum)
        $stateConflictCount = [int](($metricDelta | Measure-Object -Property StateConflict -Sum).Sum)
        $contendedCount = [int](($metricDelta | Measure-Object -Property Contended -Sum).Sum)
        $activePublisherCount = @($metricDelta | Where-Object Success -gt 0).Count
        $retryBudget = [math]::Max(5, [math]::Ceiling($EventCount * 0.005))
        $contentionBudget = [math]::Max(5, $instanceCount * 2)
        $orderViolations = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM outbox_event first_event
JOIN outbox_event second_event
  ON second_event.aggregate_type = first_event.aggregate_type
 AND second_event.aggregate_id = first_event.aggregate_id
 AND second_event.aggregate_version = 2
WHERE first_event.aggregate_type = '$aggregateType'
  AND first_event.aggregate_version = 1
  AND second_event.published_at < first_event.published_at;
"@)
        $duplicateDatabaseIds = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM (
    SELECT id
    FROM outbox_event
    WHERE aggregate_type = '$aggregateType'
    GROUP BY id
    HAVING COUNT(*) > 1
) duplicates;
"@)
        $deadlockEvidence = @()
        for ($index = 0; $index -lt $instanceCount; $index++) {
            $stdout = Join-Path $runDirectory "$scenarioId-instance-$($index + 1).out.log"
            $stderr = Join-Path $runDirectory "$scenarioId-instance-$($index + 1).err.log"
            foreach ($path in @($stdout, $stderr)) {
                if (Test-Path -LiteralPath $path) {
                    $deadlockEvidence += @(Select-String `
                        -Path $path `
                        -Pattern 'Deadlock found when trying to get lock' `
                        -SimpleMatch)
                }
            }
        }

        if ($successCount -ne $EventCount -or
            $failureCount -ne [int]$finalState[3] -or
            $failureCount -gt $retryBudget -or
            $stateConflictCount -ne 0 -or
            $contendedCount -gt $contentionBudget -or
            $activePublisherCount -ne $instanceCount -or
            $orderViolations -ne 0 -or
            $duplicateDatabaseIds -ne 0 -or
            $deadlockEvidence.Count -ne 0) {
            throw ("M3 $instanceCount-instance assertions failed: " +
                "success=$successCount failure=$failureCount stateConflict=$stateConflictCount " +
                "contended=$contendedCount activePublishers=$activePublisherCount " +
                "orderViolations=$orderViolations duplicateDatabaseIds=$duplicateDatabaseIds " +
                "attempts=$($finalState[3]) deadlockLogs=$($deadlockEvidence.Count)")
        }

        $results.Add([pscustomobject]@{
            InstanceCount = $instanceCount
            EventCount = $EventCount
            AggregateCount = $EventCount / 2
            ElapsedMilliseconds = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
            ThroughputEventsPerSecond = [math]::Round($EventCount / $timer.Elapsed.TotalSeconds, 3)
            Published = [int]$finalState[0]
            Pending = [int]$finalState[1]
            Publishing = [int]$finalState[2]
            Attempts = [int]$finalState[3]
            RetryBudget = $retryBudget
            ContendedClaims = $contendedCount
            ContentionBudget = $contentionBudget
            OrderViolations = $orderViolations
            DuplicateDatabaseEventIds = $duplicateDatabaseIds
            ActivePublisherCount = $activePublisherCount
            PublisherMetrics = $metricDelta
        })

        if ($instanceCount -eq 3) {
            $deadAggregateType = "$aggregatePrefix$scenarioId-dead"
            $deadEventId = [Guid]::NewGuid().ToString()
            $deadAggregateId = "probe-$scenarioId-dead-order"
            $beforeRecovery = @(Get-MetricSnapshot -Count $instanceCount)
            Send-TradeMySql -Sql @"
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
     published_at, last_error, created_at, updated_at)
VALUES
    ('$deadEventId', 'M3OutboxProbe', '$deadAggregateType', '$deadAggregateId', 1,
     '{"aggregateId":"$deadAggregateId","version":1}',
     'PUBLISHING', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3),
     'dead-publisher', TIMESTAMPADD(SECOND, 2, CURRENT_TIMESTAMP(3)),
     NULL, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
"@
            $recoveryTimer = [Diagnostics.Stopwatch]::StartNew()
            Write-Host 'Inserted one simulated dead-owner claim with a 2-second lease.'
            $deadState = Wait-ProbePublished -AggregateType $deadAggregateType -ExpectedCount 1
            $recoveryTimer.Stop()
            Write-Host "Expired claim recovered in $([math]::Round($recoveryTimer.Elapsed.TotalMilliseconds, 3)) ms."
            $afterRecovery = @(Get-MetricSnapshot -Count $instanceCount)
            $recoveryDelta = @(Get-MetricDelta -Before $beforeRecovery -After $afterRecovery)
            $staleRecovered = [int](($recoveryDelta | Measure-Object -Property StaleRecovered -Sum).Sum)
            $recoverySuccess = [int](($recoveryDelta | Measure-Object -Property Success -Sum).Sum)
            $recoveryConflicts = [int](($recoveryDelta | Measure-Object -Property StateConflict -Sum).Sum)
            if ($staleRecovered -ne 1 -or $recoverySuccess -ne 1 -or $recoveryConflicts -ne 0) {
                throw ("Expired lease recovery assertions failed: " +
                    "staleRecovered=$staleRecovered success=$recoverySuccess conflicts=$recoveryConflicts")
            }
            $results.Add([pscustomobject]@{
                Scenario = 'expired-lease-recovery'
                InstanceCount = 3
                EventCount = 1
                ElapsedMilliseconds = [math]::Round($recoveryTimer.Elapsed.TotalMilliseconds, 3)
                Published = [int]$deadState[0]
                StaleClaimsRecovered = $staleRecovered
                StateConflicts = $recoveryConflicts
                PublisherMetrics = $recoveryDelta
            })
        }

        Stop-TradeInstances
        Send-TradeMySql -Sql "DELETE FROM outbox_event WHERE aggregate_type LIKE '$aggregatePrefix%';"
    }

    Remove-ProbeTopic
    $evidence = [pscustomobject]@{
        VerifiedAtUtc = $verifiedAt.ToString('O')
        GitHead = $gitHead
        Environment = [pscustomobject]@{
            MySql = 'mysql:8.4.10'
            RocketMq = 'apache/rocketmq:5.3.2'
            Java = $javaVersionLine
            MachineModel = 'single-host scaled experiment'
        }
        Configuration = [pscustomobject]@{
            EventCountPerEquivalenceRun = $EventCount
            AggregateDepth = 2
            PublisherParallelism = 8
            BatchSize = 500
            FixedDelayMilliseconds = 100
            LeaseSeconds = 10
            Topic = $topic
        }
        Results = $results
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $evidencePath -Encoding utf8
    Write-Host "Trade Outbox 1/2/3 instance verification passed."
    $results | Format-Table -AutoSize
    Write-Host "Evidence: $evidencePath"
}
finally {
    Stop-TradeInstances
    try {
        Send-TradeMySql -Sql "DELETE FROM outbox_event WHERE aggregate_type LIKE '$aggregatePrefix%';"
    }
    catch {
        Write-Warning "Probe-row cleanup failed: $($_.Exception.Message)"
    }
    try {
        Remove-ProbeTopic
    }
    catch {
        Write-Warning "Probe-topic cleanup failed: $($_.Exception.Message)"
    }
    foreach ($name in $probeEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    if (-not $KeepContainersRunning) {
        foreach ($container in @($startedContainers)) {
            docker stop $container | Out-Null
        }
    }
}
