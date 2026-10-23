# The IR, settled against preflight

Roadmap step 3: what the portable IR in `koine-ir` keeps, now that the language exists. The IR and its three lowerings
are changed to match. The method was to take the
checked preflight (`koine-lang/src/test/resources/examples/preflight.koine`) and the standard library it uses
(`koine-lang/src/main/resources/sibarum/koine/std`), and say for every construct which IR node it becomes. A node
nothing maps to is dropped. A node that is now a library member moves into that member's `native` bodies, with its
per-shell code.

## The shape

- **One IR per OS.** The compiler resolves `on` and `native` for the OS it is compiling for, so a `Script` holds only
  that OS's code. A native function says which OS its body is for, and a lowering refuses one written for another
  shell.
- **Library members are functions.** A module member the script uses, directly or through other members, is emitted
  once, as a function: a `NativeFunction` for a native body, a `Function` for a Koine one. A call to either is the
  same `Call` or `Invoke`. A member that takes a block is never a function: the compiler has already expanded it.
- **Records are types.** `Type.Record` has named, typed fields. A record is made with `MakeRecord`, read with
  `FieldOf`, and `ReadAs` turns `Data` from a read into one. PowerShell holds a record as a `[pscustomobject]`; Bash
  and Zsh as one variable per field, `p_id` and `p_log`, as language.md says.

## Each construct in preflight, and its node

| Koine | IR |
|---|---|
| `script Preflight<Windows>(skipBuild = false, …)` | `Script` and its `Param`s, for the Windows lowering only |
| `let x = …`, `x = …` | `Set` |
| `list.add(x)`, `map[k] = v` | `Append`, `Put` |
| `if` / `else if` / `else` | `If` |
| `for x in list` | `ForEach` |
| ``for line in `cmd` `` | `EachLine`, then a `Fail` if its status is not 0 |
| `while` | `While` |
| `step Build` | `Script.runStep('Build')`, expanded: `Invoke`s of its native members, then the block, under `Finally` |
| `within dir` | `Dir.runIn(dir)`, expanded the same way |
| `` `cmd` `` | `Run` with `ABORT` |
| ``try `cmd` `` | `Run` with `IGNORE` |
| ``try `cmd` `` in a condition | `Succeeds` |
| ``let x = `cmd` `` | `Capture`, which fails the script if the command does |
| ``let x = `cmd` else 'y'`` | `Capture` with a fallback value |
| ``let x = `cmd` else fail 'y'`` | `Capture` with a failure message |
| `fail 'message'` | `Fail` |
| `return` at the script's level | `Exit` with 0, after the cleanup |
| `def`, `return x`, a call | `Function`, `Return`, `Call` or `Invoke` |
| a module member | `NativeFunction` or `Function`, then `Call` or `Invoke` |
| `print x` | `Print` |
| `'…'`, `{}"…{x}…"`, numbers, `true` | `StringLit`, `Interp`, `IntLit`, `BoolLit` |
| `[…]`, `{}` | `ListLit`, `EmptyMap` |
| `xs[i]`, `map[k]`, `map.has(k)`, `map.keys`, `x.isEmpty` | `At`, `Get`, `HasKey`, `Keys`, `IsEmpty` |
| `line.matches('^…')` | `Matches`: the pattern is a literal, translated to .NET's dialect when compiling |
| `not`, `and`, `or`, `==` … `>=`, `in` | `Not`, `And`, `Or`, `Compare`, and `In` (new) |
| `Json.read(path) as App` | `Call` of `Json.read`, which gives `Data`, then `ReadAs` |
| `app.version` | `FieldOf` |
| `Binary(name: …, source: …)` | `MakeRecord` |
| `finally` | `Finally` |

## Each node, and what became of it

**Kept:** `StringLit`, `IntLit`, `BoolLit`, `Ref`, `Interp`, `ListLit`, `EmptyMap`, `At`, `Get`, `HasKey`, `Keys`,
`IsEmpty`, `Not`, `And`, `Or`, `Compare`, `Matches`, `Succeeds`, `Capture`, `Call`; `Set`, `Append`, `Put`, `If`,
`ForEach`, `While`, `Function`, `Return`, `Invoke`, `Exit`, `Print`, `Run`, `EachLine`, `Comment`.

**Changed:**
- `Capture` says what a failure does: stop the script, give a fallback value, or fail with a message. It used to
  ignore the exit status, which the language does only when asked.
- `Print` has no colour. Colour is `Console.success`, `warn`, `error` and `heading`.
- `IsEmpty` works on a record (an optional field that is missing) instead of a JSON value.

