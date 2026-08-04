#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(100, 10000)]
    [int]$AdmissionRequests = 1000,
    [ValidateRange(1, 500)]
    [int]$AdmissionConcurrency = 100,
    [ValidateRange(1, 10000)]
    [int]$AdmissionLimit = 100,
    [ValidateRange(30, 1000)]
    [int]$MixedRequests = 300,
    [ValidateRange(1, 200)]
    [int]$MixedConcurrency = 30,
    [switch]$EnableMqFaultInjection,
    [switch]$PreserveFixtureOnFailure,
    [switch]$SkipPackage,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:runId = "m6q$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"
$script:namespace = $script:runId
$script:flashSaleTopic = "ecommerce-flash-sale-events-$($script:runId)"
$script:tradeConsumerGroup = "trade-flash-sale-admission-$($script:runId)"
$script:marketingConsumerGroup = "marketing-flash-sale-result-$($script:runId)"
$script:processes = [ordered]@{}
$script:environmentRestores = [Collections.Generic.List[object]]::new()
$script:activityNos = [Collections.Generic.List[string]]::new()
$script:normalOrderNos = [Collections.Generic.List[string]]::new()
$script:paymentNos = [Collections.Generic.List[string]]::new()
$script:refundNos = [Collections.Generic.List[string]]::new()
$script:redisWasStopped = $false
$script:proxyWasStopped = $false
$script:tradeStarted = $false
$script:flashSaleTopicCreated = $false
$script:flashSaleConsumerGroups = [Collections.Generic.List[string]]::new()

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) { continue }
        [Environment]::SetEnvironmentVariable(
            $trimmed.Substring(0, $separator).Trim(),
            $trimmed.Substring($separator + 1).Trim(),
            'Process')
    }
}

function Assert-RequiredEnvironment {
    $required = @(
        'IDENTITY_JWT_SECRET', 'TRADE_INTERNAL_SERVICE_TOKEN',
        'PAYMENT_INTERNAL_SERVICE_TOKEN', 'METRICS_SCRAPE_TOKEN',
        'MOCK_PAYMENT_CALLBACK_SECRET', 'NACOS_ADMIN_PASSWORD', 'REDIS_PASSWORD',
        'CATALOG_DB_NAME', 'CATALOG_DB_USER', 'CATALOG_DB_PASSWORD',
        'IDENTITY_DB_NAME', 'IDENTITY_DB_USER', 'IDENTITY_DB_PASSWORD',
        'INVENTORY_DB_NAME', 'INVENTORY_DB_USER', 'INVENTORY_DB_PASSWORD',
        'TRADE_DB_NAME', 'TRADE_DB_USER', 'TRADE_DB_PASSWORD',
        'PAYMENT_DB_NAME', 'PAYMENT_DB_USER', 'PAYMENT_DB_PASSWORD',
        'MARKETING_DB_NAME', 'MARKETING_DB_USER', 'MARKETING_DB_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
        })
    if ($missing.Count -gt 0) {
        throw "Missing required local settings: $($missing -join ', ')"
    }
}

function Invoke-DbSql {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$Capture
    )
    $arguments = @(
        'exec', '-i', '-e', "MYSQL_PWD=$Password", 'plainjournal-mysql', 'mysql',
        "--user=$User", '--default-character-set=utf8mb4',
        '--batch', '--skip-column-names', $Database
    )
    $output = @($Sql | docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed for ${Database}: $($output -join "`n")"
    }
    if ($Capture) { return $output }
}

function Test-DbTable {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$TableName
    )
    $escapedTableName = $TableName.Replace("'", "''")
    $result = @(Invoke-DbSql `
            -Database $Database `
            -User $User `
            -Password $Password `
            -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$escapedTableName';" `
            -Capture)
    return ($result.Count -gt 0 -and [int]$result[0] -eq 1)
}

function Remove-RedisNamespace {
    $pattern = "ecommerce:$($script:namespace):*"
    $lua = @'
local cursor = '0'
local deleted = 0
repeat
  local result = redis.call('SCAN', cursor, 'MATCH', ARGV[1], 'COUNT', 1000)
  cursor = result[1]
  for _, key in ipairs(result[2]) do
    deleted = deleted + redis.call('DEL', key)
  end
until cursor == '0'
return deleted
'@
    $output = @(
        docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" `
            plainjournal-redis redis-cli EVAL $lua 0 $pattern 2>&1
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Redis namespace cleanup failed for $($script:namespace): $($output -join "`n")"
    }
}

function Get-ActivityRedisMeta {
    param([Parameter(Mandatory)][string]$ActivityNo)

    $pattern = "ecommerce:$($script:namespace):marketing:flash-sale:activity:${ActivityNo}:meta"
    $keys = @(
        docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" `
            plainjournal-redis redis-cli --raw --scan --pattern $pattern
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Redis metadata scan failed for $ActivityNo."
    }
    if ($keys.Count -ne 1) {
        throw "Expected one Redis metadata key for $ActivityNo, found $($keys.Count): $($keys -join ', ')"
    }
    $raw = @(
        docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" `
            plainjournal-redis redis-cli --raw HGETALL $keys[0]
    )
    if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0 -or $raw.Count % 2 -ne 0) {
        throw "Redis metadata is invalid for $ActivityNo."
    }
    $values = [ordered]@{ key = $keys[0] }
    for ($index = 0; $index -lt $raw.Count; $index += 2) {
        $values[[string]$raw[$index]] = [string]$raw[$index + 1]
    }
    return [pscustomobject]$values
}

function New-FlashSaleTopic {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin updateTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $script:flashSaleTopic `
            -r 4 `
            -w 4 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Unable to create isolated RocketMQ topic $($script:flashSaleTopic): $($output -join "`n")"
    }
    $script:flashSaleTopicCreated = $true
}

function Remove-FlashSaleTopic {
    if (-not $script:flashSaleTopicCreated) { return }
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
            -n plainjournal-rocketmq-namesrv:9876 `
            -c EcommerceCluster `
            -t $script:flashSaleTopic 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Unable to delete isolated RocketMQ topic $($script:flashSaleTopic): $($output -join "`n")"
    }
    $script:flashSaleTopicCreated = $false
}

