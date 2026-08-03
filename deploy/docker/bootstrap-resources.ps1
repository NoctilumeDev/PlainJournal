$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-EnvValue {
    param([Parameter(Mandatory)][string]$Name)

    $line = Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env') |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -First 1

    if (-not $line) {
        throw "Missing $Name in .env"
    }

    return $line.Substring($line.IndexOf('=') + 1)
}

function Ensure-EnvValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )

    $envPath = Join-Path $PSScriptRoot '.env'
    $line = Get-Content -LiteralPath $envPath |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -First 1

    if ($line) {
        return $line.Substring($line.IndexOf('=') + 1)
    }

    [System.IO.File]::AppendAllText($envPath, "`r`n$Name=$Value", [System.Text.UTF8Encoding]::new($false))
    return $Value
}

function New-HexSecret {
    param([int]$ByteLength = 32)

    return [Convert]::ToHexString(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes($ByteLength)
    ).ToLowerInvariant()
}

function Initialize-ServiceDatabase {
    param(
        [Parameter(Mandatory)][string]$DatabaseName,
        [Parameter(Mandatory)][string]$DatabaseUser,
        [Parameter(Mandatory)][string]$DatabasePassword,
        [Parameter(Mandatory)][string]$RootPassword
    )

    if ($DatabaseName -notmatch '^[a-z0-9_]+$' -or $DatabaseName.Length -gt 64 -or
        $DatabaseUser -notmatch '^[a-z0-9_]+$' -or $DatabaseUser.Length -gt 32) {
        throw 'Service database names and users must contain only lowercase letters, digits, and underscores.'
    }

    $escapedPassword = $DatabasePassword.Replace("'", "''")
    $databaseSql = @"
CREATE DATABASE IF NOT EXISTS $DatabaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$DatabaseUser'@'%' IDENTIFIED BY '$escapedPassword';
ALTER USER '$DatabaseUser'@'%' IDENTIFIED BY '$escapedPassword';
GRANT ALL PRIVILEGES ON $DatabaseName.* TO '$DatabaseUser'@'%';
FLUSH PRIVILEGES;
"@

    $databaseSql | docker exec -i -e "MYSQL_PWD=$RootPassword" plainjournal-mysql mysql -uroot
    if ($LASTEXITCODE -ne 0) {
        throw "Database initialization failed: $DatabaseName"
    }
}

