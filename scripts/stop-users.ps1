param([int]$Port = 8082)
$ErrorActionPreference='Stop'
if ($Port -notin @(8082,8083)) { throw 'Only project application ports 8082 and 8083 are managed.' }
$projectRoot=Split-Path $PSScriptRoot -Parent
$expectedJava=Join-Path $projectRoot '.tools/zulu8.98.0.111-ca-jdk8.0.512-win_x64/bin/java.exe'
$lines=netstat -ano | Select-String ":$Port\s+.*LISTENING\s+(\d+)"
$processIds=@($lines | ForEach-Object { if ($_.Line -match 'LISTENING\s+(\d+)') {[int]$matches[1]} } | Select-Object -Unique)
foreach ($processId in $processIds) {
    $process=Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and $process.Path -ne $expectedJava) { throw "Port $Port belongs to a different runtime; it was not stopped." }
    if ($process) { Stop-Process -Id $processId; Write-Host "Stopped project application on port $Port." }
}