function New-FlashSaleConsumerGroup {
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
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
        throw "Unable to create RocketMQ consumer group ${ConsumerGroup}: $($output -join "`n")"
    }
    $script:flashSaleConsumerGroups.Add($ConsumerGroup)
}

function Remove-FlashSaleConsumerGroups {
    $errors = [Collections.Generic.List[string]]::new()
    $consumerGroups = @($script:flashSaleConsumerGroups)
    foreach ($consumerGroup in $consumerGroups) {
        $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                -n plainjournal-rocketmq-namesrv:9876 `
                -c EcommerceCluster `
                -g $consumerGroup `
                -r true 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
            $errors.Add("${consumerGroup}: $($output -join "`n")")
        }
    }
    $topicOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin topicList `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $errors.Add("topic list: $($topicOutput -join "`n")")
    } else {
        $consumerArtifacts = @($topicOutput | ForEach-Object { $_.Trim() } |
            Where-Object {
                $topicName = $_
                $consumerGroups | Where-Object {
                    $topicName.Contains($_) -and
                    ($topicName.StartsWith('%RETRY%') -or $topicName.StartsWith('%DLQ%'))
                }
            } | Sort-Object -Unique)
        foreach ($topicName in $consumerArtifacts) {
            $artifactGroup = $topicName -replace '^%(?:RETRY|DLQ)%', ''
            $groupOutput = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteSubGroup `
                    -n plainjournal-rocketmq-namesrv:9876 `
                    -c EcommerceCluster `
                    -g $artifactGroup `
                    -r true 2>&1)
            if ($LASTEXITCODE -ne 0 -or
                ($groupOutput -join "`n") -notmatch 'success') {
                $errors.Add("${artifactGroup}: $($groupOutput -join "`n")")
            }
            $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin deleteTopic `
                    -n plainjournal-rocketmq-namesrv:9876 `
                    -c EcommerceCluster `
                    -t $topicName 2>&1)
            if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'success') {
                $errors.Add("${topicName}: $($output -join "`n")")
            }
        }
    }
    Start-Sleep -Seconds 2
    $metadata = $null
    for ($attempt = 1; $attempt -le 5 -and $null -eq $metadata; $attempt++) {
        $offsetOutput = @(docker exec plainjournal-rocketmq-broker sh -lc `
                'cat /home/rocketmq/store/config/consumerOffset.json' 2>&1)
        if ($LASTEXITCODE -eq 0) {
            try {
                $metadata = ($offsetOutput -join "`n") |
                    ConvertFrom-Json -AsHashtable
            }
            catch {
                if ($attempt -eq 5) {
                    $errors.Add(
                        "consumer offset parse: $($_.Exception.Message)")
                }
            }
        }
        elseif ($attempt -eq 5) {
            $errors.Add(
                "consumer offset inspection: $($offsetOutput -join "`n")")
        }
        if ($null -eq $metadata) {
            Start-Sleep -Milliseconds 500
        }
    }
    if ($null -ne $metadata) {
        $residualGroups = @($consumerGroups | Where-Object {
                $suffix = "@$_"
                @($metadata.offsetTable.Keys | Where-Object {
                        $_.EndsWith($suffix, [StringComparison]::Ordinal)
                    }).Count -gt 0
            })
        if ($residualGroups.Count -gt 0) {
            $errors.Add("residual consumer offsets: $($residualGroups -join ', ')")
        }
    }
    if ($errors.Count -gt 0) {
        throw "Unable to delete RocketMQ consumer artifacts: $($errors -join ' | ')"
    }
    $script:flashSaleConsumerGroups.Clear()
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
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 10
    }
    $response = Invoke-WebRequest @parameters
    [pscustomobject]@{
        status = [int]$response.StatusCode
        payload = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    }
}

function Assert-Response {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][int]$Status,
        [Parameter(Mandatory)][string]$Code,
        [Parameter(Mandatory)][string]$Message
    )
    $actualCode = if ($null -ne $Response.payload -and
        $Response.payload.PSObject.Properties.Name -contains 'code') {
        [string]$Response.payload.code
    } else {
        '<no-json-code>'
    }
    if ($Response.status -ne $Status -or $actualCode -ne $Code) {
        throw "$Message Expected=HTTP $Status/$Code Actual=HTTP $($Response.status)/$actualCode"
    }
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    param([Parameter(Mandatory)][long]$UserId, [string[]]$Roles = @('CUSTOMER'))
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + 21600
        jti = [Guid]::NewGuid().ToString()
        roles = $Roles
    } | ConvertTo-Json -Compress
    $unsigned = "$(ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))).$(ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload)))"
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:IDENTITY_JWT_SECRET))
    try {
        return "$unsigned.$(ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned))))"
    } finally {
        $hmac.Dispose()
    }
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $Port is already in use by process $($listener[0].OwningProcess)." }
}

function Set-ProcessEnvironment {
    param([Parameter(Mandatory)][string]$Name, [AllowNull()][string]$Value)
    $script:environmentRestores.Add([pscustomobject]@{
            name = $Name
            value = [Environment]::GetEnvironmentVariable($Name, 'Process')
        })
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Restore-ProcessEnvironment {
    for ($index = $script:environmentRestores.Count - 1; $index -ge 0; $index--) {
        $entry = $script:environmentRestores[$index]
        [Environment]::SetEnvironmentVariable($entry.name, $entry.value, 'Process')
    }
    $script:environmentRestores.Clear()
}

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment,
        [hashtable]$SystemProperties = @{}
    )
    if (-not (Test-Path -LiteralPath $Jar)) { throw "Missing application artifact: $Jar" }
    foreach ($entry in $Environment.GetEnumerator()) {
        Set-ProcessEnvironment -Name $entry.Key -Value ([string]$entry.Value)
    }
    try {
        $arguments = @('-Xms256m', '-Xmx256m', '-XX:ActiveProcessorCount=4')
        foreach ($entry in $SystemProperties.GetEnumerator() | Sort-Object Key) {
            $arguments += "-D$($entry.Key)=$($entry.Value)"
        }
        $arguments += @('-jar', $Jar)
        $process = Start-Process -FilePath $script:javaPath `
            -ArgumentList $arguments -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden -PassThru
        $script:processes[$Name] = [pscustomobject]@{ process = $process; jar = $Jar }
    } finally {
        Restore-ProcessEnvironment
    }
}

function Stop-Application {
    param([Parameter(Mandatory)][string]$Name)
    if (-not $script:processes.Contains($Name)) { return }
    $managed = $script:processes[$Name]
    $jarName = [IO.Path]::GetFileName($managed.jar)
    $candidates = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -eq 'java.exe' -and
            $_.CommandLine -like "*$jarName*"
        })
    if ($candidates.Count -eq 0) { return }
    foreach ($candidate in $candidates) {
        Stop-Process -Id ([int]$candidate.ProcessId) -Force -ErrorAction Stop
    }
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $remaining = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -eq 'java.exe' -and
                $_.CommandLine -like "*$jarName*"
            })
        if ($remaining.Count -eq 0) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Application process did not exit: $Name/$($remaining.ProcessId -join ',')"
}

function Stop-Applications {
    $errors = [Collections.Generic.List[string]]::new()
    $names = @($script:processes.Keys | ForEach-Object { [string]$_ })
    foreach ($name in $names) {
        try {
            Stop-Application -Name $name
        } catch {
            $errors.Add("${name}: $($_.Exception.Message)")
        }
    }
    if ($errors.Count -gt 0) {
        throw "Application cleanup failed: $($errors -join ' | ')"
    }
}

