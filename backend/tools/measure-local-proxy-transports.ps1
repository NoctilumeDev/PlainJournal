#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Phase,

    [Parameter(Mandatory)]
    [string]$OutputPath,

    [ValidateRange(1, 10)]
    [int]$CandidatesPerTransport = 2,

    [ValidateRange(1, 10)]
    [int]$AttemptsPerCandidate = 3,

    [ValidateRange(1000, 30000)]
    [int]$NodeTimeoutMilliseconds = 6000,

    [string]$NodeProbeUrl = 'https://cp.cloudflare.com/generate_204',

    [string]$HttpProbeUrl = 'https://repo.maven.apache.org/maven2/',

    [string]$IncidentFile
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$configPath = Join-Path `
    $env:APPDATA `
    'io.github.clash-verge-rev.clash-verge-rev/clash-verge.yaml'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Clash configuration not found: $configPath"
}

$secretLine = Get-Content -LiteralPath $configPath |
    Where-Object { $_ -match '^secret\s*:' } |
    Select-Object -First 1
if (-not $secretLine) {
    throw 'Clash external-controller secret is not configured.'
}
$secret = ($secretLine -replace '^secret\s*:\s*', '').Trim().Trim("'", '"')

function Assert-NoIncident {
    if ($IncidentFile -and (Test-Path -LiteralPath $IncidentFile)) {
        throw "UDP exhaustion monitor triggered: $IncidentFile"
    }
}

function ConvertFrom-ChunkedBody {
    param([Parameter(Mandatory)][byte[]]$Body)

    $decoded = [IO.MemoryStream]::new()
    try {
        $position = 0
        while ($position -lt $Body.Length) {
            $lineEnd = -1
            for ($index = $position; $index -le $Body.Length - 2; $index++) {
                if ($Body[$index] -eq 13 -and $Body[$index + 1] -eq 10) {
                    $lineEnd = $index
                    break
                }
            }
            if ($lineEnd -lt 0) {
                throw 'Invalid chunked response from Mihomo.'
            }

            $sizeText = [Text.Encoding]::ASCII.GetString(
                $Body,
                $position,
                $lineEnd - $position
            ).Split(';')[0]
            $size = [Convert]::ToInt32($sizeText, 16)
            $position = $lineEnd + 2
            if ($size -eq 0) {
                break
            }
            if ($position + $size -gt $Body.Length) {
                throw 'Mihomo chunk exceeds the response body.'
            }

            $decoded.Write($Body, $position, $size)
            $position += $size + 2
        }
        return $decoded.ToArray()
    }
    finally {
        $decoded.Dispose()
    }
}

function Invoke-MihomoPipeRequest {
    param([Parameter(Mandatory)][string]$Path)

    $pipe = [IO.Pipes.NamedPipeClientStream]::new(
        '.',
        'verge-mihomo',
        [IO.Pipes.PipeDirection]::InOut
    )
    try {
        $pipe.Connect(5000)
        $request = @(
            "GET $Path HTTP/1.1"
            'Host: localhost'
            "Authorization: Bearer $secret"
            'Connection: close'
            ''
            ''
        ) -join "`r`n"
        $requestBytes = [Text.Encoding]::ASCII.GetBytes($request)
        $pipe.Write($requestBytes, 0, $requestBytes.Length)
        $pipe.Flush()

        $buffer = New-Object byte[] 65536
        $responseStream = [IO.MemoryStream]::new()
        try {
            do {
                $read = $pipe.Read($buffer, 0, $buffer.Length)
                if ($read -gt 0) {
                    $responseStream.Write($buffer, 0, $read)
                }
            } while ($read -gt 0)
            $responseBytes = $responseStream.ToArray()
        }
        finally {
            $responseStream.Dispose()
        }

        $headerEnd = -1
        for ($index = 0; $index -le $responseBytes.Length - 4; $index++) {
            if ($responseBytes[$index] -eq 13 -and
                    $responseBytes[$index + 1] -eq 10 -and
                    $responseBytes[$index + 2] -eq 13 -and
                    $responseBytes[$index + 3] -eq 10) {
                $headerEnd = $index
                break
            }
        }
        if ($headerEnd -lt 0) {
            throw 'Invalid HTTP response from Mihomo named pipe.'
        }

        $headers = [Text.Encoding]::ASCII.GetString(
            $responseBytes,
            0,
            $headerEnd
        )
        $bodyBytes = $responseBytes[
            ($headerEnd + 4)..($responseBytes.Length - 1)
        ]
        if ($headers -match '(?im)^Transfer-Encoding:\s*chunked') {
            $bodyBytes = ConvertFrom-ChunkedBody -Body $bodyBytes
        }

        return [ordered]@{
            status = ($headers -split "`r?`n")[0]
            body = [Text.Encoding]::UTF8.GetString($bodyBytes)
        }
    }
    finally {
        $pipe.Dispose()
    }
}

