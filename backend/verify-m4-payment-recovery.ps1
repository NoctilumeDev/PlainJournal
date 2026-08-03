#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(0, 600)]
    [int]$BrowserHoldSeconds = 0,
    [switch]$BrowserCreatesPayment,
    [string]$GatewayBaseUrl,
    [ValidateRange(1024, 65535)]
    [int]$ProxyPort = 18601,
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
        mysql "-u$User" $Database -N -B -e $Sql
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

function Get-HmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Secret
    )

    $algorithm = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        return [Convert]::ToHexString(
            $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
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

$runDirectory = Join-Path $PSScriptRoot '.run\m4-payment-recovery-20260721'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
if (-not $ArmFile) {
    $ArmFile = Join-Path $runDirectory 'payment-create-proxy.arm'
}
if (-not $ProxyEvidenceFile) {
    $ProxyEvidenceFile = Join-Path $runDirectory 'payment-create-proxy-evidence.json'
}
Remove-Item -LiteralPath $ArmFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $ProxyEvidenceFile -Force -ErrorAction SilentlyContinue

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

foreach ($port in 18000, 18101, 18103, 18104, 18105, 18106, 18107) {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health/liveness" -TimeoutSec 5
    if ($health.status -ne 'UP') {
        throw "Required application on port $port is not healthy."
    }
}

$runToken = [Guid]::NewGuid().ToString('N').Substring(0, 12).ToLowerInvariant()
$upperToken = $runToken.ToUpperInvariant()
$localProxyProcess = $null
$localProxyReadyFile = Join-Path $runDirectory "payment-create-proxy-$runToken.ready"
$localProxyOut = Join-Path $runDirectory "payment-create-proxy-$runToken.out.log"
$localProxyErr = Join-Path $runDirectory "payment-create-proxy-$runToken.err.log"
if ($BrowserCreatesPayment -and -not $BrowserFixtureFile) {
    $BrowserFixtureFile = Join-Path $runDirectory "payment-browser-fixture-$runToken.json"
}
$gateway = $null
$email = "m4-payment-$runToken@example.invalid"
$otherEmail = "m4-payment-other-$runToken@example.invalid"
$password = 'M4PaymentPass123'
$orderNo = "ORDM4PAY$upperToken"
$reservationNo = "RSVM4PAY$upperToken"
$orderKey = "fixture:$runToken"
$paymentKey = "payment:$runToken"
$marketingLockNo = "PLKM4PAY$upperToken"
$eventId = "EVT-M4-PAY-$upperToken"
$transactionNo = "TXN-M4-PAY-$upperToken"
$amount = '378.00'
$userId = $null
$otherUserId = $null
$paymentNo = $null
$paymentEventIds = @()
$tradeEventIds = @()
$fulfillmentEventIds = @()
$fulfillmentNo = $null
$verificationError = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$evidence = $null
$warehouseCode = "M4-PAY-WH-$upperToken"

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
            throw "Payment response-loss proxy port $ProxyPort is already in use."
        }
        $proxyScript = Join-Path $PSScriptRoot 'tools\payment-create-response-drop-proxy.ps1'
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
                throw "Payment response-loss proxy exited with code $($localProxyProcess.ExitCode)."
            }
            if ((Get-Date) -ge $proxyDeadline) {
                throw 'Payment response-loss proxy did not become ready.'
            }
            Start-Sleep -Milliseconds 250
        }
        $GatewayBaseUrl = "http://127.0.0.1:$ProxyPort/api/v1"
    }
    $gateway = $GatewayBaseUrl.TrimEnd('/')

    $registration = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/register" -Body @{
        email = $email
        password = $password
        displayName = 'M4 Payment Evidence'
    }
    $userId = [string]$registration.data.id
    $login = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/login" -Body @{
        email = $email
        password = $password
    }
    $customerHeaders = @{ Authorization = "Bearer $($login.data.accessToken)" }

    $otherRegistration = Invoke-JsonApi -Method Post -Uri "$gateway/identity/auth/register" -Body @{
        email = $otherEmail
        password = $password
        displayName = 'M4 Payment Other'
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
    ($orderId, '$orderNo', $userId, '$orderKey', '$('a' * 64)', '$reservationNo',
     '$warehouseCode', $warehouseId, 'PENDING_PAYMENT', $amount, '$deadlineSql',
     NULL, 0, NULL, NULL, 1, '$nowSql', '$nowSql', 398.00, 20.00, '$marketingLockNo');
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
     line_no, discount_amount, payable_amount)