$identityDbName = Ensure-EnvValue -Name 'IDENTITY_DB_NAME' -Value 'ecom_identity'
$identityDbUser = Ensure-EnvValue -Name 'IDENTITY_DB_USER' -Value 'ecom_identity_app'
$identityDbPassword = Ensure-EnvValue -Name 'IDENTITY_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$null = Ensure-EnvValue -Name 'IDENTITY_JWT_SECRET' -Value (New-HexSecret -ByteLength 32)
$tradeInternalServiceToken = Ensure-EnvValue -Name 'TRADE_INTERNAL_SERVICE_TOKEN' -Value (New-HexSecret -ByteLength 32)
$paymentInternalServiceToken = Ensure-EnvValue -Name 'PAYMENT_INTERNAL_SERVICE_TOKEN' -Value (New-HexSecret -ByteLength 32)
if ($tradeInternalServiceToken -eq $paymentInternalServiceToken) {
    throw 'TRADE_INTERNAL_SERVICE_TOKEN and PAYMENT_INTERNAL_SERVICE_TOKEN must be different.'
}
$metricsScrapeToken = Ensure-EnvValue -Name 'METRICS_SCRAPE_TOKEN' -Value (New-HexSecret -ByteLength 32)
$null = Ensure-EnvValue -Name 'GRAFANA_ADMIN_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$catalogDbName = Ensure-EnvValue -Name 'CATALOG_DB_NAME' -Value 'ecom_catalog'
$catalogDbUser = Ensure-EnvValue -Name 'CATALOG_DB_USER' -Value 'ecom_catalog_app'
$catalogDbPassword = Ensure-EnvValue -Name 'CATALOG_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$inventoryDbName = Ensure-EnvValue -Name 'INVENTORY_DB_NAME' -Value 'ecom_inventory'
$inventoryDbUser = Ensure-EnvValue -Name 'INVENTORY_DB_USER' -Value 'ecom_inventory_app'
$inventoryDbPassword = Ensure-EnvValue -Name 'INVENTORY_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$tradeDbName = Ensure-EnvValue -Name 'TRADE_DB_NAME' -Value 'ecom_trade'
$tradeDbUser = Ensure-EnvValue -Name 'TRADE_DB_USER' -Value 'ecom_trade_app'
$tradeDbPassword = Ensure-EnvValue -Name 'TRADE_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$null = Ensure-EnvValue -Name 'TRADE_SHARD_0_DB_NAME' -Value 'ecom_trade_shard_0'
$null = Ensure-EnvValue -Name 'TRADE_SHARD_1_DB_NAME' -Value 'ecom_trade_shard_1'
$null = Ensure-EnvValue -Name 'TRADE_SHARD_DB_USER' -Value 'ecom_trade_shard_app'
$null = Ensure-EnvValue -Name 'TRADE_SHARD_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$null = Ensure-EnvValue -Name 'TRADE_SHARD_1_PORT' -Value '13326'
$paymentDbName = Ensure-EnvValue -Name 'PAYMENT_DB_NAME' -Value 'ecom_payment'
$paymentDbUser = Ensure-EnvValue -Name 'PAYMENT_DB_USER' -Value 'ecom_payment_app'
$paymentDbPassword = Ensure-EnvValue -Name 'PAYMENT_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$null = Ensure-EnvValue -Name 'MOCK_PAYMENT_CALLBACK_SECRET' -Value (New-HexSecret -ByteLength 32)
$fulfillmentDbName = Ensure-EnvValue -Name 'FULFILLMENT_DB_NAME' -Value 'ecom_fulfillment'
$fulfillmentDbUser = Ensure-EnvValue -Name 'FULFILLMENT_DB_USER' -Value 'ecom_fulfillment_app'
$fulfillmentDbPassword = Ensure-EnvValue -Name 'FULFILLMENT_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$marketingDbName = Ensure-EnvValue -Name 'MARKETING_DB_NAME' -Value 'ecom_marketing'
$marketingDbUser = Ensure-EnvValue -Name 'MARKETING_DB_USER' -Value 'ecom_marketing_app'
$marketingDbPassword = Ensure-EnvValue -Name 'MARKETING_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$chatDbName = Ensure-EnvValue -Name 'CHAT_DB_NAME' -Value 'ecom_chat'
$chatDbUser = Ensure-EnvValue -Name 'CHAT_DB_USER' -Value 'ecom_chat_app'
$chatDbPassword = Ensure-EnvValue -Name 'CHAT_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$notificationDbName = Ensure-EnvValue -Name 'NOTIFICATION_DB_NAME' -Value 'ecom_notification'
$notificationDbUser = Ensure-EnvValue -Name 'NOTIFICATION_DB_USER' -Value 'ecom_notification_app'
$notificationDbPassword = Ensure-EnvValue -Name 'NOTIFICATION_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)
$analyticsDbName = Ensure-EnvValue -Name 'ANALYTICS_DB_NAME' -Value 'ecom_analytics'
$analyticsDbUser = Ensure-EnvValue -Name 'ANALYTICS_DB_USER' -Value 'ecom_analytics_app'
$analyticsDbPassword = Ensure-EnvValue -Name 'ANALYTICS_DB_PASSWORD' -Value (New-HexSecret -ByteLength 24)

$runtimeSecretDirectory = Join-Path $PSScriptRoot '.runtime-secrets'
[System.IO.Directory]::CreateDirectory($runtimeSecretDirectory) | Out-Null
[System.IO.File]::WriteAllText(
    (Join-Path $runtimeSecretDirectory 'metrics-scrape-token'),
    $metricsScrapeToken,
    [System.Text.UTF8Encoding]::new($false)
)

$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-redis',
    'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy',
    'plainjournal-minio'
)

foreach ($container in $requiredContainers) {
    $running = docker inspect --format '{{.State.Running}}' $container 2>$null
    if ($running -ne 'true') {
        throw "Container is not running: $container"
    }
}

