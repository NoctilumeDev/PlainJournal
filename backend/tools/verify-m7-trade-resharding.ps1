#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$SkipBuild,
    [ValidateRange(1, 100)]
    [int]$BatchSize = 2,
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
$environmentFile = Join-Path $composeDirectory '.env'
$networkCheck = Join-Path $PSScriptRoot 'check-verification-host.ps1'
$migrationDirectory = Join-Path $backendRoot `
    'services\trade-service\src\main\resources\db\migration'
$reshardingTool = Join-Path $PSScriptRoot 'invoke-m7-trade-resharding.ps1'
$tradeJar = Join-Path $backendRoot `
    'services\trade-service\target\trade-service.jar'
$timestamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\m7-trade-resharding-$timestamp"
$evidencePath = Join-Path $runDirectory 'verification.json'
$probe = "m7rs$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$sourceSchemas = @("${probe}_source_0", "${probe}_source_1")
$targetSchemas = @(
    "${probe}_target_0",
    "${probe}_target_1",
    "${probe}_target_2",
    "${probe}_target_3"
)
$databaseUser = "${probe}_app"
$databasePassword = [Convert]::ToBase64String(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).
    TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
$jobId = "M7-RESHARD-$($probe.ToUpperInvariant())"
$replayJobId = "$jobId-REPLAY"
$checkpointPath = Join-Path $runDirectory 'checkpoint.json'
$replayCheckpointPath = Join-Path $runDirectory 'checkpoint-replay.json'
$tradePort = 18144
$processes = [Collections.Generic.List[object]]::new()
$cleanupErrors = [Collections.Generic.List[string]]::new()
$environmentRestores = [Collections.Generic.List[object]]::new()
$stages = [Collections.Generic.List[object]]::new()
$executionError = $null
$verificationSucceeded = $false
$resourcesCreated = $false
$secondShardExistedBefore = $false
$secondShardRunningBefore = $false
$sourceUsers = @(1000L, 1002L, 1004L, 1006L, 1001L, 1003L, 1005L, 1007L)
$lateUsers = @(1008L, 1009L)
$allUsers = @($sourceUsers + $lateUsers)
$evidence = [ordered]@{
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    jobId = $jobId
    replayJobId = $replayJobId
    sourceSchemas = $sourceSchemas
    targetSchemas = $targetSchemas
    batchSize = $BatchSize
    nullOwnerGate = $null
    expectedInterruption = $null
    checkpointAfterInterruption = $null
    onlineMutation = $null
    resumedCopy = $null
    idempotentCopy = $null
    finalCatchUp = $null
    corruptionGate = $null
    verification = $null
    staleVerificationPromotionGate = $null
    verificationBeforePromotion = $null
    promotion = $null
    fourShardReadRouting = $null
    rollback = $null
    replayAfterRollback = $null
    sourceFacts = $null
    cleanup = [ordered]@{}
}

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file does not exist: $Path"
    }
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

function Invoke-TimedStage {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Body
    )

    $started = [DateTimeOffset]::UtcNow
    try {
        return & $Body
    }
    finally {
        $completed = [DateTimeOffset]::UtcNow
        $stages.Add([ordered]@{
                name = $Name
                startedAtUtc = $started.ToString('O')
                completedAtUtc = $completed.ToString('O')
                durationMs = [Math]::Round(($completed - $started).TotalMilliseconds, 3)
            })
    }
}

function ConvertTo-SqlLiteral {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value) {
        return 'NULL'
    }
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function Invoke-ContainerMySql {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $container = if ($PhysicalShard -eq 0) {
        'plainjournal-mysql'
    }
    else {
        'plainjournal-mysql-trade-shard-1'
    }
    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD",
        $container,
        'mysql',
        '--user=root',
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names'
    )
    $output = @($Sql | & docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed on physical shard $PhysicalShard`: " +
            ($output -join [Environment]::NewLine)
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { $_.ToString() } |
            Where-Object { $_.Length -gt 0 })
    }
}

function Get-Scalar {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Sql
    )

    $rows = @((Invoke-ContainerMySql -PhysicalShard $PhysicalShard -Sql $Sql).Lines)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar row on physical shard $PhysicalShard, " +
            "received $($rows.Count)."
    }
    return $rows[0]
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [ValidateRange(10, 300)][int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $raw = docker inspect $Container 2>$null
        if ($LASTEXITCODE -eq 0) {
            $inspect = @($raw | ConvertFrom-Json)[0]
            $healthProperty = $inspect.State.PSObject.Properties['Health']
            $health = if ($healthProperty) { $healthProperty.Value.Status } else { $null }
            if ($inspect.State.Status -eq 'running' -and
                    ($null -eq $health -or $health -eq 'healthy')) {
                return
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Container."
}

function Assert-ExclusiveProfile {
    $conflicting = @(
        'plainjournal-mysql-replica',
        'plainjournal-prometheus',
        'plainjournal-alertmanager',
        'plainjournal-grafana',
        'plainjournal-tempo'
    )
    $running = @(docker ps --format '{{.Names}}')
    $found = @($running | Where-Object { $_ -in $conflicting })
    if ($found.Count -gt 0) {
        throw 'M7 Trade resharding requires the scale, replica and observability ' +
            "profiles to be stopped. Running: $($found -join ', ')"
    }
}

function Set-ProcessEnvironment {
    param(
        [Parameter(Mandatory)][string]$Name,
        [AllowNull()][string]$Value
    )

    $environmentRestores.Add([pscustomobject]@{
            name = $Name
            value = [Environment]::GetEnvironmentVariable($Name, 'Process')
        })
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnvironment {
    for ($index = $environmentRestores.Count - 1; $index -ge 0; $index--) {
        $entry = $environmentRestores[$index]
        [Environment]::SetEnvironmentVariable($entry.name, $entry.value, 'Process')
    }
    $environmentRestores.Clear()
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Wait-PortFree {
    param([Parameter(Mandatory)][int]$Port)

    $deadline = (Get-Date).AddSeconds(30)
    do {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port `
                    -ErrorAction SilentlyContinue)) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Port $Port remained in use after stopping the Trade process."
}

