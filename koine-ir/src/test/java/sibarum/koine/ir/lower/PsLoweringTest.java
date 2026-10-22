package sibarum.koine.ir.lower;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.FunctionSig;
import sibarum.koine.ir.PreflightAccess;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Statement.*;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.ps.PsPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.koine.ir.Type.*;

class PsLoweringTest {

    /** The ported preflight lowers to expected/preflight.ps1. */
    @Test
    void preflightLowersAsExpected() throws Exception {
        assertEquals(expected("expected/preflight.ps1"), PsPrinter.print(PsLowering.lower(PreflightAccess.script())));
    }

    /**
     * A script of one statement per construct lowers to expected/probe.ps1: maps that keep case and order, lists
     * returned from functions, JSON fields that may be missing, Test-Succeeds for quiet and missing programs, a line
     * loop with its status, a capture, paths, and an exit status.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.ps1"), PsPrinter.print(PsLowering.lower(probe())));
    }

    private static Script probe() {
        Var jsonPath = new Var("Json", STRING);
        Var m = new Var("m", mapOf(STRING));
        Var words = new Var("words", listOf(STRING));
        Var doc = new Var("doc", JSON);
        Var item = new Var("item", JSON);
        Var ids = new Var("ids", listOf(STRING));
        Var line = new Var("line", STRING);
        Var lines = new Var("lines", INT);
        Var status = new Var("status", INT);
        Var version = new Var("version", STRING);
        FunctionSig none = new FunctionSig("Get-None", List.of(), Optional.of(listOf(STRING)));
        FunctionSig one = new FunctionSig("Get-One", List.of(), Optional.of(listOf(STRING)));
        FunctionSig two = new FunctionSig("Get-Two", List.of(), Optional.of(listOf(STRING)));
        Command gitVersion = new Command(s("git"), List.of(s("--version")), false);

        return new Script("probe", "Tries each portable construct.", "", List.of(
                new Script.Param(jsonPath, Optional.empty(), "A JSON file to read.")), List.of(
                new Set(m, new EmptyMap(STRING)),
                new Put(m, s("B"), s("1")),
                new Put(m, s("b"), s("2")),
                print("map: ", new Count(ref(m)), " ", new Join(new Keys(ref(m)), s(","))),
                new Set(words, new Words(s("  a  b c "))),
                print("words: ", new Count(ref(words)), " ", new At(ref(words), i(1))),
                print("take: ", new Take(s("abcdef"), i(3)), " ", new Take(s("ab"), i(9))),
                print("match: ", new Choose(new Matches(s("  # x"), "^[[:space:]]*(#|$)"), s("comment"), s("code"))),
                print("case: ", new Choose(new Compare(s("A"), Comparison.EQ, s("a")), s("same"), s("different"))),
                new Function(none, List.of(new Return(Optional.of(new ListLit(STRING, List.of()))))),
                new Function(one, List.of(new Return(Optional.of(new ListLit(STRING, List.of(s("x"))))))),
                new Function(two, List.of(new Return(Optional.of(new ListLit(STRING, List.of(s("x"), s("y"))))))),
                print("none ", new Count(new Call(none, List.of())), ", one ", new Count(new Call(one, List.of())),
                        ", two ", new Count(new Call(two, List.of()))),
                new Set(doc, new ReadJson(ref(jsonPath))),
                new Set(ids, new ListLit(STRING, List.of())),
                new ForEach(item, new Field(ref(doc), List.of("items"), listOf(JSON)), List.of(
                        new Append(ids, new Field(ref(item), List.of("id"), STRING)))),
                new ForEach(item, new Field(ref(doc), List.of("missing", "items"), listOf(JSON)), List.of(
                        new Append(ids, s("never")))),
                print("json: ", new Field(ref(doc), List.of("name"), STRING), " [", new Field(ref(doc), List.of("nothing"), STRING), "] ",
                        new Join(ref(ids), s(" "))),
                print("missing field empty: ", new Choose(new IsEmpty(new Field(ref(doc), List.of("linux"), JSON)), s("True"), s("False"))),
                print("probe git: ", yesNo(new Succeeds(new Command(s("git"), List.of(s("--version")), true)))),
                print("failing git: ", yesNo(new Succeeds(new Command(s("git"), List.of(s("no-such-subcommand")), true)))),
                print("missing program: ", yesNo(new Succeeds(new Command(s("no-such-program-koine"), List.of(), true)))),
                new Set(lines, i(0)),
                new EachLine(gitVersion, line, List.of(new Set(lines, i(1))), status),
                print("lines: ", ref(lines), " status ", ref(status)),
                new Set(version, new Capture(gitVersion)),
                print("capture: ", new Take(ref(version), i(11))),
                print("path: ", new FileName(new PathJoin(s("a"), List.of(s("b"), s("c.txt")))), " ",
                        new FileName(new Parent(new PathJoin(s("a"), List.of(s("b"), s("c.txt")))))),
                print("interp: ", new Count(ref(ids)), " items, ", new Count(new Keys(ref(m))), " keys"),
                new Exit(i(7))));
    }

    /**
     * A script of programs called the way Windows PowerShell 5.1 needs lowers to expected/natives.ps1: 7.3's
     * preferences set back to 5.1's, aliases called with .exe, arguments escaped (literals at once, the rest through
     * ConvertTo-NativeArgument) but not for cmd or a script, output read as UTF-8, a full path from PowerShell's
     * location, a chosen list kept a list, and EREs that keep their meaning in .NET.
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
        Var picked = new Var("picked", listOf(STRING));
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
                print("sort: ", yesNo(new CommandExists(s("Sort")))),
                new Run(new Command(s("cmd"), List.of(s("/c"), s("echo \"as it is\"")), false), OnFailure.ABORT),
                new Run(new Command(new ScriptPath("helper"), List.of(s("a b"), ref(quoted)), false), OnFailure.ABORT),
                print("status: ", yesNo(new Succeeds(new Command(s("git"), List.of(s("-C"), ref(dir), s("status")), true)))),
                new Set(version, new Capture(new Command(s("git"), List.of(s("--version")), false))),
                print("full path: ", new FullPath(s("x.txt"))),
                new Set(picked, new Choose(new IsEmpty(ref(empty)), new ListLit(STRING, List.of(s("only"))), new ListLit(STRING, List.of()))),
                print("picked: ", new Count(ref(picked))),
                print("end: ", yesNo(new Matches(s("a\n"), "a$")), " dot: ", yesNo(new Matches(s("a\nb"), "^a.b$")),
                        " backslash: ", yesNo(new Matches(s("x\\y"), "[\\]")), " bracket: ", yesNo(new Matches(s("]"), "^[]a]$")))));
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

    private static Expr yesNo(Expr condition) {
        return new Choose(condition, s("True"), s("False"));
    }

    private static Statement print(Object... parts) {
        return new Print(new Interp(Arrays.stream(parts).map(p -> p instanceof String t ? s(t) : (Expr) p).toList()), Statement.Color.DEFAULT);
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
