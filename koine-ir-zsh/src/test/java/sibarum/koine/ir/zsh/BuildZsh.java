package sibarum.koine.ir.zsh;

import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.zsh.ZshCommand.*;
import sibarum.koine.ir.zsh.ZshCondition.Unary;

import static sibarum.koine.ir.zsh.Zsh.*;
import static sibarum.koine.ir.zsh.ZshCondition.BinaryTest.MATCHES;
import static sibarum.koine.ir.zsh.ZshCondition.UnaryTest.*;

/** build.zsh, the Zsh port of vexelray-dist's build.sh, built by hand in the Zsh IR. */
final class BuildZsh {

    private BuildZsh() {
    }

    /** Lines 2 to 13, which {@code --help} prints. */
    static final String HEADER = """
            The Linux half of a release, ported to Zsh: what vexelray-dist's build.sh does, in Zsh's own terms. The reference
            script for the Zsh IR: arrays that do not split, modifiers, parameter flags, an always block, an anonymous function.

              ./build.zsh                      everything, then start each application
              ./build.zsh --only Pix           rebuild one application (its checkout's name) after a fix, then start it
              ./build.zsh --skip-build         only start the applications already built
              ./build.zsh --seconds 15         how long each application must stay up (8)
              ./build.zsh --no-start           build only; for a VM with no desktop session

            Logs land in logs/linux/. Stops at the first library that fails; an application that fails is reported, and the
            run fails. Only a build where everything passed writes logs/linux/BUILD.txt, the commit each checkout was built
            from, which preflight.ps1 compares with the checkouts before it lets deploy.ps1 near the Linux binaries.""";

