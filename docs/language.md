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
- **No escape sequences.** Commands are written exactly as they should arrive. Where a character would clash, you
  choose different delimiters instead of escaping it.
- **For internal use.** Managing utility binaries could come later as library modules. It is not a goal.

## Scripts and targets

```
script Preflight<OS>(skipBuild = false, seconds = 8, buildOnly = false)
  ...
```

`script` gives the name, the `<OS>` parameter and the inputs, with defaults. The compiler produces one script per OS:

| OS | Shell | Output |
|---|---|---|
| Windows | Windows PowerShell 5.1 (and still runs on 7.x) | `preflight.ps1` |
| Linux | Bash 4.4+ | `preflight.sh` |
| Mac | Zsh | `preflight.zsh` |

`on` branches by OS when compiling. Each output contains only its own branch, and nothing checks the OS at runtime:

```
on Windows
  `build.cmd`
on Linux, Mac
  `./build.sh`
```

## Keywords

| Keyword | Role |
|---|---|
| `script` | Declares the script: its name, `<OS>`, and its inputs with defaults. |
| `let` | Introduces a name. Plain `x = …` reassigns one that exists, so a misspelled name is an error, not a new variable. A name lives until the end of its block. |
| `if` / `else` | Branches at runtime. `else if` chains. |
| `on` | Branches at compile time, by OS. |
| `for … in` | Loops over a list, or over the lines of a command's output as they come. |
| `while` | Loops while a condition holds. |
| `within` | Runs a block in a directory and restores the previous one afterwards, even on failure. It is the only way to change the working directory. |
| `try` | Before a literal command: gives true or false instead of stopping the script. As a statement on its own: runs the command and ignores its exit status. |
| `fail` | Prints the message in red, exits non-zero, and runs any cleanup. |
| `step` | A named part of the story. It announces itself when it starts (`== Build`), and a failure inside it is reported with its name. |
| `def` | Defines a function: a quiet helper that takes arguments and can return a value. See [Functions](#functions). |
| `return` | Ends a function, optionally with its result. |
| `print` | Writes a line. |

Operators: `and`, `or`, `not`, `in` (list membership and substring), `==`, `!=`, `<`, `<=`, `>`, `>=`.

## Values

Provisional, and kept small:

- Strings in `"…"` or `'…'`, with `{expr}` inserting a value: `"{app.name} is up"`.
- Whole numbers, `true` and `false`.
- Lists `['master', 'main']` and maps `{}`, indexed with `[…]`.
- Members with `.`: `repo.branch.name`, `line.words`, `dead.add(problem)`. A member that takes no arguments is written
  without parentheses.
- Library modules are capitalised names: `Git`, `Path`, `Json`, `Process`, `Console`.

## Literal commands

A literal command goes in backticks. Where it appears decides what it does:

| Where it appears | What it does | Example |
|---|---|---|
| As a statement | Runs it; the script stops if it fails | `` `build.cmd` `` |
| As a value | Captures its output, without trailing line breaks | ``let head = `git rev-parse HEAD` `` |
| After `try` in a condition | Gives true or false | ``if not try `{python} {notices} --check` `` |
| `try` as a statement | Runs it and ignores its exit status | ``try `git status --short` `` |
| After `for … in` | Runs the body for each line of output as it comes; the script stops if it then fails | ``for line in `{buildCmd}` `` |

Unless captured, a command's output goes where the script's goes.

### Inside the backticks

- Words are literal.
- `{expr}` inserts a value, and is always exactly **one** argument, whatever it contains. A list becomes one argument
  per item. There is no word splitting.
- `'…'` and `"…"` both group words with spaces, and neither expands anything. Each can contain the other. An argument
  that needs both quote characters is built as a string and inserted with `{…}`.
- Nothing else, for now: no unquoted `|`, `>`, `<` or `&&`. Inside quotes they are just text, so
  `` `jq '.a | .b'` `` passes `.a | .b` as one argument.

The compiler re-quotes every command for its target, so none of these characters reach the shell as written. That
includes PowerShell, where the backtick is the escape character.

### Choosing delimiters

There are no escape sequences. A clash is solved by choosing other delimiters:

- **The command delimiter is a run of backticks**, as in Markdown. One is the default. A command containing a backtick
  opens and closes with two (or more), and a shorter run inside is literal:

  ```
  ``powershell -Command "Write-Host `"done`""``
  ```

- **The value brackets are `{…}` by default.** Two characters written in front of the opening backticks become the
  brackets for that command instead, for text full of braces such as `jq` filters, PowerShell script blocks or JSON:

  ```
  let names = <>`jq -r '.members[] | {name: .config}' <suiteFile>`
  ```

  Here `<suiteFile>` inserts a value, and `{name: .config}` reaches `jq` as written.

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
    fail "Run this from develop, not {repo.branch.name}"
```

## Functions

`def` defines a function and `return` gives its result:

```
def latestPublished(manifest)
  if not File.exists(manifest)
    return ''
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
   keeps recursion working, and it needs nothing newer than Bash 3.2. PowerShell returns normally, with
   `return ,$value` for a list, and the compiler puts `$null =` in front of every other line that would produce output.

   ```
   # Bash
   linuxBuildProblems() { local -a problems=(); …; reply=("${problems[@]}"); return 0; }
   linuxBuildProblems "$logs" "$root" "${configs[@]}"; problems=("${reply[@]}")
   ```

3. **Arguments are values, and every variable is local.** Bash functions can see their caller's local variables. The
   compiler makes every `let` inside a function local, so a function cannot change anything it was not passed.
4. **`fail` inside a function stops the whole script.** Rule 1 guarantees it: nothing runs in a subshell.
5. **A result is a string, a number, a boolean, a list or a record.** A map stays inside the function that builds it.
   See [Records](#records).

A function is defined at the script's top level, and can be called before the line that defines it: the compiler
moves every definition to the top of the output.

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
    if try `{candidate} --version`
      return candidate
  return ''
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
members may not.

### Tiers

A module is native tier unless its header says otherwise: `module Rg bundled`. One member of a native module can be
raised on its own: `bundled def search(…)`. The checker enforces both rules:

- A native member cannot use a bundled one.
- A bootstrap script cannot use a bundled member, directly or through other members.

### Native bodies

`native` takes the same OS list as `on`. A member's body is either Koine, or a set of branches by OS, each of which is
`on` (Koine) or `native` (shell). Every OS the script is compiled for must be covered, or the compiler says which one
is missing: `Clock.sleep has no Linux body`.

A native body is raw text. It ends where the indentation does, and nothing in it is interpreted, so it needs no
delimiters and no escaping. The compiler copies it into the function it generates, and does not check it, quote it
or put `$null =` in front of its lines. Writing it correctly for its shell is the module author's job, and the [target
notes](targets.md) are the checklist.

What a native body can rely on:

| | PowerShell | Bash, Zsh |
|---|---|---|
| Parameters | `$name`, typed: `[string]`, `[long]`, `[bool]`, `[string[]]`, or the record's object | Locals named after the parameters, unpacked by a prologue the compiler writes. Lists are arrays; a record parameter `p` is one local per field, `p_id`, `p_log`. |
| A `String`, `Number` or list result | `return $value`, `return ,$list` | Set `REPLY`, or the array `reply` |
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
```

`process.running` is `Process.running(process)`: a member written after a value is looked up in the module that
declares the value's record. The members of strings, lists and maps (`.endsWith`, `.take`, `.words`, `.join`,
`.isEmpty`, `.keys`, `.has`, `.matches`) are written the same way, in the built-in modules `String`, `List` and `Map`.
Members that change a value in place, such as `list.add(x)` and `map[key] = x`, are built into the compiler instead,
because a function cannot change what it was passed (rule 3).

