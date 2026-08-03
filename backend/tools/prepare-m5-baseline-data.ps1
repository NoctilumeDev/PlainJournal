#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Seed', 'Verify', 'Remove')]
    [string]$Action = 'Verify',
    [ValidateRange(1, 10000)]
    [int]$SpuCount = 1000,
    [ValidateRange(1, 10)]
    [int]$SkuPerSpu = 3,
    [ValidateRange(1, 10000)]
    [int]$UserCount = 1000,
    [ValidateRange(1, 20)]
    [int]$CartItemsPerUser = 3,
    [ValidateRange(1, 100000)]
    [int]$OrderCount = 10000,
    [ValidateRange(0, 10000)]
    [int]$DenseUserOrderCount = 1000,
    [ValidateRange(1, 1000000000)]
    [long]$StockPerSku = 1000,
    [string]$ManifestPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$fixturePrefix = 'M5'
$fixtureEmailSuffix = '@plainjournal.local'
$fixturePasswordHash = '$2b$12$KREIAX0KbHhyffdoXFSQfeS/27kwWbzT0tcYWXQ2RZBJM20NihfL2'
$businessPorts = @(18000, 18101, 18102, 18103, 18104, 18105, 18106, 18107)
$id = [ordered]@{
    category = [long]7100000000000000001
    brand = [long]7100000000000000002
    productBase = [long]7110000000000000000
    skuBase = [long]7120000000000000000
    userBase = [long]7130000000000000000
    addressBase = [long]7140000000000000000
    cartBase = [long]7150000000000000000
    orderBase = [long]7160000000000000000
    orderItemBase = [long]7170000000000000000
    orderAddressBase = [long]7180000000000000000
    priceSnapshotBase = [long]7190000000000000000
    orderHistoryBase = [long]7200000000000000000
    warehouse = [long]7210000000000000001
    inventoryBalanceBase = [long]7220000000000000000
    stockAdjustmentBase = [long]7230000000000000000
    stockMovementBase = [long]7240000000000000000
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

function Assert-RequiredEnvironment {
    $required = @(
        'CATALOG_DB_NAME', 'CATALOG_DB_USER', 'CATALOG_DB_PASSWORD',
        'IDENTITY_DB_NAME', 'IDENTITY_DB_USER', 'IDENTITY_DB_PASSWORD',
        'TRADE_DB_NAME', 'TRADE_DB_USER', 'TRADE_DB_PASSWORD',
        'INVENTORY_DB_NAME', 'INVENTORY_DB_USER', 'INVENTORY_DB_PASSWORD',
        'MARKETING_DB_NAME', 'MARKETING_DB_USER', 'MARKETING_DB_PASSWORD',
        'PAYMENT_DB_NAME', 'PAYMENT_DB_USER', 'PAYMENT_DB_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
        })
    if ($missing.Count -gt 0) {
        throw "Missing required local database settings: $($missing -join ', ')"
    }
}

function Assert-BusinessApplicationsStopped {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $businessPorts -contains $_.LocalPort })
    if ($listeners.Count -gt 0) {
        $details = $listeners |
            Sort-Object LocalPort |
            ForEach-Object { "$($_.LocalPort)/pid=$($_.OwningProcess)" }
        throw "M5 baseline data is an offline fixture. Stop business applications first: $($details -join ', ')"
    }
}

function Assert-MySqlReady {
    $status = docker inspect --format '{{.State.Status}}' plainjournal-mysql 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne 'running') {
        throw 'The plainjournal-mysql container must already be running.'
    }
}

function Invoke-DatabaseSql {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$Capture
    )

    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$Password",
        'plainjournal-mysql',
        'mysql',
        "--user=$User",
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names',
        $Database
    )
    if ($Capture) {
        $output = @($Sql | docker @arguments 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL command failed for $Database`: $($output -join [Environment]::NewLine)"
        }
        return $output
    }

    $output = @($Sql | docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed for $Database`: $($output -join [Environment]::NewLine)"
    }
}

function Add-BatchedInsert {
    param(
        [Parameter(Mandatory)][Text.StringBuilder]$Builder,
        [Parameter(Mandatory)][string]$Header,
        [Parameter(Mandatory)][int]$Count,
        [Parameter(Mandatory)][scriptblock]$RowFactory,
        [int]$BatchSize = 500
    )

    for ($start = 1; $start -le $Count; $start += $BatchSize) {
        $end = [Math]::Min($Count, $start + $BatchSize - 1)
        $rows = [Collections.Generic.List[string]]::new($end - $start + 1)
        for ($index = $start; $index -le $end; $index++) {
            $rows.Add([string](& $RowFactory $index))
        }
        [void]$Builder.AppendLine($Header)
        [void]$Builder.AppendLine(($rows -join ",`n"))
        [void]$Builder.AppendLine(';')
    }
}

