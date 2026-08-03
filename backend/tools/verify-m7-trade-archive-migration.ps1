#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [ValidateRange(1, 100)]
    [int]$BatchSize = 2
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
$composeFile = Join-Path $composeDirectory 'compose.yml'
$environmentFile = Join-Path $composeDirectory '.env'
$networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
$archiveTool = Join-Path $PSScriptRoot 'invoke-m7-trade-archive-migration.ps1'
$migrationDirectory = Join-Path $backendRoot `
    'services\trade-service\src\main\resources\db\migration'
$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDirectory = Join-Path $backendRoot ".run\m7-trade-archive-$timestamp"
$evidencePath = Join-Path $runDirectory 'verification.json'
$probe = "m7arch$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$jobId = "M7-ARCHIVE-$($probe.ToUpperInvariant())"
$sourceSchemas = @("${probe}_source_0", "${probe}_source_1")
$archiveSchemas = @("${probe}_archive_0", "${probe}_archive_1")
$containers = @('plainjournal-mysql', 'plainjournal-mysql-trade-shard-1')
$cutoffAt = '2026-06-01 00:00:00.000'
$executionError = $null
$verificationSucceeded = $false
$shardContainerExistedBefore = $false
$shardContainerRunningBefore = $false
$schemasCreated = $false
$cleanupErrors = [Collections.Generic.List[string]]::new()
$stages = [Collections.Generic.List[object]]::new()
$evidence = [ordered]@{
    startedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    jobId = $jobId
    sourceSchemas = $sourceSchemas
    archiveSchemas = $archiveSchemas
    cutoffAt = $cutoffAt
    batchSize = $BatchSize
    expectedInterruption = $null
    checkpointAfterInterruption = $null
    onlineWatermarkRefresh = $null
    idempotentRerun = $null
    verificationBeforeCorruption = $null
    corruptionGate = $null
    verificationAfterRepair = $null
    staleVerificationPromotionGate = $null
    verificationBeforePromotion = $null
    promotedRead = $null
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
        $stages.Add([pscustomobject]@{
                name = $Name
                startedAtUtc = $started.ToString('O')
                completedAtUtc = $completed.ToString('O')
                durationMs = [Math]::Round(
                    ($completed - $started).TotalMilliseconds, 3)
            })
    }
}

function Invoke-ContainerMySql {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$Shard,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD",
        $containers[$Shard],
        'mysql',
        '--user=root',
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names'
    )
    $output = @($Sql | & docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed on shard $Shard`: " +
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
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$Shard,
        [Parameter(Mandatory)][string]$Sql
    )

    $rows = @((Invoke-ContainerMySql -Shard $Shard -Sql $Sql).Lines)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar row on shard $Shard, received $($rows.Count)."
    }
    return $rows[0]
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [ValidateRange(10, 300)][int]$TimeoutSeconds = 120
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

function Assert-ExclusiveProfile {
    $conflicting = @(
        'plainjournal-mysql-replica',
        'plainjournal-prometheus',
        'plainjournal-alertmanager',
        'plainjournal-grafana',
        'plainjournal-tempo'
    )
    $running = @(docker ps --format '{{.Names}}')
    $found = @($conflicting | Where-Object { $running -contains $_ })
    if ($found.Count -gt 0) {
        throw "M7 Trade archive verification uses an exclusive database profile. " +
            "Stop conflicting containers first: $($found -join ', ')"
    }
}

function Initialize-SourceSchemas {
    for ($shard = 0; $shard -lt 2; $shard++) {
        [void](Invoke-ContainerMySql -Shard $shard -Sql @"
DROP DATABASE IF EXISTS $($sourceSchemas[$shard]);
DROP DATABASE IF EXISTS $($archiveSchemas[$shard]);
CREATE DATABASE $($sourceSchemas[$shard])
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@)
        $migrationFiles = @(Get-ChildItem -LiteralPath $migrationDirectory `
                -Filter 'V*__*.sql' -File |
            Sort-Object {
                [int]([regex]::Match($_.Name, '^V(\d+)').Groups[1].Value)
            })
        foreach ($migration in $migrationFiles) {
            $sql = "USE $($sourceSchemas[$shard]);`n" +
                [IO.File]::ReadAllText($migration.FullName)
            [void](Invoke-ContainerMySql -Shard $shard -Sql $sql)
        }
    }
    $script:schemasCreated = $true
}

