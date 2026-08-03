#requires -Version 7.0

[CmdletBinding()]
param(
    [ValidateSet('Seed', 'Verify', 'Remove')]
    [string]$Action = 'Verify',
    [ValidateSet('Small', 'Medium', 'Formal')]
    [string]$Scale = 'Small',
    [ValidateRange(0, 1000000)]
    [int]$SpuCount = 0,
    [ValidateRange(0, 10)]
    [int]$SkuPerSpu = 0,
    [ValidateRange(0, 100000)]
    [int]$UserCount = 0,
    [ValidateRange(0, 5000000)]
    [int]$OrderCount = 0,
    [ValidateRange(0, 5000000)]
    [int]$DenseUserOrderCount = 0,
    [ValidateRange(0, 10)]
    [int]$ItemsPerOrder = 0,
    [ValidateRange(0, 1000)]
    [int]$DiscountEvery = 0,
    [ValidateRange(100, 10000)]
    [int]$BatchSize = 5000,
    [switch]$AllowFormal,
    [string]$ManifestPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$fixturePrefix = 'M7'
$fixtureEmailSuffix = '@plainjournal.local'
$fixturePasswordHash = '$2b$12$KREIAX0KbHhyffdoXFSQfeS/27kwWbzT0tcYWXQ2RZBJM20NihfL2'
$businessPorts = @(18000, 18101, 18102, 18103, 18104, 18105, 18106, 18107)
$reservedRowsPerTable = 100000000
$id = [ordered]@{
    category = [long]7400000000000000001
    brand = [long]7400000000000000002
    productBase = [long]7410000000000000000
    skuBase = [long]7420000000000000000
    userBase = [long]7430000000000000000
    orderBase = [long]7440000000000000000
    orderItemBase = [long]7450000000000000000
    orderAddressBase = [long]7460000000000000000
    priceSnapshotBase = [long]7470000000000000000
    discountAllocationBase = [long]7480000000000000000
    orderHistoryBase = [long]7490000000000000000
}
$scaleProfiles = @{
    Small = [ordered]@{
        spus = 10000
        skuPerSpu = 2
        users = 1000
        orders = 50000
        denseUserOrders = 40000
        itemsPerOrder = 2
        discountEvery = 4
    }
    Medium = [ordered]@{
        spus = 50000
        skuPerSpu = 2
        users = 5000
        orders = 250000
        denseUserOrders = 200000
        itemsPerOrder = 2
        discountEvery = 4
    }
    Formal = [ordered]@{
        spus = 100000
        skuPerSpu = 2
        users = 10000
        orders = 1000000
        denseUserOrders = 800000
        itemsPerOrder = 2
        discountEvery = 4
    }
}
$script:stageTimings = [Collections.Generic.List[object]]::new()

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
        'MYSQL_ROOT_PASSWORD',
        'CATALOG_DB_NAME', 'CATALOG_DB_USER', 'CATALOG_DB_PASSWORD',
        'IDENTITY_DB_NAME', 'IDENTITY_DB_USER', 'IDENTITY_DB_PASSWORD',
        'TRADE_DB_NAME', 'TRADE_DB_USER', 'TRADE_DB_PASSWORD'
    )
    $missing = @($required | Where-Object {
            -not [Environment]::GetEnvironmentVariable($_, 'Process')
        })
    if ($missing.Count -gt 0) {
        throw "Missing required local database settings: $($missing -join ', ')"
    }
}

function Assert-BusinessApplicationsStopped {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $businessPorts -contains $_.LocalPort })
    if ($listeners.Count -gt 0) {
        $details = $listeners |
            Sort-Object LocalPort |
            ForEach-Object { "$($_.LocalPort)/pid=$($_.OwningProcess)" }
        throw "M7 scale data is an offline fixture. Stop business applications first: $($details -join ', ')"
    }
}

function Assert-MySqlReady {
    $status = docker inspect --format '{{.State.Status}}' plainjournal-mysql 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne 'running') {
        throw 'The plainjournal-mysql container must already be running.'
    }
}

function Assert-ResourceBudget {
    $thresholds = @{
        Small = @{ freeMemoryBytes = 2GB; freeDiskBytes = 15GB }
        Medium = @{ freeMemoryBytes = 3GB; freeDiskBytes = 50GB }
        Formal = @{ freeMemoryBytes = 4GB; freeDiskBytes = 100GB }
    }
    $threshold = $thresholds[$Scale]
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $freeMemoryBytes = [long]$operatingSystem.FreePhysicalMemory * 1KB
    $drive = Get-PSDrive -Name C
    if ($freeMemoryBytes -lt $threshold.freeMemoryBytes) {
        throw "M7 $Scale seed requires at least $([Math]::Round($threshold.freeMemoryBytes / 1GB, 1)) GiB " +
            "free host memory; current free memory is $([Math]::Round($freeMemoryBytes / 1GB, 2)) GiB."
    }
    if ([long]$drive.Free -lt $threshold.freeDiskBytes) {
        throw "M7 $Scale seed requires at least $([Math]::Round($threshold.freeDiskBytes / 1GB, 1)) GiB " +
            "free space on C:; current free space is $([Math]::Round([long]$drive.Free / 1GB, 2)) GiB."
    }

    $mysqlInspect = @(docker inspect plainjournal-mysql | ConvertFrom-Json)[0]
    if ($mysqlInspect.State.OOMKilled) {
        throw 'The MySQL container reports OOMKilled=true; recreate it before an M7 capacity run.'
    }
    $heavyContainers = @(
        'plainjournal-prometheus', 'plainjournal-alertmanager', 'plainjournal-grafana', 'plainjournal-tempo',
        'plainjournal-mysql-replica', 'plainjournal-mysql-trade-shard-1'
    )
    $running = @(docker ps --format '{{.Names}}')
    $conflicts = @($heavyContainers | Where-Object { $running -contains $_ })
    if ($conflicts.Count -gt 0) {
        throw "M7 scale generation uses an exclusive profile. Stop conflicting containers first: " +
            ($conflicts -join ', ')
    }
}

