package sibarum.koine.ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.Statement.*;

import static sibarum.koine.ir.Type.*;

/**
 * vexelray-dist's preflight.ps1, ported to the portable IR. It does what the original does, in the IR's terms:
 * <ul>
 *   <li>The suite's members are a list of config paths. Each loop reads its app's installer.json where it needs it,
 *       instead of carrying {@code [pscustomobject]}s, which Bash has no counterpart for.</li>
 *   <li>{@code Where-Object} and {@code ForEach-Object} are loops with an {@code if}.</li>
 *   <li>{@code $LASTEXITCODE} after a command is {@link Succeeds}, or {@link EachLine}'s status.</li>
 *   <li>Find-Python's local {@code $ErrorActionPreference = 'Continue'} is gone: a quiet {@link Succeeds} probe says
 *       what it was for, and the PowerShell lowering is what has to know about stderr and {@code Stop}.</li>
 *   <li>{@code try { & deploy.ps1 } catch} is a {@link Succeeds} of the deploy script.</li>
 * </ul>
 */
final class Preflight {

    private Preflight() {
    }

    // ---- variables ----------------------------------------------------------------------------------------------

    static final Var SKIP_BUILD = new Var("SkipBuild", BOOL);
    static final Var SECONDS = new Var("Seconds", INT);
    static final Var BUILD_ONLY = new Var("BuildOnly", BOOL);

    static final Var DIST = new Var("dist", STRING);
    static final Var ROOT = new Var("root", STRING);
    static final Var LOGS = new Var("logs", STRING);
    static final Var SUITE = new Var("suite", JSON);
    static final Var MEMBER = new Var("m", JSON);
    static final Var CONFIGS = new Var("configs", listOf(STRING));
    static final Var CONFIG = new Var("config", STRING);
    static final Var APP = new Var("app", JSON);
    static final Var REPO = new Var("repo", STRING);
    static final Var BINARY = new Var("b", JSON);

    static final Var NAME = new Var("name", STRING);
    static final Var MSG = new Var("msg", STRING);
    static final Var C = new Var("c", STRING);
    static final Var LINE = new Var("line", STRING);
    static final Var STATUS = new Var("status", INT);
    static final Var FAILED = new Var("failed", BOOL);
    static final Var DEAD = new Var("dead", listOf(STRING));
    static final Var EXE = new Var("exe", STRING);
    static final Var OUT = new Var("out", STRING);
    static final Var P = new Var("p", PROCESS);
    static final Var PROBLEM = new Var("problem", STRING);
    static final Var STAMP = new Var("stamp", STRING);
    static final Var BUILT_FROM = new Var("builtFrom", mapOf(STRING));
    static final Var F = new Var("f", listOf(STRING));
    static final Var PROBLEMS = new Var("problems", listOf(STRING));
    static final Var HEAD = new Var("head", STRING);
    static final Var LINUX_APPS = new Var("linuxApps", listOf(STRING));
    static final Var INTERACTIVE = new Var("interactive", BOOL);
    static final Var PYTHON = new Var("python", STRING);
    static final Var MANIFEST = new Var("manifest", STRING);
    static final Var LATEST = new Var("latest", STRING);
    static final Var SITE = new Var("site", STRING);

    static final FunctionSig STEP = new FunctionSig("Step", List.of(NAME), Optional.empty());
    static final FunctionSig FAIL = new FunctionSig("Fail", List.of(MSG), Optional.empty());
    static final FunctionSig FIND_PYTHON = new FunctionSig("Find-Python", List.of(), Optional.of(STRING));
    static final FunctionSig TEST_LINUX_BUILD = new FunctionSig("Test-LinuxBuild", List.of(), Optional.of(listOf(STRING)));

    // ---- the script ---------------------------------------------------------------------------------------------

