#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Initialize', 'Copy', 'CatchUp', 'Verify', 'Promote', 'Rollback', 'Status')]
    [string]$Action,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$')]
    [string]$JobId,
    [ValidateRange(1, 10000)]
    [int]$BatchSize = 500,
    [ValidateRange(0, 1000000)]
    [int]$MaxBatches = 0,
    [ValidateRange(0, 1000000)]
    [int]$FailAfterCommittedBatches = 0,
    [switch]$FinalWriteFence,
    [switch]$ConfirmNoTargetWrites,
    [string]$SourceShard0Schema,
    [string]$SourceShard1Schema,
    [string]$TargetShard0Schema,
    [string]$TargetShard1Schema,
    [string]$TargetShard2Schema,
    [string]$TargetShard3Schema,
    [string]$EnvironmentFile,
    [string]$CheckpointPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$backendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $backendRoot
$composeDirectory = Join-Path $repositoryRoot 'deploy\docker'
if (-not $EnvironmentFile) {
    $EnvironmentFile = Join-Path $composeDirectory '.env'
}
if (-not $CheckpointPath) {
    $CheckpointPath = Join-Path $backendRoot ".run\m7-trade-resharding-jobs\$JobId.json"
}

$userTables = @(
    'cart_user_lock',
    'cart_item',
    'cart_merge_request',
    'trade_order',
    'order_item',
    'order_status_history',
    'order_address_snapshot',
    'order_benefit_selection',
    'order_price_snapshot',
    'order_discount_allocation',
    'after_sale_order',
    'after_sale_item',
    'after_sale_history',
    'flash_sale_order_request',
    'outbox_event',
    'consumed_event',
    'reconciliation_record'
)
$controlTables = @('consumer_failure')
$allMigratedTables = @($userTables + $controlTables)
$rollbackTables = @(
    'reconciliation_record',
    'consumed_event',
    'outbox_event',
    'flash_sale_order_request',
    'after_sale_history',
    'after_sale_item',
    'after_sale_order',
    'order_discount_allocation',
    'order_price_snapshot',
    'order_benefit_selection',
    'order_address_snapshot',
    'order_status_history',
    'order_item',
    'trade_order',
    'cart_merge_request',
    'cart_item',
    'cart_user_lock',
    'consumer_failure'
)

function Import-DotEnv {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file does not exist: $Path"
    }
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

function Assert-Identifier {
    param(
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$Name
    )

    if ($Value -notmatch '^[a-z0-9_]+$' -or $Value.Length -gt 64) {
        throw "$Name must contain only lowercase letters, digits, and underscores."
    }
}

