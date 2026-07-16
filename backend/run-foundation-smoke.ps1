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

        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                return $response
            }
        }
        catch {
            Start-Sleep -Milliseconds 750
        }
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Uri"
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Show-LogTail {
    param([Parameter(Mandatory)][string]$Path)

    if (Test-Path -LiteralPath $Path) {
        Write-Host "--- $Path ---"
        Get-Content -LiteralPath $Path -Tail 60
    }
}

function Invoke-JsonPost {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-RestMethod -Method Post -Uri $Uri -ContentType 'application/json' `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Compress -Depth 10) -TimeoutSec 10
}

function Invoke-JsonPostRaw {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-WebRequest -Method Post -Uri $Uri -ContentType 'application/json' `
        -Headers $Headers -Body ($Body | ConvertTo-Json -Compress -Depth 10) -TimeoutSec 10 -SkipHttpErrorCheck
}

function Assert-JsonPostRejected {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][hashtable]$Body,
        [Parameter(Mandatory)][int]$ExpectedStatus
    )

    $response = Invoke-JsonPostRaw -Uri $Uri -Body $Body
    $actualStatus = [int]$response.StatusCode

    if ($actualStatus -ne $ExpectedStatus) {
        throw "Expected HTTP $ExpectedStatus from $Uri, received $actualStatus."
    }
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

function Get-HmacSha256Hex {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Secret
    )

    $algorithm = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Remove-RedisKeys {
    param([Parameter(Mandatory)][string[]]$Keys)

    if ($Keys.Count -eq 0) {
        return
    }
    docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" ecom-redis redis-cli DEL $Keys | Out-Null
}

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Container 2>$null
        if ($status -eq 'healthy' -or $status -eq 'running') {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for container $Container"
}

function Stop-FoundationServices {
    param([Parameter(Mandatory)][int[]]$Ports)

    $processIds = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object LocalPort -in $Ports |
        Select-Object -ExpandProperty OwningProcess -Unique

    foreach ($processId in $processIds) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }

        $isFoundationService = $process.CommandLine -like '*ecommerce-gateway-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*identity-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*catalog-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*inventory-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*trade-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*payment-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*fulfillment-service-0.1.0-SNAPSHOT.jar*' -or
            $process.CommandLine -like '*marketing-service-0.1.0-SNAPSHOT.jar*'
        if ($isFoundationService) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $processId -Timeout 5 -ErrorAction SilentlyContinue
        }
    }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}

Import-DotEnv -Path $envFile
[Environment]::SetEnvironmentVariable('NACOS_USERNAME', 'nacos', 'Process')
[Environment]::SetEnvironmentVariable('NACOS_HOST', '127.0.0.1', 'Process')
[Environment]::SetEnvironmentVariable('SERVICE_IP', '127.0.0.1', 'Process')

$nacosRunning = docker inspect --format '{{.State.Running}}' ecom-nacos 2>$null
if ($nacosRunning -ne 'true') {
    throw 'Nacos container ecom-nacos is not running.'
}
$redisRunning = docker inspect --format '{{.State.Running}}' ecom-redis 2>$null
if ($redisRunning -ne 'true') {
    throw 'Redis container ecom-redis is not running.'
}

$gatewayPort = 18000
$identityPort = 18101
$catalogPort = 18102
$inventoryPort = 18103
$tradePort = 18104
$paymentPort = 18105
$fulfillmentPort = 18106
$marketingPort = 18107
Assert-PortAvailable -Port $gatewayPort
Assert-PortAvailable -Port $identityPort
Assert-PortAvailable -Port $catalogPort
Assert-PortAvailable -Port $inventoryPort
Assert-PortAvailable -Port $tradePort
Assert-PortAvailable -Port $paymentPort
Assert-PortAvailable -Port $fulfillmentPort
Assert-PortAvailable -Port $marketingPort

& mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Backend package failed with exit code $LASTEXITCODE"
}

$runDirectory = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$identityJar = Join-Path $PSScriptRoot 'services\identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
$catalogJar = Join-Path $PSScriptRoot 'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar'
$inventoryJar = Join-Path $PSScriptRoot 'services\inventory-service\target\inventory-service-0.1.0-SNAPSHOT.jar'
$tradeJar = Join-Path $PSScriptRoot 'services\trade-service\target\trade-service-0.1.0-SNAPSHOT.jar'
$paymentJar = Join-Path $PSScriptRoot 'services\payment-service\target\payment-service-0.1.0-SNAPSHOT.jar'
$fulfillmentJar = Join-Path $PSScriptRoot 'services\fulfillment-service\target\fulfillment-service-0.1.0-SNAPSHOT.jar'
$marketingJar = Join-Path $PSScriptRoot 'services\marketing-service\target\marketing-service-0.1.0-SNAPSHOT.jar'
$gatewayJar = Join-Path $PSScriptRoot 'ecommerce-gateway\target\ecommerce-gateway-0.1.0-SNAPSHOT.jar'
$identityOut = Join-Path $runDirectory 'identity.out.log'
$identityErr = Join-Path $runDirectory 'identity.err.log'
$catalogOut = Join-Path $runDirectory 'catalog.out.log'
$catalogErr = Join-Path $runDirectory 'catalog.err.log'
$inventoryOut = Join-Path $runDirectory 'inventory.out.log'
$inventoryErr = Join-Path $runDirectory 'inventory.err.log'
$tradeOut = Join-Path $runDirectory 'trade.out.log'
$tradeErr = Join-Path $runDirectory 'trade.err.log'
$paymentOut = Join-Path $runDirectory 'payment.out.log'
$paymentErr = Join-Path $runDirectory 'payment.err.log'
$fulfillmentOut = Join-Path $runDirectory 'fulfillment.out.log'
$fulfillmentErr = Join-Path $runDirectory 'fulfillment.err.log'
$marketingOut = Join-Path $runDirectory 'marketing.out.log'
$marketingErr = Join-Path $runDirectory 'marketing.err.log'
$gatewayOut = Join-Path $runDirectory 'gateway.out.log'
$gatewayErr = Join-Path $runDirectory 'gateway.err.log'
$smokeEmail = "identity-smoke-$([Guid]::NewGuid().ToString('N'))@example.invalid"
$riskEmail = "identity-risk-$([Guid]::NewGuid().ToString('N'))@example.invalid"
$riskHash = Get-Sha256Hex -Value $riskEmail
$gatewayClientHash = Get-Sha256Hex -Value '127.0.0.1'
$redisKeys = @(
    "ecommerce:local:identity:login:failures:$riskHash",
    "ecommerce:local:identity:login:lock:$riskHash",
    "ecommerce:local:gateway:rate:login:$gatewayClientHash",
    "ecommerce:local:gateway:rate:registration:$gatewayClientHash",
    "ecommerce:local:gateway:rate:refresh:$gatewayClientHash"
)
$redisStoppedBySmoke = $false
$catalogCategorySlug = "smoke-category-$([Guid]::NewGuid().ToString('N'))"
$catalogBrandSlug = "smoke-brand-$([Guid]::NewGuid().ToString('N'))"
$catalogSkuCode = "SMOKE-$([Guid]::NewGuid().ToString('N').ToUpperInvariant())"
$tradeSkuCode = "TRADE-$([Guid]::NewGuid().ToString('N').ToUpperInvariant())"
$catalogProductTitle = "Smoke Product $([Guid]::NewGuid().ToString('N'))"
$catalogObjectKey = $null
$inventoryWarehouseCode = 'PRIMARY'
$inventoryMovementNo = "SMOKE-ADJ-$([Guid]::NewGuid().ToString('N'))"
$tradeMovementNo = "SMOKE-TRADE-ADJ-$([Guid]::NewGuid().ToString('N'))"
$inventoryReservationPrefix = "SMOKE-RES-$([Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant())"
$inventoryWarehouseId = $null
$inventorySkuId = $null
$tradeSkuId = $null
$tradeOrderNumbers = @()
$tradeReservationNumbers = @()
$tradeOrderSqlList = ''
$tradeReservationSqlList = ''
$paymentNo = $null
$paymentEventId = $null
$orderPaidEventId = $null
$fulfillmentNo = $null
$fulfillmentEventIds = @()
$fulfillmentEventSqlList = ''
$afterSaleNo = $null
$returnReceiptNo = $null
$refundNo = $null
$afterSaleApprovedEventId = $null
$returnShipmentEventId = $null
$returnReceivedEventId = $null
$returnInspectedEventId = $null
$returnStockedEventId = $null
$refundRequestedEventId = $null
$refundResultEventId = $null
$smokeAddressId = $null
$smokeUserId = $null
$marketingRulePrefix = "SMOKE-MKT-$([Guid]::NewGuid().ToString('N').Substring(0, 12).ToUpperInvariant())"
$marketingBenefitNos = @()
$marketingCancelBenefitNos = @()
$marketingOrderNo = $null
$marketingCancelOrderNo = $null
$originalAddressDetail = 'No. 1 Original Smoke Street'
$updatedAddressDetail = 'No. 99 Updated Smoke Avenue'
Remove-RedisKeys -Keys $redisKeys

