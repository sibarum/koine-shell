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
- **Not doing:** function inlining (functions are real functions, and only the module functions a script uses are
  emitted) and macros.

## The plan

1. **Settle what blocks installers,** in [language.md](language.md):
   - How a module is written, and how a member declares its tier (open question 1). Everything else rests on this.
     *Proposed:* Koine `def`s with per-OS `native` bodies, a tier in the module header, and records.
   - What reading data returns. *Proposed:* a declared record. Koine's own files are
     [nanobuf](https://github.com/sibarum/intrabuf/blob/develop/docs/nanobuf.md), native on every OS; `Json.read` is
     native only on Windows, and `Json.write` everywhere, for files other programs own.
   - Cleanup that runs even on failure. `within` already promises it.
   - Capturing output without failing, and ending early with success (open questions 5 and 6).
   - Declaring the OSes a script supports, and marking it as a bootstrap script (open question 7).
2. **A parser and checker** for the language, with preflight as the first test.
3. **Settle the IR.** Map preflight onto the portable IR in `koine-ir`, keep what it uses, and add or drop the rest.
   Nodes that the language makes library members (`Print` with a colour, `Pause`, `ScriptPath`, `ReadJson`, `Field`,
   `Spawn`, `IsInteractive` and the like) move into native-tier modules, and their per-shell code with them.
4. **The native-tier modules preflight needs:** `Path`, `File`, `Dir`, `Json`, `Process`, `Console`, `Script`,
   `Python`. The installer will add `Nanobuf`.
5. **Compile preflight for all three shells.** Compare against the expected outputs in the tests and the original
   `preflight.ps1`, then run it for real.
6. **Port a real installer as a bootstrap script.** This is the milestone.

## After that

- **The bundled tier:** choosing the utilities, shipping them, and the modules that use them.
- **The REPL:** a pseudo-terminal per OS, statement-at-a-time translation, line editing and completion, and a toggle
  that shows each statement's translation. Bare commands at the prompt are not planned: with the bundle, the library
  covers what backticks would.
