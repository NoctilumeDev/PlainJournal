[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputPath = "docs/quality/spotbugs-summary.json",
    [switch]$Check
)

$ErrorActionPreference = "Stop"

$root = [System.IO.Path]::GetFullPath($RepositoryRoot)
$backendRoot = Join-Path $root "backend"
$resolvedOutput = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    [System.IO.Path]::GetFullPath($OutputPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $OutputPath))
}

$reports = Get-ChildItem -Path $backendRoot -Recurse -Filter "spotbugsXml.xml" -File |
    Sort-Object FullName

if ($reports.Count -eq 0) {
    throw "No SpotBugs XML reports found below $backendRoot"
}

$instances = [System.Collections.Generic.List[object]]::new()
$modules = [System.Collections.Generic.List[object]]::new()
$missingClasses = 0
$analysisErrors = 0

foreach ($report in $reports) {
    [xml]$document = Get-Content -LiteralPath $report.FullName
    $moduleDirectory = $report.Directory.Parent.FullName
    $module = [System.IO.Path]::GetRelativePath($backendRoot, $moduleDirectory).
        Replace([System.IO.Path]::DirectorySeparatorChar, "/")
    $bugs = @($document.BugCollection.BugInstance)

    $moduleInstances = foreach ($bug in $bugs) {
        [pscustomobject]@{
            module = $module
            type = [string]$bug.type
            category = [string]$bug.category
            priority = [int]$bug.priority
            rank = [int]$bug.rank
            class = [string]$bug.Class.classname
            method = [string]$bug.Method.name
            field = [string]$bug.Field.name
        }
    }
    foreach ($instance in $moduleInstances) {
        $instances.Add($instance)
    }

    $modules.Add([pscustomobject]@{
        module = $module
        total = $bugs.Count
        priority1 = @($moduleInstances | Where-Object priority -eq 1).Count
        priority2 = @($moduleInstances | Where-Object priority -eq 2).Count
        priority3 = @($moduleInstances | Where-Object priority -eq 3).Count
    })

    $missingClasses += [int]$document.BugCollection.Errors.missingClasses
    $analysisErrors += [int]$document.BugCollection.Errors.errors
}

$patterns = $instances |
    Group-Object type |
    ForEach-Object {
        $group = @($_.Group)
        [pscustomobject]@{
            type = $_.Name
            category = [string]($group | Select-Object -First 1).category
            total = $_.Count
            priority1 = @($group | Where-Object priority -eq 1).Count
            priority2 = @($group | Where-Object priority -eq 2).Count
            priority3 = @($group | Where-Object priority -eq 3).Count
            modules = @($group.module | Sort-Object -Unique)
            classes = @(
                $group |
                    Group-Object class |
                    ForEach-Object {
                        [pscustomobject]@{
                            class = $_.Name
                            count = $_.Count
                        }
                    } |
                    Sort-Object class
            )
        }
    } |
    Sort-Object type

$result = [ordered]@{
    schemaVersion = 1
    scanner = [ordered]@{
        plugin = "com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2"
        effort = "Max"
        threshold = "Low"
    }
    summary = [ordered]@{
        reports = $reports.Count
        total = $instances.Count
        priority1 = @($instances | Where-Object priority -eq 1).Count
        priority2 = @($instances | Where-Object priority -eq 2).Count
        priority3 = @($instances | Where-Object priority -eq 3).Count
        missingClasses = $missingClasses
        analysisErrors = $analysisErrors
    }
    modules = @($modules | Sort-Object module)
    patterns = @($patterns)
}

$json = $result | ConvertTo-Json -Depth 8
$normalized = $json.Replace("`r`n", "`n").TrimEnd() + "`n"

if ($Check) {
    if (-not (Test-Path -LiteralPath $resolvedOutput)) {
        throw "SpotBugs summary is missing: $resolvedOutput"
    }
    $current = [System.IO.File]::ReadAllText($resolvedOutput).Replace("`r`n", "`n")
    if ($current -ne $normalized) {
        throw "SpotBugs summary is stale. Run tools/summarize-spotbugs.ps1"
    }
    Write-Host "SpotBugs summary is current: $resolvedOutput"
    exit 0
}

$outputDirectory = Split-Path -Parent $resolvedOutput
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
[System.IO.File]::WriteAllText(
    $resolvedOutput,
    $normalized,
    [System.Text.UTF8Encoding]::new($false))
Write-Host "Wrote SpotBugs summary: $resolvedOutput"
