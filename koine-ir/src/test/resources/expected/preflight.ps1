<#
.SYNOPSIS
  Everything before publishing: builds the stack, starts every application, checks the notices and versions, and generates the installers without publishing them.

.DESCRIPTION
  Nothing is uploaded, committed or pushed. Stops at the first failing step. It builds and starts the Windows
  applications, so it is for Windows only.

.PARAMETER skipBuild


.PARAMETER seconds


.PARAMETER buildOnly

#>
param([switch]$skipBuild, [int]$seconds = 8, [switch]$buildOnly)
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

# Fails the script: the message in red, with the step it is in, then exit, which runs every finally.
function koine_fail([string]$Message) {
    if ($script:KoineStep) { $Message = "$script:KoineStep failed: $Message" }
    Write-Host $Message -ForegroundColor Red
    exit 1
}

# Data read as a record: only the record's fields, a missing one as empty, and every list an array.
function koine_as_App($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ id = [string]$Value.id; name = [string]$Value.name; version = [string]$Value.version; windows = koine_as_Platform $Value.windows; linux = koine_as_Platform $Value.linux }
}
function koine_as_Platform($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ binaries = @(foreach ($koineItem in $Value.binaries) { (koine_as_Binary $koineItem) }) }
}
function koine_as_Binary($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ name = [string]$Value.name; source = [string]$Value.source }
}
function koine_as_Suite($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ members = @(foreach ($koineItem in $Value.members) { (koine_as_Member $koineItem) }) }
}
function koine_as_Member($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ config = [string]$Value.config }
}
function koine_as_Manifest($Value) {
    if ($null -eq $Value) { return $null }
    [pscustomobject]@{ latest = [string]$Value.latest }
}

function Script_directory {
    return $PSScriptRoot
}

function Path_parent([string]$path) {
    return (Split-Path -Path $path -Parent)
}

function Path_join([string[]]$parts) {
    $joined = $parts[0]
    for ($i = 1; $i -lt $parts.Count; $i++) { $joined = Join-Path $joined $parts[$i] }
    return $joined
}

function Dir_create([string]$path) {
    $null = New-Item -ItemType Directory -Force -Path $path
}

function Json_read([string]$path) {
    return (Get-Content -LiteralPath $path -Raw | ConvertFrom-Json)
}

function Path_full([string]$path) {
    return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
}

function Console_heading([string]$text) {
    Write-Host "`n== $text" -ForegroundColor Cyan
}

function Script_currentStep {
    return [string]$script:KoineStep
}

function Script_setStep([string]$name) {
    $script:KoineStep = $name
}

function String_endsWith([string]$text, [string]$suffix) {
    return $text.EndsWith($suffix, [System.StringComparison]::Ordinal)
}

function File_exists([string]$path) {
    return (Test-Path -LiteralPath $path)
}

function Dir_push([string]$path) {
    Push-Location -LiteralPath $path
}

function Process_start([string]$exe, [string]$log) {
    $p = Start-Process -FilePath $exe -WorkingDirectory (Get-Location).Path -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    return [pscustomobject]@{ id = [long]$p.Id; log = $log }
}

function Clock_sleep([long]$seconds) {
    Start-Sleep -Seconds $seconds
}

function Process_running($process) {
    return $null -ne (Get-Process -Id $process.id -ErrorAction SilentlyContinue)
}

function Process_stop($process) {
    Stop-Process -Id $process.id -Force -ErrorAction SilentlyContinue
}

function Number_text([long]$number) {
    return [string]$number
}

function Dir_pop {
    Pop-Location
}

function Console_error([string]$message) {
    Write-Host $message -ForegroundColor Red
}

function File_lines([string]$path) {
    return @(Get-Content -LiteralPath $path)
}

function String_words([string]$text) {
    return @(-split $text)
}

function String_take([string]$text, [long]$count) {
    return $text.Substring(0, [Math]::Min($count, $text.Length))
}

function Path_name([string]$path) {
    return (Split-Path -Path $path -Leaf)
}

function Console_warn([string]$message) {
    Write-Host $message -ForegroundColor Yellow
}

