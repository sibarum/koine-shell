<#
.SYNOPSIS
  Tries each portable construct.

.PARAMETER Json
  A JSON file to read.
#>
param([string]$Json)
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

$m = [System.Collections.Specialized.OrderedDictionary]::new()
$m['B'] = '1'
$m['b'] = '2'
Write-Host "map: $($m.Count) $(@($m.Keys) -join ',')"
$words = @(-split '  a  b c ')
Write-Host "words: $($words.Count) $($words[1])"
Write-Host "take: $('abcdef'.Substring(0, [Math]::Min(3, 'abcdef'.Length))) $('ab'.Substring(0, [Math]::Min(9, 'ab'.Length)))"
Write-Host "match: $(if ('  # x' -cmatch '^[\s]*(#|\z)') { 'comment' } else { 'code' })"
Write-Host "case: $(if ('A' -ceq 'a') { 'same' } else { 'different' })"

function Get-None { return @() }

function Get-One { return @('x') }

function Get-Two { return @('x', 'y') }

Write-Host "none $(@(Get-None).Count), one $(@(Get-One).Count), two $(@(Get-Two).Count)"
$doc = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
$ids = @()
foreach ($item in $doc.items) { $ids += [string]$item.id }
foreach ($item in $doc.missing.items) { $ids += 'never' }
Write-Host "json: $($doc.name) [$($doc.nothing)] $($ids -join ' ')"
Write-Host "missing field empty: $(if ($null -eq $doc.linux) { 'True' } else { 'False' })"
Write-Host "probe git: $(if (Test-Succeeds 'git' @('--version') -Quiet) { 'True' } else { 'False' })"
Write-Host "failing git: $(if (Test-Succeeds 'git' @('no-such-subcommand') -Quiet) { 'True' } else { 'False' })"
Write-Host "missing program: $(if (Test-Succeeds 'no-such-program-koine' -Quiet) { 'True' } else { 'False' })"
$lines = 0
git --version | ForEach-Object { $line = $_; $lines = 1 }
$status = $LASTEXITCODE
Write-Host "lines: $lines status $status"
$version = (git --version) -join "`n"
Write-Host "capture: $($version.Substring(0, [Math]::Min(11, $version.Length)))"
Write-Host "path: $(Split-Path (Join-Path (Join-Path 'a' b) c.txt) -Leaf) $(Split-Path (Split-Path (Join-Path (Join-Path 'a' b) c.txt) -Parent) -Leaf)"
Write-Host "interp: $($ids.Count) items, $(@($m.Keys).Count) keys"
exit 7
