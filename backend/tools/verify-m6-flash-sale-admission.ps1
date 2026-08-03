#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateRange(100, 10000)]
    [int]$Requests = 1000,
    [ValidateRange(1, 500)]
    [int]$Concurrency = 100,
    [ValidateRange(1, 10000)]
    [int]$AdmissionLimit = 100,
    [ValidateRange(10, 1000)]
    [int]$GatewayRequests = 100,
    [ValidateRange(1, 500)]
    [int]$GatewayConcurrency = 100,
    [ValidateRange(1, 1000)]
    [int]$GatewayLimit = 20,
    [switch]$EnableRedisFaultInjection,
    [switch]$SkipPackage,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$script:backendRoot = Split-Path -Parent $PSScriptRoot
$script:repositoryRoot = Split-Path -Parent $script:backendRoot
$script:runId = "m6$((Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'))"
$script:namespace = $script:runId
$script:processes = [ordered]@{}
$script:activityNos = [Collections.Generic.List[string]]::new()
$script:cleanupTrace = [Collections.Generic.List[string]]::new()
$script:redisWasStopped = $false

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
        'IDENTITY_JWT_SECRET',
        'NACOS_ADMIN_PASSWORD',
        'MARKETING_DB_NAME',
        'MARKETING_DB_USER',
        'MARKETING_DB_PASSWORD',
        'REDIS_PASSWORD'
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
        [Parameter(Mandatory)][string]$Sql
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
    return $output
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-AccessToken {
    param(
        [Parameter(Mandatory)][long]$UserId,
        [string[]]$Roles = @('CUSTOMER')
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = [ordered]@{ alg = 'HS256' } | ConvertTo-Json -Compress
    $payload = [ordered]@{
        iss = 'ecommerce-identity'
        sub = [string]$UserId
        iat = $now
        exp = $now + 7200
        jti = [Guid]::NewGuid().ToString()
        roles = $Roles
    } | ConvertTo-Json -Compress
    $encodedHeader = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($header))
    $encodedPayload = ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($payload))
    $unsigned = "$encodedHeader.$encodedPayload"
    $hmac = [Security.Cryptography.HMACSHA256]::new(
        [Text.Encoding]::UTF8.GetBytes($env:IDENTITY_JWT_SECRET))
    try {
        $signature = ConvertTo-Base64Url -Bytes (
            $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned)))
    }
    finally {
        $hmac.Dispose()
    }
    return "$unsigned.$signature"
}

function Assert-PortAvailable {
    param([Parameter(Mandatory)][int]$Port)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $Port is already in use by process $($listener[0].OwningProcess)."
    }
}

