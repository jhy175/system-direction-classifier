# Dot-source from PowerShell: . ./scripts/env.ps1
$projectRoot = Split-Path $PSScriptRoot -Parent
$localJdk = Join-Path $projectRoot '.tools/zulu8.98.0.111-ca-jdk8.0.512-win_x64'
if (Test-Path (Join-Path $localJdk 'bin/javac.exe')) {
    $env:JAVA_HOME = $localJdk
}
if (!$env:JAVA_HOME -or !(Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
    throw 'A full JDK 8 is required. Run scripts/bootstrap.ps1 first, or set JAVA_HOME.'
}
$javaVersion = (& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'javac 1\.8\.') { throw "Expected JDK 8, got: $javaVersion" }
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$global:EdenMavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$global:EdenMavenSettings = Join-Path $projectRoot '.mvn/settings.xml'
if (Test-Path (Join-Path $projectRoot '.mvn/settings.local.xml')) {
    $global:EdenMavenSettings = Join-Path $projectRoot '.mvn/settings.local.xml'
}
function global:mvn { & $global:EdenMavenWrapper -s $global:EdenMavenSettings @args }
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host 'Maven command enabled in this PowerShell session (project Maven Wrapper 3.6.3).'