function Console_interactive {
    return [Environment]::UserInteractive -and -not [Console]::IsInputRedirected -and -not ([Environment]::GetCommandLineArgs() -match '^-NonI')
}

function Console_pause([string]$message) {
    $null = Read-Host $message
}

function List_join([string[]]$items, [string]$separator) {
    return ($items -join $separator)
}

function Home_directory {
    return $HOME
}

function String_empty {
    return ''
}

function Console_success([string]$message) {
    Write-Host $message -ForegroundColor Green
}

function Script_sibling([string]$name) {
    return (Join-Path $PSScriptRoot "$name.ps1")
}

function Python_find {
    $home_ = Home_directory
    foreach ($candidate in @('python', 'py', (Path_join @($home_, 'anaconda3', 'python.exe')), (Path_join @($home_, 'miniconda3', 'python.exe')))) {
        if (Test-Succeeds $candidate @('--version')) { return $candidate }
    }
    return String_empty
}

function linuxBuildProblems($logs, $root, $configs) {
    $stamp = Path_join @($logs, 'linux', 'BUILD.txt')
    if (-not (File_exists $stamp)) { return @('no Linux build: logs/linux/BUILD.txt is missing') }
    $builtFrom = [System.Collections.Specialized.OrderedDictionary]::new()
    foreach ($line in @(File_lines $stamp)) {
        if (-not ($line -cmatch '^[\s]*(#|\z)')) { $words = @(String_words $line); $builtFrom[$words[0]] = $words[1] }
    }
    $problems = @()
    foreach ($repo in @($builtFrom.Keys)) {
        $checkout = Path_join @($root, $repo)
        $head = $($koineTemp1 = (git -C (ConvertTo-NativeArgument $checkout) rev-parse HEAD) -join "`n"; if ($LASTEXITCODE -ne 0) {
            'nothing'
        } else {
            $koineTemp1
        })
        if ($head -cne $builtFrom[$repo]) {
            $problems += "${repo}: the Linux build is of $(String_take $builtFrom[$repo] 9), the checkout is at $(String_take $head 9)"
        }
    }
    foreach ($config in $configs) {
        $app = koine_as_App (Json_read $config)
        if ($null -ne $app.linux) {
            $repo = Path_name (Path_parent $config)
            if (-not $builtFrom.Contains($repo)) { $problems += "${repo}: not in the Linux build" }
            foreach ($binary in $app.linux.binaries) {
                if (-not (File_exists (Path_join @((Path_parent $config), $binary.source)))) {
                    $problems += "$($app.id): no $($binary.source)"
                }
            }
        }
    }
    return $problems
}

$dist = Script_directory
$root = Path_parent $dist
$logs = Path_join @($dist, 'logs')
Dir_create $logs
$configs = @()
$suite = koine_as_Suite (Json_read (Path_join @($dist, 'installer-suite.json')))
foreach ($member in $suite.members) { $configs += (Path_full (Path_join @($dist, $member.config))) }
if (-not $skipBuild) {
    try {
        Console_heading 'Build'
        $outer = Script_currentStep
        Script_setStep 'Build'
        # The output is checked for FAILED as well as the exit status, so a failure can never pass as a build.
        $buildCmd = Path_join @($dist, 'build.cmd')
        $failed = $false
        & $buildCmd | ForEach-Object { $line = $_; Write-Host $line; if ($line.Contains('FAILED')) { $failed = $true } }
        $status = $LASTEXITCODE
        if ($status -ne 0) { koine_fail "$buildCmd exited with $status" }
        if ($failed) { koine_fail 'The build printed FAILED; see logs/' }
    } finally { Script_setStep $outer }
}
try {
    Console_heading 'Start'
    $outer = Script_currentStep
    Script_setStep 'Start'
    $dead = @()
    foreach ($config in $configs) {
        $app = koine_as_App (Json_read $config)
        foreach ($binary in $app.windows.binaries) {
            if (String_endsWith $binary.source '.exe') {
                $exe = Path_join @((Path_parent $config), $binary.source)
                if (-not (File_exists $exe)) {
                    $dead += "$($app.id): no $exe"
                } else {
                    $log = Path_join @($logs, "start-$($binary.name).log")
                    try {
                        Dir_push (Path_parent $exe)
                        $process = Process_start $exe $log
                        Clock_sleep $seconds
                        if (Process_running $process) {
                            Write-Host "  $($binary.name) up"
                            Process_stop $process
                        } else {
                            $dead += "$($binary.name) exited within $(Number_text $seconds)s; see logs/start-$($binary.name).log.err"
                        }
                    } finally { Dir_pop }
                }
            }
        }
    }
    if (@($dead).Count -ne 0) {
        foreach ($problem in $dead) { Console_error "  $problem" }
        koine_fail 'An application does not start'
    }
} finally { Script_setStep $outer }

