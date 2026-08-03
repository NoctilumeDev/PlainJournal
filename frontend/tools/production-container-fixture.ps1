param(
    [ValidateSet("verify", "status", "stop")]
    [string] $Action = "status",
    [string] $BaselineTag = "v1.0.0-rc.0",
    [string] $CandidateTag = "v1.0.0-rc.1",
    [string] $ImagePrefix = "plainjournal-v7-4",
    [switch] $SkipBuild,
    [switch] $KeepRunning
)

$ErrorActionPreference = "Stop"
$frontendRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $frontendRoot
$deploymentRoot = Join-Path $frontendRoot "deploy/nginx"
$composePath = Join-Path $deploymentRoot "compose.yml"
$runRoot = Join-Path $frontendRoot ".run"
$statePath = Join-Path $runRoot "v7-4-container-state.json"
$evidencePath = Join-Path $runRoot "v7-4-production-verification.json"
$ports = @(18090, 18300, 18301)
$environmentNames = @(
    "PLAINJOURNAL_IMAGE_PREFIX",
    "PLAINJOURNAL_FRONTEND_TAG",
    "PLAINJOURNAL_GATEWAY_HOST",
    "PLAINJOURNAL_GATEWAY_PORT",
    "PLAINJOURNAL_OCI_CREATED",
    "PLAINJOURNAL_OCI_REVISION",
    "PLAINJOURNAL_OCI_SOURCE",
    "PLAINJOURNAL_OCI_VERSION"
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name,
        [EnvironmentVariableTarget]::Process
    )
}

function Restore-PlainJournalEnvironment {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $originalEnvironment[$name],
            [EnvironmentVariableTarget]::Process
        )
    }
}

function Assert-PlainJournal {
    param(
        [Parameter(Mandatory = $true)]
        [bool] $Condition,
        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-PlainJournalCompose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    Push-Location $deploymentRoot
    try {
        & docker compose -f $composePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Set-PlainJournalReleaseEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Tag,
        [Parameter(Mandatory = $true)]
        [string] $Created,
        [Parameter(Mandatory = $true)]
        [string] $Revision,
        [Parameter(Mandatory = $true)]
        [string] $Source
    )

    $env:PLAINJOURNAL_IMAGE_PREFIX = $ImagePrefix
    $env:PLAINJOURNAL_FRONTEND_TAG = $Tag
    $env:PLAINJOURNAL_GATEWAY_HOST = "host.docker.internal"
    $env:PLAINJOURNAL_GATEWAY_PORT = "18090"
    $env:PLAINJOURNAL_OCI_CREATED = $Created
    $env:PLAINJOURNAL_OCI_REVISION = $Revision
    $env:PLAINJOURNAL_OCI_SOURCE = $Source
    $env:PLAINJOURNAL_OCI_VERSION = $Tag
}

function Get-PlainJournalListeners {
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $ports } |
        Sort-Object LocalPort
}

function Wait-PlainJournalUrl {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url,
        [int] $TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        } catch {
            # The next bounded probe determines readiness.
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for $Url"
}