function Wait-HttpOk {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [string]$ProcessName,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'no response'
    do {
        if ($ProcessName -and $script:processes.Contains($ProcessName)) {
            $managedProcess = $script:processes[$ProcessName].process
            if ($managedProcess.HasExited) {
                throw "$ProcessName exited before $Uri became ready. ExitCode=$($managedProcess.ExitCode)"
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -SkipHttpErrorCheck -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
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

function Wait-RedisHealthy {
    param([int]$TimeoutSeconds = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $health = docker inspect --format '{{.State.Health.Status}}' plainjournal-redis 2>$null
        if ($health -eq 'healthy') {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw 'Redis did not return to healthy state.'
}

function Start-Application {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Jar,
        [Parameter(Mandatory)][hashtable]$Environment,
        [hashtable]$SystemProperties = @{}
    )

    if (-not (Test-Path -LiteralPath $Jar)) {
        throw "Missing application artifact: $Jar"
    }
    $original = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $original[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    try {
        $arguments = @(
            '-Xms256m',
            '-Xmx256m',
            '-XX:ActiveProcessorCount=4'
        )
        foreach ($entry in $SystemProperties.GetEnumerator() | Sort-Object Key) {
            $arguments += "-D$($entry.Key)=$($entry.Value)"
        }
        $arguments += @('-jar', $Jar)
        $process = Start-Process -FilePath $script:javaPath `
            -ArgumentList $arguments `
            -WorkingDirectory $script:backendRoot `
            -RedirectStandardOutput (Join-Path $script:runDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $script:runDirectory "$Name.err.log") `
            -WindowStyle Hidden `
            -PassThru
        $script:processes[$Name] = [pscustomobject]@{
            process = $process
            jar = $Jar
        }
    }
    finally {
        foreach ($entry in $original.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        }
    }
}

function Stop-Applications {
    foreach ($name in @($script:processes.Keys)) {
        $managed = $script:processes[$name]
        $processId = [int]$managed.process.Id
        $script:cleanupTrace.Add("stop-check:$name/$processId")
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" `
            -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            $script:cleanupTrace.Add("stop-already-exited:$name/$processId")
            continue
        }
        if ($process.CommandLine -notlike "*$([IO.Path]::GetFileName($managed.jar))*") {
            Write-Warning "Refused to stop PID $processId; command line no longer matches."
            continue
        }
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            throw "Application process did not exit: $name/$processId"
        }
        $script:cleanupTrace.Add("stop-complete:$name/$processId")
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
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 10
    }
    $response = Invoke-WebRequest @parameters
    $payload = if ($response.Content) {
        $response.Content | ConvertFrom-Json
    } else {
        $null
    }
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        payload = $payload
    }
}

function Assert-Response {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][int]$Status,
        [Parameter(Mandatory)][string]$Code,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Response.status -ne $Status -or $Response.payload.code -ne $Code) {
        throw "$Message Expected=HTTP $Status/$Code Actual=HTTP $($Response.status)/$($Response.payload.code)"
    }
}

function New-Activity {
    param(
        [Parameter(Mandatory)][int]$Limit,
        [Parameter(Mandatory)][string]$Suffix,
        [Parameter(Mandatory)][hashtable]$AdminHeaders
    )

    $created = Invoke-JsonRequest -Method Post `
        -Uri 'http://127.0.0.1:18000/api/v1/marketing/admin/flash-sales' `
        -Headers $AdminHeaders `
        -Body @{
            name = "M6 $($script:runId) $Suffix"
            productId = '7995000000000000001'
            skuId = '7996000000000000001'
            salePrice = '9.90'
            admissionLimit = $Limit
            startsAt = [DateTimeOffset]::UtcNow.AddSeconds(-10).ToString('o')
            endsAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
        }
    Assert-Response -Response $created -Status 200 -Code 'OK' `
        -Message "Unable to create $Suffix activity."
    $activityNo = [string]$created.payload.data.activityNo
    $script:activityNos.Add($activityNo)
    $published = Invoke-JsonRequest -Method Post `
        -Uri "http://127.0.0.1:18000/api/v1/marketing/admin/flash-sales/$activityNo/publish" `
        -Headers $AdminHeaders
    Assert-Response -Response $published -Status 200 -Code 'OK' `
        -Message "Unable to publish $Suffix activity."
    if ($published.payload.data.status -ne 'ACTIVE') {
        throw "$Suffix activity did not reach ACTIVE."
    }
    return $activityNo
}

function Invoke-Load {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$RequestCount,
        [Parameter(Mandatory)][int]$ConcurrentWorkers,
        [Parameter(Mandatory)][object[]]$Variants,
        [Parameter(Mandatory)][int[]]$ExpectedStatuses
    )

    $directory = Join-Path $script:runDirectory $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $configurationPath = Join-Path $directory 'config.runtime.json'
    $resultPath = Join-Path $directory 'result.json'
    [ordered]@{
        schemaVersion = 1
        name = $Name
        requests = $RequestCount
        concurrency = $ConcurrentWorkers
        warmupRequests = 0
        timeoutMs = 10000
        maxErrorRate = 0
        scenarios = @([ordered]@{
                name = $Name
                method = 'POST'
                expectedStatuses = $ExpectedStatuses
                variants = $Variants
            })
    } | ConvertTo-Json -Depth 15 |
        Set-Content -LiteralPath $configurationPath -Encoding utf8
    try {
        $runnerOutput = @(
            & $script:nodePath `
                (Join-Path $PSScriptRoot 'm5-http-load-runner.mjs') `
                $configurationPath `
                $resultPath 2>&1
        )
        $runnerOutput |
            Set-Content -LiteralPath (Join-Path $directory 'runner-console.log') -Encoding utf8
        if ($LASTEXITCODE -ne 0) {
            throw "Load run failed: $Name. $($runnerOutput -join [Environment]::NewLine)"
        }
    }
    finally {
        Remove-Item -LiteralPath $configurationPath -Force -ErrorAction SilentlyContinue
    }
    return (Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json)
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

