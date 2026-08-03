[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [switch]$AllowPartialPublisherParticipation,
    [switch]$KeepRunning,
    [ValidateRange(100, 5000)][int]$EventCount = 1000,
    [ValidateRange(60, 300)][int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($EventCount % 2 -ne 0) {
    throw 'EventCount must be even because every probe aggregate contains versions 1 and 2.'
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$envFile = Join-Path $composeDirectory '.env'
$tradeJar = Join-Path $PSScriptRoot 'services\trade-service\target\trade-service-0.1.0-SNAPSHOT.jar'
$runDirectory = Join-Path $PSScriptRoot '.run'
$evidencePath = Join-Path $runDirectory 'trade-container-multi-instance.json'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$probeTopic = 'plainjournal-m3-container-probe-v1'
$aggregatePrefix = 'M3ContainerProbe:'
$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy'
)
$startedContainers = [System.Collections.Generic.List[string]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$probeTopicCreated = $false
$previousProbeTopic = [Environment]::GetEnvironmentVariable('TRADE_CONTAINER_OUTBOX_TOPIC', 'Process')

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

function Remove-ProbeTopic {
    if (-not $script:probeTopicCreated) {
        return
    }
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $script:probeTopic 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Failed to delete RocketMQ probe topic $($script:probeTopic): $($output -join "`n")"
    }
    $topics = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0 -or $topics -contains $script:probeTopic) {
        throw "RocketMQ probe topic remained after cleanup: $($script:probeTopic)"
    }
    $script:probeTopicCreated = $false
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
            Wait-TcpPort -HostName '127.0.0.1' -Port 18082 -WaitSeconds 30
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

function Get-ContainerIdentity {
    param([Parameter(Mandatory)][string]$ContainerId)

    $name = (docker inspect --format '{{.Name}}' $ContainerId).TrimStart('/')
    $uid = (docker exec $ContainerId id -u).Trim()
    $identityLines = @(docker exec $ContainerId sh -lc @'
java_pid=$(pidof java)
tr "\000" "\n" < /proc/$java_pid/environ |
    grep -E "^(SERVICE_IP|SERVICE_INSTANCE_ID|TRADE_OUTBOX_PUBLISHER_ID)="
'@)
    $identity = @{}
    foreach ($line in $identityLines) {
        $pair = $line -split '=', 2
        $identity[$pair[0]] = $pair[1]
    }
    $health = docker inspect --format '{{.State.Health.Status}}' $ContainerId
    return [pscustomobject]@{
        ContainerId = $ContainerId
        Name = $name
        Uid = $uid
        Ip = $identity['SERVICE_IP']
        InstanceId = $identity['SERVICE_INSTANCE_ID']
        PublisherId = $identity['TRADE_OUTBOX_PUBLISHER_ID']
        Health = $health
    }
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
        throw "Expected one scalar row, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function New-ProbeInsertSql {
    param(
        [Parameter(Mandatory)][string]$AggregateType,
        [Parameter(Mandatory)][int]$Count
    )

    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine(@"
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
     published_at, last_error, created_at, updated_at)
VALUES
"@)
    $rowIndex = 0
    for ($aggregateIndex = 1; $aggregateIndex -le ($Count / 2); $aggregateIndex++) {
        $aggregateId = "container-order-$('{0:D5}' -f $aggregateIndex)"
        foreach ($version in @(1, 2)) {
            if ($rowIndex -gt 0) {
                [void]$builder.AppendLine(',')
            }
            $eventId = [Guid]::NewGuid().ToString()
            $payload = "{`"aggregateId`":`"$aggregateId`",`"version`":$version}"
            [void]$builder.Append(
                "('$eventId','M3ContainerProbe','$AggregateType','$aggregateId',$version," +
                "'$payload','PENDING',0,CURRENT_TIMESTAMP(3),NULL,NULL,NULL,NULL,NULL," +
                "CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
            $rowIndex++
        }
    }
    [void]$builder.AppendLine(';')
    return $builder.ToString()
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

function Get-PublisherMetrics {
    param([Parameter(Mandatory)][object[]]$Containers)

    $metrics = @()
    foreach ($container in $Containers) {
        $content = docker exec $container.ContainerId wget `
            -q `
            -O - `
            "--header=X-Metrics-Token: $($script:settings['METRICS_SCRAPE_TOKEN'])" `
            http://127.0.0.1:18104/actuator/prometheus
        if ($LASTEXITCODE -ne 0) {
            throw "Metrics scrape failed: $($container.Name)"
        }
        $metrics += [pscustomobject]@{
            Name = $container.Name
            Success = Get-PrometheusCounter `
                -Content ($content -join "`n") `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'success'
            Failure = Get-PrometheusCounter `
                -Content ($content -join "`n") `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'failure'
            StateConflict = Get-PrometheusCounter `
                -Content ($content -join "`n") `
                -Metric 'ecommerce_outbox_publications_total' `
                -Outcome 'state_conflict'
        }
    }
    return $metrics
}

function Get-MetricDelta {
    param(
        [Parameter(Mandatory)][object[]]$Before,
        [Parameter(Mandatory)][object[]]$After
    )

    $delta = @()
    foreach ($afterMetric in $After) {
        $beforeMetric = $Before | Where-Object Name -eq $afterMetric.Name | Select-Object -First 1
        $delta += [pscustomobject]@{
            Name = $afterMetric.Name
            Success = $afterMetric.Success - $beforeMetric.Success
            Failure = $afterMetric.Failure - $beforeMetric.Failure
            StateConflict = $afterMetric.StateConflict - $beforeMetric.StateConflict
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

$existingTradeContainers = @(Invoke-Compose -Arguments @('ps', '-aq', 'trade-service'))
if (@($existingTradeContainers | Where-Object { $_ }).Count -gt 0) {
    throw 'Trade Compose containers already exist. Remove or stop the existing M3 experiment before running this script.'
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
    Wait-Middleware

    [Environment]::SetEnvironmentVariable(
        'TRADE_CONTAINER_OUTBOX_TOPIC',
        $probeTopic,
        'Process')

    if (-not $SkipBuild) {
        Invoke-Compose -Arguments @('build', 'trade-service')
    }

    $nacosHeaders = Get-NacosHeaders
    foreach ($scale in @(1, 2, 3)) {
        Invoke-Compose -Arguments @(
            'up', '-d', '--no-deps', '--scale', "trade-service=$scale", 'trade-service'
        )
        $containerIds = @(Wait-TradeContainers -ExpectedCount $scale)
        $containers = @($containerIds | ForEach-Object { Get-ContainerIdentity -ContainerId $_ })
        $nacosInstances = @(Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount $scale)

        if (@($containers | Where-Object Uid -ne '10001').Count -ne 0) {
            throw "Scale $scale contains a root or unexpected runtime UID."
        }
        if (@($containers.Ip | Select-Object -Unique).Count -ne $scale) {
            throw "Scale $scale container IPs are not unique."
        }
        if (@($containers.PublisherId | Select-Object -Unique).Count -ne $scale) {
            throw "Scale $scale Outbox publisher IDs are not unique."
        }

        $results.Add([pscustomobject]@{
            Scenario = 'scale-readiness'
            InstanceCount = $scale
            Containers = $containers
            NacosEndpoints = @($nacosInstances | ForEach-Object { "$($_.ip):$($_.port)" })
        })
    }

    $unrelatedBacklog = [int](Get-MySqlScalar -Sql @"
SELECT COUNT(*)
FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND aggregate_type NOT LIKE '$aggregatePrefix%';
"@)
    if ($unrelatedBacklog -ne 0) {
        throw "Trade contains $unrelatedBacklog unrelated unpublished Outbox events."
    }

    Send-TradeMySql -Sql "DELETE FROM outbox_event WHERE aggregate_type LIKE '$aggregatePrefix%';"
    $topicCommand = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateTopic " +
        "-n plainjournal-rocketmq-namesrv:9876 -c EcommerceCluster -t $probeTopic -r 8 -w 8"
    $topicResult = docker exec plainjournal-rocketmq-broker sh -lc $topicCommand
    if ($LASTEXITCODE -ne 0 -or ($topicResult -join "`n") -notmatch 'success') {
        throw "Failed to create or update RocketMQ probe topic: $($topicResult -join ' ')"
    }
    $script:probeTopicCreated = $true

    $containers = @((Get-TradeContainerIds) | ForEach-Object { Get-ContainerIdentity -ContainerId $_ })
    $beforeMetrics = @(Get-PublisherMetrics -Containers $containers)
    $aggregateType = "$aggregatePrefix$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff'))"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Send-TradeMySql -Sql (New-ProbeInsertSql -AggregateType $aggregateType -Count $EventCount)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = Get-MySqlScalar -Sql @"
SELECT CONCAT(
    SUM(status = 'PUBLISHED'), '|',
    SUM(status = 'PENDING'), '|',
    SUM(status = 'PUBLISHING'), '|',
    SUM(attempts)
)
FROM outbox_event
WHERE aggregate_type = '$aggregateType';
"@
        $parts = $state -split '\|'
        if ($parts.Count -eq 4 -and
            [int]$parts[0] -eq $EventCount -and
            [int]$parts[1] -eq 0 -and
            [int]$parts[2] -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    $timer.Stop()
    if ([int]$parts[0] -ne $EventCount -or [int]$parts[1] -ne 0 -or [int]$parts[2] -ne 0) {
        throw "Containerized Outbox probe did not converge: $state"
    }

    $afterMetrics = @(Get-PublisherMetrics -Containers $containers)
    $metricDelta = @(Get-MetricDelta -Before $beforeMetrics -After $afterMetrics)
    $success = [int](($metricDelta | Measure-Object Success -Sum).Sum)
    $failure = [int](($metricDelta | Measure-Object Failure -Sum).Sum)
    $stateConflict = [int](($metricDelta | Measure-Object StateConflict -Sum).Sum)
    $activePublishers = @($metricDelta | Where-Object Success -gt 0).Count
    $requiredActivePublishers = if ($AllowPartialPublisherParticipation) { 1 } else { 3 }
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
    if ($success -ne $EventCount -or
        $failure -ne 0 -or
        $stateConflict -ne 0 -or
        $activePublishers -lt $requiredActivePublishers -or
        $orderViolations -ne 0 -or
        [int]$parts[3] -ne 0) {
        throw ("Containerized Outbox assertions failed: success=$success failure=$failure " +
            "stateConflict=$stateConflict activePublishers=$activePublishers " +
            "requiredActivePublishers=$requiredActivePublishers " +
            "orderViolations=$orderViolations attempts=$($parts[3])")
    }
    $results.Add([pscustomobject]@{
        Scenario = 'containerized-outbox'
        InstanceCount = 3
        EventCount = $EventCount
        ElapsedMilliseconds = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
        ThroughputEventsPerSecond = [math]::Round(
            $EventCount / $timer.Elapsed.TotalSeconds,
            3)
        RequiredActivePublisherCount = $requiredActivePublishers
        Attempts = [int]$parts[3]
        OrderViolations = $orderViolations
        PublisherMetrics = $metricDelta
    })

    $stopTarget = $containers | Sort-Object Name | Select-Object -Last 1
    $nacosBeforeStop = @(Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount 3)
    $stopTimer = [Diagnostics.Stopwatch]::StartNew()
    docker stop --time 30 $stopTarget.ContainerId | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop $($stopTarget.Name)"
    }
    $stopTimer.Stop()
    $exitCode = [int](docker inspect --format '{{.State.ExitCode}}' $stopTarget.ContainerId)
    $nacosAfterStop = @(Wait-NacosInstanceCount -Headers $nacosHeaders -ExpectedCount 2)
    $stopLogs = (docker logs $stopTarget.ContainerId 2>&1) -join "`n"
    $knownNacosShutdownIssue = $stopLogs.Contains(
        'Cannot read field "sharePublisher" because "com.alibaba.nacos.common.notify.NotifyCenter.INSTANCE" is null')

    if ($exitCode -eq 137 -or
        -not $stopLogs.Contains('Graceful shutdown complete') -or
        -not $stopLogs.Contains('De-registration finished.')) {
        throw ("Graceful stop assertions failed: exitCode=$exitCode " +
            "tomcat=$($stopLogs.Contains('Graceful shutdown complete')) " +
            "nacos=$($stopLogs.Contains('De-registration finished.'))")
    }
    $results.Add([pscustomobject]@{
        Scenario = 'graceful-stop'
        Container = $stopTarget.Name
        StopMilliseconds = [math]::Round($stopTimer.Elapsed.TotalMilliseconds, 3)
        ExitCode = $exitCode
        NacosBefore = $nacosBeforeStop.Count
        NacosAfter = $nacosAfterStop.Count
        TomcatGracefulShutdown = $true
        NacosDeregistered = $true
        KnownNacosNotifyCenterShutdownIssue = $knownNacosShutdownIssue
    })

    Remove-ProbeTopic
    $image = docker image inspect plainjournal/trade-service:local | ConvertFrom-Json
    $evidence = [pscustomobject]@{
        VerifiedAtUtc = (Get-Date).ToUniversalTime().ToString('O')
        GitHead = (git -C $repositoryRoot rev-parse HEAD).Trim()
        Image = [pscustomobject]@{
            Reference = 'plainjournal/trade-service:local'
            Id = $image.Id
            SizeBytes = $image.Size
            RuntimeUser = $image.Config.User
            StopSignal = $image.Config.StopSignal
        }
        Environment = [pscustomobject]@{
            MySql = 'mysql:8.4.10'
            Nacos = 'nacos/nacos-server:v3.2.2'
            RocketMq = 'apache/rocketmq:5.3.2'
            Java = 'eclipse-temurin:17-jre-alpine'
        }
        Results = $results
    }
    $evidence | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8

    Write-Host 'Trade container 1/2/3 instance verification passed.'
    $results | Format-Table -AutoSize
    Write-Host "Evidence: $evidencePath"
}
finally {
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
    try {
        $tradeContainers = @(Invoke-Compose -Arguments @('ps', '-aq', 'trade-service'))
        if (@($tradeContainers | Where-Object { $_ }).Count -gt 0 -and -not $KeepRunning) {
            Invoke-Compose -Arguments @('rm', '-sf', 'trade-service')
        }
    }
    catch {
        Write-Warning "Trade container cleanup failed: $($_.Exception.Message)"
    }
    [Environment]::SetEnvironmentVariable(
        'TRADE_CONTAINER_OUTBOX_TOPIC',
        $previousProbeTopic,
        'Process')
    if (-not $KeepRunning) {
        foreach ($container in @($startedContainers)) {
            docker stop $container | Out-Null
        }
    }
}
