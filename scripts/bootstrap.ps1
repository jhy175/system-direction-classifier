[CmdletBinding()]
param([switch]$SkipEden)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsDir = Join-Path $projectRoot '.tools'
$jdkName = 'zulu8.98.0.111-ca-jdk8.0.512-win_x64'
$jdkHash = '68549CC971A00E4407DE1CEF569C0851C8DEE00C71EAB461FE9CA62684BB161B'
$edenCommit = '735d40cbe99424ceca9ec541a52144787dd9f314'
New-Item -ItemType Directory -Force $toolsDir | Out-Null
if (!(Test-Path (Join-Path $toolsDir "$jdkName/bin/javac.exe"))) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $zipPath = Join-Path $toolsDir 'jdk8-x64.zip'
    if (!(Test-Path $zipPath)) {
        Invoke-WebRequest "https://cdn.azul.com/zulu/bin/$jdkName.zip" -OutFile $zipPath -UseBasicParsing
    }
    if ((Get-FileHash $zipPath -Algorithm SHA256).Hash -ne $jdkHash) {
        throw "JDK checksum mismatch. Remove $zipPath and retry."
    }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $toolsDir -Force
}
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location $projectRoot
try {
    & $global:EdenMavenWrapper -version
    if ($LASTEXITCODE -ne 0) { throw 'Maven Wrapper initialization failed.' }
    if (!$SkipEden) {
        Get-Command git -ErrorAction Stop | Out-Null
        $edenDir = Join-Path $toolsDir 'eden-architect'
        if (!(Test-Path $edenDir)) {
            & git -c core.longpaths=true clone --no-checkout https://github.com/shiyindaxiaojie/eden-architect.git $edenDir
            if ($LASTEXITCODE -ne 0) { throw 'Eden clone failed.' }
            & git -C $edenDir -c core.longpaths=true checkout $edenCommit
            if ($LASTEXITCODE -ne 0) { throw 'Eden checkout failed.' }
        }
        $actualCommit = & git -C $edenDir rev-parse HEAD
        if ($actualCommit -ne $edenCommit) { throw "Expected Eden commit $edenCommit, got $actualCommit" }
        # Read only the demo root and its direct module POMs, excluding downloaded tools.
        $pomFiles = @(Get-Item (Join-Path $projectRoot 'pom.xml'))
        $pomFiles += Get-ChildItem $projectRoot -Directory -Filter 'eden-demo-layer-*' |
            ForEach-Object { Get-Item (Join-Path $_.FullName 'pom.xml') }
        $artifacts = foreach ($pomFile in $pomFiles) {
            [xml]$pom = Get-Content -LiteralPath $pomFile.FullName -Raw
            foreach ($dependency in $pom.project.dependencies.dependency) {
                if ($dependency.groupId -eq 'io.github.shiyindaxiaojie') { ':' + $dependency.artifactId }
            }
        }
        $projects = ($artifacts | Sort-Object -Unique) -join ','
        & $global:EdenMavenWrapper -s $global:EdenMavenSettings -B -ntp -f (Join-Path $edenDir 'pom.xml') -pl $projects -am install '-DskipTests' '-Dmaven.javadoc.skip=true'
        if ($LASTEXITCODE -ne 0) { throw 'Eden dependency installation failed. See Maven error above.' }
    }
    Write-Host 'Environment ready. Run: . ./scripts/env.ps1; mvn -B -ntp clean compile'
} finally {
    Pop-Location
}
