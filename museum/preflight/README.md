# preflight.ps1

**Hand-written Windows PowerShell, October 2026**
The script Koine was designed against

| | |
|---|---|
| **Object** | [`preflight.ps1`](preflight.ps1), a release check for the VexelRay stack |
| **Origin** | `vexelray-dist`, commit `0de55cb`, 21 October 2026 |
| **Medium** | Windows PowerShell 5.1 |
| **Dimensions** | 158 lines: 7 steps, 3 parameters, 4 functions |
| **Condition** | Complete and unaltered. Git blob `9c5afdd`, the same as `git -C ../vexelray-dist show 0de55cb:preflight.ps1` |

## The object

Everything short of publishing, in one command. It builds the stack with `build.cmd`, starts each application's
native image and makes sure it stays up, checks that the Linux binaries built in the VM are of exactly the commits
checked out here, checks the third-party notices, lists versions already published, and runs `deploy.ps1` without
`-Publish`. Nothing leaves the machine.

It is a careful script, and its comments say where the care went. The build is started as `cmd /c "call …"`, because
`cmd /c` alone can report success from a batch file that ran `exit /b 1`, and its output is also read for `FAILED`, so
that a failure can never pass as a build. Finding Python skips the Microsoft Store's `python.exe` stub, which answers
but runs nothing. Asking a person to press Enter is guarded, since `Read-Host` fails under `-NonInteractive`.

## Why it is here

Koine's predecessor, shellish, ported this script by hand to a portable IR. Koine then designed its language from the
top, with this script as its first test. Almost every decision in [the language](../../docs/language.md) answers a
line of it:

| In `preflight.ps1` | In Koine |
|---|---|
| `Step`, `Fail`: functions of its own | the keywords `step` and `fail`; `step` is `Script.runStep`, and a failure names its step |
| `Get-Content … \| ConvertFrom-Json`, read field by field | `Json.read(config) as App`: a declared record, with only its fields read |
| `Start-Process`, `$p.HasExited`, `Stop-Process` | `Process.start`, `process.running`, `process.stop` |
| `Find-Python`, with `$ErrorActionPreference = 'Continue'` against the stub | `Python.find` in the standard library, probing with `try` |
| `git rev-parse HEAD` with its exit status ignored, and "nothing" when it fails | `` `git rev-parse HEAD` else 'nothing' ``: open question 5 |
| `if ($BuildOnly) { …; exit 0 }` | `return` at the script's level: open question 6 |
| Windows-only, never said | `script Preflight<Windows>`: open question 7 |
| `$_ -match 'FAILED'` | `'FAILED' in line` |
| the interactive check, written out | `Console.interactive` |
| comment-based help | the comment above the script line |

## Successors

- [`preflight.koine`](../../koine-lang/src/test/resources/examples/preflight.koine): the same story, in Koine.
- [`Preflight.java`](../../koine-ir/src/test/java/sibarum/koine/ir/Preflight.java): the IR the compiler must produce
  from it, built by hand. The compiler's tests check that it does, exactly.
- [`expected/preflight.ps1`](../../koine-ir/src/test/resources/expected/preflight.ps1): what it compiles to. This is
  generated PowerShell, 385 lines, more than twice the length, since it carries the standard library's members with
  it.

## Conservation notes

The compiled successor is not a replica. Where it differs:

- **Starting `build.cmd`** was, until 23 October, the one real loss. The first compiled script ran `& $buildCmd`,
  and on a batch file whose `exit /b 1` sits inside a block with more after it, that reports **0**: tried on Windows
  PowerShell 5.1, where `cmd /c "call …"` reports 1. This script's own comment, and `build.cmd`'s, warn of exactly
  that. Restored: the PowerShell lowering now starts every batch file, and every program named by a value, through an
  `Invoke-Program` function that runs a batch file as `cmd /c "call …"`.
- **Messages:** `PREFLIGHT FAILED: the build` becomes `Build failed: …`, with the step's name. The paths in messages
  are written with `/`.
- **Help:** the three parameters lose their help text. Koine has no way to write it yet (open question 8).
- **Probing Python** was, until 23 October, not quiet: each candidate's `--version` was shown, and so was the
  Microsoft Store stub's "Python was not found; …" on stderr, which read as a fault. The run below caught it.
  Restored: `Python.find` now has native bodies that probe quietly, as `Find-Python` did, since Koine cannot yet
  silence a command.
- **`deploy.ps1`** is judged by its exit status, not by an exception it throws.
- **The Linux step** lists its problems in `BUILD.txt`'s order. The original kept them in a hashtable, so in no
  particular order.
- **The last line** has no blank line before it.

## Run side by side

On 23 October both ran for real, non-interactively, under Windows PowerShell 5.1, from a worktree of
`vexelray-dist` at `0de55cb`, one after the other:

- **With the build:** each built the whole stack (27 and 28 minutes), started all nine applications, and failed at
  the Linux step with exit 1, naming the same five repos that had moved on since the VM's build.
- **With `-SkipBuild`,** given a `BUILD.txt` naming the commits checked out, which no real Linux build had: both
  passed with exit 0. What `deploy.ps1` generated, in `vexelray-dist` and in `sibarum.github.io`, was the same byte
  for byte, but for each manifest's `publishedAt` time.

Every other line printed was the same, apart from the differences listed above and the step names, which Koine
capitalises.

## Later life

The live script did not stop here. By 23 October it had grown, still uncommitted, to 255 lines: it asks the VM to
build as soon as it starts, and waits for an answer (`build.sh --watch`). It also gained `-LinuxLocal` and
`-LinuxMinutes`, and a Linux sibling, `preflight.sh`. `preflight.koine` follows this exhibit, not those changes.
