param(
    [string]$GodotExe,
    [ValidateRange(0, 65535)][int]$Port = 0,
    [ValidateRange(1, 2)][int]$TeamSize = 1,
    [switch]$Smoke,
    [switch]$Watch
)

$ErrorActionPreference = 'Stop'
if (-not $GodotExe) {
    $suffix = if ($Smoke) { '_console.exe' } else { '.exe' }
    $GodotExe = "E:/Godot/Godot_v4.8-beta4_win64.exe/Godot_v4.8-beta4_win64$suffix"
}
$GodotExe = (Get-Item -LiteralPath $GodotExe).FullName
$java = (Get-Command java.exe).Source
$project = Join-Path $PSScriptRoot 'client'
$serverFolder = Join-Path $PSScriptRoot 'backend'
$toolCache = Join-Path ([IO.Path]::GetTempPath()) 'quidditch-sbt'
New-Item -ItemType Directory -Path $toolCache -Force | Out-Null
$launcher = Join-Path $toolCache 'sbt-launch-1.12.2.jar'
if (-not (Test-Path -LiteralPath $launcher)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/1.12.2/sbt-launch-1.12.2.jar' -OutFile $launcher
}
$repositories = Join-Path $toolCache 'repositories'
Set-Content -LiteralPath $repositories -Value @('[repositories]', 'local', 'maven-central: https://repo.maven.apache.org/maven2')

Push-Location -LiteralPath $serverFolder
try {
    $build = & $java '-Dsbt.log.noformat=true' '-Dsbt.override.build.repos=true' "-Dsbt.repository.config=$repositories" -jar $launcher compile 'export Runtime / fullClasspath'
    if ($LASTEXITCODE -ne 0) { throw ($build -join [Environment]::NewLine) }
    $classPath = $build | Where-Object { $_ -match '^[A-Z]:.*target.*classes;' } | Select-Object -Last 1
    if (-not $classPath) { throw 'sbt did not return the runtime classpath' }
} finally {
    Pop-Location
}

if ($Port -eq 0) {
    $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $socket.Start()
    $Port = $socket.LocalEndpoint.Port
    $socket.Stop()
}
$origin = "http://127.0.0.1:$Port"
$runId = [Guid]::NewGuid().ToString('N')
$backendLog = Join-Path $toolCache "backend-$runId.log"
$backendErrors = Join-Path $toolCache "backend-$runId.err"
$mode = if ($Watch) { 'watch' } elseif ($Smoke) { 'human' } else { 'cpu' }
$backendArgs = @('-cp', ('"' + $classPath + '"'), 'services.system.Main', $Port, $mode)
$seedArgument = if ($Smoke) { '42' } else { 'random' }
$backendArgs += @($seedArgument, $TeamSize)
$launchOptions = @{
    FilePath = $java
    ArgumentList = $backendArgs
    WorkingDirectory = $serverFolder
    WindowStyle = 'Hidden'
    RedirectStandardOutput = $backendLog
    RedirectStandardError = $backendErrors
    PassThru = $true
}
$previousDatabase = $env:QUIDDITCH_DB_PATH
try {
    if ($Smoke) { $env:QUIDDITCH_DB_PATH = Join-Path $serverFolder "target/smoke-$runId.sqlite3" }
    $backendProcess = Start-Process @launchOptions
} finally {
    $env:QUIDDITCH_DB_PATH = $previousDatabase
}
$previousOrigin = $env:QUIDDITCH_BACKEND_URL
$env:QUIDDITCH_BACKEND_URL = $origin
try {
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(12)
    while ([DateTime]::UtcNow -lt $deadline -and -not $backendProcess.HasExited) {
        try {
            $ready = (Invoke-RestMethod "$origin/health" -TimeoutSec 1).status -eq 'ok'
            if ($ready) { break }
        } catch {
            Start-Sleep -Milliseconds 150
        }
    }
    if (-not $ready) { throw "Scala backend did not start. Check $backendErrors" }
    Write-Host "Quidditch backend: $origin"

    if ($Smoke) {
        & $GodotExe --headless --path $project --editor --import --quit
        if ($LASTEXITCODE -ne 0) { throw 'Godot could not import the project' }
        & $GodotExe --headless --path $project --script res://tests/match_smoke.gd
        if ($LASTEXITCODE -ne 0) { throw 'Godot integration check failed' }
    } else {
        $imports = Join-Path $project '.godot/imported'
        if (-not (Get-ChildItem -LiteralPath $imports -Filter 'rider.glb-*.scn' -ErrorAction SilentlyContinue)) {
            $import = Start-Process -FilePath $GodotExe -ArgumentList @('--headless', '--path', ('"' + $project + '"'), '--editor', '--import', '--quit') -WindowStyle Hidden -PassThru -Wait
            if ($import.ExitCode -ne 0) { throw 'Godot could not import the project' }
        }
        $game = Start-Process -FilePath $GodotExe -ArgumentList @('--path', ('"' + $project + '"')) -WindowStyle Normal -PassThru
        Write-Host "Godot process: $($game.Id). Press Enter in the game to begin."
        $game.WaitForExit()
        if ($game.ExitCode -ne 0) { throw "Godot exited with code $($game.ExitCode)" }
    }
} finally {
    $env:QUIDDITCH_BACKEND_URL = $previousOrigin
    if (-not $backendProcess.HasExited) {
        Stop-Process -InputObject $backendProcess
    }
}