function Wait-HttpOk {
    param([Parameter(Mandatory)][string]$Uri, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri."
}

function Invoke-Load {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$RequestCount,
        [Parameter(Mandatory)][int]$ConcurrentWorkers,
        [Parameter(Mandatory)][object[]]$Scenarios
    )
    $directory = Join-Path $script:runDirectory $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configPath = Join-Path $directory 'config.json'
    $resultPath = Join-Path $directory 'result.json'
    [ordered]@{
        schemaVersion = 1
        name = $Name
        requests = $RequestCount
        concurrency = $ConcurrentWorkers
        timeoutMs = 30000
        maxErrorRate = 0
        scenarios = $Scenarios
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $configPath -Encoding utf8
    try {
        $output = @(& $script:nodePath `
                (Join-Path $PSScriptRoot 'm5-http-load-runner.mjs') $configPath $resultPath 2>&1)
        $output | Set-Content -LiteralPath (Join-Path $directory 'runner.log') -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw "Load run failed: $Name. $($output -join "`n")" }
    } finally {
        Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    }
    Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
}

function New-Fixture {
    $entropy = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() % 1000000
    $script:fixture = [ordered]@{
        categoryId = [long](7600000000000000000 + $entropy * 1000000 + 1)
        brandId = [long](7600000000000000000 + $entropy * 1000000 + 2)
        productId = [long](7600000000000000000 + $entropy * 1000000 + 10)
        flashSkuId = [long](7600000000000000000 + $entropy * 1000000 + 11)
        normalSkuId = [long](7600000000000000000 + $entropy * 1000000 + 12)
        mediaId = [long](7600000000000000000 + $entropy * 1000000 + 20)
        userBase = [long](7600000000000000000 + $entropy * 1000000 + 1000)
        addressBase = [long](7600000000000000000 + $entropy * 1000000 + 3000)
        inventoryBase = [long](7600000000000000000 + $entropy * 1000000 + 5000)
        paymentBase = [long](7600000000000000000 + $entropy * 1000000 + 7000)
        refundBase = [long](7600000000000000000 + $entropy * 1000000 + 8000)
        warehouseCreated = $false
    }
    $script:fixture.userCount = [Math]::Max(1000, $AdmissionRequests)
    $script:fixture.categorySlug = "m6-$($script:runId)-category"
    $script:fixture.brandSlug = "m6-$($script:runId)-brand"
    $script:fixture.emailSuffix = "@$($script:runId.ToLowerInvariant()).plainjournal.local"
}

function Seed-Fixture {
    $now = [DateTimeOffset]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss.fff')
    $flashInventoryCapacity = $AdmissionLimit + $(if ($EnableMqFaultInjection) { 1 } else { 0 })
    $catalog = [Text.StringBuilder]::new()
    [void]$catalog.AppendLine("INSERT INTO catalog_category (id,parent_id,name,slug,status,sort_order,version,created_at,updated_at) VALUES ($($script:fixture.categoryId),NULL,'M6 Category','$($script:fixture.categorySlug)','ACTIVE',0,0,'$now','$now');")
    [void]$catalog.AppendLine("INSERT INTO catalog_brand (id,name,slug,status,version,created_at,updated_at) VALUES ($($script:fixture.brandId),'M6 Brand','$($script:fixture.brandSlug)','ACTIVE',0,'$now','$now');")
    [void]$catalog.AppendLine("INSERT INTO product_spu (id,category_id,brand_id,title,subtitle,description,status,version,created_at,updated_at) VALUES ($($script:fixture.productId),$($script:fixture.categoryId),$($script:fixture.brandId),'M6 Queue Product','Flash-sale fixture','M6 isolated fixture','ACTIVE',0,'$now','$now');")
    [void]$catalog.AppendLine("INSERT INTO product_sku (id,spu_id,sku_code,name,spec_json,sale_price,market_price,status,version,created_at,updated_at) VALUES ($($script:fixture.flashSkuId),$($script:fixture.productId),'$($script:runId)-FLASH','Flash SKU','{`"variant`":`"flash`}',9.90,19.90,'ACTIVE',0,'$now','$now');")
    [void]$catalog.AppendLine("INSERT INTO product_sku (id,spu_id,sku_code,name,spec_json,sale_price,market_price,status,version,created_at,updated_at) VALUES ($($script:fixture.normalSkuId),$($script:fixture.productId),'$($script:runId)-NORMAL','Normal SKU','{`"variant`":`"normal`}',19.90,19.90,'ACTIVE',0,'$now','$now');")
    [void]$catalog.AppendLine("INSERT INTO product_media (id,spu_id,sku_id,object_key,mime_type,size_bytes,sort_order,created_at) VALUES ($($script:fixture.mediaId),$($script:fixture.productId),$($script:fixture.flashSkuId),'m6/$($script:runId).png','image/png',1,0,'$now');")
    Invoke-DbSql -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER -Password $env:CATALOG_DB_PASSWORD -Sql $catalog.ToString()

    $identity = [Text.StringBuilder]::new()
    for ($index = 0; $index -lt $script:fixture.userCount; $index++) {
        $userId = $script:fixture.userBase + $index
        $addressId = $script:fixture.addressBase + $index
        $email = "m6.$($script:runId).$($index.ToString('0000'))$($script:fixture.emailSuffix)"
        [void]$identity.AppendLine("INSERT INTO user_account (id,email,password_hash,display_name,status,version,created_at,updated_at) VALUES ($userId,'$email','`$2b`$12`$KREIAX0KbHhyffdoXFSQfeS/27kwWbzT0tcYWXQ2RZBJM20NihfL2','M6 User $index','ACTIVE',0,'$now','$now');")
        [void]$identity.AppendLine("INSERT INTO user_role (user_id,role_id,created_at) SELECT $userId,id,'$now' FROM identity_role WHERE code='CUSTOMER';")
        [void]$identity.AppendLine("INSERT INTO user_address (id,user_id,recipient_name,phone,province,province_code,city,city_code,district,district_code,detail_address,postal_code,is_default,version,created_at,updated_at) VALUES ($addressId,$userId,'M6 User $index','1380000$($index.ToString('0000'))','Zhejiang','330000','Hangzhou','330100','Xihu','330106','M6 Queue Street $index','310000',TRUE,0,'$now','$now');")
    }
    Invoke-DbSql -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER -Password $env:IDENTITY_DB_PASSWORD -Sql $identity.ToString()

    $warehouseRows = @(Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER -Password $env:INVENTORY_DB_PASSWORD -Sql "SELECT id FROM warehouse WHERE code='PRIMARY';" -Capture)
    if ($warehouseRows.Count -eq 0 -or -not $warehouseRows[0]) {
        $script:fixture.warehouseId = [long]($script:fixture.inventoryBase - 1)
        Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD -Sql @"
INSERT INTO warehouse (id,code,name,status,version,created_at,updated_at)
VALUES ($($script:fixture.warehouseId),'PRIMARY','M6 Primary Warehouse','ACTIVE',0,'$now','$now');
"@
        $script:fixture.warehouseCreated = $true
    } else {
        $script:fixture.warehouseId = [long]$warehouseRows[0]
    }
    $inventory = @"
INSERT INTO inventory_balance (id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES ($($script:fixture.inventoryBase),$($script:fixture.warehouseId),$($script:fixture.flashSkuId),$flashInventoryCapacity,0,0,'$now','$now');
INSERT INTO inventory_balance (id,warehouse_id,sku_id,on_hand,reserved,version,created_at,updated_at)
VALUES ($($script:fixture.inventoryBase + 1),$($script:fixture.warehouseId),$($script:fixture.normalSkuId),200,0,0,'$now','$now');
"@
    Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER -Password $env:INVENTORY_DB_PASSWORD -Sql $inventory

    $payment = [Text.StringBuilder]::new()
    for ($index = 0; $index -lt 10; $index++) {
        $paymentId = $script:fixture.paymentBase + $index
        $refundId = $script:fixture.refundBase + $index
        $paymentNo = "M6P-$($script:runId)-$index"
        $refundNo = "M6R-$($script:runId)-$index"
        $orderNo = "M6-REFUND-ORDER-$($script:runId)-$index"
        $afterSaleNo = "M6-AFTER-SALE-$($script:runId)-$index"
        [void]$script:refundNos.Add($refundNo)
        [void]$payment.AppendLine("INSERT INTO payment_order (id,payment_no,order_no,user_id,reservation_no,idempotency_key,request_hash,channel,status,amount,version,created_at,updated_at) VALUES ($paymentId,'$paymentNo','$orderNo',$($script:fixture.userBase + $index),'M6-RESERVATION-$($script:runId)-$index','m6-$($script:runId)-fixture-payment-$index','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','MOCK','SUCCESS',19.90,0,'$now','$now');")
        [void]$payment.AppendLine("INSERT INTO refund_order (id,refund_no,after_sale_no,order_no,payment_id,payment_no,user_id,request_hash,channel,status,amount,version,created_at,updated_at,request_status,request_attempts) VALUES ($refundId,'$refundNo','$afterSaleNo','$orderNo',$paymentId,'$paymentNo',$($script:fixture.userBase + $index),'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','MOCK','PROCESSING',19.90,0,'$now','$now','PENDING',0);")
    }
    Invoke-DbSql -Database $env:PAYMENT_DB_NAME -User $env:PAYMENT_DB_USER -Password $env:PAYMENT_DB_PASSWORD -Sql $payment.ToString()
}

function Remove-Fixture {
    $activityList = if ($script:activityNos.Count -gt 0) {
        ($script:activityNos | ForEach-Object { "'$($_.Replace("'", "''"))'" }) -join ','
    } else { "''" }
    if ($script:tradeStarted) {
        $hasFlashSaleOrderRequest = Test-DbTable `
            -Database $env:TRADE_DB_NAME `
            -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD `
            -TableName 'flash_sale_order_request'
        if ($hasFlashSaleOrderRequest) {
            $trade = @"
DELETE oi FROM order_item oi JOIN trade_order o ON o.id=oi.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE h FROM order_status_history h JOIN trade_order o ON o.id=h.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE oa FROM order_address_snapshot oa JOIN trade_order o ON o.id=oa.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE ps FROM order_price_snapshot ps JOIN trade_order o ON o.id=ps.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE oi FROM order_item oi JOIN trade_order o ON o.id=oi.order_id
 WHERE o.source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList));
DELETE h FROM order_status_history h JOIN trade_order o ON o.id=h.order_id
 WHERE o.source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList));
DELETE oa FROM order_address_snapshot oa JOIN trade_order o ON o.id=oa.order_id
 WHERE o.source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList));
DELETE ps FROM order_price_snapshot ps JOIN trade_order o ON o.id=ps.order_id
 WHERE o.source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList));
DELETE oe FROM outbox_event oe WHERE oe.aggregate_id IN (
 SELECT order_no FROM trade_order WHERE idempotency_key LIKE 'm6-$($script:runId)-%'
 UNION
 SELECT order_no FROM trade_order
 WHERE source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList))
);
DELETE FROM outbox_event
WHERE aggregate_id IN (
 SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList)
);
DELETE FROM trade_order
 WHERE source_reference IN (SELECT request_token FROM flash_sale_order_request WHERE activity_no IN ($activityList));
DELETE FROM flash_sale_order_request WHERE activity_no IN ($activityList);
DELETE FROM trade_order WHERE idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE FROM consumed_event WHERE consumer_group='$($script:tradeConsumerGroup)';
"@
        } else {
            # A failed pre-migration run may have started the service but cannot
            # have created flash-sale orders. Clean only the ordinary fixture
            # rows and skip every queue-table reference.
            $trade = @"
DELETE oi FROM order_item oi JOIN trade_order o ON o.id=oi.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE h FROM order_status_history h JOIN trade_order o ON o.id=h.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE oa FROM order_address_snapshot oa JOIN trade_order o ON o.id=oa.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE ps FROM order_price_snapshot ps JOIN trade_order o ON o.id=ps.order_id WHERE o.idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE oe FROM outbox_event oe WHERE oe.aggregate_id IN (
 SELECT order_no FROM trade_order WHERE idempotency_key LIKE 'm6-$($script:runId)-%'
);
DELETE FROM trade_order WHERE idempotency_key LIKE 'm6-$($script:runId)-%';
DELETE FROM consumed_event WHERE consumer_group='$($script:tradeConsumerGroup)';
"@
        }
        Invoke-DbSql -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER -Password $env:TRADE_DB_PASSWORD -Sql $trade
    }

    $inventory = @"
DROP TEMPORARY TABLE IF EXISTS m6_fixture_reservation;
CREATE TEMPORARY TABLE m6_fixture_reservation AS
SELECT DISTINCT r.id, r.reservation_no
FROM inventory_reservation r
JOIN inventory_reservation_item i ON i.reservation_id = r.id
WHERE i.sku_id IN ($($script:fixture.flashSkuId),$($script:fixture.normalSkuId));
DELETE FROM inventory_return
WHERE reservation_no IN (
 SELECT reservation_no FROM m6_fixture_reservation
);
DELETE FROM outbox_event
WHERE aggregate_id IN (
 SELECT reservation_no FROM m6_fixture_reservation
);
DELETE FROM reconciliation_record
WHERE reference_no IN (
 SELECT reservation_no FROM m6_fixture_reservation
);
DELETE i
FROM inventory_reservation_item i
JOIN m6_fixture_reservation r ON r.id = i.reservation_id;
DELETE r
FROM inventory_reservation r
JOIN m6_fixture_reservation f ON f.id = r.id;
DROP TEMPORARY TABLE m6_fixture_reservation;
DELETE FROM stock_movement
WHERE sku_id IN ($($script:fixture.flashSkuId),$($script:fixture.normalSkuId));
DELETE FROM stock_adjustment
WHERE sku_id IN ($($script:fixture.flashSkuId),$($script:fixture.normalSkuId));
DELETE FROM inventory_balance WHERE id IN ($($script:fixture.inventoryBase),$($script:fixture.inventoryBase + 1));
"@
    Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER -Password $env:INVENTORY_DB_PASSWORD -Sql $inventory
    if ($script:fixture.warehouseCreated) {
        Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER `
            -Password $env:INVENTORY_DB_PASSWORD `
            -Sql "DELETE FROM warehouse WHERE id=$($script:fixture.warehouseId);"
    }

    $payment = @"
DELETE FROM refund_callback_log WHERE refund_no IN (
 SELECT refund_no FROM refund_order
 WHERE id BETWEEN $($script:fixture.refundBase) AND $($script:fixture.refundBase + 9)
);
DELETE FROM refund_transaction WHERE refund_id IN (
 SELECT id FROM refund_order
 WHERE id BETWEEN $($script:fixture.refundBase) AND $($script:fixture.refundBase + 9)
);
DELETE FROM refund_dispatch_retry_audit WHERE refund_no IN (
 SELECT refund_no FROM refund_order
 WHERE id BETWEEN $($script:fixture.refundBase) AND $($script:fixture.refundBase + 9)
);
DELETE FROM outbox_event WHERE aggregate_id IN (
 SELECT refund_no FROM refund_order
 WHERE id BETWEEN $($script:fixture.refundBase) AND $($script:fixture.refundBase + 9)
);
DELETE FROM refund_order WHERE id BETWEEN $($script:fixture.refundBase) AND $($script:fixture.refundBase + 9);
DELETE FROM payment_callback_log WHERE payment_no IN (
 SELECT payment_no FROM payment_order
 WHERE idempotency_key LIKE 'm6-$($script:runId)-%'
);
DELETE FROM payment_transaction WHERE payment_id IN (
 SELECT id FROM payment_order
 WHERE idempotency_key LIKE 'm6-$($script:runId)-%'
);
DELETE FROM outbox_event WHERE aggregate_id IN (
 SELECT payment_no FROM payment_order
 WHERE idempotency_key LIKE 'm6-$($script:runId)-%'
);
DELETE FROM payment_order WHERE idempotency_key LIKE 'm6-$($script:runId)-%';
"@
    Invoke-DbSql -Database $env:PAYMENT_DB_NAME -User $env:PAYMENT_DB_USER -Password $env:PAYMENT_DB_PASSWORD -Sql $payment

    $hasFlashSaleAdmission = Test-DbTable `
        -Database $env:MARKETING_DB_NAME `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -TableName 'flash_sale_admission'
    $hasFlashSaleOutbox = Test-DbTable `
        -Database $env:MARKETING_DB_NAME `
        -User $env:MARKETING_DB_USER `
        -Password $env:MARKETING_DB_PASSWORD `
        -TableName 'flash_sale_outbox_event'
    $marketing = if ($hasFlashSaleAdmission -and $hasFlashSaleOutbox) {
@"
DELETE FROM flash_sale_outbox_event WHERE aggregate_id IN (SELECT request_token FROM flash_sale_admission WHERE activity_no IN ($activityList));
DELETE FROM flash_sale_admission WHERE activity_no IN ($activityList);
DELETE FROM flash_sale_activity WHERE activity_no IN ($activityList);
DELETE FROM consumed_event WHERE consumer_group='$($script:marketingConsumerGroup)';
"@
    } elseif ($hasFlashSaleAdmission) {
@"
DELETE FROM flash_sale_admission WHERE activity_no IN ($activityList);
DELETE FROM flash_sale_activity WHERE activity_no IN ($activityList);
DELETE FROM consumed_event WHERE consumer_group='$($script:marketingConsumerGroup)';
"@
    } else {
@"
DELETE FROM flash_sale_activity WHERE activity_no IN ($activityList);
DELETE FROM consumed_event WHERE consumer_group='$($script:marketingConsumerGroup)';
"@
    }
    Invoke-DbSql -Database $env:MARKETING_DB_NAME -User $env:MARKETING_DB_USER -Password $env:MARKETING_DB_PASSWORD -Sql $marketing

    $identity = @"
DELETE FROM user_role WHERE user_id BETWEEN $($script:fixture.userBase) AND $($script:fixture.userBase + $($script:fixture.userCount) - 1);
DELETE FROM user_address WHERE id BETWEEN $($script:fixture.addressBase) AND $($script:fixture.addressBase + $($script:fixture.userCount) - 1);
DELETE FROM user_account WHERE id BETWEEN $($script:fixture.userBase) AND $($script:fixture.userBase + $($script:fixture.userCount) - 1);
"@
    Invoke-DbSql -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER -Password $env:IDENTITY_DB_PASSWORD -Sql $identity

    $catalog = @"
DELETE FROM product_media WHERE id=$($script:fixture.mediaId);
DELETE FROM product_sku WHERE id IN ($($script:fixture.flashSkuId),$($script:fixture.normalSkuId));
DELETE FROM product_spu WHERE id=$($script:fixture.productId);
DELETE FROM catalog_brand WHERE id=$($script:fixture.brandId);
DELETE FROM catalog_category WHERE id=$($script:fixture.categoryId);
"@
    Invoke-DbSql -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER -Password $env:CATALOG_DB_PASSWORD -Sql $catalog
    Remove-RedisNamespace
}

function New-Activity {
    param([int]$Limit, [hashtable]$AdminHeaders)
    $response = Invoke-JsonRequest -Method Post `
        -Uri 'http://127.0.0.1:18107/api/v1/marketing/admin/flash-sales' `
        -Headers $AdminHeaders `
        -Body @{
            name = "M6 Queue $($script:runId)"
            productId = [string]$script:fixture.productId
            skuId = [string]$script:fixture.flashSkuId
            salePrice = '9.90'
            admissionLimit = $Limit
            startsAt = [DateTimeOffset]::UtcNow.AddSeconds(-10).ToString('o')
            endsAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
        }
    Assert-Response $response 200 'OK' 'Create activity failed.'
    $activityNo = [string]$response.payload.data.activityNo
    $script:activityNos.Add($activityNo)
    $published = Invoke-JsonRequest -Method Post `
        -Uri "http://127.0.0.1:18107/api/v1/marketing/admin/flash-sales/$activityNo/publish" `
        -Headers $AdminHeaders
    Assert-Response $published 200 'OK' 'Publish activity failed.'
    return $activityNo
}

function Get-QueueState {
    $marketingRows = @(Invoke-DbSql -Database $env:MARKETING_DB_NAME -User $env:MARKETING_DB_USER -Password $env:MARKETING_DB_PASSWORD -Sql @"
SELECT
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='ADMISSION_PENDING'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='ADMISSION_REJECTED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='QUEUED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='ORDER_CREATED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='FAILED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='RESULT_UNKNOWN'),
 (SELECT COUNT(*) FROM flash_sale_outbox_event WHERE aggregate_id IN (
     SELECT request_token FROM flash_sale_admission
     WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"}))
 )),
 (SELECT COUNT(*) FROM flash_sale_outbox_event WHERE status <> 'PUBLISHED' AND aggregate_id IN (
     SELECT request_token FROM flash_sale_admission
     WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"}))
 ));
"@ -Capture)
    $tradeRows = @(Invoke-DbSql -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER -Password $env:TRADE_DB_PASSWORD -Sql @"
SELECT
 (SELECT COUNT(*) FROM flash_sale_order_request WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='PROCESSING'),
 (SELECT COUNT(*) FROM flash_sale_order_request WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='ORDER_CREATED'),
 (SELECT COUNT(*) FROM flash_sale_order_request WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='FAILED'),
 (SELECT COUNT(*) FROM flash_sale_order_request WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"})) AND status='NEEDS_ATTENTION'),
 (SELECT COUNT(*) FROM trade_order WHERE order_source='FLASH_SALE' AND source_reference IN (
     SELECT request_token FROM flash_sale_order_request
     WHERE activity_no IN ($(if($script:activityNos.Count -gt 0){($script:activityNos | ForEach-Object {"'$_'"}) -join ','}else{"''"}))
 ));
"@ -Capture)
    $inventoryRows = @(Invoke-DbSql -Database $env:INVENTORY_DB_NAME -User $env:INVENTORY_DB_USER -Password $env:INVENTORY_DB_PASSWORD -Sql @"
SELECT on_hand,reserved FROM inventory_balance
WHERE warehouse_id=$($script:fixture.warehouseId) AND sku_id=$($script:fixture.flashSkuId);
"@ -Capture)
    [ordered]@{
        marketingPending = [int]$marketingRows[0].Split("`t")[0]
        marketingRejected = [int]$marketingRows[0].Split("`t")[1]
        marketingQueued = [int]$marketingRows[0].Split("`t")[2]
        marketingOrderCreated = [int]$marketingRows[0].Split("`t")[3]
        marketingFailed = [int]$marketingRows[0].Split("`t")[4]
        marketingResultUnknown = [int]$marketingRows[0].Split("`t")[5]
        marketingOutboxTotal = [int]$marketingRows[0].Split("`t")[6]
        marketingOutboxUnpublished = [int]$marketingRows[0].Split("`t")[7]
        tradeProcessing = [int]$tradeRows[0].Split("`t")[0]
        tradeOrderCreated = [int]$tradeRows[0].Split("`t")[1]
        tradeFailed = [int]$tradeRows[0].Split("`t")[2]
        tradeNeedsAttention = [int]$tradeRows[0].Split("`t")[3]
        tradeOrders = [int]$tradeRows[0].Split("`t")[4]
        inventoryOnHand = [int]$inventoryRows[0].Split("`t")[0]
        inventoryReserved = [int]$inventoryRows[0].Split("`t")[1]
    }
}

function Wait-QueueConverged {
    param(
        [Parameter(Mandatory)][int]$ExpectedAdmissions,
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = Get-QueueState
        $terminalAdmissions = $state.marketingOrderCreated +
            $state.marketingFailed +
            $state.marketingResultUnknown
        if ($state.tradeProcessing -eq 0 -and
            $state.marketingPending -eq 0 -and
            $state.marketingQueued -eq 0 -and
            $state.marketingOutboxUnpublished -eq 0 -and
            $terminalAdmissions -eq $ExpectedAdmissions) {
            return $state
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "M6 queue did not converge: $(($state | ConvertTo-Json -Compress))"
}

function Get-MqProgress {
    $output = @(docker exec plainjournal-rocketmq-broker sh mqadmin consumerProgress `
            -n plainjournal-rocketmq-namesrv:9876 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read RocketMQ consumer progress: $($output -join "`n")"
    }
    [ordered]@{
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        raw = $output
        brokerBacklogLines = @($output | Where-Object {
                $_ -match [regex]::Escape($script:flashSaleTopic) -or
                $_ -match [regex]::Escape($script:tradeConsumerGroup)
            })
    }
}

function Get-MetricsSnapshot {
    param([int]$Port, [string]$Service)
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } -TimeoutSec 10
    $lines = @($response.Content -split "`r?`n" | Where-Object {
            $_ -match 'ecommerce_flash_sale_processing|ecommerce_outbox_pending_events|ecommerce_outbox_oldest_age_seconds'
        })
    [ordered]@{ service = $Service; capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); lines = $lines }
}

$envFile = Join-Path $script:repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing local middleware configuration: $envFile" }
Import-DotEnv -Path $envFile
Assert-RequiredEnvironment
$nodeCandidate = Get-Command node -ErrorAction SilentlyContinue
$script:nodePath = if ($nodeCandidate) {
    $nodeCandidate.Source
} elseif (Test-Path -LiteralPath 'D:\Node.js\current\node.exe') {
    'D:\Node.js\current\node.exe'
} else {
    throw 'Node.js was not found on PATH or at D:\Node.js\current\node.exe.'
}
$javaHomeCandidate = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $null
}
$script:javaPath = if ($javaHomeCandidate -and (Test-Path -LiteralPath $javaHomeCandidate)) {
    $javaHomeCandidate
} else {
    (Get-Command java -ErrorAction Stop).Source
}
New-Fixture

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $script:backendRoot ".run\m6-flash-sale-queue-$($script:runId)"
}
$script:runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null

$primaryError = $null
$cleanupFailure = $null
$summary = $null
try {
    $networkLog = Join-Path $script:runDirectory 'network-preflight.log'
    $networkOutput = @(& 'D:\DevTools\Network\check-dev-network.ps1' *>&1)
    $networkExitCode = $LASTEXITCODE
    $networkOutput | Out-String -Width 240 |
        Set-Content -LiteralPath $networkLog -Encoding utf8
    $requiredContainers = @(
        'plainjournal-mysql', 'plainjournal-redis', 'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv', 'plainjournal-rocketmq-broker', 'plainjournal-rocketmq-proxy', 'plainjournal-minio'
    )
    $runningContainers = @(docker ps --format '{{.Names}}')
    $missing = @($requiredContainers | Where-Object { $runningContainers -notcontains $_ })
    if ($missing.Count -gt 0) { throw "Required containers are not running: $($missing -join ', ')" }
    if ($networkExitCode -ne 0) {
        throw "Local network preflight failed with exit code $networkExitCode. See $networkLog."
    }
    foreach ($port in @(18000,18101,18102,18103,18104,18105,18107)) {
        Assert-PortAvailable $port
    }

    if (-not $SkipPackage) {
        $mavenArguments = @(
            '-q',
            '-f', (Join-Path $script:backendRoot 'pom.xml'),
            '-pl', 'ecommerce-gateway,services/identity-service,services/catalog-service,services/inventory-service,services/trade-service,services/payment-service,services/marketing-service',
            '-am',
            '-DskipTests',
            'package'
        )
        & mvn @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw "M6 queue package failed with exit code $LASTEXITCODE." }
    }
    New-FlashSaleTopic
    New-FlashSaleConsumerGroup -ConsumerGroup $script:tradeConsumerGroup
    New-FlashSaleConsumerGroup -ConsumerGroup $script:marketingConsumerGroup
    Seed-Fixture

    $baseEnv = @{
        APP_ENV = $script:namespace
        NACOS_HOST = '127.0.0.1'
        NACOS_USERNAME = 'nacos'
        SERVICE_IP = '127.0.0.1'
    }
    $jars = @{
        identity = Join-Path $script:backendRoot 'services\identity-service\target\identity-service-1.0.2-SNAPSHOT.jar'
        catalog = Join-Path $script:backendRoot 'services\catalog-service\target\catalog-service-1.0.2-SNAPSHOT.jar'
        inventory = Join-Path $script:backendRoot 'services\inventory-service\target\inventory-service-1.0.2-SNAPSHOT.jar'
        trade = Join-Path $script:backendRoot 'services\trade-service\target\trade-service-1.0.2-SNAPSHOT.jar'
        payment = Join-Path $script:backendRoot 'services\payment-service\target\payment-service-1.0.2-SNAPSHOT.jar'
        marketing = Join-Path $script:backendRoot 'services\marketing-service\target\marketing-service-1.0.2-SNAPSHOT.jar'
        gateway = Join-Path $script:backendRoot 'ecommerce-gateway\target\ecommerce-gateway-1.0.2-SNAPSHOT.jar'
    }
    Start-Application -Name identity -Jar $jars.identity -Environment $baseEnv
    Wait-HttpOk 'http://127.0.0.1:18101/actuator/health/liveness'
    Start-Application -Name catalog -Jar $jars.catalog -Environment $baseEnv
    Wait-HttpOk 'http://127.0.0.1:18102/actuator/health/liveness'
    Start-Application -Name inventory -Jar $jars.inventory -Environment $baseEnv
    Wait-HttpOk 'http://127.0.0.1:18103/actuator/health/liveness'
    Start-Application -Name marketing -Jar $jars.marketing -Environment $baseEnv `
        -SystemProperties @{
            'ecommerce.marketing.order-consumer.enabled' = 'false'
            'ecommerce.marketing.flash-sale-outbox.topic' = $script:flashSaleTopic
            'ecommerce.marketing.flash-sale-result-consumer.topic' = $script:flashSaleTopic
            'ecommerce.marketing.flash-sale-result-consumer.consumer-group' = $script:marketingConsumerGroup
        }
    Wait-HttpOk 'http://127.0.0.1:18107/actuator/health/liveness'
    Start-Application -Name payment -Jar $jars.payment -Environment $baseEnv `
        -SystemProperties @{
            'ecommerce.payment.client.service-discovery-enabled' = 'false'
            'ecommerce.payment.client.trade-base-url' = 'http://127.0.0.1:18104'
            'ecommerce.payment.client.trade-payment-context.bulkhead-max-wait' = '250ms'
            'ecommerce.payment.refund-consumer.enabled' = 'false'
            'ecommerce.payment.refund-dispatch.enabled' = 'false'
            'ecommerce.payment.reconciliation.enabled' = 'false'
        }
    Wait-HttpOk 'http://127.0.0.1:18105/actuator/health/liveness'
    Start-Application -Name gateway -Jar $jars.gateway -Environment $baseEnv
    Wait-HttpOk 'http://127.0.0.1:18000/actuator/health/liveness'

    $adminHeaders = @{ Authorization = "Bearer $(New-AccessToken ($script:fixture.userBase) @('ADMIN'))" }
    $activityNo = New-Activity -Limit $AdmissionLimit -AdminHeaders $adminHeaders
    $admissionVariants = @(0..($AdmissionRequests - 1) | ForEach-Object {
            $userId = $script:fixture.userBase + $_
            [ordered]@{
                url = "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$activityNo/admissions"
                headers = @{
                    Authorization = "Bearer $(New-AccessToken $userId)"
                    'Idempotency-Key' = "m6-$($script:runId)-admission-$($_.ToString('0000'))"
                }
                body = @{ addressId = [string]($script:fixture.addressBase + $_) }
            }
        })

    # Consumer groups were created through mqadmin before publication. Trade is
    # intentionally absent here, so no dead SimpleConsumer long poll can claim
    # the first queued message while the backlog is being built.
    $admissionResult = Invoke-Load -Name 'admission-1000' `
        -RequestCount $AdmissionRequests -ConcurrentWorkers $AdmissionConcurrency `
        -Scenarios @([ordered]@{
                name = 'flash-sale-admission'
                method = 'POST'
                expectedStatuses = @(202, 409)
                variants = $admissionVariants
            })
    $accepted = [int]($admissionResult.aggregate.statusCodes.'202' ?? 0)
    $soldOut = [int]($admissionResult.aggregate.statusCodes.'409' ?? 0)
    if ($accepted -ne $AdmissionLimit -or $soldOut -ne ($AdmissionRequests - $AdmissionLimit)) {
        throw "Admission quota mismatch: accepted=$accepted soldOut=$soldOut."
    }
    $activityRedisMeta = Get-ActivityRedisMeta -ActivityNo $activityNo
    $redisAdmitted = [int]$activityRedisMeta.admitted
    $redisRemaining = [int]$activityRedisMeta.remaining
    if ($redisAdmitted -ne $accepted -or
        $redisRemaining -ne ($AdmissionLimit - $accepted)) {
        throw "Redis quota fact mismatch: admitted=$redisAdmitted remaining=$redisRemaining."
    }
    $stoppedState = Get-QueueState
    if ($stoppedState.marketingPending -ne 0 -or
        $stoppedState.marketingRejected -ne $soldOut -or
        $stoppedState.marketingQueued -ne $accepted -or
        $stoppedState.marketingOrderCreated -ne 0 -or
        $stoppedState.marketingFailed -ne 0 -or
        $stoppedState.marketingResultUnknown -ne 0 -or
        $stoppedState.marketingOutboxTotal -ne $accepted) {
        throw "Stopped-consumer admission facts mismatch: $(($stoppedState | ConvertTo-Json -Compress))"
    }
    $mqBefore = Get-MqProgress

    $faultAccepted = 0
    if ($EnableMqFaultInjection) {
        docker stop plainjournal-rocketmq-proxy *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to stop RocketMQ Proxy.' }
        $script:proxyWasStopped = $true
        $faultActivity = New-Activity -Limit 1 -AdminHeaders $adminHeaders
        $faultUser = $script:fixture.userBase + 999
        $fault = Invoke-JsonRequest -Method Post `
            -Uri "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$faultActivity/admissions" `
            -Headers @{
                Authorization = "Bearer $(New-AccessToken $faultUser)"
                'Idempotency-Key' = "m6-$($script:runId)-mq-fault"
            } -Body @{ addressId = [string]($script:fixture.addressBase + 999) }
        Assert-Response $fault 202 'OK' 'MQ fault admission was not accepted.'
        $faultAccepted = 1
        Start-Sleep -Seconds 3
        $faultState = Get-QueueState
        if ($faultState.marketingOutboxUnpublished -le 0) {
            throw 'Marketing Outbox did not retain an unpublished event while RocketMQ Proxy was stopped.'
        }
        docker start plainjournal-rocketmq-proxy *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to restart RocketMQ Proxy.' }
        $script:proxyWasStopped = $false
        Start-Sleep -Seconds 5
    } else {
        $faultState = $null
    }

    Start-Application -Name trade -Jar $jars.trade -Environment $baseEnv `
        -SystemProperties @{
            'ecommerce.trade.outbox.flash-sale-topic' = $script:flashSaleTopic
            'ecommerce.trade.flash-sale-consumer.topic' = $script:flashSaleTopic
            'ecommerce.trade.flash-sale-consumer.consumer-group' = $script:tradeConsumerGroup
            'ecommerce.trade.client.service-discovery-enabled' = 'false'
            'ecommerce.trade.client.catalog-base-url' = 'http://127.0.0.1:18102'
            'ecommerce.trade.client.identity-base-url' = 'http://127.0.0.1:18101'
            'ecommerce.trade.client.inventory-base-url' = 'http://127.0.0.1:18103'
            'ecommerce.trade.client.marketing-base-url' = 'http://127.0.0.1:18107'
            'ecommerce.trade.client.synchronous-boundary.bulkhead-max-wait' = '250ms'
            'ecommerce.trade.client.marketing-pricing-lock.bulkhead-max-wait' = '250ms'
            'ecommerce.trade.payment-consumer.enabled' = 'false'
            'ecommerce.trade.fulfillment-consumer.enabled' = 'false'
            'ecommerce.trade.after-sale-fulfillment-consumer.enabled' = 'false'
            'ecommerce.trade.after-sale-inventory-consumer.enabled' = 'false'
            'ecommerce.trade.refund-result-consumer.enabled' = 'false'
            'ecommerce.trade.reconciliation.enabled' = 'false'
        }
    $script:tradeStarted = $true
    Wait-HttpOk 'http://127.0.0.1:18104/actuator/health/liveness'
    # Gateway health only proves the gateway JVM is alive. Poll a public Trade
    # status route as well so the Nacos-backed route is registered before the
    # mixed peak begins.
    Wait-HttpOk 'http://127.0.0.1:18000/api/v1/trade/status'

    # Build ordinary pending orders before the mixed peak; payments are created during the peak.
    $ordinaryUserCount = 30
    for ($index = 0; $index -lt $ordinaryUserCount; $index++) {
        $userId = $script:fixture.userBase + $index
        $body = @{
            addressId = [string]($script:fixture.addressBase + $index)
            items = @(@{
                    productId = [string]$script:fixture.productId
                    skuId = [string]$script:fixture.normalSkuId
                    quantity = 1
                })
            benefitNos = @()
        }
        $normal = Invoke-JsonRequest -Method Post `
            -Uri 'http://127.0.0.1:18000/api/v1/trade/orders' `
            -Headers @{
                Authorization = "Bearer $(New-AccessToken $userId)"
                'Idempotency-Key' = "m6-$($script:runId)-normal-$index"
            } -Body $body
        Assert-Response $normal 200 'OK' 'Ordinary order setup failed.'
        $script:normalOrderNos.Add([string]$normal.payload.data.orderNo)
    }

    $mixedOrderVariants = @(0..99 | ForEach-Object {
            $userId = $script:fixture.userBase + 100 + $_
            [ordered]@{
                url = 'http://127.0.0.1:18000/api/v1/trade/orders'
                headers = @{
                    Authorization = "Bearer $(New-AccessToken $userId)"
                    'Idempotency-Key' = "m6-$($script:runId)-peak-order-$($_.ToString('000'))"
                }
                body = @{
                    addressId = [string]($script:fixture.addressBase + 100 + $_)
                    items = @(@{
                            productId = [string]$script:fixture.productId
                            skuId = [string]$script:fixture.normalSkuId
                            quantity = 1
                        })
                    benefitNos = @()
                }
            }
        })
    $paymentVariants = @(0..($ordinaryUserCount - 1) | ForEach-Object {
            $userId = $script:fixture.userBase + $_
            [ordered]@{
                url = 'http://127.0.0.1:18000/api/v1/payment/payments'
                headers = @{
                    Authorization = "Bearer $(New-AccessToken $userId)"
                    'Idempotency-Key' = "m6-$($script:runId)-payment-$($_.ToString('000'))"
                }
                body = @{
                    orderNo = $script:normalOrderNos[$_]
                    channel = 'MOCK'
                }
            }
        })
    $refundVariants = @(0..9 | ForEach-Object {
            $userId = $script:fixture.userBase + $_
            [ordered]@{
                url = "http://127.0.0.1:18000/api/v1/payment/refunds/$($script:refundNos[$_])"
                headers = @{ Authorization = "Bearer $(New-AccessToken $userId)" }
            }
        })
    $mixed = Invoke-Load -Name 'mixed-peak' -RequestCount $MixedRequests `
        -ConcurrentWorkers $MixedConcurrency -Scenarios @(
            [ordered]@{
                name = 'ordinary-order'
                method = 'POST'
                expectedStatuses = @(200)
                variants = $mixedOrderVariants
                weight = 3
            },
            [ordered]@{
                name = 'payment-create'
                method = 'POST'
                expectedStatuses = @(200)
                variants = $paymentVariants
                weight = 1
            },
            [ordered]@{
                name = 'refund-query'
                method = 'GET'
                expectedStatuses = @(200)
                variants = $refundVariants
                weight = 1
            })
    $expectedFlashOrders = $accepted + $faultAccepted
    $finalState = Wait-QueueConverged -ExpectedAdmissions $expectedFlashOrders
    $mqAfter = Get-MqProgress
    $tradeMetrics = Get-MetricsSnapshot -Port 18104 -Service 'trade-service'
    $marketingMetrics = Get-MetricsSnapshot -Port 18107 -Service 'marketing-service'

    if ($finalState.tradeOrderCreated -ne $expectedFlashOrders -or
        $finalState.tradeOrders -ne $expectedFlashOrders -or
        $finalState.inventoryReserved -ne $expectedFlashOrders -or
        $finalState.marketingPending -ne 0 -or
        $finalState.marketingRejected -ne $soldOut -or
        $finalState.marketingQueued -ne 0 -or
        $finalState.marketingOrderCreated -ne $expectedFlashOrders -or
        $finalState.marketingOutboxTotal -ne $expectedFlashOrders) {
        throw "Flash-sale final fact mismatch: $(($finalState | ConvertTo-Json -Compress))"
    }
    if ($finalState.marketingFailed -ne 0 -or
        $finalState.marketingResultUnknown -ne 0 -or
        $finalState.tradeFailed -ne 0 -or
        $finalState.tradeNeedsAttention -ne 0) {
        throw "Flash-sale processing has failures: $(($finalState | ConvertTo-Json -Compress))"
    }

    $summary = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $script:runId
        namespace = $script:namespace
        fixture = $script:fixture
        networkPreflightExitCode = $networkExitCode
        parameters = [ordered]@{
            admissionRequests = $AdmissionRequests
            admissionConcurrency = $AdmissionConcurrency
            admissionLimit = $AdmissionLimit
            mixedRequests = $MixedRequests
            mixedConcurrency = $MixedConcurrency
            mqFaultInjection = [bool]$EnableMqFaultInjection
        }
        admission = [ordered]@{
            accepted = $accepted
            faultAccepted = $faultAccepted
            soldOut = $soldOut
            redis = [ordered]@{
                admitted = $redisAdmitted
                remaining = $redisRemaining
            }
            stoppedTradeState = $stoppedState
        }
        mq = [ordered]@{
            beforeRecovery = $mqBefore
            afterRecovery = $mqAfter
            outageState = $faultState
        }
        mixedPeak = $mixed
        finalState = $finalState
        metrics = [ordered]@{
            trade = $tradeMetrics
            marketing = $marketingMetrics
        }
    }
    $summary | ConvertTo-Json -Depth 30 | Set-Content `
        -LiteralPath (Join-Path $script:runDirectory 'summary.json') -Encoding utf8
    $summary | ConvertTo-Json -Depth 30
}
catch {
    $primaryError = $_
    [ordered]@{
        message = $_.Exception.Message
        scriptStackTrace = $_.ScriptStackTrace
        position = [string]$_.InvocationInfo.PositionMessage
    } | ConvertTo-Json -Depth 8 | Set-Content `
        -LiteralPath (Join-Path $script:runDirectory 'failure.json') -Encoding utf8
}
finally {
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    try {
        if ($script:proxyWasStopped) {
            docker start plainjournal-rocketmq-proxy *> $null
            $script:proxyWasStopped = $false
        }
        if ($script:redisWasStopped) {
            docker start plainjournal-redis *> $null
            $script:redisWasStopped = $false
        }
    } catch {
        $cleanupErrors.Add("middleware restore: $($_.Exception.Message)")
    }
    try {
        Stop-Applications
    } catch {
        $cleanupErrors.Add("application stop: $($_.Exception.Message)")
    }
    if (-not ($PreserveFixtureOnFailure -and $null -ne $primaryError)) {
        try {
            Remove-Fixture
        } catch {
            $cleanupErrors.Add("fixture cleanup: $($_.Exception.Message)")
        }
        try {
            Remove-FlashSaleConsumerGroups
        } catch {
            $cleanupErrors.Add("RocketMQ consumer group cleanup: $($_.Exception.Message)")
        }
        try {
            Remove-FlashSaleTopic
        } catch {
            $cleanupErrors.Add("RocketMQ topic cleanup: $($_.Exception.Message)")
        }
    }
    if ($cleanupErrors.Count -gt 0) {
        $cleanupErrors | Set-Content `
            -LiteralPath (Join-Path $script:runDirectory 'cleanup-failure.log') `
            -Encoding utf8
        $cleanupFailure = [InvalidOperationException]::new(
            "M6 queue cleanup failed: $($cleanupErrors -join ' | ')")
    }
    Restore-ProcessEnvironment
}

if ($null -ne $primaryError) { throw $primaryError }
if ($null -ne $cleanupFailure) { throw $cleanupFailure }