VALUES
    ($itemId, $orderId, $productId, $skuId, 'M4 Payment Fixture', 'M4-PAY-$upperToken',
     CONVERT(0xE9BB98E8AEA4E8A784E6A0BC USING utf8mb4),
     '{}', NULL, 199.00, 2, 398.00, '$nowSql', 1, 20.00, 378.00);
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province, city,
     district, detail_address, postal_code, created_at, province_code, city_code, district_code)
VALUES
    ($addressSnapshotId, $orderId, $sourceAddressId, 'M4 Customer', '+86 13800000000',
     CONVERT(0xE6B599E6B19FE79C81 USING utf8mb4),
     CONVERT(0xE69DADE5B79EE5B882 USING utf8mb4),
     CONVERT(0xE8A5BFE6B996E58CBA USING utf8mb4),
     CONVERT(0xE69687E4B889E8B7AF203120E58FB7 USING utf8mb4),
     '310000', '$nowSql',
     '330000', '330100', '330106');
INSERT INTO order_price_snapshot
    (id, order_id, marketing_lock_no, original_amount, coupon_discount,
     red_packet_discount, subsidy_discount, discount_amount, payable_amount,
     pricing_version, created_at)
VALUES
    ($priceSnapshotId, $orderId, '$marketingLockNo', 398.00, 20.00,
     0.00, 0.00, 20.00, 378.00, 'm4-payment-v1', '$nowSql');
INSERT INTO order_status_history
    (id, order_id, from_status, to_status, command, reason, operator_type, operator_id, created_at)
VALUES
    ($historyId, $orderId, 'PENDING_STOCK', 'PENDING_PAYMENT',
     'FIXTURE_READY', 'M4_PAYMENT_VERIFICATION', 'SYSTEM', 'verification', '$nowSql');
"@ | Out-Null

    Invoke-ServiceSql `
        -User $env:INVENTORY_DB_USER `
        -Password $env:INVENTORY_DB_PASSWORD `
        -Database $env:INVENTORY_DB_NAME `
        -Sql @"
INSERT INTO warehouse
    (id, code, name, status, version, created_at, updated_at)
VALUES
    ($warehouseId, '$warehouseCode', 'M4 Payment Warehouse', 'ACTIVE', 0,
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
     'M4 payment fixture reserved inventory', '$nowSql');