function Get-FixedTimestamp {
    param([int]$OffsetSeconds)

    return [DateTimeOffset]::new(2026, 1, 1, 0, 0, 0, [TimeSpan]::Zero).
        AddSeconds($OffsetSeconds).
        ToString('yyyy-MM-dd HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture)
}

function Get-DecimalText {
    param([decimal]$Value)

    return $Value.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
}

function Get-UserIndexForOrder {
    param([int]$OrderIndex)

    if ($OrderIndex -le $DenseUserOrderCount) {
        return 1
    }
    if ($UserCount -eq 1) {
        return 1
    }
    return 2 + (($OrderIndex - $DenseUserOrderCount - 1) % ($UserCount - 1))
}

function Get-OrderNumber {
    param([int]$OrderIndex)

    return "$fixturePrefix-ORD-$($OrderIndex.ToString('00000000'))"
}

function Get-EventId {
    param(
        [int]$OrderIndex,
        [int]$Slot
    )

    return "50000000-0000-4000-8$($Slot)00-$($OrderIndex.ToString('000000000000'))"
}

function Remove-M5Data {
    $paymentSql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM reconciliation_record
WHERE reference_no IN (
    SELECT payment_no FROM payment_order
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM outbox_event
WHERE aggregate_id IN (
    SELECT payment_no FROM payment_order
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM payment_callback_log
WHERE payment_no IN (
    SELECT payment_no FROM payment_order
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM payment_transaction
WHERE payment_id IN (
    SELECT id FROM payment_order
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM payment_order
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:PAYMENT_DB_NAME -User $env:PAYMENT_DB_USER `
        -Password $env:PAYMENT_DB_PASSWORD -Sql $paymentSql

    $marketingSql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM pricing_lock_allocation
WHERE lock_id IN (
    SELECT id FROM pricing_lock
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM pricing_lock_benefit
WHERE lock_id IN (
    SELECT id FROM pricing_lock
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM pricing_lock
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM user_benefit
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:MARKETING_DB_NAME -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD -Sql $marketingSql

    $inventorySql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM reconciliation_record
WHERE reference_no LIKE '$($id.warehouse):%'
   OR reference_no LIKE 'M5-%'
   OR reference_no IN (
       SELECT reservation_no FROM inventory_reservation
       WHERE warehouse_id = $($id.warehouse)
   );
DELETE FROM outbox_event
WHERE aggregate_id IN (
    SELECT reservation_no FROM inventory_reservation
    WHERE warehouse_id = $($id.warehouse)
);
DELETE FROM inventory_return WHERE warehouse_id = $($id.warehouse);
DELETE FROM inventory_reservation_item
WHERE reservation_id IN (
    SELECT id FROM inventory_reservation
    WHERE warehouse_id = $($id.warehouse)
);
DELETE FROM inventory_reservation WHERE warehouse_id = $($id.warehouse);
DELETE FROM stock_movement WHERE warehouse_id = $($id.warehouse);
DELETE FROM stock_adjustment WHERE warehouse_id = $($id.warehouse);
DELETE FROM inventory_balance WHERE warehouse_id = $($id.warehouse);
DELETE FROM warehouse WHERE id = $($id.warehouse) OR code = 'PRIMARY';
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD -Sql $inventorySql

    $tradeSql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM reconciliation_record WHERE reference_no LIKE 'M5-%';
DELETE FROM reconciliation_record
WHERE reference_no IN (
    SELECT order_no FROM trade_order
    WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM consumed_event WHERE event_id LIKE '50000000-0000-4000-8%';
DELETE FROM outbox_event
WHERE aggregate_id LIKE 'M5-%'
   OR aggregate_id IN (
       SELECT order_no FROM trade_order
       WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
   );
DELETE FROM after_sale_history
WHERE after_sale_id IN (
    SELECT id FROM after_sale_order
    WHERE after_sale_no LIKE 'M5-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM after_sale_item
WHERE after_sale_id IN (
    SELECT id FROM after_sale_order
    WHERE after_sale_no LIKE 'M5-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM after_sale_order
WHERE after_sale_no LIKE 'M5-%'
   OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM order_status_history
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM order_discount_allocation
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM order_price_snapshot
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM order_benefit_selection
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM order_address_snapshot
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM order_item
WHERE order_id IN (
    SELECT id FROM trade_order
    WHERE order_no LIKE 'M5-ORD-%'
       OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
);
DELETE FROM trade_order
WHERE order_no LIKE 'M5-ORD-%'
   OR user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM cart_merge_request
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM cart_user_lock
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM cart_item
WHERE id BETWEEN $($id.cartBase + 1) AND $($id.cartBase + 1000000);
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -Sql $tradeSql

    $identitySql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM refresh_token
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM user_role
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM user_address
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
DELETE FROM user_account
WHERE id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000)
   OR email LIKE 'm5.user.%@plainjournal.local';
DELETE FROM login_record WHERE normalized_email LIKE 'm5.user.%@plainjournal.local';
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD -Sql $identitySql

    $catalogSql = @"
SET time_zone = '+00:00';
START TRANSACTION;
DELETE FROM product_media WHERE object_key LIKE 'm5/%';
DELETE FROM product_sku WHERE sku_code LIKE 'M5-SKU-%';
DELETE FROM product_spu
WHERE id BETWEEN $($id.productBase + 1) AND $($id.productBase + 1000000)
   OR title LIKE 'M5 Baseline Product %';
DELETE FROM catalog_brand WHERE id = $($id.brand) OR slug = 'm5-baseline-brand';
DELETE FROM catalog_category WHERE id = $($id.category) OR slug = 'm5-baseline-category';
COMMIT;
"@
    Invoke-DatabaseSql -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -Sql $catalogSql
}

function New-InventorySeedSql {
    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine("SET time_zone = '+00:00';")
    [void]$builder.AppendLine('START TRANSACTION;')
    [void]$builder.AppendLine(@"
INSERT INTO warehouse
    (id, code, name, status, version, created_at, updated_at)
VALUES
    ($($id.warehouse), 'PRIMARY', 'M5 Baseline Warehouse', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
"@)

    $skuCount = $SpuCount * $SkuPerSpu
    Add-BatchedInsert -Builder $builder -Count $skuCount -Header @'
INSERT INTO inventory_balance
    (id, warehouse_id, sku_id, on_hand, reserved, version, created_at, updated_at) VALUES
'@ -RowFactory {
        param($index)
        $balanceId = $id.inventoryBalanceBase + $index
        $skuId = $id.skuBase + $index
        $timestamp = Get-FixedTimestamp -OffsetSeconds (200000 + $index)
        "($balanceId, $($id.warehouse), $skuId, $StockPerSku, 0, 0, '$timestamp', '$timestamp')"
    }

    Add-BatchedInsert -Builder $builder -Count $skuCount -Header @'
INSERT INTO stock_adjustment
    (id, movement_no, request_hash, warehouse_id, sku_id, quantity_delta,
     reason, status, created_at, updated_at) VALUES
'@ -RowFactory {
        param($index)
        $adjustmentId = $id.stockAdjustmentBase + $index
        $skuId = $id.skuBase + $index
        $movementNo = "M5-ADJ-$($index.ToString('000000'))"
        $requestHash = $index.ToString('x').PadLeft(64, '0')
        $timestamp = Get-FixedTimestamp -OffsetSeconds (200000 + $index)
        "($adjustmentId, '$movementNo', '$requestHash', $($id.warehouse), $skuId, " +
            "$StockPerSku, 'Offline M5 capacity baseline stock', 'APPLIED', " +
            "'$timestamp', '$timestamp')"
    }

    Add-BatchedInsert -Builder $builder -Count $skuCount -Header @'
INSERT INTO stock_movement
    (id, movement_no, warehouse_id, sku_id, reservation_no, movement_type,
     quantity_delta, on_hand_after, reserved_after, reason, created_at) VALUES
'@ -RowFactory {
        param($index)
        $movementId = $id.stockMovementBase + $index
        $skuId = $id.skuBase + $index
        $movementNo = "M5-ADJ-$($index.ToString('000000'))"
        $timestamp = Get-FixedTimestamp -OffsetSeconds (200000 + $index)
        "($movementId, '$movementNo', $($id.warehouse), $skuId, NULL, 'ADJUSTMENT', " +
            "$StockPerSku, $StockPerSku, 0, 'Offline M5 capacity baseline stock', '$timestamp')"
    }

    [void]$builder.AppendLine('COMMIT;')
    return $builder.ToString()
}

function New-CatalogSeedSql {
    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine("SET time_zone = '+00:00';")
    [void]$builder.AppendLine('START TRANSACTION;')
    [void]$builder.AppendLine(@"
INSERT INTO catalog_category
    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
VALUES
    ($($id.category), NULL, 'M5 Baseline Category', 'm5-baseline-category',
     'ACTIVE', 0, 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
INSERT INTO catalog_brand
    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
VALUES
    ($($id.brand), 'M5 Baseline Brand', 'm5-baseline-brand',
     NULL, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
"@)

    Add-BatchedInsert -Builder $builder -Count $SpuCount -Header @'
INSERT INTO product_spu
    (id, category_id, brand_id, title, subtitle, description, status,
     version, created_at, updated_at) VALUES
'@ -RowFactory {
        param($index)
        $productId = $id.productBase + $index
        $timestamp = Get-FixedTimestamp -OffsetSeconds $index
        "($productId, $($id.category), $($id.brand), " +
            "'M5 Baseline Product $($index.ToString('0000'))', " +
            "'Repeatable M5 capacity fixture', 'Offline capacity baseline data', " +
            "'ACTIVE', 0, '$timestamp', '$timestamp')"
    }

    $skuCount = $SpuCount * $SkuPerSpu
    Add-BatchedInsert -Builder $builder -Count $skuCount -Header @'
INSERT INTO product_sku
    (id, spu_id, sku_code, name, spec_json, sale_price, market_price,
     status, version, created_at, updated_at) VALUES
'@ -RowFactory {
        param($index)
        $skuId = $id.skuBase + $index
        $spuIndex = [int][Math]::Floor(($index - 1) / $SkuPerSpu) + 1
        $productId = $id.productBase + $spuIndex
        $variant = (($index - 1) % $SkuPerSpu) + 1
        $price = Get-DecimalText ([decimal]19.90 + [decimal](($index - 1) % 100))
        $marketPrice = Get-DecimalText ([decimal]$price + [decimal]10.00)
        $timestamp = Get-FixedTimestamp -OffsetSeconds ($SpuCount + $index)
        "($skuId, $productId, 'M5-SKU-$($index.ToString('000000'))', " +
            "'M5 SKU $($index.ToString('000000'))', " +
            "'{`"theme`":`"baseline`",`"variant`":`"$variant`"}', " +
            "$price, $marketPrice, 'ACTIVE', 0, '$timestamp', '$timestamp')"
    }
    [void]$builder.AppendLine('COMMIT;')
    return $builder.ToString()
}

function New-IdentitySeedSql {
    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine("SET time_zone = '+00:00';")
    [void]$builder.AppendLine('START TRANSACTION;')

    Add-BatchedInsert -Builder $builder -Count $UserCount -Header @'
INSERT INTO user_account
    (id, email, password_hash, display_name, status, version, created_at, updated_at) VALUES
'@ -RowFactory {
        param($index)
        $userId = $id.userBase + $index
        $timestamp = Get-FixedTimestamp -OffsetSeconds $index
        "($userId, 'm5.user.$($index.ToString('0000'))$fixtureEmailSuffix', " +
            "'$fixturePasswordHash', 'M5 User $($index.ToString('0000'))', " +
            "'ACTIVE', 0, '$timestamp', '$timestamp')"
    }

    [void]$builder.AppendLine(@"
INSERT INTO user_role (user_id, role_id, created_at)
SELECT id, (SELECT id FROM identity_role WHERE code = 'CUSTOMER'), created_at
FROM user_account
WHERE id BETWEEN $($id.userBase + 1) AND $($id.userBase + $UserCount);
"@)

    Add-BatchedInsert -Builder $builder -Count $UserCount -Header @'
INSERT INTO user_address
    (id, user_id, recipient_name, phone, province, city, district, detail_address,
     postal_code, is_default, version, created_at, updated_at,
     province_code, city_code, district_code) VALUES
'@ -RowFactory {
        param($index)
        $addressId = $id.addressBase + $index
        $userId = $id.userBase + $index
        $timestamp = Get-FixedTimestamp -OffsetSeconds ($UserCount + $index)
        "($addressId, $userId, 'M5 User $($index.ToString('0000'))', " +
            "'138$($index.ToString('00000000'))', '浙江省', '杭州市', '西湖区', " +
            "'M5 baseline road $index', '310000', TRUE, 0, '$timestamp', '$timestamp', " +
            "'330000', '330100', '330106')"
    }
    [void]$builder.AppendLine('COMMIT;')
    return $builder.ToString()
}

function New-TradeSeedSql {
    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine("SET time_zone = '+00:00';")
    [void]$builder.AppendLine('START TRANSACTION;')

    $cartCount = $UserCount * $CartItemsPerUser
    $skuCount = $SpuCount * $SkuPerSpu
    Add-BatchedInsert -Builder $builder -Count $cartCount -Header @'
INSERT INTO cart_item
    (id, user_id, product_id, sku_id, quantity, selected, created_at, updated_at,
     product_title, sku_name, spec_json, unit_price) VALUES
'@ -RowFactory {
        param($index)
        $cartId = $id.cartBase + $index
        $userIndex = [int][Math]::Floor(($index - 1) / $CartItemsPerUser) + 1
        $line = (($index - 1) % $CartItemsPerUser) + 1
        $skuIndex = ((($userIndex - 1) * $CartItemsPerUser + $line - 1) % $skuCount) + 1
        $productIndex = [int][Math]::Floor(($skuIndex - 1) / $SkuPerSpu) + 1
        $userId = $id.userBase + $userIndex
        $productId = $id.productBase + $productIndex
        $skuId = $id.skuBase + $skuIndex
        $price = Get-DecimalText ([decimal]19.90 + [decimal](($skuIndex - 1) % 100))
        $timestamp = Get-FixedTimestamp -OffsetSeconds ($UserCount * 2 + $index)
        "($cartId, $userId, $productId, $skuId, 1, TRUE, '$timestamp', '$timestamp', " +
            "'M5 Baseline Product $($productIndex.ToString('0000'))', " +
            "'M5 SKU $($skuIndex.ToString('000000'))', " +
            "'{`"theme`":`"baseline`"}', $price)"
    }

    Add-BatchedInsert -Builder $builder -Count $OrderCount -Header @'
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, total_amount, payment_deadline,
     close_reason, recovery_attempts, next_recovery_at, last_error, version,
     created_at, updated_at, original_amount, discount_amount, marketing_lock_no) VALUES
