package sibarum.koine.ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.Statement.*;

import static sibarum.koine.ir.Type.*;

/**
 * {@code koine-lang/src/test/resources/examples/preflight.koine}, built in the IR by hand, the way the compiler will:
 * for Windows only, as its script line says; every library member it uses, directly or through others, as a native
 * function from the standard library, or a Koine one for {@code Python.find}; {@code step} and {@code within}
 * expanded from {@code Script.runStep} and {@code Dir.runIn}; and each JSON file read into its record.
 */
final class Preflight {

    private Preflight() {
    }

    private static final OS OS_ = OS.Windows;

    // ---- records ------------------------------------------------------------------------------------------------

    static final Type.Record MEMBER = record("Member", field("config", STRING));
    static final Type.Record SUITE = record("Suite", field("members", listOf(MEMBER)));
    static final Type.Record BINARY = record("Binary", field("name", STRING), field("source", STRING));
    static final Type.Record PLATFORM = record("Platform", field("binaries", listOf(BINARY)));
    static final Type.Record APP = record("App", field("id", STRING), field("name", STRING), field("version", STRING),
            field("windows", PLATFORM), new Type.Record.Field("linux", PLATFORM, true));
    static final Type.Record MANIFEST = record("Manifest", field("latest", STRING));
    static final Type.Record PROCESS = Std.record("Process", "Process");

    // ---- variables ----------------------------------------------------------------------------------------------

    static final Var SKIP_BUILD = new Var("skipBuild", BOOL);
    static final Var SECONDS = new Var("seconds", INT);
    static final Var BUILD_ONLY = new Var("buildOnly", BOOL);

    static final Var DIST = new Var("dist", STRING);
    static final Var ROOT = new Var("root", STRING);
    static final Var LOGS = new Var("logs", STRING);
    static final Var CONFIGS = new Var("configs", listOf(STRING));
    static final Var SUITE_ = new Var("suite", SUITE);
    static final Var MEMBER_ = new Var("member", MEMBER);
    static final Var CONFIG = new Var("config", STRING);
    static final Var APP_ = new Var("app", APP);
    static final Var BINARY_ = new Var("binary", BINARY);
    static final Var BUILD_CMD = new Var("buildCmd", STRING);
    static final Var FAILED = new Var("failed", BOOL);
    static final Var LINE = new Var("line", STRING);
    static final Var STATUS = new Var("status", INT);
    static final Var DEAD = new Var("dead", listOf(STRING));
    static final Var EXE = new Var("exe", STRING);
    static final Var LOG = new Var("log", STRING);
    static final Var PROCESS_ = new Var("process", PROCESS);
    static final Var PROBLEM = new Var("problem", STRING);
    static final Var LINUX_APPS = new Var("linuxApps", listOf(STRING));
    static final Var PROBLEMS = new Var("problems", listOf(STRING));
    static final Var PYTHON = new Var("python", STRING);
    static final Var NOTICES = new Var("notices", STRING);
    static final Var MANIFEST_ = new Var("manifest", STRING);
    static final Var LATEST = new Var("latest", STRING);
    static final Var DEPLOY = new Var("deploy", STRING);
    static final Var SITE = new Var("site", STRING);
    /** The step each {@code step} was in before it, put back afterwards: the renamed local of Script.runStep. */
    static final Var OUTER = new Var("outerStep", STRING);

    static final Var STAMP = new Var("stamp", STRING);
    static final Var BUILT_FROM = new Var("builtFrom", mapOf(STRING));
    static final Var WORDS = new Var("words", listOf(STRING));
    static final Var REPO = new Var("repo", STRING);
    static final Var CHECKOUT = new Var("checkout", STRING);
    static final Var HEAD = new Var("head", STRING);

    static final Var HOME = new Var("home", STRING);
    static final Var CANDIDATE = new Var("candidate", STRING);

    // ---- functions ----------------------------------------------------------------------------------------------

    /** Every native member preflight uses, directly or through Python.find and the expanded steps. */
    static final List<String[]> NATIVES = List.of(
            m("Script", "directory"), m("Script", "sibling"), m("Script", "currentStep"), m("Script", "setStep"),
            m("Path", "join"), m("Path", "parent"), m("Path", "name"), m("Path", "full"),
            m("Dir", "create"), m("Dir", "push"), m("Dir", "pop"),
            m("File", "exists"), m("File", "lines"), m("Json", "read"),
            m("Process", "start"), m("Process", "running"), m("Process", "stop"), m("Clock", "sleep"),
            m("Console", "heading"), m("Console", "success"), m("Console", "warn"), m("Console", "error"),
            m("Console", "interactive"), m("Console", "pause"),
            m("Home", "directory"), m("String", "empty"), m("String", "endsWith"), m("String", "take"),
            m("String", "words"), m("Number", "text"), m("List", "join"));