**New:** `NativeFunction`, `Finally`, `Fail`, `In`, `MakeRecord`, `FieldOf`, `ReadAs`; the types `Record` and `Data`.

**Moved into the standard library, with their per-shell code:**

| Node | Member |
|---|---|
| `ScriptDir`, `ScriptPath` | `Script.directory`, `Script.sibling` |
| `Home` | `Home.directory` |
| `PathJoin`, `Parent`, `FileName`, `FullPath` | `Path.join`, `parent`, `name`, `full` |
| `Exists`, `ReadLines` | `File.exists`, `File.lines` |
| `MakeDirs` | `Dir.create` |
| `ReadJson` | `Json.read`, native on Windows and bundled on Linux and Mac |
| `Spawn`, `IsRunning`, `Kill` | `Process.start`, `running`, `stop` |
| `Sleep` | `Clock.sleep` |
| `Pause`, `IsInteractive`, `Print`'s colours | `Console.pause`, `interactive`, `success` / `warn` / `error` / `heading` |
| `EndsWith`, `Take`, `Words` | `String.endsWith`, `take`, `words` |
| `Join` | `List.join` |

**Dropped,** since nothing in the language makes them: `Count`, `Choose`, `Break`, `Continue`, `Env`,
`CommandExists`, `Field` (records replace it), and the types `Json` and `Process` (a record replaces it).

## What the lowerings now do for native functions

A native function is emitted with a prologue the lowering writes, then its body as it is. What a body can rely on is
the table under *Native bodies* in language.md; the prologues are:

- **PowerShell:** `param()` with each parameter typed: `[string]`, `[long]`, `[bool]`, `[string[]]`, or untyped for
  a record. A call passes arguments by position, a list as one array.
- **Bash:** `local name=$1 …` for each parameter. A list is passed as its length then its items, so that any number
  of lists can be passed, in any position. A record is passed as its fields in order, and unpacked as `p_id`, `p_log`.
- **Zsh:** the same, after `emulate -L zsh`, with `local -h` so that a parameter named like one of Zsh's special
  parameters, `path` above all, is an ordinary local instead of changing `$PATH`.

The result comes back as language.md's rule 2 says: `REPLY` or `reply`, `reply_<field>` for a record, and the exit
status for a `Bool`; the caller copies it at once. Koine functions use the same convention.

## How it was checked

- **Preflight** is built in the IR by hand in `koine-ir/src/test/java/sibarum/koine/ir/Preflight.java`, as the
  compiler will build it: Windows only, every native function taken from the standard library's files, `step` and
  `within` expanded. It lowers to `expected/preflight.ps1`, which Windows PowerShell 5.1 parses without errors. It has
  not been run: running it builds the whole stack and starts every application, which is step 5.
- **The probe** (`Probe.java`) is one script of every construct, built for each OS from that OS's native bodies. Its
  PowerShell and Bash outputs were run, and print the same lines, those in `expected/probe.out`. The Bash run left out
  reading data, since this machine's Git Bash has no `jq`. Running it caught a real fault: a native body that returned
  a list as `return ,$list` came back as a list holding the list, since the caller puts `@()` round the call too.
  Native bodies now return a list as it is.
- **Zsh** output is read, not run: there is no Zsh here.

## Not settled here

- **Records on Bash and Zsh beyond flat ones.** A record whose fields are lists of records (preflight's `App`) does
  not fit in arrays side by side. Preflight is Windows-only, so it does not need one; a Linux script reading such a
  file will.
- **`ReadAs` on Bash and Zsh.** There `Data` is the document's compact text, and each field is read with `jq`, which
  is bundled. Nanobuf, the native format, needs its own reader in the installer milestone.
- **A missing field without `?`** reads as empty on every shell. language.md says it should fail the script, naming
  the file and the field; `koine_as_<Record>` and the `jq` reads are where that goes.
- **Zsh's cleanup on `exit`.** Bash's `EXIT` trap was tried, and sees the locals of the function that called `exit`.
  Zsh's has not been, and a cleanup that reads a caller's local, such as `Script.runStep`'s, needs it to.
- **Arguments to a batch file** are each put in double quotes for `cmd /c "call …"`. One holding a `"`, `%`, `^` or
  `&` reaches the batch file as cmd reads it, not as written.
- **A `.ps1` run by a path in a variable** has its arguments escaped as a program's would be, since the lowering
  cannot tell it is a script. Preflight runs `deploy.ps1` with no arguments, so it does not matter yet.
- **`return` inside a block that ends with `finally`,** in a `def`: PowerShell runs the cleanup, Bash and Zsh skip it on
  that path. The checker could refuse it until they do.