'@ -RowFactory {
        param($index)
        $orderId = $id.orderBase + $index
        $userIndex = Get-UserIndexForOrder -OrderIndex $index
        $userId = $id.userBase + $userIndex
        $skuIndex = (($index - 1) % $skuCount) + 1
        $price = Get-DecimalText ([decimal]19.90 + [decimal](($skuIndex - 1) % 100))
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $index)
        $deadline = Get-FixedTimestamp -OffsetSeconds (101800 + $index)
        $orderNo = Get-OrderNumber -OrderIndex $index
        "($orderId, '$orderNo', $userId, 'M5-IDEMP-$($index.ToString('00000000'))', " +
            "'$($index.ToString('x').PadLeft(64, '0'))', " +
            "'M5-RSV-$($index.ToString('00000000'))', 'PRIMARY', NULL, 'COMPLETED', " +
            "$price, '$deadline', NULL, 0, NULL, NULL, 5, '$createdAt', '$createdAt', " +
            "$price, 0.00, 'M5-LOCK-$($index.ToString('00000000'))')"
    }

    Add-BatchedInsert -Builder $builder -Count $OrderCount -Header @'
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
     line_no, discount_amount, payable_amount) VALUES
'@ -RowFactory {
        param($index)
        $itemId = $id.orderItemBase + $index
        $orderId = $id.orderBase + $index
        $skuIndex = (($index - 1) % $skuCount) + 1
        $productIndex = [int][Math]::Floor(($skuIndex - 1) / $SkuPerSpu) + 1
        $productId = $id.productBase + $productIndex
        $skuId = $id.skuBase + $skuIndex
        $price = Get-DecimalText ([decimal]19.90 + [decimal](($skuIndex - 1) % 100))
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $index)
        "($itemId, $orderId, $productId, $skuId, " +
            "'M5 Baseline Product $($productIndex.ToString('0000'))', " +
            "'M5-SKU-$($skuIndex.ToString('000000'))', " +
            "'M5 SKU $($skuIndex.ToString('000000'))', " +
            "'{`"theme`":`"baseline`"}', NULL, $price, 1, $price, '$createdAt', 1, 0.00, $price)"
    }

    Add-BatchedInsert -Builder $builder -Count $OrderCount -Header @'
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province, city,
     district, detail_address, postal_code, created_at,
     province_code, city_code, district_code) VALUES
