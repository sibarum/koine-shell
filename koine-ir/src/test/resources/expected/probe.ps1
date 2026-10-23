<#
.SYNOPSIS
  Tries each portable construct.

.PARAMETER json
  A JSON file to read.
#>
param([string]$json)
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

# Fails the script: the message in red, with the step it is in, then exit, which runs every finally.
function koine_fail([string]$Message) {
    if ($script:KoineStep) { $Message = "$script:KoineStep failed: $Message" }
    Write-Host $Message -ForegroundColor Red
    exit 1
}

# Data read as a record: only the record's fields, a missing one as empty, and every list an array.
function koine_as_Doc($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ name = [string]$Value.name; count = [long]$Value.count; on = [bool]$Value.on; tags = @(foreach ($koineItem in $Value.tags) {
        [string]$koineItem
    }) }
}

function String_words([string]$text) {
    return @(-split $text)
}

function String_take([string]$text, [long]$count) {
    return $text.Substring(0, [Math]::Min($count, $text.Length))
}

function List_join([string[]]$items, [string]$separator) {
    return ($items -join $separator)
}

function Path_join([string[]]$parts) {
    $joined = $parts[0]
    for ($i = 1; $i -lt $parts.Count; $i++) { $joined = Join-Path $joined $parts[$i] }
    return $joined
}

function Path_parent([string]$path) {
    return (Split-Path -Path $path -Parent)
}

function Path_name([string]$path) {
    return (Split-Path -Path $path -Leaf)
}

function File_exists([string]$path) {
    return (Test-Path -LiteralPath $path)
}

function Number_text([long]$number) {
    return [string]$number
}

function Json_read([string]$path) {
    return (Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
}

function Console_warn([string]$message) {
    Write-Host $message -ForegroundColor Yellow
}

function Get-None { return @() }

function Get-Two { return @('x', 'y') }

function Join-Both($xs, $sep, $ys) { return "$(List_join $xs $sep) | $(List_join $ys $sep)" }

function Describe-Point($p) { return "$($p.label) at $(Number_text $p.x)" }

function Move-Point($p, $by) { return [pscustomobject]@{ x = $by; label = $p.label } }

function Test-Big($n) { return $n -gt 2 }

$m = [System.Collections.Specialized.OrderedDictionary]::new()
$m['B'] = '1'
$m['b'] = '2'
Write-Host "map: $($m['b']) $($m['B'])"
if ($m.Contains('b')) { Write-Host 'has b: yes' } else { Write-Host 'has b: no' }
$words = @(String_words '  a  b c ')
Write-Host "words: $($words[1]) $(List_join $words ',')"
Write-Host "take: $(String_take 'abcdef' 3) $(String_take 'ab' 9)"
if ('  # x' -cmatch '^[\s]*(#|\z)') { Write-Host 'comment: yes' } else { Write-Host 'comment: no' }
if ('A' -ceq 'a') { Write-Host 'same case: yes' } else { Write-Host 'same case: no' }
if ('abc'.Contains('b')) { Write-Host 'in string: yes' } else { Write-Host 'in string: no' }
if ($words -ccontains 'c') { Write-Host 'in list: yes' } else { Write-Host 'in list: no' }
if (-not ($words -ccontains 'z')) { Write-Host 'not in list: yes' } else { Write-Host 'not in list: no' }
Write-Host "lists: [$(List_join @(Get-None) ',')] [$(List_join @(Get-Two) ',')]"
Write-Host "both: $(Join-Both @('a b', 'c') ', ' @('d'))"
$p = [pscustomobject]@{ x = 3; label = 'here' }
Write-Host "record: $(Describe-Point $p)"
$q = Move-Point $p 7
Write-Host "moved: $(Number_text $q.x) $($q.label)"
if (Test-Big 3) { Write-Host 'big: yes' } else { Write-Host 'big: no' }
$doc = koine_as_Doc (Json_read $json)
Write-Host "data: $($doc.name) $(Number_text $doc.count) $(List_join $doc.tags '+')"
if ($doc.on) { Write-Host 'on: yes' } else { Write-Host 'on: no' }
Write-Host "path: $(Path_name (Path_join @('a', 'b', 'c.txt'))) $(Path_name (Path_parent (Path_join @('a', 'b', 'c.txt'))))"
if (File_exists $json) { Write-Host 'exists: yes' } else { Write-Host 'exists: no' }
if (Test-Succeeds 'git' @('--version') -Quiet) { Write-Host 'probe git: yes' } else { Write-Host 'probe git: no' }
if (Test-Succeeds 'git' @('no-such-subcommand') -Quiet) {
    Write-Host 'failing git: yes'
} else {
    Write-Host 'failing git: no'
}
if (Test-Succeeds 'no-such-program-koine' -Quiet) {
    Write-Host 'missing program: yes'
} else {
    Write-Host 'missing program: no'
}
$lines = 0
git --version | ForEach-Object { $line = $_; $lines = 1 }
$status = $LASTEXITCODE
Write-Host "lines: $lines status $status"
$version = $($koineTemp1 = (git --version) -join "`n"; if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
} else {
    $koineTemp1
})
Write-Host "capture: $(String_take $version 11)"
$other = $($koineTemp2 = (git config --get koine.no-such-key) -join "`n"; if ($LASTEXITCODE -ne 0) {
    'fallback'
} else {
    $koineTemp2
})
Write-Host "failed capture: $other"
$n = 0
while ($n -lt 3) { $n = 3 }
Write-Host "while: $n"
try { Write-Host 'in the body' } finally { Write-Host 'cleaned up' }
Console_warn 'warned'
if (-not (Test-Succeeds 'git' @('--version') -Quiet)) { exit 1 }
try { koine_fail 'on purpose' } finally { Write-Host 'cleaned up after the failure' }
Write-Host 'never'
