<#
.SYNOPSIS
  Calls programs as Windows PowerShell 5.1 needs.

.PARAMETER Dir
  A git checkout.
#>
param([string]$Dir)
$ErrorActionPreference = 'Stop'
# PowerShell 7.3 and later: pass programs' arguments, and leave their exit statuses, as Windows PowerShell does.
$PSNativeCommandArgumentPassing = 'Legacy'
$PSNativeCommandUseErrorActionPreference = $false
# Programs' output is UTF-8, not in the console's code page. The ISE has no console to set.
try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { }

# Runs a program: true when it is there and exits 0. Quiet discards its output and errors, and neither
# its complaints on stderr nor its absence stop the script.
function Test-Succeeds([string]$Program, [object[]]$Arguments = @(), [switch]$Quiet) {
    if (-not (Get-Command $Program -ErrorAction SilentlyContinue)) { return $false }
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    try {
        if ($Quiet) { & $Program @Arguments *> $null } else { & $Program @Arguments | Out-Host }
    } catch { return $false }
    $LASTEXITCODE -eq 0
}

# An argument as a program's C runtime reads it back. Windows PowerShell passes an empty argument as nothing
# and a " as it is, and quotes an argument with a space only where an even number of " come before it.
function ConvertTo-NativeArgument([object[]]$Value) {
    foreach ($v in $Value) {
        $s = [regex]::Replace([string]$v, '(\\*)"', '$1$1\"')
        if ($s -eq '') {
            '""'
        } elseif ($s -notmatch '\s') {
            $s
        } elseif ($s -match '^(?:[^"]*"[^"]*")*[^"]*\s') {
            [regex]::Replace($s, '(\\+)\z', '$1$1')
        } else {
            '"' + [regex]::Replace([regex]::Replace([string]$v, '(\\*)"', '$1$1""'), '(\\+)\z', '$1$1') + '"'
        }
    }
}

$quoted = 'say "hi there"'
$empty = ''
$several = @('a b', '', 'c"d')
$count = 3
git rev-parse --sq-quote '""' 'a\"b' 'C:\dir name\\' 'say \"hi there\"' (ConvertTo-NativeArgument $quoted) (ConvertTo-NativeArgument $empty) (ConvertTo-NativeArgument $several) $count
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
curl.exe --version
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
where.exe git
Write-Host "sort: $(if ([bool](Get-Command 'Sort.exe' -ErrorAction SilentlyContinue)) { 'True' } else { 'False' })"
cmd /c 'echo "as it is"'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$global:LASTEXITCODE = 0
& (Join-Path $PSScriptRoot 'helper.ps1') 'a b' $quoted
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "status: $(if (Test-Succeeds 'git' @('-C', (ConvertTo-NativeArgument $Dir), 'status') -Quiet) { 'True' } else { 'False' })"
$version = (git --version) -join "`n"
Write-Host "full path: $($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath('x.txt'))"
$picked = @(if ([string]::IsNullOrEmpty($empty)) { @('only') } else { @() })
Write-Host "picked: $($picked.Count)"
Write-Host "end: $(if ("a`n" -cmatch 'a\z') { 'True' } else { 'False' }) dot: $(if ("a`nb" -cmatch '(?s)^a.b\z') { 'True' } else { 'False' }) backslash: $(if ('x\y' -cmatch '[\\]') { 'True' } else { 'False' }) bracket: $(if (']' -cmatch '^[\]a]\z') { 'True' } else { 'False' })"