function Wait-TradeHealthy {
    param([Parameter(Mandatory)][Diagnostics.Process]$Process)

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        if ($Process.HasExited) {
            throw "Trade process $($Process.Id) exited during startup."
        }
        try {
            $health = Invoke-RestMethod -Uri `
                "http://127.0.0.1:$tradePort/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') {
                return
            }
        }
        catch {
            # Startup is still in progress.
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for Trade on port $tradePort."
}

function Stop-TradeProcesses {
    foreach ($record in $processes) {
        if ($record.stopped) {
            continue
        }
        $actual = Get-CimInstance Win32_Process -Filter `
            "ProcessId=$($record.appProcessId)" -ErrorAction SilentlyContinue
        if ($actual) {
            if ($actual.Name -ne 'java.exe' -or
                    $actual.CommandLine -notlike "*$tradeJar*") {
                throw "Refusing to stop process $($record.appProcessId) outside the " +
                    'PlainJournal Trade verification.'
            }
            Stop-Process -Id $record.appProcessId -Force
            Wait-Process -Id $record.appProcessId -Timeout 15 `
                -ErrorAction SilentlyContinue
        }
        $record.stopped = $true
        Wait-PortFree -Port $tradePort
    }
    $untracked = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$tradeJar*" })
    foreach ($actual in $untracked) {
        Stop-Process -Id $actual.ProcessId -Force
        Wait-Process -Id $actual.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
    }
    Wait-PortFree -Port $tradePort
}

function Start-Trade {
    param(
        [Parameter(Mandatory)][ValidateSet('Source2', 'Target4')][string]$Topology,
        [Parameter(Mandatory)][string]$Label
    )

    Assert-PortAvailable -Port $tradePort
    $profile = if ($Topology -eq 'Source2') {
        'm7-trade-sharding'
    }
    else {
        'm7-trade-resharding'
    }
    Set-ProcessEnvironment -Name 'SPRING_PROFILES_ACTIVE' -Value $profile
    Set-ProcessEnvironment -Name 'TRADE_SERVICE_PORT' -Value "$tradePort"
    Set-ProcessEnvironment -Name 'IDENTITY_JWT_SECRET' -Value $env:IDENTITY_JWT_SECRET
    Set-ProcessEnvironment -Name 'TRADE_INTERNAL_SERVICE_TOKEN' -Value $env:TRADE_INTERNAL_SERVICE_TOKEN
    Set-ProcessEnvironment -Name 'PAYMENT_INTERNAL_SERVICE_TOKEN' -Value $env:PAYMENT_INTERNAL_SERVICE_TOKEN
    Set-ProcessEnvironment -Name 'METRICS_SCRAPE_TOKEN' -Value $env:METRICS_SCRAPE_TOKEN
    Set-ProcessEnvironment -Name 'TRADE_OUTBOX_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_PAYMENT_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_FULFILLMENT_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_REFUND_RESULT_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'TRADE_FLASH_SALE_CONSUMER_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'ECOMMERCE_TRADE_RECONCILIATION_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'ECOMMERCE_TRADE_ORDER_RECOVERY_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'ECOMMERCE_TRADE_DISTRIBUTED_ID_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'SPRING_CLOUD_NACOS_DISCOVERY_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'SPRING_CLOUD_NACOS_CONFIG_ENABLED' -Value 'false'
    Set-ProcessEnvironment -Name 'OTLP_TRACING_EXPORT_ENABLED' -Value 'false'
    if ($Topology -eq 'Source2') {
        Set-ProcessEnvironment -Name 'TRADE_SHARD_DB_USER' -Value $databaseUser
        Set-ProcessEnvironment -Name 'TRADE_SHARD_DB_PASSWORD' -Value $databasePassword
        Set-ProcessEnvironment -Name 'TRADE_SHARD_0_URL' -Value (
            "jdbc:mysql://127.0.0.1:13306/$($sourceSchemas[0])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' +
            '&useSSL=false&allowPublicKeyRetrieval=true')
        Set-ProcessEnvironment -Name 'TRADE_SHARD_1_URL' -Value (
            "jdbc:mysql://127.0.0.1:13326/$($sourceSchemas[1])" +
            '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' +
            '&useSSL=false&allowPublicKeyRetrieval=true')
    }
    else {
        Set-ProcessEnvironment -Name 'TRADE_RESHARD_DB_USER' -Value $databaseUser
        Set-ProcessEnvironment -Name 'TRADE_RESHARD_DB_PASSWORD' -Value $databasePassword
        for ($targetIndex = 0; $targetIndex -lt 4; $targetIndex++) {
            $port = if (($targetIndex % 2) -eq 0) { 13306 } else { 13326 }
            Set-ProcessEnvironment -Name "TRADE_RESHARD_${targetIndex}_URL" -Value (
                "jdbc:mysql://127.0.0.1:$port/$($targetSchemas[$targetIndex])" +
                '?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true' +
                '&useSSL=false&allowPublicKeyRetrieval=true')
        }
    }

    $stdout = Join-Path $runDirectory "$Label.stdout.log"
    $stderr = Join-Path $runDirectory "$Label.stderr.log"
    $java = (Get-Command java -ErrorAction Stop).Source
    $process = Start-Process -FilePath $java -ArgumentList @(
        '-Xms128m',
        '-Xmx384m',
        '-XX:+UseG1GC',
        '-jar',
        $tradeJar
    ) -WorkingDirectory $backendRoot -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    Restore-ProcessEnvironment
    $record = [pscustomobject]@{
        process = $process
        appProcessId = 0
        label = $Label
        stdout = $stdout
        stderr = $stderr
        stopped = $false
    }
    $processes.Add($record)
    Wait-TradeHealthy -Process $process
    $listener = Get-NetTCPConnection -State Listen -LocalPort $tradePort `
        -ErrorAction Stop | Select-Object -First 1
    $applicationProcess = Get-CimInstance Win32_Process -Filter `
        "ProcessId=$($listener.OwningProcess)"
    if ($applicationProcess.Name -ne 'java.exe' -or
            $applicationProcess.CommandLine -notlike "*$tradeJar*") {
        throw "Port $tradePort is not owned by the expected Trade JVM."
    }
    $record.appProcessId = [int]$applicationProcess.ProcessId
    return $record
}

function Stop-Trade {
    param([Parameter(Mandatory)]$Record)

    if ($Record.stopped) {
        return
    }
    $actual = Get-CimInstance Win32_Process -Filter `
        "ProcessId=$($Record.appProcessId)" -ErrorAction SilentlyContinue
    if ($actual) {
        if ($actual.Name -ne 'java.exe' -or
                $actual.CommandLine -notlike "*$tradeJar*") {
            throw "Refusing to stop process $($Record.appProcessId) outside the " +
                'PlainJournal Trade verification.'
        }
        Stop-Process -Id $Record.appProcessId -Force
        Wait-Process -Id $Record.appProcessId -Timeout 15 `
            -ErrorAction SilentlyContinue
    }
    $Record.stopped = $true
    Wait-PortFree -Port $tradePort
}

function Initialize-SchemasAndUser {
    $passwordLiteral = ConvertTo-SqlLiteral -Value $databasePassword
    for ($physicalShard = 0; $physicalShard -lt 2; $physicalShard++) {
        $schemas = if ($physicalShard -eq 0) {
            @($sourceSchemas[0], $targetSchemas[0], $targetSchemas[2])
        }
        else {
            @($sourceSchemas[1], $targetSchemas[1], $targetSchemas[3])
        }
        $sql = [Text.StringBuilder]::new()
        foreach ($schema in $schemas) {
            [void]$sql.AppendLine("DROP DATABASE IF EXISTS $schema;")
            [void]$sql.AppendLine(
                "CREATE DATABASE $schema CHARACTER SET utf8mb4 " +
                'COLLATE utf8mb4_0900_ai_ci;')
        }
        [void]$sql.AppendLine(
            "DROP USER IF EXISTS '$databaseUser'@'%';")
        [void]$sql.AppendLine(
            "CREATE USER '$databaseUser'@'%' IDENTIFIED BY $passwordLiteral;")
        foreach ($schema in $schemas) {
            [void]$sql.AppendLine(
                "GRANT ALL PRIVILEGES ON $schema.* TO '$databaseUser'@'%';")
        }
        [void]$sql.AppendLine('FLUSH PRIVILEGES;')
        [void](Invoke-ContainerMySql -PhysicalShard $physicalShard `
            -Sql $sql.ToString())
    }
    $script:resourcesCreated = $true
}

function Get-UserFixtureSql {
    param([Parameter(Mandatory)][long]$UserId)

    $orderId = 7800000000000000000L + $UserId
    $itemId = 7800000000001000000L + $UserId
    $historyId = 7800000000002000000L + $UserId
    $addressId = 7800000000003000000L + $UserId
    $benefitId = 7800000000004000000L + $UserId
    $priceId = 7800000000005000000L + $UserId
    $allocationId = 7800000000006000000L + $UserId
    $flashId = 7800000000007000000L + $UserId
    $reconciliationId = 7800000000008000000L + $UserId
    $orderNo = "M7RS-ORD-$UserId"
    $requestToken = "M7RS-FST-$UserId"
    $hash = $UserId.ToString('x').PadLeft(64, '0')
    $eventSuffix = $UserId.ToString().PadLeft(12, '0')
    $consumedEventId = "70000000-0000-4000-8000-$eventSuffix"
    $orderOutboxId = "71000000-0000-4000-8000-$eventSuffix"
    $flashOutboxId = "72000000-0000-4000-8000-$eventSuffix"
    return @"
INSERT INTO cart_user_lock (user_id, created_at, updated_at)
VALUES ($UserId, '2026-07-23 00:00:00.000', '2026-07-23 00:00:00.000');

INSERT INTO cart_item (
    id, user_id, product_id, sku_id, quantity, selected,
    created_at, updated_at, product_title, sku_name, spec_json, unit_price
) VALUES (
    $($orderId + 50), $UserId, $($UserId + 20000), $($UserId + 30000),
    1, TRUE, '2026-07-23 00:00:00.000', '2026-07-23 00:00:00.000',
    'M7 reshard cart product $UserId', 'M7 reshard cart SKU $UserId',
    '{"fixture":"m7-resharding"}', 100.00
);

INSERT INTO cart_merge_request (
    id, user_id, merge_key, request_hash, created_at
) VALUES (
    $($orderId + 60), $UserId, 'M7RS-MERGE-$UserId', '$hash',
    '2026-07-23 00:00:00.000'
);

INSERT INTO trade_order (
    id, order_no, user_id, idempotency_key, request_hash, reservation_no,
    warehouse_code, warehouse_id, status, original_amount, discount_amount,
    total_amount, marketing_lock_no, payment_deadline, order_source,
    source_reference, close_reason, recovery_attempts, next_recovery_at,
    last_error, version, created_at, updated_at
) VALUES (
    $orderId, '$orderNo', $UserId, 'M7RS-IDEMP-$UserId', '$hash',
    'M7RS-RSV-$UserId', 'PRIMARY', 1, 'COMPLETED', 100.00, 10.00,
    90.00, 'M7RS-LOCK-$UserId', '2026-07-23 01:00:00.000',
    'STANDARD', NULL, NULL, 0, NULL, NULL, 5,
    '2026-07-23 00:00:00.000', '2026-07-23 00:10:00.000'
);

INSERT INTO order_item (
    id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
    spec_json, image_object_key, unit_price, quantity, line_amount,
    created_at, line_no, discount_amount, payable_amount
) VALUES (
    $itemId, $orderId, $($UserId + 20000), $($UserId + 30000),
    'M7 reshard product $UserId', 'M7RS-SKU-$UserId',
    'M7 reshard SKU $UserId', '{"fixture":"m7-resharding"}',
    NULL, 100.00, 1, 100.00, '2026-07-23 00:00:00.000',
    1, 10.00, 90.00
);

INSERT INTO order_status_history (
    id, order_id, from_status, to_status, command, reason,
    operator_type, operator_id, created_at
) VALUES (
    $historyId, $orderId, 'SHIPPED', 'COMPLETED', 'M7_RESHARD_FIXTURE',
    'M7 active reshard verification', 'SYSTEM', 'm7-resharding',
    '2026-07-23 00:10:00.000'
);

INSERT INTO order_address_snapshot (
    id, order_id, source_address_id, recipient_name, phone, province,
    city, district, detail_address, postal_code, created_at,
    province_code, city_code, district_code
) VALUES (
    $addressId, $orderId, $($UserId + 40000), 'M7 Reshard User $UserId',
    '13800000000', '浙江省', '杭州市', '西湖区',
    'M7 reshard road $UserId', '310000', '2026-07-23 00:00:00.000',
    '330000', '330100', '330106'
);

INSERT INTO order_benefit_selection (id, order_id, benefit_no, created_at)
VALUES (
    $benefitId, $orderId, 'M7RS-BENEFIT-$UserId',
    '2026-07-23 00:00:00.000'
);

INSERT INTO order_price_snapshot (
    id, order_id, marketing_lock_no, original_amount, coupon_discount,
    red_packet_discount, subsidy_discount, discount_amount, payable_amount,
    pricing_version, created_at
) VALUES (
    $priceId, $orderId, 'M7RS-LOCK-$UserId', 100.00, 10.00,
    0.00, 0.00, 10.00, 90.00, 'm7-reshard-v1',
    '2026-07-23 00:00:00.000'
);

INSERT INTO order_discount_allocation (
    id, order_id, order_item_id, line_no, sku_id, benefit_no, rule_code,
    benefit_type, discount_amount, created_at
) VALUES (
    $allocationId, $orderId, $itemId, 1, $($UserId + 30000),
    'M7RS-BENEFIT-$UserId', 'M7RS-RULE', 'COUPON', 10.00,
    '2026-07-23 00:00:00.000'
);

INSERT INTO flash_sale_order_request (
    id, request_token, admission_event_id, request_hash, activity_no,
    user_id, address_id, product_id, sku_id, sale_price, status,
    order_no, failure_code, attempts, next_attempt_at, last_error,
    version, accepted_at, activity_ends_at, completed_at, created_at, updated_at
) VALUES (
    $flashId, '$requestToken', '73000000-0000-4000-8000-$eventSuffix',
    '$hash', 'M7RS-ACTIVITY', $UserId, $($UserId + 40000),
    $($UserId + 20000), $($UserId + 30000), 90.00, 'ORDER_CREATED',
    '$orderNo', NULL, 0, '2026-07-23 00:00:00.000', NULL, 1,
    '2026-07-23 00:00:00.000', '2026-07-24 00:00:00.000',
    '2026-07-23 00:10:00.000', '2026-07-23 00:00:00.000',
    '2026-07-23 00:10:00.000'
);

INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, published_at,
    last_error, created_at, updated_at, claim_owner, claim_until,
    destination_topic
) VALUES (
    '$orderOutboxId', 'OrderCompleted', 'TradeOrder', '$orderNo', 5,
    '{"fixture":"m7-resharding"}', 'PUBLISHED', 0,
    '2026-07-23 00:00:00.000', NULL, '2026-07-23 00:10:00.000',
    NULL, '2026-07-23 00:00:00.000', '2026-07-23 00:10:00.000',
    NULL, NULL, 'm7-resharding-order-events'
);

INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, published_at,
    last_error, created_at, updated_at, claim_owner, claim_until,
    destination_topic
) VALUES (
    '$flashOutboxId', 'FlashSaleOrderSucceeded', 'FlashSaleOrderRequest',
    '$requestToken', 1, '{"fixture":"m7-resharding"}', 'PUBLISHED', 0,
    '2026-07-23 00:00:00.000', NULL, '2026-07-23 00:10:00.000',
    NULL, '2026-07-23 00:00:00.000', '2026-07-23 00:10:00.000',
    NULL, NULL, 'm7-resharding-flash-events'
);