    static ZshScript script() {
        return new ZshScript(Optional.of("/usr/bin/env zsh"), List.of(
                comment(HEADER),
                cmd("setopt", "no_unset", "pipe_fail"),
                blank(),
                assigns(array("LIBS", "vexelray-build", "intrabuf", "atchung", "supirvast", "dxgi-bridge", "kronometer",
                        "tactroller", "vexelray", "imagelib-wrapper", "vexelray-gui", "vexelray-framework", "ffmpeg-wrapper",
                        "vexelray-codec", "vex-suite-common", "cott-engine", "cardinal", "concordance-indexer",
                        "vexelray-sim-core", "vexelray-installer")),
                assigns(array("APPS", "mainframe", "text-editor-vexel-demo", "Pix", "vexplore", "vexelray-sim-fluid",
                        "vexelray-sim-rigid", "calculator-vexel-demo", "vexelray-maistro", "vexelray-inspector")),
                blank(),
                assigns(set("dist", modified("0", "A", "h"))),
                assigns(set("root", modified("dist", "h"))),
                assigns(set("logs", p("dist"), "/logs/linux")),
                assigns(set("stamp", p("logs"), "/BUILD.txt")),
                blank(),
                assigns(set("only"), set("build", "1"), set("start", "1"), set("seconds", "8")),
                while_(arith("$#"), case_(w(p("1")),
                        item(words("--only"), assigns(set("only", orFail("2", "--only needs an application checkout"))), cmd("shift", "2")),
                        item(words("--skip-build"), assigns(set("build", "0")), cmd("shift")),
                        item(words("--no-start"), assigns(set("start", "0")), cmd("shift")),
                        item(words("--seconds"), assigns(set("seconds", orFail("2", "--seconds needs a number"))), cmd("shift", "2")),
                        item(words("-h", "--help"),
                                pipe(cmd("sed", "-n", sq("2,13p"), p("0")), cmd("sed", sq("s/^# \\{0,1\\}//"))),
                                cmd("exit", "0")),
                        item(words(glob("*")), err("unknown option: ", p("1"), " (--help lists them)"), cmd("exit", "2")))),
                or(test(new ZshCondition.Binary(w(sub(cmd("uname", "-s"))), MATCHES, w("Linux"))),
                        group(err("build.zsh is the Linux build; on Windows, run build.cmd"), cmd("exit", "1"))),
                if_(test(new Unary(NOT_EMPTY, w(p("only")))),
                        or(arith("${APPS[(Ie)$only]}"), group(err("--only ", p("only"), ": not one of: ", p("APPS")), cmd("exit", "2"))),
                        or(test(new Unary(FILE, w(p("stamp")))), group(
                                err("--only rebuilds one application of a whole build, and there is none yet"), cmd("exit", "1")))),
                blank(),
                comment("An application is built here when its installer.json has a \"linux\" section."),
                function("has_linux", group(
                        cmd("grep", "-q", sq("\"linux\"[[:space:]]*:"), w(p("root"), "/", p("1"), "/installer.json")).redirect(toFd(2, "/dev/null")))),
                comment("The files its linux section ships from target-linux/ (Windows sources are under target/), one a line."),
                function("linux_sources", group(pipe(
                        cmd("grep", "-o", sq("\"source\"[[:space:]]*:[[:space:]]*\"target-linux/[^\"]*\""), w(p("root"), "/", p("1"), "/installer.json")),
                        cmd("sed", sq("s/.*\"\\(target-linux\\/[^\"]*\\)\"/\\1/"))))),
                blank(),
                cmd("mkdir", "-p", p("logs")),
                blank(),
                comment("---- build ------------------------------------------------------------------------------------------------------"),
                build(),
                blank(),
                comment("---- start ------------------------------------------------------------------------------------------------------\n"
                        + "The tests run on the JVM, so this is what catches a native image that cannot start: one missing a resource or a\n"
                        + "foreign call it never registered in its reachability metadata."),
                or(test(new ZshCondition.Binary(w(p("start")), MATCHES, w("1"))), cmd("exit", "0")),
                if_(test(new ZshCondition.And(new Unary(EMPTY, w(orDefault("DISPLAY"))), new Unary(EMPTY, w(orDefault("WAYLAND_DISPLAY"))))),
                        err("No desktop session (neither DISPLAY nor WAYLAND_DISPLAY is set), so the applications cannot be started."),
                        err("Run this from a terminal on the VM's desktop, or pass --no-start to skip the check."),
                        cmd("exit", "1")),
                assigns(array("targets", p("APPS"))),
                and(test(new Unary(NOT_EMPTY, w(p("only")))), assigns(array("targets", p("only")))),
                new AnonymousFunction(body(
                        cmd("local", "dead=0", "r", "s", "bin", "name", "pid"),
                        for_("r", words(p("targets")),
                                or(cmd("has_linux", p("r")), cmd("continue")),
                                for_("s", words(linuxSources()),
                                        case_(w(p("s")), item(words(glob("*.tar.gz"), glob("*.tgz"), glob("*.txt"), glob("*.jar")), cmd("continue"))),
                                        assigns(set("bin", p("root"), "/", p("r"), "/", p("s")), set("name", modified("s", "t"))),
                                        or(test(new Unary(EXECUTABLE, w(p("bin")))), group(
                                                err("  ", p("name"), ": no executable ", p("bin")), assigns(set("dead", "1")), cmd("continue"))),
                                        bg(redirected(subshell(and(cmd("cd", modified("bin", "h")), cmd("exec", p("bin")))),
                                                to(p("logs"), "/start-", p("name"), ".log"), errToOut())),
                                        assigns(set("pid", p("!"))),
                                        cmd("sleep", p("seconds")),
                                        if_(cmd("kill", "-0", p("pid")).redirect(toFd(2, "/dev/null")),
                                                out("  ", p("name"), " up"),
                                                cmd("kill", p("pid")).redirect(toFd(2, "/dev/null")),
                                                cmd("sleep", "1"),
                                                cmd("kill", "-9", p("pid")).redirect(toFd(2, "/dev/null")),
                                                cmd("wait", p("pid")).redirect(toFd(2, "/dev/null")))
                                                .orElse(body(
                                                        err("  ", p("name"), " exited within ", p("seconds"), "s; see logs/linux/start-", p("name"), ".log"),
                                                        assigns(set("dead", "1")))))),
                        or(arith("dead == 0"), group(err("An application does not start."), cmd("exit", "1")))), List.of()),
                out("Every Linux application started.")));
    }