function Get-ActivityAdmissionState {
    param([Parameter(Mandatory)][string]$ActivityNo)

    $escapedActivityNo = $ActivityNo.Replace("'", "''")
    $rows = @(Invoke-DbSql `
            -Database $env:MARKETING_DB_NAME `
            -User $env:MARKETING_DB_USER `
            -Password $env:MARKETING_DB_PASSWORD `
            -Sql @"
SELECT
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='ADMISSION_PENDING'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='ADMISSION_REJECTED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='QUEUED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='ORDER_CREATED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='FAILED'),
 (SELECT COUNT(*) FROM flash_sale_admission WHERE activity_no='$escapedActivityNo' AND status='RESULT_UNKNOWN'),
 (SELECT COUNT(*) FROM flash_sale_outbox_event WHERE aggregate_id IN (
     SELECT request_token FROM flash_sale_admission WHERE activity_no='$escapedActivityNo'
 ));
"@)
    if ($rows.Count -ne 1) {
        throw "Unexpected admission state result for ${ActivityNo}: $($rows -join "`n")"
    }
    $values = $rows[0].Split("`t")
    if ($values.Count -ne 7) {
        throw "Unexpected admission state columns for ${ActivityNo}: $($rows[0])"
    }
    return [pscustomobject]@{
        pending = [int]$values[0]
        rejected = [int]$values[1]
        queued = [int]$values[2]
        orderCreated = [int]$values[3]
        failed = [int]$values[4]
        resultUnknown = [int]$values[5]
        outboxTotal = [int]$values[6]
    }
}

function Remove-RunRedisKeys {
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
    docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" `
        plainjournal-redis redis-cli EVAL $lua 0 $pattern *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis M6 namespace cleanup failed.'
    }
}

function Remove-RunDatabaseRows {
    if ($script:activityNos.Count -eq 0) {
        return
    }
    $activityList = ($script:activityNos | ForEach-Object { "'$($_.Replace("'", "''"))'" }) -join ','
    "DELETE FROM flash_sale_activity WHERE activity_no IN ($activityList);" |
        docker exec -i -e "MYSQL_PWD=$env:MARKETING_DB_PASSWORD" `
            plainjournal-mysql mysql "--user=$env:MARKETING_DB_USER" $env:MARKETING_DB_NAME *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'M6 activity cleanup failed.'
    }
}

$envFile = Join-Path $script:repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
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

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $script:backendRoot ".run\m6-flash-sale-$($script:runId)"
}
$script:runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $script:runDirectory -Force | Out-Null

$networkLog = Join-Path $script:runDirectory 'network-preflight.log'
$networkOutput = @(& 'D:\DevTools\Network\check-dev-network.ps1' *>&1)
$networkExitCode = $LASTEXITCODE
$networkOutput | Out-String -Width 240 |
    Set-Content -LiteralPath $networkLog -Encoding utf8

$requiredContainers = @(
    'plainjournal-mysql',
    'plainjournal-redis',
    'plainjournal-nacos',
    'plainjournal-rocketmq-namesrv',
    'plainjournal-rocketmq-broker',
    'plainjournal-rocketmq-proxy',
    'plainjournal-minio'
)
$runningContainers = @(docker ps --format '{{.Names}}')
$missingContainers = @($requiredContainers | Where-Object { $runningContainers -notcontains $_ })
if ($missingContainers.Count -gt 0) {
    throw "Required containers are not running: $($missingContainers -join ', ')"
}
if ($networkExitCode -ne 0) {
    throw "Local network preflight failed with exit code $networkExitCode. See $networkLog."
}

Assert-PortAvailable -Port 18000
Assert-PortAvailable -Port 18107

if (-not $SkipPackage) {
    & mvn -q -f (Join-Path $script:backendRoot 'pom.xml') `
        -pl ecommerce-gateway,services/marketing-service -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw "Backend package failed with exit code $LASTEXITCODE."
    }
}

$marketingJar = Join-Path $script:backendRoot `
    'services\marketing-service\target\marketing-service-0.1.0-SNAPSHOT.jar'
$gatewayJar = Join-Path $script:backendRoot `
    'ecommerce-gateway\target\ecommerce-gateway-0.1.0-SNAPSHOT.jar'
$primaryError = $null
$summary = $null

try {
    Start-Application -Name marketing -Jar $marketingJar -Environment @{
        APP_ENV = $script:namespace
        NACOS_HOST = '127.0.0.1'
        NACOS_USERNAME = 'nacos'
        SERVICE_IP = '127.0.0.1'
        MARKETING_ORDER_CONSUMER_ENABLED = 'false'
        MARKETING_FLASH_SALE_REDIS_ENABLED = 'true'
    } -SystemProperties @{
        'management.endpoints.web.exposure.include' = 'health,info'
    }
    Wait-HttpOk -Uri 'http://127.0.0.1:18107/actuator/health/liveness' -ProcessName marketing

    Start-Application -Name gateway -Jar $gatewayJar -Environment @{
        APP_ENV = $script:namespace
        NACOS_HOST = '127.0.0.1'
        NACOS_USERNAME = 'nacos'
        SERVICE_IP = '127.0.0.1'
        GATEWAY_RATE_LIMIT_REDIS_ENABLED = 'true'
        ECOMMERCE_GATEWAY_RATE_LIMIT_FLASH_SALE_LIMIT = [string]$GatewayLimit
        ECOMMERCE_GATEWAY_RATE_LIMIT_FLASH_SALE_WINDOW = '1s'
    }
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/actuator/health/liveness' -ProcessName gateway
    Wait-HttpOk -Uri 'http://127.0.0.1:18000/api/v1/marketing/status' -ProcessName gateway

    $status = Invoke-JsonRequest -Method Get `
        -Uri 'http://127.0.0.1:18000/api/v1/marketing/status'
    Assert-Response -Response $status -Status 200 -Code 'OK' `
        -Message 'Gateway to Marketing route is not ready.'
    if ($status.payload.data.configurationSource -ne 'nacos') {
        throw "Marketing configuration source is $($status.payload.data.configurationSource), expected nacos."
    }

    $adminHeaders = @{
        Authorization = "Bearer $(New-AccessToken -UserId 7996000000000000001 -Roles @('ADMIN'))"
    }

    $quotaActivity = New-Activity -Limit $AdmissionLimit -Suffix 'quota' -AdminHeaders $adminHeaders
    $quotaVariants = @(0..($Requests - 1) | ForEach-Object {
            $userId = [long]7996100000000000000 + $_
            [ordered]@{
                url = "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$quotaActivity/admissions"
                headers = @{
                    Authorization = "Bearer $(New-AccessToken -UserId $userId)"
                    'Idempotency-Key' = "m6-quota-$($script:runId)-$($_.ToString('0000'))"
                }
                body = @{
                    addressId = [string]([long]7997000000000000000 + $_)
                }
            }
        })
    $quotaResult = Invoke-Load -Name 'quota-1000' `
        -RequestCount $Requests `
        -ConcurrentWorkers $Concurrency `
        -Variants $quotaVariants `
        -ExpectedStatuses @(202, 409)
    $quotaAccepted = [int]($quotaResult.aggregate.statusCodes.'202' ?? 0)
    $quotaRejected = [int]($quotaResult.aggregate.statusCodes.'409' ?? 0)
    if ($quotaAccepted -ne $AdmissionLimit -or $quotaRejected -ne ($Requests - $AdmissionLimit)) {
        throw "Fixed quota mismatch: accepted=$quotaAccepted rejected=$quotaRejected."
    }
    $quotaMeta = Get-ActivityRedisMeta -ActivityNo $quotaActivity
    $quotaMeta | ConvertTo-Json -Depth 3 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'quota-1000\redis-meta.json') -Encoding utf8
    $redisAdmitted = [int]$quotaMeta.admitted
    $redisRemaining = [int]$quotaMeta.remaining
    if ($redisAdmitted -ne $AdmissionLimit -or $redisRemaining -ne 0) {
        throw "Redis quota fact mismatch: admitted=$redisAdmitted remaining=$redisRemaining."
    }
    $quotaDatabaseState = Get-ActivityAdmissionState -ActivityNo $quotaActivity
    if ($quotaDatabaseState.pending -ne 0 -or
        $quotaDatabaseState.rejected -ne $quotaRejected -or
        $quotaDatabaseState.queued -ne $quotaAccepted -or
        $quotaDatabaseState.orderCreated -ne 0 -or
        $quotaDatabaseState.failed -ne 0 -or
        $quotaDatabaseState.resultUnknown -ne 0 -or
        $quotaDatabaseState.outboxTotal -ne $quotaAccepted) {
        throw "MySQL quota fact mismatch: $(($quotaDatabaseState | ConvertTo-Json -Compress))"
    }
    $quotaDatabaseState | ConvertTo-Json -Depth 3 |
        Set-Content -LiteralPath (
            Join-Path $script:runDirectory 'quota-1000\mysql-state.json') -Encoding utf8

    $stableActivity = New-Activity -Limit 10 -Suffix 'stable-token' -AdminHeaders $adminHeaders
    $stableUserId = [long]7996200000000000001
    $stableToken = New-AccessToken -UserId $stableUserId
    $stableVariants = @(0..99 | ForEach-Object {
            [ordered]@{
                url = "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$stableActivity/admissions"
                headers = @{
                    Authorization = "Bearer $stableToken"
                    'Idempotency-Key' = "m6-stable-$($script:runId)-$($_.ToString('000'))"
                }
                body = @{
                    addressId = '7997000000000001001'
                }
            }
        })
    $stableResult = Invoke-Load -Name 'one-user-stable-token' `
        -RequestCount 100 `
        -ConcurrentWorkers 100 `
        -Variants $stableVariants `
        -ExpectedStatuses @(202)
    $stableAccepted = [int]($stableResult.aggregate.statusCodes.'202' ?? 0)
    $stableMeta = Get-ActivityRedisMeta -ActivityNo $stableActivity
    $stableAdmitted = [int]$stableMeta.admitted
    if ($stableAccepted -ne 100 -or $stableAdmitted -ne 1) {
        throw "Stable-token fact mismatch: responses=$stableAccepted admitted=$stableAdmitted."
    }
    $stableDatabaseState = Get-ActivityAdmissionState -ActivityNo $stableActivity
    if ($stableDatabaseState.pending -ne 0 -or
        $stableDatabaseState.rejected -ne 0 -or
        $stableDatabaseState.queued -ne 1 -or
        $stableDatabaseState.orderCreated -ne 0 -or
        $stableDatabaseState.failed -ne 0 -or
        $stableDatabaseState.resultUnknown -ne 0 -or
        $stableDatabaseState.outboxTotal -ne 1) {
        throw "Stable-token MySQL fact mismatch: $(($stableDatabaseState | ConvertTo-Json -Compress))"
    }
    $stableHeadersA = @{
        Authorization = "Bearer $stableToken"
        'Idempotency-Key' = "m6-stable-$($script:runId)-000"
    }
    $stableHeadersB = @{
        Authorization = "Bearer $stableToken"
        'Idempotency-Key' = "m6-stable-$($script:runId)-099"
    }
    $stableA = Invoke-JsonRequest -Method Post `
        -Uri "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$stableActivity/admissions" `
        -Headers $stableHeadersA `
        -Body @{ addressId = '7997000000000001001' }
    $stableB = Invoke-JsonRequest -Method Post `
        -Uri "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$stableActivity/admissions" `
        -Headers $stableHeadersB `
        -Body @{ addressId = '7997000000000001001' }
    Assert-Response -Response $stableA -Status 202 -Code 'OK' -Message 'Stable token replay A failed.'
    Assert-Response -Response $stableB -Status 202 -Code 'OK' -Message 'Stable token replay B failed.'
    if ($stableA.payload.data.requestToken -ne $stableB.payload.data.requestToken -or
        $stableA.payload.data.acceptedAt -ne $stableB.payload.data.acceptedAt) {
        throw 'One-user admission did not return a stable token and acceptance time.'
    }

    $gatewayActivity = New-Activity -Limit 1000 -Suffix 'gateway-limit' -AdminHeaders $adminHeaders
    $gatewayRatePattern = "ecommerce:$($script:namespace):gateway:rate:flash-sale:*"
    $gatewayCleanupLua = "local r=redis.call('KEYS',ARGV[1]); if #r>0 then return redis.call('DEL',unpack(r)) end return 0"
    docker exec -e "REDISCLI_AUTH=$env:REDIS_PASSWORD" plainjournal-redis redis-cli `
        EVAL $gatewayCleanupLua 0 $gatewayRatePattern *> $null
    $milliseconds = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() % 1000
    Start-Sleep -Milliseconds ([int](1100 - $milliseconds))
    $gatewayVariants = @(0..($GatewayRequests - 1) | ForEach-Object {
            $userId = [long]7996300000000000000 + $_
            [ordered]@{
                url = "http://127.0.0.1:18000/api/v1/marketing/flash-sales/$gatewayActivity/admissions"
                headers = @{
                    Authorization = "Bearer $(New-AccessToken -UserId $userId)"
                    'Idempotency-Key' = "m6-gateway-$($script:runId)-$($_.ToString('000'))"
                }
                body = @{
                    addressId = [string]([long]7997000000000002000 + $_)
                }
            }
        })
    $gatewayResult = Invoke-Load -Name 'gateway-independent-limit' `
        -RequestCount $GatewayRequests `
        -ConcurrentWorkers $GatewayConcurrency `
        -Variants $gatewayVariants `
        -ExpectedStatuses @(202, 429)
    $gatewayAccepted = [int]($gatewayResult.aggregate.statusCodes.'202' ?? 0)
    $gatewayLimited = [int]($gatewayResult.aggregate.statusCodes.'429' ?? 0)
    if ($gatewayAccepted -ne $GatewayLimit -or
        $gatewayLimited -ne ($GatewayRequests - $GatewayLimit)) {
        throw "Gateway limit mismatch: accepted=$gatewayAccepted limited=$gatewayLimited."
    }

    $faultEvidence = $null
    if ($EnableRedisFaultInjection) {
        $faultActivity = New-Activity -Limit 10 -Suffix 'redis-fault' -AdminHeaders $adminHeaders
        Start-Sleep -Seconds 2
        docker stop plainjournal-redis *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to stop Redis for M6 fault injection.'
        }
        $script:redisWasStopped = $true
        $faultHeaders = @{
            Authorization = "Bearer $(New-AccessToken -UserId 7996400000000000001)"
            'Idempotency-Key' = "m6-fault-$($script:runId)"
        }
        $started = [Diagnostics.Stopwatch]::StartNew()
        $faultAdmission = Invoke-JsonRequest -Method Post `
            -Uri "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$faultActivity/admissions" `
            -Headers $faultHeaders `
            -Body @{ addressId = '7997000000000003001' }
        $started.Stop()
        Assert-Response -Response $faultAdmission -Status 503 `
            -Code 'FLASH_SALE_ADMISSION_UNAVAILABLE' `
            -Message 'Redis fault did not fail flash-sale admission closed.'
        $ordinary = Invoke-JsonRequest -Method Get `
            -Uri "http://127.0.0.1:18000/api/v1/marketing/flash-sales/$faultActivity"
        Assert-Response -Response $ordinary -Status 200 -Code 'OK' `
            -Message 'Redis fault affected the ordinary activity query.'

        docker start plainjournal-redis *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to restart Redis after M6 fault injection.'
        }
        $script:redisWasStopped = $false
        Wait-RedisHealthy
        Start-Sleep -Seconds 2
        $recovered = Invoke-JsonRequest -Method Post `
            -Uri "http://127.0.0.1:18107/api/v1/marketing/flash-sales/$faultActivity/admissions" `
            -Headers $faultHeaders `
            -Body @{ addressId = '7997000000000003001' }
        Assert-Response -Response $recovered -Status 202 -Code 'OK' `
            -Message 'Flash-sale admission did not recover after Redis restart.'
        $faultEvidence = [ordered]@{
            admissionStatusDuringFault = $faultAdmission.status
            admissionCodeDuringFault = $faultAdmission.payload.code
            failureLatencyMs = $started.ElapsedMilliseconds
            ordinaryQueryStatusDuringFault = $ordinary.status
            recoveredAdmissionStatus = $recovered.status
        }
    }

    $summary = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $script:runId
        namespace = $script:namespace
        nodeVersion = (& $script:nodePath --version)
        networkPreflightExitCode = $networkExitCode
        parameters = [ordered]@{
            requests = $Requests
            concurrency = $Concurrency
            admissionLimit = $AdmissionLimit
            gatewayRequests = $GatewayRequests
            gatewayConcurrency = $GatewayConcurrency
            gatewayLimit = $GatewayLimit
            redisFaultInjection = [bool]$EnableRedisFaultInjection
        }
        assertions = [ordered]@{
            configurationSource = $status.payload.data.configurationSource
            fixedQuota = [ordered]@{
                accepted = $quotaAccepted
                rejected = $quotaRejected
                redisAdmitted = $redisAdmitted
                redisRemaining = $redisRemaining
                mysql = $quotaDatabaseState
            }
            stableToken = [ordered]@{
                responses = $stableAccepted
                redisAdmitted = $stableAdmitted
                requestToken = $stableA.payload.data.requestToken
                acceptedAt = $stableA.payload.data.acceptedAt
                mysql = $stableDatabaseState
            }
            gatewayLimit = [ordered]@{
                accepted = $gatewayAccepted
                limited = $gatewayLimited
            }
            redisFault = $faultEvidence
        }
        evidence = [ordered]@{
            fixedQuota = Join-Path $script:runDirectory 'quota-1000\result.json'
            stableToken = Join-Path $script:runDirectory 'one-user-stable-token\result.json'
            gatewayLimit = Join-Path $script:runDirectory 'gateway-independent-limit\result.json'
            networkPreflight = $networkLog
        }
    }
    $summaryPath = Join-Path $script:runDirectory 'summary.json'
    $summary | ConvertTo-Json -Depth 12 |
        Set-Content -LiteralPath $summaryPath -Encoding utf8
    $summary | ConvertTo-Json -Depth 12
}
catch {
    $primaryError = $_
    [ordered]@{
        message = $_.Exception.Message
        category = [string]$_.CategoryInfo
        scriptStackTrace = $_.ScriptStackTrace
        position = [string]$_.InvocationInfo.PositionMessage
    } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $script:runDirectory 'failure.json') -Encoding utf8
    foreach ($name in $script:processes.Keys) {
        foreach ($suffix in @('out', 'err')) {
            $logPath = Join-Path $script:runDirectory "$name.$suffix.log"
            if (Test-Path -LiteralPath $logPath) {
                Write-Host "--- $logPath ---"
                Get-Content -LiteralPath $logPath -Tail 80
            }
        }
    }
}
finally {
    $cleanupErrors = [Collections.Generic.List[string]]::new()
    $script:cleanupTrace.Add('cleanup-start')
    if ($script:redisWasStopped) {
        try {
            $script:cleanupTrace.Add('redis-restore-start')
            docker start plainjournal-redis *> $null
            Wait-RedisHealthy
            $script:redisWasStopped = $false
            $script:cleanupTrace.Add('redis-restore-complete')
        }
        catch {
            $cleanupErrors.Add("Redis restore: $($_.Exception.Message)")
        }
    }
    try {
        if ((docker inspect --format '{{.State.Running}}' plainjournal-redis 2>$null) -eq 'true') {
            $script:cleanupTrace.Add('redis-cleanup-start')
            Remove-RunRedisKeys
            $script:cleanupTrace.Add('redis-cleanup-complete')
        }
    }
    catch {
        $cleanupErrors.Add("Redis cleanup: $($_.Exception.Message)")
    }
    try {
        $script:cleanupTrace.Add('database-cleanup-start')
        Remove-RunDatabaseRows
        $script:cleanupTrace.Add('database-cleanup-complete')
    }
    catch {
        $cleanupErrors.Add("Database cleanup: $($_.Exception.Message)")
    }
    try {
        $script:cleanupTrace.Add('application-cleanup-start')
        Stop-Applications
        $script:cleanupTrace.Add('application-cleanup-complete')
    }
    catch {
        $cleanupErrors.Add("Application cleanup: $($_.Exception.Message)")
    }
    $script:cleanupTrace |
        Set-Content -LiteralPath (Join-Path $script:runDirectory 'cleanup.log') -Encoding utf8
    if ($cleanupErrors.Count -gt 0) {
        if ($null -ne $primaryError) {
            Write-Warning "M6 verification cleanup also failed: $($cleanupErrors -join ' | ')"
        }
        else {
            throw "M6 verification cleanup failed: $($cleanupErrors -join ' | ')"
        }
    }
}

if ($null -ne $primaryError) {
    throw $primaryError
}
