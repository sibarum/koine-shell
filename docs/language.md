# The Koine language

A draft of the language people write Koine scripts in. Its predecessor, shellish, was built from the bottom up: the three
dialect IRs, then a portable IR shaped by porting `preflight.ps1`, then lowerings. That left the language itself
undesigned. This document designs it from the top. The portable IR is now provisional: it gets checked against this
language, not the other way round. The dialect IRs and their printers stay as the bottom layer.

## Principles

- **Logic that reads like a story.** The spirit of Cucumber and Watir, without Given-When-Then and without regex step
  matching: just clean, plainly written code. Blocks are indentation. No sigils, no `fi`/`done`/`end`.
- **One OS, its native shell.** A script is compiled for one OS at a time, and each OS gets the shell native to it,
  which is the one least likely to be missing, limited or oddly configured.
- **The script reads; the library knows the shell.** Library members such as `Git.getRepoDetails` are written by hand,
  idiomatically, once per OS. The compiler never has to make Bash behave like a general-purpose language.
- **Nothing is special unless you say so.** Strings and commands are written exactly as they should arrive. Value
  brackets and an escape character exist only where a prefix defines them, and where a character would clash, you
  choose different delimiters.
- **For internal use.** Managing utility binaries could come later as library modules. It is not a goal.

## Scripts and targets

```
script Preflight<OS>(skipBuild = false, seconds = 8, buildOnly = false)
  ...
```

`script` gives the name, the OSes it is for and the inputs, with defaults. The compiler produces one script per OS:

| OS | Shell | Output |
|---|---|---|
| Windows | Windows PowerShell 5.1 (and still runs on 7.x) | `preflight.ps1` |
| Linux | Bash 4.4+ | `preflight.sh` |
| Mac | Zsh | `preflight.zsh` |

`<OS>` means all three. A script that only makes sense on some names them instead: `script Preflight<Windows>(…)`
or `script Package<Linux, Mac>`. The compiler produces only those outputs, so a script never has to fail at runtime
for being on the wrong OS.