    private static ZshCommand build() {
        return if_(test(new ZshCondition.Binary(w(p("build")), MATCHES, w("1"))),
                assigns(array("missing")),
                or(arith("$+commands[mvn]"), assigns(appendArray("missing", dq("Maven (mvn on PATH)")))),
                if_(arith("$+commands[java]"),
                        assigns(set("major", sub(pipe(
                                cmd("java", "-version").redirect(errToOut()),
                                cmd("sed", "-n", sq("s/.*version \"\\([0-9]*\\).*/\\1/p")),
                                cmd("head", "-1"))))),
                        or(arith("${major:-0} >= 25"),
                                assigns(appendArray("missing", dq("GraalVM 25 (java on PATH is ", orDefault("major", "unknown"), ")")))))
                        .orElse(body(assigns(appendArray("missing", dq("GraalVM 25 (java on PATH)"))))),
                if_(and(arith("! $+commands[native-image]"),
                                test(new ZshCondition.Not(new Unary(EXECUTABLE, w(orDefault("JAVA_HOME", "/nonexistent"), "/bin/native-image"))))),
                        assigns(appendArray("missing", dq("native-image: GraalVM 25 as JAVA_HOME, not a plain JDK")))),
                if_(arith("$#missing"),
                        err("Install these first (DEPLOYING.md, One-time setup):"),
                        cmd("print", "-u2", "-rl", "--", w(dq("  - "), param("missing").prefix(ZshParameter.Prefix.RC_EXPAND).build())),
                        cmd("exit", "1")),
                blank(),
                if_(test(new Unary(NOT_EMPTY, w(p("only")))), assigns(array("libs"), array("apps", p("only"))))
                        .orElse(body(
                                comment("a build that fails part way must not pass for the last good one"),
                                cmd("rm", "-f", p("stamp")),
                                assigns(array("libs", p("LIBS")), array("apps", p("APPS"))))),
                blank(),
                for_("r", words(p("libs")),
                        out("[", p("r"), "] clean install"),
                        if_(not(mvn(List.of("mvn", "-B", "clean", "install"), "/")),
                                err("[", p("r"), "] FAILED, see logs/linux/", p("r"), ".log"),
                                cmd("exit", "1"))),
                blank(),
                assigns(set("failed", "0"), array("built")),
                for_("r", words(p("apps")),
                        if_(not(cmd("has_linux", p("r"))), out("[", p("r"), "] no linux section in installer.json; not built"), cmd("continue")),
                        out("[", p("r"), "] tests"),
                        if_(not(mvn(List.of("mvn", "-B", "clean", "install"), "/app-")),
                                err("[", p("r"), "] FAILED, see logs/linux/app-", p("r"), ".log"),
                                assigns(set("failed", "1")),
                                cmd("continue")),
                        out("[", p("r"), "] native image"),
                        if_(not(mvn(List.of("mvn", "-B", "-Pnative-linux", "clean", "package", "-DskipTests"), "/native-")),
                                err("[", p("r"), "] NATIVE FAILED, see logs/linux/native-", p("r"), ".log"),
                                assigns(set("failed", "1")),
                                cmd("continue")),
                        for_("s", words(linuxSources()),
                                or(test(new Unary(FILE, w(p("root"), "/", p("r"), "/", p("s")))), group(
                                        err("[", p("r"), "] the build left no ", p("s"), ", which installer.json ships"),
                                        assigns(set("failed", "1"))))),
                        assigns(appendArray("built", p("r")))),
                or(arith("failed == 0"), group(err("Some applications failed."), cmd("exit", "1"))),
                blank(),
                comment("What was built, a checkout a line: <checkout> <commit>, and \"changed\" when it had uncommitted changes.\n"
                        + "preflight.ps1 reads it. --only keeps the other lines of the whole build it rebuilds one application of."),
                function("built_from", group(
                        cmd("local", "changed="),
                        and(test(new Unary(NOT_EMPTY, w(sub(cmd("git", "-C", w(p("root"), "/", p("1")), "status", "--porcelain", "-uno"))))),
                                assigns(set("changed", sq(" changed")))),
                        out(p("1"), " ", sub(cmd("git", "-C", w(p("root"), "/", p("1")), "rev-parse", "HEAD")), p("changed")))),
                new Always(body(and(
                                redirected(group(
                                                out("# Linux build, ", sub(cmd("date", sq("+%Y-%m-%d %H:%M"))), ", on ", sub(cmd("uname", "-n"))),
                                                if_(test(new Unary(NOT_EMPTY, w(p("only")))),
                                                        cmd("grep", "-v", "-e", sq("^#"), "-e", dq("^", p("only"), " "), p("stamp"))),
                                                for_("r", words(p("libs"), p("built")), cmd("built_from", p("r")))),
                                        to(p("stamp"), ".tmp")),
                                cmd("mv", w(p("stamp"), ".tmp"), p("stamp")))),
                        body(cmd("rm", "-f", w(p("stamp"), ".tmp")))),
                out("Build done: logs/linux/BUILD.txt"));
    }

    /** {@code ${(f)"$(linux_sources $r)"}}: its output, a word a line. */
    private static ZshParameter linuxSources() {
        return param(dq(sub(cmd("linux_sources", p("r"))))).flags("f").build();
    }

    /** {@code (cd $root/$r && mvn ...) > $logs<log>$r.log 2>&1}. */
    private static Redirected mvn(List<String> mvn, String log) {
        return redirected(subshell(and(cmd("cd", w(p("root"), "/", p("r"))), cmd(mvn.toArray()))),
                to(p("logs"), log, p("r"), ".log"), errToOut());
    }

    /** {@code print -r -- "parts"}. */
    private static Simple out(Object... parts) {
        return cmd("print", "-r", "--", dq(parts));
    }

    /** {@code print -u2 -r -- "parts"}. */
    private static Simple err(Object... parts) {
        return cmd("print", "-u2", "-r", "--", dq(parts));
    }
}
