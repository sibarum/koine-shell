# Target notes

What each target shell needs from the compiler and the native-tier modules: which native features to use, and which
version-specific traps to avoid. These notes outlive any one design of the IR; the plan itself is in
[roadmap.md](roadmap.md), and the language in [language.md](language.md).

- **P0**: needed for preflight and the installer scripts.
- **P1**: the set a real deploy script needs.
- **P2**: speed-ups and rarer needs.
- **Out**: deliberately not supported.

## Bash

Target **Bash 4.4+**. macOS still ships Bash 3.2, which has no `declare -A`, so maps can't work there. The Zsh
output already covers macOS.

| Priority | Feature | Why |
|---|---|---|
| **P0** | `cmd \|\| exit $?` written out for a command that must succeed, instead of `set -e` | `set -e` is silently turned off inside functions and conditions. |
| **P0** | `"${xs[@]}"` and strict quoting everywhere | Lists need it already. |
| **P0** | `local` for every variable in a function, and results passed back in `reply` | `$(f)` runs `f` in a subshell, so its variable changes are lost. See language.md, Functions. |
| **P0** | `$(( ))` | Arithmetic. |
| **P0** | `[[ =~ ]]` with `BASH_REMATCH` | `.matches` and capture groups. |
| **P0** | `trap … EXIT` | Cleanup. |
| **P0** | `>`, `>>`, `2>&1`, `<`, and `cat <<'EOF'` for writing files | Command plumbing. |
| **P0** | Pipes with `pipefail` / `PIPESTATUS` | Pipe exit status. |
| **P0** | `export`, and `VAR=x cmd` for one command | Environment. |
| **P0** | `while IFS= read -r … done < <(cmd)`, then `wait $!` | `for line in` a command, and its exit status (needs 4.4). |
| **P1** | `case` with glob patterns | Match. |
| **P1** | `${v#pat}`, `${v/pat/rep}`, `${v,,}` | String operations without forking a process. |
| **P1** | `( cd dir && cmd )` | Working directory for one command. |
| **P1** | `mktemp`, `read -r -p`, `wait` / `kill`, `command -v` | Temp files, prompts, processes, finding a program. |
| **P1** | `uname` | Platform detection. |
| **P2** | `declare -A`, `mapfile` | Faster maps and `File.lines`. |
| **Out** | `eval`, `coproc`, `select`, `shopt` for interactive use, brace ranges | Unsafe, or not needed. |

## Zsh

| Priority | Feature | Why |
|---|---|---|
| **P0** | `emulate -L zsh` at the top of the script and of each function | Gives predictable options whatever the user's rc files set. |
| **P0** | 1-based arrays: lower `xs[i]` as `xs[i+1]` | The easiest bug to ship. |
| **P0** | `${0:A:h}`, and the `:t`, `:h`, `:A` modifiers | `Script.directory` and the `Path` members without external tools. |
| **P0** | `{ … } always { … }` | Cleanup. Cleaner than `trap`. |
| **P0** | `typeset -A` | Maps, with no version worries. |
| **P0** | `[[ =~ ]]` with `$match` / `$MATCH` | Capture groups. |
| **P0** | `$pipestatus` (lower case), `setopt pipe_fail` | Pipe exit status. |
| **P0** | `${(f)"$(cmd)"}` | Splitting output into lines. |
| **P1** | Parameter flags `(j:…:)`, `(s:…:)`, `(L)`, `(U)`, `(k)` | `.join`, split, case, `.keys`. |
| **P1** | Glob qualifiers `(N)`, `(.)`, `(/)` | File listing that is null-safe and filters files or directories. |
| **P1** | `zparseopts` | Native long options for script parameters. |
| **P1** | `read -r "?prompt"` | Prompt. |
| **P2** | Anonymous functions for scoping, `TRAPINT`, `=(…)` | `=(…)` gives a temp-file process substitution, for tools that need a real file. |
| **Out** | ZLE, completion, prompts, `extendedglob` operators, `zmodload` beyond the basics | Interactive shell features, not scripting. |

## PowerShell

Target **Windows PowerShell 5.1**, which every Windows machine has, and make sure the output still runs on 7.x.

| Priority | Feature | Why |
|---|---|---|
| **P0** | Check `$LASTEXITCODE` after every native call | 5.1 has no `&&`, `\|\|`, or native-command error action. |
| **P0** | Escape native arguments | 5.1 drops empty arguments and mangles embedded `"`. |
| **P0** | Always call `curl.exe`, and watch `sort` and `where` | In 5.1 these are aliases for PowerShell commands. |
| **P0** | Suppress output nobody uses: `$null = …` / `[void]` | Any output a function doesn't capture becomes part of its return value. |
| **P0** | `@(…)` around lists, `,$x` when returning one | Stops one-item lists turning into scalars. |
| **P0** | `try { } finally { }` | Cleanup. Also used to save and restore `$env:` and `Push-Location` around one command. |
| **P0** | `[IO.File]::WriteAllText` with a no-BOM UTF8 encoding, and set `[Console]::OutputEncoding` before capturing output | In 5.1, `Set-Content -Encoding utf8` writes a BOM, and the console decodes captured output with the wrong code page. |
| **P0** | Resolve `Path.full` against the PowerShell location | .NET methods resolve relative paths against the process directory, which isn't the same. |
| **P0** | Integer division as `[Math]::Truncate(a / b)` | `/` gives a double, and `[int]` rounds half to even. |
| **P0** | `-match` with `$Matches` | Capture groups, using the ERE translation in `Patterns.java`. |
| **P1** | `switch -Wildcard` / `-Regex` | Case. |
| **P1** | `Read-Host`, `Get-ChildItem -File`, `Wait-Process`, `New-TemporaryFile` | Prompt, listing files, waiting, temp files. |
| **P1** | `[Console]::Error.WriteLine` | Printing to stderr. |
| **P1** | `$env:OS` | Platform detection; `$IsWindows` doesn't exist in 5.1. |
| **P1** | `[ValidateSet]` | Parameters limited to a set of values. |
| **P1** | Pipes between two native commands, with a caveat | 5.1 re-encodes them as text, so binary data breaks. Use `cmd /c` or temp files for those. |
| **P2** | `ConvertTo-Json -Depth` for writing JSON | The default depth is 2 and silently truncates. |
| **Out** | Object pipelines, classes, jobs (`Process.start` uses `Start-Process`), remoting, DSC, `Invoke-Expression` | Not needed, or unsafe. |
