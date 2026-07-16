$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$envFile = Join-Path $PSScriptRoot '..\..\deploy\docker\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Local middleware environment file not found: $envFile"
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

$env:NACOS_USERNAME = 'nacos'
$env:MYSQL_HOST = '127.0.0.1'
$env:REDIS_HOST = '127.0.0.1'
$env:NACOS_HOST = '127.0.0.1'
$env:MINIO_HOST = '127.0.0.1'
$env:ROCKETMQ_HOST = '127.0.0.1'

$requiredContainers = @(
    'ecom-mysql',
    'ecom-redis',
    'ecom-nacos',
    'ecom-rocketmq-broker',
    'ecom-minio'
)

foreach ($container in $requiredContainers) {
    $running = docker inspect --format '{{.State.Running}}' $container 2>$null
    if ($running -ne 'true') {
        throw "Required container is not running: $container"
    }
}

Push-Location $PSScriptRoot
try {
    mvn clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