function Invoke-DatabaseSql {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Sql
    )

    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$Password",
        'plainjournal-mysql',
        'mysql',
        "--user=$User",
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names',
        $Database
    )
    $output = @($Sql | docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed for $Database`: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-DatabaseStream {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][scriptblock]$WriteSql
    )

    $dockerCommand = Get-Command docker -ErrorAction Stop
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $dockerCommand.Source
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardInputEncoding = $utf8WithoutBom
    $startInfo.StandardOutputEncoding = $utf8WithoutBom
    $startInfo.StandardErrorEncoding = $utf8WithoutBom
    foreach ($argument in @(
            'exec', '-i',
            '-e', "MYSQL_PWD=$Password",
            'plainjournal-mysql',
            'mysql',
            "--user=$User",
            '--default-character-set=utf8mb4',
            '--batch',
            '--skip-column-names',
            $Database
        )) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Unable to start MySQL stream for $Database."
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    try {
        & $WriteSql $process.StandardInput
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        if ($process.ExitCode -ne 0) {
            throw "MySQL stream failed for $Database`: $stderr`n$stdout"
        }
        if ($stderr) {
            Write-Verbose $stderr
        }
    }
    finally {
        if (-not $process.HasExited) {
            $process.Kill($true)
        }
        $process.Dispose()
    }
}

function Invoke-TimedStage {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][long]$Rows,
        [Parameter(Mandatory)][scriptblock]$Body
    )

    $startedAt = Get-Date
    & $Body
    $elapsed = ((Get-Date) - $startedAt).TotalSeconds
    $script:stageTimings.Add([ordered]@{
            name = $Name
            rows = $Rows
            elapsedSeconds = [Math]::Round($elapsed, 3)
            rowsPerSecond = if ($elapsed -gt 0 -and $Rows -gt 0) {
                [Math]::Round($Rows / $elapsed, 2)
            } else {
                0
            }
        })
}

function Write-SequencePreamble {
    param([Parameter(Mandatory)][IO.StreamWriter]$Writer)

    $Writer.WriteLine("SET SESSION cte_max_recursion_depth = $($BatchSize + 1);")
    $Writer.WriteLine('DROP TEMPORARY TABLE IF EXISTS m7_numbers;')
    $Writer.WriteLine('CREATE TEMPORARY TABLE m7_numbers (n INT NOT NULL PRIMARY KEY);')
    $Writer.WriteLine(@"
INSERT INTO m7_numbers (n)
WITH RECURSIVE sequence(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM sequence WHERE n + 1 < $BatchSize
)
SELECT n FROM sequence;
"@)
    $Writer.Flush()
}

function Write-BatchedInsertSelect {
    param(
        [Parameter(Mandatory)][IO.StreamWriter]$Writer,
        [Parameter(Mandatory)][long]$Count,
        [Parameter(Mandatory)][string]$InsertSql
    )

    for ($start = 1L; $start -le $Count; $start += $BatchSize) {
        $currentCount = [Math]::Min([long]$BatchSize, $Count - $start + 1)
        $Writer.WriteLine("SET @m7_start = $start;")
        $Writer.WriteLine("SET @m7_count = $currentCount;")
        $Writer.WriteLine('START TRANSACTION;')
        $Writer.WriteLine($InsertSql)
        $Writer.WriteLine('COMMIT;')
        $Writer.Flush()
    }
}

function Invoke-InsertSelectStage {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][long]$Rows,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$InsertSql
    )

    Invoke-TimedStage -Name $Name -Rows $Rows -Body {
        Invoke-DatabaseStream -Database $Database -User $User -Password $Password -WriteSql {
            param($writer)
            $writer.WriteLine("SET time_zone = '+00:00';")
            Write-SequencePreamble -Writer $writer
            Write-BatchedInsertSelect -Writer $writer -Count $Rows -InsertSql $InsertSql
        }
    }
}

function Convert-LabeledRows {
    param([string[]]$Rows)

    $result = [ordered]@{}
    foreach ($row in $Rows) {
        $parts = $row -split "`t", 2
        if ($parts.Count -eq 2) {
            $value = 0L
            if ([long]::TryParse($parts[1], [ref]$value)) {
                $result[$parts[0]] = $value
            } else {
                $result[$parts[0]] = $parts[1]
            }
        }
    }
    return $result
}

