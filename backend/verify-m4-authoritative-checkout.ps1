#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(0, 600)]
    [int]$BrowserHoldSeconds = 0
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
        [Environment]::SetEnvironmentVariable(
            $trimmed.Substring(0, $separator).Trim(),
            $trimmed.Substring($separator + 1).Trim(),
            'Process')
    }
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 12
    }
    return Invoke-RestMethod @parameters
}

function Invoke-ServiceSql {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllRows
    )

    $result = docker exec -e "MYSQL_PWD=$Password" plainjournal-mysql `
        mysql "-u$User" $Database -N -B -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "SQL failed for database $Database."
    }
    if ($AllRows) {
        return @($result)
    }
    return $result | Select-Object -Last 1
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function Assert-ForbiddenJsonApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 15
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 12
    }

    $response = Invoke-WebRequest @parameters
    Assert-Equal ([int]$response.StatusCode) 403 `
        "Customer token crossed an administrative boundary at $Uri."
    $payload = $response.Content | ConvertFrom-Json
    Assert-Equal $payload.code 'FORBIDDEN' `
        "Administrative boundary did not return the structured forbidden contract at $Uri."
}

function Wait-ForExpectedValue {
    param(
        [Parameter(Mandatory)][scriptblock]$Operation,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Message,
        [int]$TimeoutSeconds = 30,
        [int]$PollMilliseconds = 500
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $actual = $null
    $lastError = $null
    do {
        try {
            $actual = & $Operation
            $lastError = $null
            if ($actual -eq $Expected) {
                return $actual
            }
        }
        catch {
            $lastError = $_
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    if ($lastError) {
        throw "$Message Expected=$Expected LastError=$($lastError.Exception.Message)"
    }
    throw "$Message Expected=$Expected Actual=$actual"
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

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile

foreach ($container in @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy',
        'plainjournal-minio')) {
    if ((docker inspect --format '{{.State.Running}}' $container 2>$null) -ne 'true') {
        throw "Required core container is not running: $container"
    }
}

foreach ($port in 18000, 18101, 18102, 18103, 18104, 18107) {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/liveness" -TimeoutSec 5
    if ($health.status -ne 'UP') {
        throw "Required application on port $port is not healthy."
    }
}

$gateway = 'http://127.0.0.1:18000/api/v1'
$runToken = [Guid]::NewGuid().ToString('N').Substring(0, 12).ToLowerInvariant()
$upperToken = $runToken.ToUpperInvariant()
$browserFixtureFile = $null
if ($BrowserHoldSeconds -gt 0) {
    $browserFixtureDirectory = Join-Path $PSScriptRoot '.run'
    New-Item -ItemType Directory -Path $browserFixtureDirectory -Force | Out-Null
    $browserFixtureFile = Join-Path $browserFixtureDirectory `
        "m4-authoritative-checkout-browser-$runToken.json"
}
$email = "m4-checkout-$runToken@example.invalid"
$password = 'M4CheckoutPass123'
$categorySlug = "m4-checkout-$runToken"
$brandSlug = "m4-brand-$runToken"
$productTitle = "M4 Authoritative Checkout $runToken"
$skuCode = "M4-$upperToken"
$movementNo = "M4-ADJ-$upperToken"
$ruleCode = "M4-CHECKOUT-$upperToken"
$grantKey = "M4-GRANT-$upperToken"
$idempotencyKey = "order:$runToken"

$userId = $null
$productId = $null
$skuId = $null
$warehouseId = $null
$benefitNo = $null
$orderNo = $null
$reservationNo = $null
$evidence = $null
$verificationError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()