function Get-OrderFixtureSql {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$Shard,
        [Parameter(Mandatory)][ValidateRange(1, 99)][int]$Sequence,
        [Parameter(Mandatory)][string]$Status,
        [Parameter(Mandatory)][string]$UpdatedAt,
        [ValidateSet('NONE', 'COMPLETED', 'REFUNDING')]
        [string]$AfterSale = 'NONE',
        [ValidateSet('NONE', 'PUBLISHED', 'PENDING')]
        [string]$Outbox = 'PUBLISHED',
        [switch]$OpenReconciliation
    )

    $prefix = 7600000000000000000L + ($Shard * 1000000000L)
    $orderId = $prefix + $Sequence
    $itemId = $prefix + 100000L + $Sequence
    $addressId = $prefix + 200000L + $Sequence
    $priceId = $prefix + 300000L + $Sequence
    $benefitId = $prefix + 400000L + $Sequence
    $allocationId = $prefix + 500000L + $Sequence
    $historyId = $prefix + 600000L + $Sequence
    $afterSaleId = $prefix + 700000L + $Sequence
    $afterSaleItemId = $prefix + 800000L + $Sequence
    $afterSaleHistoryId = $prefix + 900000L + $Sequence
    $reconciliationId = $prefix + 950000L + $Sequence
    $orderNo = "M7A-S$Shard-O$Sequence"
    $afterSaleNo = "M7A-S$Shard-A$Sequence"
    $hash = $Sequence.ToString('x').PadLeft(64, '0')
    $closeReason = if ($Status -in @('CANCELED', 'CLOSED')) {
        "'M7_ARCHIVE_FIXTURE'"
    }
    else {
        'NULL'
    }
    $sql = [Text.StringBuilder]::new()
    [void]$sql.AppendLine(@"
INSERT INTO $($sourceSchemas[$Shard]).trade_order (
    id, order_no, user_id, idempotency_key, request_hash, reservation_no,
    warehouse_code, warehouse_id, status, total_amount, payment_deadline,
    close_reason, recovery_attempts, next_recovery_at, last_error, version,
    created_at, updated_at, original_amount, discount_amount,
    marketing_lock_no, order_source, source_reference
) VALUES (
    $orderId, '$orderNo', $([long](900000 + $Shard * 100 + $Sequence)),
    'M7A-IDEMP-S$Shard-$Sequence', '$hash', 'M7A-RSV-S$Shard-$Sequence',
    'PRIMARY', NULL, '$Status', 90.00, '2026-01-01 01:00:00.000',
    $closeReason, 0, NULL, NULL, 5,
    '2026-01-01 00:00:00.000', '$UpdatedAt', 100.00, 10.00,
    'M7A-LOCK-S$Shard-$Sequence', 'STANDARD', NULL
);

INSERT INTO $($sourceSchemas[$Shard]).order_item (
    id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
    spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
    line_no, discount_amount, payable_amount
) VALUES (
    $itemId, $orderId, $($prefix + 2000 + $Sequence),
    $($prefix + 3000 + $Sequence), 'M7 archive product $Sequence',
    'M7A-SKU-$Shard-$Sequence', 'M7 archive SKU $Sequence',
    '{"fixture":"m7-archive"}', NULL, 100.00, 1, 100.00,
    '2026-01-01 00:00:00.000', 1, 10.00, 90.00
);

INSERT INTO $($sourceSchemas[$Shard]).order_address_snapshot (
    id, order_id, source_address_id, recipient_name, phone, province, city,
    district, detail_address, postal_code, created_at,
    province_code, city_code, district_code
) VALUES (
    $addressId, $orderId, $($prefix + 4000 + $Sequence), 'M7 Archive User',
    '13800000000', '浙江省', '杭州市', '西湖区', 'M7 archive road',
    '310000', '2026-01-01 00:00:00.000', '330000', '330100', '330106'
);

INSERT INTO $($sourceSchemas[$Shard]).order_benefit_selection (
    id, order_id, benefit_no, created_at
) VALUES (
    $benefitId, $orderId, 'M7A-BENEFIT-S$Shard-$Sequence',
    '2026-01-01 00:00:00.000'
);

INSERT INTO $($sourceSchemas[$Shard]).order_price_snapshot (
    id, order_id, marketing_lock_no, original_amount, coupon_discount,
    red_packet_discount, subsidy_discount, discount_amount, payable_amount,
    pricing_version, created_at
) VALUES (
    $priceId, $orderId, 'M7A-LOCK-S$Shard-$Sequence', 100.00, 10.00,
    0.00, 0.00, 10.00, 90.00, 'm7-archive-v1',
    '2026-01-01 00:00:00.000'
);

INSERT INTO $($sourceSchemas[$Shard]).order_discount_allocation (
    id, order_id, order_item_id, line_no, sku_id, benefit_no, rule_code,
    benefit_type, discount_amount, created_at
) VALUES (
    $allocationId, $orderId, $itemId, 1, $($prefix + 3000 + $Sequence),
    'M7A-BENEFIT-S$Shard-$Sequence', 'M7A-RULE', 'COUPON', 10.00,
    '2026-01-01 00:00:00.000'
);

INSERT INTO $($sourceSchemas[$Shard]).order_status_history (
    id, order_id, from_status, to_status, command, reason,
    operator_type, operator_id, created_at
) VALUES (
    $historyId, $orderId, 'SHIPPED', '$Status', 'M7_ARCHIVE_FIXTURE',
    'M7 archive migration verification', 'SYSTEM', 'm7-archive',
    '$UpdatedAt'
);
"@)

    if ($AfterSale -ne 'NONE') {
        [void]$sql.AppendLine(@"
INSERT INTO $($sourceSchemas[$Shard]).after_sale_order (
    id, after_sale_no, order_id, order_no, user_id, after_sale_type, status,
    idempotency_key, request_hash, reason, review_reason, refund_amount,
    warehouse_id, reservation_no, return_receipt_no, refund_no, version,
    created_at, updated_at, approved_at, completed_at
) VALUES (
    $afterSaleId, '$afterSaleNo', $orderId, '$orderNo',
    $([long](900000 + $Shard * 100 + $Sequence)), 'RETURN_REFUND',
    '$AfterSale', 'M7A-AS-IDEMP-S$Shard-$Sequence', '$hash',
    'M7 archive fixture', 'approved', 90.00, 1,
    'M7A-RSV-S$Shard-$Sequence', 'M7A-RETURN-S$Shard-$Sequence',
    'M7A-REFUND-S$Shard-$Sequence', 6,
    '2026-01-02 00:00:00.000', '$UpdatedAt',
    '2026-01-02 01:00:00.000',
    $(if ($AfterSale -eq 'COMPLETED') { "'$UpdatedAt'" } else { 'NULL' })
);

INSERT INTO $($sourceSchemas[$Shard]).after_sale_item (
    id, after_sale_id, order_item_id, line_no, sku_id, product_title,
    sku_name, quantity, line_amount, discount_amount, refundable_amount,
    created_at
) VALUES (
    $afterSaleItemId, $afterSaleId, $itemId, 1,
    $($prefix + 3000 + $Sequence), 'M7 archive product $Sequence',
    'M7 archive SKU $Sequence', 1, 100.00, 10.00, 90.00,
    '2026-01-02 00:00:00.000'
);

INSERT INTO $($sourceSchemas[$Shard]).after_sale_history (
    id, after_sale_id, from_status, to_status, command, reason,
    operator_type, operator_id, created_at
) VALUES (
    $afterSaleHistoryId, $afterSaleId, 'REFUNDING', '$AfterSale',
    'M7_ARCHIVE_FIXTURE', 'M7 archive migration verification',
    'SYSTEM', 'm7-archive', '$UpdatedAt'
);
"@)
    }

    if ($Outbox -ne 'NONE') {
        $outboxId = [Guid]::NewGuid().ToString()
        $aggregateType = if ($AfterSale -ne 'NONE') {
            'AfterSaleOrder'
        }
        else {
            'TradeOrder'
        }
        $aggregateId = if ($AfterSale -ne 'NONE') { $afterSaleNo } else { $orderNo }
        [void]$sql.AppendLine(@"
INSERT INTO $($sourceSchemas[$Shard]).outbox_event (
    id, event_type, aggregate_type, aggregate_id, aggregate_version,
    payload, status, attempts, next_attempt_at, claimed_at, published_at,
    last_error, created_at, updated_at, claim_owner, claim_until,
    destination_topic
) VALUES (
    '$outboxId', 'M7ArchiveFixture', '$aggregateType', '$aggregateId', 5,
    '{"fixture":"m7-archive"}', '$Outbox', 0,
    '2026-01-01 00:00:00.000', NULL,
    $(if ($Outbox -eq 'PUBLISHED') { "'2026-01-01 00:00:01.000'" } else { 'NULL' }),
    NULL, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000',
    NULL, NULL, 'm7-archive-fixture'
);
"@)
    }

    if ($OpenReconciliation) {
        [void]$sql.AppendLine(@"
INSERT INTO $($sourceSchemas[$Shard]).reconciliation_record (
    id, domain, reference_no, issue_type, status, occurrences,
    first_detected_at, last_detected_at, resolved_at
) VALUES (
    $reconciliationId, 'TRADE', '$orderNo', 'M7_ARCHIVE_FIXTURE',
    'OPEN', 1, '2026-01-03 00:00:00.000',
    '2026-01-03 00:00:00.000', NULL
);
"@)
    }
    return $sql.ToString()
}

function Seed-InitialData {
    for ($shard = 0; $shard -lt 2; $shard++) {
        $sql = [Text.StringBuilder]::new()
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 1 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-10 00:00:00.000'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 2 `
                    -Status 'CANCELED' -UpdatedAt '2026-01-11 00:00:00.000'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 3 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-12 00:00:00.000' `
                    -AfterSale 'COMPLETED'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 10 `
                    -Status 'PENDING_PAYMENT' -UpdatedAt '2026-01-13 00:00:00.000'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 11 `
                    -Status 'COMPLETED' -UpdatedAt '2026-07-01 00:00:00.000'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 12 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-14 00:00:00.000' `
                    -Outbox 'PENDING'))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 13 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-15 00:00:00.000' `
                    -OpenReconciliation))
        [void]$sql.Append((Get-OrderFixtureSql -Shard $shard -Sequence 14 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-16 00:00:00.000' `
                    -AfterSale 'REFUNDING'))
        [void](Invoke-ContainerMySql -Shard $shard -Sql $sql.ToString())
    }
}