    static Script script() {
        return new Script("preflight",
                "Everything before publishing: builds the stack, starts every application, checks the notices and "
                        + "versions, and generates the installers without publishing them.",
                """
                        1. build      build.cmd: libraries in order with their tests, then each application's tests and native image
                        2. start      each application's exe, from its installer.json, must still be running after a few seconds
                        3. linux      each application with a linux section needs its Linux binaries, built in the Linux VM from
                                      exactly the commits checked out here
                        4. notices    notices/notices.py --check
                        5. versions   lists each application whose installer.json version is already published
                        6. generate   deploy without -Publish, then the diff here and in ../sibarum.github.io

                        Nothing is uploaded, committed or pushed. Stops at the first failing step.""",
                List.of(
                        new Script.Param(SKIP_BUILD, Optional.empty(),
                                "Use the exes already in each application's target directory."),
                        new Script.Param(SECONDS, Optional.of(i(8)), "How long each application must stay up."),
                        new Script.Param(BUILD_ONLY, Optional.empty(),
                                "Stop after the notices: build, start, the Linux build and the notices checked, generating nothing.")),
                body(
                        set(DIST, new ScriptDir()),
                        set(ROOT, new Parent(ref(DIST))),
                        set(LOGS, path(ref(DIST), s("logs"))),
                        new MakeDirs(ref(LOGS)),
                        set(SUITE, new ReadJson(path(ref(DIST), s("installer-suite.json")))),
                        set(CONFIGS, new ListLit(STRING, List.of())),
                        forEach(MEMBER, field(ref(SUITE), listOf(JSON), "members"),
                                new Append(CONFIGS, new FullPath(path(ref(DIST), field(ref(MEMBER), STRING, "config"))))),
                        new Function(STEP, body(new Print(str("\n== ", ref(NAME)), Color.CYAN))),
                        new Function(FAIL, body(new Print(str("PREFLIGHT FAILED: ", ref(MSG)), Color.RED), new Exit(i(1)))),
                        new Comment("A Python that answers --version: the Store's python.exe stub is on the path but runs nothing."),
                        new Function(FIND_PYTHON, body(
                                forEach(C, new ListLit(STRING, List.of(s("python"), s("py"),
                                                path(new Home(), s("anaconda3"), s("python.exe")),
                                                path(new Home(), s("miniconda3"), s("python.exe")))),
                                        when(new And(new CommandExists(ref(C)),
                                                        new Succeeds(new Command(ref(C), List.of(s("--version")), true))),
                                                new Return(Optional.of(ref(C))))),
                                new Return(Optional.of(s(""))))),
                        build(),
                        start(),
                        new Comment("The Linux binaries are built in the Linux VM by build.sh, which writes logs/linux/BUILD.txt: the commit of\n"
                                + "every checkout it built. They are fit to publish only while every one of those is still checked out."),
                        testLinuxBuild(),
                        linux(),
                        step("notices"),
                        set(PYTHON, new Call(FIND_PYTHON, List.of())),
                        when(new IsEmpty(ref(PYTHON)), fail(s("no Python found (python, py, or anaconda3)"))),
                        when(new Not(new Succeeds(new Command(ref(PYTHON), List.of(path(ref(DIST), s("notices"), s("notices.py")), s("--check")), false))),
                                fail(s("the notices"))),
                        when(ref(BUILD_ONLY), new Print(s("\nBuilt, started and checked."), Color.GREEN), new Exit(i(0))),
                        versions(),
                        step("generate"),
                        when(new Not(new Succeeds(new Command(new ScriptPath("deploy"), List.of(), false))), fail(s("deploy"))),
                        step("diff"),
                        new Run(new Command(s("git"), List.of(s("-C"), ref(DIST), s("status"), s("--short")), false), OnFailure.IGNORE),
                        set(SITE, path(ref(ROOT), s("sibarum.github.io"))),
                        when(new Exists(path(ref(SITE), s(".git"))),
                                new Run(new Command(s("git"), List.of(s("-C"), ref(SITE), s("status"), s("--short")), false), OnFailure.IGNORE)),
                        new Print(s("\nPreflight passed. Look at git diff here (and in ../sibarum.github.io), then deploy -Publish."), Color.GREEN)));
    }

