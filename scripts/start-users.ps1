param([switch]$TestDatabase, [int]$Port = 8082, [int]$ManagementPort = 9082)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'env.ps1')
$privateSettings = Join-Path $projectRoot '.local/user-module.properties'
if (!(Test-Path -LiteralPath $privateSettings)) { throw 'Run scripts/setup-mysql.ps1 first.' }
$settings=@{}
Get-Content -LiteralPath $privateSettings | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
$database = if ($TestDatabase) { 'direction_classifier_test' } else { 'direction_classifier' }
$env:CLASSIFIER_DB_URL="jdbc:mysql://127.0.0.1:$($settings.mysqlPort)/${database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:CLASSIFIER_DB_USER='classifier_app'
$env:CLASSIFIER_DB_PASSWORD=$settings.dbPassword
$env:CLASSIFIER_ADMIN_LOGIN=$settings.adminLogin
$env:CLASSIFIER_ADMIN_PASSWORD=$settings.adminPassword
$env:SERVER_SERVLET_SESSION_COOKIE_NAME="CLASSIFIER_SESSION_$Port"
$modelAddresses = Join-Path $projectRoot '.local/model-secrets/approved-base-urls.txt'
if (!$env:CLASSIFIER_MODEL_PRIVATE_BASE_URLS -and (Test-Path -LiteralPath $modelAddresses -PathType Leaf)) {
    $env:CLASSIFIER_MODEL_PRIVATE_BASE_URLS = ((Get-Content -Encoding UTF8 -LiteralPath $modelAddresses |
        ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ',')
}
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force 'logs/tmp' | Out-Null
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Duser.timezone=UTC' '-Djava.io.tmpdir=./logs/tmp' '-jar' 'system-direction-classifier-start/target/system-direction-classifier-start.jar' '--spring.profiles.active=dev,mysql' "--server.port=$Port" "--management.server.port=$ManagementPort" '--logging.file.path=./logs'
} finally { Pop-Location }
