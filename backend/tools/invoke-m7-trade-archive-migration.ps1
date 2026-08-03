#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Initialize', 'Migrate', 'Verify', 'Promote', 'Rollback', 'Status')]
    [string]$Action,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$')]
    [string]$JobId,
    [ValidatePattern('^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$')]
    [string]$CutoffAt = '2026-06-01 00:00:00.000',
    [ValidateRange(1, 10000)]
    [int]$BatchSize = 500,
    [ValidateRange(0, 1000000)]
    [int]$MaxBatches = 0,
    [ValidateRange(0, 1000000)]
    [int]$FailAfterCommittedBatches = 0,
    [switch]$RefreshWatermark,
    [string]$SourceShard0Schema,
    [string]$SourceShard1Schema,
    [string]$ArchiveShard0Schema,
    [string]$ArchiveShard1Schema,
    [string]$EnvironmentFile,
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

$archiveTables = @(
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
    'outbox_event'
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
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Sql,
        [switch]$AllowFailure
    )

    $arguments = @(
        'exec', '-i',
        '-e', "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD",
        $Container,
        'mysql',
        '--user=root',
        '--default-character-set=utf8mb4',
        '--batch',
        '--skip-column-names'
    )
    $output = @($Sql | & docker @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "MySQL command failed in $Container`: $($output -join [Environment]::NewLine)"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = $output
    }
}

function Invoke-MySqlRows {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$Sql
    )

    return @((Invoke-ContainerMySql -Container $Shard.Container -Sql $Sql).Lines) |
        ForEach-Object { $_.ToString() } |
        Where-Object { $_.Length -gt 0 }
}

function Get-MySqlScalar {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$Sql
    )

    $rows = @(Invoke-MySqlRows -Shard $Shard -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw "Expected one scalar row from shard $($Shard.Index), received $($rows.Count)."
    }
    return $rows[0]
}

