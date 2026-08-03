#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:deployDirectory = Join-Path $script:repositoryRoot 'deploy\docker'
$script:suffix = "$([DateTimeOffset]::Now.ToString('yyyyMMddHHmmss'))$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
$script:runId = "m8-product-reviews-$($script:suffix)"
$script:namespace = "m8r-$($script:suffix)"
$script:runDirectory = Join-Path $script:backendRoot ".run\$($script:runId)"
$script:catalogDatabase = "ecom_catalog_review_$($script:suffix)"
$script:tradeDatabase = "ecom_trade_review_$($script:suffix)"
$script:orderTopic = "ecommerce-review-order-$($script:suffix)"
$script:logisticsTopic = "ecommerce-review-logistics-$($script:suffix)"
$script:catalogConsumerGroup = "catalog-review-$($script:suffix)"
$script:tradeConsumerGroup = "trade-review-fulfillment-$($script:suffix)"
$script:gatewayPort = 18000
$script:catalogPort = 18102
$script:tradePort = 18104
$script:customerId = 890000000000000001L
$script:otherCustomerId = 890000000000000002L
$script:adminId = 890000000000000101L
$script:productId = 890000000000001001L
$script:skuId = 890000000000001101L
$script:categoryId = 890000000000001201L
$script:brandId = 890000000000001301L
$script:firstOrderId = 890000000000002001L
$script:firstOrderItemId = 890000000000002101L
$script:firstOrderNo = "ORD-REVIEW-$($script:suffix)-1"
$script:secondOrderNo = "ORD-REVIEW-$($script:suffix)-2"
$script:processes = [ordered]@{}
$script:createdTopics = [Collections.Generic.List[string]]::new()
$script:createdConsumerGroups = [Collections.Generic.List[string]]::new()
$script:databasesCreated = $false
$script:proxyStopped = $false
$script:verification = [ordered]@{}
$script:failureContext = [ordered]@{}

$javaHomeLauncher = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $null
}
else {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
}
$script:javaPath = if ($null -ne $javaHomeLauncher -and
    (Test-Path -LiteralPath $javaHomeLauncher)) {
    $javaHomeLauncher
}
else {
    (Get-Command java -ErrorAction Stop).Source
}

[IO.Directory]::CreateDirectory($script:runDirectory) | Out-Null
$script:tracePath = Join-Path $script:runDirectory 'script-trace.log'

function Write-VerificationTrace {
    param([Parameter(Mandatory)][string]$Message)

    $line = "$([DateTimeOffset]::Now.ToString('o')) $Message"
    [IO.File]::AppendAllText(
        $script:tracePath,
        "$line`r`n",
        [Text.UTF8Encoding]::new($false))
}

function Import-LocalEnvironment {
    $envPath = Join-Path $script:deployDirectory '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw "Missing local environment file: $envPath"
    }
    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*#' -or $line -notmatch '=') {
            continue
        }
        $name, $value = $line -split '=', 2
        [Environment]::SetEnvironmentVariable(
            $name.Trim(),
            $value,
            'Process')
    }
}

function Require-Environment {
    param([Parameter(Mandatory)][string[]]$Names)

    $missing = @($Names | Where-Object {
        [string]::IsNullOrWhiteSpace(
            [Environment]::GetEnvironmentVariable($_, 'Process'))
    })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
    }
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    param(
        [Parameter(Mandatory)][long]$UserId,
        [Parameter(Mandatory)][string[]]$Roles
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + 3600
        jti = [Guid]::NewGuid().ToString()
        roles = $Roles
    } | ConvertTo-Json -Compress
    $encodedHeader = ConvertTo-Base64Url (
        [Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url (
        [Text.Encoding]::UTF8.GetBytes($payload))
    $unsigned = "$encodedHeader.$encodedPayload"
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

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
            -ErrorAction SilentlyContinue)
    if ($listeners.Count -gt 0) {
        throw "Port $Port is already in use by PID $($listeners[0].OwningProcess)."
    }
}

function Wait-Until {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][scriptblock]$Condition,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (& $Condition) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Wait-TcpPort {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 60
    )

    Wait-Until -Description "TCP port $Port" -TimeoutSeconds $TimeoutSeconds -Condition {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $task = $client.ConnectAsync('127.0.0.1', $Port)
            if (-not $task.Wait(1000)) {
                return $false
            }
            return $client.Connected
        }
        catch {
            return $false
        }
        finally {
            $client.Dispose()
        }
    }
}

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][hashtable]$Environment,
        [string[]]$ApplicationArguments = @()
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing application artifact: $Jar"
    }
    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] =
            [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable(
            $entry.Key,
            [string]$entry.Value,
            'Process')
    }
    try {
        $arguments = @(
            '-Xms128m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4',
            '-jar',
            $Jar
        ) + $ApplicationArguments
        $process = Start-Process -FilePath $script:javaPath `
            -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (
                Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (
                Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            jar = $Jar
            port = $Port
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable(
                $entry.Key,
                $entry.Value,
                'Process')
        }
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$ProcessName,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'no response'
    do {
        $managed = $script:processes[$ProcessName].process
        if ($managed.HasExited) {
            throw "$ProcessName exited before becoming ready. ExitCode=$($managed.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri `
                -SkipHttpErrorCheck `
                -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and
                $response.StatusCode -lt 300) {
                return
            }
            $lastState = "HTTP $($response.StatusCode)"
        }
        catch {
            $lastState = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri. Last state: $lastState"
}

function Stop-ManagedProcesses {
    foreach ($entry in @($script:processes.GetEnumerator())) {
        $managed = $entry.Value
        $processId = [int]$managed.process.Id
        $process = Get-CimInstance Win32_Process `
            -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        $jarName = [IO.Path]::GetFileName([string]$managed.jar)
        if ([string]$process.CommandLine -notlike "*$jarName*") {
            Write-Warning (
                "Refused to stop PID $processId because its command line " +
                "no longer matches $jarName.")
            continue
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }

    Wait-Until -Description 'M8 review application ports to close' `
        -TimeoutSeconds 15 `
        -Condition {
            foreach ($port in @(
                    $script:gatewayPort,
                    $script:catalogPort,
                    $script:tradePort)) {
                if (Get-NetTCPConnection -State Listen -LocalPort $port `
                        -ErrorAction SilentlyContinue) {
                    return $false
                }
            }
            return $true
        }
}

function Invoke-RootSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i `
        -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" `
        plainjournal-mysql mysql -uroot -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Root MySQL command failed.'
    }
    return @($output)
}

function Invoke-CatalogSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i `
        -e "MYSQL_PWD=$env:CATALOG_DB_PASSWORD" `
        plainjournal-mysql mysql "-u$env:CATALOG_DB_USER" `
        $script:catalogDatabase -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Catalog verification MySQL command failed.'
    }
    return @($output)
}

function Invoke-TradeSql {
    param([Parameter(Mandatory)][string]$Sql)

    $output = $Sql | docker exec -i `
        -e "MYSQL_PWD=$env:TRADE_DB_PASSWORD" `
        plainjournal-mysql mysql "-u$env:TRADE_DB_USER" `
        $script:tradeDatabase -N -B
    if ($LASTEXITCODE -ne 0) {
        throw 'Trade verification MySQL command failed.'
    }
    return @($output)
}

function Get-CatalogScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-CatalogSql -Sql $Sql)
    return $rows.Count -eq 0 ? $null : [string]$rows[0]
}

