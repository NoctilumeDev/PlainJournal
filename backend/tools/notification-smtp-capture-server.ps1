param(
    [Parameter(Mandatory)][ValidateRange(1024, 65535)][int]$Port,
    [Parameter(Mandatory)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$directory = Split-Path -Parent $OutputPath
if ($directory) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$listener.Start()
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new(
                $stream,
                [Text.Encoding]::ASCII,
                $false,
                4096,
                $true)
            $writer = [IO.StreamWriter]::new(
                $stream,
                [Text.Encoding]::ASCII,
                4096,
                $true)
            $writer.NewLine = "`r`n"
            $writer.AutoFlush = $true
            $writer.WriteLine('220 plainjournal.local ESMTP ready')
            $collectingData = $false
            $message = [Text.StringBuilder]::new()
            while ($client.Connected) {
                $line = $reader.ReadLine()
                if ($null -eq $line) {
                    break
                }
                if ($collectingData) {
                    if ($line -eq '.') {
                        [IO.File]::AppendAllText(
                            $OutputPath,
                            "----- MESSAGE -----`r`n$($message.ToString())----- END -----`r`n",
                            [Text.UTF8Encoding]::new($false))
                        $writer.WriteLine('250 2.0.0 queued')
                        $collectingData = $false
                        [void]$message.Clear()
                    }
                    else {
                        $contentLine = if ($line.StartsWith('..')) {
                            $line.Substring(1)
                        }
                        else {
                            $line
                        }
                        [void]$message.Append($contentLine).Append("`r`n")
                    }
                    continue
                }
                $command = $line.ToUpperInvariant()
                if ($command.StartsWith('EHLO') -or $command.StartsWith('HELO')) {
                    $writer.WriteLine('250 plainjournal.local')
                }
                elseif ($command.StartsWith('MAIL FROM:') -or $command.StartsWith('RCPT TO:')) {
                    $writer.WriteLine('250 2.1.0 accepted')
                }
                elseif ($command -eq 'DATA') {
                    $writer.WriteLine('354 End data with <CR><LF>.<CR><LF>')
                    $collectingData = $true
                }
                elseif ($command -eq 'RSET' -or $command -eq 'NOOP') {
                    $writer.WriteLine('250 2.0.0 ok')
                }
                elseif ($command -eq 'QUIT') {
                    $writer.WriteLine('221 2.0.0 bye')
                    break
                }
                else {
                    $writer.WriteLine('502 5.5.2 command not implemented')
                }
            }
        }
        finally {
            $client.Dispose()
        }
    }
}
finally {
    $listener.Stop()
}