    private static List<Statement> build() {
        return List.of(when(new Not(ref(SKIP_BUILD)), body(
                step("build"),
                new Comment("Its output is checked for FAILED as well as its status, so a failure can never pass as a build."),
                set(FAILED, new BoolLit(false)),
                new EachLine(new Command(s("cmd"), List.of(s("/c"), str("call \"", path(ref(DIST), s("build.cmd")), "\"")), false),
                        LINE, body(
                        new Print(ref(LINE), Color.DEFAULT),
                        when(new Matches(ref(LINE), "FAILED"), set(FAILED, new BoolLit(true)))),
                        STATUS),
                when(new Or(new Compare(ref(STATUS), Comparison.NE, i(0)), ref(FAILED)), fail(s("the build; see logs/"))))));
    }

    private static List<Statement> start() {
        return body(
                step("start"),
                set(DEAD, new ListLit(STRING, List.of())),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP, new ReadJson(ref(CONFIG))),
                        forEach(BINARY, field(ref(APP), listOf(JSON), "windows", "binaries"),
                                when(new Not(new EndsWith(field(ref(BINARY), STRING, "source"), s(".exe"))), new Continue()),
                                set(EXE, path(new Parent(ref(CONFIG)), field(ref(BINARY), STRING, "source"))),
                                when(new Not(new Exists(ref(EXE))),
                                        new Append(DEAD, str(field(ref(APP), STRING, "id"), ": no ", ref(EXE))),
                                        new Continue()),
                                set(OUT, path(ref(LOGS), str("start-", field(ref(BINARY), STRING, "name"), ".log"))),
                                set(P, new Spawn(new Command(ref(EXE), List.of(), false), new Parent(ref(EXE)), ref(OUT), str(ref(OUT), ".err"))),
                                new Sleep(ref(SECONDS)),
                                new If(new IsRunning(ref(P)),
                                        body(new Print(str("  ", field(ref(BINARY), STRING, "name"), " up"), Color.DEFAULT),
                                                new Kill(ref(P))),
                                        body(new Append(DEAD, str(field(ref(BINARY), STRING, "name"), " exited within ", ref(SECONDS),
                                                "s; see logs/start-", field(ref(BINARY), STRING, "name"), ".log.err")))))),
                when(new Not(new IsEmpty(ref(DEAD))),
                        forEach(PROBLEM, ref(DEAD), new Print(str("  ", ref(PROBLEM)), Color.RED)),
                        fail(s("an application does not start"))));
    }

    private static Statement testLinuxBuild() {
        return new Function(TEST_LINUX_BUILD, body(
                set(STAMP, path(ref(LOGS), s("linux"), s("BUILD.txt"))),
                when(new Not(new Exists(ref(STAMP))),
                        new Return(Optional.of(new ListLit(STRING, List.of(s("no Linux build: logs/linux/BUILD.txt is missing")))))),
                set(BUILT_FROM, new EmptyMap(STRING)),
                forEach(LINE, new ReadLines(ref(STAMP)),
                        when(new Matches(ref(LINE), "^[[:space:]]*(#|$)"), new Continue()),
                        set(F, new Words(ref(LINE))),
                        new Put(BUILT_FROM, new At(ref(F), i(0)), new At(ref(F), i(1)))),
                set(PROBLEMS, new ListLit(STRING, List.of())),
                forEach(REPO, new Keys(ref(BUILT_FROM)),
                        set(HEAD, new Capture(new Command(s("git"), List.of(s("-C"), path(ref(ROOT), ref(REPO)), s("rev-parse"), s("HEAD")), false))),
                        when(new Compare(ref(HEAD), Comparison.NE, new Get(ref(BUILT_FROM), ref(REPO))),
                                new Append(PROBLEMS, str(ref(REPO), ": the Linux build is of ", new Take(new Get(ref(BUILT_FROM), ref(REPO)), i(9)),
                                        ", the checkout is at ", new Choose(new IsEmpty(ref(HEAD)), s("nothing"), new Take(ref(HEAD), i(9))))))),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP, new ReadJson(ref(CONFIG))),
                        when(new IsEmpty(field(ref(APP), JSON, "linux")), new Continue()),
                        set(REPO, new FileName(new Parent(ref(CONFIG)))),
                        when(new Not(new HasKey(ref(BUILT_FROM), ref(REPO))), new Append(PROBLEMS, str(ref(REPO), ": not in the Linux build"))),
                        forEach(BINARY, field(ref(APP), listOf(JSON), "linux", "binaries"),
                                when(new Not(new Exists(path(new Parent(ref(CONFIG)), field(ref(BINARY), STRING, "source")))),
                                        new Append(PROBLEMS, str(field(ref(APP), STRING, "id"), ": no ", field(ref(BINARY), STRING, "source")))))),
                new Return(Optional.of(ref(PROBLEMS)))));
    }

    private static List<Statement> linux() {
        return body(
                step("linux"),
                set(LINUX_APPS, new ListLit(STRING, List.of())),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP, new ReadJson(ref(CONFIG))),
                        when(new Not(new IsEmpty(field(ref(APP), JSON, "linux"))), new Append(LINUX_APPS, field(ref(APP), STRING, "name")))),
                new If(new IsEmpty(ref(LINUX_APPS)),
                        body(new Print(s("  no application has a linux section"), Color.DEFAULT)),
                        body(
                                set(INTERACTIVE, new IsInteractive()),
                                set(PROBLEMS, new Call(TEST_LINUX_BUILD, List.of())),
                                new While(new Not(new IsEmpty(ref(PROBLEMS))), body(
                                        forEach(PROBLEM, ref(PROBLEMS), new Print(str("  ", ref(PROBLEM)), Color.YELLOW)),
                                        new Print(s("  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh"), Color.DEFAULT),
                                        when(new Not(ref(INTERACTIVE)), fail(s("the Linux build is missing, or not of these sources"))),
                                        new Pause(s("  Press Enter once it has finished, to check again (Ctrl+C stops)")),
                                        set(PROBLEMS, new Call(TEST_LINUX_BUILD, List.of())))),
                                new Print(str("  built in the VM from these sources: ", new Join(ref(LINUX_APPS), s(", "))), Color.DEFAULT))));
    }

    private static List<Statement> versions() {
        Expr name = field(ref(APP), STRING, "name");
        Expr version = field(ref(APP), STRING, "version");
        return body(
                step("versions"),
                forEach(CONFIG, ref(CONFIGS),
                        set(APP, new ReadJson(ref(CONFIG))),
                        set(MANIFEST, path(ref(DIST), s("apps"), field(ref(APP), STRING, "id"), s("manifest.json"))),
                        set(LATEST, s("")),
                        when(new Exists(ref(MANIFEST)), set(LATEST, field(new ReadJson(ref(MANIFEST)), STRING, "latest"))),
                        new If(new Compare(ref(LATEST), Comparison.EQ, version),
                                body(new Print(str("  ", name, " ", version, " is already published; bump it in ", ref(CONFIG), " if it changed"), Color.YELLOW)),
                                body(new Print(str("  ", name, " ", version, " (published: ",
                                        new Choose(new IsEmpty(ref(LATEST)), s("none"), ref(LATEST)), ")"), Color.DEFAULT)))));
    }

    // ---- shorthand ----------------------------------------------------------------------------------------------

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

    private static PathJoin path(Expr base, Expr... parts) {
        return new PathJoin(base, List.of(parts));
    }

    private static Field field(Expr json, Type type, String... path) {
        return new Field(json, List.of(path), type);
    }

    private static Set set(Var v, Expr value) {
        return new Set(v, value);
    }

    private static If when(Expr condition, Object... then) {
        return new If(condition, body(then), List.of());
    }

    private static ForEach forEach(Var item, Expr collection, Object... body) {
        return new ForEach(item, collection, body(body));
    }

    private static Invoke step(String name) {
        return new Invoke(STEP, List.of(s(name)));
    }

    private static Invoke fail(Expr message) {
        return new Invoke(FAIL, List.of(message));
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
