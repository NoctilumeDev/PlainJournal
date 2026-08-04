#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [ValidateRange(60, 600)]
    [int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$composeEnvFile = Join-Path $composeDirectory '.env'
$bootstrapScript = Join-Path $composeDirectory 'bootstrap-resources.ps1'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\m7-trade-sharding-$timestamp"
$evidencePath = Join-Path $runDirectory 'verification.json'
$probePrefix = "M7SHARD$([Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant())"
$mqResourceSuffix = $probePrefix.ToLowerInvariant()
$mqTopics = [ordered]@{
    order = "m7-trade-order-$mqResourceSuffix"
    payment = "m7-trade-payment-$mqResourceSuffix"
    logistics = "m7-trade-logistics-$mqResourceSuffix"
    inventory = "m7-trade-inventory-$mqResourceSuffix"
}
$mqConsumerGroups = [ordered]@{
    inventoryOrder = "m7-trade-inventory-order-$mqResourceSuffix"
    inventoryReturn = "m7-trade-inventory-return-$mqResourceSuffix"
    marketingOrder = "m7-trade-marketing-order-$mqResourceSuffix"
    tradePayment = "m7-trade-trade-payment-$mqResourceSuffix"
    tradeFulfillment = "m7-trade-trade-fulfillment-$mqResourceSuffix"
    tradeAfterSaleFulfillment = "m7-trade-trade-after-sale-fulfillment-$mqResourceSuffix"
    tradeAfterSaleInventory = "m7-trade-trade-after-sale-inventory-$mqResourceSuffix"
    tradeRefund = "m7-trade-trade-refund-$mqResourceSuffix"
    paymentRefund = "m7-trade-payment-refund-$mqResourceSuffix"
    fulfillmentOrder = "m7-trade-fulfillment-order-$mqResourceSuffix"
    fulfillmentAfterSale = "m7-trade-fulfillment-after-sale-$mqResourceSuffix"
}
$servicePorts = [ordered]@{
    identity = 18101
    catalog = 18102
    inventory = 18103
    trade = 18104
    payment = 18105
    fulfillment = 18106
    marketing = 18107
}
$serviceJars = @{
    identity = Join-Path $backendRoot 'services\identity-service\target\identity-service-1.0.2-SNAPSHOT.jar'
    catalog = Join-Path $backendRoot 'services\catalog-service\target\catalog-service-1.0.2-SNAPSHOT.jar'
    inventory = Join-Path $backendRoot 'services\inventory-service\target\inventory-service-1.0.2-SNAPSHOT.jar'
    trade = Join-Path $backendRoot 'services\trade-service\target\trade-service-1.0.2-SNAPSHOT.jar'
    payment = Join-Path $backendRoot 'services\payment-service\target\payment-service-1.0.2-SNAPSHOT.jar'
    fulfillment = Join-Path $backendRoot 'services\fulfillment-service\target\fulfillment-service-1.0.2-SNAPSHOT.jar'
    marketing = Join-Path $backendRoot 'services\marketing-service\target\marketing-service-1.0.2-SNAPSHOT.jar'
}
$settings = @{}
$processes = [System.Collections.Generic.List[object]]::new()
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$executionError = $null
$verificationSucceeded = $false
$shardContainerExistedBefore = $false
$shardContainerRunningBefore = $false
$shardSchemasCreated = $false
$shardUsersCreated = $false
$warehouseCreated = $false
$users = [System.Collections.Generic.List[object]]::new()
$orders = [System.Collections.Generic.List[object]]::new()
$allEventIds = [System.Collections.Generic.HashSet[string]]::new()
$mqTopicsCreated = [System.Collections.Generic.List[string]]::new()
$mqConsumerGroupsCreated = [System.Collections.Generic.List[string]]::new()
$warehouseId = $null
$productId = $null
$skuId = $null
$paymentNo = $null
$refundNo = $null
$fulfillmentNo = $null
$returnReceiptNo = $null
$afterSaleNo = $null
$chainUser = $null
$otherUser = $null
$chainOrder = $null
$otherOrder = $null
$phaseACompletedAtUtc = $null
$phaseBCompletedAtUtc = $null
$routingEvidence = [ordered]@{}
$lifecycleEvidence = [ordered]@{}
$cleanupEvidence = [ordered]@{
    servicesStopped = $false
    probeDataRemoved = $false
    shardSchemasRemoved = $false
    shardUsersRemoved = $false
    shardContainerRestored = $false
    rocketMqResourcesRemoved = $false
}

function Import-DotEnv {
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
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    return $values
}

function ConvertTo-MySqlLiteral {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value) {
        return 'NULL'
    }
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function ConvertTo-MySqlList {
    param([object[]]$Values)

    $items = @($Values | Where-Object { $null -ne $_ -and "$_".Length -gt 0 } |
        ForEach-Object { ConvertTo-MySqlLiteral -Value "$_" })
    if ($items.Count -eq 0) {
        return "''"
    }
    return $items -join ','
}

function Invoke-ContainerMySql {
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [string]$Database,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @(
            'exec',
            '-e', "MYSQL_PWD=$Password",
            $Container,
            'mysql',
            "--user=$User",
            '--default-character-set=utf8mb4',
            '--batch',
            '--skip-column-names')) {
        $arguments.Add($argument)
    }
    if ($Database) {
        $arguments.Add("--database=$Database")
    }
    $arguments.Add("--execute=$Sql")

    $output = @(& docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed in $Container`: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Get-MySqlScalar {
    param(
        [Parameter(Mandatory)][ValidateSet('root0', 'root1', 'shard0', 'shard1',
            'identity', 'catalog', 'inventory', 'payment', 'fulfillment', 'marketing')]
        [string]$Target,
        [Parameter(Mandatory)][string]$Sql
    )

    $connection = switch ($Target) {
        'root0' {
            @{ Container = 'plainjournal-mysql'; User = 'root';
                Password = $script:settings['MYSQL_ROOT_PASSWORD']; Database = $null }
        }
        'root1' {
            @{ Container = 'plainjournal-mysql-trade-shard-1'; User = 'root';
                Password = $script:settings['MYSQL_ROOT_PASSWORD']; Database = $null }
        }
        'shard0' {
            @{ Container = 'plainjournal-mysql'; User = $script:settings['TRADE_SHARD_DB_USER'];
                Password = $script:settings['TRADE_SHARD_DB_PASSWORD'];
                Database = $script:settings['TRADE_SHARD_0_DB_NAME'] }
        }
        'shard1' {
            @{ Container = 'plainjournal-mysql-trade-shard-1'; User = $script:settings['TRADE_SHARD_DB_USER'];
                Password = $script:settings['TRADE_SHARD_DB_PASSWORD'];
                Database = $script:settings['TRADE_SHARD_1_DB_NAME'] }
        }
        default {
            $prefix = $Target.ToUpperInvariant()
            @{ Container = 'plainjournal-mysql'; User = $script:settings["${prefix}_DB_USER"];
                Password = $script:settings["${prefix}_DB_PASSWORD"];
                Database = $script:settings["${prefix}_DB_NAME"] }
        }
    }
    $rows = @(Invoke-ContainerMySql @connection -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar result from $Target, received $($rows.Count)."
    }
    return $rows[0].ToString().Trim()
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory)][ValidateSet('root0', 'root1', 'shard0', 'shard1',
            'identity', 'catalog', 'inventory', 'payment', 'fulfillment', 'marketing')]
        [string]$Target,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $connection = switch ($Target) {
        'root0' {
            @{ Container = 'plainjournal-mysql'; User = 'root';
                Password = $script:settings['MYSQL_ROOT_PASSWORD']; Database = $null }
        }
        'root1' {
            @{ Container = 'plainjournal-mysql-trade-shard-1'; User = 'root';
                Password = $script:settings['MYSQL_ROOT_PASSWORD']; Database = $null }
        }
        'shard0' {
            @{ Container = 'plainjournal-mysql'; User = $script:settings['TRADE_SHARD_DB_USER'];
                Password = $script:settings['TRADE_SHARD_DB_PASSWORD'];
                Database = $script:settings['TRADE_SHARD_0_DB_NAME'] }
        }
        'shard1' {
            @{ Container = 'plainjournal-mysql-trade-shard-1'; User = $script:settings['TRADE_SHARD_DB_USER'];
                Password = $script:settings['TRADE_SHARD_DB_PASSWORD'];
                Database = $script:settings['TRADE_SHARD_1_DB_NAME'] }
        }
        default {
            $prefix = $Target.ToUpperInvariant()
            @{ Container = 'plainjournal-mysql'; User = $script:settings["${prefix}_DB_USER"];
                Password = $script:settings["${prefix}_DB_PASSWORD"];
                Database = $script:settings["${prefix}_DB_NAME"] }
        }
    }
    return @(Invoke-ContainerMySql @connection -Sql $Sql -AllowFailure:$AllowFailure)
}

function Assert-PortFree {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Wait-PortFree {
    param([Parameter(Mandatory)][int]$Port)

    $deadline = (Get-Date).AddSeconds(15)
    do {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Assert-PortFree -Port $Port
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'not inspected'
    do {
        $raw = docker inspect $Container 2>$null
        if ($LASTEXITCODE -eq 0) {
            $inspect = @($raw | ConvertFrom-Json)[0]
            $healthProperty = $inspect.State.PSObject.Properties['Health']
            $health = if ($healthProperty) { $healthProperty.Value.Status } else { $null }
            $lastState = "status=$($inspect.State.Status), health=$health"
            if ($inspect.State.Status -eq 'running' -and
                    ($null -eq $health -or $health -eq 'healthy')) {
                return
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Container. Last state: $lastState"
}

function New-IsolatedMqTopic {
    param([Parameter(Mandatory)][string]$Topic)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $Topic `
            -r 4 `
            -w 4 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Unable to create isolated RocketMQ topic ${Topic}: $($output -join "`n")"
    }
    $mqTopicsCreated.Add($Topic)
}