function Seed-LateEligibleData {
    for ($shard = 0; $shard -lt 2; $shard++) {
        [void](Invoke-ContainerMySql -Shard $shard -Sql (
                Get-OrderFixtureSql -Shard $shard -Sequence 20 `
                    -Status 'COMPLETED' -UpdatedAt '2026-01-20 00:00:00.000'))
    }
}

function Invoke-ArchiveTool {
    param(
        [Parameter(Mandatory)][string]$ToolAction,
        [switch]$ExpectFailure,
        [switch]$Refresh,
        [int]$FailAfter = 0
    )

    $arguments = @(
        '-NoProfile',
        '-File', $archiveTool,
        '-Action', $ToolAction,
        '-JobId', $jobId,
        '-CutoffAt', $cutoffAt,
        '-BatchSize', $BatchSize,
        '-SourceShard0Schema', $sourceSchemas[0],
        '-SourceShard1Schema', $sourceSchemas[1],
        '-ArchiveShard0Schema', $archiveSchemas[0],
        '-ArchiveShard1Schema', $archiveSchemas[1],
        '-EnvironmentFile', $environmentFile
    )
    if ($Refresh) {
        $arguments += '-RefreshWatermark'
    }
    if ($FailAfter -gt 0) {
        $arguments += @('-FailAfterCommittedBatches', $FailAfter)
    }

    $output = @(& pwsh @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = $output -join [Environment]::NewLine
    if ($ExpectFailure) {
        if ($exitCode -eq 0) {
            throw "Archive tool action $ToolAction was expected to fail."
        }
        return [pscustomobject]@{
            exitCode = $exitCode
            output = $text
        }
    }
    if ($exitCode -ne 0) {
        throw "Archive tool action $ToolAction failed: $text"
    }
    return $text | ConvertFrom-Json
}

function Get-ArchiveStatus {
    return Invoke-ArchiveTool -ToolAction 'Status'
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
    if (-not $env:MYSQL_ROOT_PASSWORD) {
        throw 'MYSQL_ROOT_PASSWORD is required.'
    }
    if (-not (Test-Path -LiteralPath $archiveTool -PathType Leaf)) {
        throw "Archive tool does not exist: $archiveTool"
    }

    if (-not $SkipNetworkPreflight) {
        if (-not (Test-Path -LiteralPath $networkCheck -PathType Leaf)) {
            throw "Network preflight script does not exist: $networkCheck"
        }
        Invoke-TimedStage -Name 'network-preflight' -Body {
            & $networkCheck
            if ($LASTEXITCODE -ne 0) {
                throw 'Machine network preflight failed.'
            }
        }
    }

    $shardInspect = docker inspect plainjournal-mysql-trade-shard-1 2>$null
    $shardContainerExistedBefore = $LASTEXITCODE -eq 0
    if ($shardContainerExistedBefore) {
        $shardState = @($shardInspect | ConvertFrom-Json)[0]
        $shardContainerRunningBefore = $shardState.State.Running
    }

    Assert-ExclusiveProfile
    Wait-ContainerHealthy -Container 'plainjournal-mysql'
    if (-not $shardContainerRunningBefore) {
        Invoke-TimedStage -Name 'start-second-shard' -Body {
            docker compose --env-file $environmentFile -f $composeFile `
                --profile m7-trade-sharding up -d mysql-trade-shard-1
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to start the M7 Trade second MySQL shard.'
            }
        }
    }
    Wait-ContainerHealthy -Container 'plainjournal-mysql-trade-shard-1'

    Invoke-TimedStage -Name 'initialize-source-schemas' -Body {
        Initialize-SourceSchemas
    }
    Invoke-TimedStage -Name 'seed-initial-data' -Body {
        Seed-InitialData
    }

    $sourceFactsBefore = @(
        [long](Get-Scalar -Shard 0 -Sql "SELECT COUNT(*) FROM $($sourceSchemas[0]).trade_order;"),
        [long](Get-Scalar -Shard 1 -Sql "SELECT COUNT(*) FROM $($sourceSchemas[1]).trade_order;")
    )
    Assert-Value -Actual $sourceFactsBefore[0] -Expected 8 `
        -Message 'Shard 0 source fixture count is incorrect.'
    Assert-Value -Actual $sourceFactsBefore[1] -Expected 8 `
        -Message 'Shard 1 source fixture count is incorrect.'

    Invoke-TimedStage -Name 'initialize-archive-job' -Body {
        [void](Invoke-ArchiveTool -ToolAction 'Initialize')
    }

    $expectedFailure = Invoke-TimedStage -Name 'inject-committed-interruption' -Body {
        Invoke-ArchiveTool -ToolAction 'Migrate' -ExpectFailure -FailAfter 1
    }
    $evidence.expectedInterruption = $expectedFailure
    if ($expectedFailure.output -notmatch 'Injected archive migration interruption') {
        throw "The expected interruption did not occur at the committed batch boundary. " +
            "Child output: $($expectedFailure.output)"
    }

    $checkpoint = Get-ArchiveStatus
    Assert-Value -Actual ([long]$checkpoint.status[0].migratedOrders) `
        -Expected $BatchSize `
        -Message 'Shard 0 did not retain the committed archive checkpoint.'
    Assert-Value -Actual ([long]$checkpoint.status[1].migratedOrders) `
        -Expected 0 `
        -Message 'Shard 1 advanced despite interruption before its first batch.'
    $evidence.checkpointAfterInterruption = $checkpoint.status

    Invoke-TimedStage -Name 'seed-online-late-candidates' -Body {
        Seed-LateEligibleData
    }
    $resumed = Invoke-TimedStage -Name 'resume-with-watermark-refresh' -Body {
        Invoke-ArchiveTool -ToolAction 'Migrate' -Refresh
    }
    foreach ($status in $resumed.status) {
        Assert-Value -Actual ([long]$status.eligibleOrders) -Expected 4 `
            -Message "Shard $($status.shard) did not refresh the online watermark."
        Assert-Value -Actual ([long]$status.migratedOrders) -Expected 4 `
            -Message "Shard $($status.shard) did not resume to completion."
        Assert-Value -Actual $status.status -Expected 'COPIED' `
            -Message "Shard $($status.shard) did not enter COPIED."
    }
    $evidence.onlineWatermarkRefresh = $resumed.status

    $rerun = Invoke-TimedStage -Name 'idempotent-copy-rerun' -Body {
        Invoke-ArchiveTool -ToolAction 'Migrate'
    }
    Assert-Value -Actual ([int]$rerun.result.committedBatches) -Expected 0 `
        -Message 'A completed archive copy created duplicate batches.'
    $evidence.idempotentRerun = $rerun

    $verified = Invoke-TimedStage -Name 'verify-source-target-fingerprints' -Body {
        Invoke-ArchiveTool -ToolAction 'Verify'
    }
    foreach ($shardVerification in $verified.result.shards) {
        Assert-Value -Actual $shardVerification.verified -Expected $true `
            -Message "Shard $($shardVerification.shard) fingerprint verification failed."
        foreach ($table in $shardVerification.tables) {
            Assert-Value -Actual $table.matches -Expected $true `
                -Message "Shard $($shardVerification.shard) table $($table.table) mismatched."
        }
    }
    $evidence.verificationBeforeCorruption = $verified.result.shards

    $corruptOrderId = 7600000000000000000L + 1000000000L + 1
    [void](Invoke-ContainerMySql -Shard 1 -Sql @"
UPDATE $($archiveSchemas[1]).trade_order
SET total_amount = total_amount + 1.00
WHERE id = $corruptOrderId;
"@)
    $corruptionFailure = Invoke-TimedStage -Name 'verify-corruption-gate' -Body {
        Invoke-ArchiveTool -ToolAction 'Verify' -ExpectFailure
    }
    if ($corruptionFailure.output -notmatch 'Archive verification failed on shard 1') {
        throw 'Archive corruption was not rejected by the shard fingerprint gate.'
    }
    $evidence.corruptionGate = $corruptionFailure

    [void](Invoke-ContainerMySql -Shard 1 -Sql @"
UPDATE $($archiveSchemas[1]).trade_order archive_order
JOIN $($sourceSchemas[1]).trade_order source_order
  ON source_order.id = archive_order.id
SET archive_order.total_amount = source_order.total_amount
WHERE archive_order.id = $corruptOrderId;
"@)
    $reverified = Invoke-TimedStage -Name 'verify-after-repair' -Body {
        Invoke-ArchiveTool -ToolAction 'Verify'
    }
    $evidence.verificationAfterRepair = $reverified.result.shards

    [void](Invoke-ContainerMySql -Shard 1 -Sql @"
UPDATE $($archiveSchemas[1]).trade_order
SET total_amount = total_amount + 2.00
WHERE id = $corruptOrderId;
"@)
    $staleVerificationFailure = Invoke-TimedStage `
        -Name 'reject-stale-verification-at-promotion' -Body {
        Invoke-ArchiveTool -ToolAction 'Promote' -ExpectFailure
    }
    if ($staleVerificationFailure.output -notmatch
            'Archive verification failed on shard 1') {
        throw 'Promotion trusted stale archive verification after target mutation.'
    }
    $cutoverCounts = for ($shard = 0; $shard -lt 2; $shard++) {
        [long](Get-Scalar -Shard $shard -Sql @"
SELECT COUNT(*)
FROM $($archiveSchemas[$shard]).trade_archive_read_cutover
WHERE cutover_key = 'historical-orders';
"@)
    }
    if (@($cutoverCounts | Where-Object { $_ -ne 0 }).Count -ne 0) {
        throw 'Failed stale-verification promotion activated an archive read cutover.'
    }
    $evidence.staleVerificationPromotionGate = [ordered]@{
        failure = $staleVerificationFailure
        cutoverCounts = @($cutoverCounts)
    }

    [void](Invoke-ContainerMySql -Shard 1 -Sql @"
UPDATE $($archiveSchemas[1]).trade_order archive_order
JOIN $($sourceSchemas[1]).trade_order source_order
  ON source_order.id = archive_order.id
SET archive_order.total_amount = source_order.total_amount
WHERE archive_order.id = $corruptOrderId;
"@)
    $promotionVerification = Invoke-TimedStage `
        -Name 'verify-immediately-before-promotion' -Body {
        Invoke-ArchiveTool -ToolAction 'Verify'
    }
    $evidence.verificationBeforePromotion = $promotionVerification.result.shards

    $promoted = Invoke-TimedStage -Name 'promote-read-cutover' -Body {
        Invoke-ArchiveTool -ToolAction 'Promote'
    }
    $archiveRead = @(
        Get-Scalar -Shard 0 -Sql @"
SELECT CONCAT(order_no, '|', status, '|', total_amount)
FROM $($archiveSchemas[0]).trade_order
WHERE id = 7600000000000000001;
"@
        Get-Scalar -Shard 1 -Sql @"
SELECT CONCAT(order_no, '|', status, '|', total_amount)
FROM $($archiveSchemas[1]).trade_order
WHERE id = $corruptOrderId;
"@
    )
    Assert-Value -Actual $archiveRead[0] -Expected 'M7A-S0-O1|COMPLETED|90.00' `
        -Message 'Shard 0 promoted archive read is incorrect.'
    Assert-Value -Actual $archiveRead[1] -Expected 'M7A-S1-O1|COMPLETED|90.00' `
        -Message 'Shard 1 promoted archive read is incorrect.'
    $evidence.promotedRead = [ordered]@{
        status = $promoted.status
        samples = $archiveRead
    }

    $sourceFactsWithLateCandidate = @(
        [long](Get-Scalar -Shard 0 -Sql "SELECT COUNT(*) FROM $($sourceSchemas[0]).trade_order;"),
        [long](Get-Scalar -Shard 1 -Sql "SELECT COUNT(*) FROM $($sourceSchemas[1]).trade_order;")
    )
    Assert-Value -Actual $sourceFactsWithLateCandidate[0] -Expected 9 `
        -Message 'Shard 0 source changed unexpectedly before rollback.'
    Assert-Value -Actual $sourceFactsWithLateCandidate[1] -Expected 9 `
        -Message 'Shard 1 source changed unexpectedly before rollback.'

    $rolledBack = Invoke-TimedStage -Name 'rollback-archive-copy' -Body {
        Invoke-ArchiveTool -ToolAction 'Rollback'
    }
    for ($shard = 0; $shard -lt 2; $shard++) {
        $archiveOrders = [long](Get-Scalar -Shard $shard -Sql @"
SELECT COUNT(*) FROM $($archiveSchemas[$shard]).trade_order;
"@)
        $sourceOrders = [long](Get-Scalar -Shard $shard -Sql @"
SELECT COUNT(*) FROM $($sourceSchemas[$shard]).trade_order;
"@)
        Assert-Value -Actual $archiveOrders -Expected 0 `
            -Message "Shard $shard rollback left archive orders."
        Assert-Value -Actual $sourceOrders -Expected 9 `
            -Message "Shard $shard rollback deleted source facts."
        Assert-Value -Actual $rolledBack.status[$shard].readCutoverActive `
            -Expected $false `
            -Message "Shard $shard rollback left read cutover active."
    }
    $evidence.rollback = $rolledBack

    Invoke-TimedStage -Name 'replay-after-rollback' -Body {
        [void](Invoke-ArchiveTool -ToolAction 'Initialize')
        [void](Invoke-ArchiveTool -ToolAction 'Migrate')
        [void](Invoke-ArchiveTool -ToolAction 'Verify')
        [void](Invoke-ArchiveTool -ToolAction 'Promote')
    }
    $replayed = Get-ArchiveStatus
    foreach ($status in $replayed.status) {
        Assert-Value -Actual $status.status -Expected 'PROMOTED' `
            -Message "Shard $($status.shard) did not replay after rollback."
        Assert-Value -Actual ([long]$status.migratedOrders) -Expected 4 `
            -Message "Shard $($status.shard) replay count is incorrect."
        Assert-Value -Actual $status.readCutoverActive -Expected $true `
            -Message "Shard $($status.shard) replay did not activate cutover."
    }
    $evidence.replayAfterRollback = $replayed.status
    $evidence.sourceFacts = [ordered]@{
        beforeMigration = $sourceFactsBefore
        afterOnlineCandidate = $sourceFactsWithLateCandidate
        deletedByMigration = 0
    }

    $verificationSucceeded = $true
}
catch {
    $executionError = $_
}
finally {
    if ($schemasCreated) {
        for ($shard = 0; $shard -lt 2; $shard++) {
            try {
                [void](Invoke-ContainerMySql -Shard $shard -Sql @"
DROP DATABASE IF EXISTS $($archiveSchemas[$shard]);
DROP DATABASE IF EXISTS $($sourceSchemas[$shard]);
"@)
            }
            catch {
                $cleanupErrors.Add(
                    "schema cleanup shard $shard failed: $($_.Exception.Message)")
            }
        }
    }

    try {
        if (-not $shardContainerRunningBefore) {
            docker stop plainjournal-mysql-trade-shard-1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to stop the second shard container.'
            }
        }
        if (-not $shardContainerExistedBefore) {
            docker rm plainjournal-mysql-trade-shard-1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to remove the temporary second shard container.'
            }
        }
    }
    catch {
        $cleanupErrors.Add("container restore failed: $($_.Exception.Message)")
    }

    $schemaCleanup = @()
    for ($shard = 0; $shard -lt 2; $shard++) {
        try {
            $sourceExists = [int](Get-Scalar -Shard $shard -Sql @"
SELECT COUNT(*) FROM information_schema.schemata
WHERE schema_name IN ('$($sourceSchemas[$shard])', '$($archiveSchemas[$shard])');
"@)
            $schemaCleanup += $sourceExists
        }
        catch {
            if ($shard -eq 1 -and -not $shardContainerRunningBefore) {
                $schemaCleanup += 0
            }
            else {
                $cleanupErrors.Add(
                    "schema cleanup verification shard $shard failed: " +
                    $_.Exception.Message)
            }
        }
    }
    $evidence.cleanup = [ordered]@{
        sourceAndArchiveSchemasRemaining = $schemaCleanup
        secondShardExistedBefore = $shardContainerExistedBefore
        secondShardRunningBefore = $shardContainerRunningBefore
        secondShardRestored = if ($shardContainerRunningBefore) {
            (docker inspect --format '{{.State.Running}}' `
                    plainjournal-mysql-trade-shard-1 2>$null) -eq 'true'
        }
        elseif ($shardContainerExistedBefore) {
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
        @($schemaCleanup | Where-Object { $_ -ne 0 }).Count -eq 0
    if ($executionError) {
        $evidence.error = $executionError.Exception.Message
    }
    [IO.File]::WriteAllText(
        $evidencePath,
        ($evidence | ConvertTo-Json -Depth 16) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

if ($executionError) {
    throw $executionError
}
if ($cleanupErrors.Count -gt 0) {
    throw "M7 Trade archive verification cleanup failed: " +
        ($cleanupErrors -join '; ')
}
if (-not $evidence.succeeded) {
    throw "M7 Trade archive verification did not satisfy all gates. Evidence: $evidencePath"
}
Write-Host "M7 Trade archive migration verification passed: $evidencePath"
