package sibarum.koine.ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.Statement.*;

import static sibarum.koine.ir.Type.*;

/**
 * A script of one statement per construct, for any OS: its native functions are the standard library's for that OS.
 * Run with a JSON file holding {@code {"name": "probe", "count": 3, "on": true, "tags": ["a", "b"]}}, it prints the
 * same lines on every shell, the ones in {@code expected/probe.out}, and fails at the end, on purpose, after its
 * cleanup. The tests compare the lowered scripts; the output is checked by running them.
 */
public final class Probe {

    private Probe() {
    }

    static final Type.Record POINT = new Type.Record("Point", List.of(
            new Type.Record.Field("x", INT, false), new Type.Record.Field("label", STRING, false)));
    static final Type.Record DOC = new Type.Record("Doc", List.of(
            new Type.Record.Field("name", STRING, false), new Type.Record.Field("count", INT, false),
            new Type.Record.Field("on", BOOL, false), new Type.Record.Field("tags", listOf(STRING), false)));

    static final List<String[]> NATIVES = List.of(
            new String[]{"String", "words"}, new String[]{"String", "take"}, new String[]{"List", "join"},
            new String[]{"Path", "join"}, new String[]{"Path", "parent"}, new String[]{"Path", "name"},
            new String[]{"File", "exists"}, new String[]{"Number", "text"}, new String[]{"Json", "read"},
            new String[]{"Console", "warn"});