function New-IsolatedMqConsumerGroup {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateSubGroup `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -g $ConsumerGroup `
            -s true `
            -m true `
            -d true `
            -o false `
            -q 1 `
            -r 16 `
            -i 0 `
            -w 1 `
            -a true 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Unable to create isolated RocketMQ consumer group ${ConsumerGroup}: $($output -join "`n")"
    }
    $mqConsumerGroupsCreated.Add($ConsumerGroup)
}

function Initialize-IsolatedMessaging {
    foreach ($topic in $mqTopics.Values) {
        New-IsolatedMqTopic -Topic $topic
    }
    foreach ($consumerGroup in $mqConsumerGroups.Values) {
        New-IsolatedMqConsumerGroup -ConsumerGroup $consumerGroup
    }
}

function Remove-IsolatedMessaging {
    $errors = [System.Collections.Generic.List[string]]::new()
    $consumerGroups = @($mqConsumerGroupsCreated)
    foreach ($consumerGroup in $consumerGroups) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $consumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
            $errors.Add("${consumerGroup}: $($output -join "`n")")
        }
    }

    $topicOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $errors.Add("topic list: $($topicOutput -join "`n")")
    }
    else {
        $consumerArtifacts = @($topicOutput | ForEach-Object { $_.Trim() } |
            Where-Object {
                $topicName = $_
                $consumerGroups | Where-Object {
                    $topicName.Contains($_) -and
                    ($topicName.StartsWith('%RETRY%') -or $topicName.StartsWith('%DLQ%'))
                }
            } | Sort-Object -Unique)
        foreach ($topicName in $consumerArtifacts) {
            $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                    -n plainjournal-rocketmq-namesrv:9876 `
                    -c EcommerceCluster `
                    -t $topicName 2>&1)
            if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
                $errors.Add("${topicName}: $($output -join "`n")")
            }
        }
    }

    foreach ($topic in @($mqTopicsCreated)) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topic 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
            $errors.Add("${topic}: $($output -join "`n")")
        }
    }
    if ($errors.Count -gt 0) {
        throw "Unable to delete isolated RocketMQ resources: $($errors -join ' | ')"
    }
    $mqConsumerGroupsCreated.Clear()
    $mqTopicsCreated.Clear()
}