INSERT INTO consumed_event (
    event_id, consumer_group, owner_user_id, consumed_at
) VALUES (
    '$consumedEventId', 'm7-resharding-consumer', $UserId,
    '2026-07-23 00:10:00.000'
);

INSERT INTO reconciliation_record (
    id, domain, reference_no, issue_type, status, occurrences,
    first_detected_at, last_detected_at, resolved_at
) VALUES (
    $reconciliationId, 'ORDER', '$orderNo', 'M7_RESHARD_FIXTURE',
    'RESOLVED', 1, '2026-07-23 00:10:00.000',
    '2026-07-23 00:10:00.000', '2026-07-23 00:11:00.000'
);
"@
}

function Get-AfterSaleFixtureSql {
    param([Parameter(Mandatory)][long]$UserId)

    $orderId = 7800000000000000000L + $UserId
    $itemId = 7800000000001000000L + $UserId
    $afterSaleId = 7800000000009000000L + $UserId
    $afterSaleItemId = 7800000000010000000L + $UserId
    $afterSaleHistoryId = 7800000000011000000L + $UserId
    $orderNo = "M7RS-ORD-$UserId"
    $afterSaleNo = "M7RS-AS-$UserId"
    $hash = $UserId.ToString('x').PadLeft(64, '0')
    $eventSuffix = $UserId.ToString().PadLeft(12, '0')
    return @"
INSERT INTO after_sale_order (
    id, after_sale_no, order_id, order_no, user_id, after_sale_type, status,
    idempotency_key, request_hash, reason, review_reason, refund_amount,
    warehouse_id, reservation_no, return_receipt_no, refund_no, version,
    created_at, updated_at, approved_at, completed_at
) VALUES (
    $afterSaleId, '$afterSaleNo', $orderId, '$orderNo', $UserId,
    'RETURN_REFUND', 'COMPLETED', 'M7RS-AS-IDEMP-$UserId', '$hash',
    'M7 reshard fixture', 'approved', 90.00, 1, 'M7RS-RSV-$UserId',
    'M7RS-RETURN-$UserId', 'M7RS-REFUND-$UserId', 6,
    '2026-07-23 00:20:00.000', '2026-07-23 00:30:00.000',
    '2026-07-23 00:21:00.000', '2026-07-23 00:30:00.000'
);

INSERT INTO after_sale_item (
    id, after_sale_id, order_item_id, line_no, sku_id, product_title,
    sku_name, quantity, line_amount, discount_amount, refundable_amount,
    created_at
) VALUES (
    $afterSaleItemId, $afterSaleId, $itemId, 1, $($UserId + 30000),
    'M7 reshard product $UserId', 'M7 reshard SKU $UserId',
    1, 100.00, 10.00, 90.00, '2026-07-23 00:20:00.000'
);

INSERT INTO after_sale_history (
    id, after_sale_id, from_status, to_status, command, reason,
    operator_type, operator_id, created_at
) VALUES (
    $afterSaleHistoryId, $afterSaleId, 'REFUNDING', 'COMPLETED',
    'M7_RESHARD_FIXTURE', 'M7 active reshard verification',
    'SYSTEM', 'm7-resharding', '2026-07-23 00:30:00.000'
);

INSERT INTO outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, published_at,
    last_error, created_at, updated_at, claim_owner, claim_until,
    destination_topic
) VALUES (
    '74000000-0000-4000-8000-$eventSuffix', 'AfterSaleCompleted',
    'AfterSaleOrder', '$afterSaleNo', 6, '{"fixture":"m7-resharding"}',
    'PUBLISHED', 0, '2026-07-23 00:20:00.000', NULL,
    '2026-07-23 00:30:00.000', NULL, '2026-07-23 00:20:00.000',
    '2026-07-23 00:30:00.000', NULL, NULL,
    'm7-resharding-after-sale-events'
);
"@
}

