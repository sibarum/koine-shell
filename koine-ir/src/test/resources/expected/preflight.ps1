<#
.SYNOPSIS
  Everything before publishing: builds the stack, starts every application, checks the notices and versions, and generates the installers without publishing them.

.DESCRIPTION
  1. build      build.cmd: libraries in order with their tests, then each application's tests and native image
  2. start      each application's exe, from its installer.json, must still be running after a few seconds
  3. linux      each application with a linux section needs its Linux binaries, built in the Linux VM from
                exactly the commits checked out here
  4. notices    notices/notices.py --check
  5. versions   lists each application whose installer.json version is already published
  6. generate   deploy without -Publish, then the diff here and in ../sibarum.github.io

  Nothing is uploaded, committed or pushed. Stops at the first failing step.

.PARAMETER SkipBuild
  Use the exes already in each application's target directory.

.PARAMETER Seconds
  How long each application must stay up.

.PARAMETER BuildOnly
  Stop after the notices: build, start, the Linux build and the notices checked, generating nothing.
#>
param([switch]$SkipBuild, [int]$Seconds = 8, [switch]$BuildOnly)
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

$dist = $PSScriptRoot
$root = Split-Path $dist -Parent
$logs = Join-Path $dist logs
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$suite = Get-Content -LiteralPath (Join-Path $dist installer-suite.json) -Raw | ConvertFrom-Json
$configs = @()
foreach ($m in $suite.members) {
    $configs += $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath((Join-Path $dist ([string]$m.config)))
}

function Step($name) { Write-Host "`n== $name" -ForegroundColor Cyan }

function Fail($msg) { Write-Host "PREFLIGHT FAILED: $msg" -ForegroundColor Red; exit 1 }

# A Python that answers --version: the Store's python.exe stub is on the path but runs nothing.
function Find-Python {
    foreach ($c in @('python', 'py', (Join-Path (Join-Path $HOME anaconda3) python.exe), (Join-Path (Join-Path $HOME miniconda3) python.exe))) {
        if ([bool](Get-Command $c -ErrorAction SilentlyContinue) -and (Test-Succeeds $c @('--version') -Quiet)) {
            return $c
        }
    }
    return ''
}

if (-not $SkipBuild) {
    Step 'build'
    # Its output is checked for FAILED as well as its status, so a failure can never pass as a build.
    $failed = $false
    cmd /c "call `"$(Join-Path $dist build.cmd)`"" | ForEach-Object {
        $line = $_
        Write-Host $line
        if ($line -cmatch 'FAILED') { $failed = $true }
    }
    $status = $LASTEXITCODE
    if ($status -ne 0 -or $failed) { Fail 'the build; see logs/' }
}
Step 'start'
$dead = @()
foreach ($config in $configs) {
    $app = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
    foreach ($b in $app.windows.binaries) {
        if (-not ([string]$b.source).EndsWith('.exe', [System.StringComparison]::Ordinal)) { continue }
        $exe = Join-Path (Split-Path $config -Parent) ([string]$b.source)
        if (-not (Test-Path -LiteralPath $exe)) { $dead += "$($app.id): no $exe"; continue }
        $out = Join-Path $logs "start-$($b.name).log"
        $p = Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe -Parent) -PassThru -RedirectStandardOutput $out -RedirectStandardError "$out.err"
        Start-Sleep -Seconds $Seconds
        if (-not $p.HasExited) {
            Write-Host "  $($b.name) up"
            Stop-Process -InputObject $p -Force
        } else {
            $dead += "$($b.name) exited within ${Seconds}s; see logs/start-$($b.name).log.err"
        }
    }
}
if (@($dead).Count -ne 0) {
    foreach ($problem in $dead) { Write-Host "  $problem" -ForegroundColor Red }
    Fail 'an application does not start'
}

# The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of
# every checkout it built. They are fit to publish only while every one of those is still checked out.
function Test-LinuxBuild {
    $stamp = Join-Path (Join-Path $logs linux) BUILD.txt
    if (-not (Test-Path -LiteralPath $stamp)) { return @('no Linux build: logs/linux/BUILD.txt is missing') }
    $builtFrom = [System.Collections.Specialized.OrderedDictionary]::new()
    foreach ($line in @(Get-Content -LiteralPath $stamp)) {
        if ($line -cmatch '^[\s]*(#|\z)') { continue }
        $f = @(-split $line)
        $builtFrom[$f[0]] = $f[1]
    }
    $problems = @()
    foreach ($repo in @($builtFrom.Keys)) {
        $head = (git -C (ConvertTo-NativeArgument (Join-Path $root $repo)) rev-parse HEAD) -join "`n"
        if ($head -cne $builtFrom[$repo]) {
            $problems += "${repo}: the Linux build is of $($builtFrom[$repo].Substring(0, [Math]::Min(9, $builtFrom[$repo].Length))), the checkout is at $(if ([string]::IsNullOrEmpty($head)) { 'nothing' } else { $head.Substring(0, [Math]::Min(9, $head.Length)) })"
        }
    }
    foreach ($config in $configs) {
        $app = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
        if ($null -eq $app.linux) { continue }
        $repo = Split-Path (Split-Path $config -Parent) -Leaf
        if (-not $builtFrom.Contains($repo)) { $problems += "${repo}: not in the Linux build" }
        foreach ($b in $app.linux.binaries) {
            if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $config -Parent) ([string]$b.source)))) {
                $problems += "$($app.id): no $($b.source)"
            }
        }
    }
    return $problems
}

