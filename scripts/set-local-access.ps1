param(
    [Parameter(Mandatory=$true)][string[]]$Path,
    [string]$Account = 'LAPTOP-BDKBMPCD\JHY',
    [switch]$Private
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent)).TrimEnd('\')
$projectPrefix = $projectRoot + '\'
$humanSid = (New-Object System.Security.Principal.NTAccount($Account)).Translate([System.Security.Principal.SecurityIdentifier])
$runtimeSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
foreach ($requestedPath in $Path) {
    $candidatePath = if ([System.IO.Path]::IsPathRooted($requestedPath)) { $requestedPath } else { Join-Path $projectRoot $requestedPath }
    $fullPath = [System.IO.Path]::GetFullPath($candidatePath).TrimEnd('\')
    if (!$fullPath.Equals($projectRoot,[StringComparison]::OrdinalIgnoreCase) -and !$fullPath.StartsWith($projectPrefix,[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Only paths inside this project may be managed.'
    }
    $item = Get-Item -LiteralPath $fullPath -Force
    $checkItem = $item
    while ($checkItem) {
        if ($checkItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'Permission changes through symbolic links or junctions are not supported.' }
        if ($checkItem.FullName.TrimEnd('\').Equals($projectRoot,[StringComparison]::OrdinalIgnoreCase)) { break }
        $checkItem = if ($checkItem -is [System.IO.DirectoryInfo]) { $checkItem.Parent } else { $checkItem.Directory }
    }
    $rights = if ($item.PSIsContainer) { '(OI)(CI)(F)' } else { '(F)' }
    $grants = @("*$($humanSid.Value):$rights")
    if ($Private -and $runtimeSid.Value -ne $humanSid.Value) { $grants += "*$($runtimeSid.Value):$rights" }
    # icacls changes only the DACL; Set-Acl can unnecessarily require SeSecurityPrivilege.
    & icacls.exe $fullPath '/grant:r' @grants | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot grant full control of $fullPath to $Account. Run this helper as an account allowed to modify that file's ACL." }
    if ($Private) {
        # Grant the human and runtime first, then remove broad inherited entries.
        & icacls.exe $fullPath '/inheritance:r' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot protect the ACL of $fullPath." }
    }
    $actualAcl = Get-Acl -LiteralPath $fullPath
    $fullControl = [System.Security.AccessControl.FileSystemRights]::FullControl
    $humanRules = @($actualAcl.Access | Where-Object {
        $_.AccessControlType -eq 'Allow' -and
        $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -eq $humanSid.Value -and
        ($_.FileSystemRights -band $fullControl) -eq $fullControl
    })
    if (!$humanRules.Count) { throw "Full control verification failed for $Account." }
    if ($item.PSIsContainer) {
        $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
        if (!@($humanRules | Where-Object { ($_.InheritanceFlags -band $inheritance) -eq $inheritance }).Count) {
            throw "Inheritable full control verification failed for $Account."
        }
    }
    [pscustomobject]@{ Path=$fullPath; Account=$Account; FullControl=$true; Inheritable=[bool]$item.PSIsContainer; Private=[bool]$Private }
}
