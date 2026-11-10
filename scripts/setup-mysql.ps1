param([int]$Port = 3307, [string]$MysqlHome = 'C:\Program Files\MySQL\MySQL Server 8.4', [string]$CredentialOwner = 'LAPTOP-BDKBMPCD\JHY')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'env.ps1')
$privateDir = Join-Path $projectRoot '.local'
$mysqlDir = Join-Path $projectRoot '.tools/mysql'
$dataDir = Join-Path $mysqlDir 'data'
$secretsPath = Join-Path $privateDir 'user-module.properties'
New-Item -ItemType Directory -Force $privateDir,$mysqlDir | Out-Null
& (Join-Path $PSScriptRoot 'set-local-access.ps1') -Path @($projectRoot,$privateDir) -Account $CredentialOwner | Out-Null
function New-LocalSecret {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant() }
    finally { $rng.Dispose() }
}
if (!(Test-Path -LiteralPath $secretsPath)) {
    @("mysqlPort=$Port", "rootPassword=$(New-LocalSecret)", "dbPassword=$(New-LocalSecret)",
      'adminLogin=admin', "adminPassword=$(New-LocalSecret)") | Set-Content -LiteralPath $secretsPath -Encoding UTF8
}
# Apply to both new and existing files, preserving the explicitly authorized human owner's access.
& (Join-Path $PSScriptRoot 'set-local-access.ps1') -Path $secretsPath -Account $CredentialOwner -Private | Out-Null
$settings = @{}
Get-Content -LiteralPath $secretsPath | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $settings[$matches[1]]=$matches[2] } }
$Port = [int]$settings.mysqlPort
$server = Join-Path $MysqlHome 'bin/mysqld.exe'
if (!(Test-Path -LiteralPath $server)) { throw "MySQL server executable not found: $server" }
$fresh = !(Test-Path -LiteralPath (Join-Path $dataDir 'mysql'))
if ($fresh) {
    New-Item -ItemType Directory -Force $dataDir | Out-Null
    & $server '--no-defaults' '--initialize-insecure' "--basedir=$MysqlHome" "--datadir=$dataDir" "--log-error=$(Join-Path $mysqlDir 'initialize.log')"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed; inspect .tools/mysql/initialize.log.' }
}
$client = New-Object System.Net.Sockets.TcpClient
try { $client.Connect('127.0.0.1',$Port); $listening=$true } catch { $listening=$false } finally { $client.Dispose() }
if (!$listening) {
    # MySQL resolves relative datadir against basedir. Keep both relative to avoid Unicode argv truncation.
    $env:ICU_DATA = Join-Path $MysqlHome 'lib/private'
    $arguments = @('--no-defaults', '--basedir=.', '--datadir=.tools/mysql/data', "--plugin-dir=`"$(Join-Path $MysqlHome 'lib/plugin')`"", "--lc-messages-dir=`"$(Join-Path $MysqlHome 'share')`"", '--bind-address=127.0.0.1',
        "--port=$Port", '--mysqlx=0', '--skip-log-bin', '--default-time-zone=+00:00',
        '--log-error=../server.log', '--pid-file=../server.pid'
    )
    $process = Start-Process -FilePath $server -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -LiteralPath (Join-Path $privateDir 'mysql-process.pid')
    $ready=$false
    for ($i=0; $i -lt 40; $i++) {
        $tcp=New-Object System.Net.Sockets.TcpClient
        try { $tcp.Connect('127.0.0.1',$Port); $ready=$true; break } catch { Start-Sleep -Milliseconds 500 } finally { $tcp.Dispose() }
    }
    if (!$ready) { throw 'MySQL did not start; inspect .tools/mysql/server.log.' }
}
$driver = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/repository/com/mysql/mysql-connector-j') -Recurse -Filter 'mysql-connector-j-*.jar' | Sort-Object FullName | Select-Object -Last 1
if (!$driver) { throw 'MySQL JDBC driver is not cached. Build the project first.' }
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -encoding UTF-8 -d $privateDir (Join-Path $PSScriptRoot 'MysqlProvision.java')
if ($LASTEXITCODE -ne 0) { throw 'Cannot compile local MySQL provisioning helper.' }
$provisionArgs = @('-cp', "$privateDir;$($driver.FullName)", 'MysqlProvision', $secretsPath)
if (!(Test-Path -LiteralPath (Join-Path $privateDir 'mysql-provisioned'))) { $provisionArgs += 'fresh' }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') @provisionArgs
if ($LASTEXITCODE -ne 0) { throw 'MySQL provisioning failed. Existing database credentials were not changed.' }
Set-Content -LiteralPath (Join-Path $privateDir 'mysql-provisioned') -Value 'ready'
Write-Host 'Credentials are stored only in .local/user-module.properties (git ignored).'
