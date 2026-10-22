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
import sibarum.koine.ir.zsh.ZshPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.Type.*;

class ZshLoweringTest {

    /** The ported preflight lowers to expected/preflight.zsh. */
    @Test
    void preflightLowersAsExpected() throws Exception {
        assertEquals(expected("expected/preflight.zsh"), ZshPrinter.print(ZshLowering.lower(PreflightAccess.script())));
    }

    /**
     * A script of one statement per construct lowers to expected/probe.zsh: option parsing, 1-based indexes, maps,
     * functions that return a list, a map or a bool, a call in a loop's condition, JSON fields that may be missing,
     * quiet and missing programs, a line loop with its status, a spawned process, paths, and an exit status.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.zsh"), ZshPrinter.print(ZshLowering.lower(probe())));
    }

    @Test
    void zshArraysDoNotNest() {
        Var lists = new Var("lists", listOf(listOf(STRING)));
        Script s = new Script("nested", "", "", List.of(), List.of(
                new Set(lists, new ListLit(listOf(STRING), List.of(new ListLit(STRING, List.of(s("a"))))))));
        assertThrows(IllegalArgumentException.class, () -> ZshLowering.lower(s));
    }

    static Script probe() {
        Var jsonPath = new Var("Json", STRING);
        Var verbose = new Var("Verbose", BOOL);
        Var count = new Var("Count", INT);
        Var m = new Var("m", mapOf(STRING));
        Var words = new Var("words", listOf(STRING));
        Var i = new Var("i", INT);
        Var doc = new Var("doc", JSON);
        Var item = new Var("item", JSON);
        Var ids = new Var("ids", listOf(STRING));
        Var line = new Var("line", STRING);
        Var lines = new Var("lines", INT);
        Var status = new Var("status", INT);
        Var version = new Var("version", STRING);
        Var text = new Var("text", STRING);
        Var word = new Var("word", STRING);
        Var pairs = new Var("pairs", mapOf(STRING));
        Var flag = new Var("flag", BOOL);
        Var grown = new Var("grown", STRING);
        Var proc = new Var("proc", PROCESS);
        FunctionSig none = new FunctionSig("Get-None", List.of(), Optional.of(listOf(STRING)));
        FunctionSig two = new FunctionSig("Get-Two", List.of(), Optional.of(listOf(STRING)));
        FunctionSig isShort = new FunctionSig("Is-Short", List.of(text), Optional.of(BOOL));
        FunctionSig grow = new FunctionSig("Grow", List.of(text), Optional.of(STRING));
        FunctionSig pairsOf = new FunctionSig("Get-Pairs", List.of(), Optional.of(mapOf(STRING)));
        FunctionSig walk = new FunctionSig("Walk", List.of(text, words), Optional.empty());
        FunctionSig here = new FunctionSig("Here", List.of(), Optional.of(STRING));
        Command gitVersion = new Command(s("git"), List.of(s("--version")), false);

        return new Script("probe", "Tries each portable construct.", "", List.of(
                new Script.Param(jsonPath, Optional.empty(), "A JSON file to read."),
                new Script.Param(verbose, Optional.empty(), "Say more."),
                new Script.Param(count, Optional.of(i(3)), "How many.")), List.of(
                new Set(m, new EmptyMap(STRING)),
                new Put(m, s("B"), s("1")),
                new Put(m, s("b"), s("2")),
                print("map: ", new Count(ref(m)), " ", new Join(new Keys(ref(m)), s(",")), " ", new Get(ref(m), s("b")),
                        " ", new Choose(new HasKey(ref(m), s("B")), s("has B"), s("no B"))),
                new Set(words, new Words(s("  a  b c "))),
                new Set(i, i(2)),
                print("words: ", new Count(ref(words)), " ", new At(ref(words), i(1)), " ", new At(ref(words), ref(i)),
                        " ", new Join(ref(words), s(":"))),
                print("take: ", new Take(s("abcdef"), i(3)), " ", new Take(s("ab"), i(9))),
                print("match: ", new Choose(new Matches(s("  # x"), "^[[:space:]]*(#|$)"), s("comment"), s("code"))),
                print("case: ", new Choose(new Compare(s("A"), Comparison.EQ, s("a")), s("same"), s("different"))),
                print("few: ", new Choose(new And(new Compare(new Count(ref(words)), Comparison.LT, ref(count)),
                        new Not(new Or(ref(verbose), new IsEmpty(ref(words))))), s("yes"), s("no"))),
                new Set(flag, new And(new Not(new IsEmpty(ref(words))), new Not(ref(verbose)))),
                new Comment("Lists, a bool, a map and nothing, returned from functions."),
                new Function(none, List.of(new Return(Optional.of(new ListLit(STRING, List.of()))))),
                new Function(two, List.of(new Return(Optional.of(new ListLit(STRING, List.of(s("x"), s("y"))))))),
                new Function(isShort, List.of(new Return(Optional.of(new Compare(new Take(ref(text), i(3)), Comparison.EQ, ref(text)))))),
                new Function(grow, List.of(new Return(Optional.of(new Interp(List.of(ref(text), s("x"))))))),
                new Function(pairsOf, List.of(new Set(pairs, new EmptyMap(STRING)), new Put(pairs, s("k"), s("v")),
                        new Return(Optional.of(ref(pairs))))),
                new Function(walk, List.of(
                        new ForEach(word, ref(words), List.of(
                                new If(new Compare(ref(word), Comparison.EQ, s("b")), List.of(new Continue()), List.of()),
                                new If(new Compare(ref(word), Comparison.EQ, s("c")), List.of(new Break()), List.of()),
                                new Print(new Interp(List.of(ref(text), s(" "), ref(word))), Color.DEFAULT))),
                        new Return(Optional.empty()))),
                new Function(here, List.of(new Return(Optional.of(new FileName(new ScriptDir()))))),
                print("none ", new Count(new Call(none, List.of())), ", two ", new Count(new Call(two, List.of())),
                        ", first ", new At(new Call(two, List.of()), i(0))),
                new If(new Call(isShort, List.of(s("ab"))), List.of(new Print(s("ab is short"), Color.GREEN)),
                        List.of(new If(ref(flag), List.of(new Print(s("flag"), Color.DEFAULT)), List.of(new Print(s("neither"), Color.RED))))),
                new Set(grown, s("ab")),
                new While(new Not(new Compare(new Call(grow, List.of(ref(grown))), Comparison.EQ, s("abxxx"))),
                        List.of(new Set(grown, new Call(grow, List.of(ref(grown)))))),
                new Set(pairs, new Call(pairsOf, List.of())),
                print("grown ", ref(grown), ", pairs ", new Get(ref(pairs), s("k")), ", here ", new Call(here, List.of())),
                new Invoke(walk, List.of(s("walk"), ref(words))),
                new Invoke(grow, List.of(s("dropped"))),
                new Set(doc, new ReadJson(ref(jsonPath))),
                new Set(ids, new ListLit(STRING, List.of())),
                new ForEach(item, new Field(ref(doc), List.of("items"), listOf(JSON)), List.of(
                        new Append(ids, new Field(ref(item), List.of("id"), STRING)))),
                new ForEach(item, new Field(ref(doc), List.of("missing", "items"), listOf(JSON)), List.of(
                        new Append(ids, s("never")))),
                print("json: ", new Field(ref(doc), List.of("name"), STRING), " [", new Field(ref(doc), List.of("nothing"), STRING), "] ",
                        new Join(ref(ids), s(" ")), " ", new Field(ref(doc), List.of("size"), INT), " ",
                        new Join(new Field(ref(doc), List.of("tags"), listOf(STRING)), s("+"))),
                print("missing field empty: ", yesNo(new IsEmpty(new Field(ref(doc), List.of("linux"), JSON))),
                        ", on: ", yesNo(new Field(ref(doc), List.of("on"), BOOL)),
                        ", sub-field: ", new Field(new Field(ref(doc), List.of("sub"), JSON), List.of("my-name"), STRING)),
                print("probe git: ", yesNo(new Succeeds(new Command(s("git"), List.of(s("--version")), true)))),
                print("failing git: ", yesNo(new Succeeds(new Command(s("git"), List.of(s("no-such-subcommand")), true)))),
                print("missing program: ", yesNo(new Succeeds(new Command(s("no-such-program-koine"), List.of(), true)))),
                print("missing script: ", yesNo(new Succeeds(new Command(new ScriptPath("no-such-script"), List.of(), true)))),
                print("git on path: ", yesNo(new CommandExists(s("git")))),
                new Run(new Command(s("git"), List.of(s("--version")), true), OnFailure.ABORT),
                new Run(new Command(s("git"), List.of(s("rev-parse"), s("--is-inside-work-tree")), false), OnFailure.IGNORE),
                new Set(lines, i(0)),
                new EachLine(gitVersion, line, List.of(new Set(lines, i(1))), status),
                print("lines: ", ref(lines), " status ", ref(status)),
                new Set(version, new Capture(gitVersion)),
                print("capture: ", new Take(ref(version), i(11))),
                print("path: ", new FileName(new PathJoin(s("a"), List.of(s("b"), s("c.txt")))), " ",
                        new FileName(new Parent(new PathJoin(s("a"), List.of(s("b"), s("c.txt"))))), " ",
                        new FileName(new FullPath(new PathJoin(new ScriptDir(), List.of(s("x"), s(".."), s("y")))))),
                print("home: ", yesNo(new Compare(new Home(), Comparison.EQ, new Env("HOME")))),
                new MakeDirs(new PathJoin(new ScriptDir(), List.of(s("probe-out")))),
                new Set(proc, new Spawn(new Command(s("sleep"), List.of(s("5")), false), new ScriptDir(),
                        new PathJoin(new ScriptDir(), List.of(s("probe-out"), s("sleep.log"))),
                        new PathJoin(new ScriptDir(), List.of(s("probe-out"), s("sleep.err"))))),
                new Sleep(i(1)),
                new If(new IsRunning(ref(proc)), List.of(new Kill(ref(proc)), new Print(s("spawned and killed"), Color.YELLOW)), List.of()),
                new If(new IsInteractive(), List.of(new Pause(s("Press Enter to finish"))), List.of()),
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
        Path p = Path.of(ZshLoweringTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