function Get-TableMaximumId {
    param(
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$Column,
        [Parameter(Mandatory)][long]$Base
    )

    $upper = $Base + $reservedRowsPerTable
    $rows = @(Invoke-DatabaseSql -Database $Database -User $User -Password $Password -Sql `
            "SELECT COALESCE(MAX($Column), $Base) FROM $Table WHERE $Column > $Base AND $Column <= $upper;")
    if ($rows.Count -ne 1) {
        throw "Unable to determine M7 maximum id for $Database.$Table."
    }
    return [long]$rows[0]
}

function Write-RangeDeletes {
    param(
        [Parameter(Mandatory)][IO.StreamWriter]$Writer,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$Column,
        [Parameter(Mandatory)][long]$Base,
        [Parameter(Mandatory)][long]$Maximum
    )

    if ($Maximum -le $Base) {
        return
    }
    $deleteSpan = [Math]::Max($BatchSize * 5, 10000)
    for ($start = $Base + 1; $start -le $Maximum; $start += $deleteSpan) {
        $end = [Math]::Min($Maximum, $start + $deleteSpan - 1)
        $Writer.WriteLine('START TRANSACTION;')
        $Writer.WriteLine("DELETE FROM $Table WHERE $Column BETWEEN $start AND $end;")
        $Writer.WriteLine('COMMIT;')
        $Writer.Flush()
    }
}

function Remove-M7Data {
    $tradeTables = @(
        @{ name = 'order_discount_allocation'; column = 'id'; base = $id.discountAllocationBase },
        @{ name = 'order_price_snapshot'; column = 'id'; base = $id.priceSnapshotBase },
        @{ name = 'order_address_snapshot'; column = 'id'; base = $id.orderAddressBase },
        @{ name = 'order_status_history'; column = 'id'; base = $id.orderHistoryBase },
        @{ name = 'order_item'; column = 'id'; base = $id.orderItemBase },
        @{ name = 'trade_order'; column = 'id'; base = $id.orderBase }
    )
    $tradeMaximums = foreach ($table in $tradeTables) {
        [ordered]@{
            name = $table.name
            column = $table.column
            base = [long]$table.base
            maximum = Get-TableMaximumId `
                -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD -Table $table.name `
                -Column $table.column -Base ([long]$table.base)
        }
    }
    Invoke-TimedStage -Name 'remove-trade' -Rows 0 -Body {
        Invoke-DatabaseStream -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD -WriteSql {
            param($writer)
            foreach ($table in $tradeMaximums) {
                Write-RangeDeletes -Writer $writer -Table $table.name -Column $table.column `
                    -Base $table.base -Maximum $table.maximum
            }
        }
    }

    $maximumUserId = Get-TableMaximumId `
        -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD -Table 'user_account' -Column 'id' -Base $id.userBase
    Invoke-TimedStage -Name 'remove-identity' -Rows 0 -Body {
        Invoke-DatabaseStream -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
            -Password $env:IDENTITY_DB_PASSWORD -WriteSql {
            param($writer)
            if ($maximumUserId -gt $id.userBase) {
                Write-RangeDeletes -Writer $writer -Table 'user_role' -Column 'user_id' `
                    -Base $id.userBase -Maximum $maximumUserId
                Write-RangeDeletes -Writer $writer -Table 'refresh_token' -Column 'user_id' `
                    -Base $id.userBase -Maximum $maximumUserId
                Write-RangeDeletes -Writer $writer -Table 'user_address' -Column 'user_id' `
                    -Base $id.userBase -Maximum $maximumUserId
                Write-RangeDeletes -Writer $writer -Table 'user_account' -Column 'id' `
                    -Base $id.userBase -Maximum $maximumUserId
            }
            $writer.WriteLine("DELETE FROM login_record WHERE normalized_email LIKE 'm7.user.%@plainjournal.local';")
            $writer.Flush()
        }
    }

    $maximumSkuId = Get-TableMaximumId `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -Table 'product_sku' -Column 'id' -Base $id.skuBase
    $maximumProductId = Get-TableMaximumId `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -Table 'product_spu' -Column 'id' -Base $id.productBase
    Invoke-TimedStage -Name 'remove-catalog' -Rows 0 -Body {
        Invoke-DatabaseStream -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
            -Password $env:CATALOG_DB_PASSWORD -WriteSql {
            param($writer)
            if ($maximumSkuId -gt $id.skuBase) {
                Write-RangeDeletes -Writer $writer -Table 'product_sku' -Column 'id' `
                    -Base $id.skuBase -Maximum $maximumSkuId
            }
            if ($maximumProductId -gt $id.productBase) {
                Write-RangeDeletes -Writer $writer -Table 'product_media' -Column 'spu_id' `
                    -Base $id.productBase -Maximum $maximumProductId
                Write-RangeDeletes -Writer $writer -Table 'product_spu' -Column 'id' `
                    -Base $id.productBase -Maximum $maximumProductId
            }
            $writer.WriteLine("DELETE FROM catalog_brand WHERE id = $($id.brand) OR slug = 'm7-scale-brand';")
            $writer.WriteLine("DELETE FROM catalog_category WHERE id = $($id.category) OR slug = 'm7-scale-category';")
            $writer.Flush()
        }
    }
}