    static final FunctionSig PYTHON_FIND = new FunctionSig("Python_find", List.of(), Optional.of(STRING));
    static final FunctionSig LINUX_BUILD_PROBLEMS = new FunctionSig("linuxBuildProblems", List.of(LOGS, ROOT, CONFIGS),
            Optional.of(listOf(STRING)));

    // ---- the script ---------------------------------------------------------------------------------------------

    static Script script() {
        List<Statement> body = new ArrayList<>();
        for (String[] n : NATIVES) body.add(Std.native_(n[0], n[1], OS_));
        body.add(pythonFind());
        body.add(linuxBuildProblems());
        body.addAll(body(
                set(DIST, call("Script", "directory")),
                set(ROOT, call("Path", "parent", ref(DIST))),
                set(LOGS, path(ref(DIST), s("logs"))),
                invoke("Dir", "create", ref(LOGS)),
                set(CONFIGS, new ListLit(STRING, List.of())),
                set(SUITE_, readJson(path(ref(DIST), s("installer-suite.json")), SUITE)),
                forEach(MEMBER_, field(ref(SUITE_), "members"),
                        new Append(CONFIGS, call("Path", "full", path(ref(DIST), field(ref(MEMBER_), "config"))))),
                when(new Not(ref(SKIP_BUILD)), step("Build", build())),
                step("Start", start()),
                new Comment("The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of\n"
                        + "every checkout it built. They are fit to publish only while every one of those is still checked out."),
                step("Linux", linux()),
                step("Notices", body(
                        set(PYTHON, new Call(PYTHON_FIND, List.of())),
                        when(new IsEmpty(ref(PYTHON)), fail("No Python found (python, py, or anaconda3)")),
                        set(NOTICES, path(ref(DIST), s("notices"), s("notices.py"))),
                        when(new Not(new Succeeds(new Command(ref(PYTHON), List.of(ref(NOTICES), s("--check")), false))),
                                fail("The notices are out of date")))),
                when(ref(BUILD_ONLY), invoke("Console", "success", s("Built, started and checked.")), new Exit(i(0))),
                step("Versions", versions()),
                step("Generate", body(
                        set(DEPLOY, call("Script", "sibling", s("deploy"))),
                        when(new Not(new Succeeds(new Command(ref(DEPLOY), List.of(), false))), fail("deploy")))),
                step("Diff", body(
                        within(ref(DIST), gitStatus()),
                        set(SITE, path(ref(ROOT), s("sibarum.github.io"))),
                        when(call("File", "exists", path(ref(SITE), s(".git"))), within(ref(SITE), gitStatus())),
                        invoke("Console", "success",
                                s("Preflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish."))))));
        return new Script("preflight",
                "Everything before publishing: builds the stack, starts every application, checks the notices and "
                        + "versions, and generates the installers without publishing them.",
                "Nothing is uploaded, committed or pushed. Stops at the first failing step. It builds and starts the "
                        + "Windows applications, so it is for Windows only.",
                List.of(
                        new Script.Param(SKIP_BUILD, Optional.empty(), "Use the exes already in each application's target directory."),
                        new Script.Param(SECONDS, Optional.of(i(8)), "How long each application must stay up."),
                        new Script.Param(BUILD_ONLY, Optional.empty(), "Stop after the notices, generating nothing.")),
                body);
    }

    private static List<Statement> build() {
        return body(
                new Comment("The output is checked for FAILED as well as the exit status, so a failure can never pass as a build."),
                set(BUILD_CMD, path(ref(DIST), s("build.cmd"))),
                set(FAILED, new BoolLit(false)),
                new EachLine(new Command(ref(BUILD_CMD), List.of(), false), LINE, body(
                        new Print(ref(LINE)),
                        when(new In(s("FAILED"), ref(LINE)), set(FAILED, new BoolLit(true)))), STATUS),
                when(new Compare(ref(STATUS), Comparison.NE, i(0)), new Fail(str(ref(BUILD_CMD), " exited with ", ref(STATUS)))),
                when(ref(FAILED), fail("The build printed FAILED; see logs/")));
    }

