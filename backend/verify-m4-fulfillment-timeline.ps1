#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(0, 600)]
    [int]$BrowserHoldSeconds = 0,
    [switch]$BrowserConfirmsReceipt,
    [string]$GatewayBaseUrl,
    [ValidateRange(1024, 65535)]
    [int]$ProxyPort = 18602,
    [string]$ArmFile,
    [string]$ProxyEvidenceFile,
    [string]$BrowserFixtureFile,
    [string]$BrowserContinueFile
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

function Invoke-ServiceSql {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllRows
    )

    $result = docker exec -e "MYSQL_PWD=$Password" plainjournal-mysql `
        mysql "-u$User" --default-character-set=utf8mb4 $Database -N -B -e $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "SQL failed for database $Database."
    }
    if ($AllRows) {
        return @($result)
    }
    return $result | Select-Object -Last 1
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

function Invoke-ApiStatus {
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
    return Invoke-WebRequest @parameters
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

function Wait-ForExpectedValue {
    param(
        [Parameter(Mandatory)][scriptblock]$Operation,
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][string]$Message,
        [int]$TimeoutSeconds = 45,
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

    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile

$runDirectory = Join-Path $PSScriptRoot '.run\m4-fulfillment-timeline-20260721'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
if (-not $ArmFile) {
    $ArmFile = Join-Path $runDirectory 'fulfillment-confirm-proxy.arm'
}
if (-not $ProxyEvidenceFile) {
    $ProxyEvidenceFile = Join-Path $runDirectory 'fulfillment-confirm-proxy-evidence.json'
}
Remove-Item -LiteralPath $ArmFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $ProxyEvidenceFile -Force -ErrorAction SilentlyContinue
$httpEvidenceFile = Join-Path $runDirectory 'http-evidence.json'

foreach ($container in @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy')) {
    if ((docker inspect --format '{{.State.Running}}' $container 2>$null) -ne 'true') {
        throw "Required container is not running: $container"
    }
}

foreach ($port in 18000, 18101, 18103, 18104, 18106, 18107) {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/liveness" -TimeoutSec 5
    if ($health.status -ne 'UP') {
        throw "Required application on port $port is not healthy."
    }
}

$runToken = [Guid]::NewGuid().ToString('N').Substring(0, 12).ToLowerInvariant()
$upperToken = $runToken.ToUpperInvariant()
$localProxyProcess = $null
$localProxyReadyFile = Join-Path $runDirectory "fulfillment-confirm-proxy-$runToken.ready"
$localProxyOut = Join-Path $runDirectory "fulfillment-confirm-proxy-$runToken.out.log"
$localProxyErr = Join-Path $runDirectory "fulfillment-confirm-proxy-$runToken.err.log"
if ($BrowserConfirmsReceipt -and -not $BrowserFixtureFile) {
    $BrowserFixtureFile = Join-Path $runDirectory `
        "fulfillment-browser-fixture-$runToken.json"
}
$gateway = $null
$email = "m4-fulfillment-$runToken@example.invalid"
$otherEmail = "m4-fulfillment-other-$runToken@example.invalid"
$password = 'M4FulfillmentPass123'
$orderNo = "ORDM4FUL$upperToken"
$orderKey = "fixture:$runToken"
$reservationNo = "RSVM4FUL$upperToken"
$marketingLockNo = "PLKM4FUL$upperToken"
$trackingNo = "TRACKM4FUL$upperToken"
$orderPaidEventId = [Guid]::NewGuid().ToString()
$userId = $null
$otherUserId = $null
$fulfillmentNo = $null
$fulfillmentEventIds = @()
$verificationError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$evidence = $null
$warehouseCode = "M4-FUL-WH-$upperToken"

$baseId = [long](
    ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 100000L) +
    (Get-Random -Minimum 1000 -Maximum 9999))