"@ | Out-Null

    $order = Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
        -Headers $customerHeaders
    Assert-Equal $order.data.status 'PENDING_PAYMENT' 'Trade fixture did not remain payable.'

    $ownerHidden = Invoke-ApiStatus -Method Get `
        -Uri "$gateway/payment/payments/by-order/$orderNo" -Headers $otherHeaders
    Assert-Equal $ownerHidden.StatusCode 404 'Cross-account payment lookup did not return 404.'
    $otherCreate = Invoke-ApiStatus -Method Post -Uri "$gateway/payment/payments" `
        -Headers ($otherHeaders + @{ 'Idempotency-Key' = "payment:other:$runToken" }) `
        -Body @{ orderNo = $orderNo; channel = 'MOCK' }
    Assert-Equal $otherCreate.StatusCode 404 'Cross-account payment creation did not return 404.'

    Set-Content -LiteralPath $ArmFile -Value 'armed' -Encoding ascii
    if ($BrowserCreatesPayment) {
        $browserEvidence = [ordered]@{
            email = $email
            password = $password
            orderNo = $orderNo
            holdSeconds = $BrowserHoldSeconds
        }
        $browserEvidenceJson = $browserEvidence | ConvertTo-Json -Compress
        $browserEvidenceJson |
            Set-Content -LiteralPath $BrowserFixtureFile -Encoding utf8
        [ordered]@{
            fixtureFile = $BrowserFixtureFile
            email = $email
            orderNo = $orderNo
            holdSeconds = $BrowserHoldSeconds
        } | ConvertTo-Json -Compress | ForEach-Object {
            Write-Output "BROWSER_FIXTURE_READY=$_"
        }
        if ($BrowserHoldSeconds -le 0) {
            throw 'BrowserCreatesPayment requires a positive BrowserHoldSeconds value.'
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
            throw 'Browser did not trigger the armed payment response-loss proxy.'
        }
        $proxyEvidence = Get-Content -LiteralPath $ProxyEvidenceFile -Raw | ConvertFrom-Json
        $paymentKey = [string]$proxyEvidence.idempotencyKey
        if (-not $paymentKey) {
            throw 'Proxy evidence did not capture the payment idempotency key.'
        }
    }
    else {
        $responseDropped = $false
        try {
            Invoke-JsonApi -Method Post -Uri "$gateway/payment/payments" `
                -Headers ($customerHeaders + @{ 'Idempotency-Key' = $paymentKey }) `
                -Body @{ orderNo = $orderNo; channel = 'MOCK' } | Out-Null
        }
        catch {
            $responseDropped = $true
        }
        Assert-Equal $responseDropped $true 'Armed payment create response was not dropped.'
        if (-not (Test-Path -LiteralPath $ProxyEvidenceFile)) {
            throw 'Payment creation failed before the proxy could drop an upstream HTTP 200 response.'
        }
        $createEvidence = Get-Content -LiteralPath $ProxyEvidenceFile -Raw | ConvertFrom-Json
        Assert-Equal $createEvidence.upstreamStatus 200 `
            'Payment creation did not reach an upstream HTTP 200 before response loss.'
    }

    $recovered = Invoke-JsonApi -Method Get `
        -Uri "$gateway/payment/payments/by-idempotency-key/$([Uri]::EscapeDataString($paymentKey))" `
        -Headers $customerHeaders
    $paymentNo = [string]$recovered.data.paymentNo
    Assert-Equal $recovered.data.status 'PROCESSING' 'Recovered payment was not PROCESSING.'
    Assert-Equal $recovered.data.orderNo $orderNo 'Recovered payment order mismatch.'
    Assert-Equal ([decimal]$recovered.data.amount) ([decimal]$amount) `
        'Recovered payment amount mismatch.'

    $byOrder = Invoke-JsonApi -Method Get `
        -Uri "$gateway/payment/payments/by-order/$orderNo" -Headers $customerHeaders
    Assert-Equal $byOrder.data.paymentNo $paymentNo 'Order lookup returned another payment.'
    $retried = Invoke-JsonApi -Method Post -Uri "$gateway/payment/payments" `
        -Headers ($customerHeaders + @{ 'Idempotency-Key' = $paymentKey }) `
        -Body @{ orderNo = $orderNo; channel = 'MOCK' }
    Assert-Equal $retried.data.paymentNo $paymentNo 'Original-key retry created another payment.'

    foreach ($path in @(
            "payments/$paymentNo",
            "payments/by-idempotency-key/$([Uri]::EscapeDataString($paymentKey))",
            "payments/by-order/$orderNo")) {
        $hidden = Invoke-ApiStatus -Method Get -Uri "$gateway/payment/$path" -Headers $otherHeaders
        Assert-Equal $hidden.StatusCode 404 "Cross-account payment path was not hidden: $path"
    }

    $paymentFactsBeforeCallback = Invoke-ServiceSql `
        -User $env:PAYMENT_DB_USER `
        -Password $env:PAYMENT_DB_PASSWORD `
        -Database $env:PAYMENT_DB_NAME `
        -Sql "SELECT CONCAT(COUNT(*), '|', COUNT(DISTINCT payment_no), '|', MAX(status))
              FROM payment_order WHERE order_no = '$orderNo';"
    Assert-Equal $paymentFactsBeforeCallback '1|1|PROCESSING' `
        'Payment response recovery did not preserve one PROCESSING fact.'

    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $canonical = "$paymentNo|$eventId|$transactionNo|SUCCESS|378|$timestamp"
    $signature = Get-HmacSha256Hex -Value $canonical `
        -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
    $callbackBody = @{
        paymentNo = $paymentNo
        externalEventId = $eventId
        externalTransactionNo = $transactionNo
        status = 'SUCCESS'
        amount = $amount
        timestamp = $timestamp
        signature = $signature
    }
    $callback = Invoke-JsonApi -Method Post -Uri "$gateway/payment/callbacks/mock" `
        -Body $callbackBody
    Assert-Equal $callback.data.status 'SUCCESS' 'Signed callback did not confirm payment.'
    $duplicateCallback = Invoke-JsonApi -Method Post -Uri "$gateway/payment/callbacks/mock" `
        -Body $callbackBody
    Assert-Equal $duplicateCallback.data.status 'SUCCESS' 'Duplicate callback was not idempotent.'

    Wait-ForExpectedValue -Expected 'SUCCESS' `
        -Message 'Payment did not remain SUCCESS after callback.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/payment/payments/$paymentNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null
    Wait-ForExpectedValue -Expected 'PAID' `
        -Message 'Trade did not consume PaymentSucceeded and become PAID.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null

    $paymentFactsAfterCallback = Invoke-ServiceSql `
        -User $env:PAYMENT_DB_USER `
        -Password $env:PAYMENT_DB_PASSWORD `
        -Database $env:PAYMENT_DB_NAME `
        -Sql @"