function Get-TradeScalar {
    param([Parameter(Mandatory)][string]$Sql)

    $rows = @(Invoke-TradeSql -Sql $Sql)
    return $rows.Count -eq 0 ? $null : [string]$rows[0]
}

function New-VerificationDatabases {
    $catalogUser = $env:CATALOG_DB_USER.Replace("'", "''")
    $tradeUser = $env:TRADE_DB_USER.Replace("'", "''")
    Invoke-RootSql -Sql @"
CREATE DATABASE ``$($script:catalogDatabase)``
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE ``$($script:tradeDatabase)``
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON ``$($script:catalogDatabase)``.*
    TO '$catalogUser'@'%';
GRANT ALL PRIVILEGES ON ``$($script:tradeDatabase)``.*
    TO '$tradeUser'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null
    $script:databasesCreated = $true
}

function Remove-VerificationDatabases {
    if (-not $script:databasesCreated) {
        return
    }
    $catalogUser = $env:CATALOG_DB_USER.Replace("'", "''")
    $tradeUser = $env:TRADE_DB_USER.Replace("'", "''")
    Invoke-RootSql -Sql @"
REVOKE ALL PRIVILEGES
    ON ``$($script:catalogDatabase)``.* FROM '$catalogUser'@'%';
REVOKE ALL PRIVILEGES
    ON ``$($script:tradeDatabase)``.* FROM '$tradeUser'@'%';
DROP DATABASE IF EXISTS ``$($script:catalogDatabase)``;
DROP DATABASE IF EXISTS ``$($script:tradeDatabase)``;
FLUSH PRIVILEGES;
"@ | Out-Null
    $script:databasesCreated = $false
}