'@ -RowFactory {
        param($index)
        $snapshotId = $id.orderAddressBase + $index
        $orderId = $id.orderBase + $index
        $userIndex = Get-UserIndexForOrder -OrderIndex $index
        $addressId = $id.addressBase + $userIndex
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $index)
        "($snapshotId, $orderId, $addressId, 'M5 User $($userIndex.ToString('0000'))', " +
            "'138$($userIndex.ToString('00000000'))', '浙江省', '杭州市', '西湖区', " +
            "'M5 baseline road $userIndex', '310000', '$createdAt', " +
            "'330000', '330100', '330106')"
    }

    Add-BatchedInsert -Builder $builder -Count $OrderCount -Header @'
INSERT INTO order_price_snapshot
    (id, order_id, marketing_lock_no, original_amount, coupon_discount,
     red_packet_discount, subsidy_discount, discount_amount, payable_amount,
     pricing_version, created_at) VALUES
'@ -RowFactory {
        param($index)
        $snapshotId = $id.priceSnapshotBase + $index
        $orderId = $id.orderBase + $index
        $skuIndex = (($index - 1) % $skuCount) + 1
        $price = Get-DecimalText ([decimal]19.90 + [decimal](($skuIndex - 1) % 100))
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $index)
        "($snapshotId, $orderId, 'M5-LOCK-$($index.ToString('00000000'))', " +
            "$price, 0.00, 0.00, 0.00, 0.00, $price, 'm5-baseline-v1', '$createdAt')"
    }

    Add-BatchedInsert -Builder $builder -Count $OrderCount -Header @'
