[CmdletBinding()]
param(
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot

function Assert-TextContains {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Pattern,
        [Parameter(Mandatory)][string]$Description
    )

    $content = Get-Content -LiteralPath $Path -Raw
    if ($content -notmatch $Pattern) {
        throw "$Description ($Path)"
    }
}

$databaseBoundaries = [ordered]@{
    identity = @{
        Config = 'backend/services/identity-service/src/main/resources/application.yml'
        Name = 'ecom_identity'
        User = 'ecom_identity_app'
    }
    catalog = @{
        Config = 'backend/services/catalog-service/src/main/resources/application.yml'
        Name = 'ecom_catalog'
        User = 'ecom_catalog_app'
    }
    inventory = @{
        Config = 'backend/services/inventory-service/src/main/resources/application.yml'
        Name = 'ecom_inventory'
        User = 'ecom_inventory_app'
    }
    trade = @{
        Config = 'backend/services/trade-service/src/main/resources/application.yml'
        Name = 'ecom_trade'
        User = 'ecom_trade_app'
    }
    payment = @{
        Config = 'backend/services/payment-service/src/main/resources/application.yml'
        Name = 'ecom_payment'
        User = 'ecom_payment_app'
    }
    fulfillment = @{
        Config = 'backend/services/fulfillment-service/src/main/resources/application.yml'
        Name = 'ecom_fulfillment'
        User = 'ecom_fulfillment_app'
    }
    marketing = @{
        Config = 'backend/services/marketing-service/src/main/resources/application.yml'
        Name = 'ecom_marketing'
        User = 'ecom_marketing_app'
    }
    chat = @{
        Config = 'backend/services/chat-service/src/main/resources/application.yml'
        Name = 'ecom_chat'
        User = 'ecom_chat_app'
    }
    notification = @{
        Config = 'backend/services/notification-service/src/main/resources/application.yml'
        Name = 'ecom_notification'
        User = 'ecom_notification_app'
    }
    analytics = @{
        Config = 'backend/services/analytics-service/src/main/resources/application.yml'
        Name = 'ecom_analytics'
        User = 'ecom_analytics_app'
    }
}

$seenUsers = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)
$seenDatabases = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)

foreach ($entry in $databaseBoundaries.GetEnumerator()) {
    $configPath = Join-Path $repositoryRoot $entry.Value.Config
    if (-not (Test-Path -LiteralPath $configPath)) {
        throw "Missing datasource configuration for $($entry.Key): $configPath"
    }
    if (-not $seenUsers.Add($entry.Value.User)) {
        throw "Database user is shared across owner domains: $($entry.Value.User)"
    }
    if (-not $seenDatabases.Add($entry.Value.Name)) {
        throw "Database schema is shared across owner domains: $($entry.Value.Name)"
    }

    $namePattern = [regex]::Escape(
        '${' + $entry.Key.ToUpperInvariant() + '_DB_NAME:' + $entry.Value.Name + '}')
    $userPattern = [regex]::Escape(
        '${' + $entry.Key.ToUpperInvariant() + '_DB_USER:' + $entry.Value.User + '}')
    Assert-TextContains -Path $configPath -Pattern $namePattern `
        -Description "Datasource does not fail into the expected $($entry.Key) schema"
    Assert-TextContains -Path $configPath -Pattern $userPattern `
        -Description "Datasource does not use the expected $($entry.Key) owner account"
    Assert-TextContains -Path $configPath -Pattern 'connectionTimeZone=UTC' `
        -Description "Datasource does not interpret persisted Instants in UTC"
    Assert-TextContains -Path $configPath -Pattern 'forceConnectionTimeZoneToSession=true' `
        -Description "Datasource does not force the MySQL session clock to UTC"
}