function New-RocketMqTopic {
    param([Parameter(Mandatory)][string]$Topic)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $Topic `
            -r 2 `
            -w 2 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'success') {
        throw "Unable to create RocketMQ topic ${Topic}: $($output -join "`n")"
    }
    $script:createdTopics.Add($Topic)
}

function New-RocketMqConsumerGroup {
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
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'success') {
        throw (
            "Unable to create RocketMQ consumer group " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    $script:createdConsumerGroups.Add($ConsumerGroup)
}

function Get-RocketMqTopics {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list RocketMQ topics: $($output -join "`n")"
    }
    return @($output | ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Test-RocketMqConsumerGroupPresent {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin getConsumerConfig `
            -n plainjournal-rocketmq-namesrv:9876 `
            -g $ConsumerGroup 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw (
            "Unable to inspect RocketMQ consumer group " +
            "${ConsumerGroup}: $($output -join "`n")")
    }
    $pattern = (
        '(?m)^\s*groupName\s*=\s*' +
        [regex]::Escape($ConsumerGroup) +
        '\s*$')
    return ($output -join "`n") -match $pattern
}

function Get-RocketMqConsumerOffsetKeys {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $output = @(docker exec plainjournal-rocketmq-broker sh -lc `
                'cat /home/rocketmq/store/config/consumerOffset.json' 2>&1)
        if ($LASTEXITCODE -eq 0) {
            try {
                $metadata = ($output -join "`n") | ConvertFrom-Json -AsHashtable
                return @($metadata.offsetTable.Keys)
            }
            catch {
                if ($attempt -eq 5) {
                    throw "Unable to parse RocketMQ consumer offsets: $($_.Exception.Message)"
                }
            }
        }
        elseif ($attempt -eq 5) {
            throw "Unable to inspect RocketMQ consumer offsets: $($output -join "`n")"
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Unable to inspect RocketMQ consumer offsets.'
}

function Test-RocketMqConsumerGroupResidual {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    if (Test-RocketMqConsumerGroupPresent -ConsumerGroup $ConsumerGroup) {
        return $true
    }
    $suffix = "@$ConsumerGroup"
    return @(
        Get-RocketMqConsumerOffsetKeys |
        Where-Object { $_.EndsWith($suffix, [StringComparison]::Ordinal) }
    ).Count -gt 0
}

function Get-RocketMqConsumerArtifactTopics {
    param([Parameter(Mandatory)][string[]]$ConsumerGroups)

    return @(Get-RocketMqTopics | Where-Object {
            $topicName = $_
            ($topicName.StartsWith('%RETRY%') -or
                $topicName.StartsWith('%DLQ%')) -and
            @($ConsumerGroups | Where-Object {
                    $topicName.StartsWith("%RETRY%$_") -or
                    $topicName.StartsWith("%DLQ%$_")
                }).Count -gt 0
        } | Sort-Object -Unique)
}

function Remove-RocketMqConsumerGroup {
    param([Parameter(Mandatory)][string]$ConsumerGroup)

    $lastOutput = @()
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $lastOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $ConsumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($lastOutput -join "`n") -notmatch 'success') {
            throw (
                "RocketMQ consumer group deletion attempt $attempt failed " +
                "for ${ConsumerGroup}: $($lastOutput -join "`n")")
        }

        Start-Sleep -Seconds 3
        if (Test-RocketMqConsumerGroupResidual `
                -ConsumerGroup $ConsumerGroup) {
            continue
        }

        Start-Sleep -Seconds 3
        if (-not (Test-RocketMqConsumerGroupResidual `
                    -ConsumerGroup $ConsumerGroup)) {
            return
        }
    }

    throw (
        "RocketMQ consumer group remained after three deletion attempts: " +
        $ConsumerGroup)
}

function Remove-RocketMqResources {
    $errors = [Collections.Generic.List[string]]::new()
    $configuredGroups = @($script:createdConsumerGroups)
    foreach ($consumerGroup in $configuredGroups) {
        try {
            Remove-RocketMqConsumerGroup -ConsumerGroup $consumerGroup
        }
        catch {
            $errors.Add("${consumerGroup}: $($_.Exception.Message)")
        }
    }
    foreach ($topicName in @(Get-RocketMqConsumerArtifactTopics `
                -ConsumerGroups $configuredGroups)) {
        $artifactGroup = $topicName -replace '^%(?:RETRY|DLQ)%', ''
        try {
            Remove-RocketMqConsumerGroup -ConsumerGroup $artifactGroup
            $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                    -n plainjournal-rocketmq-namesrv:9876 `
                    -c EcommerceCluster `
                    -t $topicName 2>&1)
            if ($LASTEXITCODE -ne 0 -or
                ($output -join "`n") -notmatch 'success') {
                throw "Unable to delete artifact topic: $($output -join "`n")"
            }
        }
        catch {
            $errors.Add("${topicName}: $($_.Exception.Message)")
        }
    }
    foreach ($topic in @($script:createdTopics)) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -t $topic 2>&1)
        if ($LASTEXITCODE -ne 0 -or
            ($output -join "`n") -notmatch 'success') {
            $errors.Add("${topic}: $($output -join "`n")")
            continue
        }
        try {
            Wait-Until `
                -Description "RocketMQ topic $topic removal" `
                -TimeoutSeconds 10 `
                -Condition {
                    $topic -notin @(Get-RocketMqTopics)
                }
        }
        catch {
            $errors.Add("${topic} residual check: $($_.Exception.Message)")
        }
    }
    if ($errors.Count -gt 0) {
        throw "RocketMQ cleanup failed: $($errors -join ' | ')"
    }
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $json = if ($response.Content) {
        $response.Content | ConvertFrom-Json
    }
    else {
        $null
    }
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        body = $json
    }
}

function Invoke-ConcurrentReviewSubmissions {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$IdempotencyKey,
        [Parameter(Mandatory)][string]$JsonBody,
        [int]$Count = 8
    )

    $client = [Net.Http.HttpClient]::new()
    $requests = [Collections.Generic.List[Net.Http.HttpRequestMessage]]::new()
    $tasks = [Collections.Generic.List[object]]::new()
    try {
        for ($index = 0; $index -lt $Count; $index++) {
            $request = [Net.Http.HttpRequestMessage]::new(
                [Net.Http.HttpMethod]::Post,
                $Uri)
            $request.Headers.Authorization =
                [Net.Http.Headers.AuthenticationHeaderValue]::new(
                    'Bearer',
                    $Token)
            $request.Headers.TryAddWithoutValidation(
                'Idempotency-Key',
                $IdempotencyKey) | Out-Null
            $request.Content = [Net.Http.StringContent]::new(
                $JsonBody,
                [Text.Encoding]::UTF8,
                'application/json')
            $requests.Add($request)
            $tasks.Add($client.SendAsync($request))
        }

        $results = [Collections.Generic.List[object]]::new()
        foreach ($task in $tasks) {
            $response = $task.GetAwaiter().GetResult()
            try {
                $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $results.Add([pscustomobject]@{
                    status = [int]$response.StatusCode
                    body = $content | ConvertFrom-Json
                })
            }
            finally {
                $response.Dispose()
            }
        }
        return @($results)
    }
    finally {
        foreach ($request in $requests) {
            $request.Dispose()
        }
        $client.Dispose()
    }
}

function Seed-CatalogProduct {
    Invoke-CatalogSql -Sql @"
INSERT INTO catalog_category
    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
VALUES
    ($($script:categoryId), NULL, 'Review verification',
     'review-verification-$($script:suffix)', 'ACTIVE', 1, 0,
     UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
INSERT INTO catalog_brand
    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
VALUES
    ($($script:brandId), 'Plain Journal Verification',
     'plain-journal-verification-$($script:suffix)', NULL, 'ACTIVE', 0,
     UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
INSERT INTO product_spu
    (id, category_id, brand_id, title, subtitle, description, status,
     version, created_at, updated_at)
VALUES
    ($($script:productId), $($script:categoryId), $($script:brandId),
     'Plain Journal commuter bag', NULL, 'M8 product review verification',
     'ACTIVE', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
INSERT INTO product_sku
    (id, spu_id, sku_code, name, spec_json, sale_price, market_price,
     status, version, created_at, updated_at)
VALUES
    ($($script:skuId), $($script:productId),
     'REVIEW-$($script:suffix)', 'Mist gray',
     '{"color":"mist-gray"}', 88.00, 108.00,
     'ACTIVE', 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
"@ | Out-Null
}

function Seed-ShippedTradeOrder {
    Invoke-TradeSql -Sql @"
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, total_amount, payment_deadline,
     close_reason, recovery_attempts, next_recovery_at, last_error, version,
     created_at, updated_at, original_amount, discount_amount, marketing_lock_no,
     order_source, source_reference)
VALUES
    ($($script:firstOrderId), '$($script:firstOrderNo)', $($script:customerId),
     'review-order-$($script:suffix)',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     'RSV-REVIEW-$($script:suffix)', 'PRIMARY', 1, 'SHIPPED', 88.00,
     DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY), NULL, 0, NULL, NULL, 3,
     UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 88.00, 0.00, NULL,
     'STANDARD', NULL);
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
     line_no, discount_amount, payable_amount)
VALUES
    ($($script:firstOrderItemId), $($script:firstOrderId),
     $($script:productId), $($script:skuId),
     'Plain Journal commuter bag', 'REVIEW-$($script:suffix)', 'Mist gray',
     '{"color":"mist-gray"}', NULL, 88.00, 1, 88.00, UTC_TIMESTAMP(3),
     1, 0.00, 88.00);
"@ | Out-Null
}

function Send-ShipmentSigned {
    $eventId = [Guid]::NewGuid().ToString()
    $envelope = [ordered]@{
        eventId = $eventId
        eventType = 'ShipmentSigned'
        aggregateType = 'FulfillmentOrder'
        aggregateId = "FUL-REVIEW-$($script:suffix)"
        aggregateVersion = 1
        occurredAt = [DateTimeOffset]::UtcNow.ToString('o')
        producer = 'fulfillment-service'
        payloadVersion = 1
        payload = [ordered]@{
            fulfillmentNo = "FUL-REVIEW-$($script:suffix)"
            orderNo = $script:firstOrderNo
            userId = $script:customerId
        }
    } | ConvertTo-Json -Depth 8 -Compress
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin sendMessage `
            -n plainjournal-rocketmq-namesrv:9876 `
            -t $script:logisticsTopic `
            -c ShipmentSigned `
            -k $eventId `
            -p $envelope 2>&1)
    if ($LASTEXITCODE -ne 0 -or
        ($output -join "`n") -notmatch 'SEND_OK') {
        throw "Unable to send ShipmentSigned: $($output -join "`n")"
    }
    return $eventId
}

function Insert-OutageOrderCompletedOutbox {
    param([Parameter(Mandatory)][string]$EventId)

    $envelope = [ordered]@{
        eventId = $EventId
        eventType = 'OrderCompleted'
        aggregateType = 'TradeOrder'
        aggregateId = $script:secondOrderNo
        aggregateVersion = 4
        occurredAt = [DateTimeOffset]::UtcNow.ToString('o')
        producer = 'trade-service'
        traceId = $null
        payloadVersion = 1
        payload = [ordered]@{
            orderNo = $script:secondOrderNo
            userId = $script:otherCustomerId
            reservationNo = "RSV-REVIEW-$($script:suffix)-2"
            status = 'COMPLETED'
            totalAmount = 88.00
            closeReason = $null
            paymentDeadline = [DateTimeOffset]::UtcNow.AddHours(1).ToString('o')
            items = @(
                [ordered]@{
                    lineNo = 1
                    productId = $script:productId
                    skuId = $script:skuId
                    productTitle = 'Plain Journal commuter bag'
                    skuCode = "REVIEW-$($script:suffix)"
                    skuName = 'Mist gray'
                    specJson = '{"color":"mist-gray"}'
                    imageObjectKey = $null
                    quantity = 1
                }
            )
        }
    } | ConvertTo-Json -Depth 10 -Compress
    $encodedEnvelope = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($envelope))
    Invoke-TradeSql -Sql @"
INSERT INTO outbox_event
    (id, event_type, aggregate_type, aggregate_id, aggregate_version,
     payload, status, attempts, next_attempt_at, claimed_at, published_at,
     last_error, created_at, updated_at, claim_owner, claim_until,
     destination_topic)
VALUES
    ('$EventId', 'OrderCompleted', 'TradeOrder', '$($script:secondOrderNo)', 4,
     CAST(FROM_BASE64('$encodedEnvelope') AS CHAR CHARACTER SET utf8mb4),
     'PENDING', 0, UTC_TIMESTAMP(3), NULL, NULL, NULL,
     UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), NULL, NULL, NULL);
"@ | Out-Null
}

$primaryError = $null
$cleanupErrors = [Collections.Generic.List[string]]::new()

try {
    Write-Host 'Stage 1/9: validating network, middleware, ports, and local configuration.'
    Write-VerificationTrace 'stage 1 begin'
    if (-not $SkipNetworkPreflight) {
        & 'D:\DevTools\Network\check-dev-network.ps1'
        if ($LASTEXITCODE -ne 0) {
            throw 'Local network preflight failed.'
        }
    }
    foreach ($port in @(
            $script:gatewayPort,
            $script:catalogPort,
            $script:tradePort)) {
        Assert-PortAvailable -Port $port
    }
    Import-LocalEnvironment
    Require-Environment -Names @(
        'MYSQL_ROOT_PASSWORD',
        'MYSQL_PORT',
        'REDIS_PORT',
        'REDIS_PASSWORD',
        'NACOS_ADMIN_PASSWORD',
        'IDENTITY_JWT_SECRET',
        'METRICS_SCRAPE_TOKEN',
        'TRADE_INTERNAL_SERVICE_TOKEN',
        'PAYMENT_INTERNAL_SERVICE_TOKEN',
        'CATALOG_DB_USER',
        'CATALOG_DB_PASSWORD',
        'TRADE_DB_USER',
        'TRADE_DB_PASSWORD',
        'MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD')

    Write-Host 'Stage 2/9: provisioning standard Nacos resources and isolated verification resources.'
    Write-VerificationTrace 'stage 2 begin'
    Push-Location $script:deployDirectory
    try {
        & .\bootstrap-resources.ps1
        if ($LASTEXITCODE -ne 0) {
            throw 'Middleware resource bootstrap failed.'
        }
    }
    finally {
        Pop-Location
    }
    New-VerificationDatabases
    New-RocketMqTopic -Topic $script:orderTopic
    New-RocketMqTopic -Topic $script:logisticsTopic
    New-RocketMqConsumerGroup -ConsumerGroup $script:catalogConsumerGroup
    New-RocketMqConsumerGroup -ConsumerGroup $script:tradeConsumerGroup

    Write-Host 'Stage 3/9: building and starting Catalog, Trade, and Gateway with real Nacos registration.'
    Write-VerificationTrace 'stage 3 begin'
    if (-not $SkipBuild) {
        & mvn -pl ecommerce-gateway,services/catalog-service,services/trade-service `
            -am -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw 'Maven package failed.'
        }
    }
    $gatewayJar = Join-Path $script:backendRoot `
        'ecommerce-gateway\target\ecommerce-gateway-0.1.0-SNAPSHOT.jar'
    $catalogJar = Join-Path $script:backendRoot `
        'services\catalog-service\target\catalog-service-0.1.0-SNAPSHOT.jar'
    $tradeJar = Join-Path $script:backendRoot `
        'services\trade-service\target\trade-service-0.1.0-SNAPSHOT.jar'

    $commonEnvironment = @{
        NACOS_HOST = '127.0.0.1'
        NACOS_CLIENT_PORT = '8848'
        NACOS_USERNAME = 'nacos'
        NACOS_ADMIN_PASSWORD = $env:NACOS_ADMIN_PASSWORD
        MYSQL_HOST = '127.0.0.1'
        MYSQL_PORT = $env:MYSQL_PORT
        REDIS_HOST = '127.0.0.1'
        REDIS_PORT = $env:REDIS_PORT
        REDIS_PASSWORD = $env:REDIS_PASSWORD
        IDENTITY_JWT_SECRET = $env:IDENTITY_JWT_SECRET
        METRICS_SCRAPE_TOKEN = $env:METRICS_SCRAPE_TOKEN
        TRADE_INTERNAL_SERVICE_TOKEN = $env:TRADE_INTERNAL_SERVICE_TOKEN
        PAYMENT_INTERNAL_SERVICE_TOKEN = $env:PAYMENT_INTERNAL_SERVICE_TOKEN
        SERVICE_IP = '127.0.0.1'
        APP_ENV = $script:namespace
        OTLP_TRACING_EXPORT_ENABLED = 'false'
        TRACING_SAMPLING_PROBABILITY = '0'
    }

    $catalogEnvironment = @{} + $commonEnvironment
    $catalogEnvironment += @{
        SERVICE_INSTANCE_ID = "$($script:runId)-catalog"
        CATALOG_SERVICE_PORT = [string]$script:catalogPort
        CATALOG_DB_NAME = $script:catalogDatabase
        CATALOG_DB_USER = $env:CATALOG_DB_USER
        CATALOG_DB_PASSWORD = $env:CATALOG_DB_PASSWORD
        MINIO_HOST = '127.0.0.1'
        MINIO_API_PORT = '19000'
        MINIO_ROOT_USER = $env:MINIO_ROOT_USER
        MINIO_ROOT_PASSWORD = $env:MINIO_ROOT_PASSWORD
        CATALOG_REVIEW_EVENTS_ENABLED = 'true'
        CATALOG_REVIEW_ORDER_TOPIC = $script:orderTopic
        CATALOG_REVIEW_CONSUMER_GROUP = $script:catalogConsumerGroup
        CATALOG_READ_REPLICA_ENABLED = 'false'
        CATALOG_CACHE_ENABLED = 'false'
        ROCKETMQ_ENDPOINTS = '127.0.0.1:18082'
    }
    Start-Application -Name 'catalog' -Jar $catalogJar `
        -Port $script:catalogPort `
        -Environment $catalogEnvironment `
        -ApplicationArguments @(
            "--ecommerce.catalog.review-events.topic=$($script:orderTopic)",
            "--ecommerce.catalog.review-events.consumer-group=$($script:catalogConsumerGroup)",
            '--ecommerce.catalog.review-events.enabled=true',
            '--ecommerce.catalog.review-events.initial-delay=500',
            '--ecommerce.catalog.review-events.fixed-delay=300',
            '--ecommerce.catalog.review-events.await-duration=5s',
            '--ecommerce.catalog.review-events.invisible-duration=20s',
            '--ecommerce.catalog.cache.enabled=false',
            '--ecommerce.catalog.read-replica.enabled=false'
        )
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:catalogPort)/actuator/health/liveness" `
        -ProcessName 'catalog'

    $tradeEnvironment = @{} + $commonEnvironment
    $tradeEnvironment += @{
        SERVICE_INSTANCE_ID = "$($script:runId)-trade"
        SERVICE_RELEASE_ID = 'm8-product-reviews-v1'
        TRADE_SERVICE_PORT = [string]$script:tradePort
        TRADE_DB_NAME = $script:tradeDatabase
        TRADE_DB_USER = $env:TRADE_DB_USER
        TRADE_DB_PASSWORD = $env:TRADE_DB_PASSWORD
        TRADE_OUTBOX_ENABLED = 'true'
        TRADE_OUTBOX_PUBLISHER_ID = "$($script:runId)-publisher"
        TRADE_ORDER_RECOVERY_ENABLED = 'false'
        TRADE_DISTRIBUTED_ID_ENABLED = 'false'
        TRADE_FLASH_SALE_CONSUMER_ENABLED = 'false'
        TRADE_PAYMENT_CONSUMER_ENABLED = 'false'
        TRADE_FULFILLMENT_CONSUMER_ENABLED = 'true'
        TRADE_AFTER_SALE_FULFILLMENT_CONSUMER_ENABLED = 'false'
        TRADE_AFTER_SALE_INVENTORY_CONSUMER_ENABLED = 'false'
        TRADE_REFUND_RESULT_CONSUMER_ENABLED = 'false'
        TRADE_RECONCILIATION_ENABLED = 'false'
        ROCKETMQ_PROXY_ENDPOINT = '127.0.0.1:18082'
    }
    Start-Application -Name 'trade' -Jar $tradeJar `
        -Port $script:tradePort `
        -Environment $tradeEnvironment `
        -ApplicationArguments @(
            "--ecommerce.trade.outbox.topic=$($script:orderTopic)",
            '--ecommerce.trade.outbox.enabled=true',
            '--ecommerce.trade.outbox.fixed-delay=500',
            '--ecommerce.trade.outbox.retry-delay=1s',
            '--ecommerce.trade.outbox.batch-size=10',
            '--ecommerce.trade.outbox.parallelism=2',
            "--ecommerce.trade.outbox.publisher-id=$($script:runId)-publisher",
            '--ecommerce.trade.order.recovery-enabled=false',
            '--ecommerce.trade.distributed-id.enabled=false',
            '--ecommerce.trade.client.service-discovery-enabled=false',
            '--ecommerce.trade.flash-sale-consumer.enabled=false',
            '--ecommerce.trade.payment-consumer.enabled=false',
            '--ecommerce.trade.fulfillment-consumer.enabled=true',
            "--ecommerce.trade.fulfillment-consumer.topic=$($script:logisticsTopic)",
            "--ecommerce.trade.fulfillment-consumer.consumer-group=$($script:tradeConsumerGroup)",
            '--ecommerce.trade.fulfillment-consumer.fixed-delay=300',
            '--ecommerce.trade.fulfillment-consumer.await-duration=5s',
            '--ecommerce.trade.fulfillment-consumer.invisible-duration=30s',
            '--ecommerce.trade.after-sale-fulfillment-consumer.enabled=false',
            '--ecommerce.trade.after-sale-inventory-consumer.enabled=false',
            '--ecommerce.trade.refund-result-consumer.enabled=false',
            '--ecommerce.trade.reconciliation.enabled=false'
        )
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:tradePort)/actuator/health/liveness" `
        -ProcessName 'trade'

    $gatewayEnvironment = @{} + $commonEnvironment
    $gatewayEnvironment += @{
        SERVICE_INSTANCE_ID = "$($script:runId)-gateway"
        GATEWAY_PORT = [string]$script:gatewayPort
    }
    Start-Application -Name 'gateway' -Jar $gatewayJar `
        -Port $script:gatewayPort `
        -Environment $gatewayEnvironment
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:gatewayPort)/actuator/health/liveness" `
        -ProcessName 'gateway'
    Wait-HttpOk `
        -Uri "http://127.0.0.1:$($script:gatewayPort)/api/v1/trade/status" `
        -ProcessName 'gateway'

    Write-Host 'Stage 4/9: seeding isolated product and shipped-order facts.'
    Write-VerificationTrace 'stage 4 begin'
    Seed-CatalogProduct
    Seed-ShippedTradeOrder
    $gatewayBase = "http://127.0.0.1:$($script:gatewayPort)"
    Wait-HttpOk `
        -Uri "$gatewayBase/api/v1/catalog/products/$($script:productId)/review-summary" `
        -ProcessName 'gateway'

    Write-Host 'Stage 5/9: driving ShipmentSigned through RocketMQ so Trade creates and publishes OrderCompleted.'
    Write-VerificationTrace 'stage 5 begin'
    $shipmentEventId = Send-ShipmentSigned
    Wait-Until -Description 'Trade order completion' -TimeoutSeconds 60 -Condition {
        (Get-TradeScalar -Sql @"
SELECT status FROM trade_order WHERE order_no = '$($script:firstOrderNo)';
"@) -eq 'COMPLETED'
    }
    $orderCompletedEventId = Get-TradeScalar -Sql @"
SELECT id FROM outbox_event
WHERE aggregate_id = '$($script:firstOrderNo)'
  AND event_type = 'OrderCompleted'
ORDER BY created_at DESC
LIMIT 1;
"@
    if ([string]::IsNullOrWhiteSpace($orderCompletedEventId)) {
        throw 'Trade did not create an OrderCompleted Outbox event.'
    }
    Wait-Until -Description 'Trade OrderCompleted Outbox publication' `
        -TimeoutSeconds 60 `
        -Condition {
            (Get-TradeScalar -Sql @"
SELECT status FROM outbox_event WHERE id = '$orderCompletedEventId';
"@) -eq 'PUBLISHED'
        }
    Wait-Until -Description 'Catalog review eligibility consumption' `
        -TimeoutSeconds 60 `
        -Condition {
            (Get-CatalogScalar -Sql @"
SELECT COUNT(*) FROM review_eligibility
WHERE source_event_id = '$orderCompletedEventId';
"@) -eq '1'
        }
    $payloadProducer = Get-TradeScalar -Sql @"
SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.producer'))
FROM outbox_event WHERE id = '$orderCompletedEventId';
"@
    if ($payloadProducer -ne 'trade-service') {
        throw 'OrderCompleted payload was not produced by Trade.'
    }
    $script:verification.eventFlow = [ordered]@{
        shipmentEventId = $shipmentEventId
        orderCompletedEventId = $orderCompletedEventId
        tradeOrderStatus = 'COMPLETED'
        tradeOutboxStatus = 'PUBLISHED'
        catalogEligibilityRows = 1
        producer = $payloadProducer
    }

    Write-Host 'Stage 6/9: verifying ownership, concurrent idempotency, summary, likes, replies, reports, and moderation.'
    Write-VerificationTrace 'stage 6 begin'
    $customerToken = New-AccessToken `
        -UserId $script:customerId `
        -Roles @('CUSTOMER')
    $otherCustomerToken = New-AccessToken `
        -UserId $script:otherCustomerId `
        -Roles @('CUSTOMER')
    $adminToken = New-AccessToken `
        -UserId $script:adminId `
        -Roles @('ADMIN')
    $customerHeaders = @{ Authorization = "Bearer $customerToken" }
    $otherHeaders = @{ Authorization = "Bearer $otherCustomerToken" }
    $adminHeaders = @{ Authorization = "Bearer $adminToken" }
    $eligibilityUri = (
        "$gatewayBase/api/v1/catalog/review-eligibilities?orderNo=" +
        [Uri]::EscapeDataString($script:firstOrderNo))
    $eligibilityResponse = Invoke-JsonRequest `
        -Method Get `
        -Uri $eligibilityUri `
        -Headers $customerHeaders
    $eligibilityData = $eligibilityResponse.body.data
    $eligibilityDataType = if ($null -eq $eligibilityData) {
        '<null>'
    }
    else {
        $eligibilityData.GetType().FullName
    }
    $eligibilityDatabaseFacts = @(Invoke-CatalogSql -Sql @"
SELECT id, order_no, user_id, status
FROM review_eligibility
WHERE source_event_id = '$orderCompletedEventId';
"@)
    $eligibilityDiagnostic = [ordered]@{
        httpStatus = $eligibilityResponse.status
        body = $eligibilityResponse.body
        dataType = $eligibilityDataType
        dataCount = @($eligibilityData).Count
        expectedUserId = [string]$script:customerId
        databaseFacts = $eligibilityDatabaseFacts
    }
    $script:failureContext.eligibilityRead = $eligibilityDiagnostic
    Write-VerificationTrace (
        'eligibility response: ' +
        ($eligibilityDiagnostic | ConvertTo-Json -Depth 12 -Compress))
    if ($eligibilityResponse.status -ne 200 -or
        @($eligibilityData).Count -ne 1) {
        throw (
            'The owning customer could not read the review eligibility. ' +
            ($eligibilityDiagnostic | ConvertTo-Json -Depth 12 -Compress))
    }
    $eligibilityId = [string]@($eligibilityData)[0].id
    $otherEligibility = Invoke-JsonRequest `
        -Method Get `
        -Uri $eligibilityUri `
        -Headers $otherHeaders
    if ($otherEligibility.status -ne 200 -or
        @($otherEligibility.body.data).Count -ne 0) {
        throw 'Review eligibility ownership isolation failed.'
    }
    $crossAccount = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/reviews" `
        -Headers (@{
            Authorization = "Bearer $otherCustomerToken"
            'Idempotency-Key' = "review-cross-$($script:suffix)"
        }) `
        -Body @{
            eligibilityId = $eligibilityId
            rating = 5
            content = 'Cross-account review must not be accepted.'
            anonymous = $false
        }
    if ($crossAccount.status -ne 404 -or
        $crossAccount.body.code -ne 'RESOURCE_NOT_FOUND') {
        throw 'Cross-account review submission was not rejected as not found.'
    }

    $reviewIdempotencyKey = "review-concurrent-$($script:suffix)"
    $reviewBody = @{
        eligibilityId = $eligibilityId
        rating = 5
        content = 'The received product matches the immutable order snapshot.'
        anonymous = $false
    } | ConvertTo-Json -Compress
    $concurrentResults = @(Invoke-ConcurrentReviewSubmissions `
            -Uri "$gatewayBase/api/v1/catalog/reviews" `
            -Token $customerToken `
            -IdempotencyKey $reviewIdempotencyKey `
            -JsonBody $reviewBody `
            -Count 8)
    $concurrentDiagnostic = @($concurrentResults | ForEach-Object {
        [ordered]@{
            status = $_.status
            body = $_.body
        }
    })
    $script:failureContext.concurrentReviewSubmissions = $concurrentDiagnostic
    Write-VerificationTrace (
        'concurrent review responses: ' +
        ($concurrentDiagnostic | ConvertTo-Json -Depth 12 -Compress))
    if (@($concurrentResults | Where-Object status -ne 200).Count -ne 0) {
        throw (
            'At least one concurrent review retry did not return HTTP 200. ' +
            ($concurrentDiagnostic | ConvertTo-Json -Depth 12 -Compress))
    }
    $reviewIds = @($concurrentResults |
        ForEach-Object { [string]$_.body.data.id } |
        Sort-Object -Unique)
    if ($reviewIds.Count -ne 1) {
        throw "Concurrent review retries produced $($reviewIds.Count) review IDs."
    }
    $reviewId = $reviewIds[0]
    if ((Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM product_review;') -ne '1' -or
        (Get-CatalogScalar -Sql 'SELECT review_count FROM product_review_summary;') -ne '1') {
        throw 'Concurrent review retries did not converge to one MySQL review fact.'
    }
    $summary = Invoke-JsonRequest `
        -Method Get `
        -Uri "$gatewayBase/api/v1/catalog/products/$($script:productId)/review-summary"
    if ($summary.status -ne 200 -or
        [long]$summary.body.data.reviewCount -ne 1 -or
        [decimal]$summary.body.data.averageRating -ne 5.0) {
        throw 'Published review summary is incorrect.'
    }

    $like = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/reviews/$reviewId/likes" `
        -Headers $otherHeaders
    $likeAgain = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/reviews/$reviewId/likes" `
        -Headers $otherHeaders
    if ($like.status -ne 200 -or
        $likeAgain.status -ne 200 -or
        [long]$likeAgain.body.data.likeCount -ne 1) {
        throw 'Review like idempotency failed.'
    }

    $replyCommand = "reply-$($script:suffix)"
    $replyHeaders = @{
        Authorization = "Bearer $adminToken"
        'Idempotency-Key' = $replyCommand
    }
    $reply = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/admin/reviews/$reviewId/reply" `
        -Headers $replyHeaders `
        -Body @{ content = 'The platform checked the order snapshot and product specification.' }
    $replyAgain = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/admin/reviews/$reviewId/reply" `
        -Headers $replyHeaders `
        -Body @{ content = 'The platform checked the order snapshot and product specification.' }
    if ($reply.status -ne 200 -or
        $replyAgain.status -ne 200 -or
        [string]$reply.body.data.reply.id -ne
        [string]$replyAgain.body.data.reply.id) {
        throw 'Administrator reply idempotency failed.'
    }

    $report = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/reviews/$reviewId/reports" `
        -Headers $otherHeaders `
        -Body @{
            reasonCode = 'FALSE_INFORMATION'
            detail = 'The platform should verify this statement against the snapshot.'
        }
    if ($report.status -ne 200 -or
        $report.body.data.status -ne 'OPEN') {
        throw 'Review report was not persisted.'
    }
    $reportId = [string]$report.body.data.id
    $moderationBody = @{
        commandId = "moderate-$($script:suffix)"
        resolution = 'UPHELD'
        reason = 'Verified against the immutable order line and current catalog facts.'
    }
    $moderation = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/admin/reviews/reports/$reportId/resolve" `
        -Headers $adminHeaders `
        -Body $moderationBody
    $moderationAgain = Invoke-JsonRequest `
        -Method Post `
        -Uri "$gatewayBase/api/v1/catalog/admin/reviews/reports/$reportId/resolve" `
        -Headers $adminHeaders `
        -Body $moderationBody
    if ($moderation.status -ne 200 -or
        $moderationAgain.status -ne 200 -or
        $moderation.body.data.reviewStatusAfter -ne 'HIDDEN') {
        throw 'Review moderation did not hide the upheld review.'
    }
    if ((Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM review_moderation_audit;') -ne '1') {
        throw 'Repeated moderation command produced duplicate audit rows.'
    }
    $hiddenSummary = Invoke-JsonRequest `
        -Method Get `
        -Uri "$gatewayBase/api/v1/catalog/products/$($script:productId)/review-summary"
    $publicReviews = Invoke-JsonRequest `
        -Method Get `
        -Uri "$gatewayBase/api/v1/catalog/products/$($script:productId)/reviews"
    if ([long]$hiddenSummary.body.data.reviewCount -ne 0 -or
        [long]$publicReviews.body.data.total -ne 0) {
        throw 'Hidden review still contributes to public reviews or rating summary.'
    }
    $script:verification.reviewLifecycle = [ordered]@{
        ownershipIsolation = $true
        crossAccountHttpStatus = $crossAccount.status
        concurrentRequests = $concurrentResults.Count
        distinctReviewIds = $reviewIds.Count
        persistedReviewRows = 1
        publishedReviewCountBeforeModeration = 1
        averageRatingBeforeModeration = 5.0
        likeCountAfterReplay = [long]$likeAgain.body.data.likeCount
        replyRows = [long](Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM review_reply;')
        reportRows = [long](Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM review_report;')
        moderationAuditRows = 1
        reviewStatusAfterModeration = 'HIDDEN'
        publishedReviewCountAfterModeration = 0
    }

    Write-Host 'Stage 7/9: stopping RocketMQ Proxy and proving Trade Outbox retains an unknown result.'
    Write-VerificationTrace 'stage 7 begin'
    docker stop plainjournal-rocketmq-proxy *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to stop RocketMQ Proxy.'
    }
    $script:proxyStopped = $true
    Wait-Until -Description 'RocketMQ Proxy port to close' `
        -TimeoutSeconds 20 `
        -Condition {
            -not (Get-NetTCPConnection -State Listen -LocalPort 18082 `
                -ErrorAction SilentlyContinue)
    }
    $outageEventId = [Guid]::NewGuid().ToString()
    Insert-OutageOrderCompletedOutbox -EventId $outageEventId
    $storedOutageSpec = Get-TradeScalar -Sql @"
SELECT JSON_UNQUOTE(
    JSON_EXTRACT(payload, '$.payload.items[0].specJson'))
FROM outbox_event WHERE id = '$outageEventId';
"@
    $script:failureContext.outagePayload = [ordered]@{
        eventId = $outageEventId
        storedSpecJson = $storedOutageSpec
    }
    if ($storedOutageSpec -ne '{"color":"mist-gray"}') {
        throw 'The injected outage Outbox payload was not stored losslessly.'
    }
    Wait-Until -Description 'Trade Outbox retry during RocketMQ outage' `
        -TimeoutSeconds 45 `
        -Condition {
            $state = @(Invoke-TradeSql -Sql @"
SELECT status, attempts
FROM outbox_event WHERE id = '$outageEventId';
"@)
            if ($state.Count -eq 0) {
                return $false
            }
            $parts = [string]$state[0] -split "`t"
            return $parts[0] -eq 'PENDING' -and [int]$parts[1] -ge 1
        }
    if ((Get-CatalogScalar -Sql @"
SELECT COUNT(*) FROM review_eligibility
WHERE source_event_id = '$outageEventId';
"@) -ne '0') {
        throw 'Catalog consumed the outage event before Broker access recovered.'
    }
    $attemptsDuringOutage = [int](Get-TradeScalar -Sql @"
SELECT attempts FROM outbox_event WHERE id = '$outageEventId';
"@)

    Write-Host 'Stage 8/9: restoring RocketMQ Proxy and verifying Outbox and Catalog convergence.'
    Write-VerificationTrace 'stage 8 begin'
    docker start plainjournal-rocketmq-proxy *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to restart RocketMQ Proxy.'
    }
    $script:proxyStopped = $false
    Wait-TcpPort -Port 18082 -TimeoutSeconds 60
    Wait-Until -Description 'Trade Outbox recovery publication' `
        -TimeoutSeconds 90 `
        -Condition {
            (Get-TradeScalar -Sql @"
SELECT status FROM outbox_event WHERE id = '$outageEventId';
"@) -eq 'PUBLISHED'
        }
    Wait-Until -Description 'Catalog recovery eligibility' `
        -TimeoutSeconds 90 `
        -Condition {
            (Get-CatalogScalar -Sql @"
SELECT COUNT(*) FROM review_eligibility
WHERE source_event_id = '$outageEventId';
"@) -eq '1'
        }
    $script:verification.outageRecovery = [ordered]@{
        fault = 'RocketMQ Proxy stopped'
        outboxStatusDuringOutage = 'PENDING'
        attemptsDuringOutage = $attemptsDuringOutage
        eligibilityRowsDuringOutage = 0
        outboxStatusAfterRecovery = 'PUBLISHED'
        eligibilityRowsAfterRecovery = 1
    }

    Write-Host 'Stage 9/9: recording evidence and checking isolated-resource counts.'
    Write-VerificationTrace 'stage 9 begin'
    $script:verification.environment = [ordered]@{
        runId = $script:runId
        catalogDatabase = $script:catalogDatabase
        tradeDatabase = $script:tradeDatabase
        orderTopic = $script:orderTopic
        logisticsTopic = $script:logisticsTopic
        catalogConsumerGroup = $script:catalogConsumerGroup
        tradeConsumerGroup = $script:tradeConsumerGroup
        gatewayPort = $script:gatewayPort
        catalogPort = $script:catalogPort
        tradePort = $script:tradePort
        nacosBackedGatewayRoute = $true
    }
    $script:verification.finalDatabaseState = [ordered]@{
        catalogConsumedEvents = [long](Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM consumed_event;')
        catalogEligibilities = [long](Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM review_eligibility;')
        catalogReviews = [long](Get-CatalogScalar -Sql 'SELECT COUNT(*) FROM product_review;')
        tradeCompletedOrders = [long](Get-TradeScalar -Sql "SELECT COUNT(*) FROM trade_order WHERE status = 'COMPLETED';")
        tradePublishedOutbox = [long](Get-TradeScalar -Sql "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED';")
        tradeUnpublishedOutbox = [long](Get-TradeScalar -Sql "SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED';")
    }
    if ($script:verification.finalDatabaseState.tradeUnpublishedOutbox -ne 0) {
        throw 'Trade still has unpublished verification Outbox rows.'
    }
    $verificationPath = Join-Path $script:runDirectory 'verification.json'
    $script:verification | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $verificationPath -Encoding utf8
    Write-VerificationTrace "verification succeeded: $verificationPath"
}
catch {
    $primaryError = $_
    Write-VerificationTrace "verification failed: $($_.Exception.Message)"
    [ordered]@{
        message = $_.Exception.Message
        scriptStackTrace = $_.ScriptStackTrace
        position = [string]$_.InvocationInfo.PositionMessage
        context = $script:failureContext
    } | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'failure.json') -Encoding utf8
}
finally {
    if ($script:proxyStopped) {
        try {
            docker start plainjournal-rocketmq-proxy *> $null
            if ($LASTEXITCODE -ne 0) {
                throw 'RocketMQ Proxy restart returned a non-zero exit code.'
            }
            Wait-TcpPort -Port 18082 -TimeoutSeconds 60
            $script:proxyStopped = $false
        }
        catch {
            $cleanupErrors.Add(
                "RocketMQ Proxy restore: $($_.Exception.Message)")
        }
    }
    try {
        Stop-ManagedProcesses
    }
    catch {
        $cleanupErrors.Add("application cleanup: $($_.Exception.Message)")
    }
    try {
        if ($script:createdConsumerGroups.Count -gt 0 -or
            $script:createdTopics.Count -gt 0) {
            Remove-RocketMqResources
        }
    }
    catch {
        $cleanupErrors.Add("RocketMQ resource cleanup: $($_.Exception.Message)")
    }
    try {
        Remove-VerificationDatabases
    }
    catch {
        $cleanupErrors.Add("database cleanup: $($_.Exception.Message)")
    }

    $residualConsumerGroups = @()
    $residualRocketMqTopics = @()
    try {
        $residualConsumerGroups = @(
            $script:createdConsumerGroups |
            Where-Object {
                Test-RocketMqConsumerGroupResidual -ConsumerGroup $_
            })
        $topicNames = @(Get-RocketMqTopics)
        $residualRocketMqTopics = @(
            $topicNames |
            Where-Object {
                $topicName = $_
                $script:createdTopics -contains $topicName -or
                ($script:createdConsumerGroups | Where-Object {
                    $topicName.Contains($_) -and
                    ($topicName.StartsWith('%RETRY%') -or
                        $topicName.StartsWith('%DLQ%'))
                })
            } |
            Sort-Object -Unique)
        if ($residualConsumerGroups.Count -gt 0) {
            $cleanupErrors.Add(
                "residual RocketMQ consumer groups: " +
                ($residualConsumerGroups -join ', '))
        }
        if ($residualRocketMqTopics.Count -gt 0) {
            $cleanupErrors.Add(
                "residual RocketMQ topics: " +
                ($residualRocketMqTopics -join ', '))
        }
    }
    catch {
        $cleanupErrors.Add(
            "RocketMQ residual inspection: $($_.Exception.Message)")
    }

    $residualPorts = @(Get-NetTCPConnection -State Listen `
            -ErrorAction SilentlyContinue |
        Where-Object LocalPort -in @(
            $script:gatewayPort,
            $script:catalogPort,
            $script:tradePort))
    $residualJvms = @(Get-CimInstance Win32_Process `
            -Filter "Name='java.exe'" `
            -ErrorAction SilentlyContinue |
        Where-Object {
            [string]$_.CommandLine -like "*$($script:runId)*" -or
            [string]$_.CommandLine -like '*catalog-service-0.1.0-SNAPSHOT.jar*' -or
            [string]$_.CommandLine -like '*trade-service-0.1.0-SNAPSHOT.jar*' -or
            [string]$_.CommandLine -like '*ecommerce-gateway-0.1.0-SNAPSHOT.jar*'
        })
    [ordered]@{
        cleanupErrors = @($cleanupErrors)
        residualPorts = $residualPorts.Count
        residualJvms = $residualJvms.Count
        residualRocketMqConsumerGroups = @($residualConsumerGroups)
        residualRocketMqTopics = @($residualRocketMqTopics)
        rocketMqProxyRunning = (
            docker inspect -f '{{.State.Running}}' plainjournal-rocketmq-proxy 2>$null
        ) -eq 'true'
    } | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'cleanup.json') -Encoding utf8
}

if ($cleanupErrors.Count -gt 0) {
    $message = "Verification cleanup failed: $($cleanupErrors -join ' | ')"
    if ($null -ne $primaryError) {
        throw "$($primaryError.Exception.Message) | $message"
    }
    throw $message
}
if ($null -ne $primaryError) {
    throw $primaryError
}

$resultPath = Join-Path $script:runDirectory 'verification.json'
Write-Host "M8 product review verification passed: $resultPath"
Get-Content -LiteralPath $resultPath -Raw