try {
    $status = Invoke-RestMethod -Uri "$gateway/identity/status" -TimeoutSec 10
    Assert-Equal $status.data.configurationSource 'nacos' 'Gateway/Identity configuration source mismatch.'

    $registration = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/register" -Body @{
        email = $email
        password = $password
        displayName = 'M4 Checkout Evidence'
    }
    $userId = [string]$registration.data.id
    if ($registration.data.id -isnot [string]) {
        throw 'Identity business ID was not serialized as a JSON string.'
    }

    $login = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/login" -Body @{
        email = $email
        password = $password
    }
    $customerHeaders = @{ Authorization = "Bearer $($login.data.accessToken)" }

    $address = Invoke-JsonApi -Method Post -Uri "$gateway/identity/addresses" `
        -Headers $customerHeaders -Body @{
            recipientName = 'M4 Customer'
            phone = '+86 13800000000'
            province = '浙江省'
            provinceCode = '330000'
            city = '杭州市'
            cityCode = '330100'
            district = '西湖区'
            districtCode = '330106'
            detailAddress = '文三路 1 号'
            postalCode = '310000'
            setDefault = $true
        }
    $addressId = [string]$address.data.id

    $customerAdminBoundaryChecks = [ordered]@{
        gatewayCatalog = "$gateway/catalog/admin/categories"
        serviceCatalog = 'http://127.0.0.1:18102/api/v1/catalog/admin/categories'
        gatewayInventory = "$gateway/inventory/admin/warehouses"
        serviceInventory = 'http://127.0.0.1:18103/api/v1/inventory/admin/warehouses'
        gatewayMarketing = "$gateway/marketing/admin/benefits"
        serviceMarketing = 'http://127.0.0.1:18107/api/v1/marketing/admin/benefits'
    }
    foreach ($boundary in $customerAdminBoundaryChecks.GetEnumerator()) {
        Assert-ForbiddenJsonApi -Method Post -Uri $boundary.Value `
            -Headers $customerHeaders -Body @{}
    }

    Invoke-ServiceSql `
        -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD `
        -Database $env:IDENTITY_DB_NAME `
        -Sql @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT user_account.id, identity_role.id, CURRENT_TIMESTAMP(3)
FROM user_account
JOIN identity_role ON identity_role.code = 'ADMIN'
WHERE user_account.email = '$email';
"@ | Out-Null

    $adminLogin = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/login" -Body @{
        email = $email
        password = $password
    }
    $adminHeaders = @{ Authorization = "Bearer $($adminLogin.data.accessToken)" }

    $category = Invoke-JsonApi -Method Post -Uri "$gateway/catalog/admin/categories" `
        -Headers $adminHeaders -Body @{
            name = 'M4 Checkout'
            slug = $categorySlug
            sortOrder = 9999
        }
    $brand = Invoke-JsonApi -Method Post -Uri "$gateway/catalog/admin/brands" `
        -Headers $adminHeaders -Body @{
            name = 'M4 Brand'
            slug = $brandSlug
        }
    $product = Invoke-JsonApi -Method Post -Uri "$gateway/catalog/admin/products" `
        -Headers $adminHeaders -Body @{
            categoryId = $category.data.id
            brandId = $brand.data.id
            title = $productTitle
            subtitle = 'Temporary authoritative checkout evidence'
            description = 'Removed after verification.'
            skus = @(@{
                    skuCode = $skuCode
                    name = '自然色 / 中号'
                    specJson = '{"variant":"natural"}'
                    salePrice = 189.00
                    marketPrice = 219.00
                })
        }
    $productId = [string]$product.data.id
    $skuId = [string]$product.data.skus[0].id
    $skuVersion = [int]$product.data.skus[0].version
    $published = Invoke-JsonApi -Method Post `
        -Uri "$gateway/catalog/admin/products/$productId/publish" `
        -Headers $adminHeaders -Body @{ expectedVersion = $product.data.version }
    Assert-Equal $published.data.status 'ACTIVE' 'Catalog product publication failed.'

    $cartItem = Invoke-JsonApi -Method Put -Uri "$gateway/trade/cart/items/$skuId" `
        -Headers $customerHeaders -Body @{
            productId = $productId
            quantity = 2
            selected = $true
        }
    Assert-Equal ([decimal]$cartItem.data.unitPrice) ([decimal]189.00) `
        'Initial cart display snapshot price mismatch.'

    $updatedSku = Invoke-JsonApi -Method Put `
        -Uri "$gateway/catalog/admin/products/$productId/skus/$skuId" `
        -Headers $adminHeaders -Body @{
            name = '自然色 / 中号'
            specJson = '{"variant":"natural"}'
            salePrice = 199.00
            marketPrice = 219.00
            status = 'ACTIVE'
            expectedVersion = $skuVersion
        }
    Assert-Equal ([decimal]$updatedSku.data.salePrice) ([decimal]199.00) `
        'Catalog price update failed.'

    $warehouse = Invoke-JsonApi -Method Post -Uri "$gateway/inventory/admin/warehouses" `
        -Headers $adminHeaders -Body @{
            code = 'PRIMARY'
            name = 'M4 Checkout Warehouse'
        }
    $warehouseId = [string]$warehouse.data.id
    $stockAdjustment = Invoke-JsonApi -Method Post `
        -Uri "$gateway/inventory/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = $movementNo
            warehouseId = $warehouseId
            skuId = $skuId
            quantityDelta = 5
            reason = 'M4 authoritative checkout evidence'
        }
    Assert-Equal ([long]$stockAdjustment.data.available) 5 'Inventory stock setup failed.'

    Invoke-JsonApi -Method Post -Uri "$gateway/marketing/admin/rules" `
        -Headers $adminHeaders -Body @{
            ruleCode = $ruleCode
            name = $ruleCode
            benefitType = 'COUPON'
            thresholdAmount = 300.00
            discountAmount = 20.00
            stackOrder = 10
            validFrom = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToString('o')
            validUntil = [DateTimeOffset]::UtcNow.AddHours(2).ToString('o')
            regions = @(@{
                    level = 'DISTRICT'
                    regionCode = '330106'
                })
        } | Out-Null
    $benefit = Invoke-JsonApi -Method Post -Uri "$gateway/marketing/admin/benefits" `
        -Headers $adminHeaders -Body @{
            userId = [long]$userId
            ruleCode = $ruleCode
            grantKey = $grantKey
        }
    $benefitNo = [string]$benefit.data.benefitNo

    $cart = Invoke-RestMethod -Uri "$gateway/trade/cart/items" `
        -Headers $customerHeaders -TimeoutSec 10
    Assert-Equal ([decimal]$cart.data[0].unitPrice) ([decimal]189.00) `
        'Cart display snapshot changed after Catalog changed.'
    if ($cart.data[0].productId -isnot [string] -or $cart.data[0].skuId -isnot [string]) {
        throw 'Cart business IDs were not serialized as JSON strings.'
    }

    $productResponse = Invoke-WebRequest -Uri "$gateway/catalog/products/$productId" -TimeoutSec 10
    $currentProduct = $productResponse.Content | ConvertFrom-Json
    Assert-Equal ([decimal]$currentProduct.data.skus[0].salePrice) ([decimal]199.00) `
        'Authoritative Catalog price was stale.'
    if ($currentProduct.data.id -isnot [string] -or
        $currentProduct.data.skus[0].id -isnot [string]) {
        throw 'Catalog business IDs were not serialized as JSON strings.'
    }

    $stockResponse = Invoke-WebRequest -Uri "$gateway/inventory/stocks/$skuId" -TimeoutSec 10
    $currentStock = $stockResponse.Content | ConvertFrom-Json
    Assert-Equal ([long]$currentStock.data.available) 5 `
        'Authoritative Inventory availability was incorrect before order creation.'
    if ($currentStock.data.skuId -isnot [string]) {
        throw 'Inventory SKU ID was not serialized as a JSON string.'
    }

    $pricingLockCountBefore = [long](Invoke-ServiceSql `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Database $env:MARKETING_DB_NAME `
            -Sql 'SELECT COUNT(*) FROM pricing_lock;')
    $preview = Invoke-JsonApi -Method Post -Uri "$gateway/marketing/pricing-previews" `
        -Headers $customerHeaders -Body @{
            originalAmount = 398.00
            deliveryRegion = @{
                provinceCode = '330000'
                cityCode = '330100'
                districtCode = '330106'
            }
            lines = @(@{
                    lineNo = 1
                    skuId = $skuId
                    lineAmount = 398.00
                })
            benefitNos = @($benefitNo)
        }
    Assert-Equal ([decimal]$preview.data.originalAmount) ([decimal]398.00) `
        'Marketing preview original amount mismatch.'
    Assert-Equal ([decimal]$preview.data.discountAmount) ([decimal]20.00) `
        'Marketing preview discount mismatch.'
    Assert-Equal ([decimal]$preview.data.payableAmount) ([decimal]378.00) `
        'Marketing preview payable amount mismatch.'
    $previewFacts = Invoke-ServiceSql `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -Database $env:MARKETING_DB_NAME `
        -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM pricing_lock),
    '|',
    (SELECT status FROM user_benefit WHERE benefit_no = '$benefitNo'));
"@
    Assert-Equal $previewFacts "$pricingLockCountBefore|AVAILABLE" `
        'Marketing preview created a lock or changed the benefit state.'

    $orderBody = @{
        addressId = $addressId
        items = @(@{
                productId = $productId
                skuId = $skuId
                quantity = 2
            })
        benefitNos = @($benefitNo)
    }
    $orderHeaders = @{
        Authorization = $customerHeaders.Authorization
        'Idempotency-Key' = $idempotencyKey
    }
    $created = Invoke-JsonApi -Method Post -Uri "$gateway/trade/orders" `
        -Headers $orderHeaders -Body $orderBody
    $orderNo = [string]$created.data.orderNo
    $reservationNo = $orderNo -replace '^ORD', 'RSV'
    Assert-Equal $created.data.status 'PENDING_PAYMENT' `
        'Trade order did not reach PENDING_PAYMENT.'
    Assert-Equal ([decimal]$created.data.items[0].unitPrice) ([decimal]199.00) `
        'Trade order did not snapshot the current Catalog price.'
    Assert-Equal ([decimal]$created.data.priceSnapshot.discountAmount) ([decimal]20.00) `
        'Trade price snapshot discount mismatch.'
    Assert-Equal ([decimal]$created.data.totalAmount) ([decimal]378.00) `
        'Trade order payable amount mismatch.'
    Assert-Equal $created.data.deliveryAddress.detailAddress '文三路 1 号' `
        'Trade address snapshot mismatch.'
    if ($created.data.items[0].productId -isnot [string] -or
        $created.data.items[0].skuId -isnot [string]) {
        throw 'Trade order business IDs were not serialized as JSON strings.'
    }

    $encodedKey = [Uri]::EscapeDataString($idempotencyKey)
    $recovered = Invoke-RestMethod `
        -Uri "$gateway/trade/orders/by-idempotency-key/$encodedKey" `
        -Headers $customerHeaders `
        -TimeoutSec 10
    $retried = Invoke-JsonApi -Method Post -Uri "$gateway/trade/orders" `
        -Headers $orderHeaders -Body $orderBody
    $result = Invoke-RestMethod -Uri "$gateway/trade/orders/$orderNo" `
        -Headers $customerHeaders `
        -TimeoutSec 10
    Assert-Equal $recovered.data.orderNo $orderNo `
        'Idempotency-key recovery returned a different order.'
    Assert-Equal $retried.data.orderNo $orderNo `
        'Same-key retry returned a different order.'
    Assert-Equal $result.data.orderNo $orderNo `
        'Order result endpoint returned a different order.'

    $tradeFacts = Invoke-ServiceSql `
        -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD `
        -Database $env:TRADE_DB_NAME `
        -Sql @"