$relationshipCredentials = [ordered]@{
    'backend/services/identity-service/src/main/resources/application.yml' =
        '${TRADE_INTERNAL_SERVICE_TOKEN:}'
    'backend/services/inventory-service/src/main/resources/application.yml' =
        '${TRADE_INTERNAL_SERVICE_TOKEN:}'
    'backend/services/marketing-service/src/main/resources/application.yml' =
        '${TRADE_INTERNAL_SERVICE_TOKEN:}'
    'backend/services/payment-service/src/main/resources/application.yml' =
        '${PAYMENT_INTERNAL_SERVICE_TOKEN:}'
    'backend/services/trade-service/src/main/resources/application.yml' =
        '${PAYMENT_INTERNAL_SERVICE_TOKEN:}'
}
foreach ($entry in $relationshipCredentials.GetEnumerator()) {
    Assert-TextContains -Path (Join-Path $repositoryRoot $entry.Key) `
        -Pattern ([regex]::Escape($entry.Value)) `
        -Description 'Relationship-specific internal credential is missing'
}
Assert-TextContains `
    -Path (Join-Path $repositoryRoot 'backend/services/trade-service/src/main/resources/application.yml') `
    -Pattern ([regex]::Escape('${TRADE_INTERNAL_SERVICE_TOKEN:}')) `
    -Description 'Trade outbound relationship credential is missing'

$sourceRoots = @(
    (Join-Path $repositoryRoot 'backend'),
    (Join-Path $repositoryRoot 'deploy')
)
$activeConfigFiles = @(
    Get-ChildItem -LiteralPath $sourceRoots -Recurse -File |
        Where-Object {
            -not [string]::Equals($_.FullName, $PSCommandPath,
                [StringComparison]::OrdinalIgnoreCase) -and
            $_.FullName -notmatch '[\\/](target|\.run)[\\/]' -and
            $_.Extension -in @('.yml', '.yaml', '.ps1')
        }
)
$activeConfigText = ($activeConfigFiles |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
$jdbcUrlCount = [regex]::Matches($activeConfigText, 'jdbc:mysql').Count
$utcConnectionCount = [regex]::Matches(
    $activeConfigText, 'connectionTimeZone=UTC').Count
$forcedUtcSessionCount = [regex]::Matches(
    $activeConfigText, 'forceConnectionTimeZoneToSession=true').Count
if ($jdbcUrlCount -eq 0 -or
    $utcConnectionCount -ne $jdbcUrlCount -or
    $forcedUtcSessionCount -ne $jdbcUrlCount -or
    $activeConfigText -match 'serverTimezone=') {
    throw "JDBC time-zone contract is incomplete: urls=$jdbcUrlCount, UTC=$utcConnectionCount, forcedSessions=$forcedUtcSessionCount."
}

$genericCredentialPattern = '(?<!TRADE_)(?<!PAYMENT_)INTERNAL_SERVICE_TOKEN'
$genericCredentialMatches = @(
    Get-ChildItem -LiteralPath $sourceRoots -Recurse -File |
        Where-Object {
            -not [string]::Equals($_.FullName, $PSCommandPath,
                [StringComparison]::OrdinalIgnoreCase) -and
            $_.FullName -notmatch '[\\/](target|\.run)[\\/]' -and
            $_.Extension -in @('.java', '.yml', '.yaml', '.ps1', '.xml', '.example')
        } |
        Select-String -Pattern $genericCredentialPattern
)
$legacyCompatibilityPaths = @(
    'backend/verify-gateway-rolling-upgrade.ps1',
    'backend/verify-trade-dual-version-compatibility.ps1'
)
$legacyCompatibilityPattern =
    'INTERNAL_SERVICE_TOKEN=\$\(\$script:settings\[''PAYMENT_INTERNAL_SERVICE_TOKEN''\]\)'
$unexpectedGenericCredentialMatches = @(
    $genericCredentialMatches | Where-Object {
        $relativePath = [IO.Path]::GetRelativePath(
            $repositoryRoot,
            $_.Path).Replace('\', '/')
        $relativePath -notin $legacyCompatibilityPaths -or
            $_.Line -notmatch $legacyCompatibilityPattern
    }
)
if ($unexpectedGenericCredentialMatches.Count -ne 0) {
    $unexpected = $unexpectedGenericCredentialMatches[0]
    throw "Shared internal credential remains in active configuration: $($unexpected.Path):$($unexpected.LineNumber)"
}
if ($genericCredentialMatches.Count -ne $legacyCompatibilityPaths.Count) {
    throw "Expected exactly $($legacyCompatibilityPaths.Count) isolated legacy compatibility mappings, found $($genericCredentialMatches.Count)."
}
foreach ($relativePath in $legacyCompatibilityPaths) {
    $matchesForPath = @(
        $genericCredentialMatches | Where-Object {
            [IO.Path]::GetRelativePath(
                $repositoryRoot,
                $_.Path).Replace('\', '/') -eq $relativePath
        }
    )
    if ($matchesForPath.Count -ne 1) {
        throw "Legacy compatibility mapping is missing or duplicated: $relativePath"
    }
}

$applicationClockMatches = @(
    Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'backend/services') `
        -Recurse -Filter '*.java' |
        Where-Object { $_.FullName -match '[\\/]application[\\/]' } |
        Select-String -Pattern '\bjava\.time\.Clock\b|\bClock\b|Instant\.now\(|LocalDateTime\.now\('
)
if ($applicationClockMatches.Count -ne 0) {
    throw "Owner-domain application code still depends on a JVM wall clock: $($applicationClockMatches[0].Path):$($applicationClockMatches[0].LineNumber)"
}

