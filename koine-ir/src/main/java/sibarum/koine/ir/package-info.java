/**
 * The portable Koine IR: what a script means, independent of any one shell. It lowers to the PowerShell
 * ({@code sibarum.koine.ir.ps}), Bash ({@code sibarum.koine.ir.bash}) and Zsh ({@code sibarum.koine.ir.zsh})
 * IRs, each of which prints as its shell's own syntax. A script is built for one OS: the standard library's members it
 * uses come as {@link sibarum.koine.ir.Statement.NativeFunction}s holding that OS's bodies. {@code docs/ir.md} says what
 * each node is for.
 */
package sibarum.koine.ir;