Step 'linux'
$linuxApps = @()
foreach ($config in $configs) {
    $app = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
    if ($null -ne $app.linux) { $linuxApps += [string]$app.name }
}
if (@($linuxApps).Count -eq 0) {
    Write-Host '  no application has a linux section'
} else {
    $interactive = [Environment]::UserInteractive -and -not [Console]::IsInputRedirected -and -not ([Environment]::GetCommandLineArgs() -match '^-NonI')
    $problems = @(Test-LinuxBuild)
    while (@($problems).Count -ne 0) {
        foreach ($problem in $problems) { Write-Host "  $problem" -ForegroundColor Yellow }
        Write-Host '  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh'
        if (-not $interactive) { Fail 'the Linux build is missing, or not of these sources' }
        Read-Host '  Press Enter once it has finished, to check again (Ctrl+C stops)' | Out-Null
        $problems = @(Test-LinuxBuild)
    }
    Write-Host "  built in the VM from these sources: $($linuxApps -join ', ')"
}
Step 'notices'
$python = Find-Python
if ([string]::IsNullOrEmpty($python)) { Fail 'no Python found (python, py, or anaconda3)' }
if (-not (Test-Succeeds $python @((ConvertTo-NativeArgument (Join-Path (Join-Path $dist notices) notices.py)), '--check'))) {
    Fail 'the notices'
}
if ($BuildOnly) { Write-Host "`nBuilt, started and checked." -ForegroundColor Green; exit 0 }
Step 'versions'
foreach ($config in $configs) {
    $app = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
    $manifest = Join-Path (Join-Path (Join-Path $dist apps) ([string]$app.id)) manifest.json
    $latest = ''
    if (Test-Path -LiteralPath $manifest) {
        $latest = [string](Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json).latest
    }
    if ($latest -ceq [string]$app.version) {
        Write-Host "  $($app.name) $($app.version) is already published; bump it in $config if it changed" -ForegroundColor Yellow
    } else {
        Write-Host "  $($app.name) $($app.version) (published: $(if ([string]::IsNullOrEmpty($latest)) { 'none' } else { $latest }))"
    }
}
Step 'generate'
if (-not (Test-Succeeds (Join-Path $PSScriptRoot 'deploy.ps1'))) { Fail 'deploy' }
Step 'diff'
git -C (ConvertTo-NativeArgument $dist) status --short
$site = Join-Path $root sibarum.github.io
if (Test-Path -LiteralPath (Join-Path $site .git)) { git -C (ConvertTo-NativeArgument $site) status --short }
Write-Host "`nPreflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish." -ForegroundColor Green