$orderId = $baseId
$itemId = $baseId + 1
$addressSnapshotId = $baseId + 2
$priceSnapshotId = $baseId + 3
$historyId = $baseId + 4
$productId = $baseId + 5
$skuId = $baseId + 6
$sourceAddressId = $baseId + 7
$warehouseId = $baseId + 8
$balanceId = $baseId + 9
$reservationId = $baseId + 10
$reservationItemId = $baseId + 11
$reserveMovementId = $baseId + 12

try {
    if (-not $GatewayBaseUrl) {
        if (Get-NetTCPConnection -State Listen -LocalPort $ProxyPort -ErrorAction SilentlyContinue) {
            throw "Fulfillment response-loss proxy port $ProxyPort is already in use."
        }
        $proxyScript = Join-Path $PSScriptRoot 'tools\fulfillment-confirm-response-drop-proxy.ps1'
        $localProxyProcess = Start-Process `
            -FilePath (Get-Command pwsh).Source `
            -ArgumentList @(
                '-NoProfile',
                '-File', $proxyScript,
                '-ListenPort', $ProxyPort,
                '-UpstreamBaseUrl', 'http://127.0.0.1:18000',
                '-ArmFile', $ArmFile,
                '-EvidenceFile', $ProxyEvidenceFile,
                '-ReadyFile', $localProxyReadyFile
            ) `
            -PassThru `
            -WindowStyle Hidden `
            -RedirectStandardOutput $localProxyOut `
            -RedirectStandardError $localProxyErr
        $proxyDeadline = (Get-Date).AddSeconds(30)
        while (-not (Test-Path -LiteralPath $localProxyReadyFile)) {
            if ($localProxyProcess.HasExited) {
                throw "Fulfillment response-loss proxy exited with code $($localProxyProcess.ExitCode)."
            }
            if ((Get-Date) -ge $proxyDeadline) {
                throw 'Fulfillment response-loss proxy did not become ready.'
            }
            Start-Sleep -Milliseconds 250
        }
        $GatewayBaseUrl = "http://127.0.0.1:$ProxyPort/api/v1"
    }
    $gateway = $GatewayBaseUrl.TrimEnd('/')

    $registration = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/register" -Body @{
        email = $email
        password = $password
        displayName = 'M4 Fulfillment Evidence'
    }
    $userId = [string]$registration.data.id
    Invoke-ServiceSql `
        -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD `
        -Database $env:IDENTITY_DB_NAME `
        -Sql @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT user_account.id, identity_role.id, CURRENT_TIMESTAMP(3)
FROM user_account
JOIN identity_role ON identity_role.code = 'WAREHOUSE'
WHERE user_account.email = '$email';
"@ | Out-Null
    $login = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/login" -Body @{
        email = $email
        password = $password
    }
    $customerHeaders = @{ Authorization = "Bearer $($login.data.accessToken)" }
    $profile = Invoke-JsonApi -Method Get -Uri "$gateway/identity/me" -Headers $customerHeaders
    if ($profile.data.roles -notcontains 'WAREHOUSE') {
        throw 'Temporary fulfillment account did not receive the WAREHOUSE role.'
    }

    $otherRegistration = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/register" -Body @{
        email = $otherEmail
        password = $password
        displayName = 'M4 Fulfillment Other'
    }
    $otherUserId = [string]$otherRegistration.data.id
    $otherLogin = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/login" -Body @{
        email = $otherEmail
        password = $password
    }
    $otherHeaders = @{ Authorization = "Bearer $($otherLogin.data.accessToken)" }

    $now = [DateTimeOffset]::UtcNow
    $databaseOffset = [TimeSpan]::FromHours(8)
    $nowSql = $now.ToOffset($databaseOffset).ToString('yyyy-MM-dd HH:mm:ss.fff')
    $deadlineSql = $now.AddMinutes(15).ToOffset($databaseOffset).ToString(
        'yyyy-MM-dd HH:mm:ss.fff')
    $orderPaidEnvelope = [ordered]@{
        eventId = $orderPaidEventId
        eventType = 'OrderPaid'
        aggregateType = 'TradeOrder'
        aggregateId = $orderNo
        aggregateVersion = 2
        occurredAt = $now.ToString('o')
        producer = 'trade-service'
        traceId = $null
        payloadVersion = 1
        payload = [ordered]@{
            orderNo = $orderNo
            userId = [long]$userId
            reservationNo = $reservationNo
            deliveryAddress = [ordered]@{
                sourceAddressId = $sourceAddressId
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
            }
        }
    }
    $orderPaidJson = ($orderPaidEnvelope | ConvertTo-Json -Compress -Depth 12).Replace("'", "''")
    Invoke-ServiceSql `
        -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD `
        -Database $env:INVENTORY_DB_NAME `
        -Sql @"
