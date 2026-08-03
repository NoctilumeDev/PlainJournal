#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(1024, 65535)][int]$ListenPort,
    [Parameter(Mandatory)][ValidateScript({ Test-Path -LiteralPath $_ -PathType Container })]
    [string]$StaticRoot,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$ApiUpstream,
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
$apiBaseUrl = $ApiUpstream.TrimEnd('/')
$root = [IO.Path]::GetFullPath($StaticRoot)
$rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$contentTypes = @{
    '.css' = 'text/css; charset=utf-8'
    '.html' = 'text/html; charset=utf-8'
    '.ico' = 'image/x-icon'
    '.jpeg' = 'image/jpeg'
    '.jpg' = 'image/jpeg'
    '.js' = 'text/javascript; charset=utf-8'
    '.json' = 'application/json; charset=utf-8'
    '.png' = 'image/png'
    '.svg' = 'image/svg+xml'
    '.webp' = 'image/webp'
}

function Copy-RequestHeaders {
    param(
        [Parameter(Mandatory)][System.Net.HttpListenerRequest]$Source,
        [Parameter(Mandatory)][System.Net.Http.HttpRequestMessage]$Target,
        [Parameter()][System.Net.Http.HttpContent]$Content
    )

    foreach ($name in @(
            'Accept',
            'Authorization',
            'Content-Type',
            'Idempotency-Key',
            'X-Request-Id')) {
        $values = @($Source.Headers.GetValues($name))
        if ($values.Count -eq 0) {
            continue
        }
        if (-not $Target.Headers.TryAddWithoutValidation($name, $values) -and $null -ne $Content) {
            [void]$Content.Headers.TryAddWithoutValidation($name, $values)
        }
    }
}

function Send-Bytes {
    param(
        [Parameter(Mandatory)][System.Net.HttpListenerResponse]$Response,
        [Parameter(Mandatory)][byte[]]$Bytes,
        [Parameter(Mandatory)][string]$ContentType,
        [int]$StatusCode = 200
    )

    $Response.StatusCode = $StatusCode
    $Response.ContentType = $ContentType
    $Response.ContentLength64 = $Bytes.Length
    if ($Bytes.Length -gt 0) {
        $Response.OutputStream.Write($Bytes, 0, $Bytes.Length)
    }
    $Response.Close()
}

function Forward-ApiRequest {
    param([Parameter(Mandatory)][System.Net.HttpListenerContext]$Context)

    [byte[]]$requestBytes = [Array]::Empty[byte]()
    if ($Context.Request.HasEntityBody) {
        $memory = [IO.MemoryStream]::new()
        try {
            $Context.Request.InputStream.CopyTo($memory)
            $requestBytes = $memory.ToArray()
        }
        finally {
            $memory.Dispose()
        }
    }
    $message = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Context.Request.HttpMethod),
        $apiBaseUrl + $Context.Request.RawUrl)
    $upstreamResponse = $null
    try {
        if ($requestBytes.Length -gt 0) {
            $message.Content = [System.Net.Http.ByteArrayContent]::new($requestBytes)
        }
        Copy-RequestHeaders -Source $Context.Request -Target $message -Content $message.Content
        $upstreamResponse = $client.SendAsync(
            $message,
            [System.Net.Http.HttpCompletionOption]::ResponseContentRead).GetAwaiter().GetResult()
        $responseBytes = $upstreamResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $contentType = if ($null -ne $upstreamResponse.Content.Headers.ContentType) {
            $upstreamResponse.Content.Headers.ContentType.ToString()
        }
        else {
            'application/octet-stream'
        }
        Send-Bytes -Response $Context.Response `
            -Bytes $responseBytes `
            -ContentType $contentType `
            -StatusCode ([int]$upstreamResponse.StatusCode)
    }
    finally {
        if ($null -ne $upstreamResponse) {
            $upstreamResponse.Dispose()
        }
        $message.Dispose()
    }
}

function Send-StaticFile {
    param([Parameter(Mandatory)][System.Net.HttpListenerContext]$Context)

    $relativePath = [Uri]::UnescapeDataString($Context.Request.Url.AbsolutePath).TrimStart('/')
    if (-not $relativePath) {
        $relativePath = 'index.html'
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $root $relativePath))
    if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Send-Bytes -Response $Context.Response `
            -Bytes ([Text.Encoding]::UTF8.GetBytes('forbidden')) `
            -ContentType 'text/plain; charset=utf-8' `
            -StatusCode 403
        return
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        $candidate = Join-Path $root 'index.html'
    }
    $extension = [IO.Path]::GetExtension($candidate).ToLowerInvariant()
    $contentType = if ($contentTypes.ContainsKey($extension)) {
        $contentTypes[$extension]
    }
    else {
        'application/octet-stream'
    }
    Send-Bytes -Response $Context.Response `
        -Bytes ([IO.File]::ReadAllBytes($candidate)) `
        -ContentType $contentType
}

try {
    $listener.Start()
    [DateTimeOffset]::UtcNow.ToString('o') |
        Set-Content -LiteralPath $ReadyFile -Encoding ascii
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            if ($context.Request.Url.AbsolutePath.StartsWith(
                    '/api/',
                    [StringComparison]::OrdinalIgnoreCase)) {
                Forward-ApiRequest -Context $context
            }
            else {
                Send-StaticFile -Context $context
            }
        }
        catch {
            [Console]::Error.WriteLine(
                "Storefront verification server request failed: $($_.Exception)")
            if ($context.Response.OutputStream.CanWrite) {
                Send-Bytes -Response $context.Response `
                    -Bytes ([Text.Encoding]::UTF8.GetBytes('verification server unavailable')) `
                    -ContentType 'text/plain; charset=utf-8' `
                    -StatusCode 502
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