$mysqlRootPassword = Get-EnvValue -Name 'MYSQL_ROOT_PASSWORD'
Initialize-ServiceDatabase -DatabaseName $identityDbName -DatabaseUser $identityDbUser `
    -DatabasePassword $identityDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $catalogDbName -DatabaseUser $catalogDbUser `
    -DatabasePassword $catalogDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $inventoryDbName -DatabaseUser $inventoryDbUser `
    -DatabasePassword $inventoryDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $tradeDbName -DatabaseUser $tradeDbUser `
    -DatabasePassword $tradeDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $paymentDbName -DatabaseUser $paymentDbUser `
    -DatabasePassword $paymentDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $fulfillmentDbName -DatabaseUser $fulfillmentDbUser `
    -DatabasePassword $fulfillmentDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $marketingDbName -DatabaseUser $marketingDbUser `
    -DatabasePassword $marketingDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $chatDbName -DatabaseUser $chatDbUser `
    -DatabasePassword $chatDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $notificationDbName -DatabaseUser $notificationDbUser `
    -DatabasePassword $notificationDbPassword -RootPassword $mysqlRootPassword
Initialize-ServiceDatabase -DatabaseName $analyticsDbName -DatabaseUser $analyticsDbUser `
    -DatabasePassword $analyticsDbPassword -RootPassword $mysqlRootPassword

$nacosPort = Get-EnvValue -Name 'NACOS_CLIENT_PORT'
$nacosPassword = Get-EnvValue -Name 'NACOS_ADMIN_PASSWORD'
$nacosBaseUrl = "http://127.0.0.1:$nacosPort/nacos/v3/auth/user"

$loginResponse = $null
try {
    $loginResponse = Invoke-RestMethod -Method Post -Uri "$nacosBaseUrl/login" -Body @{
        username = 'nacos'
        password = $nacosPassword
    }
}
catch {
    try {
        Invoke-RestMethod -Method Post -Uri "$nacosBaseUrl/admin" -Body @{
            password = $nacosPassword
        } | Out-Null

        $loginResponse = Invoke-RestMethod -Method Post -Uri "$nacosBaseUrl/login" -Body @{
            username = 'nacos'
            password = $nacosPassword
        }
    }
    catch {
        throw 'Nacos administrator initialization failed. Check whether .env matches the existing administrator password.'
    }
}

$nacosAdminHeaders = @{
    Authorization = "Bearer $($loginResponse.accessToken)"
}
foreach ($dataId in @(
    'ecommerce-gateway.yml',
    'identity-service.yml',
    'catalog-service.yml',
    'inventory-service.yml',
    'trade-service.yml',
    'payment-service.yml',
    'fulfillment-service.yml',
    'marketing-service.yml',
    'chat-service.yml',
    'notification-service.yml',
    'analytics-service.yml'
)) {
    $configPath = Join-Path $PSScriptRoot "nacos\$dataId"
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "Missing Nacos configuration file: $configPath"
    }

    $publishResult = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:$nacosPort/nacos/v3/admin/cs/config" `
        -Headers $nacosAdminHeaders `
        -Body @{
            namespaceId = 'public'
            groupName = 'ECOMMERCE'
            dataId = $dataId
            content = Get-Content -LiteralPath $configPath -Raw
            type = 'yaml'
            desc = 'Local development foundation configuration'
        }

    if ($publishResult.code -ne 0 -or $publishResult.data -ne $true) {
        throw "Nacos configuration initialization failed: $dataId"
    }
}

$topics = @(
    'ecommerce-order-events',
    'ecommerce-flash-sale-events',
    'ecommerce-chat-events',
    'ecommerce-chat-delivery-events',
    'ecommerce-inventory-events',
    'ecommerce-payment-events',
    'ecommerce-refund-events',
    'ecommerce-chat-events',
    'ecommerce-logistics-events',
    'ecommerce-notification-events',
    'ecommerce-promotion-events'
)

foreach ($topic in $topics) {
    $result = docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic -n plainjournal-rocketmq-namesrv:9876 -c EcommerceCluster -t $topic -r 4 -w 4 2>&1
    if (($result -join "`n") -notmatch 'create topic to .* success') {
        throw "RocketMQ topic initialization failed: $topic"
    }
}

$bucketCommand = @'
mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
for bucket in product-media user-avatars chat-attachments review-media logistics-proofs after-sale-evidence; do
  mc mb --ignore-existing "local/$bucket" >/dev/null
done
printf '%s\n' '{"Rules":[{"ID":"chat-quarantine-expiry","Status":"Enabled","Filter":{"Prefix":"quarantine/chat/"},"Expiration":{"Days":1}}]}' |
  mc ilm import local/chat-attachments >/dev/null
'@

docker exec plainjournal-minio sh -c $bucketCommand
if ($LASTEXITCODE -ne 0) {
    throw 'MinIO bucket initialization failed.'
}

Write-Host 'Service databases/secrets, observability credentials, Nacos configuration, RocketMQ topics, and MinIO buckets are ready.'