SELECT CONCAT(COUNT(*), '|', COUNT(DISTINCT order_no), '|', SUM(status = 'PENDING_PAYMENT'))
FROM trade_order
WHERE user_id = $userId AND idempotency_key = '$idempotencyKey';
"@
    $inventoryFacts = Invoke-ServiceSql `
        -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD `
        -Database $env:INVENTORY_DB_NAME `
        -Sql @"
SELECT CONCAT(COUNT(*), '|', SUM(status = 'RESERVED'))
FROM inventory_reservation
WHERE order_no = '$orderNo';
"@
    $marketingFacts = Invoke-ServiceSql `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -Database $env:MARKETING_DB_NAME `
        -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM pricing_lock WHERE order_no = '$orderNo'),
    '|',
    (SELECT status FROM user_benefit WHERE benefit_no = '$benefitNo'));
"@
    Assert-Equal $tradeFacts '1|1|1' 'Trade did not preserve exactly one order fact.'
    Assert-Equal $inventoryFacts '1|1' `
        'Inventory did not preserve exactly one reservation fact.'
    Assert-Equal $marketingFacts '1|LOCKED' `
        'Marketing did not preserve one locked pricing fact.'

    $stockAfterOrder = Invoke-RestMethod -Uri "$gateway/inventory/stocks/$skuId" -TimeoutSec 10
    Assert-Equal ([long]$stockAfterOrder.data.available) 3 `
        'Available stock did not decrease by the order quantity.'
    Assert-Equal ([long]$stockAfterOrder.data.reserved) 2 `
        'Reserved stock did not equal the order quantity.'

    if ($BrowserHoldSeconds -gt 0) {
        $browserFixture = [ordered]@{
            email = $email
            password = $password
            orderNo = $orderNo
            idempotencyKey = $idempotencyKey
            holdSeconds = $BrowserHoldSeconds
        }
        $browserFixture | ConvertTo-Json -Compress |
            Set-Content -LiteralPath $browserFixtureFile -Encoding utf8
        [ordered]@{
            fixtureFile = $browserFixtureFile
            email = $email
            orderNo = $orderNo
            holdSeconds = $BrowserHoldSeconds
        } | ConvertTo-Json -Compress | ForEach-Object {
            Write-Output "BROWSER_FIXTURE_READY=$_"
        }
        Start-Sleep -Seconds $BrowserHoldSeconds
    }

    $canceled = Invoke-JsonApi -Method Post -Uri "$gateway/trade/orders/$orderNo/cancel" `
        -Headers $customerHeaders -Body @{}
    Assert-Equal $canceled.data.status 'CANCELED' 'Temporary order did not cancel cleanly.'
    $restoredStock = Invoke-RestMethod -Uri "$gateway/inventory/stocks/$skuId" -TimeoutSec 10
    Assert-Equal ([long]$restoredStock.data.available) 5 `
        'Inventory did not restore available stock after cancellation.'
    Assert-Equal ([long]$restoredStock.data.reserved) 0 `
        'Inventory did not release reserved stock after cancellation.'
    Wait-ForExpectedValue -Expected 'AVAILABLE' `
        -Message 'Marketing benefit did not return to AVAILABLE after cancellation.' `
        -Operation {
        $benefits = Invoke-RestMethod -Uri "$gateway/marketing/benefits" `
            -Headers $customerHeaders `
            -TimeoutSec 10
        $restoredBenefit = @($benefits.data |
                Where-Object benefitNo -eq $benefitNo |
                Select-Object -First 1)
        return $restoredBenefit.status
    } | Out-Null

    $evidence = [ordered]@{
        schemaVersion = 1
        configurationSource = 'nacos'
        userId = $userId
        productId = $productId
        skuId = $skuId
        cartSnapshotPrice = '189.00'
        catalogCurrentPrice = '199.00'
        inventoryAvailableBefore = 5
        previewOriginalAmount = '398.00'
        previewDiscountAmount = '20.00'
        previewPayableAmount = '378.00'
        idempotencyKey = $idempotencyKey
        orderNo = $orderNo
        orderStatus = 'PENDING_PAYMENT'
        tradeFacts = $tradeFacts
        inventoryFacts = $inventoryFacts
        marketingFacts = $marketingFacts
        restoredAfterCancel = $true
        customerAdminBoundary403 = @($customerAdminBoundaryChecks.Keys)
        businessIdsAreJsonStrings = $true
    }
}
catch {
    $verificationError = $_
}
finally {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Stop'

    $runCleanup = {
        param([string]$Name, [scriptblock]$Operation)
        try {
            & $Operation
        }
        catch {
            $cleanupErrors.Add("${Name}: $($_.Exception.Message)")
        }
    }

    if ($browserFixtureFile) {
        Remove-Item -LiteralPath $browserFixtureFile -Force -ErrorAction SilentlyContinue
    }

    & $runCleanup 'marketing and pricing facts' {
        $eventIds = @()
        if ($orderNo) {
            $eventIds = @(Invoke-ServiceSql `
                    -User $env:TRADE_DB_USER `
                    -Password $env:TRADE_DB_PASSWORD `
                    -Database $env:TRADE_DB_NAME `
                    -Sql "SELECT id FROM outbox_event WHERE aggregate_id = '$orderNo';" `
                    -AllRows |
                    Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
        }
        $consumedDelete = ''
        if ($eventIds.Count -gt 0) {
            $eventSql = ($eventIds | ForEach-Object { "'$_'" }) -join ','
            $consumedDelete = "DELETE FROM consumed_event WHERE event_id IN ($eventSql);"
        }
        Invoke-ServiceSql `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Database $env:MARKETING_DB_NAME `
            -Sql @"
$consumedDelete
DELETE FROM pricing_lock_allocation
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no = '$orderNo');
DELETE FROM pricing_lock_benefit
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no = '$orderNo');
DELETE FROM pricing_lock WHERE order_no = '$orderNo';
DELETE FROM user_benefit
WHERE rule_id IN (SELECT id FROM marketing_rule WHERE rule_code = '$ruleCode');
DELETE FROM marketing_rule_region
WHERE rule_id IN (SELECT id FROM marketing_rule WHERE rule_code = '$ruleCode');
DELETE FROM marketing_rule WHERE rule_code = '$ruleCode';
"@ | Out-Null
    }

    & $runCleanup 'trade facts' {
        if ($userId) {
            Invoke-ServiceSql `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Database $env:TRADE_DB_NAME `
                -Sql @"
DELETE FROM outbox_event WHERE aggregate_id = '$orderNo';
DELETE FROM reconciliation_record WHERE reference_no = '$orderNo';
DELETE FROM order_status_history
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM order_address_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM order_discount_allocation
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM order_price_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM order_benefit_selection
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM order_item
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no = '$orderNo');
DELETE FROM trade_order WHERE order_no = '$orderNo';
DELETE FROM cart_merge_request WHERE user_id = $userId;
DELETE FROM cart_item WHERE user_id = $userId;
DELETE FROM cart_user_lock WHERE user_id = $userId;
"@ | Out-Null
        }
    }

    & $runCleanup 'inventory facts' {
        if ($warehouseId) {
            Invoke-ServiceSql `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Database $env:INVENTORY_DB_NAME `
                -Sql @"
DELETE FROM outbox_event
WHERE aggregate_id IN ('$reservationNo', '$warehouseId`:$skuId');
DELETE FROM reconciliation_record WHERE reference_no LIKE '$warehouseId`:%';
DELETE FROM stock_movement WHERE warehouse_id = $warehouseId;
DELETE FROM inventory_reservation_item
WHERE reservation_id IN (
    SELECT id FROM inventory_reservation WHERE warehouse_id = $warehouseId
);
DELETE FROM inventory_reservation WHERE warehouse_id = $warehouseId;
DELETE FROM stock_adjustment WHERE warehouse_id = $warehouseId;
DELETE FROM inventory_balance WHERE warehouse_id = $warehouseId;
DELETE FROM warehouse WHERE id = $warehouseId;
"@ | Out-Null
        }
    }

    & $runCleanup 'catalog facts' {
        if ($productId) {
            Invoke-ServiceSql `
                -User $env:CATALOG_DB_USER `
                -Password $env:CATALOG_DB_PASSWORD `
                -Database $env:CATALOG_DB_NAME `
                -Sql @"
DELETE FROM product_media WHERE spu_id = $productId;
DELETE FROM product_sku WHERE spu_id = $productId;
DELETE FROM product_spu WHERE id = $productId;
DELETE FROM catalog_brand WHERE slug = '$brandSlug';
DELETE FROM catalog_category WHERE slug = '$categorySlug';
"@ | Out-Null
        }
    }

    & $runCleanup 'identity and Redis facts' {
        if ($email) {
            Invoke-ServiceSql `
                -User $env:IDENTITY_DB_USER `
                -Password $env:IDENTITY_DB_PASSWORD `
                -Database $env:IDENTITY_DB_NAME `
                -Sql @"
DELETE FROM refresh_token
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$email');
DELETE FROM user_role
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$email');
DELETE FROM user_address
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$email');
DELETE FROM user_account WHERE email = '$email';
DELETE FROM login_record WHERE normalized_email = '$email';
"@ | Out-Null

            $emailHash = Get-Sha256Hex -Value $email
            $clientHash = Get-Sha256Hex -Value '127.0.0.1'
            docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis redis-cli DEL `
                "ecommerce:local:identity:login:failures:$emailHash" `
                "ecommerce:local:identity:login:lock:$emailHash" `
                "ecommerce:local:gateway:rate:login:$clientHash" `
                "ecommerce:local:gateway:rate:registration:$clientHash" `
                "ecommerce:local:gateway:rate:refresh:$clientHash" *> $null
            if ($LASTEXITCODE -ne 0) {
                throw 'Redis key cleanup failed.'
            }
        }
    }

    $ErrorActionPreference = $previousErrorAction
}

if ($verificationError) {
    throw $verificationError
}
if ($cleanupErrors.Count -gt 0) {
    throw "M4 checkout verification cleanup failed: $($cleanupErrors -join ' | ')"
}

$evidence | ConvertTo-Json -Depth 5