# The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of every
# checkout it built. They are fit to publish only while every one of those is still checked out.
try {
    Console_heading 'Linux'
    $outer = Script_currentStep
    Script_setStep 'Linux'
    $linuxApps = @()
    foreach ($config in $configs) {
        $app = koine_as_App (Json_read $config)
        if ($null -ne $app.linux) { $linuxApps += $app.name }
    }
    if (@($linuxApps).Count -eq 0) {
        Write-Host '  no application has a linux section'
    } else {
        $problems = @(linuxBuildProblems $logs $root $configs)
        while (@($problems).Count -ne 0) {
            foreach ($problem in $problems) { Console_warn "  $problem" }
            Write-Host '  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh'
            if (-not (Console_interactive)) { koine_fail 'The Linux build is missing, or not of these sources' }
            Console_pause '  Press Enter once it has finished, to check again (Ctrl+C stops)'
            $problems = @(linuxBuildProblems $logs $root $configs)
        }
        Write-Host "  built in the VM from these sources: $(List_join $linuxApps ', ')"
    }
} finally { Script_setStep $outer }
try {
    Console_heading 'Notices'
    $outer = Script_currentStep
    Script_setStep 'Notices'
    $python = Python_find
    if ([string]::IsNullOrEmpty($python)) { koine_fail 'No Python found (python, py, or anaconda3)' }
    $notices = Path_join @($dist, 'notices', 'notices.py')
    if (-not (Test-Succeeds $python @((ConvertTo-NativeArgument $notices), '--check'))) {
        koine_fail 'The notices are out of date'
    }
} finally { Script_setStep $outer }
if ($buildOnly) { Console_success 'Built, started and checked.'; exit 0 }
try {
    Console_heading 'Versions'
    $outer = Script_currentStep
    Script_setStep 'Versions'
    foreach ($config in $configs) {
        $app = koine_as_App (Json_read $config)
        $manifest = Path_join @($dist, 'apps', $app.id, 'manifest.json')
        $latest = String_empty
        if (File_exists $manifest) { $latest = (koine_as_Manifest (Json_read $manifest)).latest }
        if ($latest -ceq $app.version) {
            Console_warn "  $($app.name) $($app.version) is already published; bump it in $config if it changed"
        } elseif ([string]::IsNullOrEmpty($latest)) {
            Write-Host "  $($app.name) $($app.version) (published: none)"
        } else {
            Write-Host "  $($app.name) $($app.version) (published: $latest)"
        }
    }
} finally { Script_setStep $outer }
try {
    Console_heading 'Generate'
    $outer = Script_currentStep
    Script_setStep 'Generate'
    $deploy = Script_sibling 'deploy'
    if (-not (Test-Succeeds $deploy)) { koine_fail 'deploy' }
} finally { Script_setStep $outer }
try {
    Console_heading 'Diff'
    $outer = Script_currentStep
    Script_setStep 'Diff'
    try { Dir_push $dist; git status --short } finally { Dir_pop }
    $site = Path_join @($root, 'sibarum.github.io')
    if (File_exists (Path_join @($site, '.git'))) {
        try { Dir_push $site; git status --short } finally { Dir_pop }
    }
    Console_success 'Preflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish.'
} finally { Script_setStep $outer }