$synchronousGuardUsers = @(
    'backend/services/trade-service/src/main/java/com/ecommerce/trade/infrastructure/resilience/TradeSynchronousBoundaryResilience.java',
    'backend/services/trade-service/src/main/java/com/ecommerce/trade/infrastructure/resilience/TradeMarketingPricingLockResilience.java',
    'backend/services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/resilience/PaymentTradeResilience.java'
)
foreach ($relativePath in $synchronousGuardUsers) {
    Assert-TextContains -Path (Join-Path $repositoryRoot $relativePath) `
        -Pattern 'SynchronousBoundaryGuard\.requireOutsideTransaction\(' `
        -Description 'Synchronous HTTP boundary lacks a fail-fast transaction guard'
}

$presencePath = Join-Path $repositoryRoot `
    'backend/services/chat-service/src/main/java/com/ecommerce/chat/infrastructure/realtime/RedisChatPresenceStore.java'
Assert-TextContains -Path $presencePath -Pattern "redis\.call\('TIME'\)" `
    -Description 'Chat presence expiry does not use the Redis owner clock'

$bootstrapPath = Join-Path $repositoryRoot 'deploy/docker/bootstrap-resources.ps1'
Assert-TextContains -Path $bootstrapPath `
    -Pattern 'GRANT ALL PRIVILEGES ON \$DatabaseName\.\* TO' `
    -Description 'Bootstrap no longer grants within the selected owner schema'
$bootstrap = Get-Content -LiteralPath $bootstrapPath -Raw
if ($bootstrap -match 'GRANT\s+ALL\s+PRIVILEGES\s+ON\s+\*\.\*') {
    throw 'Bootstrap grants a service account global database privileges.'
}

$composePath = Join-Path $repositoryRoot 'deploy/docker/compose.yml'
Assert-TextContains -Path $composePath -Pattern '(?m)^name:\s+\$\{COMPOSE_PROJECT_NAME\}\r?$' `
    -Description 'Compose project name is not explicit'
$envExamplePath = Join-Path $repositoryRoot 'deploy/docker/.env.example'
Assert-TextContains -Path $envExamplePath -Pattern '(?m)^COMPOSE_PROJECT_NAME=plainjournal\r?$' `
    -Description 'PlainJournal Compose project name is not the documented default'

$result = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    databaseOwnerDomains = $databaseBoundaries.Count
    uniqueDatabaseUsers = $seenUsers.Count
    uniqueDatabaseSchemas = $seenDatabases.Count
    jdbcUrlsWithForcedUtcSessions = $forcedUtcSessionCount
    relationshipCredentials = @('TRADE_INTERNAL_SERVICE_TOKEN', 'PAYMENT_INTERNAL_SERVICE_TOKEN')
    sharedCredentialReferences = 0
    isolatedLegacyCompatibilityMappings = $legacyCompatibilityPaths.Count
    globalServiceDatabaseGrants = 0
    ownerApplicationJvmClockReferences = 0
    guardedSynchronousHttpBoundaries = $synchronousGuardUsers.Count
    chatPresenceClock = 'REDIS_TIME'
    composeProjectName = 'plainjournal'
    status = 'PASS'
}

$json = $result | ConvertTo-Json -Depth 5
if ($OutputPath) {
    $resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
        $OutputPath
    } else {
        Join-Path $repositoryRoot $OutputPath
    }
    $directory = Split-Path -Parent $resolvedOutput
    if ($directory) {
        [IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    [IO.File]::WriteAllText(
        $resolvedOutput,
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

$json