INSERT INTO order_status_history
    (id, order_id, from_status, to_status, command, reason,
     operator_type, operator_id, created_at) VALUES
'@ -RowFactory {
        param($index)
        $historyId = $id.orderHistoryBase + $index
        $orderId = $id.orderBase + $index
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $index)
        "($historyId, $orderId, 'SHIPPED', 'COMPLETED', 'M5_BASELINE_IMPORT', " +
            "'Repeatable historical capacity fixture', 'SYSTEM', 'm5-seed', '$createdAt')"
    }

    $eventTypes = @('OrderPaid', 'OrderFulfilling', 'OrderShipped', 'OrderCompleted')
    Add-BatchedInsert -Builder $builder -Count ($OrderCount * $eventTypes.Count) -Header @'
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
     created_at, updated_at, claim_owner, claim_until) VALUES
'@ -RowFactory {
        param($eventIndex)
        $orderIndex = [int][Math]::Floor(($eventIndex - 1) / $eventTypes.Count) + 1
        $slot = (($eventIndex - 1) % $eventTypes.Count) + 1
        $eventId = Get-EventId -OrderIndex $orderIndex -Slot $slot
        $orderNo = Get-OrderNumber -OrderIndex $orderIndex
        $createdAt = Get-FixedTimestamp -OffsetSeconds (100000 + $orderIndex + $slot)
        "('$eventId', '$($eventTypes[$slot - 1])', 'TradeOrder', '$orderNo', " +
            "$($slot + 1), '{`"schemaVersion`":1,`"source`":`"m5-baseline`"}', " +
            "'PUBLISHED', 0, '$createdAt', NULL, '$createdAt', NULL, " +
            "'$createdAt', '$createdAt', NULL, NULL)"
    }

    [void]$builder.AppendLine('COMMIT;')
    return $builder.ToString()
}