try {
    Start-Process -FilePath 'java' -ArgumentList @('-jar', $identityJar) `
        -WindowStyle Hidden -RedirectStandardOutput $identityOut -RedirectStandardError $identityErr
    Wait-HttpOk -Uri "http://127.0.0.1:$identityPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $catalogJar) `
        -WindowStyle Hidden -RedirectStandardOutput $catalogOut -RedirectStandardError $catalogErr
    Wait-HttpOk -Uri "http://127.0.0.1:$catalogPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $inventoryJar) `
        -WindowStyle Hidden -RedirectStandardOutput $inventoryOut -RedirectStandardError $inventoryErr
    Wait-HttpOk -Uri "http://127.0.0.1:$inventoryPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $marketingJar) `
        -WindowStyle Hidden -RedirectStandardOutput $marketingOut -RedirectStandardError $marketingErr
    Wait-HttpOk -Uri "http://127.0.0.1:$marketingPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $tradeJar) `
        -WindowStyle Hidden -RedirectStandardOutput $tradeOut -RedirectStandardError $tradeErr
    Wait-HttpOk -Uri "http://127.0.0.1:$tradePort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $paymentJar) `
        -WindowStyle Hidden -RedirectStandardOutput $paymentOut -RedirectStandardError $paymentErr
    Wait-HttpOk -Uri "http://127.0.0.1:$paymentPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $fulfillmentJar) `
        -WindowStyle Hidden -RedirectStandardOutput $fulfillmentOut -RedirectStandardError $fulfillmentErr
    Wait-HttpOk -Uri "http://127.0.0.1:$fulfillmentPort/actuator/health" | Out-Null

    Start-Process -FilePath 'java' -ArgumentList @('-jar', $gatewayJar) `
        -WindowStyle Hidden -RedirectStandardOutput $gatewayOut -RedirectStandardError $gatewayErr
    Wait-HttpOk -Uri "http://127.0.0.1:$gatewayPort/actuator/health" | Out-Null

    $requestId = 'foundation_smoke_001'
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$gatewayPort/api/v1/identity/status" `
        -Headers @{ 'X-Request-Id' = $requestId } -TimeoutSec 10
    $payload = $response.Content | ConvertFrom-Json

    if ($payload.code -ne 'OK' -or $payload.data.service -ne 'identity-service') {
        throw "Unexpected gateway response: $($response.Content)"
    }
    if ($payload.data.configurationSource -ne 'nacos') {
        throw 'Nacos configuration was not loaded. Run deploy/docker/bootstrap-resources.ps1 first.'
    }
    if ($response.Headers['X-Request-Id'] -ne $requestId) {
        throw 'Gateway did not preserve the valid request ID.'
    }

    $identityBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/identity"
    $password = 'SmokeTestPass123'
    $registration = Invoke-JsonPost -Uri "$identityBaseUrl/auth/register" -Body @{
        email = $smokeEmail
        password = $password
        displayName = 'Identity Smoke Test'
    }
    if ($registration.code -ne 'OK' -or $registration.data.roles[0] -ne 'CUSTOMER') {
        throw 'Identity registration did not create an active customer account.'
    }

    $login = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $smokeEmail
        password = $password
    }
    $accessToken = $login.data.accessToken
    $firstRefreshToken = $login.data.refreshToken
    if (-not $accessToken -or -not $firstRefreshToken) {
        throw 'Identity login did not issue both token types.'
    }

    $profile = Invoke-RestMethod -Method Get -Uri "$identityBaseUrl/me" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($profile.data.email -ne $smokeEmail -or $profile.data.roles[0] -ne 'CUSTOMER') {
        throw 'The authenticated profile does not match the registered account.'
    }

    $smokeAddress = Invoke-JsonPost -Uri "$identityBaseUrl/addresses" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            recipientName = 'Smoke Customer'
            phone = '+86 13800000000'
            province = 'Zhejiang'
            provinceCode = '330000'
            city = 'Hangzhou'
            cityCode = '330100'
            district = 'Xihu'
            districtCode = '330106'
            detailAddress = $originalAddressDetail
            postalCode = '310000'
            setDefault = $true
        }
    $smokeAddressId = $smokeAddress.data.id
    if (-not $smokeAddressId -or -not $smokeAddress.data.defaultAddress) {
        throw 'Identity address creation did not produce a default delivery address.'
    }

    $catalogBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/catalog"
    $customerCatalogAttempt = Invoke-JsonPostRaw -Uri "$catalogBaseUrl/admin/categories" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{ name = 'Forbidden Category'; slug = 'forbidden-category'; sortOrder = 0 }
    if ([int]$customerCatalogAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to write catalog data.'
    }

    $refresh = Invoke-JsonPost -Uri "$identityBaseUrl/auth/refresh" -Body @{
        refreshToken = $firstRefreshToken
    }
    $secondRefreshToken = $refresh.data.refreshToken
    if (-not $secondRefreshToken -or $secondRefreshToken -eq $firstRefreshToken) {
        throw 'Refresh token rotation did not issue a new token.'
    }
    Assert-JsonPostRejected -Uri "$identityBaseUrl/auth/refresh" `
        -Body @{ refreshToken = $firstRefreshToken } -ExpectedStatus 401

    Invoke-JsonPost -Uri "$identityBaseUrl/auth/logout" -Body @{
        refreshToken = $secondRefreshToken
    } | Out-Null
    Assert-JsonPostRejected -Uri "$identityBaseUrl/auth/refresh" `
        -Body @{ refreshToken = $secondRefreshToken } -ExpectedStatus 401

    $grantAdminSql = @"