An installer is marked `bootstrap script Install<OS>(…)`. It runs on machines that do not have Koine's bundle yet, so
it may use only native-tier members (see [Tiers](#tiers)).

### Everything is cross-platform; your own code may not be

The language and its standard library work the same on every OS. Every standard-library member has a body for all
three, and the compiler refuses one that does not. Where a script needs something done differently per OS, it says so
in a `def` of its own, with a body per OS: Koine under `on`, or the shell's own language under `native` (see [Native
bodies](#native-bodies)):

```
def build(dist: String)
  on Windows
    {}`{Path.join(dist, 'build.cmd')}`
  on Linux, Mac
    {}`{Path.join(dist, 'build.sh')}`
```

`on` and `native` appear only there, as a `def`'s whole body, so the story itself always reads the same for every OS.
Each output contains only its own branch, and nothing checks the OS at runtime. A function that proves useful beyond
one script moves into a module next to the scripts that use it.

There are no macros. A macro pastes shell text into its caller, and then has to answer whose variables it sees, how
values are quoted in and out, and what becomes of Bash's `local` and PowerShell's stray output. Rules 1–5 under
[Functions](#functions) answer those questions for a `def`, so a `def` with per-OS bodies is the escape hatch.

## Keywords

| Keyword | Role |
|---|---|
| `script` | Declares the script: its name, its OSes, and its inputs with defaults. `bootstrap script` marks an installer. |
| `let` | Introduces a name. Plain `x = …` reassigns one that exists, so a misspelled name is an error, not a new variable. A name lives until the end of its block, and cannot be introduced again while it lives. An empty list or map takes its type from what is first put in it. |
| `if` / `else` | Branches at runtime. `else if` chains. `else` after a captured command gives a value if the command fails (see [Literal commands](#literal-commands)). |
| `on` | Gives a `def` a Koine body for some OSes. Chosen when compiling. |
| `for … in` | Loops over a list, or over the lines of a command's output as they come. |
| `while` | Loops while a condition holds. |
| `within` | Runs a block in a directory and restores the previous one afterwards, even on failure. It is the only way to change the working directory. |
| `try` | Before a literal command: gives true or false instead of stopping the script. As a statement on its own: runs the command and ignores its exit status. |
| `fail` | Prints the message in red, runs any cleanup, and exits non-zero. |
| `step` | A named part of the story. It announces itself when it starts (`== Build`), and a failure inside it is reported with its name. |
| `def` | Defines a function: a quiet helper that takes arguments and can return a value. See [Functions](#functions). |
| `return` | Ends a function, optionally with its result. At the script's top level it ends the script successfully, running any cleanup. |
| `finally` | Ends a block with cleanup that runs however the block ends. See [Cleanup](#cleanup). |
| `native` | Gives a `def` a body in the shell's own language for some OSes. See [Native bodies](#native-bodies). |

`within` and `step` are written as keywords, but each is defined by a member that takes a block, in the standard
library (see [Members that take a block](#members-that-take-a-block)). The compiler has nothing special for them.
| `print` | Writes a line. |

Operators: `and`, `or`, `not`, `in` (list membership and substring), `==`, `!=`, `<`, `<=`, `>`, `>=`.

## Values

Provisional, and kept small:

- Strings in `'…'` or `"…"`, or between longer runs of quotes: `'''it's'''`. A string is plain text unless a prefix
  defines value brackets or an escape character: `{}"{app.name} is up"`. See [Choosing
  delimiters](#choosing-delimiters).
- There is no empty string literal, since `''` opens a string; the empty string is `String.empty`.
- Whole numbers, `true` and `false`.
- Nothing is converted to a string for you. A value inserted into a string or a command must be a string already (a
  list of strings in a command), so a number is converted explicitly: `{}"up for {seconds.text}s"`.
- Lists `['master', 'main']` and maps `{}`, indexed with `[…]`.
- Members with `.`: `repo.branch.name`, `line.words`, `dead.add(problem)`. A member that takes no arguments is written
  without parentheses.
- Library modules are capitalised names: `Git`, `Path`, `Json`, `Process`, `Console`.

## Literal commands

A literal command goes in backticks. Where it appears decides what it does:

| Where it appears | What it does | Example |
|---|---|---|
| As a statement | Runs it; the script stops if it fails | `` `build.cmd` `` |
| As a value | Captures its output, without trailing line breaks; the script stops if it fails | ``let head = `git rev-parse HEAD` `` |
| As a value, then `else` | Captures its output, or gives the value after `else` if it fails | ``let head = `git rev-parse HEAD` else 'nothing'`` |
| After `try` in a condition | Gives true or false | ``if not try {}`{python} {notices} --check` `` |
| `try` as a statement | Runs it and ignores its exit status | ``try `git status --short` `` |
| After `for … in` | Runs the body for each line of output as it comes; the script stops if it then fails | ``for line in {}`{buildCmd}` `` |

Unless captured, a command's output goes where the script's goes.

### When a capture fails

`else` comes directly after a captured command, on the same line, and gives the value to use if the command fails:

```
let head = {}`git -C {checkout} rev-parse HEAD` else 'nothing'
let python = `python --version` else `py --version` else String.empty
let head = {}`git -C {checkout} rev-parse HEAD` else fail {}"{repo} is not a checkout"
```

- The fallback can be another capture, so fallbacks chain, or `fail` with a better message than the script's own.
- `else` binds to the capture before it and nothing else: ``a == `x` else 'y'`` compares `a` with the capture or `'y'`.
- The failed command's error output still reaches the console; only its exit status is ignored.
- `try` is not used for this, because ``let x = try `cmd` `` would leave it unclear whether `x` is the output or the
  bool. Something that needs both the output and the exit status calls a member that returns a record of them.

On Bash and Zsh this is `head=$(…) || head='nothing'`; on PowerShell, the capture and a test of `$LASTEXITCODE`.

### Inside the backticks

- Words are literal, and so is everything else unless the command's prefix defines value brackets or an escape
  character (see [Choosing delimiters](#choosing-delimiters)).
- With value brackets, `{expr}` inserts a value, and is always exactly **one** argument, whatever it contains. A list
  becomes one argument per item. There is no word splitting.
- `'…'` and `"…"` both group words with spaces, and neither expands anything. Each can contain the other, and a
  longer run of quotes can contain its own quote: `''it's''`. There is no empty argument literal; insert
  `String.empty`.
- Nothing else, for now: no unquoted `|`, `>`, `<` or `&&`. Inside quotes they are just text, so
  `` `jq '.a | .b'` `` passes `.a | .b` as one argument.

The compiler re-quotes every command for its target, so none of these characters reach the shell as written. That
includes PowerShell, where the backtick is the escape character.

### Choosing delimiters

By default nothing in a string or a command is special but its own closing delimiter. A clash is solved by choosing
other delimiters, and inserting values or escaping characters is something you ask for:

- **The command delimiter is a run of backticks**, as in Markdown. A command containing a backtick opens and closes
  with two (or more), and a shorter or longer run inside is literal:

  ```
  ``powershell -Command "Write-Host `"done`""``
  ```

- **The string delimiter is a run of quotes**, the same way: `'''it's'''` ends at three quotes, and `'a '' b'` holds
  two. Quotes inside a command group words by the same rule. So `''` does not mean the empty string: it opens a string
  that ends at the next `''`. The empty string is `String.empty`. Text cannot start or end with its own quote
  character, since the run would take it; use the other quote, or an escape character.

- **A prefix defines what is special**, written right in front of the opening backticks or quote:

  | Prefix | Defines | Example |
  |---|---|---|
  | One symbol | An escape character | `\'it\'s'` |
  | Two characters | Value brackets | `{}"{app.name} is up"` |
  | Two characters, then a symbol | Value brackets, then an escape character | `{}\"{name} said \"hi\""` |

  Value brackets insert a value: `{}` is the usual choice, and others suit text full of braces, such as `jq` filters,
  PowerShell script blocks, JSON or regular expressions:

  ```
  let names = <>`jq -r '.members[] | {name: .config}' <suiteFile>`
  if line.matches(<>'^[0-9]{3} <name>$')
  ```

  Here `<suiteFile>` and `<name>` insert values, and `{name: .config}` and `{3}` arrive as written.

  The brackets are either an opening bracket and its closing one (`()`, `[]`, `{}`, `<>`), or any two of the symbols
  Koine's own syntax does not use: `~ @ $ % ^ & * + - / \ | ;`. The same symbol twice is allowed, so
  `` %%`cmd /c echo %name%` `` inserts `name` between percent signs, much as `cmd` itself would. Inside the chosen
  brackets, the opening one can no longer appear as text: choose a pair the text does not use, or escape it.

  An escape character is one of those symbols, and not one of the brackets. It makes the character after it
  literal, whatever that is: a quote, a backtick, a bracket, a space that would split a command's words, or the
  escape character itself. It applies everywhere in its string or command, including inside a command's quotes. It
  has no other meaning: `\n` is a backslash and an `n`, not a line break.

  Other characters of Koine's own syntax cannot be part of a prefix, because they already mean something in front
  of a string: `[['a']]`, `a=='x'`, `f(a,['x'])`.

C# 11's raw interpolated strings do the same with `$$"""…{{x}}…"""`. Shell commands are nearly always text in another
program's syntax, so in Koine choosing the delimiters is the norm, not an edge case.

## Library modules

A module member hides how a job is done on each OS, the way Watir's `button.click` hid the browser driver. A script
mostly calls members, and uses a literal command only where no member exists yet; those places show which members are
worth adding. How a module is written is under [Modules](#modules).

Members work on the current directory, so `within` is how a script points them at something:

```
within repoDirectory
  let repo = Git.getRepoDetails
  if repo.branch.name in ['master', 'main']
    fail {}"Run this from develop, not {repo.branch.name}"
```

## Functions

`def` defines a function and `return` gives its result:

```
def latestPublished(manifest)
  if not File.exists(manifest)
    return String.empty
  return (Json.read(manifest) as Manifest).latest
```

It is called like a member: `latestPublished(manifest)`, and one that takes no arguments has no parentheses.

`step` and `def` stay separate. A `step` is a chapter of the story: it announces itself, and a failure inside it is
reported with its name. A `def` is a quiet helper that can take arguments and return a value.

Shells make returning a value hard: in Bash, `$(f)` runs `f` in a subshell, and in PowerShell any output a function
does not capture becomes part of its result. These rules make functions safe on every target:

1. **A function's output is never its result.** Anything it prints goes to the console. The only way to produce a
   result is `return`.
2. **Results come back through one fixed variable.** The function sets it just before it returns, and the caller copies
   it at once. Zsh already has this convention in `REPLY` and `reply`, and Bash uses the same names. Copying at once
   keeps recursion working, and it needs nothing newer than Bash 3.2. PowerShell returns normally; it unrolls a
   returned list into nothing, one value or an array, so the caller puts `@()` round a call that returns a list, and
   the compiler puts `$null =` in front of every other line that would produce output.

   ```
   # Bash
   linuxBuildProblems() { local -a problems=(); …; reply=("${problems[@]}"); return 0; }
   linuxBuildProblems "$logs" "$root" "${configs[@]}"; problems=("${reply[@]}")
   ```

3. **Arguments are values, and every variable is local.** Bash functions can see their caller's local variables. The
   compiler makes every `let` inside a function local, so a function cannot change anything it was not passed. Nor
   can it read the script's variables: a function sees its parameters, the script's functions and records, and modules.
4. **`fail` inside a function stops the whole script.** Rule 1 guarantees it: nothing runs in a subshell.
5. **A result is a string, a number, a boolean, a list or a record.** A map stays inside the function that builds it.
   See [Records](#records).

A function is defined at the script's top level, and can be called before the line that defines it: the compiler
moves every definition to the top of the output.

### Ending the script early

The script line reads like a signature, and `return` ends the script the way it ends a function: successfully, after
running any cleanup.

```
if buildOnly
  Console.success("Built, started and checked.")
  return
```

- At the script's level `return` takes no value. Failing is `fail`'s job, and a script has no result.
- Inside a `def`, `return` still ends only the function. Only `fail` stops the script from inside one (rule 4).
- For now, `return` cannot appear inside a block given to a member, and so not inside `within` or `step` either (see
  [Members that take a block](#members-that-take-a-block)).

## Modules

*Proposed. This answers open question 1.*

A module is a Koine file of `def`s. A member is written in Koine where Koine can express it, using `on` and backticks
where the OS matters. Where it cannot, the member has a **native body** for each OS, written in that shell's own
language. There is one parser and one checker for scripts and modules alike, and only the members a script uses
(and the members they use) are emitted.

```
module Python

def find: String
  for candidate in ['python', 'py', Path.join(Home.directory, 'anaconda3', 'python.exe')]
    if try {}`{candidate} --version`
      return candidate
  return String.empty
```

```
module Clock

def sleep(seconds: Number)
  native Windows
    Start-Sleep -Seconds $seconds
  native Linux, Mac
    sleep "$seconds"
```

### Files and names

- A module is a file named after it, `Python.koine`, starting with `module Python`. The standard library ships with the
  compiler. A script finds a module there first, then next to itself, so scripts in one folder can share a module.
  That is also how functions are shared between scripts (open question 2).
- There are no imports. A capitalised name is a module.
- A module can declare records (see [Records](#records)). A record named after its module, such as `Process` in
  `Process`, is that module's main type.

### Signatures

Every member declares its parameter types and its result type: `def start(exe: String, log: String): Process`. A
member with no result leaves the result out. The types are `String`, `Number` (a whole number), `Bool`, lists such as
`[String]`, and records. A script's own `def`s may leave the types out, as they do now; a module is a contract, so its
members may not, and neither may a `def` with a native body.

- **A script's `def` without types takes them from its first call.** The arguments there fix its parameter types, and
  its `return`s fix its result; every later call must agree. One that is never called must declare them.
- **The last parameter can be repeated:** `def join(parts: String...): String` takes any number of strings, and the
  body sees `parts` as a `[String]`. That is how `Path.join(dist, 'apps', app.id)` is declared.
- **`Block`** is the type of a block given to a member (see [Members that take a block](#members-that-take-a-block)).
- **`Data`** is what a read gives, such as `Json.read(path): Data`. It can only be read into a record with `as`, so the
  record says which fields the native body must produce (see [Reading and writing data](#reading-and-writing-data)).

### Tiers

A module is native tier unless its header says otherwise: `module Rg bundled`. One member of a native module can be
raised on its own: `bundled def search(…)`, and so can one OS's branch of a member: `native Linux, Mac bundled`, as
`Json.read` is (see [Reading and writing data](#reading-and-writing-data)). The checker enforces both rules, per OS:

- A native member cannot use a bundled one.
- A bootstrap script cannot use a bundled member, directly or through other members.

### Native bodies

`native` takes the same OS list as `on`. A `def`'s body, in a module or a script alike, is either Koine, or a set of
branches by OS, each of which is `on` (Koine) or `native` (shell). Every OS the script is compiled for must be
covered, or the compiler says which one is missing: `Clock.sleep has no Linux body`. A standard-library member must
cover all three.

A native body is raw text. It ends where the indentation does, and nothing in it is interpreted, so it needs no
delimiters and no escaping. The compiler copies it into the function it generates, and does not check it, quote it
or put `$null =` in front of its lines. Writing it correctly for its shell is the module author's job, and the [target
notes](targets.md) are the checklist.

What a native body can rely on:

| | PowerShell | Bash, Zsh |
|---|---|---|
| Parameters | `$name`, typed: `[string]`, `[long]`, `[bool]`, `[string[]]`, or the record's object. Avoid PowerShell's automatic variables, such as `$home`, `$input` or `$args`. | Locals named after the parameters, unpacked by a prologue the compiler writes. Lists are arrays (from 1 in Zsh); a `Bool` is 0 or 1; a record parameter `p` is one local per field, `p_id`, `p_log`. Zsh's are `local -h`, so `path` is safe. |
| A `String`, `Number` or list result | `return $value`; a list as it is, `return $list`, since the caller puts `@()` round the call to undo PowerShell unrolling it | Set `REPLY`, or the array `reply` |
| A `Bool` result | `return $true` | The exit status: 0 for true |
| A record result | `return [pscustomobject]@{ … }` | Set `reply_<field>` for each field, `reply_branch_name` for a nested one |
| Failing | `koine_fail 'message'` | `koine_fail 'message'` |

`koine_fail` prints the message in red, runs any cleanup and exits non-zero, as `fail` does. The compiler emits it in
every output that needs it. Koine names cannot contain `_`, so the flattened names never collide.

A record and the members that work on it:

```
module Process

record Process
  id: Number
  log: String

def start(exe: String, log: String): Process
  native Windows
    $p = Start-Process -FilePath $exe -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    return [pscustomobject]@{ id = [long]$p.Id; log = $log }
  native Linux, Mac
    "$exe" >"$log" 2>"$log.err" &
    reply_id=$!
    reply_log=$log

def running(process: Process): Bool
  native Windows
    return $null -ne (Get-Process -Id $process.id -ErrorAction SilentlyContinue)
  native Linux, Mac
    kill -0 "$process_id" 2>/dev/null

def stop(process: Process)
  native Windows
    Stop-Process -Id $process.id -Force -ErrorAction SilentlyContinue
  native Linux, Mac
    kill "$process_id" 2>/dev/null || true
```

`process.running` is `Process.running(process)`: a member written after a value is looked up in the module that
declares the value's record. The members of strings, numbers and lists (`.endsWith`, `.take`, `.words`, `.text`,
`.join`) are written the same way, in the standard library's `String`, `Number` and `List`. A few are built into the
compiler instead:

- **Members that change a value in place,** `list.add(x)` and `map[key] = x`, because a function cannot change what
  it was passed (rule 3).
- **Members that work on any list, map or record,** `.isEmpty` (on strings too), `map.keys` and `map.has(key)`,
  because a module member's parameter has one type, and there is no way to write "a list of anything".
- **`text.matches(pattern)`,** because its pattern, a POSIX ERE, is translated for PowerShell when compiling. It must
  be a string written out, with nothing inserted.

A native body is a leaf: it never holds Koine and never calls back into it. Where native code has to come before and
after some Koine, the member is written in Koine and calls native members for those parts; see the next section.

### Members that take a block

A member can take a block of the caller's code, the way `within` does. It declares a parameter of type `Block`, runs
it by naming it, and is called with the block indented under the call:

```
module Lock

record Lock
  path: String

def acquire(path: String): Lock
  native Windows
    …
  native Linux, Mac
    …

def release(lock: Lock)
  native Windows
    …
  native Linux, Mac
    …

def with(path: String, body: Block)
  let lock = acquire(path)
  body
  finally
    release(lock)
```

```
Lock.with(stateFile)
  let state = Nanobuf.read(stateFile) as State
  Nanobuf.write(stateFile, state)
```

Shells have no closures to pass a block as: Bash has none, and faking them with `eval` or with functions that see the
caller's locals brings back every scoping and quoting problem the rules for functions exist to avoid. So the
compiler expands a member that takes a block where it is called, in the syntax tree:

1. **The member's body is copied into the caller,** with its own locals renamed so they cannot clash, and the caller's
   block goes where the member names it. What comes out is ordinary Koine, and is compiled like the rest; nothing is
   pasted together as text.
2. **The block is the caller's code.** It sees and assigns the caller's variables, and `fail` in it stops the script.
3. **A `Block` can only be run, or passed straight on** to another member that takes one. It cannot be stored,
   returned or put in a list, so the compiler always knows every block.
4. **A member that takes a block cannot call itself,** directly or through others, since expanding it would never end.
   It may run its block more than once, in a loop.
5. **No `return` inside a block,** for now. Leaving the caller from inside an expanded body, through its `finally`, can
   come later.

This is the one place functions are expanded rather than called. Every other `def` stays a real function.

`within` and `step` are members like this, in the standard library. `within dir` is `Dir.runIn(dir)`: a native push of
the directory, the block, and a native pop under `finally`. `step Build` is `Script.runStep('Build')`: it announces
itself, runs the block, and leaves its name where `koine_fail` reports it. Their keyword spelling is all that is
special about them, and keywords cannot be member names, which is why the members are called something else.

### Cleanup

`finally` ends a block with cleanup that runs however the block ends: normally, through `fail`, or through a failing
command.

```
let server = Process.start(exe, log)
Http.waitFor(url)
finally
  server.stop
```

- `finally` is the last thing in its block, at the block's own indentation, and its own block follows.
- **PowerShell:** `try { … } finally { … }`.
- **Bash and Zsh:** the compiler keeps a stack of cleanups, run by an `EXIT` trap. On the normal path it pops the top
  one and runs it itself.
- `fail`, `koine_fail` and a script-level `return` run everything on the stack, innermost first.

## Records

*Proposed. Records are what reading data and members such as `Process.start` need.*

A record is a named set of typed fields, declared at the top level of a script or a module:

```
record Binary
  name: String
  source: String
```

- **Fields are read with `.`**, and the checker knows every field, so a misspelled one is a compile error.
- **A field marked `?` may be missing:** `linux: Platform?`. A missing field reads as the empty value of its type:
  `String.empty`, `0`, `false`, `[]`, or an empty record, and `.isEmpty` is true for each. A field without `?` is always
  present.
- **A record is made by naming its fields:** `Binary(name: 'app', source: 'app.exe')`. A Koine-bodied member that
  returns a record makes it this way.
- **Records are values.** Assigning or passing one copies it, and its fields cannot be changed in place.
- **A record can be a function's result**, which widens rule 5. A map still cannot.

How each shell holds one:

- **PowerShell:** a `[pscustomobject]`.
- **Bash and Zsh:** no structure at all. A record variable `repo` becomes one variable per field (`repo_branch_name`),
  and a list field one array. Every field is known when compiling, so the compiler can always name them all: to
  copy a record, to pass it as an argument, or to receive one through `reply_…`. A list of records becomes one array
  per field, side by side (`binaries_name`, `binaries_source`). A list of records that themselves hold lists does not
  fit in arrays, so for now it is a compile error on Bash and Zsh. Nanobuf files never need one, and preflight reads
  each `App` on its own.

## Reading and writing data

*Proposed. Koine's own data is [nanobuf](https://github.com/sibarum/intrabuf/blob/develop/docs/nanobuf.md), specified
in the intrabuf repository; JSON is read only where another program owns the file.*

A script's data goes through two modules, and both read into a declared record, with `as`:

```
let manifest = Nanobuf.read(manifestFile) as Manifest
Nanobuf.write(stateFile, record)
let app = Json.read(config) as App
Json.write(registryFile, entry)
```

Without `as`, a read is a compile error. Only the declared fields are read. A missing field without `?`, or a value
of the wrong type, fails the script with the file and the field: `apps/x/app.json: no field version`.

**Why records, not a dynamic value.** A dynamic value has to be carried as text, and every field read has to parse it
again. That is what the current IR does on Bash and Zsh, through `jq`. With records, the compiler knows when it
compiles exactly which fields a script reads, so the file is parsed once and the fields land in ordinary variables.

### Nanobuf: the default

Files that only Koine scripts and your own components read and write are nanobuf: install records, manifests the
installers fetch, journals, build stamps. Its reader and writer are a few lines of each shell's built-in commands, so
`Nanobuf.read` and `Nanobuf.write` are native tier on every OS, and installers can use them.

A nanobuf file is read as a record whose fields are its kinds:

```
record Manifest
  app: App
  versions: [Version]
  files: [File]
```

- A field that is a record is a kind with exactly one row, or at most one when it is marked `?`.
- A field that is a list is a kind with any number of rows.
- The records of the rows hold only strings, numbers and booleans. Nesting is by key, as in the format itself.

That fits how Bash and Zsh hold records: a list of flat records is one array per field, side by side.

### JSON: only for other programs' files

Some files are JSON because something else reads or writes them. The install registry, `<id>.json`, is read by the
applications themselves (`Apps.java` in `vexelray-framework-shell`, `Install` and `Updates` in `vex-suite-common`).
`installer.json` and `installer-suite.json` are read by the installer generator.

- **`Json.write` is native tier on every OS.** The compiler knows every field, so writing is printing, with strings
  escaped for JSON (`"`, `\` and control characters). The Linux installer template already writes the registry this
  way, from its `.state` record.
- **`Json.read` is native on Windows, bundled on Linux and Mac.** Windows has `ConvertFrom-Json`. Watch two traps in
  5.1: a top-level array arrives as one object, and an empty array as `$null`, so lists are wrapped in `@(…)`. Linux
  and Mac use `jq` from the bundle: no Linux distribution ships `jq` in every install, and macOS has had it only since
  15 (Sequoia). An installer that needs to read something on Linux or Mac reads nanobuf, as the Linux installer
  already reads `manifest.linux.txt` instead of `manifest.json`.

**What changes in the IR:** `ReadJson` and `Field` in `koine-ir` lower to `jq` on Bash and Zsh, which is not native.
They are replaced by `Json.read` in the `Json` module, bundled on those two shells.

## Example: preflight

`vexelray-dist`'s `preflight.ps1`, as ported to the portable IR in `koine-ir/src/test/java/sibarum/koine/ir/Preflight.java`,
rewritten in this language:

```
# Everything before publishing: builds the stack, starts every application, checks the notices and versions, and
# generates the installers without publishing them. Nothing is uploaded, committed or pushed. Stops at the first
# failing step. It builds and starts the Windows applications, so it is for Windows only.
script Preflight<Windows>(skipBuild = false, seconds = 8, buildOnly = false)
  let dist = Script.directory
  let root = Path.parent(dist)
  let logs = Path.join(dist, 'logs')
  Dir.create(logs)

  let configs = []
  let suite = Json.read(Path.join(dist, 'installer-suite.json')) as Suite
  for member in suite.members
    configs.add(Path.full(Path.join(dist, member.config)))

  if not skipBuild
    step Build
      # The output is checked for FAILED as well as the exit status, so a failure can never pass as a build.
      let buildCmd = Path.join(dist, 'build.cmd')
      let failed = false
      for line in {}`{buildCmd}`
        print line
        if 'FAILED' in line
          failed = true
      if failed
        fail "The build printed FAILED; see logs/"

  step Start
    let dead = []
    for config in configs
      let app = Json.read(config) as App
      for binary in app.windows.binaries
        if binary.source.endsWith('.exe')
          let exe = Path.join(Path.parent(config), binary.source)
          if not File.exists(exe)
            dead.add({}"{app.id}: no {exe}")
          else
            let log = Path.join(logs, {}"start-{binary.name}.log")
            within Path.parent(exe)
              let process = Process.start(exe, log)
              Clock.sleep(seconds)
              if process.running
                print {}"  {binary.name} up"
                process.stop
              else
                dead.add({}"{binary.name} exited within {seconds.text}s; see logs/start-{binary.name}.log.err")
    if not dead.isEmpty
      for problem in dead
        Console.error({}"  {problem}")
      fail "An application does not start"

  # The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of every
  # checkout it built. They are fit to publish only while every one of those is still checked out.
  step Linux
    let linuxApps = []
    for config in configs
      let app = Json.read(config) as App
      if not app.linux.isEmpty
        linuxApps.add(app.name)

    if linuxApps.isEmpty
      print "  no application has a linux section"
    else
      let problems = linuxBuildProblems(logs, root, configs)
      while not problems.isEmpty
        for problem in problems
          Console.warn({}"  {problem}")
        print "  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh"
        if not Console.interactive
          fail "The Linux build is missing, or not of these sources"
        Console.pause("  Press Enter once it has finished, to check again (Ctrl+C stops)")
        problems = linuxBuildProblems(logs, root, configs)
      print {}"  built in the VM from these sources: {linuxApps.join(', ')}"

  step Notices
    let python = Python.find
    if python.isEmpty
      fail "No Python found (python, py, or anaconda3)"
    let notices = Path.join(dist, 'notices', 'notices.py')
    if not try {}`{python} {notices} --check`
      fail "The notices are out of date"

  if buildOnly
    Console.success("Built, started and checked.")
    return

  step Versions
    for config in configs
      let app = Json.read(config) as App
      let manifest = Path.join(dist, 'apps', app.id, 'manifest.json')
      let latest = String.empty
      if File.exists(manifest)
        latest = (Json.read(manifest) as Manifest).latest
      if latest == app.version
        Console.warn({}"  {app.name} {app.version} is already published; bump it in {config} if it changed")
      else if latest.isEmpty
        print {}"  {app.name} {app.version} (published: none)"
      else
        print {}"  {app.name} {app.version} (published: {latest})"

  step Generate
    let deploy = Script.sibling('deploy')
    if not try {}`{deploy}`
      fail "deploy"

  step Diff
    within dist
      try `git status --short`
    let site = Path.join(root, 'sibarum.github.io')
    if File.exists(Path.join(site, '.git'))
      within site
        try `git status --short`
    Console.success("Preflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish.")

  # What is wrong with the Linux build: missing, not of the commits checked out here, or without a binary an
  # application needs. Empty when it is fit to publish.
  def linuxBuildProblems(logs, root, configs)
    let stamp = Path.join(logs, 'linux', 'BUILD.txt')
    if not File.exists(stamp)
      return ["no Linux build: logs/linux/BUILD.txt is missing"]

    let builtFrom = {}
    for line in File.lines(stamp)
      if not line.matches('^[[:space:]]*(#|$)')
        let words = line.words
        builtFrom[words[0]] = words[1]

    let problems = []
    for repo in builtFrom.keys
      let checkout = Path.join(root, repo)
      let head = {}`git -C {checkout} rev-parse HEAD` else 'nothing'
      if head != builtFrom[repo]
        problems.add({}"{repo}: the Linux build is of {builtFrom[repo].take(9)}, the checkout is at {head.take(9)}")
    for config in configs
      let app = Json.read(config) as App
      if not app.linux.isEmpty
        let repo = Path.name(Path.parent(config))
        if not builtFrom.has(repo)
          problems.add({}"{repo}: not in the Linux build")
        for binary in app.linux.binaries
          if not File.exists(Path.join(Path.parent(config), binary.source))
            problems.add({}"{app.id}: no {binary.source}")
    return problems

  # The fields of the JSON files this script reads; the files may hold more.
  record Suite
    members: [Member]

  record Member
    config: String

  record App
    id: String
    name: String
    version: String
    windows: Platform
    linux: Platform?

  record Platform
    binaries: [Binary]

  record Binary
    name: String
    source: String

  record Manifest
    latest: String
```

### What the example shows

- **It uses only the keywords above.** Everything else is a library member: `Script.directory`, `Script.sibling`,
  `Path.join`/`parent`/`full`/`name`, `Dir.create`, `File.exists`/`lines`, `Json.read`, `Process.start`,
  `Clock.sleep`, `Console.warn`/`error`/`success`/`interactive`/`pause`, and `Python.find`. `Python.find` was a helper
  function in the original; it becomes a library member because it is about the machine, not this script.
- **`within` replaces `Spawn`'s working-directory argument** and `git -C` where a block suits. The started process
  inherits the directory.
- **`for line in` a command replaces `EachLine`.** A non-zero exit still stops the script, with the step's name. The
  original instead reported `the build; see logs/`, so that message changes.
- **`cmd /c call` is gone.** How a `.cmd` file is started on PowerShell 5.1 is the compiler's job.
- **Only what inserts a value says so.** Every string and command that inserts one starts with `{}`; the rest are
  plain text. `seconds` is a number, so the message converts it with `seconds.text`, and `latest` starts as
  `String.empty`.
- **It is for Windows only, and says so in the script line:** `Preflight<Windows>`. Only `preflight.ps1` is produced,
  and nothing checks the OS at runtime.
- **`return` ends it early, successfully,** when `buildOnly` is set, so the steps after it stay at the top level.
- **A missing checkout reads as "at nothing",** as in the original: the `git rev-parse HEAD` capture falls back with
  `else 'nothing'` instead of stopping the script.
- **The Linux check is a `def`,** as it was a function in the original, called once and again after each rebuild. It
  is defined at the end, so the story reads first and the detail comes after. Its result is a list of strings, which
  rule 5 allows; the map it builds stays inside it.
- **Every JSON file is read as a record,** declared at the end next to the function. `app.linux` is marked `?` because
  not every application has a Linux section, and `app.linux.isEmpty` tests for it. These files stay JSON because the
  installer generator reads them, and preflight runs only on Windows, where `Json.read` is native.

## Open questions

1. **How a library module is written: proposed** under [Modules](#modules), with [Records](#records) and [Reading
   and writing data](#reading-and-writing-data). Still open:
   - Whether native bodies get checked, for example by running ShellCheck and PSScriptAnalyzer on the generated
     functions in the module tests.
   - Whether `Number` needs fractions. Nothing in preflight does.
   - Lists of records that hold lists, on Bash and Zsh.
2. **Functions: proposed.** `def` and `return`, with the rules under [Functions](#functions). A function can now
   return a record, and functions are shared between scripts as a module next to them. What is still open: whether a
   function can return a map.
3. **Plumbing.** Pipes and redirects are not allowed inside backticks. Writing output to a file will eventually need a
   keyword or a library member.
4. **What happens to the portable IR.** It could become this language's target, or be replaced by something much
   smaller. Listing what the preflight example actually needs from it would answer this.
5. **Capturing without failing: settled.** `else` after a capture gives a value, or `fail` with a message, if the
   command fails. See [When a capture fails](#when-a-capture-fails).
6. **Ending early with success: settled.** `return` at the script's level. See [Ending the script
   early](#ending-the-script-early).
7. **Which OSes a script supports: settled.** The script line names them, `<OS>` for all three, and `bootstrap script`
   marks an installer. The standard library covers every OS, and anything per OS is a `def` of the script's own with
   `on` and `native` bodies; there are no macros. See [Scripts and targets](#scripts-and-targets).
8. **Help text for script inputs.** The original documents each parameter. Comments could carry it, or `script` could
   take descriptions.
9. **Members and actions look alike.** `process.running` reads a value and `process.stop` does something, and without
   parentheses they look the same. Shell scripts are mostly actions.
10. **Delimiters for ordinary strings: settled.** Strings and commands are plain unless a prefix defines value
    brackets or an escape character, and both close at a run of exactly as many delimiters as opened them. See
    [Choosing delimiters](#choosing-delimiters).
11. **Colours.** Printing in colour is done through `Console` members. `print` might deserve a colour of its own, or
    it might not.
12. **`return` inside a block given to a member.** Not allowed for now; see [Members that take a
    block](#members-that-take-a-block).