function Convert-LabeledRows {
    param([string[]]$Rows)

    $result = [ordered]@{}
    foreach ($row in $Rows) {
        $parts = $row -split "`t", 2
        if ($parts.Count -eq 2) {
            $result[$parts[0]] = [long]$parts[1]
        }
    }
    return $result
}

function Get-M5Counts {
    $catalogRows = Invoke-DatabaseSql -Capture `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -Sql @"
SELECT 'categories', COUNT(*) FROM catalog_category WHERE slug = 'm5-baseline-category';
SELECT 'brands', COUNT(*) FROM catalog_brand WHERE slug = 'm5-baseline-brand';
SELECT 'spus', COUNT(*) FROM product_spu WHERE title LIKE 'M5 Baseline Product %';
SELECT 'skus', COUNT(*) FROM product_sku WHERE sku_code LIKE 'M5-SKU-%';
"@
    $identityRows = Invoke-DatabaseSql -Capture `
        -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD -Sql @"
SELECT 'users', COUNT(*) FROM user_account WHERE email LIKE 'm5.user.%@plainjournal.local';
SELECT 'addresses', COUNT(*) FROM user_address
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
SELECT 'roles', COUNT(*) FROM user_role
WHERE user_id BETWEEN $($id.userBase + 1) AND $($id.userBase + 1000000);
"@
    $tradeRows = Invoke-DatabaseSql -Capture `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -Sql @"
SELECT 'carts', COUNT(*) FROM cart_item
WHERE id BETWEEN $($id.cartBase + 1) AND $($id.cartBase + 1000000);
SELECT 'orders', COUNT(*) FROM trade_order WHERE order_no LIKE 'M5-ORD-%';
SELECT 'dense_user_orders', COUNT(*) FROM trade_order WHERE user_id = $($id.userBase + 1);
SELECT 'order_items', COUNT(*) FROM order_item
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no LIKE 'M5-ORD-%');
SELECT 'order_addresses', COUNT(*) FROM order_address_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no LIKE 'M5-ORD-%');
SELECT 'price_snapshots', COUNT(*) FROM order_price_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no LIKE 'M5-ORD-%');
SELECT 'order_history', COUNT(*) FROM order_status_history
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no LIKE 'M5-ORD-%');
SELECT 'outbox_events', COUNT(*) FROM outbox_event WHERE aggregate_id LIKE 'M5-ORD-%';
SELECT 'order_total_mismatches', COUNT(*)
FROM trade_order o
LEFT JOIN (
    SELECT order_id, SUM(line_amount) AS original_amount,
           SUM(discount_amount) AS discount_amount,
           SUM(payable_amount) AS payable_amount
    FROM order_item GROUP BY order_id
) i ON i.order_id = o.id
WHERE o.order_no LIKE 'M5-ORD-%'
    AND (COALESCE(i.original_amount, 0) <> o.original_amount
    OR COALESCE(i.discount_amount, 0) <> o.discount_amount
    OR COALESCE(i.payable_amount, 0) <> o.total_amount);
"@
    $inventoryRows = Invoke-DatabaseSql -Capture `
        -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD -Sql @"
SELECT 'warehouses', COUNT(*) FROM warehouse
WHERE id = $($id.warehouse) AND code = 'PRIMARY';
SELECT 'balances', COUNT(*) FROM inventory_balance
WHERE warehouse_id = $($id.warehouse);
SELECT 'adjustments', COUNT(*) FROM stock_adjustment
WHERE warehouse_id = $($id.warehouse) AND movement_no LIKE 'M5-ADJ-%';
SELECT 'movements', COUNT(*) FROM stock_movement
WHERE warehouse_id = $($id.warehouse) AND movement_no LIKE 'M5-ADJ-%'
  AND movement_type = 'ADJUSTMENT';
SELECT 'on_hand', COALESCE(SUM(on_hand), 0) FROM inventory_balance
WHERE warehouse_id = $($id.warehouse);
SELECT 'reserved', COALESCE(SUM(reserved), 0) FROM inventory_balance
WHERE warehouse_id = $($id.warehouse);
SELECT 'adjustment_mismatches', COUNT(*)
FROM stock_adjustment a
WHERE a.warehouse_id = $($id.warehouse)
  AND a.status = 'APPLIED'
  AND NOT EXISTS (
      SELECT 1 FROM stock_movement m
      WHERE m.movement_no = a.movement_no
        AND m.warehouse_id = a.warehouse_id
        AND m.sku_id = a.sku_id
        AND m.reservation_no IS NULL
        AND m.movement_type = 'ADJUSTMENT'
        AND m.quantity_delta = a.quantity_delta
  );
"@

    return [ordered]@{
        catalog = Convert-LabeledRows -Rows $catalogRows
        identity = Convert-LabeledRows -Rows $identityRows
        trade = Convert-LabeledRows -Rows $tradeRows
        inventory = Convert-LabeledRows -Rows $inventoryRows
    }
}