## Records

*Proposed. Records are what `Json.read` and members such as `Process.start` need.*

A record is a named set of typed fields, declared at the top level of a script or a module:

```
record Binary
  name: String
  source: String
```

- **Fields are read with `.`**, and the checker knows every field, so a misspelled one is a compile error.
- **A field marked `?` may be missing:** `linux: Platform?`. A missing field reads as the empty value of its type:
  `''`, `0`, `false`, `[]`, or an empty record, and `.isEmpty` is true for each. A field without `?` is always
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
  fit in arrays, so for now it is a compile error on Bash and Zsh. Preflight never needs one: it reads each `App` on
  its own.

## Reading JSON

*Proposed. `Json.read` gives a record, not a dynamic JSON value.*

`Json.read` takes the record type to read as, with `as`:

```
let app = Json.read(config) as App
```

Without `as` it is a compile error. A top-level list is read as a list type: `Json.read(path) as [Binary]`. Only the
declared fields are read; others are ignored. A field without `?` that is missing, or a value of the wrong type, fails
the script with the file and the field: `apps/x/app.json: no field version`.

**Why records, not a dynamic value.** A dynamic value has to be carried as JSON text, and every field read has to parse
it again. That is what the current IR does on Bash and Zsh, through `jq`. With records, the compiler knows when it
compiles exactly which paths a script reads, so the file is parsed once and the fields land in ordinary variables.