function Seed-Catalog {
    Invoke-TimedStage -Name 'catalog-dimensions' -Rows 2 -Body {
        [void](Invoke-DatabaseSql `
                -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
                -Password $env:CATALOG_DB_PASSWORD -Sql @"
SET time_zone = '+00:00';
START TRANSACTION;
INSERT INTO catalog_category
    (id, parent_id, name, slug, status, sort_order, version, created_at, updated_at)
VALUES
    ($($id.category), NULL, 'M7 Scale Category', 'm7-scale-category',
     'ACTIVE', 0, 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
INSERT INTO catalog_brand
    (id, name, slug, logo_object_key, status, version, created_at, updated_at)
VALUES
    ($($id.brand), 'M7 Scale Brand', 'm7-scale-brand',
     NULL, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
COMMIT;
"@)
    }

    Invoke-InsertSelectStage -Name 'catalog-spu' -Rows $SpuCount `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -InsertSql @"
INSERT INTO product_spu
    (id, category_id, brand_id, title, subtitle, description, status,
     version, created_at, updated_at)
SELECT
    $($id.productBase) + (@m7_start + n),
    $($id.category),
    $($id.brand),
    CONCAT('M7 Scale Product ', LPAD(@m7_start + n, 9, '0')),
    'Repeatable M7 scale fixture',
    'Offline scale and query baseline data',
    'ACTIVE',
    0,
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-01-01 00:00:00.000'),
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-01-01 00:00:00.000')
FROM m7_numbers
WHERE n < @m7_count;
"@

    $skuCount = [long]$SpuCount * $SkuPerSpu
    Invoke-InsertSelectStage -Name 'catalog-sku' -Rows $skuCount `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -InsertSql @"
INSERT INTO product_sku
    (id, spu_id, sku_code, name, spec_json, sale_price, market_price,
     status, version, created_at, updated_at)
SELECT
    $($id.skuBase) + (@m7_start + n),
    $($id.productBase) + FLOOR((@m7_start + n - 1) / $SkuPerSpu) + 1,
    CONCAT('M7-SKU-', LPAD(@m7_start + n, 9, '0')),
    CONCAT('M7 SKU ', LPAD(@m7_start + n, 9, '0')),
    CONCAT('{"profile":"$($Scale.ToLowerInvariant())","variant":',
           MOD(@m7_start + n - 1, $SkuPerSpu) + 1, '}'),
    29.90 + MOD(@m7_start + n - 1, 100),
    39.90 + MOD(@m7_start + n - 1, 100),
    'ACTIVE',
    0,
    TIMESTAMPADD(SECOND, $SpuCount + @m7_start + n, '2026-01-01 00:00:00.000'),
    TIMESTAMPADD(SECOND, $SpuCount + @m7_start + n, '2026-01-01 00:00:00.000')
FROM m7_numbers
WHERE n < @m7_count;
"@
}

function Seed-Identity {
    Invoke-InsertSelectStage -Name 'identity-users' -Rows $UserCount `
        -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD -InsertSql @"
INSERT INTO user_account
    (id, email, password_hash, display_name, status, version, created_at, updated_at)
SELECT
    $($id.userBase) + (@m7_start + n),
    CONCAT('m7.user.', LPAD(@m7_start + n, 6, '0'), '$fixtureEmailSuffix'),
    '$fixturePasswordHash',
    CONCAT('M7 User ', LPAD(@m7_start + n, 6, '0')),
    'ACTIVE',
    0,
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-01-15 00:00:00.000'),
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-01-15 00:00:00.000')
FROM m7_numbers
WHERE n < @m7_count;
"@
    Invoke-TimedStage -Name 'identity-user-roles' -Rows $UserCount -Body {
        [void](Invoke-DatabaseSql `
                -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
                -Password $env:IDENTITY_DB_PASSWORD -Sql @"
INSERT INTO user_role (user_id, role_id, created_at)
SELECT id, (SELECT id FROM identity_role WHERE code = 'CUSTOMER'), created_at
FROM user_account
WHERE id BETWEEN $($id.userBase + 1) AND $($id.userBase + $UserCount);
"@)
    }
}

function Get-TradeUserIndexSql {
    if ($UserCount -eq 1) {
        return '1'
    }
    return "(CASE WHEN (@m7_start + n) <= $DenseUserOrderCount THEN 1 " +
        "ELSE 2 + MOD((@m7_start + n) - $DenseUserOrderCount - 1, $($UserCount - 1)) END)"
}

function Seed-Trade {
    $skuCount = [long]$SpuCount * $SkuPerSpu
    $originalAmount = ([decimal]49.90 * $ItemsPerOrder).ToString(
        '0.00', [Globalization.CultureInfo]::InvariantCulture)
    $discountSql = "(CASE WHEN MOD(@m7_start + n, $DiscountEvery) = 0 THEN 5.00 ELSE 0.00 END)"
    $userIndexSql = Get-TradeUserIndexSql

    Invoke-InsertSelectStage -Name 'trade-orders' -Rows $OrderCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO trade_order
    (id, order_no, user_id, idempotency_key, request_hash, reservation_no,
     warehouse_code, warehouse_id, status, original_amount, discount_amount,
     total_amount, marketing_lock_no, payment_deadline, close_reason,
     recovery_attempts, next_recovery_at, last_error, version, created_at, updated_at,
     order_source, source_reference)
SELECT
    $($id.orderBase) + (@m7_start + n),
    CONCAT('M7-ORD-', LPAD(@m7_start + n, 9, '0')),
    $($id.userBase) + $userIndexSql,
    CONCAT('M7-IDEMP-', LPAD(@m7_start + n, 9, '0')),
    LOWER(LPAD(HEX(@m7_start + n), 64, '0')),
    CONCAT('M7-RSV-', LPAD(@m7_start + n, 9, '0')),
    'PRIMARY',
    NULL,
    'COMPLETED',
    $originalAmount,
    $discountSql,
    $originalAmount - $discountSql,
    CONCAT('M7-LOCK-', LPAD(@m7_start + n, 9, '0')),
    TIMESTAMPADD(SECOND, 1800 + @m7_start + n, '2026-02-01 00:00:00.000'),
    NULL,
    0,
    NULL,
    NULL,
    5,
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-02-01 00:00:00.000'),
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-02-01 00:00:00.000'),
    'STANDARD',
    NULL
FROM m7_numbers
WHERE n < @m7_count;
"@

    $itemCount = [long]$OrderCount * $ItemsPerOrder
    Invoke-InsertSelectStage -Name 'trade-order-items' -Rows $itemCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO order_item
    (id, order_id, product_id, sku_id, product_title, sku_code, sku_name,
     spec_json, image_object_key, unit_price, quantity, line_amount, created_at,
     line_no, discount_amount, payable_amount)
SELECT
    $($id.orderItemBase) + item_index,
    $($id.orderBase) + order_index,
    $($id.productBase) + FLOOR((sku_index - 1) / $SkuPerSpu) + 1,
    $($id.skuBase) + sku_index,
    CONCAT('M7 Scale Product ', LPAD(FLOOR((sku_index - 1) / $SkuPerSpu) + 1, 9, '0')),
    CONCAT('M7-SKU-', LPAD(sku_index, 9, '0')),
    CONCAT('M7 SKU ', LPAD(sku_index, 9, '0')),
    '{"profile":"m7-scale"}',
    NULL,
    49.90,
    1,
    49.90,
    TIMESTAMPADD(SECOND, order_index, '2026-02-01 00:00:00.000'),
    line_no,
    CASE WHEN line_no = 1 AND MOD(order_index, $DiscountEvery) = 0 THEN 5.00 ELSE 0.00 END,
    49.90 - CASE WHEN line_no = 1 AND MOD(order_index, $DiscountEvery) = 0
                 THEN 5.00 ELSE 0.00 END
FROM (
    SELECT
        @m7_start + n AS item_index,
        FLOOR((@m7_start + n - 1) / $ItemsPerOrder) + 1 AS order_index,
        MOD(@m7_start + n - 1, $ItemsPerOrder) + 1 AS line_no,
        MOD(@m7_start + n - 1, $skuCount) + 1 AS sku_index
    FROM m7_numbers
    WHERE n < @m7_count
) generated_rows;
"@

    Invoke-InsertSelectStage -Name 'trade-order-addresses' -Rows $OrderCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO order_address_snapshot
    (id, order_id, source_address_id, recipient_name, phone, province, city,
     district, detail_address, postal_code, created_at,
     province_code, city_code, district_code)
SELECT
    $($id.orderAddressBase) + (@m7_start + n),
    $($id.orderBase) + (@m7_start + n),
    $($id.userBase) + $userIndexSql,
    CONCAT('M7 User ', LPAD($userIndexSql, 6, '0')),
    CONCAT('138', LPAD(MOD($userIndexSql, 100000000), 8, '0')),
    '浙江省',
    '杭州市',
    '西湖区',
    CONCAT('M7 scale road ', $userIndexSql),
    '310000',
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-02-01 00:00:00.000'),
    '330000',
    '330100',
    '330106'
FROM m7_numbers
WHERE n < @m7_count;
"@

    Invoke-InsertSelectStage -Name 'trade-price-snapshots' -Rows $OrderCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO order_price_snapshot
    (id, order_id, marketing_lock_no, original_amount, coupon_discount,
     red_packet_discount, subsidy_discount, discount_amount, payable_amount,
     pricing_version, created_at)
SELECT
    $($id.priceSnapshotBase) + (@m7_start + n),
    $($id.orderBase) + (@m7_start + n),
    CONCAT('M7-LOCK-', LPAD(@m7_start + n, 9, '0')),
    $originalAmount,
    $discountSql,
    0.00,
    0.00,
    $discountSql,
    $originalAmount - $discountSql,
    'm7-scale-v1',
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-02-01 00:00:00.000')
FROM m7_numbers
WHERE n < @m7_count;
"@

    $allocationCount = [long][Math]::Floor($OrderCount / $DiscountEvery)
    Invoke-InsertSelectStage -Name 'trade-discount-allocations' -Rows $allocationCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO order_discount_allocation
    (id, order_id, order_item_id, line_no, sku_id, benefit_no, rule_code,
     benefit_type, discount_amount, created_at)
SELECT
    $($id.discountAllocationBase) + allocation_index,
    $($id.orderBase) + order_index,
    $($id.orderItemBase) + ((order_index - 1) * $ItemsPerOrder) + 1,
    1,
    $($id.skuBase) + MOD((order_index - 1) * $ItemsPerOrder, $skuCount) + 1,
    CONCAT('M7-BENEFIT-', LPAD(order_index, 9, '0')),
    'M7-SCALE-COUPON',
    'COUPON',
    5.00,
    TIMESTAMPADD(SECOND, order_index, '2026-02-01 00:00:00.000')
FROM (
    SELECT
        @m7_start + n AS allocation_index,
        (@m7_start + n) * $DiscountEvery AS order_index
    FROM m7_numbers
    WHERE n < @m7_count
) generated_rows;
"@

    Invoke-InsertSelectStage -Name 'trade-order-history' -Rows $OrderCount `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -InsertSql @"
INSERT INTO order_status_history
    (id, order_id, from_status, to_status, command, reason,
     operator_type, operator_id, created_at)
SELECT
    $($id.orderHistoryBase) + (@m7_start + n),
    $($id.orderBase) + (@m7_start + n),
    'SHIPPED',
    'COMPLETED',
    'M7_SCALE_IMPORT',
    'Repeatable historical scale fixture',
    'SYSTEM',
    'm7-seed',
    TIMESTAMPADD(SECOND, @m7_start + n, '2026-02-01 00:00:00.000')
FROM m7_numbers
WHERE n < @m7_count;
"@
}

function Analyze-M7Tables {
    Invoke-TimedStage -Name 'analyze-scale-tables' -Rows 12 -Body {
        [void](Invoke-DatabaseSql `
                -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
                -Password $env:CATALOG_DB_PASSWORD -Sql @"
ANALYZE TABLE catalog_category, catalog_brand, product_spu, product_sku;
"@)
        [void](Invoke-DatabaseSql `
                -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
                -Password $env:IDENTITY_DB_PASSWORD -Sql @"
ANALYZE TABLE user_account, user_role;
"@)
        [void](Invoke-DatabaseSql `
                -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
                -Password $env:TRADE_DB_PASSWORD -Sql @"
ANALYZE TABLE trade_order, order_item, order_address_snapshot,
              order_price_snapshot, order_discount_allocation, order_status_history;
"@)
    }
}

function Get-M7Counts {
    $catalogRows = Invoke-DatabaseSql `
        -Database $env:CATALOG_DB_NAME -User $env:CATALOG_DB_USER `
        -Password $env:CATALOG_DB_PASSWORD -Sql @"
SELECT 'categories', COUNT(*) FROM catalog_category WHERE id = $($id.category);
SELECT 'brands', COUNT(*) FROM catalog_brand WHERE id = $($id.brand);
SELECT 'spus', COUNT(*) FROM product_spu
WHERE id > $($id.productBase) AND id <= $($id.productBase + $reservedRowsPerTable);
SELECT 'skus', COUNT(*) FROM product_sku
WHERE id > $($id.skuBase) AND id <= $($id.skuBase + $reservedRowsPerTable);
"@
    $identityRows = Invoke-DatabaseSql `
        -Database $env:IDENTITY_DB_NAME -User $env:IDENTITY_DB_USER `
        -Password $env:IDENTITY_DB_PASSWORD -Sql @"
SELECT 'users', COUNT(*) FROM user_account
WHERE id > $($id.userBase) AND id <= $($id.userBase + $reservedRowsPerTable);
SELECT 'roles', COUNT(*) FROM user_role
WHERE user_id > $($id.userBase) AND user_id <= $($id.userBase + $reservedRowsPerTable);
"@
    $tradeRows = Invoke-DatabaseSql `
        -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
        -Password $env:TRADE_DB_PASSWORD -Sql @"
SELECT 'orders', COUNT(*) FROM trade_order
WHERE id > $($id.orderBase) AND id <= $($id.orderBase + $reservedRowsPerTable);
SELECT 'dense_user_orders', COUNT(*) FROM trade_order WHERE user_id = $($id.userBase + 1);
SELECT 'order_items', COUNT(*) FROM order_item
WHERE id > $($id.orderItemBase) AND id <= $($id.orderItemBase + $reservedRowsPerTable);
SELECT 'order_addresses', COUNT(*) FROM order_address_snapshot
WHERE id > $($id.orderAddressBase) AND id <= $($id.orderAddressBase + $reservedRowsPerTable);
SELECT 'price_snapshots', COUNT(*) FROM order_price_snapshot
WHERE id > $($id.priceSnapshotBase) AND id <= $($id.priceSnapshotBase + $reservedRowsPerTable);
SELECT 'discount_allocations', COUNT(*) FROM order_discount_allocation
WHERE id > $($id.discountAllocationBase) AND id <= $($id.discountAllocationBase + $reservedRowsPerTable);
SELECT 'order_history', COUNT(*) FROM order_status_history
WHERE id > $($id.orderHistoryBase) AND id <= $($id.orderHistoryBase + $reservedRowsPerTable);
SELECT 'missing_required_children', COUNT(*)
FROM trade_order o
LEFT JOIN order_address_snapshot a ON a.order_id = o.id
LEFT JOIN order_price_snapshot p ON p.order_id = o.id
WHERE o.id > $($id.orderBase) AND o.id <= $($id.orderBase + $reservedRowsPerTable)
  AND (a.id IS NULL OR p.id IS NULL);
SELECT 'order_amount_mismatches', COUNT(*)
FROM (
    SELECT o.id
    FROM trade_order o
    LEFT JOIN order_item i ON i.order_id = o.id
    WHERE o.id > $($id.orderBase) AND o.id <= $($id.orderBase + $reservedRowsPerTable)
    GROUP BY o.id, o.original_amount, o.discount_amount, o.total_amount
    HAVING COUNT(i.id) <> $ItemsPerOrder
       OR COALESCE(SUM(i.line_amount), 0) <> o.original_amount
       OR COALESCE(SUM(i.discount_amount), 0) <> o.discount_amount
       OR COALESCE(SUM(i.payable_amount), 0) <> o.total_amount
) mismatches;
"@
    return [ordered]@{
        catalog = Convert-LabeledRows -Rows $catalogRows
        identity = Convert-LabeledRows -Rows $identityRows
        trade = Convert-LabeledRows -Rows $tradeRows
    }
}

function Assert-Count {
    param(
        [Parameter(Mandatory)][long]$Actual,
        [Parameter(Mandatory)][long]$Expected,
        [Parameter(Mandatory)][string]$Name
    )

    if ($Actual -ne $Expected) {
        throw "M7 scale count mismatch for $Name`: expected $Expected, actual $Actual."
    }
}

function Assert-SeededData {
    $counts = Get-M7Counts
    $skuCount = [long]$SpuCount * $SkuPerSpu
    $itemCount = [long]$OrderCount * $ItemsPerOrder
    $allocationCount = [long][Math]::Floor($OrderCount / $DiscountEvery)
    $denseExpected = if ($UserCount -eq 1) {
        $OrderCount
    } else {
        [Math]::Min($DenseUserOrderCount, $OrderCount)
    }

    Assert-Count $counts.catalog.categories 1 'catalog categories'
    Assert-Count $counts.catalog.brands 1 'catalog brands'
    Assert-Count $counts.catalog.spus $SpuCount 'catalog SPUs'
    Assert-Count $counts.catalog.skus $skuCount 'catalog SKUs'
    Assert-Count $counts.identity.users $UserCount 'identity users'
    Assert-Count $counts.identity.roles $UserCount 'identity roles'
    Assert-Count $counts.trade.orders $OrderCount 'trade orders'
    Assert-Count $counts.trade.dense_user_orders $denseExpected 'dense-user orders'
    Assert-Count $counts.trade.order_items $itemCount 'trade order items'
    Assert-Count $counts.trade.order_addresses $OrderCount 'trade order addresses'
    Assert-Count $counts.trade.price_snapshots $OrderCount 'trade price snapshots'
    Assert-Count $counts.trade.discount_allocations $allocationCount 'trade discount allocations'
    Assert-Count $counts.trade.order_history $OrderCount 'trade order history'
    Assert-Count $counts.trade.missing_required_children 0 'trade required children'
    Assert-Count $counts.trade.order_amount_mismatches 0 'trade amount invariants'
    return $counts
}

function Assert-RemovedData {
    $counts = Get-M7Counts
    foreach ($domain in $counts.Keys) {
        foreach ($entry in $counts[$domain].GetEnumerator()) {
            Assert-Count ([long]$entry.Value) 0 "$domain $($entry.Key)"
        }
    }
    return $counts
}

function Get-StorageSnapshot {
    $rows = Invoke-DatabaseSql `
        -Database 'information_schema' -User 'root' `
        -Password $env:MYSQL_ROOT_PASSWORD -Sql @"
SELECT CONCAT(table_schema, '.', table_name),
       CONCAT(table_rows, '|', data_length, '|', index_length)
FROM information_schema.tables
WHERE table_schema IN ('$($env:CATALOG_DB_NAME)', '$($env:IDENTITY_DB_NAME)', '$($env:TRADE_DB_NAME)')
  AND table_name IN (
      'catalog_category', 'catalog_brand', 'product_spu', 'product_sku',
      'user_account', 'user_role',
      'trade_order', 'order_item', 'order_address_snapshot',
      'order_price_snapshot', 'order_discount_allocation', 'order_status_history'
  )
ORDER BY table_schema, table_name;
"@
    $tables = [ordered]@{}
    foreach ($row in $rows) {
        $parts = $row -split "`t", 2
        $values = $parts[1] -split '\|'
        $tables[$parts[0]] = [ordered]@{
            approximateRows = [long]$values[0]
            dataBytes = [long]$values[1]
            indexBytes = [long]$values[2]
        }
    }
    return $tables
}

function Get-HostSnapshot {
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $drive = Get-PSDrive -Name C
    $mysqlInspect = @(docker inspect plainjournal-mysql | ConvertFrom-Json)[0]
    $mysqlStatsRaw = docker stats plainjournal-mysql --no-stream --format '{{json .}}'
    $mysqlStats = if ($LASTEXITCODE -eq 0 -and $mysqlStatsRaw) {
        $mysqlStatsRaw | ConvertFrom-Json
    } else {
        $null
    }
    $mysqlRuntime = Convert-LabeledRows -Rows (Invoke-DatabaseSql `
            -Database $env:TRADE_DB_NAME -User $env:TRADE_DB_USER `
            -Password $env:TRADE_DB_PASSWORD -Sql @"
SELECT 'mysql_version', @@version;
SELECT 'innodb_buffer_pool_bytes', @@innodb_buffer_pool_size;
SELECT 'max_connections', @@max_connections;
SELECT 'threads_connected', VARIABLE_VALUE
FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_connected';
SELECT 'threads_running', VARIABLE_VALUE
FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_running';
"@)
    return [ordered]@{
        capturedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        host = [ordered]@{
            totalMemoryBytes = [long]$operatingSystem.TotalVisibleMemorySize * 1KB
            freeMemoryBytes = [long]$operatingSystem.FreePhysicalMemory * 1KB
            systemDriveFreeBytes = [long]$drive.Free
        }
        mysqlContainer = $mysqlStats
        mysqlContainerState = [ordered]@{
            restartCount = [int]$mysqlInspect.RestartCount
            oomKilled = [bool]$mysqlInspect.State.OOMKilled
            startedAt = [string]$mysqlInspect.State.StartedAt
        }
        mysql = $mysqlRuntime
    }
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$envFile = Join-Path $repositoryRoot 'deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing local middleware configuration: $envFile"
}
Import-DotEnv -Path $envFile
Assert-RequiredEnvironment
Assert-MySqlReady

$profile = $scaleProfiles[$Scale]
if ($SpuCount -eq 0) {
    $SpuCount = $profile.spus
}
if ($SkuPerSpu -eq 0) {
    $SkuPerSpu = $profile.skuPerSpu
}
if ($UserCount -eq 0) {
    $UserCount = $profile.users
}
if ($OrderCount -eq 0) {
    $OrderCount = $profile.orders
}
if ($DenseUserOrderCount -eq 0) {
    $DenseUserOrderCount = $profile.denseUserOrders
}
if ($ItemsPerOrder -eq 0) {
    $ItemsPerOrder = $profile.itemsPerOrder
}
if ($DiscountEvery -eq 0) {
    $DiscountEvery = $profile.discountEvery
}

if ($DenseUserOrderCount -gt $OrderCount) {
    throw 'DenseUserOrderCount cannot be greater than OrderCount.'
}
if ($Scale -eq 'Formal' -and $Action -eq 'Seed' -and -not $AllowFormal) {
    throw 'Formal is an explicit upper-bound experiment. Re-run with -AllowFormal after Small and Medium pass.'
}
$skuCount = [long]$SpuCount * $SkuPerSpu
$itemCount = [long]$OrderCount * $ItemsPerOrder
foreach ($entry in @{
        spus = [long]$SpuCount
        skus = $skuCount
        users = [long]$UserCount
        orders = [long]$OrderCount
        orderItems = $itemCount
    }.GetEnumerator()) {
    if ($entry.Value -gt $reservedRowsPerTable) {
        throw "Requested $($entry.Key) exceeds the reserved M7 id range of $reservedRowsPerTable."
    }
}

if (-not $ManifestPath) {
    $ManifestPath = Join-Path $backendRoot ".run\m7-scale-data-$($Scale.ToLowerInvariant()).json"
}
$resolvedManifestPath = [IO.Path]::GetFullPath($ManifestPath)
if ($Action -in @('Seed', 'Remove')) {
    Assert-BusinessApplicationsStopped
}
if ($Action -eq 'Seed') {
    Assert-ResourceBudget
}

$startedAt = Get-Date
$before = [ordered]@{
    storage = Get-StorageSnapshot
    environment = Get-HostSnapshot
}
switch ($Action) {
    'Seed' {
        Remove-M7Data
        Seed-Catalog
        Seed-Identity
        Seed-Trade
        Analyze-M7Tables
        $counts = Assert-SeededData
    }
    'Verify' {
        $counts = Assert-SeededData
    }
    'Remove' {
        Remove-M7Data
        $counts = Assert-RemovedData
    }
}
$after = [ordered]@{
    storage = Get-StorageSnapshot
    environment = Get-HostSnapshot
}
if ($after.environment.mysqlContainerState.oomKilled `
        -or $after.environment.mysqlContainerState.restartCount `
        -ne $before.environment.mysqlContainerState.restartCount `
        -or $after.environment.mysqlContainerState.startedAt `
        -ne $before.environment.mysqlContainerState.startedAt) {
    throw 'The MySQL container restarted or was OOM-killed during the M7 data operation.'
}

$manifestDirectory = Split-Path -Parent $resolvedManifestPath
New-Item -ItemType Directory -Path $manifestDirectory -Force | Out-Null
$manifest = [ordered]@{
    schemaVersion = 1
    action = $Action
    scale = $Scale
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    elapsedSeconds = [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    fixture = [ordered]@{
        prefix = $fixturePrefix
        credentialProfile = 'm7-local-fixture-v1'
        password = 'M5-PlainJournal-2026!'
        denseUserEmail = "m7.user.000001$fixtureEmailSuffix"
        denseUserId = [string]($id.userBase + 1)
        categoryId = [string]$id.category
        brandId = [string]$id.brand
        firstProductId = [string]($id.productBase + 1)
        lastProductId = [string]($id.productBase + $SpuCount)
        firstOrderNo = 'M7-ORD-000000001'
        lastOrderNo = "M7-ORD-$($OrderCount.ToString('000000000'))"
        idRanges = [ordered]@{
            category = [string]$id.category
            brand = [string]$id.brand
            productBase = [string]$id.productBase
            skuBase = [string]$id.skuBase
            userBase = [string]$id.userBase
            orderBase = [string]$id.orderBase
            orderItemBase = [string]$id.orderItemBase
            orderAddressBase = [string]$id.orderAddressBase
            priceSnapshotBase = [string]$id.priceSnapshotBase
            discountAllocationBase = [string]$id.discountAllocationBase
            orderHistoryBase = [string]$id.orderHistoryBase
        }
    }
    requested = [ordered]@{
        spus = $SpuCount
        skus = $skuCount
        users = $UserCount
        orders = $OrderCount
        denseUserOrders = $DenseUserOrderCount
        itemsPerOrder = $ItemsPerOrder
        orderItems = $itemCount
        discountEvery = $DiscountEvery
        discountAllocations = [long][Math]::Floor($OrderCount / $DiscountEvery)
        batchSize = $BatchSize
        formalExplicitlyAllowed = [bool]$AllowFormal
    }
    actual = $counts
    stageTimings = $script:stageTimings
    before = $before
    after = $after
    recovery = [ordered]@{
        resumeSupported = $false
        repeatedSeedStartsWithCleanup = $true
        explicitCleanupCommand =
            ".\tools\prepare-m7-scale-data.ps1 -Action Remove -Scale $Scale"
    }
}
$manifest | ConvertTo-Json -Depth 12 |
    Set-Content -LiteralPath $resolvedManifestPath -Encoding utf8
$manifest | ConvertTo-Json -Depth 12