    private static List<Statement> start() {
        Expr binaryName = field(ref(BINARY_), "name");
        return body(
                set(DEAD, new ListLit(STRING, List.of())),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP_, readJson(ref(CONFIG), APP)),
                        forEach(BINARY_, field(field(ref(APP_), "windows"), "binaries"),
                                when(call("String", "endsWith", field(ref(BINARY_), "source"), s(".exe")),
                                        set(EXE, path(call("Path", "parent", ref(CONFIG)), field(ref(BINARY_), "source"))),
                                        new If(new Not(call("File", "exists", ref(EXE))),
                                                body(new Append(DEAD, str(field(ref(APP_), "id"), ": no ", ref(EXE)))),
                                                body(
                                                        set(LOG, path(ref(LOGS), str("start-", binaryName, ".log"))),
                                                        within(call("Path", "parent", ref(EXE)), body(
                                                                set(PROCESS_, call("Process", "start", ref(EXE), ref(LOG))),
                                                                invoke("Clock", "sleep", ref(SECONDS)),
                                                                new If(call("Process", "running", ref(PROCESS_)),
                                                                        body(new Print(str("  ", binaryName, " up")),
                                                                                invoke("Process", "stop", ref(PROCESS_))),
                                                                        body(new Append(DEAD, str(binaryName, " exited within ",
                                                                                call("Number", "text", ref(SECONDS)), "s; see logs/start-",
                                                                                binaryName, ".log.err"))))))))))),
                when(new Not(new IsEmpty(ref(DEAD))),
                        forEach(PROBLEM, ref(DEAD), invoke("Console", "error", str("  ", ref(PROBLEM)))),
                        fail("An application does not start")));
    }

    private static List<Statement> linux() {
        return body(
                set(LINUX_APPS, new ListLit(STRING, List.of())),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP_, readJson(ref(CONFIG), APP)),
                        when(new Not(new IsEmpty(field(ref(APP_), "linux"))), new Append(LINUX_APPS, field(ref(APP_), "name")))),
                new If(new IsEmpty(ref(LINUX_APPS)),
                        body(new Print(s("  no application has a linux section"))),
                        body(
                                set(PROBLEMS, new Call(LINUX_BUILD_PROBLEMS, List.of(ref(LOGS), ref(ROOT), ref(CONFIGS)))),
                                new While(new Not(new IsEmpty(ref(PROBLEMS))), body(
                                        forEach(PROBLEM, ref(PROBLEMS), invoke("Console", "warn", str("  ", ref(PROBLEM)))),
                                        new Print(s("  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh")),
                                        when(new Not(call("Console", "interactive")), fail("The Linux build is missing, or not of these sources")),
                                        invoke("Console", "pause", s("  Press Enter once it has finished, to check again (Ctrl+C stops)")),
                                        set(PROBLEMS, new Call(LINUX_BUILD_PROBLEMS, List.of(ref(LOGS), ref(ROOT), ref(CONFIGS)))))),
                                new Print(str("  built in the VM from these sources: ", call("List", "join", ref(LINUX_APPS), s(", ")))))));
    }

    private static List<Statement> versions() {
        Expr name = field(ref(APP_), "name");
        Expr version = field(ref(APP_), "version");
        return body(forEach(CONFIG, ref(CONFIGS),
                set(APP_, readJson(ref(CONFIG), APP)),
                set(MANIFEST_, path(ref(DIST), s("apps"), field(ref(APP_), "id"), s("manifest.json"))),
                set(LATEST, call("String", "empty")),
                when(call("File", "exists", ref(MANIFEST_)), set(LATEST, field(readJson(ref(MANIFEST_), MANIFEST), "latest"))),
                new If(new Compare(ref(LATEST), Comparison.EQ, version),
                        body(invoke("Console", "warn", str("  ", name, " ", version, " is already published; bump it in ", ref(CONFIG), " if it changed"))),
                        body(new If(new IsEmpty(ref(LATEST)),
                                body(new Print(str("  ", name, " ", version, " (published: none)"))),
                                body(new Print(str("  ", name, " ", version, " (published: ", ref(LATEST), ")"))))))));
    }

    private static List<Statement> gitStatus() {
        return body(new Run(new Command(s("git"), List.of(s("status"), s("--short")), false), OnFailure.IGNORE));
    }

    /** What is wrong with the Linux build: missing, not of the commits checked out here, or without a binary. */
    private static Function linuxBuildProblems() {
        return new Function(LINUX_BUILD_PROBLEMS, body(
                set(STAMP, path(ref(LOGS), s("linux"), s("BUILD.txt"))),
                when(new Not(call("File", "exists", ref(STAMP))),
                        new Return(Optional.of(new ListLit(STRING, List.of(s("no Linux build: logs/linux/BUILD.txt is missing")))))),
                set(BUILT_FROM, new EmptyMap(STRING)),
                forEach(LINE, call("File", "lines", ref(STAMP)),
                        when(new Not(new Matches(ref(LINE), "^[[:space:]]*(#|$)")),
                                set(WORDS, call("String", "words", ref(LINE))),
                                new Put(BUILT_FROM, new At(ref(WORDS), i(0)), new At(ref(WORDS), i(1))))),
                set(PROBLEMS, new ListLit(STRING, List.of())),
                forEach(REPO, new Keys(ref(BUILT_FROM)),
                        set(CHECKOUT, path(ref(ROOT), ref(REPO))),
                        set(HEAD, new Capture(new Command(s("git"), List.of(s("-C"), ref(CHECKOUT), s("rev-parse"), s("HEAD")), false),
                                new Fallback.Value(s("nothing")))),
                        when(new Compare(ref(HEAD), Comparison.NE, new Get(ref(BUILT_FROM), ref(REPO))),
                                new Append(PROBLEMS, str(ref(REPO), ": the Linux build is of ",
                                        call("String", "take", new Get(ref(BUILT_FROM), ref(REPO)), i(9)),
                                        ", the checkout is at ", call("String", "take", ref(HEAD), i(9)))))),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP_, readJson(ref(CONFIG), APP)),
                        when(new Not(new IsEmpty(field(ref(APP_), "linux"))),
                                set(REPO, call("Path", "name", call("Path", "parent", ref(CONFIG)))),
                                when(new Not(new HasKey(ref(BUILT_FROM), ref(REPO))), new Append(PROBLEMS, str(ref(REPO), ": not in the Linux build"))),
                                forEach(BINARY_, field(field(ref(APP_), "linux"), "binaries"),
                                        when(new Not(call("File", "exists", path(call("Path", "parent", ref(CONFIG)), field(ref(BINARY_), "source")))),
                                                new Append(PROBLEMS, str(field(ref(APP_), "id"), ": no ", field(ref(BINARY_), "source"))))))),
                new Return(Optional.of(ref(PROBLEMS)))));
    }

    /** The standard library's Python.find, which is Koine, as the compiler will emit it. */
    private static Function pythonFind() {
        return new Function(PYTHON_FIND, body(
                set(HOME, call("Home", "directory")),
                forEach(CANDIDATE, new ListLit(STRING, List.of(s("python"), s("py"),
                                path(ref(HOME), s("anaconda3"), s("python.exe")), path(ref(HOME), s("miniconda3"), s("python.exe")))),
                        when(new Succeeds(new Command(ref(CANDIDATE), List.of(s("--version")), false)), new Return(Optional.of(ref(CANDIDATE))))),
                new Return(Optional.of(call("String", "empty")))));
    }

    // ---- what the compiler will expand --------------------------------------------------------------------------

    /** {@code step Name}: Script.runStep, expanded. */
    private static List<Statement> step(String name, List<Statement> block) {
        return body(
                invoke("Console", "heading", s(name)),
                set(OUTER, call("Script", "currentStep")),
                invoke("Script", "setStep", s(name)),
                new Finally(block, body(invoke("Script", "setStep", ref(OUTER)))));
    }

    /** {@code within dir}: Dir.runIn, expanded. */
    private static Statement within(Expr dir, List<Statement> block) {
        List<Statement> b = new ArrayList<>(List.of(invoke("Dir", "push", dir)));
        b.addAll(block);
        return new Finally(b, body(invoke("Dir", "pop")));
    }

    // ---- shorthand ----------------------------------------------------------------------------------------------

    private static String[] m(String module, String member) {
        return new String[]{module, member};
    }

    private static Type.Record record(String name, Type.Record.Field... fields) {
        return new Type.Record(name, List.of(fields));
    }

    private static Type.Record.Field field(String name, Type type) {
        return new Type.Record.Field(name, type, false);
    }

    private static Call call(String module, String member, Expr... arguments) {
        return new Call(Std.sig(module, member), List.of(arguments));
    }

    private static Invoke invoke(String module, String member, Expr... arguments) {
        return new Invoke(Std.sig(module, member), List.of(arguments));
    }

    /** {@code Path.join(a, b, …)}: its parts, one list, as the repeated parameter takes them. */
    private static Call path(Expr... parts) {
        return call("Path", "join", new ListLit(STRING, List.of(parts)));
    }

    private static ReadAs readJson(Expr path, Type.Record record) {
        return new ReadAs(call("Json", "read", path), record);
    }

    private static FieldOf field(Expr record, String name) {
        return new FieldOf(record, name);
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

    /** Strings and expressions, one after another. */
    private static Interp str(Object... parts) {
        return new Interp(Arrays.stream(parts).map(p -> p instanceof String t ? s(t) : (Expr) p).toList());
    }

    private static Set set(Var v, Expr value) {
        return new Set(v, value);
    }

    private static Fail fail(String message) {
        return new Fail(s(message));
    }

    private static If when(Expr condition, Object... then) {
        return new If(condition, body(then), List.of());
    }

    private static ForEach forEach(Var item, Expr collection, Object... body) {
        return new ForEach(item, collection, body(body));
    }

    /** Statements and lists of statements, flattened. */
    private static List<Statement> body(Object... items) {
        List<Statement> all = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof List<?> l) l.forEach(x -> all.add((Statement) x));
            else all.add((Statement) o);
        }
        return all;
    }
}
