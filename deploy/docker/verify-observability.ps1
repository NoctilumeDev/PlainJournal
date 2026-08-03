#requires -Version 7.0

[CmdletBinding()]
param(
    [switch]$SkipNetworkPreflight,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$operationalServices = @(
    [pscustomobject]@{ Name = 'inventory-service'; Port = 18103 },
    [pscustomobject]@{ Name = 'trade-service'; Port = 18104 },
    [pscustomobject]@{ Name = 'payment-service'; Port = 18105 },
    [pscustomobject]@{ Name = 'fulfillment-service'; Port = 18106 },
    [pscustomobject]@{ Name = 'marketing-service'; Port = 18107 }
)

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

function Wait-ContainerHealthy {
    param(
        [Parameter(Mandatory)][string]$Container,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
            $Container 2>$null
        if ($status -eq 'healthy') {
            return
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for healthy container $Container. Last status: $status"
}

function Wait-PrometheusTargets {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string[]]$ExpectedServices,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = 'Prometheus API not ready'
    do {
        try {
            $targets = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/targets" -TimeoutSec 5
            $operationalTargets = @($targets.data.activeTargets |
                    Where-Object { $_.scrapePool -eq 'plainjournal-backend' })
            $healthyTargets = @($operationalTargets | Where-Object { $_.health -eq 'up' })
            $actualServices = @($operationalTargets |
                    ForEach-Object { $_.labels.service } |
                    Where-Object { $_ } |
                    Sort-Object -Unique)
            $missingServices = @($ExpectedServices |
                    Where-Object { $actualServices -notcontains $_ })
            $unexpectedServices = @($actualServices |
                    Where-Object { $ExpectedServices -notcontains $_ })
            $duplicateServices = @($operationalTargets |
                    Group-Object { $_.labels.service } |
                    Where-Object { $_.Count -ne 1 } |
                    ForEach-Object Name)
            $lastState = "$($healthyTargets.Count)/$($operationalTargets.Count) targets up; " +
                    "missing=$($missingServices -join ','); " +
                    "unexpected=$($unexpectedServices -join ','); " +
                    "duplicates=$($duplicateServices -join ',')"
            if ($operationalTargets.Count -eq $ExpectedServices.Count -and
                $healthyTargets.Count -eq $ExpectedServices.Count -and
                $missingServices.Count -eq 0 -and
                $unexpectedServices.Count -eq 0 -and
                $duplicateServices.Count -eq 0) {
                return
            }
        }
        catch {
            $lastState = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw "Prometheus did not scrape the expected operational services. Last state: $lastState"
}

function Wait-TempoReady {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    do {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/ready" `
                -TimeoutSec 5 -SkipHttpErrorCheck
            if ([int]$response.StatusCode -eq 200) {
                return
            }
            $lastError = "HTTP $([int]$response.StatusCode)"
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw "Tempo did not become ready. Last error: $lastError"
}

function Wait-GrafanaDashboard {
    param(
        [Parameter(Mandatory)][int]$Port,
        [int]$TimeoutSeconds = 60
    )

    $basicValue = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("plainjournal-admin:$env:GRAFANA_ADMIN_PASSWORD"))
    $headers = @{ Authorization = "Basic $basicValue" }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    do {
        try {
            $dashboard = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$Port/api/dashboards/uid/plainjournal-operations" `
                -Headers $headers -TimeoutSec 5
            if ($dashboard.dashboard.title -eq 'PlainJournal Operations') {
                $datasource = Invoke-RestMethod `
                    -Uri "http://127.0.0.1:$Port/api/datasources/uid/plainjournal-tempo" `
                    -Headers $headers -TimeoutSec 5
                if ($datasource.type -eq 'tempo' -and $datasource.url -eq 'http://tempo:3200') {
                    return
                }
                $lastError = 'Provisioned Tempo datasource did not match.'
                continue
            }
            $lastError = 'Provisioned dashboard title did not match.'
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)

    throw "Grafana dashboard provisioning did not converge. Last error: $lastError"
}

$envFile = Join-Path $PSScriptRoot '.env'
$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$secretFile = Join-Path $PSScriptRoot '.runtime-secrets\metrics-scrape-token'
if (-not (Test-Path -LiteralPath $envFile) -or
    -not (Test-Path -LiteralPath $composeFile) -or
    -not (Test-Path -LiteralPath $secretFile)) {
    throw 'Run bootstrap-resources.ps1 before verifying the observability profile.'
}
Import-DotEnv -Path $envFile
if (-not $env:METRICS_SCRAPE_TOKEN -or $env:METRICS_SCRAPE_TOKEN.Length -lt 32) {
    throw 'METRICS_SCRAPE_TOKEN is missing or too short.'
}
if (-not $env:GRAFANA_ADMIN_PASSWORD -or $env:GRAFANA_ADMIN_PASSWORD.Length -lt 24) {
    throw 'GRAFANA_ADMIN_PASSWORD is missing or too short.'
}
$prometheusPort = if ($env:PROMETHEUS_PORT) { [int]$env:PROMETHEUS_PORT } else { 19090 }
$grafanaPort = if ($env:GRAFANA_PORT) { [int]$env:GRAFANA_PORT } else { 13000 }
$tempoPort = if ($env:TEMPO_HTTP_PORT) { [int]$env:TEMPO_HTTP_PORT } else { 13200 }

if (-not $SkipNetworkPreflight) {
    $networkCheck = 'D:\DevTools\Network\check-dev-network.ps1'
    if (-not (Test-Path -LiteralPath $networkCheck)) {
        throw "Missing required local network diagnostic: $networkCheck"
    }
    & $networkCheck
    if ($LASTEXITCODE -ne 0) {
        throw "Local network preflight failed with exit code $LASTEXITCODE"
    }
}

foreach ($service in $operationalServices) {
    $health = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/actuator/health/liveness" `
        -TimeoutSec 5 -SkipHttpErrorCheck
    if ([int]$health.StatusCode -ne 200) {
        throw "$($service.Name) on port $($service.Port) is not ready for observability verification."
    }

    $anonymous = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/actuator/prometheus" `
        -TimeoutSec 5 -SkipHttpErrorCheck
    if ([int]$anonymous.StatusCode -ne 401) {
        throw "$($service.Name) Prometheus endpoint was accessible without credentials."
    }

    $scrape = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/actuator/prometheus" `
        -Headers @{ 'X-Metrics-Token' = $env:METRICS_SCRAPE_TOKEN } -TimeoutSec 5
    if ($scrape.Content -notmatch 'ecommerce_consumer_failure_active_events') {
        throw "$($service.Name) Prometheus endpoint did not expose shared operational metrics."
    }
}

$containers = @('plainjournal-prometheus', 'plainjournal-alertmanager', 'plainjournal-tempo', 'plainjournal-grafana')
$healthyContainers = @('plainjournal-prometheus', 'plainjournal-alertmanager', 'plainjournal-grafana')
$originalState = @{}
foreach ($container in $containers) {
    $exists = docker inspect $container *> $null
    $containerExists = $LASTEXITCODE -eq 0
    $running = $false
    if ($containerExists) {
        $running = (docker inspect --format '{{.State.Running}}' $container 2>$null) -eq 'true'
    }
    $originalState[$container] = @{ Exists = $containerExists; Running = $running }
}

try {
    docker compose --project-directory $PSScriptRoot --env-file $envFile `
        --file $composeFile --profile observability config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'Observability Compose model is invalid.'
    }
    docker compose --project-directory $PSScriptRoot --env-file $envFile `
        --file $composeFile --profile observability up -d
    if ($LASTEXITCODE -ne 0) {
        throw 'Observability containers failed to start.'
    }

    foreach ($container in $healthyContainers) {
        Wait-ContainerHealthy -Container $container
    }
    Wait-TempoReady -Port $tempoPort

    docker exec plainjournal-prometheus promtool check config /etc/prometheus/prometheus.yml
    if ($LASTEXITCODE -ne 0) {
        throw 'Prometheus configuration or alert rules are invalid.'
    }
    docker exec plainjournal-alertmanager amtool check-config /etc/alertmanager/alertmanager.yml
    if ($LASTEXITCODE -ne 0) {
        throw 'Alertmanager configuration is invalid.'
    }

    Wait-PrometheusTargets `
        -Port $prometheusPort `
        -ExpectedServices @($operationalServices.Name)

    $rules = Invoke-RestMethod -Uri "http://127.0.0.1:$prometheusPort/api/v1/rules" -TimeoutSec 5
    $groupNames = @($rules.data.groups | ForEach-Object name)
    foreach ($expectedGroup in @(
            'plainjournal-service-health',
            'plainjournal-event-consistency',
            'plainjournal-synchronous-resilience',
            'plainjournal-business-convergence')) {
        if ($groupNames -notcontains $expectedGroup) {
            throw "Prometheus did not load alert group $expectedGroup."
        }
    }
    $unhealthyRules = @($rules.data.groups.rules | Where-Object { $_.health -ne 'ok' })
    if ($unhealthyRules.Count -gt 0) {
        throw 'At least one Prometheus alert rule is not healthy.'
    }

    $alertmanagers = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$prometheusPort/api/v1/alertmanagers" -TimeoutSec 5
    if (@($alertmanagers.data.activeAlertmanagers).Count -ne 1) {
        throw 'Prometheus did not discover the local Alertmanager.'
    }
    Wait-GrafanaDashboard -Port $grafanaPort

    Write-Output 'Observability smoke verification: PASS'
    Write-Output '  Dedicated metrics identity and anonymous rejection: PASS'
    Write-Output "  Prometheus config/rules and $($operationalServices.Count) live scrape targets: PASS"
    Write-Output '  Alertmanager routing readiness: PASS'
    Write-Output '  Tempo OTLP/query readiness: PASS'
    Write-Output '  Grafana Prometheus/Tempo datasources and operations dashboard provisioning: PASS'
}
finally {
    if (-not $KeepRunning) {
        foreach ($container in $containers) {
            if (-not $originalState[$container].Running) {
                docker stop $container *> $null
            }
            if (-not $originalState[$container].Exists) {
                docker rm $container *> $null
            }
        }
    }
}