INSERT INTO warehouse
    (id, code, name, status, version, created_at, updated_at)
VALUES
    ($warehouseId, '$warehouseCode', 'M4 Fulfillment Warehouse', 'ACTIVE', 0,
     '$nowSql', '$nowSql');
INSERT INTO inventory_balance
    (id, warehouse_id, sku_id, on_hand, reserved, version, created_at, updated_at)
VALUES
    ($balanceId, $warehouseId, $skuId, 2, 2, 0, '$nowSql', '$nowSql');
INSERT INTO inventory_reservation
    (id, reservation_no, order_no, request_hash, warehouse_id, status,
     expires_at, version, created_at, updated_at)
VALUES
    ($reservationId, '$reservationNo', '$orderNo', '$('c' * 64)', $warehouseId,
     'RESERVED', '$deadlineSql', 0, '$nowSql', '$nowSql');
INSERT INTO inventory_reservation_item
    (id, reservation_id, sku_id, quantity, created_at)
VALUES
    ($reservationItemId, $reservationId, $skuId, 2, '$nowSql');
INSERT INTO stock_movement
    (id, movement_no, warehouse_id, sku_id, reservation_no, movement_type,
     quantity_delta, on_hand_after, reserved_after, reason, created_at)
VALUES
    ($reserveMovementId, '$reservationNo`:RESERVE`:$skuId', $warehouseId, $skuId,
     '$reservationNo', 'RESERVE', 2, 2, 2,
     'M4 fulfillment fixture reserved inventory', '$nowSql');