function ConvertTo-SqlLiteral {
    param([AllowEmptyString()][string]$Value)

    if ($null -eq $Value) {
        return 'NULL'
    }
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function Invoke-ContainerMySql {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $container = if ($PhysicalShard -eq 0) {
        'plainjournal-mysql'
    }
    else {
        'plainjournal-mysql-trade-shard-1'
    }
    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD",
        $container,
        'mysql',
        '--user=root',
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names'
    )
    $output = @($Sql | & docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed on physical shard $PhysicalShard`: " +
            ($output -join [Environment]::NewLine)
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { $_.ToString() } |
            Where-Object { $_.Length -gt 0 })
    }
}

function Invoke-MySqlRows {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Sql
    )

    return @((Invoke-ContainerMySql -PhysicalShard $PhysicalShard -Sql $Sql).Lines)
}

function Get-Scalar {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Sql
    )

    $rows = @(Invoke-MySqlRows -PhysicalShard $PhysicalShard -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar row on physical shard $PhysicalShard, " +
            "received $($rows.Count)."
    }
    return $rows[0]
}

function Save-Checkpoint {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    $directory = Split-Path -Parent $CheckpointPath
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $temporaryPath = "$CheckpointPath.tmp"
    $Checkpoint.updatedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    $Checkpoint | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporaryPath -Encoding utf8
    Move-Item -LiteralPath $temporaryPath -Destination $CheckpointPath -Force
}

function Load-Checkpoint {
    if (-not (Test-Path -LiteralPath $CheckpointPath -PathType Leaf)) {
        throw "Resharding checkpoint does not exist: $CheckpointPath"
    }
    $checkpoint = Get-Content -LiteralPath $CheckpointPath -Raw |
        ConvertFrom-Json -AsHashtable
    if ($checkpoint.jobId -ne $JobId) {
        throw "Checkpoint job ID does not match $JobId."
    }
    return $checkpoint
}

function Get-SourceSelection {
    param(
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$SourceSchema
    )

    switch ($Table) {
        { $_ -in @(
                'cart_user_lock', 'cart_item', 'cart_merge_request',
                'trade_order', 'after_sale_order', 'flash_sale_order_request') } {
            return [pscustomobject]@{
                From = "$SourceSchema.$Table source_row"
                Owner = 'source_row.user_id'
            }
        }
        { $_ -in @(
                'order_item', 'order_status_history', 'order_address_snapshot',
                'order_benefit_selection', 'order_price_snapshot',
                'order_discount_allocation') } {
            return [pscustomobject]@{
                From = "$SourceSchema.$Table source_row " +
                    "JOIN $SourceSchema.trade_order owner_order " +
                    "ON owner_order.id = source_row.order_id"
                Owner = 'owner_order.user_id'
            }
        }
        { $_ -in @('after_sale_item', 'after_sale_history') } {
            return [pscustomobject]@{
                From = "$SourceSchema.$Table source_row " +
                    "JOIN $SourceSchema.after_sale_order owner_after_sale " +
                    "ON owner_after_sale.id = source_row.after_sale_id"
                Owner = 'owner_after_sale.user_id'
            }
        }
        'outbox_event' {
            return [pscustomobject]@{
                From = @"
$SourceSchema.outbox_event source_row
LEFT JOIN $SourceSchema.trade_order owner_order
  ON source_row.aggregate_type = 'TradeOrder'
 AND owner_order.order_no = source_row.aggregate_id
LEFT JOIN $SourceSchema.after_sale_order owner_after_sale
  ON source_row.aggregate_type = 'AfterSaleOrder'
 AND owner_after_sale.after_sale_no = source_row.aggregate_id
LEFT JOIN $SourceSchema.flash_sale_order_request owner_flash
  ON source_row.aggregate_type = 'FlashSaleOrderRequest'
 AND owner_flash.request_token = source_row.aggregate_id
"@
                Owner = 'COALESCE(owner_order.user_id, owner_after_sale.user_id, owner_flash.user_id)'
            }
        }
        'consumed_event' {
            return [pscustomobject]@{
                From = "$SourceSchema.consumed_event source_row"
                Owner = 'source_row.owner_user_id'
            }
        }
        'reconciliation_record' {
            return [pscustomobject]@{
                From = @"
$SourceSchema.reconciliation_record source_row
LEFT JOIN $SourceSchema.trade_order owner_order
  ON source_row.domain = 'ORDER'
 AND owner_order.order_no = source_row.reference_no
LEFT JOIN $SourceSchema.after_sale_order owner_after_sale
  ON source_row.domain = 'AFTER_SALE'
 AND owner_after_sale.after_sale_no = source_row.reference_no
"@
                Owner = 'COALESCE(owner_order.user_id, owner_after_sale.user_id)'
            }
        }
        default {
            throw "Unsupported user-owned Trade table: $Table"
        }
    }
}

function Get-TableColumns {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Schema,
        [Parameter(Mandatory)][string]$Table
    )

    return @(Invoke-MySqlRows -PhysicalShard $PhysicalShard -Sql @"
SELECT column_name
FROM information_schema.columns
WHERE table_schema = $(ConvertTo-SqlLiteral $Schema)
  AND table_name = $(ConvertTo-SqlLiteral $Table)
ORDER BY ordinal_position;
"@)
}

function Get-PrimaryKeyColumns {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Schema,
        [Parameter(Mandatory)][string]$Table
    )

    return @(Invoke-MySqlRows -PhysicalShard $PhysicalShard -Sql @"
SELECT column_name
FROM information_schema.statistics
WHERE table_schema = $(ConvertTo-SqlLiteral $Schema)
  AND table_name = $(ConvertTo-SqlLiteral $Table)
  AND index_name = 'PRIMARY'
ORDER BY seq_in_index;
"@)
}

function Assert-SchemaStructure {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
        $sourceSchema = $Checkpoint.sourceSchemas[$sourceIndex]
        foreach ($table in @($allMigratedTables + @('distributed_id_worker_lease'))) {
            $sourceColumns = @(Get-TableColumns -PhysicalShard $sourceIndex `
                    -Schema $sourceSchema -Table $table)
            if ($sourceColumns.Count -eq 0) {
                throw "Missing source table $sourceSchema.$table."
            }
            foreach ($targetIndex in @($sourceIndex, ($sourceIndex + 2))) {
                $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
                $targetColumns = @(Get-TableColumns -PhysicalShard $sourceIndex `
                        -Schema $targetSchema -Table $table)
                if (($sourceColumns -join "`n") -ne ($targetColumns -join "`n")) {
                    throw "Column mismatch between $sourceSchema.$table and " +
                        "$targetSchema.$table."
                }
            }
        }
    }
}

function Get-MaxOwnedUserId {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$SourceIndex,
        [Parameter(Mandatory)][string]$SourceSchema
    )

    return [long](Get-Scalar -PhysicalShard $SourceIndex -Sql @"
SELECT COALESCE(MAX(user_id), 0)
FROM (
    SELECT user_id FROM $SourceSchema.cart_user_lock
    UNION ALL SELECT user_id FROM $SourceSchema.cart_item
    UNION ALL SELECT user_id FROM $SourceSchema.cart_merge_request
    UNION ALL SELECT user_id FROM $SourceSchema.trade_order
    UNION ALL SELECT user_id FROM $SourceSchema.after_sale_order
    UNION ALL SELECT user_id FROM $SourceSchema.flash_sale_order_request
    UNION ALL SELECT owner_user_id AS user_id
      FROM $SourceSchema.consumed_event
     WHERE owner_user_id IS NOT NULL
) owned_users;
"@)
}

function Get-NextBatchLastUserId {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$SourceIndex,
        [Parameter(Mandatory)][string]$SourceSchema,
        [Parameter(Mandatory)][long]$AfterUserId,
        [Parameter(Mandatory)][long]$HighWatermarkUserId
    )

    return [long](Get-Scalar -PhysicalShard $SourceIndex -Sql @"
SELECT COALESCE(MAX(selected_user.user_id), 0)
FROM (
    SELECT DISTINCT owned_user.user_id
    FROM (
        SELECT user_id FROM $SourceSchema.cart_user_lock
        UNION ALL SELECT user_id FROM $SourceSchema.cart_item
        UNION ALL SELECT user_id FROM $SourceSchema.cart_merge_request
        UNION ALL SELECT user_id FROM $SourceSchema.trade_order
        UNION ALL SELECT user_id FROM $SourceSchema.after_sale_order
        UNION ALL SELECT user_id FROM $SourceSchema.flash_sale_order_request
        UNION ALL SELECT owner_user_id AS user_id
          FROM $SourceSchema.consumed_event
         WHERE owner_user_id IS NOT NULL
    ) owned_user
    WHERE owned_user.user_id > $AfterUserId
      AND owned_user.user_id <= $HighWatermarkUserId
    ORDER BY owned_user.user_id
    LIMIT $BatchSize
) selected_user;
"@)
}

function Get-UpsertSql {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$SourceSchema,
        [Parameter(Mandatory)][string]$TargetSchema,
        [Parameter(Mandatory)][int]$TargetIndex,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][long]$AfterUserId,
        [Parameter(Mandatory)][long]$LastUserId
    )

    $columns = @(Get-TableColumns -PhysicalShard $PhysicalShard `
            -Schema $SourceSchema -Table $Table)
    $primaryKeys = @(Get-PrimaryKeyColumns -PhysicalShard $PhysicalShard `
            -Schema $SourceSchema -Table $Table)
    if ($columns.Count -eq 0 -or $primaryKeys.Count -eq 0) {
        throw "Cannot construct upsert for $SourceSchema.$Table."
    }
    $selection = Get-SourceSelection -Table $Table -SourceSchema $SourceSchema
    $quotedColumns = @($columns | ForEach-Object { "``$_``" })
    $selectedColumns = @($columns | ForEach-Object { "source_row.``$_``" })
    $updates = @($columns | Where-Object { $_ -notin $primaryKeys } |
        ForEach-Object { "``$_`` = VALUES(``$_``)" })
    if ($updates.Count -eq 0) {
        $updates = @("``$($primaryKeys[0])`` = VALUES(``$($primaryKeys[0])``)")
    }
    return @"
INSERT INTO $TargetSchema.$Table ($($quotedColumns -join ', '))
SELECT $($selectedColumns -join ', ')
FROM $($selection.From)
WHERE $($selection.Owner) > $AfterUserId
  AND $($selection.Owner) <= $LastUserId
  AND MOD($($selection.Owner), 4) = $TargetIndex
ON DUPLICATE KEY UPDATE $($updates -join ', ');
"@
}

function Invoke-UserBatch {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint,
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$SourceIndex,
        [Parameter(Mandatory)][long]$AfterUserId,
        [Parameter(Mandatory)][long]$LastUserId
    )

    $sourceSchema = $Checkpoint.sourceSchemas[$SourceIndex]
    $sql = [Text.StringBuilder]::new()
    [void]$sql.AppendLine('START TRANSACTION;')
    foreach ($targetIndex in @($SourceIndex, ($SourceIndex + 2))) {
        $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
        foreach ($table in $userTables) {
            [void]$sql.AppendLine((Get-UpsertSql -PhysicalShard $SourceIndex `
                        -SourceSchema $sourceSchema -TargetSchema $targetSchema `
                        -TargetIndex $targetIndex -Table $table `
                        -AfterUserId $AfterUserId -LastUserId $LastUserId))
        }
    }
    [void]$sql.AppendLine('COMMIT;')
    [void](Invoke-ContainerMySql -PhysicalShard $SourceIndex -Sql $sql.ToString())
}