function Assert-Count {
    param(
        [Parameter(Mandatory)][long]$Actual,
        [Parameter(Mandatory)][long]$Expected,
        [Parameter(Mandatory)][string]$Name
    )

    if ($Actual -ne $Expected) {
        throw "M5 baseline count mismatch for $Name`: expected $Expected, actual $Actual."
    }
}

function Assert-SeededData {
    $counts = Get-M5Counts
    $skuCount = $SpuCount * $SkuPerSpu
    $cartCount = $UserCount * $CartItemsPerUser
    $denseExpected = [Math]::Min($DenseUserOrderCount, $OrderCount)
    if ($UserCount -eq 1) {
        $denseExpected = $OrderCount
    }

    Assert-Count $counts.catalog.categories 1 'catalog categories'
    Assert-Count $counts.catalog.brands 1 'catalog brands'
    Assert-Count $counts.catalog.spus $SpuCount 'catalog SPUs'
    Assert-Count $counts.catalog.skus $skuCount 'catalog SKUs'
    Assert-Count $counts.identity.users $UserCount 'identity users'
    Assert-Count $counts.identity.addresses $UserCount 'identity addresses'
    Assert-Count $counts.identity.roles $UserCount 'identity user roles'
    Assert-Count $counts.trade.carts $cartCount 'trade cart items'
    Assert-Count $counts.trade.orders $OrderCount 'trade orders'
    Assert-Count $counts.trade.dense_user_orders $denseExpected 'dense-user orders'
    Assert-Count $counts.trade.order_items $OrderCount 'trade order items'
    Assert-Count $counts.trade.order_addresses $OrderCount 'trade order addresses'
    Assert-Count $counts.trade.price_snapshots $OrderCount 'trade price snapshots'
    Assert-Count $counts.trade.order_history $OrderCount 'trade order history'
    Assert-Count $counts.trade.outbox_events ($OrderCount * 4) 'trade historical outbox'
    Assert-Count $counts.trade.order_total_mismatches 0 'trade amount invariants'
    Assert-Count $counts.inventory.warehouses 1 'inventory warehouses'
    Assert-Count $counts.inventory.balances $skuCount 'inventory balances'
    Assert-Count $counts.inventory.adjustments $skuCount 'inventory stock adjustments'
    Assert-Count $counts.inventory.movements $skuCount 'inventory stock movements'
    Assert-Count $counts.inventory.on_hand ($skuCount * $StockPerSku) 'inventory on-hand units'
    Assert-Count $counts.inventory.reserved 0 'inventory reserved units'
    Assert-Count $counts.inventory.adjustment_mismatches 0 'inventory adjustment movements'
    return $counts
}

