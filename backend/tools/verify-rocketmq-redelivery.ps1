[CmdletBinding()]
param(
    [ValidateRange(30, 600)]
    [int]$TimeoutSeconds = 120,
    [switch]$SkipNetworkPreflight
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $root
$runId = 'rmq-redelivery-' + (Get-Date -Format 'yyyyMMddHHmmss')
$topic = "plainjournal-$runId"
$consumerGroup = "plainjournal-$runId-consumer"
$runDirectory = Join-Path $root ".run\$runId"
$classesDirectory = Join-Path $runDirectory 'classes'
$classpathPath = Join-Path $runDirectory 'classpath.txt'
$probeLogPath = Join-Path $runDirectory 'probe.out.log'
$probeErrorLogPath = Join-Path $runDirectory 'probe.err.log'
$verificationPath = Join-Path $runDirectory 'verification.json'
$sourcePath = Join-Path $PSScriptRoot 'rocketmq-redelivery-probe\RocketMqRedeliveryProbe.java'
$nameServer = 'plainjournal-rocketmq-namesrv:9876'
$cluster = 'EcommerceCluster'
$broker = 'plainjournal-rocketmq-broker'
$topicCreated = $false
$groupCreated = $false
$probeExitCode = $null

function Invoke-MqAdmin {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(docker exec $broker sh mqadmin @Arguments 2>&1 |
                ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $joinedOutput = $output -join "`n"
    $reportedFailure = $joinedOutput -match (
        '(?im)(SubCommandException|MQClientException|CODE:\s*\d+\s+DESC:|command failed)')
    if ($exitCode -ne 0 -or $reportedFailure) {
        throw "mqadmin failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Invoke-MqAdminDiagnostic {
    param([Parameter(Mandatory)][string[]]$Arguments)

    try {
        return [ordered]@{
            available = $true
            output = @(Invoke-MqAdmin -Arguments $Arguments)
            error = $null
        }
    }
    catch {
        return [ordered]@{
            available = $false
            output = @()
            error = $_.Exception.Message
        }
    }
}

function Test-ConsumerGroupPresent {
    $output = Invoke-MqAdmin -Arguments @(
        'getConsumerConfig', '-n', $nameServer, '-g', $consumerGroup)
    return ($output -join "`n") -match (
        '(?m)^\s*groupName\s*=\s*' +
        [regex]::Escape($consumerGroup) +
        '\s*$')
}

function Test-TopicPresent {
    $output = Invoke-MqAdmin -Arguments @('topicList', '-n', $nameServer)
    return @($output | Where-Object { $_.Trim() -eq $topic }).Count -gt 0
}

New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null

try {
    if (-not $SkipNetworkPreflight) {
        & 'D:\DevTools\Network\check-dev-network.ps1' | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw 'Network preflight failed.'
        }
    }

    $topicOutput = Invoke-MqAdmin -Arguments @(
        'updateTopic',
        '-n', $nameServer,
        '-c', $cluster,
        '-t', $topic,
        '-r', '1',
        '-w', '1')
    if (($topicOutput -join "`n") -notmatch 'success') {
        throw "Topic creation did not report success: $($topicOutput -join "`n")"
    }
    $topicCreated = $true

    $groupOutput = Invoke-MqAdmin -Arguments @(
        'updateSubGroup',
        '-n', $nameServer,
        '-c', $cluster,
        '-g', $consumerGroup,
        '-m', 'false',
        '-d', 'false',
        '-q', '1',
        '-r', '16',
        '-s', 'true',
        '-a', 'true')
    if (($groupOutput -join "`n") -notmatch 'success') {
        throw "Consumer group creation did not report success: $($groupOutput -join "`n")"
    }
    $groupCreated = $true

    Push-Location $root
    try {
        & mvn -q -pl services/chat-service dependency:build-classpath `
            "-Dmdep.outputFile=$classpathPath"
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to build the RocketMQ probe classpath.'
        }
    }
    finally {
        Pop-Location
    }

    $classpath = Get-Content -LiteralPath $classpathPath -Raw
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        throw 'JAVA_HOME is required for the RocketMQ redelivery probe.'
    }
    $javac = Join-Path $javaHome 'bin\javac.exe'
    $java = Join-Path $javaHome 'bin\java.exe'
    & $javac -cp $classpath -d $classesDirectory $sourcePath
    if ($LASTEXITCODE -ne 0) {
        throw 'RocketMQ redelivery probe compilation failed.'
    }

    $runtimeClasspath = "$classesDirectory;$classpath"
    $processStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processStartInfo.FileName = $java
    $processStartInfo.UseShellExecute = $false
    $processStartInfo.CreateNoWindow = $true
    $processStartInfo.RedirectStandardOutput = $true
    $processStartInfo.RedirectStandardError = $true
    foreach ($argument in @(
            '-cp',
            $runtimeClasspath,
            'RocketMqRedeliveryProbe',
            '127.0.0.1:18082',
            $topic,
            $consumerGroup,
            $TimeoutSeconds.ToString())) {
        $processStartInfo.ArgumentList.Add($argument)
    }
    $probeProcess = [System.Diagnostics.Process]::new()
    $probeProcess.StartInfo = $processStartInfo
    if (-not $probeProcess.Start()) {
        throw 'RocketMQ redelivery probe process did not start.'
    }
    $standardOutputTask = $probeProcess.StandardOutput.ReadToEndAsync()
    $standardErrorTask = $probeProcess.StandardError.ReadToEndAsync()
    $hardTimeoutMilliseconds = ($TimeoutSeconds + 60) * 1000
    if (-not $probeProcess.WaitForExit($hardTimeoutMilliseconds)) {
        $probeProcess.Kill($true)
        $probeProcess.WaitForExit()
        $probeExitCode = 124
        $probeTimedOut = $true
    }
    else {
        $probeExitCode = $probeProcess.ExitCode
        $probeTimedOut = $false
    }
    $probeOutputText = $standardOutputTask.GetAwaiter().GetResult()
    $probeErrorText = $standardErrorTask.GetAwaiter().GetResult()
    $probeOutputText | Set-Content -LiteralPath $probeLogPath -Encoding utf8NoBOM
    $probeErrorText | Set-Content -LiteralPath $probeErrorLogPath -Encoding utf8NoBOM
    if (-not [string]::IsNullOrWhiteSpace($probeOutputText)) {
        Write-Output $probeOutputText.TrimEnd()
    }
    if (-not [string]::IsNullOrWhiteSpace($probeErrorText)) {
        Write-Warning $probeErrorText.TrimEnd()
    }

    $progress = Invoke-MqAdminDiagnostic -Arguments @(
        'consumerProgress',
        '-n', $nameServer,
        '-g', $consumerGroup)
    $probeOutput = Get-Content -LiteralPath $probeLogPath -Raw
    $redelivered = $probeOutput -match 'PROBE_RESULT\|redelivered=true'
    $evidence = [ordered]@{
        runId = $runId
        completedDate = '2026-07-25'
        broker = 'shared-core'
        endpoints = '127.0.0.1:18082'
        topic = $topic
        consumerGroup = $consumerGroup
        timeoutSeconds = $TimeoutSeconds
        probeExitCode = $probeExitCode
        probeTimedOut = $probeTimedOut
        redelivered = $redelivered
        probeOutput = @($probeOutput -split "`r?`n" | Where-Object { $_ })
        probeError = @($probeErrorText -split "`r?`n" | Where-Object { $_ })
        consumerProgress = $progress
    }
    $evidence | ConvertTo-Json -Depth 6 | Set-Content `
        -LiteralPath $verificationPath -Encoding utf8NoBOM

    if (-not $redelivered -or $probeExitCode -ne 0) {
        throw "Shared RocketMQ did not redeliver within $TimeoutSeconds seconds. Evidence: $verificationPath"
    }
}
finally {
    if ($groupCreated) {
        try {
            Invoke-MqAdmin -Arguments @(
                'deleteSubGroup',
                '-n', $nameServer,
                '-c', $cluster,
                '-g', $consumerGroup,
                '-r', 'true') | Out-Null
        }
        catch {
            Write-Warning "Consumer group cleanup failed: $($_.Exception.Message)"
        }
    }
    if ($topicCreated) {
        try {
            Invoke-MqAdmin -Arguments @(
                'deleteTopic',
                '-n', $nameServer,
                '-c', $cluster,
                '-t', $topic) | Out-Null
        }
        catch {
            Write-Warning "Topic cleanup failed: $($_.Exception.Message)"
        }
    }
    if ($groupCreated -and (Test-ConsumerGroupPresent)) {
        Write-Warning "Consumer group remains after cleanup: $consumerGroup"
    }
    if ($topicCreated -and (Test-TopicPresent)) {
        Write-Warning "Topic remains after cleanup: $topic"
    }
}