### What it needs on each OS

`Json.read` must be native tier: installers use it, and they run before the bundle is installed.

- **Windows:** `ConvertFrom-Json`, then a check of the declared fields. Watch two traps in 5.1: a top-level array
  arrives as one object, and an empty array as `$null`, so lists are wrapped in `@(…)`.
- **Linux and Mac: not `jq`.** No Linux distribution ships `jq` in every install, and macOS has only had it since 15
  (Sequoia). Python is not guaranteed either. `awk` is: every Linux has one (gawk, mawk or BusyBox), and so does
  macOS (BWK awk). So `Json.read` uses one small JSON reader written in POSIX awk, emitted once per script. It turns a
  file into `path<TAB>value` lines, and the generated code reads the declared paths from them into the record's
  variables.

What the awk reader needs:

- Only features common to all four awks: no gawk extensions, no `length(array)` assumptions, no regex intervals.
- Strings with `\"`, `\\`, `\/`, `\n`, `\t` and `\uXXXX`, the last encoded to UTF-8 bytes by arithmetic, since
  `sprintf("%c")` is not portable above 127. Surrogate pairs can come later.
- Paths that cannot be confused with values: list items by index, and keys that contain `.` or a tab kept intact.
- Numbers checked against `Number`: a whole number is accepted, a fraction or exponent fails the read.
- `null` read as missing.
- A clear error with the file name for malformed JSON, not empty fields.

**What changes in the IR:** `ReadJson` and `Field` in `koine-ir` lower to `jq` on Bash and Zsh, so they break the
native tier. They are replaced by `Json.read` in the `Json` module, with the awk reader as its native body on Linux and
Mac.

## Example: preflight

`vexelray-dist`'s `preflight.ps1`, as ported to the portable IR in `koine-ir/src/test/java/sibarum/koine/ir/Preflight.java`,
rewritten in this language:

```
# Everything before publishing: builds the stack, starts every application, checks the notices and versions, and
# generates the installers without publishing them. Nothing is uploaded, committed or pushed. Stops at the first
# failing step.
script Preflight<OS>(skipBuild = false, seconds = 8, buildOnly = false)
  on Linux, Mac
    fail "Preflight runs on Windows: it builds and starts the Windows applications"

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
      for line in `{buildCmd}`
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
            dead.add("{app.id}: no {exe}")
          else
            let log = Path.join(logs, "start-{binary.name}.log")
            within Path.parent(exe)
              let process = Process.start(exe, log)
              Clock.sleep(seconds)
              if process.running
                print "  {binary.name} up"
                process.stop
              else
                dead.add("{binary.name} exited within {seconds}s; see logs/start-{binary.name}.log.err")
    if not dead.isEmpty
      for problem in dead
        Console.error("  {problem}")
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
          Console.warn("  {problem}")
        print "  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh"
        if not Console.interactive
          fail "The Linux build is missing, or not of these sources"
        Console.pause("  Press Enter once it has finished, to check again (Ctrl+C stops)")
        problems = linuxBuildProblems(logs, root, configs)
      print "  built in the VM from these sources: {linuxApps.join(', ')}"

  step Notices
    let python = Python.find
    if python.isEmpty
      fail "No Python found (python, py, or anaconda3)"
    let notices = Path.join(dist, 'notices', 'notices.py')
    if not try `{python} {notices} --check`
      fail "The notices are out of date"

  if buildOnly
    Console.success("Built, started and checked.")
  else
    step Versions
      for config in configs
        let app = Json.read(config) as App
        let manifest = Path.join(dist, 'apps', app.id, 'manifest.json')
        let latest = ''
        if File.exists(manifest)
          latest = (Json.read(manifest) as Manifest).latest
        if latest == app.version
          Console.warn("  {app.name} {app.version} is already published; bump it in {config} if it changed")
        else if latest.isEmpty
          print "  {app.name} {app.version} (published: none)"
        else
          print "  {app.name} {app.version} (published: {latest})"

    step Generate
      let deploy = Script.sibling('deploy')
      if not try `{deploy}`
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
      let head = `git -C {checkout} rev-parse HEAD`
      if head != builtFrom[repo]
        problems.add("{repo}: the Linux build is of {builtFrom[repo].take(9)}, the checkout is at {head.take(9)}")
    for config in configs
      let app = Json.read(config) as App
      if not app.linux.isEmpty
        let repo = Path.name(Path.parent(config))
        if not builtFrom.has(repo)
          problems.add("{repo}: not in the Linux build")
        for binary in app.linux.binaries
          if not File.exists(Path.join(Path.parent(config), binary.source))
            problems.add("{app.id}: no {binary.source}")
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
- **Behaviour that changed:** the original captured `git rev-parse HEAD` ignoring its status, and reported a missing
  checkout as "at nothing". Here a capture that fails stops the script. See open question 5.
- **The Linux check is a `def`,** as it was a function in the original, called once and again after each rebuild. It
  is defined at the end, so the story reads first and the detail comes after. Its result is a list of strings, which
  rule 5 allows; the map it builds stays inside it.
- **Every JSON file is read as a record,** declared at the end next to the function. `app.linux` is marked `?` because
  not every application has a Linux section, and `app.linux.isEmpty` tests for it.

## Open questions

1. **How a library module is written: proposed** under [Modules](#modules), with [Records](#records) and [Reading
   JSON](#reading-json). Still open:
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
5. **Capturing without failing.** `try` gives a bool, so there is no way yet to capture output and ignore the exit
   status, as the original's `git rev-parse` did.
6. **Ending early with success.** `buildOnly` wraps the rest of the script in an `else`, because there is no keyword
   for "stop here, successfully".
7. **Which OSes a script supports.** Preflight is Windows-only and says so with `on Linux, Mac` and `fail`. It might be
   better to name the supported OSes in the script line, so the other outputs are never produced.
8. **Help text for script inputs.** The original documents each parameter. Comments could carry it, or `script` could
   take descriptions.
9. **Members and actions look alike.** `process.running` reads a value and `process.stop` does something, and without
   parentheses they look the same. Shell scripts are mostly actions.
10. **Delimiters for ordinary strings.** Strings use `{…}` too. Whether they take the same two-character prefix as
    backticks is not settled.
11. **Colours.** Printing in colour is done through `Console` members. `print` might deserve a colour of its own, or
    it might not.