INSERT IGNORE INTO user_role (user_id, role_id, created_at)
SELECT user_account.id, identity_role.id, CURRENT_TIMESTAMP(3)
FROM user_account
JOIN identity_role ON identity_role.code = 'ADMIN'
WHERE user_account.email = '$smokeEmail';
"@
    $grantAdminSql | docker exec -i -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" ecom-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to grant the temporary smoke account an administrator role.'
    }

    $adminLogin = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $smokeEmail
        password = $password
    }
    $adminAccessToken = $adminLogin.data.accessToken
    $adminProfile = Invoke-RestMethod -Method Get -Uri "$identityBaseUrl/me" `
        -Headers @{ Authorization = "Bearer $adminAccessToken" } -TimeoutSec 10
    if ($adminProfile.data.roles -notcontains 'ADMIN') {
        throw 'The temporary administrator role was not included in the new access token.'
    }
    $adminHeaders = @{ Authorization = "Bearer $adminAccessToken" }
    $smokeUserId = docker exec -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" ecom-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME -N -B `
        -e "SELECT id FROM user_account WHERE email = '$smokeEmail'"
    if (-not $smokeUserId) {
        throw 'Unable to resolve the temporary smoke user ID.'
    }

    $category = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/categories" -Headers $adminHeaders -Body @{
        name = 'Smoke Category'
        slug = $catalogCategorySlug
        sortOrder = 9999
    }
    $brand = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/brands" -Headers $adminHeaders -Body @{
        name = 'Smoke Brand'
        slug = $catalogBrandSlug
    }
    $product = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products" -Headers $adminHeaders -Body @{
        categoryId = $category.data.id
        brandId = $brand.data.id
        title = $catalogProductTitle
        subtitle = 'Temporary catalog verification data'
        description = 'Created and removed by run-foundation-smoke.ps1.'
        skus = @(
            @{
                skuCode = $catalogSkuCode
                name = 'Default SKU'
                specJson = '{"variant":"default"}'
                salePrice = 129.90
                marketPrice = 159.90
            },
            @{
                skuCode = $tradeSkuCode
                name = 'Trade Competition SKU'
                specJson = '{"variant":"trade"}'
                salePrice = 49.90
                marketPrice = 69.90
            }
        )
    }
    if ($product.data.status -ne 'DRAFT') {
        throw 'A newly created catalog product was not a draft.'
    }
    $productId = $product.data.id

    $draftRead = Invoke-WebRequest -Uri "$catalogBaseUrl/products/$productId" -SkipHttpErrorCheck -TimeoutSec 10
    if ([int]$draftRead.StatusCode -ne 404) {
        throw 'A draft catalog product was visible through the public API.'
    }
    $publishedProduct = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/publish" `
        -Headers $adminHeaders -Body @{ expectedVersion = $product.data.version }
    if ($publishedProduct.data.status -ne 'ACTIVE') {
        throw 'Catalog product publication failed.'
    }

    $pngBytes = [Convert]::FromBase64String(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
    $uploadIntent = Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/media/upload-intents" `
        -Headers $adminHeaders -Body @{ contentType = 'image/png'; sizeBytes = $pngBytes.Length }
    $catalogObjectKey = $uploadIntent.data.objectKey
    $uploadResponse = Invoke-WebRequest -Method Put -Uri $uploadIntent.data.uploadUrl `
        -Body $pngBytes -ContentType 'image/png' -TimeoutSec 10
    if ([int]$uploadResponse.StatusCode -notin @(200, 204)) {
        throw 'The pre-signed MinIO product upload failed.'
    }
    Invoke-JsonPost -Uri "$catalogBaseUrl/admin/products/$productId/media" -Headers $adminHeaders -Body @{
        objectKey = $catalogObjectKey
        sortOrder = 0
    } | Out-Null

    $publicProduct = Invoke-RestMethod -Method Get -Uri "$catalogBaseUrl/products/$productId" -TimeoutSec 10
    $publicDefaultSku = $publicProduct.data.skus | Where-Object skuCode -eq $catalogSkuCode
    if ([decimal]$publicDefaultSku.salePrice -ne [decimal]129.90 -or
        -not $publicProduct.data.media[0].url) {
        throw 'The published product price or signed media URL was not returned correctly.'
    }
    $mediaRead = Invoke-WebRequest -Uri $publicProduct.data.media[0].url -TimeoutSec 10
    if ([int]$mediaRead.StatusCode -ne 200) {
        throw 'The pre-signed MinIO product download failed.'
    }

    $inventoryBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/inventory"
    $inventoryInternalBaseUrl = "http://127.0.0.1:$inventoryPort/api/v1/inventory/internal"
    $internalHeaders = @{
        'X-Internal-Service' = 'trade-service'
        'X-Internal-Token' = $env:INTERNAL_SERVICE_TOKEN
    }
    $customerInventoryAttempt = Invoke-JsonPostRaw -Uri "$inventoryBaseUrl/internal/reservations" `
        -Headers @{ Authorization = "Bearer $accessToken" } `
        -Body @{
            reservationNo = "$inventoryReservationPrefix-FORBIDDEN"
            orderNo = "$inventoryReservationPrefix-FORBIDDEN"
            warehouseId = 1
            items = @(@{ skuId = 1; quantity = 1 })
        }
    if ([int]$customerInventoryAttempt.StatusCode -ne 404) {
        throw 'The public gateway exposed an internal inventory command.'
    }

    $warehouse = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/warehouses" -Headers $adminHeaders -Body @{
        code = $inventoryWarehouseCode
        name = 'Smoke Test Warehouse'
    }
    $inventoryWarehouseId = $warehouse.data.id
    $inventorySkuId = ($product.data.skus | Where-Object skuCode -eq $catalogSkuCode).id
    $tradeSkuId = ($product.data.skus | Where-Object skuCode -eq $tradeSkuCode).id
    if (-not $inventorySkuId -or -not $tradeSkuId) {
        throw 'Catalog product creation did not return both smoke SKUs.'
    }
    $initialStock = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = $inventoryMovementNo
            warehouseId = $inventoryWarehouseId
            skuId = $inventorySkuId
            quantityDelta = 20
            reason = 'Real middleware concurrency smoke stock'
        }
    if ($initialStock.data.available -ne 20) {
        throw 'Inventory adjustment did not create 20 available units.'
    }

    $warehouseIdForThreads = $inventoryWarehouseId
    $skuIdForThreads = $inventorySkuId
    $reservationResults = 0..99 | ForEach-Object -Parallel {
        $index = $_
        $body = @{
            reservationNo = "$using:inventoryReservationPrefix-$index"
            orderNo = "SMOKE-ORDER-$index"
            warehouseId = $using:warehouseIdForThreads
            items = @(@{ skuId = $using:skuIdForThreads; quantity = 1 })
        } | ConvertTo-Json -Compress -Depth 10
        $response = Invoke-RestMethod -Method Post `
            -Uri "$using:inventoryInternalBaseUrl/reservations" `
            -Headers $using:internalHeaders `
            -ContentType 'application/json' -Body $body -TimeoutSec 30
        [pscustomobject]@{
            ReservationNo = $response.data.reservationNo
            Status = $response.data.status
        }
    } -ThrottleLimit 25

    $reservedNumbers = @($reservationResults | Where-Object Status -eq 'RESERVED' |
        Select-Object -ExpandProperty ReservationNo)
    $rejectedCount = @($reservationResults | Where-Object Status -eq 'REJECTED').Count
    if ($reservedNumbers.Count -ne 20 -or $rejectedCount -ne 80) {
        throw "Real MySQL inventory competition was incorrect: reserved=$($reservedNumbers.Count), rejected=$rejectedCount."
    }

    $idempotentRetry = Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations" `
        -Headers $internalHeaders -Body @{
            reservationNo = $reservedNumbers[0]
            orderNo = "SMOKE-ORDER-$($reservedNumbers[0].Split('-')[-1])"
            warehouseId = $inventoryWarehouseId
            items = @(@{ skuId = $inventorySkuId; quantity = 1 })
        }
    if ($idempotentRetry.data.status -ne 'RESERVED') {
        throw 'An idempotent reservation retry did not return the original result.'
    }

    Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations/$($reservedNumbers[0])/confirm" `
        -Headers $internalHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$inventoryInternalBaseUrl/reservations/$($reservedNumbers[1])/release" `
        -Headers $internalHeaders -Body @{} | Out-Null
    $stockAfterTransitions = Invoke-RestMethod -Method Get `
        -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$inventorySkuId" `
        -Headers $adminHeaders -TimeoutSec 10
    if ($stockAfterTransitions.data.onHand -ne 19 -or
        $stockAfterTransitions.data.reserved -ne 18 -or
        $stockAfterTransitions.data.available -ne 1) {
        throw 'Inventory confirm/release transitions broke the stock equation.'
    }

    $tradeStock = Invoke-JsonPost -Uri "$inventoryBaseUrl/admin/stocks/adjustments" `
        -Headers $adminHeaders -Body @{
            movementNo = $tradeMovementNo
            warehouseId = $inventoryWarehouseId
            skuId = $tradeSkuId
            quantityDelta = 6
            reason = 'End-to-end trade competition stock'
        }
    if ($tradeStock.data.available -ne 6) {
        throw 'Trade smoke stock did not create six available units.'
    }

    $tradeBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/trade"
    $marketingBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/marketing"
    $validFrom = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToString('o')
    $validUntil = [DateTimeOffset]::UtcNow.AddHours(2).ToString('o')
    $marketingRules = @(
        @{ Code = "$marketingRulePrefix-COUPON"; Type = 'COUPON'; Threshold = 40.00; Discount = 5.00; Order = 10; Regions = @(@{ level = 'DISTRICT'; regionCode = '330106' }) },
        @{ Code = "$marketingRulePrefix-RED"; Type = 'RED_PACKET'; Threshold = 0.00; Discount = 2.00; Order = 20; Regions = @(@{ level = 'PROVINCE'; regionCode = '330000' }) },
        @{ Code = "$marketingRulePrefix-SUBSIDY"; Type = 'SUBSIDY'; Threshold = 0.00; Discount = 1.00; Order = 30; Regions = @() }
    )
    foreach ($rule in $marketingRules) {
        Invoke-JsonPost -Uri "$marketingBaseUrl/admin/rules" -Headers $adminHeaders -Body @{
            ruleCode = $rule.Code
            name = $rule.Code
            benefitType = $rule.Type
            thresholdAmount = $rule.Threshold
            discountAmount = $rule.Discount
            stackOrder = $rule.Order
            validFrom = $validFrom
            validUntil = $validUntil
            regions = $rule.Regions
        } | Out-Null
        $paidBenefit = Invoke-JsonPost -Uri "$marketingBaseUrl/admin/benefits" -Headers $adminHeaders -Body @{
            userId = [long]$smokeUserId
            ruleCode = $rule.Code
            grantKey = "$marketingRulePrefix-PAID-$($rule.Type)"
        }
        $cancelBenefit = Invoke-JsonPost -Uri "$marketingBaseUrl/admin/benefits" -Headers $adminHeaders -Body @{
            userId = [long]$smokeUserId
            ruleCode = $rule.Code
            grantKey = "$marketingRulePrefix-CANCEL-$($rule.Type)"
        }
        $marketingBenefitNos += $paidBenefit.data.benefitNo
        $marketingCancelBenefitNos += $cancelBenefit.data.benefitNo
    }

    $marketingCancelOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-marketing-cancel-$inventoryReservationPrefix"
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        benefitNos = $marketingCancelBenefitNos
    }
    $marketingCancelOrderNo = $marketingCancelOrder.data.orderNo
    $tradeOrderNumbers += $marketingCancelOrderNo
    $tradeReservationNumbers += ($marketingCancelOrderNo -replace '^ORD', 'RSV')
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    if ($marketingCancelOrder.data.status -ne 'PENDING_PAYMENT' -or
        [decimal]$marketingCancelOrder.data.priceSnapshot.discountAmount -ne 8.00) {
        throw 'Marketing cancellation order did not lock the stacked benefits.'
    }
    $marketingCanceled = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$marketingCancelOrderNo/cancel" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ($marketingCanceled.data.status -ne 'CANCELED') {
        throw 'Marketing cancellation order did not cancel.'
    }
    $marketingReleaseDeadline = (Get-Date).AddSeconds(45)
    do {
        $customerBenefits = Invoke-RestMethod -Method Get -Uri "$marketingBaseUrl/benefits" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $releasedBenefits = @($customerBenefits.data | Where-Object {
                $_.benefitNo -in $marketingCancelBenefitNos -and $_.status -eq 'AVAILABLE'
            }).Count
        if ($releasedBenefits -eq 3) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $marketingReleaseDeadline)
    if ($releasedBenefits -ne 3) {
        throw 'OrderCanceled did not release all marketing benefits.'
    }

    $marketingPaidOrder = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-marketing-paid-$inventoryReservationPrefix"
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
        benefitNos = $marketingBenefitNos
    }
    $marketingOrderNo = $marketingPaidOrder.data.orderNo
    $tradeOrderNumbers += $marketingOrderNo
    $tradeReservationNumbers += ($marketingOrderNo -replace '^ORD', 'RSV')
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    if ($marketingPaidOrder.data.status -ne 'PENDING_PAYMENT' -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.originalAmount -ne 49.90 -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.discountAmount -ne 8.00 -or
        [decimal]$marketingPaidOrder.data.priceSnapshot.payableAmount -ne 41.90 -or
        @($marketingPaidOrder.data.priceSnapshot.allocations).Count -ne 3) {
        throw 'Marketing price and allocation snapshots were not persisted correctly.'
    }

    $tradeProductIdForThreads = $productId
    $tradeSkuIdForThreads = $tradeSkuId
    $tradeResults = 0..29 | ForEach-Object -Parallel {
        $index = $_
        $idempotencyKey = "smoke-trade-$index-$using:inventoryReservationPrefix"
        $body = @{
            addressId = $using:smokeAddressId
            items = @(@{
                productId = $using:tradeProductIdForThreads
                skuId = $using:tradeSkuIdForThreads
                quantity = 1
            })
        } | ConvertTo-Json -Compress -Depth 10
        $response = Invoke-RestMethod -Method Post -Uri "$using:tradeBaseUrl/orders" `
            -Headers @{
                Authorization = "Bearer $using:accessToken"
                'Idempotency-Key' = $idempotencyKey
            } -ContentType 'application/json' -Body $body -TimeoutSec 30
        [pscustomobject]@{
            Key = $idempotencyKey
            OrderNo = $response.data.orderNo
            Status = $response.data.status
        }
    } -ThrottleLimit 15

    $payableOrders = @($tradeResults | Where-Object Status -eq 'PENDING_PAYMENT')
    $closedTradeCount = @($tradeResults | Where-Object Status -eq 'CLOSED').Count
    $tradeOrderNumbers += @($tradeResults | Select-Object -ExpandProperty OrderNo)
    $tradeReservationNumbers = @($tradeOrderNumbers | ForEach-Object { $_ -replace '^ORD', 'RSV' })
    $tradeOrderSqlList = ($tradeOrderNumbers | ForEach-Object { "'$_'" }) -join ','
    $tradeReservationSqlList = ($tradeReservationNumbers | ForEach-Object { "'$_'" }) -join ','
    if ($payableOrders.Count -ne 5 -or $closedTradeCount -ne 25) {
        throw "Trade competition was incorrect: payable=$($payableOrders.Count), closed=$closedTradeCount."
    }

    $retryOrder = $payableOrders[0]
    $tradeRetry = Invoke-JsonPost -Uri "$tradeBaseUrl/orders" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = $retryOrder.Key
    } -Body @{
        addressId = $smokeAddressId
        items = @(@{ productId = $productId; skuId = $tradeSkuId; quantity = 1 })
    }
    if ($tradeRetry.data.orderNo -ne $retryOrder.OrderNo -or $tradeRetry.data.status -ne 'PENDING_PAYMENT') {
        throw 'Trade order idempotency did not return the original order.'
    }

    $canceledTrade = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$($retryOrder.OrderNo)/cancel" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ($canceledTrade.data.status -ne 'CANCELED') {
        throw 'Trade cancellation did not complete its inventory release.'
    }
    $tradeStockAfterCancel = Invoke-RestMethod -Method Get `
        -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
        -Headers $adminHeaders -TimeoutSec 10
    if ($tradeStockAfterCancel.data.onHand -ne 6 -or
        $tradeStockAfterCancel.data.reserved -ne 5 -or
        $tradeStockAfterCancel.data.available -ne 1) {
        throw 'Trade cancellation broke the inventory equation.'
    }

    $paymentOrder = [pscustomobject]@{ OrderNo = $marketingOrderNo }
    $paymentBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/payment"
    $createdPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/payments" -Headers @{
        Authorization = "Bearer $accessToken"
        'Idempotency-Key' = "smoke-payment-$inventoryReservationPrefix"
    } -Body @{
        orderNo = $paymentOrder.OrderNo
        channel = 'MOCK'
    }
    $paymentNo = $createdPayment.data.paymentNo
    if (-not $paymentNo -or $createdPayment.data.status -ne 'PROCESSING') {
        throw 'Payment creation did not produce a processing payment order.'
    }

    $callbackEventId = "SMOKE-PAY-EVT-$([Guid]::NewGuid().ToString('N'))"
    $callbackTransactionNo = "SMOKE-PAY-TXN-$([Guid]::NewGuid().ToString('N'))"
    $callbackTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $callbackAmount = ([decimal]$createdPayment.data.amount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $callbackCanonical = "$paymentNo|$callbackEventId|$callbackTransactionNo|SUCCESS|$callbackAmount|$callbackTimestamp"
    $callbackSignature = Get-HmacSha256Hex -Value $callbackCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
    $callbackBody = @{
        paymentNo = $paymentNo
        externalEventId = $callbackEventId
        externalTransactionNo = $callbackTransactionNo
        status = 'SUCCESS'
        amount = [decimal]$createdPayment.data.amount
        timestamp = $callbackTimestamp
        signature = $callbackSignature
    }
    $paidPayment = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock" -Body $callbackBody
    $duplicateCallback = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock" -Body $callbackBody
    if ($paidPayment.data.status -ne 'SUCCESS' -or $duplicateCallback.data.status -ne 'SUCCESS') {
        throw 'Signed payment callback or its idempotent retry did not succeed.'
    }

    $paymentChainDeadline = (Get-Date).AddSeconds(75)
    $fulfillmentBaseUrl = "http://127.0.0.1:$gatewayPort/api/v1/fulfillment"
    do {
        $paidOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $tradeStockAfterPayment = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders -TimeoutSec 10
        $fulfillmentRead = Invoke-WebRequest -Method Get `
            -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -SkipHttpErrorCheck -TimeoutSec 10
        $fulfillmentPayload = if ([int]$fulfillmentRead.StatusCode -eq 200) {
            $fulfillmentRead.Content | ConvertFrom-Json
        } else {
            $null
        }
        $paidMarketingBenefits = Invoke-RestMethod -Method Get -Uri "$marketingBaseUrl/benefits" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $redeemedMarketingBenefits = @($paidMarketingBenefits.data | Where-Object {
                $_.benefitNo -in $marketingBenefitNos -and $_.status -eq 'REDEEMED' -and
                $_.redeemedOrderNo -eq $marketingOrderNo
            }).Count
        if ($paidOrder.data.status -eq 'FULFILLING' -and
            $null -ne $fulfillmentPayload -and $fulfillmentPayload.data.status -eq 'CREATED' -and
            $tradeStockAfterPayment.data.onHand -eq 5 -and
            $tradeStockAfterPayment.data.reserved -eq 4 -and
            $tradeStockAfterPayment.data.available -eq 1 -and
            $redeemedMarketingBenefits -eq 3) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $paymentChainDeadline)
    if ($paidOrder.data.status -ne 'FULFILLING' -or
        $null -eq $fulfillmentPayload -or $fulfillmentPayload.data.status -ne 'CREATED' -or
        $tradeStockAfterPayment.data.onHand -ne 5 -or
        $tradeStockAfterPayment.data.reserved -ne 4 -or
        $tradeStockAfterPayment.data.available -ne 1 -or
        $redeemedMarketingBenefits -ne 3) {
        throw 'PaymentSucceeded -> OrderPaid -> inventory confirmation and fulfillment creation did not converge.'
    }
    $fulfillmentNo = $fulfillmentPayload.data.fulfillmentNo

    if ($paidOrder.data.deliveryAddress.detailAddress -ne $originalAddressDetail -or
        $fulfillmentPayload.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Trade or fulfillment did not preserve the original delivery address snapshot.'
    }
    $updatedAddress = Invoke-RestMethod -Method Put -Uri "$identityBaseUrl/addresses/$smokeAddressId" `
        -Headers @{ Authorization = "Bearer $accessToken" } -ContentType 'application/json' `
        -Body (@{
            recipientName = 'Smoke Customer'
            phone = '+86 13800000000'
            province = 'Zhejiang'
            provinceCode = '330000'
            city = 'Hangzhou'
            cityCode = '330100'
            district = 'Xihu'
            districtCode = '330106'
            detailAddress = $updatedAddressDetail
            postalCode = '310000'
            setDefault = $false
        } | ConvertTo-Json -Compress) -TimeoutSec 10
    if ($updatedAddress.data.detailAddress -ne $updatedAddressDetail) {
        throw 'Identity address update did not persist.'
    }
    $orderAfterAddressUpdate = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    $fulfillmentAfterAddressUpdate = Invoke-RestMethod -Method Get `
        -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterAddressUpdate.data.deliveryAddress.detailAddress -ne $originalAddressDetail -or
        $fulfillmentAfterAddressUpdate.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Editing the source address changed an immutable order or fulfillment snapshot.'
    }
    Invoke-RestMethod -Method Delete -Uri "$identityBaseUrl/addresses/$smokeAddressId" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10 | Out-Null
    $orderAfterAddressDelete = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterAddressDelete.data.deliveryAddress.detailAddress -ne $originalAddressDetail) {
        throw 'Deleting the source address changed the immutable trade snapshot.'
    }

    $customerFulfillmentAttempt = Invoke-JsonPostRaw `
        -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/picking" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{}
    if ([int]$customerFulfillmentAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to operate a fulfillment order.'
    }

    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/picking" `
        -Headers $adminHeaders -Body @{} | Out-Null
    Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/packed" `
        -Headers $adminHeaders -Body @{} | Out-Null
    $shipped = Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/ship" `
        -Headers $adminHeaders -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "SMOKE-TRACK-$inventoryReservationPrefix"
        }
    if ($shipped.data.status -ne 'SHIPPED') {
        throw 'Fulfillment shipping command did not reach SHIPPED.'
    }

    $shippingDeadline = (Get-Date).AddSeconds(45)
    do {
        $shippedOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($shippedOrder.data.status -eq 'SHIPPED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $shippingDeadline)
    if ($shippedOrder.data.status -ne 'SHIPPED') {
        throw 'ShipmentDispatched did not advance the trade order to SHIPPED.'
    }

    $traceTime = [DateTimeOffset]::UtcNow.ToString('o')
    foreach ($trace in @(
        @{ externalEventId = "SMOKE-TRACE-1-$inventoryReservationPrefix"; nodeType = 'TRANSIT';
           description = 'Arrived at sorting center'; locationName = 'Nanjing' },
        @{ externalEventId = "SMOKE-TRACE-2-$inventoryReservationPrefix"; nodeType = 'DELIVERING';
           description = 'Courier is delivering'; locationName = 'Shanghai' },
        @{ externalEventId = "SMOKE-TRACE-3-$inventoryReservationPrefix"; nodeType = 'SIGNED';
           description = 'Recipient signed'; locationName = 'Shanghai' }
    )) {
        Invoke-JsonPost -Uri "$fulfillmentBaseUrl/admin/orders/$fulfillmentNo/traces" `
            -Headers $adminHeaders -Body @{
                externalEventId = $trace.externalEventId
                nodeType = $trace.nodeType
                description = $trace.description
                locationName = $trace.locationName
                longitude = 118.796877
                latitude = 32.060255
                occurredAt = $traceTime
            } | Out-Null
    }

    $completionDeadline = (Get-Date).AddSeconds(45)
    do {
        $completedOrder = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($completedOrder.data.status -eq 'COMPLETED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $completionDeadline)
    if ($completedOrder.data.status -ne 'COMPLETED') {
        throw 'ShipmentSigned did not advance the trade order to COMPLETED.'
    }

    $finalFulfillment = Invoke-RestMethod -Method Get `
        -Uri "$fulfillmentBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($finalFulfillment.data.status -ne 'SIGNED' -or $finalFulfillment.data.traces.Count -ne 3) {
        throw 'Fulfillment tracking history was not append-only or did not reach SIGNED.'
    }

    $afterSale = Invoke-JsonPost -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)/after-sales" `
        -Headers @{
            Authorization = "Bearer $accessToken"
            'Idempotency-Key' = "smoke-after-sale-$inventoryReservationPrefix"
        } -Body @{
            reason = 'Real middleware whole-order return and refund smoke'
        }
    $afterSaleNo = $afterSale.data.afterSaleNo
    if (-not $afterSaleNo -or $afterSale.data.status -ne 'APPLIED' -or
        [decimal]$afterSale.data.refundAmount -ne [decimal]$createdPayment.data.amount -or
        @($afterSale.data.items).Count -ne 1 -or
        [decimal]$afterSale.data.items[0].refundableAmount -ne [decimal]$createdPayment.data.amount) {
        throw 'Whole-order after-sale did not preserve the original payable allocation snapshot.'
    }

    $customerReviewAttempt = Invoke-JsonPostRaw `
        -Uri "$tradeBaseUrl/admin/after-sales/$afterSaleNo/review" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            approved = $true
            reason = 'Customer must not review after-sales'
        }
    if ([int]$customerReviewAttempt.StatusCode -ne 403) {
        throw 'A customer token was allowed to review an after-sale request.'
    }

    $approvedAfterSale = Invoke-JsonPost -Uri "$tradeBaseUrl/admin/after-sales/$afterSaleNo/review" `
        -Headers $adminHeaders -Body @{
            approved = $true
            reason = 'Approved by real middleware smoke'
        }
    if ($approvedAfterSale.data.status -ne 'WAIT_RETURN') {
        throw 'After-sale approval did not reach WAIT_RETURN.'
    }

    $returnReceiptDeadline = (Get-Date).AddSeconds(60)
    $returnReceipt = $null
    do {
        $returnList = Invoke-RestMethod -Method Get -Uri "$fulfillmentBaseUrl/returns" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $returnReceipt = $returnList.data | Where-Object { $_.afterSaleNo -eq $afterSaleNo } |
            Select-Object -First 1
        if ($null -ne $returnReceipt -and $returnReceipt.status -eq 'WAIT_SHIPMENT') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $returnReceiptDeadline)
    if ($null -eq $returnReceipt -or $returnReceipt.status -ne 'WAIT_SHIPMENT') {
        throw 'AfterSaleApproved did not create a return receipt.'
    }
    $returnReceiptNo = $returnReceipt.returnReceiptNo

    $returningReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/returns/$returnReceiptNo/shipment" `
        -Headers @{ Authorization = "Bearer $accessToken" } -Body @{
            carrier = 'MOCK_EXPRESS'
            trackingNo = "SMOKE-RETURN-TRACK-$inventoryReservationPrefix"
        }
    if ($returningReceipt.data.status -ne 'RETURNING') {
        throw 'Customer return shipment did not reach RETURNING.'
    }
    $receivedReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/receive" `
        -Headers $adminHeaders -Body @{}
    if ($receivedReceipt.data.status -ne 'RECEIVED') {
        throw 'Warehouse return receipt did not reach RECEIVED.'
    }
    $inspectedReceipt = Invoke-JsonPost `
        -Uri "$fulfillmentBaseUrl/admin/returns/$returnReceiptNo/inspect" `
        -Headers $adminHeaders -Body @{
            remark = 'All returned goods accepted by real middleware smoke'
        }
    if ($inspectedReceipt.data.status -ne 'INSPECTED') {
        throw 'Warehouse inspection did not reach INSPECTED.'
    }

    $refundCreationDeadline = (Get-Date).AddSeconds(75)
    do {
        $afterSaleProgress = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        $returnStock = Invoke-RestMethod -Method Get `
            -Uri "$inventoryBaseUrl/admin/warehouses/$inventoryWarehouseId/stocks/$tradeSkuId" `
            -Headers $adminHeaders -TimeoutSec 10
        $refundNoResult = @"
SELECT refund_no FROM refund_order WHERE after_sale_no = '$afterSaleNo' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
            mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
        $refundNo = $refundNoResult | Select-Object -Last 1
        if ($afterSaleProgress.data.status -eq 'REFUNDING' -and $refundNo -and
            $returnStock.data.onHand -eq 6 -and $returnStock.data.reserved -eq 4 -and
            $returnStock.data.available -eq 2) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $refundCreationDeadline)
    if ($afterSaleProgress.data.status -ne 'REFUNDING' -or -not $refundNo -or
        $returnStock.data.onHand -ne 6 -or $returnStock.data.reserved -ne 4 -or
        $returnStock.data.available -ne 2) {
        throw 'ReturnInspected -> ReturnStocked -> RefundRequested did not converge.'
    }

    $refundEventId = "SMOKE-REFUND-EVT-$([Guid]::NewGuid().ToString('N'))"
    $channelRefundNo = "SMOKE-REFUND-TXN-$([Guid]::NewGuid().ToString('N'))"
    $refundTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $refundAmount = ([decimal]$afterSale.data.refundAmount).ToString(
        '0.############################', [Globalization.CultureInfo]::InvariantCulture)
    $refundCanonical = "$refundNo|$refundEventId|$channelRefundNo|SUCCESS|$refundAmount|$refundTimestamp"
    $refundSignature = Get-HmacSha256Hex -Value $refundCanonical -Secret $env:MOCK_PAYMENT_CALLBACK_SECRET
    $refundCallbackBody = @{
        refundNo = $refundNo
        externalEventId = $refundEventId
        externalRefundNo = $channelRefundNo
        status = 'SUCCESS'
        amount = [decimal]$afterSale.data.refundAmount
        timestamp = $refundTimestamp
        signature = $refundSignature
    }
    $refunded = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $refundCallbackBody
    $duplicateRefund = Invoke-JsonPost -Uri "$paymentBaseUrl/callbacks/mock/refunds" -Body $refundCallbackBody
    if ($refunded.data.status -ne 'SUCCESS' -or $duplicateRefund.data.status -ne 'SUCCESS') {
        throw 'Signed refund callback or its idempotent retry did not succeed.'
    }

    $afterSaleCompletionDeadline = (Get-Date).AddSeconds(60)
    do {
        $completedAfterSale = Invoke-RestMethod -Method Get `
            -Uri "$tradeBaseUrl/after-sales/$afterSaleNo" `
            -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
        if ($completedAfterSale.data.status -eq 'COMPLETED') {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $afterSaleCompletionDeadline)
    if ($completedAfterSale.data.status -ne 'COMPLETED' -or
        $completedAfterSale.data.refundNo -ne $refundNo) {
        throw 'RefundSucceeded did not complete the trade after-sale.'
    }
    $orderAfterRefund = Invoke-RestMethod -Method Get `
        -Uri "$tradeBaseUrl/orders/$($paymentOrder.OrderNo)" `
        -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 10
    if ($orderAfterRefund.data.status -ne 'COMPLETED') {
        throw 'After-sale completion rewrote the original completed order state.'
    }

    $paymentEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$paymentNo' AND event_type = 'PaymentSucceeded'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $paymentEventId = $paymentEventId | Select-Object -Last 1
    $orderPaidEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$($paymentOrder.OrderNo)' AND event_type = 'OrderPaid'
LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME
    $orderPaidEventId = $orderPaidEventId | Select-Object -Last 1
    if (-not $paymentEventId -or -not $orderPaidEventId) {
        throw 'The payment/order event chain was not persisted to the outboxes.'
    }

    $fulfillmentEventIds = @(@"
SELECT id FROM outbox_event
WHERE aggregate_id = '$fulfillmentNo'
  AND event_type IN ('FulfillmentCreated', 'ShipmentDispatched', 'ShipmentSigned')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME)
    $fulfillmentEventIds = @($fulfillmentEventIds | Where-Object { $_ -and $_.Trim() })
    if ($fulfillmentEventIds.Count -ne 3) {
        throw "Expected three fulfillment lifecycle events, found $($fulfillmentEventIds.Count)."
    }
    $fulfillmentEventSqlList = ($fulfillmentEventIds | ForEach-Object { "'$($_.Trim())'" }) -join ','

    $afterSaleEventRows = @(@"
SELECT event_type, id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo'
  AND event_type IN ('AfterSaleApproved', 'RefundRequested')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME)
    foreach ($row in $afterSaleEventRows) {
        $parts = $row -split "`t"
        if ($parts[0] -eq 'AfterSaleApproved') { $afterSaleApprovedEventId = $parts[1] }
        if ($parts[0] -eq 'RefundRequested') { $refundRequestedEventId = $parts[1] }
    }
    $returnEventRows = @(@"
SELECT event_type, id FROM outbox_event
WHERE aggregate_id = '$returnReceiptNo'
  AND event_type IN ('ReturnShipmentSubmitted', 'ReturnReceived', 'ReturnInspected')
ORDER BY created_at;
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME)
    foreach ($row in $returnEventRows) {
        $parts = $row -split "`t"
        if ($parts[0] -eq 'ReturnShipmentSubmitted') { $returnShipmentEventId = $parts[1] }
        if ($parts[0] -eq 'ReturnReceived') { $returnReceivedEventId = $parts[1] }
        if ($parts[0] -eq 'ReturnInspected') { $returnInspectedEventId = $parts[1] }
    }
    $returnStockedEventId = @"
SELECT event_id FROM outbox_event
WHERE aggregate_id = '$afterSaleNo' AND event_type = 'ReturnStocked' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME | Select-Object -Last 1
    $refundResultEventId = @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$refundNo' AND event_type = 'RefundSucceeded' LIMIT 1;
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME | Select-Object -Last 1
    if (-not $afterSaleApprovedEventId -or -not $refundRequestedEventId -or
        -not $returnShipmentEventId -or -not $returnReceivedEventId -or
        -not $returnInspectedEventId -or -not $returnStockedEventId -or -not $refundResultEventId) {
        throw 'The return/refund event chain was not persisted across all four service outboxes.'
    }

    $paymentRowCounts = @"
SELECT
  (SELECT COUNT(*) FROM payment_order WHERE payment_no = '$paymentNo'),
  (SELECT COUNT(*) FROM payment_transaction WHERE payment_id =
      (SELECT id FROM payment_order WHERE payment_no = '$paymentNo')),
  (SELECT COUNT(*) FROM payment_callback_log WHERE payment_no = '$paymentNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $paymentCounts = ($paymentRowCounts | Select-Object -Last 1) -split "`t"
    if ($paymentCounts[0] -ne '1' -or $paymentCounts[1] -ne '1' -or $paymentCounts[2] -ne '1') {
        throw "Payment callback idempotency rows were incorrect: $($paymentCounts -join '/')."
    }

    $refundRowCounts = @"
SELECT
  (SELECT COUNT(*) FROM refund_order WHERE refund_no = '$refundNo' AND status = 'SUCCESS'),
  (SELECT COUNT(*) FROM refund_transaction WHERE refund_id =
      (SELECT id FROM refund_order WHERE refund_no = '$refundNo')),
  (SELECT COUNT(*) FROM refund_callback_log WHERE refund_no = '$refundNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
        mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
    $refundCounts = ($refundRowCounts | Select-Object -Last 1) -split "`t"
    if ($refundCounts[0] -ne '1' -or $refundCounts[1] -ne '1' -or $refundCounts[2] -ne '1') {
        throw "Refund callback idempotency rows were incorrect: $($refundCounts -join '/')."
    }

    for ($attempt = 1; $attempt -lt 5; $attempt++) {
        $failedLogin = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/login" -Body @{
            email = $riskEmail
            password = 'WrongPassword123'
        }
        $failedPayload = $failedLogin.Content | ConvertFrom-Json
        if ([int]$failedLogin.StatusCode -ne 401 -or $failedPayload.code -ne 'INVALID_CREDENTIALS') {
            throw "Unexpected login-risk response at attempt $attempt."
        }
    }
    $lockedLogin = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $riskEmail
        password = 'WrongPassword123'
    }
    $lockedPayload = $lockedLogin.Content | ConvertFrom-Json
    if ([int]$lockedLogin.StatusCode -ne 429 -or $lockedPayload.code -ne 'LOGIN_TEMPORARILY_LOCKED') {
        throw 'Identity login-attempt locking did not trigger on the fifth failure.'
    }
    $identityLockExists = docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" ecom-redis `
        redis-cli EXISTS "ecommerce:local:identity:login:lock:$riskHash"
    if (($identityLockExists | Select-Object -Last 1) -ne '1') {
        throw 'Identity login lock was not stored in Redis.'
    }

    $gatewayLimited = $false
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $registrationProbe = Invoke-JsonPostRaw -Uri "$identityBaseUrl/auth/register" -Body @{
            email = 'invalid'
        }
        $registrationPayload = $registrationProbe.Content | ConvertFrom-Json
        if ([int]$registrationProbe.StatusCode -eq 429 -and $registrationPayload.code -eq 'GATEWAY_RATE_LIMITED') {
            if ($registrationProbe.Headers['X-RateLimit-Policy'] -ne 'registration') {
                throw 'Gateway rate-limit response did not identify its policy.'
            }
            $gatewayLimited = $true
            break
        }
    }
    if (-not $gatewayLimited) {
        throw 'Gateway registration rate limit did not trigger.'
    }

    docker stop ecom-redis | Out-Null
    $redisStoppedBySmoke = $true
    $degradedLogin = Invoke-JsonPost -Uri "$identityBaseUrl/auth/login" -Body @{
        email = $smokeEmail
        password = $password
    }
    if (-not $degradedLogin.data.accessToken) {
        throw 'Login failed instead of using local fallback while Redis was unavailable.'
    }
    docker start ecom-redis | Out-Null
    Wait-ContainerHealthy -Container 'ecom-redis'
    $redisStoppedBySmoke = $false

    $outboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $unpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND (aggregate_id LIKE '$inventoryReservationPrefix%'
       OR aggregate_id IN ('$inventoryWarehouseId`:$inventorySkuId', '$inventoryWarehouseId`:$tradeSkuId')
       OR aggregate_id IN ($tradeReservationSqlList)
       OR aggregate_id = '$afterSaleNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" ecom-mysql `
            mysql -N "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME
        $unpublishedCount = [int]($unpublished | Select-Object -Last 1)
        if ($unpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $outboxDeadline)
    if ($unpublishedCount -ne 0) {
        throw "$unpublishedCount inventory outbox events were not published before the deadline."
    }

    $tradeOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $tradeUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED'
  AND (aggregate_id IN ($tradeOrderSqlList) OR aggregate_id = '$afterSaleNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" ecom-mysql `
            mysql -N "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME
        $tradeUnpublishedCount = [int]($tradeUnpublished | Select-Object -Last 1)
        if ($tradeUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $tradeOutboxDeadline)
    if ($tradeUnpublishedCount -ne 0) {
        throw "$tradeUnpublishedCount trade outbox events were not published before the deadline."
    }

    $fulfillmentOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $fulfillmentUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED' AND aggregate_id IN ('$fulfillmentNo', '$returnReceiptNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" ecom-mysql `
            mysql -N "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME
        $fulfillmentUnpublishedCount = [int]($fulfillmentUnpublished | Select-Object -Last 1)
        if ($fulfillmentUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $fulfillmentOutboxDeadline)
    if ($fulfillmentUnpublishedCount -ne 0) {
        throw "$fulfillmentUnpublishedCount fulfillment outbox events were not published before the deadline."
    }

    $paymentOutboxDeadline = (Get-Date).AddSeconds(45)
    do {
        $paymentUnpublished = @"
SELECT COUNT(*) FROM outbox_event
WHERE status <> 'PUBLISHED' AND aggregate_id IN ('$paymentNo', '$refundNo');
"@ | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
            mysql -N "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME
        $paymentUnpublishedCount = [int]($paymentUnpublished | Select-Object -Last 1)
        if ($paymentUnpublishedCount -eq 0) {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $paymentOutboxDeadline)
    if ($paymentUnpublishedCount -ne 0) {
        throw "$paymentUnpublishedCount payment outbox events were not published before the deadline."
    }

    Write-Output 'Foundation smoke test: PASS'
    Write-Output '  Gateway health: UP'
    Write-Output '  Identity health: UP'
    Write-Output '  Catalog health: UP'
    Write-Output '  Inventory health: UP'
    Write-Output '  Trade health: UP'
    Write-Output '  Payment health: UP'
    Write-Output '  Fulfillment health: UP'
    Write-Output '  Marketing health: UP'
    Write-Output '  Nacos discovery route: PASS'
    Write-Output '  Request ID propagation: PASS'
    Write-Output "  Configuration source: $($payload.data.configurationSource)"
    Write-Output '  MySQL/Flyway identity schema: PASS'
    Write-Output '  MySQL/Flyway catalog schema: PASS'
    Write-Output '  MySQL/Flyway inventory schema: PASS'
    Write-Output '  MySQL/Flyway trade schema: PASS'
    Write-Output '  MySQL/Flyway payment schema: PASS'
    Write-Output '  MySQL/Flyway fulfillment schema: PASS'
    Write-Output '  MySQL/Flyway marketing schema: PASS'
    Write-Output '  Register/login/JWT profile: PASS'
    Write-Output '  Delivery address CRUD/internal ownership: PASS'
    Write-Output '  Refresh rotation/logout revocation: PASS'
    Write-Output '  Catalog customer/admin RBAC: PASS'
    Write-Output '  Category/brand/SPU/SKU publication: PASS'
    Write-Output '  MinIO pre-signed product media: PASS'
    Write-Output '  Inventory customer/internal RBAC: PASS'
    Write-Output '  Real MySQL stock competition (20/100): PASS'
    Write-Output '  Reservation idempotency/confirm/release: PASS'
    Write-Output '  RocketMQ inventory outbox publication: PASS'
    Write-Output '  Trade order competition (5/30): PASS'
    Write-Output '  Trade snapshot/idempotency/cancellation: PASS'
    Write-Output '  Coupon/red packet/subsidy stacking and allocation: PASS'
    Write-Output '  Marketing cancel release/payment redemption: PASS'
    Write-Output '  Address edit/delete -> immutable trade/fulfillment snapshot: PASS'
    Write-Output '  RocketMQ trade outbox publication: PASS'
    Write-Output '  Signed payment callback/idempotency: PASS'
    Write-Output '  PaymentSucceeded -> OrderPaid -> inventory confirm/fulfillment create: PASS'
    Write-Output '  Picking -> packed -> shipped -> logistics -> signed: PASS'
    Write-Output '  Fulfillment events -> trade COMPLETED: PASS'
    Write-Output '  Whole-order after-sale price allocation snapshot: PASS'
    Write-Output '  After-sale approval -> return receipt -> warehouse inspection: PASS'
    Write-Output '  Original confirmed reservation validation -> idempotent return stock: PASS'
    Write-Output '  RefundRequested -> signed refund callback -> after-sale COMPLETED: PASS'
    Write-Output '  Redis email failure lock: PASS'
    Write-Output '  Gateway authentication rate limit: PASS'
    Write-Output '  Redis outage local fallback: PASS'
}
catch {
    Show-LogTail -Path $identityOut
    Show-LogTail -Path $identityErr
    Show-LogTail -Path $catalogOut
    Show-LogTail -Path $catalogErr
    Show-LogTail -Path $inventoryOut
    Show-LogTail -Path $inventoryErr
    Show-LogTail -Path $tradeOut
    Show-LogTail -Path $tradeErr
    Show-LogTail -Path $paymentOut
    Show-LogTail -Path $paymentErr
    Show-LogTail -Path $fulfillmentOut
    Show-LogTail -Path $fulfillmentErr
    Show-LogTail -Path $marketingOut
    Show-LogTail -Path $marketingErr
    Show-LogTail -Path $gatewayOut
    Show-LogTail -Path $gatewayErr
    throw
}
finally {
    Stop-FoundationServices -Ports @(
        $gatewayPort,
        $identityPort,
        $catalogPort,
        $inventoryPort,
        $tradePort,
        $paymentPort,
        $fulfillmentPort,
        $marketingPort
    )

    if ($redisStoppedBySmoke) {
        docker start ecom-redis | Out-Null
        Wait-ContainerHealthy -Container 'ecom-redis'
    }
    Remove-RedisKeys -Keys $redisKeys

    if ($catalogObjectKey) {
        docker exec -e "SMOKE_OBJECT_KEY=$catalogObjectKey" ecom-minio sh -c `
            'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc rm --force "local/product-media/$SMOKE_OBJECT_KEY" >/dev/null' 2>$null
    }

    $catalogCleanupSql = @"
DELETE FROM product_media WHERE object_key = '$catalogObjectKey';
DELETE FROM product_sku WHERE spu_id IN (SELECT id FROM product_spu WHERE title = '$catalogProductTitle');
DELETE FROM product_spu WHERE title = '$catalogProductTitle';
DELETE FROM catalog_brand WHERE slug = '$catalogBrandSlug';
DELETE FROM catalog_category WHERE slug = '$catalogCategorySlug';
"@
    $catalogCleanupSql | docker exec -i -e "MYSQL_PWD=$env:CATALOG_DB_PASSWORD" ecom-mysql `
        mysql "-u$env:CATALOG_DB_USER" $env:CATALOG_DB_NAME 2>$null

    if ($inventoryWarehouseId) {
        $inventoryAggregateId = "$inventoryWarehouseId`:$inventorySkuId"
        $tradeInventoryAggregateId = "$inventoryWarehouseId`:$tradeSkuId"
        $reservationFilter = if ($tradeReservationNumbers.Count -gt 0) {
            " OR aggregate_id IN ($tradeReservationSqlList)"
        } else {
            ''
        }
        $inventoryCleanupSql = @"
DELETE FROM consumed_event WHERE event_id IN ('$orderPaidEventId', '$returnInspectedEventId');
DELETE FROM outbox_event
WHERE aggregate_id LIKE '$inventoryReservationPrefix%'
   OR aggregate_id IN ('$inventoryAggregateId', '$tradeInventoryAggregateId', '$afterSaleNo')$reservationFilter;
DELETE FROM stock_movement WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_return WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_reservation_item
WHERE reservation_id IN (SELECT id FROM inventory_reservation WHERE warehouse_id = $inventoryWarehouseId);
DELETE FROM inventory_reservation WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM stock_adjustment WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM inventory_balance WHERE warehouse_id = $inventoryWarehouseId;
DELETE FROM warehouse WHERE id = $inventoryWarehouseId;
"@
        $inventoryCleanupSql | docker exec -i -e "MYSQL_PWD=$env:INVENTORY_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:INVENTORY_DB_USER" $env:INVENTORY_DB_NAME 2>$null
    }

    if ($fulfillmentNo) {
        $fulfillmentCleanupSql = @"
DELETE FROM consumed_event WHERE event_id IN ('$orderPaidEventId', '$afterSaleApprovedEventId');
DELETE FROM outbox_event WHERE aggregate_id IN ('$fulfillmentNo', '$returnReceiptNo');
DELETE FROM return_status_history
WHERE return_receipt_id IN (SELECT id FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo');
DELETE FROM return_item
WHERE return_receipt_id IN (SELECT id FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo');
DELETE FROM return_receipt WHERE return_receipt_no = '$returnReceiptNo';
DELETE FROM logistics_trace
WHERE fulfillment_id IN (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_status_history
WHERE fulfillment_id IN (SELECT id FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo');
DELETE FROM fulfillment_order WHERE fulfillment_no = '$fulfillmentNo';
"@
        $fulfillmentCleanupSql | docker exec -i -e "MYSQL_PWD=$env:FULFILLMENT_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:FULFILLMENT_DB_USER" $env:FULFILLMENT_DB_NAME 2>$null
    }

    if ($tradeOrderNumbers.Count -gt 0) {
        $marketingLifecycleEventIds = @(docker exec -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME -N -B `
            -e "SELECT id FROM outbox_event WHERE aggregate_id IN ($tradeOrderSqlList) AND event_type IN ('OrderPaid','OrderCanceled','OrderClosed')" 2>$null) |
            Where-Object { $_ -match '^[0-9a-fA-F-]{36}$' }
        $marketingLifecycleEventSqlList = ($marketingLifecycleEventIds | ForEach-Object { "'$_'" }) -join ','
        $marketingConsumedDelete = if ($marketingLifecycleEventSqlList) {
            "DELETE FROM consumed_event WHERE event_id IN ($marketingLifecycleEventSqlList);"
        } else {
            ''
        }
        $allMarketingBenefitNos = @($marketingBenefitNos) + @($marketingCancelBenefitNos)
        $marketingBenefitSqlList = ($allMarketingBenefitNos | Where-Object { $_ } |
            ForEach-Object { "'$_'" }) -join ','
        $marketingBenefitDelete = if ($marketingBenefitSqlList) {
            "DELETE FROM user_benefit WHERE benefit_no IN ($marketingBenefitSqlList);"
        } else {
            ''
        }
        $marketingCleanupSql = @"
$marketingConsumedDelete
DELETE FROM pricing_lock_allocation
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM pricing_lock_benefit
WHERE lock_id IN (SELECT id FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM pricing_lock WHERE order_no IN ($tradeOrderSqlList);
$marketingBenefitDelete
DELETE FROM marketing_rule_region
WHERE rule_id IN (SELECT id FROM marketing_rule WHERE rule_code LIKE '$marketingRulePrefix%');
DELETE FROM marketing_rule WHERE rule_code LIKE '$marketingRulePrefix%';
"@
        $marketingCleanupSql | docker exec -i -e "MYSQL_PWD=$env:MARKETING_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:MARKETING_DB_USER" $env:MARKETING_DB_NAME 2>$null
    }

    if ($tradeOrderNumbers.Count -gt 0) {
        $fulfillmentConsumedFilter = if ($fulfillmentEventSqlList) {
            " OR event_id IN ($fulfillmentEventSqlList)"
        } else {
            ''
        }
        $tradeCleanupSql = @"
DELETE FROM consumed_event
WHERE event_id = '$paymentEventId'$fulfillmentConsumedFilter
   OR event_id IN ('$returnShipmentEventId', '$returnReceivedEventId',
                   '$returnStockedEventId', '$refundResultEventId');
DELETE FROM outbox_event
WHERE aggregate_id IN ($tradeOrderSqlList) OR aggregate_id = '$afterSaleNo';
DELETE FROM after_sale_history
WHERE after_sale_id IN (SELECT id FROM after_sale_order WHERE after_sale_no = '$afterSaleNo');
DELETE FROM after_sale_item
WHERE after_sale_id IN (SELECT id FROM after_sale_order WHERE after_sale_no = '$afterSaleNo');
DELETE FROM after_sale_order WHERE after_sale_no = '$afterSaleNo';
DELETE FROM order_status_history
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_address_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_discount_allocation
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_price_snapshot
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_benefit_selection
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM order_item
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no IN ($tradeOrderSqlList));
DELETE FROM trade_order WHERE order_no IN ($tradeOrderSqlList);
"@
        $tradeCleanupSql | docker exec -i -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:TRADE_DB_USER" $env:TRADE_DB_NAME 2>$null
    }

    if ($paymentNo) {
        $paymentCleanupSql = @"
DELETE FROM consumed_event WHERE event_id = '$refundRequestedEventId';
DELETE FROM outbox_event WHERE aggregate_id IN ('$paymentNo', '$refundNo');
DELETE FROM refund_callback_log WHERE refund_no = '$refundNo';
DELETE FROM refund_transaction
WHERE refund_id IN (SELECT id FROM refund_order WHERE refund_no = '$refundNo');
DELETE FROM refund_order WHERE refund_no = '$refundNo';
DELETE FROM payment_callback_log WHERE payment_no = '$paymentNo';
DELETE FROM payment_transaction
WHERE payment_id IN (SELECT id FROM payment_order WHERE payment_no = '$paymentNo');
DELETE FROM payment_order WHERE payment_no = '$paymentNo';
"@
        $paymentCleanupSql | docker exec -i -e "MYSQL_PWD=$env:PAYMENT_DB_PASSWORD" ecom-mysql `
            mysql "-u$env:PAYMENT_DB_USER" $env:PAYMENT_DB_NAME 2>$null
    }

    $cleanupSql = @"
DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM user_account WHERE email = '$smokeEmail');
DELETE FROM user_role WHERE user_id IN (SELECT id FROM user_account WHERE email = '$smokeEmail');
DELETE FROM user_address WHERE user_id IN (SELECT id FROM user_account WHERE email = '$smokeEmail');
DELETE FROM user_account WHERE email = '$smokeEmail';
DELETE FROM login_record WHERE normalized_email = '$smokeEmail';
DELETE FROM login_record WHERE normalized_email = '$riskEmail';
"@
    $cleanupSql | docker exec -i -e "MYSQL_PWD=$env:IDENTITY_DB_PASSWORD" ecom-mysql `
        mysql "-u$env:IDENTITY_DB_USER" $env:IDENTITY_DB_NAME 2>$null
}