function Seed-Users {
    param([Parameter(Mandatory)][long[]]$Users)

    foreach ($physicalShard in 0..1) {
        $sql = [Text.StringBuilder]::new()
        [void]$sql.AppendLine("USE $($sourceSchemas[$physicalShard]);")
        foreach ($userId in $Users | Where-Object { ($_ % 2) -eq $physicalShard }) {
            [void]$sql.AppendLine((Get-UserFixtureSql -UserId $userId))
            if ($userId -in @(1000L, 1001L)) {
                [void]$sql.AppendLine((Get-AfterSaleFixtureSql -UserId $userId))
            }
        }
        if ($sql.Length -gt 0) {
            [void](Invoke-ContainerMySql -PhysicalShard $physicalShard `
                -Sql $sql.ToString())
        }
    }
}

function Invoke-ReshardTool {
    param(
        [Parameter(Mandatory)][string]$ToolAction,
        [string]$ToolJobId = $jobId,
        [string]$ToolCheckpointPath = $checkpointPath,
        [switch]$ExpectFailure,
        [switch]$InjectFailure,
        [switch]$Fence,
        [switch]$ConfirmRollback
    )

    $outputPath = Join-Path $runDirectory (
        "$ToolJobId-$ToolAction-$([Guid]::NewGuid().ToString('N')).json")
    $arguments = @{
        Action = $ToolAction
        JobId = $ToolJobId
        BatchSize = $BatchSize
        SourceShard0Schema = $sourceSchemas[0]
        SourceShard1Schema = $sourceSchemas[1]
        TargetShard0Schema = $targetSchemas[0]
        TargetShard1Schema = $targetSchemas[1]
        TargetShard2Schema = $targetSchemas[2]
        TargetShard3Schema = $targetSchemas[3]
        EnvironmentFile = $environmentFile
        CheckpointPath = $ToolCheckpointPath
        OutputPath = $outputPath
    }
    if ($InjectFailure) {
        $arguments.FailAfterCommittedBatches = 1
    }
    if ($Fence) {
        $arguments.FinalWriteFence = $true
    }
    if ($ConfirmRollback) {
        $arguments.ConfirmNoTargetWrites = $true
    }

    $captured = @()
    $succeeded = $true
    try {
        $captured = @(& $reshardingTool @arguments 2>&1)
    }
    catch {
        $succeeded = $false
        $captured += ($_ | Out-String)
        $captured += $_.Exception.ToString()
    }
    if ($ExpectFailure) {
        if ($succeeded) {
            throw "Expected $ToolAction to fail, but it succeeded."
        }
        return [ordered]@{
            failedAsExpected = $true
            output = ($captured -join [Environment]::NewLine)
        }
    }
    if (-not $succeeded) {
        throw "Resharding tool action $ToolAction failed: " +
            ($captured -join [Environment]::NewLine)
    }
    if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
        throw "Resharding tool action $ToolAction did not write JSON output."
    }
    $result = Get-Content -LiteralPath $outputPath -Raw | ConvertFrom-Json
    Remove-Item -LiteralPath $outputPath -Force
    return $result
}

function Get-Checkpoint {
    param([string]$Path = $checkpointPath)

    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Apply-OnlineMutations {
    Seed-Users -Users $lateUsers
    [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
UPDATE $($sourceSchemas[0]).trade_order
SET version = version + 1, updated_at = '2026-07-23 02:00:00.000'
WHERE user_id = 1000;
DELETE FROM $($sourceSchemas[0]).cart_item WHERE user_id = 1000;
UPDATE $($sourceSchemas[0]).outbox_event
SET attempts = 2, updated_at = '2026-07-23 02:00:00.000'
WHERE id = '71000000-0000-4000-8000-000000001002';
"@)
    [void](Invoke-ContainerMySql -PhysicalShard 1 -Sql @"
UPDATE $($sourceSchemas[1]).trade_order
SET version = version + 1, updated_at = '2026-07-23 02:00:00.000'
WHERE user_id = 1001;
INSERT INTO $($sourceSchemas[1]).cart_item (
    id, user_id, product_id, sku_id, quantity, selected,
    created_at, updated_at, product_title, sku_name, spec_json, unit_price
) VALUES (
    7800000000099001001, 1001, 99001, 99101, 2, TRUE,
    '2026-07-23 02:00:00.000', '2026-07-23 02:00:00.000',
    'M7 online cart product', 'M7 online cart SKU',
    '{"fixture":"m7-online"}', 55.00
);
INSERT INTO $($sourceSchemas[1]).consumed_event (
    event_id, consumer_group, owner_user_id, consumed_at
) VALUES (
    '75000000-0000-4000-8000-000000001003',
    'm7-resharding-online-consumer', 1003, '2026-07-23 02:00:00.000'
);
"@)
}

function Get-SourceOrderCounts {
    return @(
        [long](Get-Scalar -PhysicalShard 0 `
            -Sql "SELECT COUNT(*) FROM $($sourceSchemas[0]).trade_order;"),
        [long](Get-Scalar -PhysicalShard 1 `
            -Sql "SELECT COUNT(*) FROM $($sourceSchemas[1]).trade_order;")
    )
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=').
        Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    param([Parameter(Mandatory)][long]$UserId)

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + 3600
        jti = [Guid]::NewGuid().ToString()
        roles = @('CUSTOMER')
    } | ConvertTo-Json -Compress
    $unsigned = "$(ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header)))." +
        "$(ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload)))"
    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($env:IDENTITY_JWT_SECRET))
    try {
        $signature = ConvertTo-Base64Url (
            $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    }
    finally {
        $hmac.Dispose()
    }
    return "$unsigned.$signature"
}

function Verify-FourShardReads {
    $results = [Collections.Generic.List[object]]::new()
    foreach ($userId in @(1000L, 1001L, 1002L, 1003L)) {
        $headers = @{ Authorization = "Bearer $(New-AccessToken -UserId $userId)" }
        $response = Invoke-RestMethod -Uri `
            "http://127.0.0.1:$tradePort/api/v1/trade/orders" `
            -Headers $headers -TimeoutSec 15
        $items = @($response.data)
        if ($items.Count -ne 1 -or $items[0].orderNo -ne "M7RS-ORD-$userId") {
            throw "Four-shard read routing failed for user $userId."
        }
        $targetIndex = $userId % 4
        $physicalCount = [long](Get-Scalar -PhysicalShard ($targetIndex % 2) -Sql (
                "SELECT COUNT(*) FROM $($targetSchemas[$targetIndex]).trade_order " +
                "WHERE user_id = $userId;"))
        if ($physicalCount -ne 1) {
            throw "User $userId is absent from expected target shard $targetIndex."
        }
        $results.Add([ordered]@{
                userId = $userId
                targetShard = $targetIndex
                orderNo = $items[0].orderNo
                apiCount = $items.Count
                physicalCount = $physicalCount
            })
    }

    $wrongOwnerStatus = $null
    try {
        Invoke-WebRequest -Uri `
            "http://127.0.0.1:$tradePort/api/v1/trade/orders/M7RS-ORD-1001" `
            -Headers @{ Authorization = "Bearer $(New-AccessToken -UserId 1000)" } `
            -TimeoutSec 15 | Out-Null
        $wrongOwnerStatus = 200
    }
    catch {
        $wrongOwnerStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($wrongOwnerStatus -ne 404) {
        throw "Cross-owner order lookup returned HTTP $wrongOwnerStatus instead of 404."
    }
    return [ordered]@{
        users = @($results)
        crossOwnerStatus = $wrongOwnerStatus
    }
}

function Assert-Value {
    param(
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected, Actual=$Actual"
    }
}

try {
    [IO.Directory]::CreateDirectory($runDirectory) | Out-Null
    Import-DotEnv -Path $environmentFile
    foreach ($required in @(
            'MYSQL_ROOT_PASSWORD',
            'IDENTITY_JWT_SECRET',
            'TRADE_INTERNAL_SERVICE_TOKEN',
            'PAYMENT_INTERNAL_SERVICE_TOKEN',
            'METRICS_SCRAPE_TOKEN')) {
        if (-not [Environment]::GetEnvironmentVariable($required, 'Process')) {
            throw "$required is required."
        }
    }
    if (-not (Test-Path -LiteralPath $reshardingTool -PathType Leaf)) {
        throw "Resharding tool does not exist: $reshardingTool"
    }
    if (-not $SkipNetworkPreflight) {
        Invoke-TimedStage -Name 'network-preflight' -Body {
            & $networkCheck
            if ($LASTEXITCODE -ne 0) {
                throw 'Host preflight failed.'
            }
        }
    }
    Assert-ExclusiveProfile
    Wait-ContainerHealthy -Container 'plainjournal-mysql'
    $secondInspect = docker inspect plainjournal-mysql-trade-shard-1 2>$null
    $secondShardExistedBefore = $LASTEXITCODE -eq 0
    if ($secondShardExistedBefore) {
        $secondState = @($secondInspect | ConvertFrom-Json)[0]
        $secondShardRunningBefore = [bool]$secondState.State.Running
    }
    if (-not $secondShardRunningBefore) {
        Invoke-TimedStage -Name 'start-second-shard' -Body {
            docker compose --env-file $environmentFile -f $composeFile `
                --profile m7-trade-sharding up -d mysql-trade-shard-1
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to start the M7 Trade second MySQL shard.'
            }
        }
    }
    Wait-ContainerHealthy -Container 'plainjournal-mysql-trade-shard-1'

    if (-not $SkipBuild) {
        Invoke-TimedStage -Name 'build-trade' -Body {
            & mvn -q -f (Join-Path $backendRoot 'pom.xml') `
                -pl services/trade-service -am package -DskipTests
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to build the Trade service.'
            }
        }
    }
    if (-not (Test-Path -LiteralPath $tradeJar -PathType Leaf)) {
        throw "Trade JAR does not exist: $tradeJar"
    }

    Invoke-TimedStage -Name 'initialize-schemas-and-user' -Body {
        Initialize-SchemasAndUser
    }
    Invoke-TimedStage -Name 'flyway-source-two-shards' -Body {
        $sourceTrade = Start-Trade -Topology Source2 -Label 'source-migration'
        Stop-Trade -Record $sourceTrade
    }
    Invoke-TimedStage -Name 'flyway-target-four-shards' -Body {
        $targetTrade = Start-Trade -Topology Target4 -Label 'target-migration'
        Stop-Trade -Record $targetTrade
    }

    Invoke-TimedStage -Name 'seed-source-fixtures' -Body {
        Seed-Users -Users $sourceUsers
        [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
INSERT INTO $($sourceSchemas[0]).consumer_failure (
    message_id, consumer_group, raw_payload, attempts, status, last_error,
    first_failed_at, last_failed_at, recovered_at
) VALUES (
    'M7RS-CONTROL-1', 'm7-resharding-control', '{"fixture":"control"}',
    2, 'RECOVERED', 'fixture', '2026-07-23 00:00:00.000',
    '2026-07-23 00:01:00.000', '2026-07-23 00:02:00.000'
);
"@)
    }
    $sourceFactsBefore = Get-SourceOrderCounts
    Assert-Value -Actual $sourceFactsBefore[0] -Expected 4 `
        -Message 'Source shard 0 fixture count is incorrect.'
    Assert-Value -Actual $sourceFactsBefore[1] -Expected 4 `
        -Message 'Source shard 1 fixture count is incorrect.'

    $nullOwnerFailure = Invoke-TimedStage -Name 'null-owner-cutover-gate' -Body {
        [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
INSERT INTO $($sourceSchemas[0]).consumed_event (
    event_id, consumer_group, owner_user_id, consumed_at
) VALUES (
    '76000000-0000-4000-8000-000000000000',
    'm7-resharding-legacy', NULL, '2026-07-23 00:00:00.000'
);
"@)
        $failed = Invoke-ReshardTool -ToolAction Initialize `
            -ToolJobId "$jobId-NULL" `
            -ToolCheckpointPath (Join-Path $runDirectory 'checkpoint-null.json') `
            -ExpectFailure
        [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql (
                "DELETE FROM $($sourceSchemas[0]).consumed_event " +
                "WHERE event_id = '76000000-0000-4000-8000-000000000000';"))
        return $failed
    }
    $evidence.nullOwnerGate = $nullOwnerFailure
    if ($nullOwnerFailure.output -notmatch 'without owner_user_id') {
        throw "Legacy NULL consumed_event ownership was not rejected for the " +
            "expected reason. Child output: $($nullOwnerFailure.output)"
    }

    Invoke-TimedStage -Name 'initialize-resharding-job' -Body {
        [void](Invoke-ReshardTool -ToolAction Initialize)
    }
    $interruption = Invoke-TimedStage -Name 'inject-committed-interruption' -Body {
        Invoke-ReshardTool -ToolAction Copy -ExpectFailure -InjectFailure
    }
    if ($interruption.output -notmatch 'Injected Trade resharding interruption') {
        throw 'The expected committed-batch interruption did not occur.'
    }
    $evidence.expectedInterruption = $interruption
    $checkpointAfterInterruption = Get-Checkpoint
    Assert-Value -Actual ([long]$checkpointAfterInterruption.sources[0].lastUserId) `
        -Expected 1002L -Message 'Source shard 0 checkpoint did not persist.'
    Assert-Value -Actual ([long]$checkpointAfterInterruption.sources[1].lastUserId) `
        -Expected 0L -Message 'Source shard 1 advanced before interruption.'
    $evidence.checkpointAfterInterruption = $checkpointAfterInterruption

    Invoke-TimedStage -Name 'source-online-mutations' -Body {
        Apply-OnlineMutations
    }
    $evidence.onlineMutation = [ordered]@{
        lateUsers = $lateUsers
        updatedUsers = @(1000L, 1001L)
        deletedCartUser = 1000L
        addedCartUser = 1001L
        addedConsumedEventUser = 1003L
    }

    $resumed = Invoke-TimedStage -Name 'resume-initial-copy' -Body {
        Invoke-ReshardTool -ToolAction Copy
    }
    Assert-Value -Actual $resumed.status -Expected 'COPIED' `
        -Message 'Initial copy did not complete.'
    $evidence.resumedCopy = $resumed
    $beforeIdempotent = [long]$resumed.totalCommittedBatches
    $rerun = Invoke-TimedStage -Name 'idempotent-copy-rerun' -Body {
        Invoke-ReshardTool -ToolAction Copy
    }
    Assert-Value -Actual ([long]$rerun.totalCommittedBatches) `
        -Expected $beforeIdempotent `
        -Message 'Completed initial copy created duplicate batches.'
    $evidence.idempotentCopy = $rerun

    $fenced = Invoke-TimedStage -Name 'final-write-fence-and-catch-up' -Body {
        Invoke-ReshardTool -ToolAction CatchUp -Fence
    }
    Assert-Value -Actual $fenced.status -Expected 'FENCED' `
        -Message 'Final catch-up did not establish the cutover fence.'
    $evidence.finalCatchUp = $fenced

    [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
UPDATE $($targetSchemas[2]).trade_order
SET total_amount = 91.00
WHERE user_id = 1002;
"@)
    $corruptionFailure = Invoke-TimedStage -Name 'corruption-cutover-gate' -Body {
        Invoke-ReshardTool -ToolAction Verify -ExpectFailure
    }
    if ($corruptionFailure.output -notmatch 'verification failed') {
        throw 'Target corruption did not block verification.'
    }
    [void](Invoke-ReshardTool -ToolAction CatchUp -Fence)
    $evidence.corruptionGate = $corruptionFailure

    $verified = Invoke-TimedStage -Name 'verify-four-shard-fingerprints' -Body {
        Invoke-ReshardTool -ToolAction Verify
    }
    Assert-Value -Actual $verified.verified -Expected $true `
        -Message 'Four-shard fingerprint verification failed.'
    foreach ($table in $verified.tables) {
        Assert-Value -Actual $table.matches -Expected $true `
            -Message (
                "Fingerprint mismatch for shard $($table.targetShard) " +
                "table $($table.table).")
    }
    $evidence.verification = $verified

    [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
UPDATE $($targetSchemas[2]).trade_order
SET total_amount = 92.00
WHERE user_id = 1002;
"@)
    $staleVerificationFailure = Invoke-TimedStage `
        -Name 'reject-stale-verification-at-promotion' -Body {
        Invoke-ReshardTool -ToolAction Promote -ExpectFailure
    }
    if ($staleVerificationFailure.output -notmatch 'verification failed') {
        throw 'Promotion trusted stale resharding verification after target mutation.'
    }
    $checkpointAfterRejectedPromotion = Get-Checkpoint
    if ($checkpointAfterRejectedPromotion.status -eq 'PROMOTED') {
        throw 'Failed stale-verification promotion advanced the checkpoint.'
    }
    $evidence.staleVerificationPromotionGate = [ordered]@{
        failure = $staleVerificationFailure
        checkpointStatus = $checkpointAfterRejectedPromotion.status
    }

    [void](Invoke-ReshardTool -ToolAction CatchUp -Fence)
    $promotionVerification = Invoke-TimedStage `
        -Name 'verify-immediately-before-promotion' -Body {
        Invoke-ReshardTool -ToolAction Verify
    }
    Assert-Value -Actual $promotionVerification.verified -Expected $true `
        -Message 'Repaired target failed verification before promotion.'
    $evidence.verificationBeforePromotion = $promotionVerification

    $promoted = Invoke-TimedStage -Name 'promote-four-shard-read' -Body {
        Invoke-ReshardTool -ToolAction Promote
    }
    Assert-Value -Actual $promoted.status -Expected 'PROMOTED' `
        -Message 'Four-shard target was not promoted.'
    $evidence.promotion = $promoted

    $readRouting = Invoke-TimedStage -Name 'four-shard-jvm-read-routing' -Body {
        $targetTrade = Start-Trade -Topology Target4 -Label 'target-read-routing'
        try {
            Verify-FourShardReads
        }
        finally {
            Stop-Trade -Record $targetTrade
        }
    }
    $evidence.fourShardReadRouting = $readRouting

    $rollback = Invoke-TimedStage -Name 'rollback-to-source-two-shards' -Body {
        Invoke-ReshardTool -ToolAction Rollback -ConfirmRollback
    }
    Assert-Value -Actual $rollback.status -Expected 'ROLLED_BACK' `
        -Message 'Resharding rollback did not complete.'
    $targetOrdersAfterRollback = for ($targetIndex = 0; $targetIndex -lt 4; $targetIndex++) {
        [long](Get-Scalar -PhysicalShard ($targetIndex % 2) -Sql (
                "SELECT COUNT(*) FROM $($targetSchemas[$targetIndex]).trade_order;"))
    }
    if (@($targetOrdersAfterRollback | Where-Object { $_ -ne 0 }).Count -ne 0) {
        throw 'Target facts remained after controlled rollback.'
    }
    $sourceFactsAfterRollback = Get-SourceOrderCounts
    Assert-Value -Actual $sourceFactsAfterRollback[0] -Expected 5 `
        -Message 'Rollback changed source shard 0 facts.'
    Assert-Value -Actual $sourceFactsAfterRollback[1] -Expected 5 `
        -Message 'Rollback changed source shard 1 facts.'
    $evidence.rollback = [ordered]@{
        status = $rollback.status
        targetOrderCounts = @($targetOrdersAfterRollback)
        sourceOrderCounts = @($sourceFactsAfterRollback)
    }

    Invoke-TimedStage -Name 'replay-after-rollback' -Body {
        [void](Invoke-ReshardTool -ToolAction Initialize `
            -ToolJobId $replayJobId -ToolCheckpointPath $replayCheckpointPath)
        [void](Invoke-ReshardTool -ToolAction Copy `
            -ToolJobId $replayJobId -ToolCheckpointPath $replayCheckpointPath)
        [void](Invoke-ReshardTool -ToolAction CatchUp `
            -ToolJobId $replayJobId -ToolCheckpointPath $replayCheckpointPath -Fence)
        [void](Invoke-ReshardTool -ToolAction Verify `
            -ToolJobId $replayJobId -ToolCheckpointPath $replayCheckpointPath)
        [void](Invoke-ReshardTool -ToolAction Promote `
            -ToolJobId $replayJobId -ToolCheckpointPath $replayCheckpointPath)
    }
    $replayed = Get-Checkpoint -Path $replayCheckpointPath
    Assert-Value -Actual $replayed.status -Expected 'PROMOTED' `
        -Message 'Rollback replay did not return to PROMOTED.'
    $evidence.replayAfterRollback = $replayed
    $evidence.sourceFacts = [ordered]@{
        beforeMigration = @($sourceFactsBefore)
        afterOnlineWrites = @(5, 5)
        afterRollback = @($sourceFactsAfterRollback)
        deletedByMigration = 0
    }

    $verificationSucceeded = $true
}
catch {
    $executionError = $_
}
finally {
    try {
        Restore-ProcessEnvironment
        Stop-TradeProcesses
    }
    catch {
        $cleanupErrors.Add("Trade process cleanup failed: $($_.Exception.Message)")
    }

    if ($resourcesCreated) {
        for ($physicalShard = 0; $physicalShard -lt 2; $physicalShard++) {
            try {
                $schemas = if ($physicalShard -eq 0) {
                    @($sourceSchemas[0], $targetSchemas[0], $targetSchemas[2])
                }
                else {
                    @($sourceSchemas[1], $targetSchemas[1], $targetSchemas[3])
                }
                $sql = [Text.StringBuilder]::new()
                foreach ($schema in $schemas) {
                    [void]$sql.AppendLine("DROP DATABASE IF EXISTS $schema;")
                }
                [void]$sql.AppendLine("DROP USER IF EXISTS '$databaseUser'@'%';")
                [void](Invoke-ContainerMySql -PhysicalShard $physicalShard `
                    -Sql $sql.ToString())
            }
            catch {
                $cleanupErrors.Add(
                    "schema cleanup physical shard $physicalShard failed: " +
                    $_.Exception.Message)
            }
        }
    }

    foreach ($checkpoint in @(
            $checkpointPath,
            $replayCheckpointPath,
            (Join-Path $runDirectory 'checkpoint-null.json'))) {
        try {
            if (Test-Path -LiteralPath $checkpoint -PathType Leaf) {
                Remove-Item -LiteralPath $checkpoint -Force
            }
        }
        catch {
            $cleanupErrors.Add(
                "checkpoint cleanup failed for $checkpoint`: $($_.Exception.Message)")
        }
    }

    try {
        if (-not $secondShardRunningBefore) {
            docker stop plainjournal-mysql-trade-shard-1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to stop the second shard container.'
            }
        }
        if (-not $secondShardExistedBefore) {
            docker rm plainjournal-mysql-trade-shard-1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to remove the temporary second shard container.'
            }
        }
    }
    catch {
        $cleanupErrors.Add("container restore failed: $($_.Exception.Message)")
    }

    $remainingSchemas = @()
    for ($physicalShard = 0; $physicalShard -lt 2; $physicalShard++) {
        if ($physicalShard -eq 1 -and -not $secondShardRunningBefore) {
            $remainingSchemas += 0
            continue
        }
        try {
            $schemaList = if ($physicalShard -eq 0) {
                @($sourceSchemas[0], $targetSchemas[0], $targetSchemas[2])
            }
            else {
                @($sourceSchemas[1], $targetSchemas[1], $targetSchemas[3])
            }
            $literals = ($schemaList | ForEach-Object {
                    ConvertTo-SqlLiteral -Value $_
                }) -join ','
            $remainingSchemas += [long](Get-Scalar -PhysicalShard $physicalShard -Sql @"
SELECT COUNT(*)
FROM information_schema.schemata
WHERE schema_name IN ($literals);
"@)
        }
        catch {
            $cleanupErrors.Add(
                "schema cleanup verification physical shard $physicalShard failed: " +
                $_.Exception.Message)
        }
    }
    $remainingJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$tradeJar*" }).Count
    $portRemaining = @(Get-NetTCPConnection -State Listen -LocalPort $tradePort `
        -ErrorAction SilentlyContinue).Count
    $evidence.cleanup = [ordered]@{
        schemasRemaining = @($remainingSchemas)
        javaProcessesRemaining = $remainingJava
        portsRemaining = $portRemaining
        secondShardExistedBefore = $secondShardExistedBefore
        secondShardRunningBefore = $secondShardRunningBefore
        secondShardRestored = if ($secondShardRunningBefore) {
            (docker inspect --format '{{.State.Running}}' `
                    plainjournal-mysql-trade-shard-1 2>$null) -eq 'true'
        }
        elseif ($secondShardExistedBefore) {
            (docker inspect --format '{{.State.Running}}' `
                    plainjournal-mysql-trade-shard-1 2>$null) -eq 'false'
        }
        else {
            docker inspect plainjournal-mysql-trade-shard-1 2>$null | Out-Null
            $LASTEXITCODE -ne 0
        }
        cleanupErrors = @($cleanupErrors)
    }
    $evidence.stages = @($stages)
    $evidence.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    $evidence.succeeded = $verificationSucceeded -and
        $cleanupErrors.Count -eq 0 -and
        @($remainingSchemas | Where-Object { $_ -ne 0 }).Count -eq 0 -and
        $remainingJava -eq 0 -and $portRemaining -eq 0
    if ($executionError) {
        $evidence.error = $executionError.Exception.Message
    }
    [IO.File]::WriteAllText(
        $evidencePath,
        ($evidence | ConvertTo-Json -Depth 20) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

if ($executionError) {
    throw $executionError
}
if ($cleanupErrors.Count -gt 0) {
    throw "M7 Trade resharding cleanup failed: " + ($cleanupErrors -join '; ')
}
if (-not $evidence.succeeded) {
    throw "M7 Trade resharding verification did not satisfy all gates. " +
        "Evidence: $evidencePath"
}
Write-Host "M7 Trade 2-to-4 resharding verification passed: $evidencePath"
