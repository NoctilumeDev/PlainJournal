[CmdletBinding()]
param(
    [string]$OutputPath = 'backend/.run/m0-m8-pre-m9-audit-20260728-r5/evidence/database-ownership.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$envPath = Join-Path $repositoryRoot 'deploy/docker/.env'

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            continue
        }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Invoke-OwnerQuery {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Sql
    )

    $output = docker exec -e "MYSQL_PWD=$Password" plainjournal-mysql `
        mysql "--user=$User" "--database=$Database" --batch --skip-column-names `
        --execute $Sql 2>&1
    return [ordered]@{
        ExitCode = $LASTEXITCODE
        Output = @($output)
    }
}

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Missing local environment file: $envPath"
}
Import-DotEnv -Path $envPath

$domains = @(
    @{ Name = 'identity'; Prefix = 'IDENTITY' },
    @{ Name = 'catalog'; Prefix = 'CATALOG' },
    @{ Name = 'inventory'; Prefix = 'INVENTORY' },
    @{ Name = 'trade'; Prefix = 'TRADE' },
    @{ Name = 'payment'; Prefix = 'PAYMENT' },
    @{ Name = 'fulfillment'; Prefix = 'FULFILLMENT' },
    @{ Name = 'marketing'; Prefix = 'MARKETING' },
    @{ Name = 'chat'; Prefix = 'CHAT' },
    @{ Name = 'notification'; Prefix = 'NOTIFICATION' },
    @{ Name = 'analytics'; Prefix = 'ANALYTICS' }
)

foreach ($container in @(
        'plainjournal-mysql',
        'plainjournal-redis',
        'plainjournal-nacos',
        'plainjournal-rocketmq-namesrv',
        'plainjournal-rocketmq-broker',
        'plainjournal-rocketmq-proxy',
        'plainjournal-minio')) {
    $running = docker inspect --format '{{.State.Running}}' $container 2>$null
    $project = docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' `
        $container 2>$null
    if ($running -ne 'true' -or $project -ne 'plainjournal') {
        throw "Core container identity mismatch: $container running=$running project=$project"
    }
}

$tradeToken = [Environment]::GetEnvironmentVariable(
    'TRADE_INTERNAL_SERVICE_TOKEN', 'Process')
$paymentToken = [Environment]::GetEnvironmentVariable(
    'PAYMENT_INTERNAL_SERVICE_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($tradeToken) -or $tradeToken.Length -lt 32 -or
    [string]::IsNullOrWhiteSpace($paymentToken) -or $paymentToken.Length -lt 32 -or
    [string]::Equals($tradeToken, $paymentToken, [StringComparison]::Ordinal)) {
    throw 'Relationship-specific internal credentials are missing, short, or equal.'
}

$resolvedDomains = foreach ($domain in $domains) {
    $database = [Environment]::GetEnvironmentVariable(
        "$($domain.Prefix)_DB_NAME", 'Process')
    $user = [Environment]::GetEnvironmentVariable(
        "$($domain.Prefix)_DB_USER", 'Process')
    $password = [Environment]::GetEnvironmentVariable(
        "$($domain.Prefix)_DB_PASSWORD", 'Process')
    if ([string]::IsNullOrWhiteSpace($database) -or
        [string]::IsNullOrWhiteSpace($user) -or
        [string]::IsNullOrWhiteSpace($password)) {
        throw "Missing database owner credential for $($domain.Name)."
    }
    [pscustomobject]@{
        Name = $domain.Name
        Database = $database
        User = $user
        Password = $password
    }
}

$duplicates = $resolvedDomains |
    Group-Object User |
    Where-Object Count -ne 1
if ($duplicates) {
    throw "Service database account is shared: $($duplicates[0].Name)"
}

$ownerChecks = @()
$crossChecks = @()
foreach ($owner in $resolvedDomains) {
    $ownResult = Invoke-OwnerQuery -User $owner.User -Password $owner.Password `
        -Database $owner.Database -Sql 'SELECT DATABASE(), CURRENT_TIMESTAMP(3);'
    if ($ownResult.ExitCode -ne 0 -or $ownResult.Output.Count -ne 1) {
        throw "Owner account cannot read its own schema: $($owner.Name)"
    }
    $ownerChecks += [ordered]@{
        owner = $owner.Name
        database = $owner.Database
        user = $owner.User
        access = 'ALLOWED'
        databaseReported = (($ownResult.Output[0] -split "`t")[0])
    }

    foreach ($target in $resolvedDomains) {
        if ($target.Name -eq $owner.Name) {
            continue
        }
        $crossResult = Invoke-OwnerQuery -User $owner.User -Password $owner.Password `
            -Database $target.Database -Sql 'SELECT 1;'
        if ($crossResult.ExitCode -eq 0) {
            throw "$($owner.Name) owner account accessed $($target.Name) schema."
        }
        $crossChecks += [ordered]@{
            owner = $owner.Name
            target = $target.Name
            targetDatabase = $target.Database
            access = 'DENIED'
        }
    }
}

$result = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    composeProject = 'plainjournal'
    coreContainers = 7
    relationshipCredentials = [ordered]@{
        tradeCredentialPresent = $true
        paymentCredentialPresent = $true
        credentialsDifferent = $true
        secretValuesRecorded = $false
    }
    ownerDatabaseAccess = $ownerChecks
    crossDomainDatabaseAccess = $crossChecks
    summary = [ordered]@{
        ownerChecks = $ownerChecks.Count
        ownerChecksPassed = $ownerChecks.Count
        crossDomainChecks = $crossChecks.Count
        crossDomainChecksDenied = $crossChecks.Count
        status = 'PASS'
    }
}

$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repositoryRoot $OutputPath
}
[IO.Directory]::CreateDirectory((Split-Path -Parent $resolvedOutput)) | Out-Null
[IO.File]::WriteAllText(
    $resolvedOutput,
    ($result | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))

$result.summary | ConvertTo-Json
