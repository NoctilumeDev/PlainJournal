#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(1024, 65535)][int]$ListenPort,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$UpstreamBaseUrl,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ArmFile,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$EvidenceFile,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ReadyFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$ListenPort/")
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)
$upstream = $UpstreamBaseUrl.TrimEnd('/')
$hopByHopHeaders = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::OrdinalIgnoreCase)
foreach ($header in @(
        'Connection',
        'Keep-Alive',
        'Proxy-Authenticate',
        'Proxy-Authorization',
        'TE',
        'Trailer',
        'Transfer-Encoding',
        'Upgrade',
        'Host',
        'Content-Length')) {
    [void]$hopByHopHeaders.Add($header)
}

function Copy-RequestHeaders {
    param(
        [Parameter(Mandatory)][System.Net.HttpListenerRequest]$Source,
        [Parameter(Mandatory)][System.Net.Http.HttpRequestMessage]$Target,
        [Parameter()][System.Net.Http.HttpContent]$Content
    )

    foreach ($name in $Source.Headers.AllKeys) {
        if ($hopByHopHeaders.Contains($name)) {
            continue
        }
        $values = @($Source.Headers.GetValues($name))
        if (-not $Target.Headers.TryAddWithoutValidation($name, $values) -and $null -ne $Content) {
            [void]$Content.Headers.TryAddWithoutValidation($name, $values)
        }
    }
}

function Copy-ResponseHeaders {
    param(
        [Parameter(Mandatory)][System.Net.Http.HttpResponseMessage]$Source,
        [Parameter(Mandatory)][System.Net.HttpListenerResponse]$Target
    )

    if ($null -ne $Source.Content.Headers.ContentType) {
        $Target.ContentType = $Source.Content.Headers.ContentType.ToString()
    }
    if ($null -ne $Source.Headers.Location) {
        $Target.RedirectLocation = $Source.Headers.Location.ToString()
    }
}

function Write-ProxyEvidence {
    param(
        [Parameter(Mandatory)][System.Net.HttpListenerRequest]$Request,
        [Parameter(Mandatory)][System.Net.Http.HttpResponseMessage]$UpstreamResponse,
        [Parameter(Mandatory)][byte[]]$ResponseBody
    )

    $match = [regex]::Match(
        $Request.Url.AbsolutePath,
        '^/api/v1/fulfillment/orders/(?<orderNo>[^/]+)/confirm-receipt$')
    $hash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($ResponseBody)).ToLowerInvariant()
    $evidence = [ordered]@{
        schemaVersion = 1
        droppedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        method = $Request.HttpMethod
        path = $Request.RawUrl
        orderNo = [Uri]::UnescapeDataString($match.Groups['orderNo'].Value)
        upstreamStatus = [int]$UpstreamResponse.StatusCode
        upstreamResponseBytes = $ResponseBody.Length
        upstreamResponseSha256 = $hash
    }
    $temporaryPath = "$EvidenceFile.tmp"
    $evidence | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $temporaryPath -Encoding utf8
    Move-Item -LiteralPath $temporaryPath -Destination $EvidenceFile -Force
}

try {
    $listener.Start()
    [DateTimeOffset]::UtcNow.ToString('o') |
        Set-Content -LiteralPath $ReadyFile -Encoding ascii

    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $requestMessage = $null
        $upstreamResponse = $null
        try {
            [byte[]]$requestBytes = [Array]::Empty[byte]()
            if ($context.Request.HasEntityBody) {
                $memory = [IO.MemoryStream]::new()
                try {
                    $context.Request.InputStream.CopyTo($memory)
                    $requestBytes = $memory.ToArray()
                }
                finally {
                    $memory.Dispose()
                }
            }
            $requestMessage = [System.Net.Http.HttpRequestMessage]::new(
                [System.Net.Http.HttpMethod]::new($context.Request.HttpMethod),
                $upstream + $context.Request.RawUrl)
            if ($requestBytes.Length -gt 0) {
                $requestMessage.Content = [System.Net.Http.ByteArrayContent]::new($requestBytes)
            }
            Copy-RequestHeaders -Source $context.Request -Target $requestMessage `
                -Content $requestMessage.Content

            $upstreamResponse = $client.SendAsync(
                $requestMessage,
                [System.Net.Http.HttpCompletionOption]::ResponseContentRead).GetAwaiter().GetResult()
            $responseBytes = $upstreamResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            $dropResponse = (Test-Path -LiteralPath $ArmFile) -and
                $context.Request.HttpMethod -eq 'POST' -and
                $context.Request.Url.AbsolutePath -match
                    '^/api/v1/fulfillment/orders/[^/]+/confirm-receipt$' -and
                [int]$upstreamResponse.StatusCode -eq 200

            if ($dropResponse) {
                Remove-Item -LiteralPath $ArmFile -Force
                Write-ProxyEvidence -Request $context.Request `
                    -UpstreamResponse $upstreamResponse `
                    -ResponseBody $responseBytes
                $context.Response.Abort()
                continue
            }

            $context.Response.StatusCode = [int]$upstreamResponse.StatusCode
            Copy-ResponseHeaders -Source $upstreamResponse -Target $context.Response
            $context.Response.ContentLength64 = $responseBytes.Length
            if ($responseBytes.Length -gt 0) {
                $context.Response.OutputStream.Write($responseBytes, 0, $responseBytes.Length)
            }
            $context.Response.Close()
        }
        catch {
            [Console]::Error.WriteLine(
                "Fulfillment confirmation response-loss proxy request failed: $($_.Exception)")
            if ($context.Response.OutputStream.CanWrite) {
                $message = [Text.Encoding]::UTF8.GetBytes(
                    'fulfillment confirmation fault proxy unavailable')
                $context.Response.StatusCode = 502
                $context.Response.ContentType = 'text/plain'
                $context.Response.ContentLength64 = $message.Length
                $context.Response.OutputStream.Write($message, 0, $message.Length)
                $context.Response.Close()
            }
        }
        finally {
            if ($null -ne $upstreamResponse) {
                $upstreamResponse.Dispose()
            }
            if ($null -ne $requestMessage) {
                $requestMessage.Dispose()
            }
        }
    }
}
finally {
    Remove-Item -LiteralPath $ReadyFile -Force -ErrorAction SilentlyContinue
    $listener.Close()
    $client.Dispose()
    $handler.Dispose()
}