function Assert-RemovedData {
    $counts = Get-M5Counts
    foreach ($domain in $counts.Keys) {
        foreach ($entry in $counts[$domain].GetEnumerator()) {
            Assert-Count $entry.Value 0 "$domain $($entry.Key)"
        }
    }
    return $counts
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile
Assert-RequiredEnvironment
Assert-MySqlReady

if ($DenseUserOrderCount -gt $OrderCount) {
    throw 'DenseUserOrderCount cannot be greater than OrderCount.'
}
$totalSkuCount = $SpuCount * $SkuPerSpu
if ($CartItemsPerUser -gt $totalSkuCount) {
    throw 'CartItemsPerUser cannot be greater than the generated SKU count.'
}

if (-not $ManifestPath) {
    $ManifestPath = Join-Path $backendRoot '.run\m5-baseline-data.json'
}
$resolvedManifestPath = [IO.Path]::GetFullPath($ManifestPath)

if ($Action -in @('Seed', 'Remove')) {
    Assert-BusinessApplicationsStopped
}

$startedAt = Get-Date
switch ($Action) {
    'Seed' {
        Remove-M5Data
        Invoke-DatabaseSql -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
            -Password $env:CATALOG_DB_PASSWORD -Sql (New-CatalogSeedSql)
        Invoke-DatabaseSql -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
            -Password $env:IDENTITY_DB_PASSWORD -Sql (New-IdentitySeedSql)
        Invoke-DatabaseSql -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD -Sql (New-TradeSeedSql)
        Invoke-DatabaseSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD -Sql (New-InventorySeedSql)
        $counts = Assert-SeededData
    }
    'Verify' {
        $counts = Assert-SeededData
    }
    'Remove' {
        Remove-M5Data
        $counts = Assert-RemovedData
    }
}

$manifestDirectory = Split-Path -Parent $resolvedManifestPath
New-Item -ItemType Directory -Path $manifestDirectory -Force | Out-Null
$manifest = [ordered]@{
    schemaVersion = 1
    action = $Action
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    elapsedSeconds = [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    fixture = [ordered]@{
        prefix = $fixturePrefix
        credentialProfile = 'm5-local-fixture-v1'
        denseUserEmail = "m5.user.0001$fixtureEmailSuffix"
        normalUserEmail = if ($UserCount -gt 1) { "m5.user.0002$fixtureEmailSuffix" } else { $null }
        firstProductId = [string]($id.productBase + 1)
        lastProductId = [string]($id.productBase + $SpuCount)
        warehouseId = [string]$id.warehouse
        firstOrderNo = Get-OrderNumber -OrderIndex 1
        lastDenseUserOrderNo = if ($DenseUserOrderCount -gt 0) {
            Get-OrderNumber -OrderIndex ([Math]::Min($DenseUserOrderCount, $OrderCount))
        } else {
            $null
        }
    }
    requested = [ordered]@{
        spus = $SpuCount
        skus = $totalSkuCount
        users = $UserCount
        cartItems = $UserCount * $CartItemsPerUser
        orders = $OrderCount
        denseUserOrders = $DenseUserOrderCount
        stockPerSku = $StockPerSku
    }
    actual = $counts
}
$manifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $resolvedManifestPath -Encoding utf8

$manifest | ConvertTo-Json -Depth 8