function Sync-ControlData {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    $sourceSchema = $Checkpoint.sourceSchemas[0]
    $targetSchema = $Checkpoint.targetSchemas[0]
    $columns = @(Get-TableColumns -PhysicalShard 0 -Schema $sourceSchema `
            -Table 'consumer_failure')
    $primaryKeys = @(Get-PrimaryKeyColumns -PhysicalShard 0 -Schema $sourceSchema `
            -Table 'consumer_failure')
    $quotedColumns = @($columns | ForEach-Object { "``$_``" })
    $updates = @($columns | Where-Object { $_ -notin $primaryKeys } |
        ForEach-Object { "``$_`` = VALUES(``$_``)" })
    [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
INSERT INTO $targetSchema.consumer_failure ($($quotedColumns -join ', '))
SELECT $($quotedColumns -join ', ')
FROM $sourceSchema.consumer_failure
ON DUPLICATE KEY UPDATE $($updates -join ', ');
"@)
}

function Invoke-CopyPass {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint,
        [switch]$ResetCursor
    )

    if ($ResetCursor) {
        for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
            $Checkpoint.sources[$sourceIndex].lastUserId = 0
            $Checkpoint.sources[$sourceIndex].highWatermarkUserId =
                Get-MaxOwnedUserId -SourceIndex $sourceIndex `
                    -SourceSchema $Checkpoint.sourceSchemas[$sourceIndex]
        }
        $Checkpoint.status = 'CATCHING_UP'
        Save-Checkpoint -Checkpoint $Checkpoint
    }
    else {
        $Checkpoint.status = 'COPYING'
        Save-Checkpoint -Checkpoint $Checkpoint
    }

    $committedBatches = 0
    $madeProgress = $true
    while ($madeProgress) {
        $madeProgress = $false
        for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
            if ($MaxBatches -gt 0 -and $committedBatches -ge $MaxBatches) {
                return $committedBatches
            }
            $sourceState = $Checkpoint.sources[$sourceIndex]
            $nextLast = Get-NextBatchLastUserId -SourceIndex $sourceIndex `
                -SourceSchema $Checkpoint.sourceSchemas[$sourceIndex] `
                -AfterUserId ([long]$sourceState.lastUserId) `
                -HighWatermarkUserId ([long]$sourceState.highWatermarkUserId)
            if ($nextLast -le 0) {
                continue
            }
            $previousLastUserId = [long]$sourceState.lastUserId
            Invoke-UserBatch -Checkpoint $Checkpoint -SourceIndex $sourceIndex `
                -AfterUserId $previousLastUserId -LastUserId $nextLast
            $sourceState.lastUserId = $nextLast
            $sourceState.committedBatches = [int]$sourceState.committedBatches + 1
            $Checkpoint.totalCommittedBatches = [int]$Checkpoint.totalCommittedBatches + 1
            $Checkpoint.lastCommittedBatch = [ordered]@{
                sourceShard = $sourceIndex
                firstExclusiveUserId = $previousLastUserId
                lastInclusiveUserId = $nextLast
                committedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
            }
            Save-Checkpoint -Checkpoint $Checkpoint
            $committedBatches++
            $madeProgress = $true
            if ($FailAfterCommittedBatches -gt 0 -and
                    $committedBatches -ge $FailAfterCommittedBatches) {
                throw "Injected Trade resharding interruption after " +
                    "$committedBatches committed batch(es)."
            }
        }
    }
    Sync-ControlData -Checkpoint $Checkpoint
    $Checkpoint.status = if ($ResetCursor) { 'CAUGHT_UP' } else { 'COPIED' }
    Save-Checkpoint -Checkpoint $Checkpoint
    return $committedBatches
}

function Get-PrimaryKeyPredicate {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Schema,
        [Parameter(Mandatory)][string]$Table
    )

    $primaryKeys = @(Get-PrimaryKeyColumns -PhysicalShard $PhysicalShard `
            -Schema $Schema -Table $Table)
    if ($primaryKeys.Count -eq 0) {
        throw "No primary key found for $Schema.$Table."
    }
    return ($primaryKeys | ForEach-Object {
            "source_row.``$_`` = target_row.``$_``"
        }) -join ' AND '
}