function Assert-ContainerReady {
    param([Parameter(Mandatory)][string]$Container)

    $status = docker inspect --format '{{.State.Status}}' $Container 2>$null
    if ($LASTEXITCODE -ne 0 -or $status -ne 'running') {
        throw "Required MySQL container is not running: $Container"
    }
    $ping = docker exec -e "MYSQL_PWD=$env:MYSQL_ROOT_PASSWORD" $Container `
        mysqladmin --user=root ping --silent 2>$null
    if ($LASTEXITCODE -ne 0 -or ($ping -join '') -notmatch 'alive') {
        throw "Required MySQL container is not ready: $Container"
    }
}

function Get-EligibilitySql {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$OrderAlias,
        [Parameter(Mandatory)][string]$CutoffLiteral
    )

    $source = $Shard.SourceSchema
    return @"
$OrderAlias.status IN ('COMPLETED', 'CANCELED', 'CLOSED')
AND $OrderAlias.updated_at < $CutoffLiteral
AND NOT EXISTS (
    SELECT 1
    FROM $source.after_sale_order eligible_after_sale
    WHERE eligible_after_sale.order_id = $OrderAlias.id
      AND eligible_after_sale.status NOT IN ('COMPLETED', 'REJECTED', 'CANCELED')
)
AND NOT EXISTS (
    SELECT 1
    FROM $source.outbox_event eligible_outbox
    WHERE eligible_outbox.status <> 'PUBLISHED'
      AND (
          (eligible_outbox.aggregate_type = 'TradeOrder'
           AND eligible_outbox.aggregate_id = $OrderAlias.order_no)
          OR
          (eligible_outbox.aggregate_type = 'AfterSaleOrder'
           AND EXISTS (
               SELECT 1
               FROM $source.after_sale_order eligible_outbox_after_sale
               WHERE eligible_outbox_after_sale.order_id = $OrderAlias.id
                 AND eligible_outbox_after_sale.after_sale_no =
                     eligible_outbox.aggregate_id
           ))
      )
)
AND NOT EXISTS (
    SELECT 1
    FROM $source.reconciliation_record eligible_reconciliation
    WHERE eligible_reconciliation.status = 'OPEN'
      AND (
          eligible_reconciliation.reference_no = $OrderAlias.order_no
          OR EXISTS (
              SELECT 1
              FROM $source.after_sale_order eligible_reconciliation_after_sale
              WHERE eligible_reconciliation_after_sale.order_id = $OrderAlias.id
                AND eligible_reconciliation_after_sale.after_sale_no =
                    eligible_reconciliation.reference_no
          )
      )
)
"@
}

function Assert-SourceSchema {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    foreach ($table in @($archiveTables + @('reconciliation_record'))) {
        $exists = Get-MySqlScalar -Shard $Shard -Sql @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = $(ConvertTo-SqlLiteral $Shard.SourceSchema)
  AND table_name = $(ConvertTo-SqlLiteral $table);
"@
        if ([int]$exists -ne 1) {
            throw "Source shard $($Shard.Index) is missing $($Shard.SourceSchema).$table."
        }
    }
}

function Initialize-ArchiveShard {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    Assert-SourceSchema -Shard $Shard
    $source = $Shard.SourceSchema
    $archive = $Shard.ArchiveSchema
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    $cutoffLiteral = ConvertTo-SqlLiteral $CutoffAt

    $ddl = [Text.StringBuilder]::new()
    [void]$ddl.AppendLine(
        "CREATE DATABASE IF NOT EXISTS $archive CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    foreach ($table in $archiveTables) {
        [void]$ddl.AppendLine(
            "CREATE TABLE IF NOT EXISTS $archive.$table LIKE $source.$table;")
    }
    [void]$ddl.AppendLine(@"
CREATE TABLE IF NOT EXISTS $archive.trade_archive_job (
    job_id VARCHAR(64) NOT NULL,
    source_shard TINYINT NOT NULL,
    cutoff_at TIMESTAMP(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    last_order_id BIGINT NOT NULL DEFAULT 0,
    high_watermark_id BIGINT NOT NULL DEFAULT 0,
    eligible_order_count BIGINT NOT NULL DEFAULT 0,
    migrated_order_count BIGINT NOT NULL DEFAULT 0,
    source_digest CHAR(64) NULL,
    target_digest CHAR(64) NULL,
    failure_message VARCHAR(1000) NULL,
    verified_at TIMESTAMP(3) NULL,
    promoted_at TIMESTAMP(3) NULL,
    rolled_back_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (job_id),
    CONSTRAINT ck_trade_archive_job_status CHECK (
        status IN (
            'INITIALIZED', 'COPYING', 'COPIED', 'VERIFY_FAILED',
            'VERIFIED', 'PROMOTED', 'ROLLED_BACK'
        )
    )
);

CREATE TABLE IF NOT EXISTS $archive.trade_archive_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id VARCHAR(64) NOT NULL,
    batch_sequence INT NOT NULL,
    first_order_id BIGINT NOT NULL,
    last_order_id BIGINT NOT NULL,
    order_count INT NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    committed_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trade_archive_batch_sequence UNIQUE (job_id, batch_sequence),
    CONSTRAINT ck_trade_archive_batch_count CHECK (order_count > 0)
);

CREATE TABLE IF NOT EXISTS $archive.trade_archive_order_manifest (
    order_id BIGINT NOT NULL,
    job_id VARCHAR(64) NOT NULL,
    source_user_id BIGINT NOT NULL,
    source_updated_at TIMESTAMP(3) NOT NULL,
    archived_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (order_id),
    CONSTRAINT uk_trade_archive_manifest_job_order UNIQUE (job_id, order_id)
);

CREATE TABLE IF NOT EXISTS $archive.trade_archive_read_cutover (
    cutover_key VARCHAR(32) NOT NULL,
    job_id VARCHAR(64) NOT NULL,
    source_shard TINYINT NOT NULL,
    source_digest CHAR(64) NOT NULL,
    target_digest CHAR(64) NOT NULL,
    activated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (cutover_key)
);
"@)
    [void](Invoke-ContainerMySql -Container $Shard.Container -Sql $ddl.ToString())

    $existingStatus = @(Invoke-MySqlRows -Shard $Shard -Sql @"
SELECT status
FROM $archive.trade_archive_job
WHERE job_id = $jobLiteral;
"@)
    if ($existingStatus.Count -gt 0 -and $existingStatus[0] -ne 'ROLLED_BACK') {
        throw "Archive job $JobId already exists on shard $($Shard.Index) with status " +
            "$($existingStatus[0])."
    }

    $eligibility = Get-EligibilitySql -Shard $Shard -OrderAlias 'source_order' `
        -CutoffLiteral $cutoffLiteral
    $candidate = @(Invoke-MySqlRows -Shard $Shard -Sql @"
SELECT COUNT(*), COALESCE(MAX(source_order.id), 0)
FROM $source.trade_order source_order
WHERE $eligibility;
"@)
    $candidateFields = $candidate[0].Split("`t")
    if ($candidateFields.Count -ne 2) {
        throw "Unable to read archive candidate watermark for shard $($Shard.Index)."
    }

    [void](Invoke-ContainerMySql -Container $Shard.Container -Sql @"
INSERT INTO $archive.trade_archive_job (
    job_id, source_shard, cutoff_at, status, last_order_id,
    high_watermark_id, eligible_order_count, migrated_order_count,
    created_at, updated_at
)
VALUES (
    $jobLiteral, $($Shard.Index), $cutoffLiteral, 'INITIALIZED', 0,
    $($candidateFields[1]), $($candidateFields[0]), 0, NOW(3), NOW(3)
)
ON DUPLICATE KEY UPDATE
    source_shard = VALUES(source_shard),
    cutoff_at = VALUES(cutoff_at),
    status = 'INITIALIZED',
    last_order_id = 0,
    high_watermark_id = VALUES(high_watermark_id),
    eligible_order_count = VALUES(eligible_order_count),
    migrated_order_count = 0,
    source_digest = NULL,
    target_digest = NULL,
    failure_message = NULL,
    verified_at = NULL,
    promoted_at = NULL,
    rolled_back_at = NULL,
    updated_at = NOW(3);
"@)
}

function Get-Job {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $jobLiteral = ConvertTo-SqlLiteral $JobId
    $rows = @(Invoke-MySqlRows -Shard $Shard -Sql @"
SELECT status, DATE_FORMAT(cutoff_at, '%Y-%m-%d %H:%i:%s.%f'),
       last_order_id, high_watermark_id, eligible_order_count,
       migrated_order_count, COALESCE(source_digest, ''),
       COALESCE(target_digest, '')
FROM $($Shard.ArchiveSchema).trade_archive_job
WHERE job_id = $jobLiteral;
"@)
    if ($rows.Count -ne 1) {
        throw "Archive job $JobId does not exist on shard $($Shard.Index)."
    }
    $fields = $rows[0].Split("`t")
    return [pscustomobject]@{
        Status = $fields[0]
        CutoffAt = $fields[1].Substring(0, 23)
        LastOrderId = [long]$fields[2]
        HighWatermarkId = [long]$fields[3]
        EligibleOrderCount = [long]$fields[4]
        MigratedOrderCount = [long]$fields[5]
        SourceDigest = $fields[6]
        TargetDigest = $fields[7]
    }
}

function Refresh-JobWatermark {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $job = Get-Job -Shard $Shard
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    $cutoffLiteral = ConvertTo-SqlLiteral $job.CutoffAt
    $eligibility = Get-EligibilitySql -Shard $Shard -OrderAlias 'source_order' `
        -CutoffLiteral $cutoffLiteral
    [void](Invoke-ContainerMySql -Container $Shard.Container -Sql @"
UPDATE $($Shard.ArchiveSchema).trade_archive_job archive_job
SET high_watermark_id = (
        SELECT COALESCE(MAX(source_order.id), 0)
        FROM $($Shard.SourceSchema).trade_order source_order
        WHERE $eligibility
    ),
    eligible_order_count = (
        SELECT COUNT(*)
        FROM $($Shard.SourceSchema).trade_order source_order
        WHERE $eligibility
    ),
    updated_at = NOW(3)
WHERE archive_job.job_id = $jobLiteral;
"@)
}

function Invoke-ArchiveBatch {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $job = Get-Job -Shard $Shard
    if ($job.Status -notin @('INITIALIZED', 'COPYING', 'COPIED', 'VERIFY_FAILED')) {
        throw "Archive job $JobId on shard $($Shard.Index) cannot copy from status " +
            "$($job.Status)."
    }
    $source = $Shard.SourceSchema
    $archive = $Shard.ArchiveSchema
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    $cutoffLiteral = ConvertTo-SqlLiteral $job.CutoffAt
    $eligibility = Get-EligibilitySql -Shard $Shard -OrderAlias 'source_order' `
        -CutoffLiteral $cutoffLiteral

    $result = @(Invoke-MySqlRows -Shard $Shard -Sql @"
USE $source;
START TRANSACTION;

UPDATE $archive.trade_archive_job
SET status = 'COPYING', failure_message = NULL, updated_at = NOW(3)
WHERE job_id = $jobLiteral;

CREATE TEMPORARY TABLE tmp_trade_archive_order_id (
    order_id BIGINT NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;

INSERT INTO tmp_trade_archive_order_id (order_id)
SELECT source_order.id
FROM $source.trade_order source_order
WHERE source_order.id > $($job.LastOrderId)
  AND source_order.id <= $($job.HighWatermarkId)
  AND $eligibility
ORDER BY source_order.id
LIMIT $BatchSize;

SET @archive_batch_count = ROW_COUNT();
SET @archive_first_order_id = (
    SELECT COALESCE(MIN(order_id), 0) FROM tmp_trade_archive_order_id
);
SET @archive_last_order_id = (
    SELECT COALESCE(MAX(order_id), 0) FROM tmp_trade_archive_order_id
);
SET @archive_batch_sequence = (
    SELECT COALESCE(MAX(batch_sequence), 0) + 1
    FROM $archive.trade_archive_batch
    WHERE job_id = $jobLiteral
);

INSERT IGNORE INTO $archive.trade_order
SELECT source_row.*
FROM $source.trade_order source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.id;

INSERT IGNORE INTO $archive.order_item
SELECT source_row.*
FROM $source.order_item source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.order_status_history
SELECT source_row.*
FROM $source.order_status_history source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.order_address_snapshot
SELECT source_row.*
FROM $source.order_address_snapshot source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.order_benefit_selection
SELECT source_row.*
FROM $source.order_benefit_selection source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.order_price_snapshot
SELECT source_row.*
FROM $source.order_price_snapshot source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.order_discount_allocation
SELECT source_row.*
FROM $source.order_discount_allocation source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.after_sale_order
SELECT source_row.*
FROM $source.after_sale_order source_row
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_row.order_id;

INSERT IGNORE INTO $archive.after_sale_item
SELECT source_row.*
FROM $source.after_sale_item source_row
JOIN $source.after_sale_order source_after_sale
  ON source_after_sale.id = source_row.after_sale_id
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_after_sale.order_id;

INSERT IGNORE INTO $archive.after_sale_history
SELECT source_row.*
FROM $source.after_sale_history source_row
JOIN $source.after_sale_order source_after_sale
  ON source_after_sale.id = source_row.after_sale_id
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_after_sale.order_id;

INSERT IGNORE INTO $archive.outbox_event
SELECT source_outbox.*
FROM $source.outbox_event source_outbox
WHERE EXISTS (
    SELECT 1
    FROM tmp_trade_archive_order_id selected_order
    JOIN $source.trade_order selected_trade_order
      ON selected_trade_order.id = selected_order.order_id
    LEFT JOIN $source.after_sale_order selected_after_sale
      ON selected_after_sale.order_id = selected_order.order_id
    WHERE (
        source_outbox.aggregate_type = 'TradeOrder'
        AND source_outbox.aggregate_id = selected_trade_order.order_no
    ) OR (
        source_outbox.aggregate_type = 'AfterSaleOrder'
        AND source_outbox.aggregate_id = selected_after_sale.after_sale_no
    )
);

INSERT IGNORE INTO $archive.trade_archive_order_manifest (
    order_id, job_id, source_user_id, source_updated_at, archived_at
)
SELECT source_order.id, $jobLiteral, source_order.user_id,
       source_order.updated_at, NOW(3)
FROM $source.trade_order source_order
JOIN tmp_trade_archive_order_id selected_order
  ON selected_order.order_id = source_order.id;

INSERT INTO $archive.trade_archive_batch (
    job_id, batch_sequence, first_order_id, last_order_id,
    order_count, started_at, committed_at
)
SELECT $jobLiteral, @archive_batch_sequence, @archive_first_order_id,
       @archive_last_order_id, @archive_batch_count, NOW(3), NOW(3)
WHERE @archive_batch_count > 0;

UPDATE $archive.trade_archive_job
SET last_order_id = CASE
        WHEN @archive_batch_count > 0 THEN @archive_last_order_id
        ELSE last_order_id
    END,
    migrated_order_count = (
        SELECT COUNT(*)
        FROM $archive.trade_archive_order_manifest
        WHERE job_id = $jobLiteral
    ),
    updated_at = NOW(3)
WHERE job_id = $jobLiteral;

SELECT @archive_batch_count, @archive_first_order_id, @archive_last_order_id;
COMMIT;
"@)
    if ($result.Count -ne 1) {
        throw "Archive batch on shard $($Shard.Index) returned an unexpected result."
    }
    $fields = $result[0].Split("`t")
    return [pscustomobject]@{
        Count = [int]$fields[0]
        FirstOrderId = [long]$fields[1]
        LastOrderId = [long]$fields[2]
    }
}

function Complete-CopyIfCaughtUp {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $job = Get-Job -Shard $Shard
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    if ($job.MigratedOrderCount -eq $job.EligibleOrderCount) {
        [void](Invoke-ContainerMySql -Container $Shard.Container -Sql @"
UPDATE $($Shard.ArchiveSchema).trade_archive_job
SET status = 'COPIED', updated_at = NOW(3)
WHERE job_id = $jobLiteral;
"@)
        return $true
    }
    return $false
}

function Invoke-Migration {
    param([Parameter(Mandatory)][pscustomobject[]]$Shards)

    if ($RefreshWatermark) {
        foreach ($shard in $Shards) {
            Refresh-JobWatermark -Shard $shard
        }
    }

    $committedBatches = 0
    $madeProgress = $true
    while ($madeProgress) {
        $madeProgress = $false
        foreach ($shard in $Shards) {
            if ($MaxBatches -gt 0 -and $committedBatches -ge $MaxBatches) {
                return $committedBatches
            }
            $batch = Invoke-ArchiveBatch -Shard $shard
            if ($batch.Count -gt 0) {
                $madeProgress = $true
                $committedBatches++
                if ($FailAfterCommittedBatches -gt 0 -and
                        $committedBatches -ge $FailAfterCommittedBatches) {
                    throw "Injected archive migration interruption after " +
                        "$committedBatches committed batch(es)."
                }
            }
        }
    }
    foreach ($shard in $Shards) {
        if (-not (Complete-CopyIfCaughtUp -Shard $shard)) {
            $job = Get-Job -Shard $shard
            throw "Archive copy stopped before shard $($shard.Index) caught up: " +
                "$($job.MigratedOrderCount)/$($job.EligibleOrderCount)."
        }
    }
    return $committedBatches
}

function Get-TableColumns {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$Table
    )

    return @(Invoke-MySqlRows -Shard $Shard -Sql @"
SELECT column_name
FROM information_schema.columns
WHERE table_schema = $(ConvertTo-SqlLiteral $Shard.SourceSchema)
  AND table_name = $(ConvertTo-SqlLiteral $Table)
ORDER BY ordinal_position;
"@)
}

function Get-TableFromSql {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$DataSchema
    )

    $archive = $Shard.ArchiveSchema
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    switch ($Table) {
        'trade_order' {
            "$DataSchema.trade_order fingerprint_row " +
            "JOIN $archive.trade_archive_order_manifest archive_manifest " +
            "ON archive_manifest.order_id = fingerprint_row.id " +
            "AND archive_manifest.job_id = $jobLiteral"
        }
        { $_ -in @(
                'order_item', 'order_status_history', 'order_address_snapshot',
                'order_benefit_selection', 'order_price_snapshot',
                'order_discount_allocation', 'after_sale_order') } {
            "$DataSchema.$Table fingerprint_row " +
            "JOIN $archive.trade_archive_order_manifest archive_manifest " +
            "ON archive_manifest.order_id = fingerprint_row.order_id " +
            "AND archive_manifest.job_id = $jobLiteral"
        }
        { $_ -in @('after_sale_item', 'after_sale_history') } {
            "$DataSchema.$Table fingerprint_row " +
            "JOIN $DataSchema.after_sale_order fingerprint_after_sale " +
            "ON fingerprint_after_sale.id = fingerprint_row.after_sale_id " +
            "JOIN $archive.trade_archive_order_manifest archive_manifest " +
            "ON archive_manifest.order_id = fingerprint_after_sale.order_id " +
            "AND archive_manifest.job_id = $jobLiteral"
        }
        'outbox_event' {
            @"
$DataSchema.outbox_event fingerprint_row
WHERE EXISTS (
    SELECT 1
    FROM $archive.trade_archive_order_manifest archive_manifest
    JOIN $DataSchema.trade_order fingerprint_order
      ON fingerprint_order.id = archive_manifest.order_id
    LEFT JOIN $DataSchema.after_sale_order fingerprint_after_sale
      ON fingerprint_after_sale.order_id = archive_manifest.order_id
    WHERE archive_manifest.job_id = $jobLiteral
      AND (
          (fingerprint_row.aggregate_type = 'TradeOrder'
           AND fingerprint_row.aggregate_id = fingerprint_order.order_no)
          OR
          (fingerprint_row.aggregate_type = 'AfterSaleOrder'
           AND fingerprint_row.aggregate_id = fingerprint_after_sale.after_sale_no)
      )
)
"@
        }
        default {
            throw "Unsupported archive fingerprint table: $Table"
        }
    }
}

function Get-TableFingerprint {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$Table,
        [Parameter(Mandatory)][string]$DataSchema
    )

    $columns = @(Get-TableColumns -Shard $Shard -Table $Table)
    if ($columns.Count -eq 0) {
        throw "No columns found for $($Shard.SourceSchema).$Table."
    }
    $encodedColumns = $columns | ForEach-Object {
        "COALESCE(HEX(CAST(fingerprint_row.``$_`` AS BINARY)), 'NULL')"
    }
    $rowExpression = "SHA2(CONCAT_WS('#', $($encodedColumns -join ', ')), 256)"
    $fromSql = Get-TableFromSql -Shard $Shard -Table $Table -DataSchema $DataSchema
    $rows = @(Invoke-MySqlRows -Shard $Shard -Sql @"
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
    FROM $fromSql
) fingerprint_rows;
"@)
    $fields = $rows[0].Split("`t")
    return [pscustomobject]@{
        table = $Table
        count = [long]$fields[0]
        xor0 = $fields[1]
        xor1 = $fields[2]
        xor2 = $fields[3]
        xor3 = $fields[4]
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).
        ToLowerInvariant()
}

function Get-AggregateFingerprint {
    param(
        [Parameter(Mandatory)][pscustomobject]$Shard,
        [Parameter(Mandatory)][string]$DataSchema
    )

    $tables = [Collections.Generic.List[object]]::new()
    foreach ($table in $archiveTables) {
        $tables.Add((Get-TableFingerprint -Shard $Shard -Table $table `
                    -DataSchema $DataSchema))
    }
    $canonical = ($tables | ForEach-Object {
            "$($_.table)|$($_.count)|$($_.xor0)|$($_.xor1)|$($_.xor2)|" +
            "$($_.xor3)"
        }) -join "`n"
    return [pscustomobject]@{
        digest = Get-Sha256 -Value $canonical
        tables = @($tables)
    }
}

function Verify-ArchiveShard {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $job = Get-Job -Shard $Shard
    if ($job.Status -notin @('COPIED', 'VERIFY_FAILED', 'VERIFIED')) {
        throw "Archive job $JobId on shard $($Shard.Index) cannot verify from status " +
            "$($job.Status)."
    }
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    $cutoffLiteral = ConvertTo-SqlLiteral $job.CutoffAt
    $eligibility = Get-EligibilitySql -Shard $Shard -OrderAlias 'source_order' `
        -CutoffLiteral $cutoffLiteral
    $currentEligible = [long](Get-MySqlScalar -Shard $Shard -Sql @"
SELECT COUNT(*)
FROM $($Shard.SourceSchema).trade_order source_order
WHERE source_order.id <= $($job.HighWatermarkId)
  AND $eligibility;
"@)
    $manifestCount = [long](Get-MySqlScalar -Shard $Shard -Sql @"
SELECT COUNT(*)
FROM $($Shard.ArchiveSchema).trade_archive_order_manifest
WHERE job_id = $jobLiteral;
"@)

    $sourceFingerprint = Get-AggregateFingerprint -Shard $Shard `
        -DataSchema $Shard.SourceSchema
    $targetFingerprint = Get-AggregateFingerprint -Shard $Shard `
        -DataSchema $Shard.ArchiveSchema
    $verified = $currentEligible -eq $job.EligibleOrderCount -and
        $manifestCount -eq $job.EligibleOrderCount -and
        $sourceFingerprint.digest -eq $targetFingerprint.digest
    $failureMessage = if ($verified) {
        'NULL'
    }
    else {
        ConvertTo-SqlLiteral (
            "eligible=$currentEligible/$($job.EligibleOrderCount), " +
            "manifest=$manifestCount, source=$($sourceFingerprint.digest), " +
            "target=$($targetFingerprint.digest)")
    }
    $newStatus = if ($verified) { 'VERIFIED' } else { 'VERIFY_FAILED' }
    [void](Invoke-ContainerMySql -Container $Shard.Container -Sql @"
UPDATE $($Shard.ArchiveSchema).trade_archive_job
SET status = '$newStatus',
    source_digest = $(ConvertTo-SqlLiteral $sourceFingerprint.digest),
    target_digest = $(ConvertTo-SqlLiteral $targetFingerprint.digest),
    failure_message = $failureMessage,
    verified_at = CASE WHEN '$newStatus' = 'VERIFIED' THEN NOW(3) ELSE NULL END,
    updated_at = NOW(3)
WHERE job_id = $jobLiteral;
"@)

    $tableComparisons = for ($index = 0; $index -lt $archiveTables.Count; $index++) {
        [pscustomobject]@{
            table = $archiveTables[$index]
            source = $sourceFingerprint.tables[$index]
            target = $targetFingerprint.tables[$index]
            matches = (
                ($sourceFingerprint.tables[$index] | ConvertTo-Json -Compress) -eq
                ($targetFingerprint.tables[$index] | ConvertTo-Json -Compress))
        }
    }
    $result = [pscustomobject]@{
        shard = $Shard.Index
        verified = $verified
        eligibleOrders = $currentEligible
        expectedEligibleOrders = $job.EligibleOrderCount
        manifestOrders = $manifestCount
        sourceDigest = $sourceFingerprint.digest
        targetDigest = $targetFingerprint.digest
        tables = @($tableComparisons)
    }
    if (-not $verified) {
        throw "Archive verification failed on shard $($Shard.Index): " +
            ($result | ConvertTo-Json -Depth 8 -Compress)
    }
    return $result
}

function Promote-Archive {
    param([Parameter(Mandatory)][pscustomobject[]]$Shards)

    foreach ($shard in $Shards) {
        [void](Verify-ArchiveShard -Shard $shard)
    }
    foreach ($shard in $Shards) {
        $job = Get-Job -Shard $shard
        if ($job.Status -ne 'VERIFIED' -or
                -not $job.SourceDigest -or
                $job.SourceDigest -ne $job.TargetDigest) {
            throw "Shard $($shard.Index) is not eligible for archive read cutover."
        }
    }
    foreach ($shard in $Shards) {
        $job = Get-Job -Shard $shard
        $jobLiteral = ConvertTo-SqlLiteral $JobId
        [void](Invoke-ContainerMySql -Container $shard.Container -Sql @"
START TRANSACTION;
INSERT INTO $($shard.ArchiveSchema).trade_archive_read_cutover (
    cutover_key, job_id, source_shard, source_digest, target_digest, activated_at
)
VALUES (
    'historical-orders', $jobLiteral, $($shard.Index),
    $(ConvertTo-SqlLiteral $job.SourceDigest),
    $(ConvertTo-SqlLiteral $job.TargetDigest),
    NOW(3)
)
ON DUPLICATE KEY UPDATE
    job_id = VALUES(job_id),
    source_shard = VALUES(source_shard),
    source_digest = VALUES(source_digest),
    target_digest = VALUES(target_digest),
    activated_at = NOW(3);
UPDATE $($shard.ArchiveSchema).trade_archive_job
SET status = 'PROMOTED', promoted_at = NOW(3), updated_at = NOW(3)
WHERE job_id = $jobLiteral;
COMMIT;
"@)
    }
}

function Rollback-ArchiveShard {
    param([Parameter(Mandatory)][pscustomobject]$Shard)

    $archive = $Shard.ArchiveSchema
    $jobLiteral = ConvertTo-SqlLiteral $JobId
    [void](Invoke-ContainerMySql -Container $Shard.Container -Sql @"
USE $archive;
START TRANSACTION;
CREATE TEMPORARY TABLE tmp_trade_archive_rollback_order (
    order_id BIGINT NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB
SELECT order_id
FROM $archive.trade_archive_order_manifest
WHERE job_id = $jobLiteral;

DELETE archive_outbox
FROM $archive.outbox_event archive_outbox
WHERE EXISTS (
    SELECT 1
    FROM tmp_trade_archive_rollback_order rollback_order
    JOIN $archive.trade_order archive_order
      ON archive_order.id = rollback_order.order_id
    LEFT JOIN $archive.after_sale_order archive_after_sale
      ON archive_after_sale.order_id = rollback_order.order_id
    WHERE (
        archive_outbox.aggregate_type = 'TradeOrder'
        AND archive_outbox.aggregate_id = archive_order.order_no
    ) OR (
        archive_outbox.aggregate_type = 'AfterSaleOrder'
        AND archive_outbox.aggregate_id = archive_after_sale.after_sale_no
    )
);

DELETE archive_history
FROM $archive.after_sale_history archive_history
JOIN $archive.after_sale_order archive_after_sale
  ON archive_after_sale.id = archive_history.after_sale_id
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_after_sale.order_id;

DELETE archive_item
FROM $archive.after_sale_item archive_item
JOIN $archive.after_sale_order archive_after_sale
  ON archive_after_sale.id = archive_item.after_sale_id
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_after_sale.order_id;

DELETE archive_after_sale
FROM $archive.after_sale_order archive_after_sale
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_after_sale.order_id;

DELETE archive_row
FROM $archive.order_discount_allocation archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_row
FROM $archive.order_price_snapshot archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_row
FROM $archive.order_benefit_selection archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_row
FROM $archive.order_address_snapshot archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_row
FROM $archive.order_status_history archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_row
FROM $archive.order_item archive_row
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_row.order_id;
DELETE archive_order
FROM $archive.trade_order archive_order
JOIN tmp_trade_archive_rollback_order rollback_order
  ON rollback_order.order_id = archive_order.id;

DELETE FROM $archive.trade_archive_order_manifest
WHERE job_id = $jobLiteral;
DELETE FROM $archive.trade_archive_batch
WHERE job_id = $jobLiteral;
DELETE FROM $archive.trade_archive_read_cutover
WHERE cutover_key = 'historical-orders' AND job_id = $jobLiteral;
UPDATE $archive.trade_archive_job
SET status = 'ROLLED_BACK',
    last_order_id = 0,
    migrated_order_count = 0,
    source_digest = NULL,
    target_digest = NULL,
    failure_message = NULL,
    verified_at = NULL,
    promoted_at = NULL,
    rolled_back_at = NOW(3),
    updated_at = NOW(3)
WHERE job_id = $jobLiteral;
COMMIT;
"@)
}

function Get-Status {
    param([Parameter(Mandatory)][pscustomobject[]]$Shards)

    return @($Shards | ForEach-Object {
            $shard = $_
            $job = Get-Job -Shard $shard
            $jobLiteral = ConvertTo-SqlLiteral $JobId
            $batchCount = [long](Get-MySqlScalar -Shard $shard -Sql @"
SELECT COUNT(*)
FROM $($shard.ArchiveSchema).trade_archive_batch
WHERE job_id = $jobLiteral;
"@)
            $cutover = [long](Get-MySqlScalar -Shard $shard -Sql @"
SELECT COUNT(*)
FROM $($shard.ArchiveSchema).trade_archive_read_cutover
WHERE cutover_key = 'historical-orders' AND job_id = $jobLiteral;
"@)
            [pscustomobject]@{
                shard = $shard.Index
                sourceSchema = $shard.SourceSchema
                archiveSchema = $shard.ArchiveSchema
                status = $job.Status
                cutoffAt = $job.CutoffAt
                lastOrderId = $job.LastOrderId
                highWatermarkId = $job.HighWatermarkId
                eligibleOrders = $job.EligibleOrderCount
                migratedOrders = $job.MigratedOrderCount
                batches = $batchCount
                readCutoverActive = $cutover -eq 1
                sourceDigest = $job.SourceDigest
                targetDigest = $job.TargetDigest
            }
        })
}

Import-DotEnv -Path $EnvironmentFile
if (-not $env:MYSQL_ROOT_PASSWORD) {
    throw 'MYSQL_ROOT_PASSWORD is required.'
}
if (-not $SourceShard0Schema) {
    $SourceShard0Schema = $env:TRADE_SHARD_0_DB_NAME
}
if (-not $SourceShard1Schema) {
    $SourceShard1Schema = $env:TRADE_SHARD_1_DB_NAME
}
if (-not $ArchiveShard0Schema) {
    $ArchiveShard0Schema = "$($SourceShard0Schema)_archive"
}
if (-not $ArchiveShard1Schema) {
    $ArchiveShard1Schema = "$($SourceShard1Schema)_archive"
}
foreach ($entry in @(
        @{ Value = $SourceShard0Schema; Name = 'SourceShard0Schema' },
        @{ Value = $SourceShard1Schema; Name = 'SourceShard1Schema' },
        @{ Value = $ArchiveShard0Schema; Name = 'ArchiveShard0Schema' },
        @{ Value = $ArchiveShard1Schema; Name = 'ArchiveShard1Schema' })) {
    Assert-Identifier -Value $entry.Value -Name $entry.Name
}

$shards = @(
    [pscustomobject]@{
        Index = 0
        Container = 'plainjournal-mysql'
        SourceSchema = $SourceShard0Schema
        ArchiveSchema = $ArchiveShard0Schema
    },
    [pscustomobject]@{
        Index = 1
        Container = 'plainjournal-mysql-trade-shard-1'
        SourceSchema = $SourceShard1Schema
        ArchiveSchema = $ArchiveShard1Schema
    }
)
foreach ($shard in $shards) {
    Assert-ContainerReady -Container $shard.Container
}

$actionResult = switch ($Action) {
    'Initialize' {
        foreach ($shard in $shards) {
            Initialize-ArchiveShard -Shard $shard
        }
        [pscustomobject]@{ initialized = $true }
    }
    'Migrate' {
        [pscustomobject]@{
            committedBatches = Invoke-Migration -Shards $shards
        }
    }
    'Verify' {
        [pscustomobject]@{
            shards = @($shards | ForEach-Object { Verify-ArchiveShard -Shard $_ })
        }
    }
    'Promote' {
        Promote-Archive -Shards $shards
        [pscustomobject]@{ promoted = $true }
    }
    'Rollback' {
        foreach ($shard in $shards) {
            Rollback-ArchiveShard -Shard $shard
        }
        [pscustomobject]@{ rolledBack = $true; sourceFactsDeleted = $false }
    }
    'Status' {
        [pscustomobject]@{ shards = @(Get-Status -Shards $shards) }
    }
}

$result = [pscustomobject]@{
    action = $Action
    jobId = $JobId
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    result = $actionResult
    status = @(Get-Status -Shards $shards)
}
$json = $result | ConvertTo-Json -Depth 12
if ($OutputPath) {
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    $parent = Split-Path -Parent $resolvedOutput
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    [IO.File]::WriteAllText(
        $resolvedOutput,
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}
Write-Output $json
