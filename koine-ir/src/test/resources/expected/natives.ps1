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
        if ($Quiet) {
            Invoke-Program $Program $Arguments *> $null
        } else {
            Invoke-Program $Program $Arguments | Out-Host
        }
    } catch { return $false }
    $LASTEXITCODE -eq 0
}

# Runs a batch file through cmd /c call, since cmd /c alone can report success from one that ran exit /b 1,
# and any other program with its arguments as its C runtime reads them. A program named by a value runs here.
function Invoke-Program([string]$Program, [object[]]$Arguments = @()) {
    if ($Program -match '\.(cmd|bat)$') {
        $commandLine = 'call "' + $Program + '"'
        foreach ($a in $Arguments) { $commandLine += ' "' + [string]$a + '"' }
        cmd /c $commandLine
    } else {
        $native = @(ConvertTo-NativeArgument $Arguments)
        & $Program @native
    }
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
cmd /c 'echo "as it is"'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Invoke-Program 'build.cmd' @('all', $quoted)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$git = 'git'
$global:LASTEXITCODE = 0
Invoke-Program $git @('a b', $quoted)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (Test-Succeeds 'git' @('-C', $Dir, 'status') -Quiet) {
    Write-Host 'status: True'
} else {
    Write-Host 'status: False'
}
$version = $($koineTemp1 = (git --version) -join "`n"; if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
} else {
    $koineTemp1
})
if ("a`n" -cmatch 'a\z') { Write-Host 'end: True' } else { Write-Host 'end: False' }
if ("a`nb" -cmatch '(?s)^a.b\z') { Write-Host 'dot: True' } else { Write-Host 'dot: False' }
if ('x\y' -cmatch '[\\]') { Write-Host 'backslash: True' } else { Write-Host 'backslash: False' }
if (']' -cmatch '^[\]a]\z') { Write-Host 'bracket: True' } else { Write-Host 'bracket: False' }