function Get-PlainJournalStatusCode {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Url
    )

    try {
        return [int](Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 10).StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Wait-PlainJournalContainersHealthy {
    param(
        [int] $TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $containerIds = @(
            (& docker compose -f $composePath ps -q storefront-web admin-web) |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
        if ($containerIds.Count -eq 2) {
            $health = @(
                $containerIds |
                    ForEach-Object {
                        (& docker inspect --format "{{.State.Health.Status}}" $_).Trim()
                    }
            )
            if ($health.Count -eq 2 -and ($health | Where-Object { $_ -ne "healthy" }).Count -eq 0) {
                return $containerIds
            }
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Frontend containers did not become healthy within $TimeoutSeconds seconds"
}

function Get-PlainJournalContainerEvidence {
    $serviceEvidence = [ordered]@{}
    foreach ($service in @("storefront-web", "admin-web")) {
        $containerId = (& docker compose -f $composePath ps -q $service).Trim()
        Assert-PlainJournal (-not [string]::IsNullOrWhiteSpace($containerId)) "$service container is missing"
        $serviceEvidence[$service] = [ordered]@{
            containerId = $containerId
            imageId = (& docker inspect --format "{{.Image}}" $containerId).Trim()
            health = (& docker inspect --format "{{.State.Health.Status}}" $containerId).Trim()
        }
    }
    return $serviceEvidence
}

function Get-PlainJournalImageLabels {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Image
    )

    $json = (& docker image inspect --format "{{json .Config.Labels}}" $Image).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        throw "Unable to inspect image labels for $Image"
    }
    return $json | ConvertFrom-Json
}

function Stop-RecordedPlainJournalMock {
    param(
        [Parameter(Mandatory = $true)]
        [int] $ProcessId
    )

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return
    }
    if ($process.CommandLine -notmatch "e2e[/\\]mock-api\.mjs") {
        throw "Refusing to stop PID $ProcessId because it is not the PlainJournal Mock API"
    }
    Stop-Process -Id $ProcessId -Force
}

if ($Action -eq "status") {
    try {
        Invoke-PlainJournalCompose -Arguments @("ps")
        if (Test-Path $statePath) {
            Get-Content -Raw $statePath
        }
    } finally {
        Restore-PlainJournalEnvironment
    }
    exit 0
}

if ($Action -eq "stop") {
    try {
        Invoke-PlainJournalCompose -Arguments @("down", "--remove-orphans")
        if (Test-Path $statePath) {
            $state = Get-Content -Raw $statePath | ConvertFrom-Json
            if ($null -ne $state.mockProcessId) {
                Stop-RecordedPlainJournalMock -ProcessId ([int]$state.mockProcessId)
            }
            Remove-Item -LiteralPath $statePath -Force
        }
    } finally {
        Restore-PlainJournalEnvironment
    }
    exit 0
}

$mockProcess = $null
$composeStarted = $false
$verificationSucceeded = $false

try {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not ready. Run the documented network preflight before this verifier."
    }

    $occupied = Get-PlainJournalListeners
    if ($occupied) {
        $descriptions = $occupied |
            ForEach-Object { "$($_.LocalPort)/PID$($_.OwningProcess)" } |
            Sort-Object -Unique
        throw "V7.4 ports are already occupied: $($descriptions -join ', ')"
    }

    New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
    $revision = (& git -C $repositoryRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        throw "Unable to resolve the repository revision"
    }
    $remotes = @(& git -C $repositoryRoot remote)
    if ($remotes -contains "origin") {
        $source = (& git -C $repositoryRoot remote get-url origin).Trim()
    } else {
        $source = ([System.Uri]::new(
            (Resolve-Path -LiteralPath $repositoryRoot).Path + [IO.Path]::DirectorySeparatorChar
        )).AbsoluteUri
    }
    $baselineCreated = (& git -C $repositoryRoot log -1 --format=%cI).Trim()
    $candidateCreated = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

    if (-not $SkipBuild) {
        Set-PlainJournalReleaseEnvironment `
            -Tag $BaselineTag `
            -Created $baselineCreated `
            -Revision $revision `
            -Source $source
        Invoke-PlainJournalCompose -Arguments @("build", "storefront-web", "admin-web")

        Set-PlainJournalReleaseEnvironment `
            -Tag $CandidateTag `
            -Created $candidateCreated `
            -Revision $revision `
            -Source $source
        Invoke-PlainJournalCompose -Arguments @("build", "storefront-web", "admin-web")
    }

    $previousMockPort = $env:PLAIN_JOURNAL_MOCK_API_PORT
    try {
        $env:PLAIN_JOURNAL_MOCK_API_PORT = "18090"
        $mockProcess = Start-Process `
            -FilePath "node.exe" `
            -ArgumentList @("e2e/mock-api.mjs") `
            -WorkingDirectory $frontendRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $runRoot "v7-4-mock.log") `
            -RedirectStandardError (Join-Path $runRoot "v7-4-mock.err.log") `
            -PassThru
    } finally {
        $env:PLAIN_JOURNAL_MOCK_API_PORT = $previousMockPort
    }
    Wait-PlainJournalUrl -Url "http://127.0.0.1:18090/api/v1/identity/me"

    Set-PlainJournalReleaseEnvironment `
        -Tag $CandidateTag `
        -Created $candidateCreated `
        -Revision $revision `
        -Source $source
    Invoke-PlainJournalCompose -Arguments @(
        "up",
        "-d",
        "--no-build",
        "--pull",
        "never",
        "--force-recreate"
    )
    $composeStarted = $true
    Wait-PlainJournalContainersHealthy | Out-Null

    $storefrontIndex = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:18300/index.html" `
        -TimeoutSec 10
    $adminIndex = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:18301/index.html" `
        -TimeoutSec 10
    $entryMatch = [regex]::Match(
        $storefrontIndex.Content,
        'src="(?<path>/assets/[^"]+\.js)"'
    )
    Assert-PlainJournal $entryMatch.Success "Storefront entry asset was not found"
    $entryAssetPath = $entryMatch.Groups["path"].Value
    $entryAsset = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:18300$entryAssetPath" `
        -TimeoutSec 10
    $stableImage = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:18300/images/catalog/canvas-commuter-tote-480.avif" `
        -TimeoutSec 10
    $apiResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "http://127.0.0.1:18300/api/v1/catalog/products/2079000000000000001" `
        -TimeoutSec 10

    $storefrontIndexCache = [string]$storefrontIndex.Headers["Cache-Control"]
    $adminIndexCache = [string]$adminIndex.Headers["Cache-Control"]
    $entryAssetCache = [string]$entryAsset.Headers["Cache-Control"]
    $stableImageCache = [string]$stableImage.Headers["Cache-Control"]
    Assert-PlainJournal ($storefrontIndexCache -match "no-store") "Storefront index.html is not no-store"
    Assert-PlainJournal ($adminIndexCache -match "no-store") "Admin index.html is not no-store"
    Assert-PlainJournal ($entryAssetCache -match "immutable") "Hashed asset is not immutable"
    Assert-PlainJournal ($stableImageCache -notmatch "immutable") "Stable image must not be immutable"
    Assert-PlainJournal ($stableImageCache -match "max-age=86400") "Stable image cache lifetime is incorrect"
    Assert-PlainJournal ($apiResponse.StatusCode -eq 200) "Same-origin API proxy did not return 200"

    $missingAssetStatus = Get-PlainJournalStatusCode `
        -Url "http://127.0.0.1:18300/assets/plainjournal-missing.js"
    $missingImageStatus = Get-PlainJournalStatusCode `
        -Url "http://127.0.0.1:18300/images/plainjournal-missing.avif"
    Assert-PlainJournal ($missingAssetStatus -eq 404) "missing asset did not return 404"
    Assert-PlainJournal ($missingImageStatus -eq 404) "missing image did not return 404"

    $candidateContainers = Get-PlainJournalContainerEvidence
    $candidateImages = [ordered]@{}
    foreach ($application in @("storefront", "admin")) {
        $image = "$ImagePrefix/$application-web`:$CandidateTag"
        $labels = Get-PlainJournalImageLabels -Image $image
        Assert-PlainJournal `
            ($labels.'org.opencontainers.image.version' -eq $CandidateTag) `
            "$image has the wrong OCI version"
        Assert-PlainJournal `
            ($labels.'org.opencontainers.image.revision' -eq $revision) `
            "$image has the wrong OCI revision"
        $candidateImages[$application] = [ordered]@{
            reference = $image
            imageId = (& docker image inspect --format "{{.Id}}" $image).Trim()
            labels = $labels
        }
    }

    Set-PlainJournalReleaseEnvironment `
        -Tag $BaselineTag `
        -Created $baselineCreated `
        -Revision $revision `
        -Source $source
    # Rollback contract: docker compose up -d --no-build --pull never
    Invoke-PlainJournalCompose -Arguments @(
        "up",
        "-d",
        "--no-build",
        "--pull",
        "never",
        "--force-recreate"
    )
    Wait-PlainJournalContainersHealthy | Out-Null
    $rollbackContainers = Get-PlainJournalContainerEvidence
    $baselineImages = [ordered]@{}
    foreach ($application in @("storefront", "admin")) {
        $image = "$ImagePrefix/$application-web`:$BaselineTag"
        $labels = Get-PlainJournalImageLabels -Image $image
        $imageId = (& docker image inspect --format "{{.Id}}" $image).Trim()
        Assert-PlainJournal `
            ($labels.'org.opencontainers.image.version' -eq $BaselineTag) `
            "$image has the wrong rollback OCI version"
        Assert-PlainJournal `
            ($imageId -ne $candidateImages[$application].imageId) `
            "$application rollback image ID did not change"
        Assert-PlainJournal `
            ($rollbackContainers["$application-web"].imageId -eq $imageId) `
            "$application container did not switch to the rollback image"
        $baselineImages[$application] = [ordered]@{
            reference = $image
            imageId = $imageId
            labels = $labels
        }
    }

    Set-PlainJournalReleaseEnvironment `
        -Tag $CandidateTag `
        -Created $candidateCreated `
        -Revision $revision `
        -Source $source
    Invoke-PlainJournalCompose -Arguments @(
        "up",
        "-d",
        "--no-build",
        "--pull",
        "never",
        "--force-recreate"
    )
    Wait-PlainJournalContainersHealthy | Out-Null
    $restoredContainers = Get-PlainJournalContainerEvidence
    foreach ($application in @("storefront", "admin")) {
        Assert-PlainJournal `
            ($restoredContainers["$application-web"].imageId -eq $candidateImages[$application].imageId) `
            "$application container did not restore the candidate image"
    }

    $evidence = [ordered]@{
        verifiedAt = (Get-Date).ToUniversalTime().ToString("o")
        revision = $revision
        source = $source
        candidateTag = $CandidateTag
        baselineTag = $BaselineTag
        headers = [ordered]@{
            storefrontIndex = $storefrontIndexCache
            adminIndex = $adminIndexCache
            entryAsset = $entryAssetCache
            stableImage = $stableImageCache
        }
        status = [ordered]@{
            api = [int]$apiResponse.StatusCode
            missingAsset = $missingAssetStatus
            missingImage = $missingImageStatus
        }
        candidateImages = $candidateImages
        baselineImages = $baselineImages
        candidateContainers = $candidateContainers
        rollbackContainers = $rollbackContainers
        restoredContainers = $restoredContainers
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $evidencePath
    $verificationSucceeded = $true

    if ($KeepRunning) {
        [ordered]@{
            mockProcessId = $mockProcess.Id
            imagePrefix = $ImagePrefix
            candidateTag = $CandidateTag
            baselineTag = $BaselineTag
            evidencePath = $evidencePath
        } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $statePath
    }

    $evidence | ConvertTo-Json -Depth 6
} finally {
    if (-not $KeepRunning -or -not $verificationSucceeded) {
        if ($composeStarted) {
            try {
                Invoke-PlainJournalCompose -Arguments @("down", "--remove-orphans")
            } catch {
                Write-Warning $_
            }
        }
        if ($null -ne $mockProcess) {
            try {
                Stop-RecordedPlainJournalMock -ProcessId $mockProcess.Id
            } catch {
                Write-Warning $_
            }
        }
    }
    Restore-PlainJournalEnvironment
}