function Start-ProbeService {
    param(
        [Parameter(Mandatory)][ValidateSet('identity', 'catalog', 'inventory', 'trade',
            'payment', 'fulfillment', 'marketing')]
        [string]$Name
    )

    Assert-PortFree -Port $servicePorts[$Name]
    $jar = $serviceJars[$Name]
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Missing service jar: $jar"
    }
    $stdout = Join-Path $runDirectory "$Name.stdout.log"
    $stderr = Join-Path $runDirectory "$Name.stderr.log"
    $environment = @{
        SERVICE_IP = '127.0.0.1'
        SERVICE_INSTANCE_ID = "m7-trade-sharding-$Name"
        MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED = 'false'
        OTLP_TRACING_EXPORT_ENABLED = 'false'
        LOGGING_LEVEL_ROOT = 'INFO'
    }
    $jvmArguments = @('-Xms96m', '-Xmx384m', '-XX:MaxMetaspaceSize=192m')
    $systemProperties = [System.Collections.Generic.List[string]]::new()
    switch ($Name) {
        'inventory' {
            $systemProperties.Add("-Decommerce.inventory.outbox.topic=$($mqTopics.inventory)")
            $systemProperties.Add("-Decommerce.inventory.order-consumer.topic=$($mqTopics.order)")
            $systemProperties.Add(
                "-Decommerce.inventory.order-consumer.consumer-group=$($mqConsumerGroups.inventoryOrder)")
            $systemProperties.Add("-Decommerce.inventory.return-consumer.topic=$($mqTopics.logistics)")
            $systemProperties.Add(
                "-Decommerce.inventory.return-consumer.consumer-group=$($mqConsumerGroups.inventoryReturn)")
        }
        'marketing' {
            $systemProperties.Add('-Decommerce.marketing.flash-sale-result-consumer.enabled=false')
            $systemProperties.Add("-Decommerce.marketing.order-consumer.topic=$($mqTopics.order)")
            $systemProperties.Add(
                "-Decommerce.marketing.order-consumer.consumer-group=$($mqConsumerGroups.marketingOrder)")
        }
        'payment' {
            $systemProperties.Add("-Decommerce.payment.outbox.topic=$($mqTopics.payment)")
            $systemProperties.Add("-Decommerce.payment.refund-consumer.topic=$($mqTopics.order)")
            $systemProperties.Add(
                "-Decommerce.payment.refund-consumer.consumer-group=$($mqConsumerGroups.paymentRefund)")
        }
        'fulfillment' {
            $systemProperties.Add("-Decommerce.fulfillment.outbox.topic=$($mqTopics.logistics)")
            $systemProperties.Add("-Decommerce.fulfillment.order-consumer.topic=$($mqTopics.order)")
            $systemProperties.Add(
                "-Decommerce.fulfillment.order-consumer.consumer-group=$($mqConsumerGroups.fulfillmentOrder)")
            $systemProperties.Add("-Decommerce.fulfillment.after-sale-consumer.topic=$($mqTopics.order)")
            $systemProperties.Add(
                "-Decommerce.fulfillment.after-sale-consumer.consumer-group=$($mqConsumerGroups.fulfillmentAfterSale)")
        }
    }
    if ($Name -eq 'trade') {
        $environment['SPRING_PROFILES_ACTIVE'] = 'm7-trade-sharding'
        $environment['TRADE_SHARD_0_URL'] =
            "jdbc:mysql://127.0.0.1:$($settings['MYSQL_PORT'])/$($settings['TRADE_SHARD_0_DB_NAME'])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true'
        $environment['TRADE_SHARD_1_URL'] =
            "jdbc:mysql://127.0.0.1:$($settings['TRADE_SHARD_1_PORT'])/$($settings['TRADE_SHARD_1_DB_NAME'])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true'
        $environment['TRADE_SHARD_DB_USER'] = $settings['TRADE_SHARD_DB_USER']
        $environment['TRADE_SHARD_DB_PASSWORD'] = $settings['TRADE_SHARD_DB_PASSWORD']
        $environment['TRADE_SHARDING_POOL_SIZE'] = '4'
        $environment['TRADE_DISTRIBUTED_ID_WORKER_ID'] = '0'
        $environment['TRADE_DISTRIBUTED_ID_NAMESPACE'] = "m7-trade-sharding-$probePrefix"
        $systemProperties.Add('-Decommerce.trade.flash-sale-consumer.enabled=false')
        $systemProperties.Add("-Decommerce.trade.outbox.topic=$($mqTopics.order)")
        $systemProperties.Add("-Decommerce.trade.payment-consumer.topic=$($mqTopics.payment)")
        $systemProperties.Add(
            "-Decommerce.trade.payment-consumer.consumer-group=$($mqConsumerGroups.tradePayment)")
        $systemProperties.Add("-Decommerce.trade.fulfillment-consumer.topic=$($mqTopics.logistics)")
        $systemProperties.Add(
            "-Decommerce.trade.fulfillment-consumer.consumer-group=$($mqConsumerGroups.tradeFulfillment)")
        $systemProperties.Add(
            "-Decommerce.trade.after-sale-fulfillment-consumer.topic=$($mqTopics.logistics)")
        $systemProperties.Add(
            "-Decommerce.trade.after-sale-fulfillment-consumer.consumer-group=" +
            $mqConsumerGroups.tradeAfterSaleFulfillment)
        $systemProperties.Add(
            "-Decommerce.trade.after-sale-inventory-consumer.topic=$($mqTopics.inventory)")
        $systemProperties.Add(
            "-Decommerce.trade.after-sale-inventory-consumer.consumer-group=" +
            $mqConsumerGroups.tradeAfterSaleInventory)
        $systemProperties.Add("-Decommerce.trade.refund-result-consumer.topic=$($mqTopics.payment)")
        $systemProperties.Add(
            "-Decommerce.trade.refund-result-consumer.consumer-group=$($mqConsumerGroups.tradeRefund)")
        $jvmArguments = @('-Xms128m', '-Xmx512m', '-XX:MaxMetaspaceSize=256m')
    }

    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    else {
        (Get-Command java.exe).Source
    }
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        throw "Java executable was not found: $javaExecutable"
    }
    $process = Start-Process `
        -FilePath $javaExecutable `
        -ArgumentList @($jvmArguments + @($systemProperties) + @('-jar', $jar)) `
        -WorkingDirectory $backendRoot `
        -Environment $environment `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    $record = [pscustomobject]@{
        Name = $Name
        Port = $servicePorts[$Name]
        Process = $process
        Stdout = $stdout
        Stderr = $stderr
        Stopped = $false
    }
    $processes.Add($record)
    Wait-ProbeService -Record $record
    Write-Host "Started $Name on port $($record.Port)."
}

function Wait-ProbeService {
    param([Parameter(Mandatory)][object]$Record)

    $uri = "http://127.0.0.1:$($Record.Port)/actuator/health/liveness"
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $lastError = 'not queried'
    do {
        $Record.Process.Refresh()
        if ($Record.Process.HasExited) {
            throw "$($Record.Name) exited with code $($Record.Process.ExitCode)."
        }
        try {
            $response = Invoke-WebRequest -Uri $uri -TimeoutSec 3
            if ([int]$response.StatusCode -eq 200) {
                return
            }
            $lastError = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $uri. Last error: $lastError"
}

function Stop-ProbeServices {
    param([string[]]$Names)

    $targets = @($processes | Where-Object {
            -not $_.Stopped -and ($null -eq $Names -or $_.Name -in $Names)
        })
    foreach ($record in $targets) {
        $targetIds = [System.Collections.Generic.HashSet[int]]::new()
        [void]$targetIds.Add($record.Process.Id)
        foreach ($listener in @(Get-NetTCPConnection -State Listen `
                -LocalPort $record.Port -ErrorAction SilentlyContinue)) {
            [void]$targetIds.Add([int]$listener.OwningProcess)
        }
        foreach ($targetId in $targetIds) {
            $actual = Get-CimInstance Win32_Process -Filter "ProcessId = $targetId" `
                -ErrorAction SilentlyContinue
            if ($null -eq $actual) {
                continue
            }
            if ($actual.Name -ne 'java.exe' -or
                    $actual.CommandLine -notlike "*$repositoryRoot*") {
                throw "Refusing to stop process $targetId outside PlainJournal."
            }
            Stop-Process -Id $targetId -Force -ErrorAction SilentlyContinue
        }
        $record.Stopped = $true
        Wait-PortFree -Port $record.Port
    }
}

function Show-ProbeLogs {
    foreach ($record in $processes) {
        foreach ($path in @($record.Stdout, $record.Stderr)) {
            if (Test-Path -LiteralPath $path) {
                Write-Host "--- $path ---"
                Get-Content -LiteralPath $path -Tail 80
            }
        }
    }
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Compress -Depth 12) -TimeoutSec 20
}

function Get-HmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Secret
    )

    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        return [Convert]::ToHexString(
            $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
    }
    finally {
        $hmac.Dispose()
    }
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][string]$Description,
        [int]$TimeoutSeconds = 90,
        [int]$IntervalMilliseconds = 1000
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds $IntervalMilliseconds
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Register-ProbeUsers {
    $identityBaseUrl = 'http://127.0.0.1:18101/api/v1/identity'
    $password = 'M7ShardingPass123'
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $email = "m7-sharding-$probePrefix-$attempt@example.invalid".ToLowerInvariant()
        Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
            email = $email
            password = $password
            displayName = "M7 Sharding $attempt"
        } | Out-Null
        $login = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
            email = $email
            password = $password
        }
        $emailLiteral = ConvertTo-MySqlLiteral -Value $email
        $userId = [long](Get-MySqlScalar -Target identity `
            -Sql "SELECT id FROM user_account WHERE email = $emailLiteral;")
        $address = Invoke-JsonPost -Uri "$identityBaseUrl/addresses" `
            -Headers @{ Authorization = "Bearer $($login.data.accessToken)" } -Body @{
                recipientName = "M7 Customer $attempt"
                phone = '+86 13800000000'
                province = 'Zhejiang'
                provinceCode = '330000'
                city = 'Hangzhou'
                cityCode = '330100'
                district = 'Xihu'
                districtCode = '330106'
                detailAddress = "M7 sharding probe address $attempt"
                postalCode = '310000'
                setDefault = $true
            }
        $record = [pscustomobject]@{
            UserId = $userId
            Shard = [int]($userId % 2)
            Email = $email
            Password = $password
            AccessToken = [string]$login.data.accessToken
            AddressId = [long]$address.data.id
        }
        $users.Add($record)
        if (@($users | Where-Object Shard -eq 0).Count -gt 0 -and
                @($users | Where-Object Shard -eq 1).Count -gt 0) {
            return
        }
    }
    throw 'Unable to create both an even and an odd user ID within six registrations.'
}

function Get-OrderPhysicalFact {
    param(
        [Parameter(Mandatory)][ValidateSet('shard0', 'shard1')][string]$Target,
        [Parameter(Mandatory)][string]$OrderNo
    )

    $order = ConvertTo-MySqlLiteral -Value $OrderNo
    return Get-MySqlScalar -Target $Target -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM trade_order WHERE order_no = $order), '|',
    (SELECT COUNT(*) FROM order_item i JOIN trade_order o ON o.id = i.order_id
      WHERE o.order_no = $order), '|',
    (SELECT COUNT(*) FROM order_address_snapshot a JOIN trade_order o ON o.id = a.order_id
      WHERE o.order_no = $order), '|',
    (SELECT COUNT(*) FROM order_price_snapshot p JOIN trade_order o ON o.id = p.order_id
      WHERE o.order_no = $order), '|',
    (SELECT COUNT(*) FROM order_status_history h JOIN trade_order o ON o.id = h.order_id
      WHERE o.order_no = $order), '|',
    (SELECT COUNT(*) FROM outbox_event
      WHERE aggregate_id = $order AND event_type = 'OrderAwaitingPayment')
);
"@
}

function Add-EventIds {
    param(
        [Parameter(Mandatory)][ValidateSet('shard0', 'shard1', 'inventory',
            'payment', 'fulfillment')][string]$Target,
        [Parameter(Mandatory)][string]$Sql
    )

    foreach ($row in @(Invoke-MySql -Target $Target -Sql $Sql -AllowFailure)) {
        $value = $row.ToString().Trim()
        if ($value -match '^[0-9a-fA-F-]{36}$') {
            [void]$allEventIds.Add($value)
        }
    }
}

New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$startedAtUtc = [DateTimeOffset]::UtcNow

try {
    if (-not $SkipNetworkPreflight) {
        if (-not (Test-Path -LiteralPath $networkCheck)) {
            throw "Missing required network diagnostic: $networkCheck"
        }
        & $networkCheck
        if ($LASTEXITCODE -ne 0) {
            throw "Network preflight failed with exit code $LASTEXITCODE."
        }
    }

    foreach ($port in $servicePorts.Values) {
        Assert-PortFree -Port $port
    }
    Assert-PortFree -Port 13326

    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not ready.'
    }
    foreach ($container in @(
            'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
            'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker',
            'plainjournal-rocketmq-proxy', 'plainjournal-minio')) {
        if ((docker inspect --format '{{.State.Running}}' $container 2>$null) -ne 'true') {
            throw "Required core container is not running: $container"
        }
    }

    & $bootstrapScript
    if ($LASTEXITCODE -ne 0) {
        throw "Resource bootstrap failed with exit code $LASTEXITCODE."
    }
    $settings = Import-DotEnv -Path $composeEnvFile
    foreach ($required in @(
            'MYSQL_ROOT_PASSWORD', 'MYSQL_PORT', 'IDENTITY_JWT_SECRET',
            'TRADE_INTERNAL_SERVICE_TOKEN', 'PAYMENT_INTERNAL_SERVICE_TOKEN',
            'MOCK_PAYMENT_CALLBACK_SECRET',
            'TRADE_SHARD_0_DB_NAME', 'TRADE_SHARD_1_DB_NAME',
            'TRADE_SHARD_DB_USER', 'TRADE_SHARD_DB_PASSWORD', 'TRADE_SHARD_1_PORT')) {
        if (-not $settings.ContainsKey($required) -or
                [string]::IsNullOrWhiteSpace($settings[$required])) {
            throw "Missing required .env value: $required"
        }
    }
    foreach ($name in @(
            $settings['TRADE_SHARD_0_DB_NAME'],
            $settings['TRADE_SHARD_1_DB_NAME'],
            $settings['TRADE_SHARD_DB_USER'])) {
        if ($name -notmatch '^[a-z0-9_]+$') {
            throw "Unsafe Trade shard identifier: $name"
        }
    }

    docker inspect plainjournal-mysql-trade-shard-1 *> $null
    $shardContainerExistedBefore = $LASTEXITCODE -eq 0
    if ($shardContainerExistedBefore) {
        $shardContainerRunningBefore =
            (docker inspect --format '{{.State.Running}}' plainjournal-mysql-trade-shard-1 2>$null) -eq 'true'
    }
    docker compose --project-directory $composeDirectory --env-file $composeEnvFile `
        --file $composeFile --profile m7-trade-sharding config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'The m7-trade-sharding Compose profile is invalid.'
    }
    docker compose --project-directory $composeDirectory --env-file $composeEnvFile `
        --file $composeFile --profile m7-trade-sharding up -d mysql-trade-shard-1
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to start the second Trade MySQL shard.'
    }
    Wait-ContainerHealthy -Container 'plainjournal-mysql-trade-shard-1'

    $schema0 = $settings['TRADE_SHARD_0_DB_NAME']
    $schema1 = $settings['TRADE_SHARD_1_DB_NAME']
    $shardUser = $settings['TRADE_SHARD_DB_USER']
    $schema0Count = [int](Get-MySqlScalar -Target root0 `
        -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$schema0';")
    $schema1Count = [int](Get-MySqlScalar -Target root1 `
        -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$schema1';")
    $user0Count = [int](Get-MySqlScalar -Target root0 `
        -Sql "SELECT COUNT(*) FROM mysql.user WHERE user = '$shardUser' AND host = '%';")
    $user1Count = [int](Get-MySqlScalar -Target root1 `
        -Sql "SELECT COUNT(*) FROM mysql.user WHERE user = '$shardUser' AND host = '%';")
    if ($schema0Count -ne 0 -or $schema1Count -ne 0 -or
            $user0Count -ne 0 -or $user1Count -ne 0) {
        throw 'Trade shard experiment schemas or users already exist; refusing to overwrite them.'
    }
    $escapedShardPassword = $settings['TRADE_SHARD_DB_PASSWORD'].Replace("'", "''")
    Invoke-MySql -Target root0 -Sql @"
CREATE DATABASE $schema0 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$shardUser'@'%' IDENTIFIED BY '$escapedShardPassword';
GRANT ALL PRIVILEGES ON $schema0.* TO '$shardUser'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
    Invoke-MySql -Target root1 -Sql @"
CREATE DATABASE $schema1 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$shardUser'@'%' IDENTIFIED BY '$escapedShardPassword';
GRANT ALL PRIVILEGES ON $schema1.* TO '$shardUser'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
    $shardSchemasCreated = $true
    $shardUsersCreated = $true

    if (-not $SkipBuild) {
        & mvn -q -f (Join-Path $backendRoot 'pom.xml') '-DskipTests' `
            -pl 'services/identity-service,services/catalog-service,services/inventory-service,services/trade-service,services/payment-service,services/fulfillment-service,services/marketing-service' `
            -am package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed with exit code $LASTEXITCODE."
        }
    }

    Initialize-IsolatedMessaging

    foreach ($name in @('identity', 'catalog', 'inventory', 'marketing', 'trade')) {
        Start-ProbeService -Name $name
    }

    Register-ProbeUsers
    $evenUser = @($users | Where-Object Shard -eq 0 | Select-Object -First 1)[0]
    $oddUser = @($users | Where-Object Shard -eq 1 | Select-Object -First 1)[0]
    $chainUser = $oddUser
    $otherUser = $evenUser

    $chainEmail = ConvertTo-MySqlLiteral -Value $chainUser.Email
    Invoke-MySql -Target identity -Sql @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT u.id, r.id, CURRENT_TIMESTAMP(3)
FROM user_account u
JOIN identity_role r ON r.code = 'ADMIN'
WHERE u.email = $chainEmail;
"@ | Out-Null
    $adminLogin = Invoke-JsonPost `
        -Uri 'http://127.0.0.1:18101/api/v1/identity/auth/login' `
        -Body @{ email = $chainUser.Email; password = $chainUser.Password }
    $adminToken = [string]$adminLogin.data.accessToken
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }

    $categorySlug = "m7-shard-category-$($probePrefix.ToLowerInvariant())"
    $brandSlug = "m7-shard-brand-$($probePrefix.ToLowerInvariant())"
    $productTitle = "M7 Trade Sharding $probePrefix"
    $skuCode = "M7-SHARD-$probePrefix"
    $catalogBaseUrl = 'http://127.0.0.1:18102/api/v1/catalog'
    $category = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/categories" `
        -Headers $adminHeaders -Body @{
            name = "M7 Sharding $probePrefix"
            slug = $categorySlug
            sortOrder = 9999
        }
    $brand = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/brands" `
        -Headers $adminHeaders -Body @{
            name = "M7 Sharding $probePrefix"
            slug = $brandSlug
        }
    $product = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products" `
        -Headers $adminHeaders -Body @{
            categoryId = $category.data.id
            brandId = $brand.data.id
            title = $productTitle
            subtitle = 'Temporary M7 Trade sharding verification data'
            description = 'Created and removed by verify-m7-trade-sharding.ps1.'
            skus = @(@{
                    skuCode = $skuCode
                    name = 'Default'
                    specJson = '{"variant":"default"}'
                    salePrice = 88.80
                    marketPrice = 99.90
                })
        }
    $productId = [long]$product.data.id
    $skuId = [long]$product.data.skus[0].id
    Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/publish" `
        -Headers $adminHeaders -Body @{ expectedVersion = $product.data.version } | Out-Null

    $inventoryBaseUrl = 'http://127.0.0.1:18103/api/v1/inventory'
    $warehouses = Invoke-RestMethod -Method Get -Uri "$inventoryBaseUrl/admin/warehouses" `
        -Headers $adminHeaders -TimeoutSec 20
    $primaryWarehouse = @($warehouses.data | Where-Object code -eq 'PRIMARY' | Select-Object -First 1)
    if ($primaryWarehouse.Count -eq 0) {
        $createdWarehouse = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/warehouses" `
            -Headers $adminHeaders -Body @{
                code = 'PRIMARY'
                name = 'M7 Primary Warehouse'
            }
        $warehouseId = [long]$createdWarehouse.data.id
        $warehouseCreated = $true
    }
    else {
        $warehouseId = [long]$primaryWarehouse[0].id
    }
    Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = "$probePrefix-STOCK"
            warehouseId = $warehouseId
            skuId = $skuId
            quantityDelta = 10
            reason = 'M7 Trade two-shard verification stock'
        } | Out-Null

    $tradeBaseUrl = 'http://127.0.0.1:18104/api/v1/trade'
    foreach ($user in @($evenUser, $oddUser)) {
        $idempotencyKey = "$probePrefix-ORDER-$($user.Shard)"
        $createdOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
            Authorization = "Bearer $($user.AccessToken)"
            'Idempotency-Key' = $idempotencyKey
        } -Body @{
            addressId = $user.AddressId
            items = @(@{ productId = $productId; skuId = $skuId; quantity = 1 })
            benefitNos = @()
        }
        Assert-True -Condition ($createdOrder.data.status -eq 'PENDING_PAYMENT') `
            -Message "User $($user.UserId) order did not reach PENDING_PAYMENT."
        $orderRecord = [pscustomobject]@{
            User = $user
            OrderNo = [string]$createdOrder.data.orderNo
            ReservationNo = ([string]$createdOrder.data.orderNo -replace '^ORD', 'RSV')
            IdempotencyKey = $idempotencyKey
            Amount = [decimal]$createdOrder.data.priceSnapshot.payableAmount
        }
        $orders.Add($orderRecord)
    }
    $chainOrder = @($orders | Where-Object { $_.User.UserId -eq $chainUser.UserId })[0]
    $otherOrder = @($orders | Where-Object { $_.User.UserId -eq $otherUser.UserId })[0]

    foreach ($order in $orders) {
        $expectedTarget = if ($order.User.Shard -eq 0) { 'shard0' } else { 'shard1' }
        $otherTarget = if ($order.User.Shard -eq 0) { 'shard1' } else { 'shard0' }
        $fact = Get-OrderPhysicalFact -Target $expectedTarget -OrderNo $order.OrderNo
        $parts = $fact -split '\|'
        Assert-True -Condition (
            $parts.Count -eq 6 -and
            [int]$parts[0] -eq 1 -and
            [int]$parts[1] -eq 1 -and
            [int]$parts[2] -eq 1 -and
            [int]$parts[3] -eq 1 -and
            [int]$parts[4] -ge 2 -and
            [int]$parts[5] -eq 1
        ) -Message "Order aggregate was incomplete on $expectedTarget`: $fact"
        $otherFact = Get-OrderPhysicalFact -Target $otherTarget -OrderNo $order.OrderNo
        Assert-True -Condition ($otherFact -eq '0|0|0|0|0|0') `
            -Message "Order $($order.OrderNo) leaked into $otherTarget`: $otherFact"

        $headers = @{ Authorization = "Bearer $($order.User.AccessToken)" }
        $page = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/page?page=1&size=10" -Headers $headers -TimeoutSec 20
        $cursorPage = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/cursor?size=10" -Headers $headers -TimeoutSec 20
        $point = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($order.OrderNo)" -Headers $headers -TimeoutSec 20
        Assert-True -Condition (
            @($page.data.items | Where-Object orderNo -eq $order.OrderNo).Count -eq 1 -and
            @($cursorPage.data.items | Where-Object orderNo -eq $order.OrderNo).Count -eq 1 -and
            $point.data.orderNo -eq $order.OrderNo
        ) -Message "User-scoped pagination or point lookup failed for $($order.OrderNo)."
        $routingEvidence[$order.OrderNo] = [ordered]@{
            userId = $order.User.UserId
            shard = "ds_$($order.User.Shard)"
            physicalFact = $fact
            oppositeShardFact = $otherFact
            pageLookup = 'PASS'
            cursorLookup = 'PASS'
            pointLookup = 'PASS'
        }
    }
    $phaseACompletedAtUtc = [DateTimeOffset]::UtcNow

    Stop-ProbeServices -Names @('identity', 'catalog')
    foreach ($name in @('payment', 'fulfillment')) {
        Start-ProbeService -Name $name
    }

    $paymentBaseUrl = 'http://127.0.0.1:18105/api/v1/payment'
    $customerHeaders = @{ Authorization = "Bearer $($chainUser.AccessToken)" }
    $createdPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
        Authorization = "Bearer $($chainUser.AccessToken)"
        'Idempotency-Key' = "$probePrefix-PAYMENT"
    } -Body @{
        orderNo = $chainOrder.OrderNo
        channel = 'MOCK'
    }
    $paymentNo = [string]$createdPayment.data.paymentNo
    Assert-True -Condition ($createdPayment.data.status -eq 'PROCESSING') `
        -Message 'Payment creation did not reach PROCESSING.'
    $paymentEventId = "$probePrefix-PAY-EVT"
    $paymentTransactionNo = "$probePrefix-PAY-TXN"
    $paymentTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $paymentAmountText = ([decimal]$createdPayment.data.amount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $paymentCanonical =
        "$paymentNo|$paymentEventId|$paymentTransactionNo|SUCCESS|$paymentAmountText|$paymentTimestamp"
    $paymentSignature = Get-HmacSha256Hex -Value $paymentCanonical `
        -Secret $settings['MOCK_PAYMENT_CALLBACK_SECRET']
    Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock" -Body @{
        paymentNo = $paymentNo
        externalEventId = $paymentEventId
        externalTransactionNo = $paymentTransactionNo
        status = 'SUCCESS'
        amount = [decimal]$createdPayment.data.amount
        timestamp = $paymentTimestamp
        signature = $paymentSignature
    } | Out-Null

    $fulfillmentBaseUrl = 'http://127.0.0.1:18106/api/v1/fulfillment'
    $paidOrder = $null
    $fulfillment = $null
    Wait-Until -Description 'PaymentSucceeded -> Trade/Inventory/Marketing/Fulfillment convergence' `
        -TimeoutSeconds 120 -Condition {
            try {
                $script:paidOrder = Invoke-RestMethod -Method Get `
                    -Uri "$tradeBaseUrl/orders/$($chainOrder.OrderNo)" `
                    -Headers $customerHeaders -TimeoutSec 10
                $script:fulfillment = Invoke-RestMethod -Method Get `
                    -Uri "$fulfillmentBaseUrl/orders/$($chainOrder.OrderNo)" `
                    -Headers $customerHeaders -TimeoutSec 10
                $lockStatus = Get-MySqlScalar -Target marketing -Sql (
                    "SELECT status FROM pricing_lock WHERE order_no = " +
                    (ConvertTo-MySqlLiteral -Value $chainOrder.OrderNo) + ';')
                return $paidOrder.data.status -eq 'FULFILLING' -and
                    $fulfillment.data.status -eq 'CREATED' -and
                    $lockStatus -eq 'REDEEMED'
            }
            catch {
                return $false
            }
        }
    $fulfillmentNo = [string]$fulfillment.data.fulfillmentNo

    $paymentSucceededEventId = Get-MySqlScalar -Target payment -Sql @"
SELECT id FROM outbox_event
WHERE aggregate_id = $(ConvertTo-MySqlLiteral -Value $paymentNo)
  AND event_type = 'PaymentSucceeded'
LIMIT 1;
"@
    $chainShardTarget = if ($chainUser.Shard -eq 0) { 'shard0' } else { 'shard1' }
    $oppositeShardTarget = if ($chainUser.Shard -eq 0) { 'shard1' } else { 'shard0' }
    Wait-Until -Description 'PaymentSucceeded consumed_event on the owning Trade shard' `
        -TimeoutSeconds 60 -Condition {
            [int](Get-MySqlScalar -Target $chainShardTarget -Sql (
                "SELECT COUNT(*) FROM consumed_event WHERE event_id = " +
                (ConvertTo-MySqlLiteral -Value $paymentSucceededEventId) + ';')) -eq 1
        }
    $oppositeConsumed = [int](Get-MySqlScalar -Target $oppositeShardTarget -Sql (
        "SELECT COUNT(*) FROM consumed_event WHERE event_id = " +
        (ConvertTo-MySqlLiteral -Value $paymentSucceededEventId) + ';'))
    Assert-True -Condition ($oppositeConsumed -eq 0) `
        -Message 'PaymentSucceeded consumed_event leaked into the opposite Trade shard.'

    Stop-ProbeServices -Names @('marketing')

    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/picking" `
        -Headers $adminHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/packed" `
        -Headers $adminHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/ship" `
        -Headers $adminHeaders -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "$probePrefix-TRACK"
        } | Out-Null
    $traceTime = [DateTimeOffset]::UtcNow.ToString('o')
    foreach ($trace in @(
            @{ Id = "$probePrefix-TRACE-1"; Type = 'TRANSIT'; Text = 'Sorting center' },
            @{ Id = "$probePrefix-TRACE-2"; Type = 'DELIVERING'; Text = 'Out for delivery' },
            @{ Id = "$probePrefix-TRACE-3"; Type = 'SIGNED'; Text = 'Signed' })) {
        Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/traces" `
            -Headers $adminHeaders -Body @{
                externalEventId = $trace.Id
                nodeType = $trace.Type
                description = $trace.Text
                locationName = 'Hangzhou'
                longitude = 120.155070
                latitude = 30.274085
                occurredAt = $traceTime
            } | Out-Null
    }
    Wait-Until -Description 'ShipmentSigned -> Trade COMPLETED' -TimeoutSeconds 90 -Condition {
        $completed = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($chainOrder.OrderNo)" `
            -Headers $customerHeaders -TimeoutSec 10
        return $completed.data.status -eq 'COMPLETED'
    }

    $afterSale = Invoke-JsonPost `
        -Uri "$tradeBaseUrl/orders/$($chainOrder.OrderNo)/after-sales" `
        -Headers @{
            Authorization = "Bearer $($chainUser.AccessToken)"
            'Idempotency-Key' = "$probePrefix-AFTERSALE"
        } -Body @{ reason = 'M7 Trade shard whole-order return verification' }
    $afterSaleNo = [string]$afterSale.data.afterSaleNo
    Assert-True -Condition (
        $afterSale.data.status -eq 'APPLIED' -and
        [decimal]$afterSale.data.refundAmount -eq $chainOrder.Amount
    ) -Message 'Whole-order after-sale snapshot was invalid.'
    Invoke-JsonPost -Uri "$tradeBaseUrl/admin/after-sales/$afterSaleNo/review" `
        -Headers $adminHeaders -Body @{
            approved = $true
            reason = 'Approved by M7 Trade shard verification'
        } | Out-Null

    $returnReceipt = $null
    Wait-Until -Description 'AfterSaleApproved -> return receipt' -TimeoutSeconds 90 -Condition {
        $returns = Invoke-RestMethod -Method Get -Uri "$fulfillmentBaseUrl/returns" `
            -Headers $customerHeaders -TimeoutSec 10
        $script:returnReceipt = @($returns.data |
            Where-Object afterSaleNo -eq $afterSaleNo | Select-Object -First 1)
        return $returnReceipt.Count -eq 1 -and $returnReceipt[0].status -eq 'WAIT_SHIPMENT'
    }
    $returnReceiptNo = [string]$returnReceipt[0].returnReceiptNo
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/returns/$returnReceiptNo/shipment" `
        -Headers $customerHeaders -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "$probePrefix-RETURN"
        } | Out-Null
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/receive" `
        -Headers $adminHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/inspect" `
        -Headers $adminHeaders -Body @{ remark = 'All returned goods accepted' } | Out-Null

    $refund = $null
    Wait-Until -Description 'ReturnInspected -> ReturnStocked -> RefundRequested' `
        -TimeoutSeconds 120 -Condition {
            try {
                $script:refund = Invoke-RestMethod -Method Get `
                    -Uri "$paymentBaseUrl/refunds/by-after-sale/$afterSaleNo" `
                    -Headers $customerHeaders -TimeoutSec 10
                $sale = Invoke-RestMethod -Method Get `
                    -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
                    -Headers $customerHeaders -TimeoutSec 10
                return $refund.data.status -eq 'PROCESSING' -and
                    $refund.data.requestStatus -eq 'SENT' -and
                    $sale.data.status -eq 'REFUNDING'
            }
            catch {
                return $false
            }
        }
    $refundNo = [string]$refund.data.refundNo
    $refundEventId = "$probePrefix-REFUND-EVT"
    $externalRefundNo = "$probePrefix-REFUND-TXN"
    $refundTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $refundAmountText = ([decimal]$refund.data.amount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $refundCanonical =
        "$refundNo|$refundEventId|$externalRefundNo|SUCCESS|$refundAmountText|$refundTimestamp"
    $refundSignature = Get-HmacSha256Hex -Value $refundCanonical `
        -Secret $settings['MOCK_PAYMENT_CALLBACK_SECRET']
    Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body @{
        refundNo = $refundNo
        externalEventId = $refundEventId
        externalRefundNo = $externalRefundNo
        status = 'SUCCESS'
        amount = [decimal]$refund.data.amount
        timestamp = $refundTimestamp
        signature = $refundSignature
    } | Out-Null
    Wait-Until -Description 'RefundSucceeded -> after-sale COMPLETED' `
        -TimeoutSeconds 90 -Condition {
            $sale = Invoke-RestMethod -Method Get `
                -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
                -Headers $customerHeaders -TimeoutSec 10
            return $sale.data.status -eq 'COMPLETED' -and $sale.data.refundNo -eq $refundNo
        }

    Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$($otherOrder.OrderNo)/cancel" `
        -Headers @{ Authorization = "Bearer $($otherUser.AccessToken)" } -Body @{} | Out-Null

    Wait-Until -Description 'both Trade shard Outboxes to drain' -TimeoutSeconds 120 -Condition {
        $pending0 = [int](Get-MySqlScalar -Target shard0 `
            -Sql "SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';")
        $pending1 = [int](Get-MySqlScalar -Target shard1 `
            -Sql "SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';")
        return $pending0 -eq 0 -and $pending1 -eq 0
    }
    Start-Sleep -Seconds 12
    $open0 = [int](Get-MySqlScalar -Target shard0 `
        -Sql "SELECT COUNT(*) FROM reconciliation_record WHERE status = 'OPEN';")
    $open1 = [int](Get-MySqlScalar -Target shard1 `
        -Sql "SELECT COUNT(*) FROM reconciliation_record WHERE status = 'OPEN';")
    Assert-True -Condition ($open0 -eq 0 -and $open1 -eq 0) `
        -Message "Trade reconciliation found OPEN issues: ds_0=$open0, ds_1=$open1."
    $apiIssues = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/admin/reconciliation/issues?status=OPEN&limit=100" `
        -Headers $adminHeaders -TimeoutSec 20
    Assert-True -Condition (@($apiIssues.data).Count -eq 0) `
        -Message 'Broadcast reconciliation read returned OPEN Trade issues.'

    $lease0 = [int](Get-MySqlScalar -Target shard0 `
        -Sql "SELECT COUNT(*) FROM distributed_id_worker_lease;")
    $lease1 = [int](Get-MySqlScalar -Target shard1 `
        -Sql "SELECT COUNT(*) FROM distributed_id_worker_lease;")
    $failures0 = [int](Get-MySqlScalar -Target shard0 `
        -Sql "SELECT COUNT(*) FROM consumer_failure;")
    $failures1 = [int](Get-MySqlScalar -Target shard1 `
        -Sql "SELECT COUNT(*) FROM consumer_failure;")
    Assert-True -Condition ($lease0 -eq 1 -and $lease1 -eq 0) `
        -Message "Distributed ID control rows were not confined to ds_0: $lease0|$lease1."
    Assert-True -Condition ($failures0 -eq 0 -and $failures1 -eq 0) `
        -Message "Unexpected Trade consumer failures: $failures0|$failures1."

    $afterSaleShardCount = [int](Get-MySqlScalar -Target $chainShardTarget -Sql (
        "SELECT COUNT(*) FROM after_sale_order WHERE after_sale_no = " +
        (ConvertTo-MySqlLiteral -Value $afterSaleNo) + ';'))
    $afterSaleOppositeCount = [int](Get-MySqlScalar -Target $oppositeShardTarget -Sql (
        "SELECT COUNT(*) FROM after_sale_order WHERE after_sale_no = " +
        (ConvertTo-MySqlLiteral -Value $afterSaleNo) + ';'))
    Assert-True -Condition ($afterSaleShardCount -eq 1 -and $afterSaleOppositeCount -eq 0) `
        -Message 'After-sale aggregate was not confined to the owning Trade shard.'

    $stock = Invoke-RestMethod -Method Get `
        -Uri "$inventoryBaseUrl/admin/warehouses/$warehouseId/stocks/$skuId" `
        -Headers $adminHeaders -TimeoutSec 20
    Assert-True -Condition (
        [long]$stock.data.onHand -eq 10 -and
        [long]$stock.data.reserved -eq 0 -and
        [long]$stock.data.available -eq 10
    ) -Message 'Inventory did not return to its exact baseline after cancel and return.'

    $lifecycleEvidence = [ordered]@{
        paymentNo = $paymentNo
        paymentSucceededEventId = $paymentSucceededEventId
        paymentConsumedShard = "ds_$($chainUser.Shard)"
        paymentConsumedOppositeShardCount = $oppositeConsumed
        fulfillmentNo = $fulfillmentNo
        afterSaleNo = $afterSaleNo
        afterSaleShard = "ds_$($chainUser.Shard)"
        returnReceiptNo = $returnReceiptNo
        refundNo = $refundNo
        finalOrderStatus = 'COMPLETED'
        finalAfterSaleStatus = 'COMPLETED'
        inventoryEquation = '10|0|10'
        tradeOutboxPending = @{ ds0 = 0; ds1 = 0 }
        tradeReconciliationOpen = @{ ds0 = $open0; ds1 = $open1 }
        controlRows = @{
            distributedIdWorkerLease = @{ ds0 = $lease0; ds1 = $lease1 }
            consumerFailure = @{ ds0 = $failures0; ds1 = $failures1 }
        }
    }
    $phaseBCompletedAtUtc = [DateTimeOffset]::UtcNow
    $verificationSucceeded = $true
}
catch {
    $executionError = $_
    Write-Host "M7 Trade sharding verification failed: $($_.Exception.Message)"
    Show-ProbeLogs
}
finally {
    try {
        Stop-ProbeServices
        $cleanupEvidence.servicesStopped = $true
    }
    catch {
        $cleanupErrors.Add("Service cleanup: $($_.Exception.Message)")
    }

    try {
        Remove-IsolatedMessaging
        $cleanupEvidence.rocketMqResourcesRemoved = $true
    }
    catch {
        $cleanupErrors.Add("RocketMQ cleanup: $($_.Exception.Message)")
    }

    try {
        if ($orders.Count -gt 0) {
            $orderNumbers = @($orders | ForEach-Object OrderNo)
            $reservationNumbers = @($orders | ForEach-Object ReservationNo)
            $orderSqlList = ConvertTo-MySqlList -Values $orderNumbers
            $reservationSqlList = ConvertTo-MySqlList -Values $reservationNumbers
            foreach ($target in @('shard0', 'shard1')) {
                Add-EventIds -Target $target `
                    -Sql "SELECT id FROM outbox_event WHERE aggregate_id IN ($orderSqlList) OR aggregate_id = $(ConvertTo-MySqlLiteral -Value $afterSaleNo);"
            }
            if ($paymentNo) {
                Add-EventIds -Target payment -Sql (
                    "SELECT id FROM outbox_event WHERE aggregate_id IN (" +
                    (ConvertTo-MySqlList -Values @($paymentNo, $refundNo)) + ');')
            }
            if ($fulfillmentNo) {
                Add-EventIds -Target fulfillment -Sql (
                    "SELECT id FROM outbox_event WHERE aggregate_id IN (" +
                    (ConvertTo-MySqlList -Values @($fulfillmentNo, $returnReceiptNo)) + ');')
            }
            if ($warehouseId -and $skuId) {
                Add-EventIds -Target inventory -Sql (
                    "SELECT event_id FROM outbox_event WHERE aggregate_id IN (" +
                    (ConvertTo-MySqlList -Values @(
                            $reservationNumbers + @("$warehouseId`:$skuId", $afterSaleNo))) + ');')
            }
            $eventSqlList = ConvertTo-MySqlList -Values @($allEventIds)
            foreach ($target in @('inventory', 'payment', 'fulfillment', 'marketing')) {
                Invoke-MySql -Target $target `
                    -Sql "DELETE FROM consumed_event WHERE event_id IN ($eventSqlList);" `
                    -AllowFailure | Out-Null
            }

            if ($fulfillmentNo) {
                Invoke-MySql -Target fulfillment -Sql @"
DELETE FROM outbox_event
WHERE aggregate_id IN ($(ConvertTo-MySqlList -Values @($fulfillmentNo, $returnReceiptNo)));
DELETE FROM reconciliation_record
WHERE reference_no IN ($(ConvertTo-MySqlList -Values @($fulfillmentNo, $returnReceiptNo)));
DELETE FROM return_status_history
WHERE return_receipt_id IN (
    SELECT id FROM return_receipt
    WHERE return_receipt_no = $(ConvertTo-MySqlLiteral -Value $returnReceiptNo));
DELETE FROM return_item
WHERE return_receipt_id IN (
    SELECT id FROM return_receipt
    WHERE return_receipt_no = $(ConvertTo-MySqlLiteral -Value $returnReceiptNo));
DELETE FROM return_receipt
WHERE return_receipt_no = $(ConvertTo-MySqlLiteral -Value $returnReceiptNo);
DELETE FROM logistics_trace
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order
    WHERE fulfillment_no = $(ConvertTo-MySqlLiteral -Value $fulfillmentNo));
DELETE FROM fulfillment_status_history
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order
    WHERE fulfillment_no = $(ConvertTo-MySqlLiteral -Value $fulfillmentNo));
DELETE FROM fulfillment_order
WHERE fulfillment_no = $(ConvertTo-MySqlLiteral -Value $fulfillmentNo);
"@ -AllowFailure | Out-Null
            }

            if ($paymentNo) {
                $paymentList = ConvertTo-MySqlList -Values @($paymentNo)
                Invoke-MySql -Target payment -Sql @"
DELETE FROM outbox_event
WHERE aggregate_id IN ($(ConvertTo-MySqlList -Values @($paymentNo, $refundNo)));
DELETE FROM reconciliation_record
WHERE reference_no IN ($(ConvertTo-MySqlList -Values @($paymentNo, $refundNo)));
DELETE FROM refund_dispatch_retry_audit
WHERE refund_no = $(ConvertTo-MySqlLiteral -Value $refundNo);
DELETE FROM refund_callback_log
WHERE refund_no = $(ConvertTo-MySqlLiteral -Value $refundNo);
DELETE FROM refund_transaction
WHERE refund_id IN (
    SELECT id FROM refund_order
    WHERE refund_no = $(ConvertTo-MySqlLiteral -Value $refundNo));
DELETE FROM refund_order
WHERE refund_no = $(ConvertTo-MySqlLiteral -Value $refundNo);
DELETE FROM payment_callback_log WHERE payment_no IN ($paymentList);
DELETE FROM payment_transaction
WHERE payment_id IN (SELECT id FROM payment_order WHERE payment_no IN ($paymentList));
DELETE FROM payment_order WHERE payment_no IN ($paymentList);
"@ -AllowFailure | Out-Null
            }

            Invoke-MySql -Target marketing -Sql @"
DELETE FROM pricing_lock_allocation
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($orderSqlList));
DELETE FROM pricing_lock_benefit
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($orderSqlList));
DELETE FROM pricing_lock WHERE order_no IN ($orderSqlList);
"@ -AllowFailure | Out-Null

            if ($warehouseId -and $skuId) {
                Invoke-MySql -Target inventory -Sql @"
DELETE FROM outbox_event
WHERE aggregate_id IN ($(ConvertTo-MySqlList -Values @(
        $reservationNumbers + @("$warehouseId`:$skuId", $afterSaleNo))));
DELETE FROM reconciliation_record
WHERE reference_no = $(ConvertTo-MySqlLiteral -Value $afterSaleNo)
   OR reference_no = $(ConvertTo-MySqlLiteral -Value "$warehouseId`:$skuId");
DELETE FROM stock_movement WHERE warehouse_id = $warehouseId AND sku_id = $skuId;
DELETE FROM inventory_return WHERE warehouse_id = $warehouseId;
DELETE FROM inventory_reservation_item
WHERE reservation_id IN (
    SELECT id FROM inventory_reservation WHERE order_no IN ($orderSqlList));
DELETE FROM inventory_reservation WHERE order_no IN ($orderSqlList);
DELETE FROM stock_adjustment WHERE warehouse_id = $warehouseId AND sku_id = $skuId;
DELETE FROM inventory_balance WHERE warehouse_id = $warehouseId AND sku_id = $skuId;
"@ -AllowFailure | Out-Null
                if ($warehouseCreated) {
                    Invoke-MySql -Target inventory `
                        -Sql "DELETE FROM warehouse WHERE id = $warehouseId;" `
                        -AllowFailure | Out-Null
                }
            }
        }

        if ($productId) {
            Invoke-MySql -Target catalog -Sql @"
DELETE FROM product_media WHERE spu_id = $productId;
DELETE FROM product_sku WHERE spu_id = $productId;
DELETE FROM product_spu WHERE id = $productId;
DELETE FROM catalog_brand
WHERE slug = $(ConvertTo-MySqlLiteral -Value "m7-shard-brand-$($probePrefix.ToLowerInvariant())");
DELETE FROM catalog_category
WHERE slug = $(ConvertTo-MySqlLiteral -Value "m7-shard-category-$($probePrefix.ToLowerInvariant())");
"@ -AllowFailure | Out-Null
        }

        if ($users.Count -gt 0) {
            $emailList = ConvertTo-MySqlList -Values @($users | ForEach-Object Email)
            Invoke-MySql -Target identity -Sql @"
DELETE FROM refresh_token
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ($emailList));
DELETE FROM user_role
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ($emailList));
DELETE FROM user_address
WHERE user_id IN (SELECT id FROM user_account WHERE email IN ($emailList));
DELETE FROM user_account WHERE email IN ($emailList);
DELETE FROM login_record WHERE normalized_email IN ($emailList);
"@ -AllowFailure | Out-Null
        }
        $cleanupEvidence.probeDataRemoved = $true
    }
    catch {
        $cleanupErrors.Add("Probe data cleanup: $($_.Exception.Message)")
    }

    try {
        if ($shardSchemasCreated) {
            Invoke-MySql -Target root0 `
                -Sql "DROP DATABASE IF EXISTS $($settings['TRADE_SHARD_0_DB_NAME']);" `
                -AllowFailure | Out-Null
            Invoke-MySql -Target root1 `
                -Sql "DROP DATABASE IF EXISTS $($settings['TRADE_SHARD_1_DB_NAME']);" `
                -AllowFailure | Out-Null
            $cleanupEvidence.shardSchemasRemoved = $true
        }
        if ($shardUsersCreated) {
            Invoke-MySql -Target root0 `
                -Sql "DROP USER IF EXISTS '$($settings['TRADE_SHARD_DB_USER'])'@'%';" `
                -AllowFailure | Out-Null
            Invoke-MySql -Target root1 `
                -Sql "DROP USER IF EXISTS '$($settings['TRADE_SHARD_DB_USER'])'@'%';" `
                -AllowFailure | Out-Null
            $cleanupEvidence.shardUsersRemoved = $true
        }
    }
    catch {
        $cleanupErrors.Add("Trade shard database cleanup: $($_.Exception.Message)")
    }

    try {
        if (-not $shardContainerExistedBefore) {
            docker compose --project-directory $composeDirectory --env-file $composeEnvFile `
                --file $composeFile --profile m7-trade-sharding rm -s -f mysql-trade-shard-1 |
                Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to remove the experiment shard container.'
            }
        }
        elseif (-not $shardContainerRunningBefore) {
            docker stop plainjournal-mysql-trade-shard-1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to restore the pre-existing shard container to stopped state.'
            }
        }
        $cleanupEvidence.shardContainerRestored = $true
    }
    catch {
        $cleanupErrors.Add("Shard container cleanup: $($_.Exception.Message)")
    }

    $evidence = [ordered]@{
        schemaVersion = 1
        verification = 'M7 Trade two-shard real middleware'
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        documentedProjectDate = '2026-07-22'
        gitCommit = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -Last 1)
        probePrefix = $probePrefix
        startedAtUtc = $startedAtUtc.ToString('o')
        phaseACompletedAtUtc = if ($phaseACompletedAtUtc) { $phaseACompletedAtUtc.ToString('o') } else { $null }
        phaseBCompletedAtUtc = if ($phaseBCompletedAtUtc) { $phaseBCompletedAtUtc.ToString('o') } else { $null }
        success = $verificationSucceeded -and $cleanupErrors.Count -eq 0
        topology = @{
            algorithm = 'user_id % 2'
            ds0 = "plainjournal-mysql/$($settings['TRADE_SHARD_0_DB_NAME'])"
            ds1 = "plainjournal-mysql-trade-shard-1/$($settings['TRADE_SHARD_1_DB_NAME'])"
            phasedJvmMaximum = 5
            isolatedRocketMqTopics = @($mqTopics.Values)
            isolatedRocketMqConsumerGroupCount = $mqConsumerGroups.Count
        }
        routing = $routingEvidence
        lifecycle = $lifecycleEvidence
        cleanup = $cleanupEvidence
        error = if ($executionError) { $executionError.Exception.Message } else { $null }
        cleanupErrors = @($cleanupErrors)
    }
    $evidence | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $evidencePath -Encoding utf8
}

if ($executionError) {
    throw $executionError
}
if ($cleanupErrors.Count -gt 0) {
    throw "Verification passed, but cleanup failed: $($cleanupErrors -join '; ')"
}

Write-Host ''
Write-Host 'M7 Trade two-shard verification: PASS'
Write-Host '  ShardingSphere-JDBC profile and dual MySQL Flyway migration: PASS'
Write-Host '  user_id % 2 physical routing and aggregate co-location: PASS'
Write-Host '  Page/cursor/point reads on both shards: PASS'
Write-Host '  PaymentSucceeded consumed_event co-location: PASS'
Write-Host '  Fulfillment, whole-order return, stock return, refund callback: PASS'
Write-Host '  Two-shard Outbox drain and per-shard Trade reconciliation: PASS'
Write-Host '  ds_0 control-row confinement: PASS'
Write-Host '  Probe, isolated RocketMQ resources, and experiment container cleanup: PASS'
Write-Host "  Evidence: $evidencePath"
