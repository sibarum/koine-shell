package sibarum.koine.ir.lower;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.PreflightAccess;
import sibarum.koine.ir.Probe;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Statement.*;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.ps.PsPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.koine.ir.Type.*;

class PsLoweringTest {

    /**
     * Preflight, built in the IR from preflight.koine and the standard library's Windows bodies, lowers to
     * expected/preflight.ps1.
     */
    @Test
    void preflightLowersAsExpected() throws Exception {
        assertEquals(expected("expected/preflight.ps1"), PsPrinter.print(PsLowering.lower(PreflightAccess.script())));
    }

    /**
     * The probe, with the standard library's Windows bodies, lowers to expected/probe.ps1: native functions with typed
     * parameters, maps that keep case and order, lists returned from functions, records, data read as a record through
     * koine_as_Doc, Test-Succeeds for quiet and missing programs, a line loop with its status, captures with each
     * fallback, finally as try/finally, and koine_fail.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.ps1"), PsPrinter.print(PsLowering.lower(Probe.script(OS.Windows))));
    }

    /**
     * A script of programs called the way Windows PowerShell 5.1 needs lowers to expected/natives.ps1: 7.3's
     * preferences set back to 5.1's, aliases called with .exe, arguments escaped (literals at once, the rest through
     * ConvertTo-NativeArgument) but not for cmd, $LASTEXITCODE reset before a program named by a value, output read
     * as UTF-8, and EREs that keep their meaning in .NET.
     */
    @Test
    void nativesLowerAsExpected() throws Exception {
        assertEquals(expected("expected/natives.ps1"), PsPrinter.print(PsLowering.lower(natives())));
    }

    private static Script natives() {
        Var dir = new Var("Dir", STRING);
        Var quoted = new Var("quoted", STRING);
        Var empty = new Var("empty", STRING);
        Var several = new Var("several", listOf(STRING));
        Var count = new Var("count", INT);
        Var version = new Var("version", STRING);
        Var git = new Var("git", STRING);
        Command sqQuote = new Command(s("git"), List.of(s("rev-parse"), s("--sq-quote"), s(""), s("a\"b"), s("C:\\dir name\\"),
                s("say \"hi there\""), ref(quoted), ref(empty), ref(several), ref(count)), false);

        return new Script("natives", "Calls programs as Windows PowerShell 5.1 needs.", "", List.of(
                new Script.Param(dir, Optional.empty(), "A git checkout.")), List.of(
                new Set(quoted, s("say \"hi there\"")),
                new Set(empty, s("")),
                new Set(several, new ListLit(STRING, List.of(s("a b"), s(""), s("c\"d")))),
                new Set(count, i(3)),
                new Run(sqQuote, OnFailure.ABORT),
                new Run(new Command(s("curl"), List.of(s("--version")), false), OnFailure.ABORT),
                new Run(new Command(s("where"), List.of(s("git")), false), OnFailure.IGNORE),
                new Run(new Command(s("cmd"), List.of(s("/c"), s("echo \"as it is\"")), false), OnFailure.ABORT),
                new Set(git, s("git")),
                new Run(new Command(ref(git), List.of(s("a b"), ref(quoted)), false), OnFailure.ABORT),
                yesNo("status: ", new Succeeds(new Command(s("git"), List.of(s("-C"), ref(dir), s("status")), true))),
                new Set(version, new Capture(new Command(s("git"), List.of(s("--version")), false), new Fallback.Stop())),
                yesNo("end: ", new Matches(s("a\n"), "a$")),
                yesNo("dot: ", new Matches(s("a\nb"), "^a.b$")),
                yesNo("backslash: ", new Matches(s("x\\y"), "[\\]")),
                yesNo("bracket: ", new Matches(s("]"), "^[]a]$"))));
    }

    /** Each argument comes back from a .NET Framework program as it was, through Windows PowerShell 5.1. */
    @Test
    void nativeArgumentsAreEscapedForWindowsPowerShell() {
        assertEquals("\"\"", PsLowering.nativeArgument(""));
        assertEquals("plain", PsLowering.nativeArgument("plain"));
        assertEquals("a\\\"b", PsLowering.nativeArgument("a\"b"));
        assertEquals("a \\\"b\\\" c", PsLowering.nativeArgument("a \"b\" c"));
        assertEquals("C:\\dir name\\\\", PsLowering.nativeArgument("C:\\dir name\\"));
        assertEquals("\\\\server\\share x\\\\\\\\", PsLowering.nativeArgument("\\\\server\\share x\\\\"));
        assertEquals("say \\\"hi there\\\"", PsLowering.nativeArgument("say \"hi there\""));
        assertEquals(" ", PsLowering.nativeArgument(" "));
        // an odd number of " before every space: quoted here, with "" for each "
        assertEquals("\"a\"\"b c\"", PsLowering.nativeArgument("a\"b c"));
        assertEquals("\"\"\"hi there\"\"\"", PsLowering.nativeArgument("\"hi there\""));
        assertEquals("\"x\\\\\"\"y z\"", PsLowering.nativeArgument("x\\\"y z"));
    }

    private static Statement yesNo(String label, Expr condition) {
        return new If(condition, List.of(new Print(s(label + "True"))), List.of(new Print(s(label + "False"))));
    }

    private static StringLit s(String value) {
        return new StringLit(value);
    }

    private static IntLit i(long value) {
        return new IntLit(value);
    }

    private static Ref ref(Var v) {
        return new Ref(v);
    }

    /** A test resource's text, with LF line breaks whatever the checkout gave it. */
    private static String expected(String name) throws Exception {
        Path p = Path.of(PsLoweringTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