    public static Script script(OS os) {
        Var json = new Var("json", STRING);
        Var m = new Var("m", mapOf(STRING));
        Var words = new Var("words", listOf(STRING));
        Var p = new Var("p", POINT);
        Var q = new Var("q", POINT);
        Var by = new Var("by", INT);
        Var n = new Var("n", INT);
        Var xs = new Var("xs", listOf(STRING));
        Var sep = new Var("sep", STRING);
        Var ys = new Var("ys", listOf(STRING));
        Var doc = new Var("doc", DOC);
        Var line = new Var("line", STRING);
        Var lines = new Var("lines", INT);
        Var status = new Var("status", INT);
        Var version = new Var("version", STRING);
        Var other = new Var("other", STRING);

        FunctionSig none = new FunctionSig("Get-None", List.of(), Optional.of(listOf(STRING)));
        FunctionSig two = new FunctionSig("Get-Two", List.of(), Optional.of(listOf(STRING)));
        FunctionSig both = new FunctionSig("Join-Both", List.of(xs, sep, ys), Optional.of(STRING));
        FunctionSig describe = new FunctionSig("Describe-Point", List.of(p), Optional.of(STRING));
        FunctionSig move = new FunctionSig("Move-Point", List.of(p, by), Optional.of(POINT));
        FunctionSig isBig = new FunctionSig("Test-Big", List.of(n), Optional.of(BOOL));
        Command gitVersion = new Command(s("git"), List.of(s("--version")), false);

        List<Statement> body = new ArrayList<>();
        for (String[] native_ : NATIVES) body.add(Std.native_(native_[0], native_[1], os));
        body.addAll(List.of(
                new Function(none, List.of(new Return(Optional.of(new ListLit(STRING, List.of()))))),
                new Function(two, List.of(new Return(Optional.of(new ListLit(STRING, List.of(s("x"), s("y"))))))),
                new Function(both, List.of(new Return(Optional.of(str(join(ref(xs), ref(sep)), " | ", join(ref(ys), ref(sep))))))),
                new Function(describe, List.of(new Return(Optional.of(str(new FieldOf(ref(p), "label"), " at ",
                        call("Number", "text", new FieldOf(ref(p), "x"))))))),
                new Function(move, List.of(new Return(Optional.of(new MakeRecord(POINT, List.of(ref(by), new FieldOf(ref(p), "label"))))))),
                new Function(isBig, List.of(new Return(Optional.of(new Compare(ref(n), Comparison.GT, i(2)))))),

                new Set(m, new EmptyMap(STRING)),
                new Put(m, s("B"), s("1")),
                new Put(m, s("b"), s("2")),
                print("map: ", new Get(ref(m), s("b")), " ", new Get(ref(m), s("B"))),
                yesNo("has b: ", new HasKey(ref(m), s("b"))),
                new Set(words, call("String", "words", s("  a  b c "))),
                print("words: ", new At(ref(words), i(1)), " ", join(ref(words), s(","))),
                print("take: ", call("String", "take", s("abcdef"), i(3)), " ", call("String", "take", s("ab"), i(9))),
                yesNo("comment: ", new Matches(s("  # x"), "^[[:space:]]*(#|$)")),
                yesNo("same case: ", new Compare(s("A"), Comparison.EQ, s("a"))),
                yesNo("in string: ", new In(s("b"), s("abc"))),
                yesNo("in list: ", new In(s("c"), ref(words))),
                yesNo("not in list: ", new Not(new In(s("z"), ref(words)))),
                print("lists: [", join(new Call(none, List.of()), s(",")), "] [", join(new Call(two, List.of()), s(",")), "]"),
                print("both: ", new Call(both, List.of(new ListLit(STRING, List.of(s("a b"), s("c"))), s(", "), new ListLit(STRING, List.of(s("d")))))),
                new Set(p, new MakeRecord(POINT, List.of(i(3), s("here")))),
                print("record: ", new Call(describe, List.of(ref(p)))),
                new Set(q, new Call(move, List.of(ref(p), i(7)))),
                print("moved: ", call("Number", "text", new FieldOf(ref(q), "x")), " ", new FieldOf(ref(q), "label")),
                yesNo("big: ", new Call(isBig, List.of(i(3)))),
                new Set(doc, new ReadAs(call("Json", "read", ref(json)), DOC)),
                print("data: ", new FieldOf(ref(doc), "name"), " ", call("Number", "text", new FieldOf(ref(doc), "count")), " ",
                        join(new FieldOf(ref(doc), "tags"), s("+"))),
                yesNo("on: ", new FieldOf(ref(doc), "on")),
                print("path: ", call("Path", "name", path(s("a"), s("b"), s("c.txt"))), " ",
                        call("Path", "name", call("Path", "parent", path(s("a"), s("b"), s("c.txt"))))),
                yesNo("exists: ", call("File", "exists", ref(json))),
                yesNo("probe git: ", new Succeeds(new Command(s("git"), List.of(s("--version")), true))),
                yesNo("failing git: ", new Succeeds(new Command(s("git"), List.of(s("no-such-subcommand")), true))),
                yesNo("missing program: ", new Succeeds(new Command(s("no-such-program-koine"), List.of(), true))),
                new Set(lines, i(0)),
                new EachLine(gitVersion, line, List.of(new Set(lines, i(1))), status),
                print("lines: ", ref(lines), " status ", ref(status)),
                new Set(version, new Capture(gitVersion, new Fallback.Stop())),
                print("capture: ", call("String", "take", ref(version), i(11))),
                new Set(other, new Capture(new Command(s("git"), List.of(s("config"), s("--get"), s("koine.no-such-key")), false),
                        new Fallback.Value(s("fallback")))),
                print("failed capture: ", ref(other)),
                new Set(n, i(0)),
                new While(new Compare(ref(n), Comparison.LT, i(3)), List.of(new Set(n, i(3)))),
                print("while: ", ref(n)),
                new Finally(List.of(new Print(s("in the body"))), List.of(new Print(s("cleaned up")))),
                new Invoke(Std.sig("Console", "warn"), List.of(s("warned"))),
                new Run(new Command(s("git"), List.of(s("--version")), true), OnFailure.ABORT),
                new Finally(List.of(new Fail(s("on purpose"))), List.of(new Print(s("cleaned up after the failure")))),
                new Print(s("never"))));
        return new Script("probe", "Tries each portable construct.", "", List.of(
                new Script.Param(json, Optional.empty(), "A JSON file to read.")), body);
    }

    private static Statement yesNo(String label, Expr condition) {
        return new If(condition, List.of(new Print(s(label + "yes"))), List.of(new Print(s(label + "no"))));
    }

    private static Statement print(Object... parts) {
        return new Print(str(parts));
    }

    private static Interp str(Object... parts) {
        return new Interp(Arrays.stream(parts).map(x -> x instanceof String t ? s(t) : (Expr) x).toList());
    }

    private static Call call(String module, String member, Expr... arguments) {
        return new Call(Std.sig(module, member), List.of(arguments));
    }

    private static Call join(Expr list, Expr separator) {
        return call("List", "join", list, separator);
    }

    private static Call path(Expr... parts) {
        return call("Path", "join", new ListLit(STRING, List.of(parts)));
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
}