function Get-AnonymousNodeId {
    param([Parameter(Mandatory)][string]$Name)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString(
            $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($Name))
        ).Substring(0, 8).ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Invoke-HttpProbe {
    param([Parameter(Mandatory)][bool]$UseProxy)

    $arguments = @(
        '-sS',
        '-o',
        'NUL',
        '-w',
        '%{http_code}|%{time_connect}|%{time_total}',
        '--connect-timeout',
        '6',
        '--max-time',
        '10'
    )
    if ($UseProxy) {
        $arguments += @('-x', 'http://127.0.0.1:7897')
    }
    else {
        $arguments += @('--noproxy', '*')
    }
    $arguments += $HttpProbeUrl

    $result = & curl.exe @arguments 2>&1
    return [pscustomobject][ordered]@{
        mode = if ($UseProxy) { 'proxy' } else { 'direct' }
        result = $result -join ' '
        exitCode = $LASTEXITCODE
    }
}

Assert-NoIncident
$proxyResponse = Invoke-MihomoPipeRequest -Path '/proxies'
if ($proxyResponse.status -notmatch ' 200 ') {
    throw "Mihomo /proxies failed: $($proxyResponse.status)"
}
$proxyData = $proxyResponse.body | ConvertFrom-Json -Depth 30
$proxyProperties = @($proxyData.proxies.PSObject.Properties)
$vlessCandidates = @(
    $proxyProperties |
        Where-Object { $_.Value.type -eq 'Vless' } |
        Sort-Object Name |
        Select-Object -First $CandidatesPerTransport
)
$hysteriaCandidates = @(
    $proxyProperties |
        Where-Object { $_.Value.type -eq 'Hysteria2' } |
        Sort-Object Name |
        Select-Object -First $CandidatesPerTransport
)
if ($vlessCandidates.Count -lt $CandidatesPerTransport -or
        $hysteriaCandidates.Count -lt $CandidatesPerTransport) {
    throw 'Insufficient VLESS or Hysteria2 candidates for the comparison.'
}

$nodeResults = @(
    foreach ($candidate in @($vlessCandidates) + @($hysteriaCandidates)) {
        foreach ($attempt in 1..$AttemptsPerCandidate) {
            Assert-NoIncident
            $encodedName = [Uri]::EscapeDataString($candidate.Name)
            $encodedUrl = [Uri]::EscapeDataString($NodeProbeUrl)
            $path = "/proxies/$encodedName/delay" +
                "?timeout=$NodeTimeoutMilliseconds&url=$encodedUrl"
            $response = Invoke-MihomoPipeRequest -Path $path
            $parsed = $null
            try {
                $parsed = $response.body | ConvertFrom-Json
            }
            catch {
                # Preserve the HTTP status and record a controlled parse error.
            }
            $delay = if ($parsed -and
                    $parsed.PSObject.Properties['delay']) {
                $parsed.delay
            }
            else {
                $null
            }
            $message = if ($parsed -and
                    $parsed.PSObject.Properties['message']) {
                [string]$parsed.message
            }
            elseif ($parsed) {
                $null
            }
            else {
                'invalid-json'
            }

            [pscustomobject][ordered]@{
                nodeId = Get-AnonymousNodeId -Name $candidate.Name
                type = [string]$candidate.Value.type
                attempt = $attempt
                status = $response.status
                delayMilliseconds = $delay
                message = $message
            }
        }
    }
)

$httpResults = @(
    foreach ($attempt in 1..$AttemptsPerCandidate) {
        Assert-NoIncident
        Invoke-HttpProbe -UseProxy $false
        Invoke-HttpProbe -UseProxy $true
    }
)

$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $outputFullPath
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$evidence = [ordered]@{
    schemaVersion = 1
    phase = $Phase
    capturedAt = (Get-Date).ToString('o')
    candidatesPerTransport = $CandidatesPerTransport
    attemptsPerCandidate = $AttemptsPerCandidate
    http = $httpResults
    nodes = $nodeResults
    incidentTriggered = if ($IncidentFile) {
        Test-Path -LiteralPath $IncidentFile
    }
    else {
        $false
    }
}
$evidence |
    ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $outputFullPath -Encoding utf8

$summary = @(
    $nodeResults |
        Group-Object nodeId, type |
        ForEach-Object {
            $successful = @(
                $_.Group |
                    Where-Object { $null -ne $_.delayMilliseconds }
            )
            [ordered]@{
                node = $_.Name
                attempts = $_.Count
                successes = $successful.Count
                meanMilliseconds = if ($successful.Count -gt 0) {
                    [Math]::Round(
                        ($successful |
                            Measure-Object delayMilliseconds -Average).Average,
                        1
                    )
                }
                else {
                    $null
                }
                errors = @(
                    $_.Group |
                        Where-Object { $null -eq $_.delayMilliseconds } |
                        ForEach-Object { $_.message }
                )
            }
        }
)

[ordered]@{
    phase = $Phase
    evidenceFile = $outputFullPath
    http = $httpResults
    nodes = $summary
} | ConvertTo-Json -Depth 7
