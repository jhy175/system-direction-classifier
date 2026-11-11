$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$secretDir = Join-Path $projectRoot '.local/model-secrets'
$keyPath = Join-Path $secretDir 'local-aes-gcm-v1.bin'
New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
$setAccess = Join-Path $PSScriptRoot 'set-local-access.ps1'
$ownerSid = 'S-1-5-21-1111734936-367921918-3107946342-1001'
$runtimeSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function Protect-ModelSecretPath([string]$Path) {
    & $setAccess -Path $Path -Private | Out-Null
    $acl = Get-Acl -LiteralPath $Path
    $allowedSids = @($ownerSid, $runtimeSid)
    $unexpected = $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]) |
        Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -notin $allowedSids }
    if (!$acl.AreAccessRulesProtected -or $unexpected) {
        throw 'The model encryption key path is not private. Run this script with permission to set Windows ACLs.'
    }
}
Protect-ModelSecretPath $secretDir

if (!(Test-Path -LiteralPath $keyPath)) {
    $stream = [IO.File]::Open($keyPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $key = New-Object byte[] 32
    try {
        $random.GetBytes($key)
        $stream.Write($key, 0, $key.Length)
        $stream.Flush($true)
    } finally {
        [Array]::Clear($key, 0, $key.Length)
        $random.Dispose()
        $stream.Dispose()
    }
}

Protect-ModelSecretPath $keyPath
if ((Get-Item -LiteralPath $keyPath).Length -ne 32) {
    throw 'The existing model encryption key has an invalid size. Restore the original key from backup.'
}
Write-Host 'Local model encryption key is ready. Keep its backup separate from MySQL.'
