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
import sibarum.koine.ir.Var;
import sibarum.koine.ir.bash.BashPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sibarum.koine.ir.Type.*;

class BashLoweringTest {

    /** The ported preflight lowers to expected/preflight.sh. */
    @Test
    void preflightLowersAsExpected() throws Exception {
        assertEquals(expected("expected/preflight.sh"), BashPrinter.print(BashLowering.lower(PreflightAccess.script())));
    }

    /**
     * A script of one statement per construct lowers to expected/probe.sh: string, int and flag options, maps and
     * their copies, lists returned from functions and passed to them, JSON fields that may be missing, quiet and
     * missing programs, a line loop with its status, a capture, paths, an environment variable, a loop left with
     * break, commands that must succeed, and an exit status.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.sh"), BashPrinter.print(BashLowering.lower(probe())));
    }

    private static Script probe() {
        Var jsonPath = new Var("Json", STRING);
        Var times = new Var("Times", INT);
        Var loud = new Var("Loud", BOOL);
        Var m = new Var("m", mapOf(STRING));
        Var copy = new Var("copy", mapOf(STRING));
        Var words = new Var("words", listOf(STRING));
        Var doc = new Var("doc", JSON);
        Var item = new Var("item", JSON);
        Var ids = new Var("ids", listOf(STRING));
        Var line = new Var("line", STRING);
        Var lines = new Var("lines", INT);
        Var status = new Var("status", INT);
        Var version = new Var("version", STRING);
        Var who = new Var("who", STRING);
        Var names = new Var("names", listOf(STRING));
        Var name = new Var("name", STRING);
        Var greetings = new Var("greetings", listOf(STRING));
        Var n = new Var("n", INT);
        Var big = new Var("big", BOOL);
        FunctionSig none = new FunctionSig("Get-None", List.of(), Optional.of(listOf(STRING)));
        FunctionSig one = new FunctionSig("Get-One", List.of(), Optional.of(listOf(STRING)));
        FunctionSig two = new FunctionSig("Get-Two", List.of(), Optional.of(listOf(STRING)));
        FunctionSig greet = new FunctionSig("Get-Greetings", List.of(who, names), Optional.of(listOf(STRING)));
        Command gitVersion = new Command(s("git"), List.of(s("--version")), false);

        return new Script("probe", "Tries each portable construct.", "", List.of(
                new Script.Param(jsonPath, Optional.empty(), "A JSON file to read."),
                new Script.Param(times, Optional.of(i(2)), "How many times."),
                new Script.Param(loud, Optional.empty(), "Say more.")), List.of(
                new Set(m, new EmptyMap(STRING)),
                new Put(m, s("B"), s("1")),
                new Put(m, s("b"), s("2")),
                print("map: ", new Count(ref(m)), " ", new Join(new Keys(ref(m)), s(","))),
                new Set(copy, ref(m)),
                new Put(copy, s("c"), s("3")),
                print("copy: ", new Count(ref(copy)), " of ", new Count(new Keys(ref(m))), ", ",
                        new Choose(new HasKey(ref(copy), s("b")), s("has b"), s("no b"))),
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
                new Function(greet, List.of(
                        new Set(greetings, new ListLit(STRING, List.of())),
                        new ForEach(name, ref(names), List.of(new Append(greetings, new Interp(List.of(ref(who), s(" "), ref(name)))))),
                        new Return(Optional.of(ref(greetings))))),
                print("greet: ", new Join(new Call(greet, List.of(s("hi"), new ListLit(STRING, List.of(s("a b"), s("c"))))), s("; "))),
                new Set(doc, new ReadJson(ref(jsonPath))),
                new Set(ids, new ListLit(STRING, List.of())),
                new ForEach(item, new Field(ref(doc), List.of("items"), listOf(JSON)), List.of(
                        new Append(ids, new Field(ref(item), List.of("id"), STRING)))),
                new ForEach(item, new Field(ref(doc), List.of("missing", "items"), listOf(JSON)), List.of(
                        new Append(ids, s("never")))),
                print("json: ", new Field(ref(doc), List.of("name"), STRING), " [", new Field(ref(doc), List.of("nothing"), STRING), "] ",
                        new Join(ref(ids), s(" ")), " ", new Field(ref(doc), List.of("count"), INT)),
                print("missing field empty: ", new Choose(new IsEmpty(new Field(ref(doc), List.of("linux"), JSON)), s("True"), s("False"))),
                print("flag field: ", yesNo(new Field(ref(doc), List.of("on"), BOOL))),
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
                print("env: ", new Choose(new IsEmpty(new Env("HOME")), s("no HOME"), s("HOME set"))),
                new Set(n, i(0)),
                new While(new BoolLit(true), List.of(new Set(n, i(3)), new Break())),
                new Set(big, new And(new Compare(new Count(ref(words)), Comparison.GT, i(2)),
                        new Or(ref(loud), new Not(new Compare(ref(times), Comparison.LT, ref(n)))))),
                new If(ref(big), List.of(print("big after ", ref(n))), List.of(print("small"))),
                new If(ref(loud), List.of(new Print(s("loud"), Statement.Color.YELLOW)), List.of()),
                new Run(new Command(s("git"), List.of(s("--version")), true), OnFailure.ABORT),
                new Run(gitVersion, OnFailure.ABORT),
                print("interp: ", new Count(ref(ids)), " items, ", new Count(new Keys(ref(m))), " keys"),
                new Exit(i(7))));
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
        Path p = Path.of(BashLoweringTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