function Remove-TargetOrphans {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    foreach ($targetIndex in 0..3) {
        $physicalShard = $targetIndex % 2
        $sourceSchema = $Checkpoint.sourceSchemas[$physicalShard]
        $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
        $sql = [Text.StringBuilder]::new()
        [void]$sql.AppendLine("USE $sourceSchema;")
        [void]$sql.AppendLine('START TRANSACTION;')
        foreach ($table in $rollbackTables | Where-Object { $_ -ne 'consumer_failure' }) {
            $selection = Get-SourceSelection -Table $table -SourceSchema $sourceSchema
            $predicate = Get-PrimaryKeyPredicate -PhysicalShard $physicalShard `
                -Schema $sourceSchema -Table $table
            [void]$sql.AppendLine(@"
DELETE target_row
FROM $targetSchema.$table target_row
WHERE NOT EXISTS (
    SELECT 1
    FROM $($selection.From)
    WHERE $predicate
      AND MOD($($selection.Owner), 4) = $targetIndex
);
"@)
        }
        [void]$sql.AppendLine('COMMIT;')
        [void](Invoke-ContainerMySql -PhysicalShard $physicalShard -Sql $sql.ToString())
    }
    $sourceControl = $Checkpoint.sourceSchemas[0]
    $targetControl = $Checkpoint.targetSchemas[0]
    [void](Invoke-ContainerMySql -PhysicalShard 0 -Sql @"
USE $sourceControl;
DELETE target_row
FROM $targetControl.consumer_failure target_row
WHERE NOT EXISTS (
    SELECT 1
    FROM $sourceControl.consumer_failure source_row
    WHERE source_row.message_id = target_row.message_id
      AND source_row.consumer_group = target_row.consumer_group
);
"@)
}

function Get-TableFingerprint {
    param(
        [Parameter(Mandatory)][ValidateSet(0, 1)][int]$PhysicalShard,
        [Parameter(Mandatory)][string]$Schema,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$FromSql
    )

    $columns = @(Get-TableColumns -PhysicalShard $PhysicalShard `
            -Schema $Schema -Table $Table)
    $encodedColumns = @($columns | ForEach-Object {
            "COALESCE(HEX(CAST(fingerprint_row.``$_`` AS BINARY)), 'NULL')"
        })
    $rowExpression = "SHA2(CONCAT_WS('#', $($encodedColumns -join ', ')), 256)"
    $rows = @(Invoke-MySqlRows -PhysicalShard $PhysicalShard -Sql @"
SELECT COUNT(*),
       COALESCE(HEX(BIT_XOR(CAST(CONV(SUBSTRING(row_digest, 1, 16), 16, 10)
           AS UNSIGNED))), '0'),
       COALESCE(HEX(BIT_XOR(CAST(CONV(SUBSTRING(row_digest, 17, 16), 16, 10)
           AS UNSIGNED))), '0'),
       COALESCE(HEX(BIT_XOR(CAST(CONV(SUBSTRING(row_digest, 33, 16), 16, 10)
           AS UNSIGNED))), '0'),
       COALESCE(HEX(BIT_XOR(CAST(CONV(SUBSTRING(row_digest, 49, 16), 16, 10)
           AS UNSIGNED))), '0')
FROM (
    SELECT $rowExpression AS row_digest
    FROM $FromSql
) fingerprint_rows;
"@)
    $fields = $rows[0].Split("`t")
    return [ordered]@{
        count = [long]$fields[0]
        xor0 = $fields[1]
        xor1 = $fields[2]
        xor2 = $fields[3]
        xor3 = $fields[4]
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Value)

    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
}

function Verify-Target {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    if (-not $Checkpoint.finalWriteFence) {
        throw 'Verification requires CatchUp -FinalWriteFence.'
    }
    $comparisons = [Collections.Generic.List[object]]::new()
    foreach ($targetIndex in 0..3) {
        $physicalShard = $targetIndex % 2
        $sourceSchema = $Checkpoint.sourceSchemas[$physicalShard]
        $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
        foreach ($table in $userTables) {
            $selection = Get-SourceSelection -Table $table -SourceSchema $sourceSchema
            $sourceFrom = @"
$($selection.From)
WHERE MOD($($selection.Owner), 4) = $targetIndex
"@
            $targetFrom = "$targetSchema.$table fingerprint_row"
            $sourceFingerprint = Get-TableFingerprint `
                -PhysicalShard $physicalShard -Schema $sourceSchema -Table $table `
                -FromSql ($sourceFrom -replace '\bsource_row\b', 'fingerprint_row')
            $targetFingerprint = Get-TableFingerprint `
                -PhysicalShard $physicalShard -Schema $targetSchema -Table $table `
                -FromSql $targetFrom
            $matches = ($sourceFingerprint | ConvertTo-Json -Compress) -eq
                ($targetFingerprint | ConvertTo-Json -Compress)
            $comparisons.Add([ordered]@{
                    targetShard = $targetIndex
                    table = $table
                    source = $sourceFingerprint
                    target = $targetFingerprint
                    matches = $matches
                })
        }
    }
    $sourceControlSchema = $Checkpoint.sourceSchemas[0]
    $targetControlSchema = $Checkpoint.targetSchemas[0]
    $sourceControl = Get-TableFingerprint -PhysicalShard 0 `
        -Schema $sourceControlSchema -Table 'consumer_failure' `
        -FromSql "$sourceControlSchema.consumer_failure fingerprint_row"
    $targetControl = Get-TableFingerprint -PhysicalShard 0 `
        -Schema $targetControlSchema -Table 'consumer_failure' `
        -FromSql "$targetControlSchema.consumer_failure fingerprint_row"
    $comparisons.Add([ordered]@{
            targetShard = 0
            table = 'consumer_failure'
            source = $sourceControl
            target = $targetControl
            matches = (($sourceControl | ConvertTo-Json -Compress) -eq
                ($targetControl | ConvertTo-Json -Compress))
        })

    $otherControlCounts = for ($targetIndex = 1; $targetIndex -lt 4; $targetIndex++) {
        $count = [long](Get-Scalar -PhysicalShard ($targetIndex % 2) -Sql (
                "SELECT COUNT(*) FROM $($Checkpoint.targetSchemas[$targetIndex])." +
                'consumer_failure;'))
        [ordered]@{ targetShard = $targetIndex; count = $count }
    }
    $leaseCounts = for ($targetIndex = 0; $targetIndex -lt 4; $targetIndex++) {
        $count = [long](Get-Scalar -PhysicalShard ($targetIndex % 2) -Sql (
                "SELECT COUNT(*) FROM $($Checkpoint.targetSchemas[$targetIndex])." +
                'distributed_id_worker_lease;'))
        [ordered]@{ targetShard = $targetIndex; count = $count }
    }
    $verified = @($comparisons | Where-Object { -not $_.matches }).Count -eq 0 -and
        @($otherControlCounts | Where-Object { $_.count -ne 0 }).Count -eq 0 -and
        @($leaseCounts | Where-Object { $_.count -ne 0 }).Count -eq 0
    $canonical = @($comparisons | ForEach-Object {
            "$($_.targetShard)|$($_.table)|$($_.source.count)|" +
            "$($_.source.xor0)|$($_.source.xor1)|$($_.source.xor2)|" +
            "$($_.source.xor3)"
        }) -join "`n"
    $result = [ordered]@{
        verified = $verified
        digest = Get-Sha256 -Value $canonical
        tables = @($comparisons)
        nonPrimaryConsumerFailureCounts = @($otherControlCounts)
        targetLeaseCounts = @($leaseCounts)
        verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    }
    $Checkpoint.verification = $result
    $Checkpoint.status = if ($verified) { 'VERIFIED' } else { 'VERIFY_FAILED' }
    Save-Checkpoint -Checkpoint $Checkpoint
    if (-not $verified) {
        throw "Trade resharding verification failed: " +
            ($result | ConvertTo-Json -Depth 12 -Compress)
    }
    return $result
}

function Assert-SourceEligibility {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
        $sourceSchema = $Checkpoint.sourceSchemas[$sourceIndex]
        $nullConsumed = [long](Get-Scalar -PhysicalShard $sourceIndex -Sql (
                "SELECT COUNT(*) FROM $sourceSchema.consumed_event " +
                'WHERE owner_user_id IS NULL;'))
        if ($nullConsumed -ne 0) {
            throw "Source shard $sourceIndex contains $nullConsumed consumed_event " +
                'row(s) without owner_user_id; resharding cannot guess ownership.'
        }
        foreach ($table in $userTables) {
            $selection = Get-SourceSelection -Table $table -SourceSchema $sourceSchema
            $unresolved = [long](Get-Scalar -PhysicalShard $sourceIndex -Sql @"
SELECT COUNT(*)
FROM $($selection.From)
WHERE $($selection.Owner) IS NULL
   OR MOD($($selection.Owner), 2) <> $sourceIndex;
"@)
            if ($unresolved -ne 0) {
                throw "Source shard $sourceIndex table $table contains $unresolved " +
                    'unresolved or incorrectly routed row(s).'
            }
        }
    }
    $secondaryFailures = [long](Get-Scalar -PhysicalShard 1 -Sql (
            "SELECT COUNT(*) FROM $($Checkpoint.sourceSchemas[1]).consumer_failure;"))
    if ($secondaryFailures -ne 0) {
        throw 'consumer_failure control data must exist only on source ds_0.'
    }
}

function Assert-TargetsEmpty {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    foreach ($targetIndex in 0..3) {
        $physicalShard = $targetIndex % 2
        $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
        foreach ($table in @($allMigratedTables + @('distributed_id_worker_lease'))) {
            $count = [long](Get-Scalar -PhysicalShard $physicalShard `
                -Sql "SELECT COUNT(*) FROM $targetSchema.$table;")
            if ($count -ne 0) {
                throw "Target shard $targetIndex table $table is not empty."
            }
        }
    }
}

function Initialize-Job {
    if (Test-Path -LiteralPath $CheckpointPath -PathType Leaf) {
        throw "Checkpoint already exists: $CheckpointPath"
    }
    $checkpoint = [ordered]@{
        version = 1
        jobId = $JobId
        status = 'INITIALIZING'
        sourceSchemas = @($SourceShard0Schema, $SourceShard1Schema)
        targetSchemas = @(
            $TargetShard0Schema,
            $TargetShard1Schema,
            $TargetShard2Schema,
            $TargetShard3Schema
        )
        batchSize = $BatchSize
        totalCommittedBatches = 0
        lastCommittedBatch = $null
        finalWriteFence = $false
        verification = $null
        sources = @()
        createdAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        updatedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    }
    Assert-SchemaStructure -Checkpoint $checkpoint
    Assert-SourceEligibility -Checkpoint $checkpoint
    Assert-TargetsEmpty -Checkpoint $checkpoint
    for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
        $checkpoint.sources += [ordered]@{
            sourceShard = $sourceIndex
            highWatermarkUserId = Get-MaxOwnedUserId -SourceIndex $sourceIndex `
                -SourceSchema $checkpoint.sourceSchemas[$sourceIndex]
            lastUserId = 0
            committedBatches = 0
        }
    }
    $checkpoint.status = 'INITIALIZED'
    Save-Checkpoint -Checkpoint $checkpoint
    return $checkpoint
}

function Promote-Job {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    if ($Checkpoint.status -ne 'VERIFIED' -or
            -not $Checkpoint.verification.verified) {
        throw 'Only a verified resharding job can be promoted.'
    }
    function Get-ActiveSourceLeaseCount {
        $activeLeases = 0
        for ($sourceIndex = 0; $sourceIndex -lt 2; $sourceIndex++) {
            $sourceSchema = $Checkpoint.sourceSchemas[$sourceIndex]
            $activeLeases += [long](Get-Scalar -PhysicalShard $sourceIndex -Sql @"
SELECT COUNT(*)
FROM $sourceSchema.distributed_id_worker_lease
WHERE lease_until > NOW(3);
"@)
        }
        return $activeLeases
    }

    $activeLeases = Get-ActiveSourceLeaseCount
    if ($activeLeases -ne 0) {
        throw "Source Trade still has $activeLeases active distributed ID worker lease(s). " +
            'Stop source writers before promotion.'
    }
    [void](Verify-Target -Checkpoint $Checkpoint)
    $activeLeases = Get-ActiveSourceLeaseCount
    if ($activeLeases -ne 0) {
        throw "Source Trade acquired $activeLeases distributed ID worker lease(s) " +
            'during final verification; promotion was aborted.'
    }
    $Checkpoint.status = 'PROMOTED'
    $Checkpoint.promotedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    Save-Checkpoint -Checkpoint $Checkpoint
    return $Checkpoint
}

function Rollback-Job {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Checkpoint)

    if (-not $ConfirmNoTargetWrites) {
        throw 'Rollback requires -ConfirmNoTargetWrites because target-only writes ' +
            'cannot be reconstructed on the source shards.'
    }
    foreach ($targetIndex in 0..3) {
        $physicalShard = $targetIndex % 2
        $targetSchema = $Checkpoint.targetSchemas[$targetIndex]
        $sql = [Text.StringBuilder]::new()
        [void]$sql.AppendLine('SET FOREIGN_KEY_CHECKS = 0;')
        foreach ($table in $rollbackTables) {
            [void]$sql.AppendLine("DELETE FROM $targetSchema.$table;")
        }
        [void]$sql.AppendLine('DELETE FROM ' +
            "$targetSchema.distributed_id_worker_lease;")
        [void]$sql.AppendLine('SET FOREIGN_KEY_CHECKS = 1;')
        [void](Invoke-ContainerMySql -PhysicalShard $physicalShard -Sql $sql.ToString())
    }
    $Checkpoint.status = 'ROLLED_BACK'
    $Checkpoint.rolledBackAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    Save-Checkpoint -Checkpoint $Checkpoint
    return $Checkpoint
}

Import-DotEnv -Path $EnvironmentFile

if (-not $SourceShard0Schema) {
    $SourceShard0Schema = $env:TRADE_SHARD_0_DB_NAME
}
if (-not $SourceShard1Schema) {
    $SourceShard1Schema = $env:TRADE_SHARD_1_DB_NAME
}
if (-not $TargetShard0Schema) {
    $TargetShard0Schema = if ($env:TRADE_RESHARD_0_DB_NAME) {
        $env:TRADE_RESHARD_0_DB_NAME
    } else { 'ecom_trade_reshard_0' }
}
if (-not $TargetShard1Schema) {
    $TargetShard1Schema = if ($env:TRADE_RESHARD_1_DB_NAME) {
        $env:TRADE_RESHARD_1_DB_NAME
    } else { 'ecom_trade_reshard_1' }
}
if (-not $TargetShard2Schema) {
    $TargetShard2Schema = if ($env:TRADE_RESHARD_2_DB_NAME) {
        $env:TRADE_RESHARD_2_DB_NAME
    } else { 'ecom_trade_reshard_2' }
}
if (-not $TargetShard3Schema) {
    $TargetShard3Schema = if ($env:TRADE_RESHARD_3_DB_NAME) {
        $env:TRADE_RESHARD_3_DB_NAME
    } else { 'ecom_trade_reshard_3' }
}

foreach ($entry in ([ordered]@{
        SourceShard0Schema = $SourceShard0Schema
        SourceShard1Schema = $SourceShard1Schema
        TargetShard0Schema = $TargetShard0Schema
        TargetShard1Schema = $TargetShard1Schema
        TargetShard2Schema = $TargetShard2Schema
        TargetShard3Schema = $TargetShard3Schema
    }).GetEnumerator()) {
    if (-not $entry.Value) {
        throw "Missing schema parameter: $($entry.Key)"
    }
    Assert-Identifier -Value $entry.Value -Name $entry.Key
}
if ($SourceShard0Schema -in @(
        $TargetShard0Schema, $TargetShard1Schema,
        $TargetShard2Schema, $TargetShard3Schema) -or
        $SourceShard1Schema -in @(
            $TargetShard0Schema, $TargetShard1Schema,
            $TargetShard2Schema, $TargetShard3Schema)) {
    throw 'Source and target Trade schemas must be distinct.'
}

$result = switch ($Action) {
    'Initialize' {
        Initialize-Job
    }
    'Copy' {
        $checkpoint = Load-Checkpoint
        [void](Invoke-CopyPass -Checkpoint $checkpoint)
        $checkpoint
    }
    'CatchUp' {
        $checkpoint = Load-Checkpoint
        [void](Invoke-CopyPass -Checkpoint $checkpoint -ResetCursor)
        if ($FinalWriteFence) {
            Remove-TargetOrphans -Checkpoint $checkpoint
            Sync-ControlData -Checkpoint $checkpoint
            $checkpoint.finalWriteFence = $true
            $checkpoint.finalWriteFenceAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
            $checkpoint.status = 'FENCED'
            Save-Checkpoint -Checkpoint $checkpoint
        }
        $checkpoint
    }
    'Verify' {
        $checkpoint = Load-Checkpoint
        Verify-Target -Checkpoint $checkpoint
    }
    'Promote' {
        $checkpoint = Load-Checkpoint
        Promote-Job -Checkpoint $checkpoint
    }
    'Rollback' {
        $checkpoint = Load-Checkpoint
        Rollback-Job -Checkpoint $checkpoint
    }
    'Status' {
        Load-Checkpoint
    }
}

if ($OutputPath) {
    $outputDirectory = Split-Path -Parent $OutputPath
    if ($outputDirectory -and
            -not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    $result | ConvertTo-Json -Depth 20 |
        Set-Content -LiteralPath $OutputPath -Encoding utf8
}
$result