SELECT CONCAT(
    (SELECT COUNT(*) FROM payment_order WHERE order_no = '$orderNo'), '|',
    (SELECT COUNT(*) FROM payment_transaction WHERE payment_id =
        (SELECT id FROM payment_order WHERE order_no = '$orderNo')), '|',
    (SELECT COUNT(*) FROM payment_callback_log WHERE payment_no = '$paymentNo'), '|',
    (SELECT COUNT(*) FROM outbox_event
        WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded'), '|',
    (SELECT MAX(status) FROM payment_order WHERE order_no = '$orderNo')
);
"@
    Assert-Equal $paymentFactsAfterCallback '1|1|1|1|SUCCESS' `
        'Payment callback facts were not unique and successful.'
    $tradePaidHistory = Invoke-ServiceSql `
        -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD `
        -Database $env:TRADE_DB_NAME `
        -Sql "SELECT COUNT(*) FROM order_status_history
              WHERE order_id = $orderId AND to_status = 'PAID';"
    Assert-Equal $tradePaidHistory '1' 'Trade PAID history was not unique.'

    $tradeEventIds = @(Invoke-ServiceSql `
            -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD `
            -Database $env:TRADE_DB_NAME `
            -Sql "SELECT id FROM outbox_event
                  WHERE aggregate_id = '$orderNo' AND event_type = 'OrderPaid';" `
            -AllRows |
            Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
    Assert-Equal $tradeEventIds.Count 1 'Trade did not persist one OrderPaid event.'
    $tradeEventSql = ($tradeEventIds | ForEach-Object { "'$_'" }) -join ','

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
    Wait-ForExpectedValue -Expected 'CREATED' `
        -Message 'Fulfillment did not consume OrderPaid and create its owner-domain fact.' `
        -Operation {
        try {
            (Invoke-JsonApi -Method Get -Uri "$gateway/fulfillment/orders/$orderNo" `
                    -Headers $customerHeaders).data.status
        }
        catch {
            $null
        }
    } | Out-Null
    $fulfillmentView = Invoke-JsonApi -Method Get `
        -Uri "$gateway/fulfillment/orders/$orderNo" -Headers $customerHeaders
    $fulfillmentNo = [string]$fulfillmentView.data.fulfillmentNo
    Wait-ForExpectedValue -Expected 'FULFILLING' `
        -Message 'Trade did not consume FulfillmentCreated before fixture cleanup.' `
        -Operation {
        (Invoke-JsonApi -Method Get -Uri "$gateway/trade/orders/$orderNo" `
                -Headers $customerHeaders).data.status
    } | Out-Null
    Wait-ForExpectedValue -Expected '1' `
        -Message 'Marketing did not consume OrderPaid before fixture cleanup.' `
        -Operation {
        Invoke-ServiceSql `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Database $env:MARKETING_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumed_event
                  WHERE event_id IN ($tradeEventSql);"
    } | Out-Null

    $relatedConsumerFailures =
        [int](Invoke-ServiceSql -User $env:TRADE_DB_USER -Password $env:TRADE_DB_PASSWORD `
            -Database $env:TRADE_DB_NAME `
            -Sql "SELECT COUNT(*) FROM consumer_failure
                  WHERE raw_payload LIKE '%$orderNo%';") +
        [int](Invoke-ServiceSql -User $env:PAYMENT_DB_USER -Password $env:PAYMENT_DB_PASSWORD `
            -Database $env:PAYMENT_DB_NAME `
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
        'Payment verification created a related consumer failure.'

    $proxyEvidence = Get-Content -LiteralPath $ProxyEvidenceFile -Raw | ConvertFrom-Json
    Assert-Equal $proxyEvidence.upstreamStatus 200 `
        'Payment response-loss proxy did not observe an upstream HTTP 200.'
    Assert-Equal $proxyEvidence.idempotencyKey $paymentKey `
        'Payment proxy evidence key mismatch.'

    $evidence = [ordered]@{
        schemaVersion = 1
        configurationSource = 'nacos'
        orderNo = $orderNo
        paymentNo = $paymentNo
        paymentKey = $paymentKey
        amount = $amount
        recoveredStatus = 'PROCESSING'
        callbackStatus = 'SUCCESS'
        tradeStatus = 'PAID'
        downstreamTradeStatus = 'FULFILLING'
        inventoryReservationStatus = 'CONFIRMED'
        fulfillmentStatus = 'CREATED'
        upstreamCreateStatus = [int]$proxyEvidence.upstreamStatus
        paymentFactsBeforeCallback = $paymentFactsBeforeCallback
        paymentFactsAfterCallback = $paymentFactsAfterCallback
        tradePaidHistory = [int]$tradePaidHistory
        relatedConsumerFailures = $relatedConsumerFailures
        crossAccountHidden = $true
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

    & $runCleanup 'capture downstream event identifiers' {
        if ($orderNo -and $tradeEventIds.Count -eq 0) {
            $tradeEventIds = @(Invoke-ServiceSql `
                    -User $env:TRADE_DB_USER `
                    -Password $env:TRADE_DB_PASSWORD `
                    -Database $env:TRADE_DB_NAME `
                    -Sql "SELECT id FROM outbox_event WHERE aggregate_id = '$orderNo';" `
                    -AllRows |
                    Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
        }
        if (-not $fulfillmentNo -and $orderNo) {
            $fulfillmentNo = Invoke-ServiceSql `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Database $env:FULFILLMENT_DB_NAME `
                -Sql "SELECT fulfillment_no FROM fulfillment_order
                      WHERE order_no = '$orderNo';"
        }
        if ($fulfillmentNo) {
            $fulfillmentEventIds = @(Invoke-ServiceSql `
                    -User $env:FULFILLMENT_DB_USER `
                    -Password $env:FULFILLMENT_DB_PASSWORD `
                    -Database $env:FULFILLMENT_DB_NAME `
                    -Sql "SELECT id FROM outbox_event
                          WHERE aggregate_id = '$fulfillmentNo';" `
                    -AllRows |
                    Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
        }
    }

    & $runCleanup 'payment facts' {
        if ($paymentNo) {
            $paymentEventIds = @(Invoke-ServiceSql `
                    -User $env:PAYMENT_DB_USER `
                    -Password $env:PAYMENT_DB_PASSWORD `
                    -Database $env:PAYMENT_DB_NAME `
                    -Sql "SELECT id FROM outbox_event WHERE aggregate_id = '$paymentNo';" `
                    -AllRows)
            Invoke-ServiceSql `
                -User $env:PAYMENT_DB_USER `
                -Password $env:PAYMENT_DB_PASSWORD `
                -Database $env:PAYMENT_DB_NAME `
                -Sql @"
DELETE FROM reconciliation_record WHERE reference_no = '$paymentNo';
DELETE FROM outbox_event WHERE aggregate_id = '$paymentNo';
DELETE FROM payment_callback_log WHERE payment_no = '$paymentNo';
DELETE FROM payment_transaction
WHERE payment_id IN (SELECT id FROM payment_order WHERE payment_no = '$paymentNo');
DELETE FROM payment_order WHERE payment_no = '$paymentNo';
"@ | Out-Null
        }
    }

    & $runCleanup 'fulfillment facts' {
        if ($fulfillmentNo) {
            $consumedDelete = ''
            $validTradeEventIds = @(
                $tradeEventIds | Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
            if ($validTradeEventIds.Count -gt 0) {
                $eventSql = ($validTradeEventIds | ForEach-Object { "'$_'" }) -join ','
                $consumedDelete = "DELETE FROM consumed_event WHERE event_id IN ($eventSql);"
            }
            Invoke-ServiceSql `
                -User $env:FULFILLMENT_DB_USER `
                -Password $env:FULFILLMENT_DB_PASSWORD `
                -Database $env:FULFILLMENT_DB_NAME `
                -Sql @"
$consumedDelete
DELETE FROM reconciliation_record WHERE reference_no = '$fulfillmentNo';
DELETE FROM outbox_event WHERE aggregate_id = '$fulfillmentNo';
DELETE FROM logistics_trace
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_status_history
WHERE fulfillment_id IN (
    SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';
"@ | Out-Null
        }
    }

    & $runCleanup 'inventory facts' {
        if ($reservationNo) {
            $consumedDelete = ''
            $validTradeEventIds = @(
                $tradeEventIds | Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
            if ($validTradeEventIds.Count -gt 0) {
                $eventSql = ($validTradeEventIds | ForEach-Object { "'$_'" }) -join ','
                $consumedDelete = "DELETE FROM consumed_event WHERE event_id IN ($eventSql);"
            }
            Invoke-ServiceSql `
                -User $env:INVENTORY_DB_USER `
                -Password $env:INVENTORY_DB_PASSWORD `
                -Database $env:INVENTORY_DB_NAME `
                -Sql @"
$consumedDelete
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
        $validTradeEventIds = @(
            $tradeEventIds | Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
        if ($validTradeEventIds.Count -gt 0) {
            $eventSql = ($validTradeEventIds | ForEach-Object { "'$_'" }) -join ','
            Invoke-ServiceSql `
                -User $env:MARKETING_DB_USER `
                -Password $env:MARKETING_DB_PASSWORD `
                -Database $env:MARKETING_DB_NAME `
                -Sql "DELETE FROM consumed_event WHERE event_id IN ($eventSql);" | Out-Null
        }
    }

    & $runCleanup 'trade fixture' {
        if ($userId) {
            $eventDelete = ''
            $allConsumedEventIds = @($paymentEventIds) + @($fulfillmentEventIds)
            $validEventIds = @(
                $allConsumedEventIds |
                    Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' })
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
    & $runCleanup 'local payment response-loss proxy' {
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
    throw "M4 payment verification cleanup failed: $($cleanupErrors -join ' | ')"
}

$evidence | ConvertTo-Json -Depth 5