"@ | Out-Null

    Invoke-ServiceSql `
        -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD `
        -Database $env:TRADE_DB_NAME `
        -Sql @"
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, total_amount, payment_deadline,
     close_reason, recovery_attempts, next_recovery_at, last_error, version,
     created_at, updated_at, original_amount, discount_amount, marketing_lock_no)
VALUES
    ($orderId, '$orderNo', $userId, '$orderKey', '$('b' * 64)', '$reservationNo',
     '$warehouseCode', $warehouseId, 'PAID', 378.00, '$deadlineSql',
     NULL, 0, NULL, NULL, 2, '$nowSql', '$nowSql', 398.00, 20.00, '$marketingLockNo');
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
     line_no, discount_amount, payable_amount)
VALUES
    ($itemId, $orderId, $productId, $skuId, 'M4 Fulfillment Fixture', 'M4-FUL-$upperToken',
     '默认规格', '{}', NULL, 199.00, 2, 398.00, '$nowSql', 1, 20.00, 378.00);
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province, city,
     district, detail_address, postal_code, created_at, province_code, city_code, district_code)
VALUES
    ($addressSnapshotId, $orderId, $sourceAddressId, 'M4 Customer', '+86 13800000000',
     '浙江省', '杭州市', '西湖区', '文三路 1 号', '310000', '$nowSql',
     '330000', '330100', '330106');
INSERT INTO order_price_snapshot
    (id, order_id, marketing_lock_no, original_amount, coupon_discount,
     red_packet_discount, subsidy_discount, discount_amount, payable_amount,
     pricing_version, created_at)
VALUES
    ($priceSnapshotId, $orderId, '$marketingLockNo', 398.00, 20.00,
     0.00, 0.00, 20.00, 378.00, 'm4-fulfillment-v1', '$nowSql');
INSERT INTO order_status_history
    (id, order_id, from_status, to_status, command, reason, operator_type, operator_id, created_at)
VALUES
    ($historyId, $orderId, 'PENDING_PAYMENT', 'PAID',
     'FIXTURE_PAID', 'M4_FULFILLMENT_VERIFICATION', 'SYSTEM', 'verification', '$nowSql');
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
     status, attempts, next_attempt_at, claimed_at, published_at, last_error,
     created_at, updated_at, claim_owner, claim_until)
VALUES
    ('$orderPaidEventId', 'OrderPaid', 'TradeOrder', '$orderNo', 2, '$orderPaidJson',
     'PENDING', 0, '$nowSql', NULL, NULL, NULL, '$nowSql', '$nowSql', NULL, NULL);
"@ | Out-Null

    Wait-ForExpectedValue -Expected 'CREATED' `
        -Message 'OrderPaid did not create a Fulfillment fact through RocketMQ.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/fulfillment/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null
    Wait-ForExpectedValue -Expected 'FULFILLING' `
        -Message 'Trade did not consume FulfillmentCreated.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null
    $tradeFixtureView = Invoke-JsonApi -Method Get `
        -Uri "$gateway/trade/orders/$orderNo" -Headers $customerHeaders
    Assert-Equal $tradeFixtureView.data.items[0].skuName '默认规格' `
        'Trade immutable SKU snapshot lost its UTF-8 characters.'
    Assert-Equal $tradeFixtureView.data.deliveryAddress.province '浙江省' `
        'Trade immutable address snapshot lost its UTF-8 characters.'

    Wait-ForExpectedValue -Expected 'CONFIRMED' `
        -Message 'Inventory did not consume OrderPaid and confirm the reservation.' `
        -Operation {
        Invoke-ServiceSql `
            -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD `
            -Database $env:INVENTORY_DB_NAME `
            -Sql "SELECT status FROM inventory_reservation
                  WHERE reservation_no = '$reservationNo';"
    } | Out-Null
    Wait-ForExpectedValue -Expected 'PUBLISHED' `
        -Message 'Inventory confirmation Outbox did not publish before cleanup.' `
        -Operation {
        Invoke-ServiceSql `
            -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD `
            -Database $env:INVENTORY_DB_NAME `
            -Sql "SELECT status FROM outbox_event
                  WHERE aggregate_id = '$reservationNo'
                    AND event_type = 'InventoryReservationConfirmed';"
    } | Out-Null
    Wait-ForExpectedValue -Expected '1' `
        -Message 'Marketing did not consume OrderPaid before fixture cleanup.' `
        -Operation {
        Invoke-ServiceSql `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Database $env:MARKETING_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumed_event
                  WHERE event_id = '$orderPaidEventId';"
    } | Out-Null

    $ownerRead = Invoke-WebRequest -Method Get `
        -Uri "$gateway/fulfillment/orders/$orderNo" `
        -Headers $customerHeaders `
        -TimeoutSec 15
    $ownerPayload = $ownerRead.Content | ConvertFrom-Json
    $fulfillmentNo = [string]$ownerPayload.data.fulfillmentNo
    Assert-Equal ($ownerPayload.data.userId -is [string]) $true `
        'Fulfillment userId was not serialized as a JSON string.'
    Assert-Equal ($ownerPayload.data.deliveryAddress.sourceAddressId -is [string]) $true `
        'Fulfillment sourceAddressId was not serialized as a JSON string.'
    Assert-Equal $ownerPayload.data.deliveryAddress.province '浙江省' `
        'Fulfillment delivery snapshot lost its UTF-8 characters.'

    $hiddenRead = Invoke-ApiStatus -Method Get `
        -Uri "$gateway/fulfillment/orders/$orderNo" -Headers $otherHeaders
    Assert-Equal $hiddenRead.StatusCode 404 `
        'Cross-account fulfillment lookup did not return 404.'
    $hiddenConfirm = Invoke-ApiStatus -Method Post `
        -Uri "$gateway/fulfillment/orders/$orderNo/confirm-receipt" `
        -Headers $otherHeaders
    Assert-Equal $hiddenConfirm.StatusCode 404 `
        'Cross-account receipt confirmation did not return 404.'

    Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/admin/orders/$fulfillmentNo/picking" `
        -Headers $customerHeaders | Out-Null
    Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/admin/orders/$fulfillmentNo/packed" `
        -Headers $customerHeaders | Out-Null
    Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/admin/orders/$fulfillmentNo/ship" `
        -Headers $customerHeaders `
        -Body @{ carrier = 'MOCK_EXPRESS'; trackingNo = $trackingNo } | Out-Null
    Wait-ForExpectedValue -Expected 'SHIPPED' `
        -Message 'Trade did not consume ShipmentDispatched.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null

    $transitAt = [DateTimeOffset]::UtcNow.AddSeconds(-2)
    Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/admin/orders/$fulfillmentNo/traces" `
        -Headers $customerHeaders `
        -Body @{
            externalEventId = "TRANSIT-$upperToken"
            nodeType = 'TRANSIT'
            description = '包裹到达杭州转运中心'
            locationName = '杭州市'
            longitude = 120.155070
            latitude = 30.274085
            occurredAt = $transitAt.ToString('o')
        } | Out-Null
    $deliveringAt = $transitAt.AddSeconds(1)
    $beforeConfirmation = Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/admin/orders/$fulfillmentNo/traces" `
        -Headers $customerHeaders `
        -Body @{
            externalEventId = "DELIVERING-$upperToken"
            nodeType = 'DELIVERING'
            description = '快递员正在派送'
            locationName = '杭州市西湖区'
            longitude = 120.130000
            latitude = 30.270000
            occurredAt = $deliveringAt.ToString('o')
        }
    Assert-Equal $beforeConfirmation.data.status 'DELIVERING' `
        'Fulfillment did not reach DELIVERING.'
    Assert-Equal @($beforeConfirmation.data.history).Count 6 `
        'Fulfillment history did not contain six append-only steps before confirmation.'
    Assert-Equal @($beforeConfirmation.data.traces).Count 2 `
        'Fulfillment logistics did not contain two append-only carrier traces.'

    Set-Content -LiteralPath $ArmFile -Value 'armed' -Encoding ascii
    if ($BrowserConfirmsReceipt) {
        $browserFixture = [ordered]@{
            email = $email
            password = $password
            orderNo = $orderNo
            fulfillmentNo = $fulfillmentNo
            holdSeconds = $BrowserHoldSeconds
        }
        $browserFixture | ConvertTo-Json -Compress |
            Set-Content -LiteralPath $BrowserFixtureFile -Encoding utf8
        [ordered]@{
            fixtureFile = $BrowserFixtureFile
            email = $email
            orderNo = $orderNo
            fulfillmentNo = $fulfillmentNo
            holdSeconds = $BrowserHoldSeconds
        } | ConvertTo-Json -Compress | ForEach-Object {
            Write-Output "BROWSER_FIXTURE_READY=$_"
        }
        if ($BrowserHoldSeconds -le 0) {
            throw 'BrowserConfirmsReceipt requires a positive BrowserHoldSeconds value.'
        }
        if ($BrowserContinueFile) {
            $browserDeadline = (Get-Date).AddSeconds($BrowserHoldSeconds)
            while (-not (Test-Path -LiteralPath $BrowserContinueFile)) {
                if ((Get-Date) -ge $browserDeadline) {
                    throw "Browser did not create the continue marker within " +
                        "$BrowserHoldSeconds seconds."
                }
                Start-Sleep -Milliseconds 250
            }
        }
        else {
            Start-Sleep -Seconds $BrowserHoldSeconds
        }
        if (-not (Test-Path -LiteralPath $ProxyEvidenceFile)) {
            throw 'Browser did not trigger the armed fulfillment confirmation proxy.'
        }
    }
    else {
        $responseDropped = $false
        try {
            Invoke-JsonApi -Method Post `
                -Uri "$gateway/fulfillment/orders/$orderNo/confirm-receipt" `
                -Headers $customerHeaders | Out-Null
        }
        catch {
            $responseDropped = $true
        }
        Assert-Equal $responseDropped $true `
            'Armed receipt confirmation response was not dropped.'
    }

    if (-not (Test-Path -LiteralPath $ProxyEvidenceFile)) {
        throw 'Fulfillment confirmation failed before the proxy could record an upstream HTTP 200.'
    }
    $proxyEvidence = Get-Content -LiteralPath $ProxyEvidenceFile -Raw | ConvertFrom-Json
    Assert-Equal $proxyEvidence.upstreamStatus 200 `
        'Receipt confirmation did not reach an upstream HTTP 200 before response loss.'
    Assert-Equal $proxyEvidence.orderNo $orderNo `
        'Fulfillment confirmation proxy evidence order mismatch.'

    $recovered = Invoke-JsonApi -Method Get `
        -Uri "$gateway/fulfillment/orders/$orderNo" `
        -Headers $customerHeaders
    Assert-Equal $recovered.data.status 'SIGNED' `
        'Receipt confirmation response loss did not recover to SIGNED.'
    Assert-Equal @($recovered.data.history).Count 7 `
        'Fulfillment history did not retain seven append-only lifecycle steps.'
    Assert-Equal @($recovered.data.traces).Count 3 `
        'Fulfillment logistics did not retain the signed trace.'
    Assert-Equal $recovered.data.history[6].command 'CONFIRM_RECEIPT' `
        'Customer receipt confirmation was not recorded in history.'
    Assert-Equal $recovered.data.traces[2].nodeType 'SIGNED' `
        'Customer receipt confirmation did not append a SIGNED trace.'

    $idempotent = Invoke-JsonApi -Method Post `
        -Uri "$gateway/fulfillment/orders/$orderNo/confirm-receipt" `
        -Headers $customerHeaders
    Assert-Equal $idempotent.data.status 'SIGNED' `
        'Repeated receipt confirmation was not idempotent.'
    Assert-Equal @($idempotent.data.traces).Count 3 `
        'Repeated receipt confirmation created another trace.'

    Wait-ForExpectedValue -Expected 'COMPLETED' `
        -Message 'Trade did not consume ShipmentSigned and complete the order.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null

    $fulfillmentFacts = Invoke-ServiceSql `
        -User $env:FULFILLMENT_DB_USER `
        -Password $env:FULFILLMENT_DB_PASSWORD `
        -Database $env:FULFILLMENT_DB_NAME `
        -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM fulfillment_order WHERE order_no = '$orderNo'), '|',
    (SELECT COUNT(*) FROM fulfillment_status_history
        WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE order_no = '$orderNo')), '|',
    (SELECT COUNT(*) FROM logistics_trace
        WHERE fulfillment_id = (SELECT id FROM fulfillment_order WHERE order_no = '$orderNo')), '|',
    (SELECT COUNT(*) FROM outbox_event
        WHERE aggregate_id = '$fulfillmentNo' AND event_type = 'ShipmentSigned'), '|',
    (SELECT MAX(status) FROM fulfillment_order WHERE order_no = '$orderNo')
);
"@
    Assert-Equal $fulfillmentFacts '1|7|3|1|SIGNED' `
        'Fulfillment facts were not unique, append-only, and signed.'
    $tradeCompletedHistory = Invoke-ServiceSql `
        -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD `
        -Database $env:TRADE_DB_NAME `
        -Sql "SELECT COUNT(*) FROM order_status_history
              WHERE order_id = $orderId AND to_status = 'COMPLETED';"
    Assert-Equal $tradeCompletedHistory '1' `
        'Trade COMPLETED history was not unique.'

    $relatedConsumerFailures =
        [int](Invoke-ServiceSql -User $env:TRADE_DB_USER -Password $env:TRADE_DB_PASSWORD `
            -Database $env:TRADE_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumer_failure
                  WHERE raw_payload LIKE '%$orderNo%';") +
        [int](Invoke-ServiceSql -User $env:INVENTORY_DB_USER -Password $env:INVENTORY_DB_PASSWORD `
            -Database $env:INVENTORY_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumer_failure
                  WHERE raw_payload LIKE '%$orderNo%';") +
        [int](Invoke-ServiceSql -User $env:FULFILLMENT_DB_USER -Password $env:FULFILLMENT_DB_PASSWORD `
            -Database $env:FULFILLMENT_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumer_failure
                  WHERE raw_payload LIKE '%$orderNo%';")
    Assert-Equal $relatedConsumerFailures 0 `
        'Fulfillment verification created a related consumer failure.'

    $evidence = [ordered]@{
        schemaVersion = 1
        configurationSource = 'nacos'
        orderNo = $orderNo
        fulfillmentNo = $fulfillmentNo
        trackingNo = $trackingNo
        orderPaidEventId = $orderPaidEventId
        createdStatus = 'CREATED'
        beforeConfirmationStatus = 'DELIVERING'
        recoveredStatus = 'SIGNED'
        tradeStatus = 'COMPLETED'
        inventoryReservationStatus = 'CONFIRMED'
        upstreamConfirmationStatus = [int]$proxyEvidence.upstreamStatus
        ownerIdsAreStrings = $true
        crossAccountHidden = $true
        historyCount = 7
        traceCount = 3
        fulfillmentFacts = $fulfillmentFacts
        tradeCompletedHistory = [int]$tradeCompletedHistory
        relatedConsumerFailures = $relatedConsumerFailures
    }
    $evidence | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $httpEvidenceFile -Encoding utf8
}
catch {
    $verificationError = $_
}
finally {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $runCleanup = {
        param([string]$Name, [scriptblock]$Action)
        try {
            & $Action
        }
        catch {
            $cleanupErrors.Add("${Name}: $($_.Exception.Message)")
        }
    }

    & $runCleanup 'fulfillment fixture' {
        if ($fulfillmentNo) {
            $fulfillmentEventIds = @(Invoke-ServiceSql `
                    -User $env:FULFILLMENT_DB_USER `
                    -Password $env:FULFILLMENT_DB_PASSWORD `
                    -Database $env:FULFILLMENT_DB_NAME `
                    -Sql "SELECT id FROM outbox_event WHERE aggregate_id = '$fulfillmentNo';" `
                    -AllRows)
            Invoke-ServiceSql `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Database $env:FULFILLMENT_DB_NAME `
                -Sql @"
DELETE FROM reconciliation_record WHERE reference_no = '$fulfillmentNo';
DELETE FROM outbox_event WHERE aggregate_id = '$fulfillmentNo';
DELETE FROM shipment_latest_position WHERE fulfillment_no = '$fulfillmentNo';
DELETE FROM logistics_trace
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_status_history
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';
DELETE FROM consumed_event WHERE event_id = '$orderPaidEventId';
"@ | Out-Null
        }
    }

    & $runCleanup 'inventory fixture' {
        if ($reservationNo) {
            Invoke-ServiceSql `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Database $env:INVENTORY_DB_NAME `
                -Sql @"
DELETE FROM consumed_event WHERE event_id = '$orderPaidEventId';
DELETE FROM reconciliation_record
WHERE reference_no IN ('$reservationNo', '$orderNo');
DELETE FROM outbox_event WHERE aggregate_id = '$reservationNo';
DELETE FROM stock_movement WHERE reservation_no = '$reservationNo';
DELETE FROM inventory_reservation_item WHERE reservation_id = $reservationId;
DELETE FROM inventory_reservation WHERE id = $reservationId;
DELETE FROM inventory_balance WHERE id = $balanceId;
DELETE FROM warehouse WHERE id = $warehouseId;
"@ | Out-Null
        }
    }

    & $runCleanup 'marketing consumption fact' {
        if ($orderPaidEventId) {
            Invoke-ServiceSql `
                -User $env:MARKETING_DB_USER `
                -Password $env:MARKETING_DB_PASSWORD `
                -Database $env:MARKETING_DB_NAME `
                -Sql "DELETE FROM consumed_event WHERE event_id = '$orderPaidEventId';" |
                Out-Null
        }
    }

    & $runCleanup 'trade fixture' {
        if ($userId) {
            $eventDelete = ''
            $validEventIds = @($fulfillmentEventIds | Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
            if ($validEventIds.Count -gt 0) {
                $eventSql = ($validEventIds | ForEach-Object { "'$_'" }) -join ','
                $eventDelete = "DELETE FROM consumed_event WHERE event_id IN ($eventSql);"
            }
            Invoke-ServiceSql `
                -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD `
                -Database $env:TRADE_DB_NAME `
                -Sql @"
$eventDelete
DELETE FROM outbox_event WHERE aggregate_id = '$orderNo';
DELETE FROM reconciliation_record WHERE reference_no = '$orderNo';
DELETE FROM order_status_history WHERE order_id = $orderId;
DELETE FROM order_discount_allocation WHERE order_id = $orderId;
DELETE FROM order_price_snapshot WHERE order_id = $orderId;
DELETE FROM order_benefit_selection WHERE order_id = $orderId;
DELETE FROM order_address_snapshot WHERE order_id = $orderId;
DELETE FROM order_item WHERE order_id = $orderId;
DELETE FROM trade_order WHERE id = $orderId;
"@ | Out-Null
        }
    }

    & $runCleanup 'identity and Redis facts' {
        foreach ($targetEmail in @($email, $otherEmail)) {
            if (-not $targetEmail) {
                continue
            }
            Invoke-ServiceSql `
                -User $env:IDENTITY_DB_USER `
                -Password $env:IDENTITY_DB_PASSWORD `
                -Database $env:IDENTITY_DB_NAME `
                -Sql @"
DELETE FROM refresh_token
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$targetEmail');
DELETE FROM user_role
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$targetEmail');
DELETE FROM user_address
WHERE user_id IN (SELECT id FROM user_account WHERE email = '$targetEmail');
DELETE FROM user_account WHERE email = '$targetEmail';
DELETE FROM login_record WHERE normalized_email = '$targetEmail';
"@ | Out-Null
            $emailHash = Get-Sha256Hex -Value $targetEmail
            docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis redis-cli DEL `
                "ecommerce:local:identity:login:failures:$emailHash" `
                "ecommerce:local:identity:login:lock:$emailHash" *> $null
            if ($LASTEXITCODE -ne 0) {
                throw "Redis cleanup failed for $targetEmail."
            }
        }
    }

    Remove-Item -LiteralPath $ArmFile -Force -ErrorAction SilentlyContinue
    if ($BrowserFixtureFile) {
        Remove-Item -LiteralPath $BrowserFixtureFile -Force -ErrorAction SilentlyContinue
    }
    if ($BrowserContinueFile) {
        Remove-Item -LiteralPath $BrowserContinueFile -Force -ErrorAction SilentlyContinue
    }
    & $runCleanup 'local fulfillment response-loss proxy' {
        if ($localProxyProcess -and -not $localProxyProcess.HasExited) {
            Stop-Process -Id $localProxyProcess.Id -Force
            Wait-Process -Id $localProxyProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $localProxyReadyFile -Force -ErrorAction SilentlyContinue
    }
    $ErrorActionPreference = $previousErrorAction
}

if ($verificationError) {
    throw $verificationError
}
if ($cleanupErrors.Count -gt 0) {
    throw "M4 fulfillment verification cleanup failed: $($cleanupErrors -join ' | ')"
}

$evidence | ConvertTo-Json -Depth 5
