$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'env.ps1')
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force 'logs/tmp' | Out-Null
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Djava.io.tmpdir=./logs/tmp' '-jar' 'system-direction-classifier-start/target/system-direction-classifier-start.jar' '--spring.profiles.active=dev' '--logging.file.path=./logs'
} finally {
    Pop-Location
}
