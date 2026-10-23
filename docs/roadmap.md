# Roadmap

Koine is designed from the top down: the language first ([language.md](language.md)), then the IR it needs, then
the native code. The first use is deployment and installer scripts; a REPL comes after.

## What is settled

- **The language:** its keywords, literal commands in backticks, choose-your-own delimiters, and `def` for functions.
- **One OS, its native shell:** a script compiles for Windows (PowerShell 5.1), Linux (Bash 4.4+) or Mac (Zsh).
- **One translator.** Scripts and the REPL both go through it. The REPL translates each statement and runs it in a
  native shell in the background; it does not interpret.
- **A REPL session is always valid as a script.**
- **Two library tiers.** The *native* tier needs only what the OS ships. The *bundled* tier may use utilities shipped
  with Koine (uutils, ripgrep, jq and the like). Installers run on machines that do not have the bundle yet, so they
  may use only the native tier, and the compiler refuses a bootstrap script that uses a bundled member.
- **The bottom layer stays:** the PowerShell, Bash and Zsh IRs (`koine-ir-ps`, `koine-ir-bash`,
  `koine-ir-zsh`) and their printers. What each shell needs from them is in [targets.md](targets.md).
- **Everything is cross-platform.** Every standard-library member covers all three OSes. A script narrows its OSes in
  its script line, and anything done per OS is a `def` of its own with `on` and `native` bodies.
- **Not doing:** macros, or function inlining in general (functions are real functions, and only the module functions
  a script uses are emitted). The one exception is a member that takes a block, which is expanded where it is called,
  in the syntax tree.

## The plan

1. **Settle what blocks installers,** in [language.md](language.md):
   - How a module is written, and how a member declares its tier (open question 1). Everything else rests on this.
     *Proposed:* Koine `def`s with per-OS `native` bodies, a tier in the module header, and records.
   - What reading data returns. *Proposed:* a declared record. Koine's own files are
     [nanobuf](https://github.com/sibarum/intrabuf/blob/develop/docs/nanobuf.md), native on every OS; `Json.read` is
     native only on Windows, and `Json.write` everywhere, for files other programs own.
   - Cleanup that runs even on failure. *Settled:* `finally`, which also lets members take a block; `within` and
     `step` become such members.
   - Capturing output without failing, and ending early with success (open questions 5 and 6). *Settled:* `else`
     after a capture, and `return` at the script's level.
   - Declaring the OSes a script supports, and marking it as a bootstrap script (open question 7). *Settled:*
     `script Preflight<Windows>`, `bootstrap script`.
2. **A parser and checker** for the language, with preflight as the first test. *Both are done:* `koine-lang` reads
   scripts and modules into a syntax tree, and `Checker` checks them: names and scopes, types (with a script's untyped
   `def`s taking theirs from their first call), what goes where, that every OS has a body, the two tier rules, and
   blocks. Preflight, the `Python`, `Clock` and `Process` examples and the whole standard library check clean. Not
   checked yet:
   - that a `def` with a result returns on every path;
   - native bodies, which stay the module author's job (open question 1);
   - `within` and `step` as members of the standard library. The checker still treats them as keywords, which they
     will stay in how they are written.

   `Checker.analyse` also keeps what the compiler needs: each expression's type, each declaration's, and what each
   name, member and call resolved to, in `Checked`.
3. **Settle the IR.** *Done:* [ir.md](ir.md) gives every construct in preflight its node, and every node its fate.
   `koine-ir` has native functions, records, `Finally`, `Fail`, `In` and captures that say what a failure does, and
   the nodes that are now library members are gone from it and its lowerings. Preflight is built in the IR from the
   standard library's Windows bodies and lowers to PowerShell that parses; a probe of every construct runs, and prints
   the same, on PowerShell and Bash. What it leaves open is at the end of ir.md.
4. **The native-tier modules preflight needs:** `Path`, `File`, `Dir`, `Json`, `Process`, `Console`, `Script`,
   `Python`. *Written* in `koine-lang/src/main/resources/sibarum/koine/std`, with `Clock`, `Home`, `String`, `Number`
   and `List`. The probe runs the PowerShell and Bash bodies of `String`, `List`, `Path`, `File`, `Number`, `Json`
   (PowerShell only) and `Console.warn`; the rest run first in step 5. The installer will add `Nanobuf`.
5. **Compile preflight.** *Compiled:* `koine-compiler` takes the checked tree to the IR for one OS: it picks `on`
   and `native` for the OS, emits the library members a script uses in the order it first uses them, expands members
   that take a block, `within` and `step` among them, and reads data into records. `preflight.koine` compiles to
   exactly the IR `Preflight.java` in `koine-ir`'s tests builds by hand. A smaller script, `hello.koine` in its tests,
   compiled for Windows and for Linux, runs and prints the same on PowerShell and Bash. *Run:* the compiled
   `preflight.ps1` ran for real beside the original, and did the same: the same steps, exit statuses and generated
   files. The differences are listed on [the museum's plaque](../museum/preflight/README.md#conservation-notes).
6. **Port a real installer as a bootstrap script.** This is the milestone.

## After that

- **The bundled tier:** choosing the utilities, shipping them, and the modules that use them.
- **The REPL:** a pseudo-terminal per OS, statement-at-a-time translation, line editing and completion, and a toggle
  that shows each statement's translation. Bare commands at the prompt are not planned: with the bundle, the library
  covers what backticks would.
