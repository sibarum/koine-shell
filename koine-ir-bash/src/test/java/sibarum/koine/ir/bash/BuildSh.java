package sibarum.koine.ir.bash;

import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.bash.BashCommand.*;

import static sibarum.koine.ir.bash.Bash.*;
import static sibarum.koine.ir.bash.BashCondition.BinaryTest.MATCHES;
import static sibarum.koine.ir.bash.BashParameter.DefaultKind.USE_ALTERNATE;

/** vexelray-dist's build.sh, built by hand in the Bash IR. */
final class BuildSh {

    private BuildSh() {
    }

    /** Lines 2 to 15, which {@code --help} prints. */
    static final String HEADER = """
            The Linux half of a release, run in the Linux VM on the checkout it shares with the Windows host.
            Clean-installs the VexelRay stack in dependency order, then tests every application that has a "linux" section in
            its installer.json and builds its native image (-Pnative-linux, into target-linux/), then starts each one.
            build.cmd does the same on Windows; deploy.ps1, on Windows, generates and publishes both.

              ./build.sh                      everything, then start each application
              ./build.sh --only Pix           rebuild one application (its checkout's name) after a fix, then start it
              ./build.sh --skip-build         only start the applications already built
              ./build.sh --seconds 15         how long each application must stay up (8)
              ./build.sh --no-start           build only; for a VM with no desktop session

            Logs land in logs/linux/. Stops at the first library that fails; an application that fails is reported, and the
            run fails. Only a build where everything passed writes logs/linux/BUILD.txt, the commit each checkout was built
            from, which preflight.ps1 compares with the checkouts before it lets deploy.ps1 near the Linux binaries.""";

    static BashScript script() {
        return new BashScript(Optional.of("/usr/bin/env bash"), List.of(
                comment(HEADER),
                cmd("set", "-uo", "pipefail"),
                blank(),
                assigns(array("LIBS", "vexelray-build", "intrabuf", "atchung", "supirvast", "dxgi-bridge", "kronometer",
                        "tactroller", "vexelray", "imagelib-wrapper", "vexelray-gui", "vexelray-framework", "ffmpeg-wrapper",
                        "vexelray-codec", "vex-suite-common", "cott-engine", "cardinal", "concordance-indexer",
                        "vexelray-sim-core", "vexelray-installer")),
                assigns(array("APPS", "mainframe", "text-editor-vexel-demo", "Pix", "vexplore", "vexelray-sim-fluid",
                        "vexelray-sim-rigid", "calculator-vexel-demo", "vexelray-maistro", "vexelray-inspector")),
                blank(),
                assigns(set("dist", sub(and(cmd("cd", dq(sub(cmd("dirname", dq(p("0")))))), cmd("pwd"))))),
                assigns(set("root", sub(cmd("dirname", dq(p("dist")))))),
                assigns(set("logs", dq(p("dist"), "/logs/linux"))),
                assigns(set("stamp", dq(p("logs"), "/BUILD.txt"))),
                blank(),
                assigns(set("only"), set("build", "1"), set("start", "1"), set("seconds", "8")),
                while_(cmd("[", p("#"), "-gt", "0", "]"), case_(w(dq(p("1"))),
                        item(words("--only"), assigns(set("only", orFail("2", "--only needs an application checkout"))), cmd("shift", "2")),
                        item(words("--skip-build"), assigns(set("build", "0")), cmd("shift")),
                        item(words("--no-start"), assigns(set("start", "0")), cmd("shift")),
                        item(words("--seconds"), assigns(set("seconds", orFail("2", "--seconds needs a number"))), cmd("shift", "2")),
                        item(words("-h", "--help"),
                                pipe(cmd("sed", "-n", sq("2,15p"), dq(p("0"))), cmd("sed", sq("s/^# \\{0,1\\}//"))),
                                cmd("exit", "0")),
                        item(words(glob("*")),
                                cmd("echo", dq("unknown option: ", p("1"), " (--help lists them)")).redirect(toErr()),
                                cmd("exit", "2")))),
                or(cmd("[", dq(sub(cmd("uname", "-s"))), "=", "Linux", "]"), group(
                        cmd("echo", dq("build.sh is the Linux build; on Windows, run build.cmd")).redirect(toErr()),
                        cmd("exit", "1"))),
                if_(cmd("[", "-n", dq(p("only")), "]"),
                        or(test(new BashCondition.Binary(w(dq(" ", p("APPS", "*"), " ")), MATCHES,
                                        w(glob("*"), dq(" ", p("only"), " "), glob("*")))),
                                group(cmd("echo", dq("--only ", p("only"), ": not one of: ", p("APPS", "*"))).redirect(toErr()),
                                        cmd("exit", "2"))),
                        or(cmd("[", "-f", dq(p("stamp")), "]"), group(
                                cmd("echo", dq("--only rebuilds one application of a whole build, and there is none yet: run ./build.sh")).redirect(toErr()),
                                cmd("exit", "1")))),
                blank(),
                comment("An application is built here when its installer.json has a \"linux\" section."),
                function("has_linux", group(
                        cmd("grep", "-q", sq("\"linux\"[[:space:]]*:"), dq(p("root"), "/", p("1"), "/installer.json")).redirect(toFd(2, "/dev/null")))),
                comment("The files its linux section ships from target-linux/ (Windows sources are under target/)."),
                function("linux_sources", group(pipe(
                        cmd("grep", "-o", sq("\"source\"[[:space:]]*:[[:space:]]*\"target-linux/[^\"]*\""), dq(p("root"), "/", p("1"), "/installer.json")),
                        cmd("sed", sq("s/.*\"\\(target-linux\\/[^\"]*\\)\"/\\1/"))))),
                blank(),
                cmd("mkdir", "-p", dq(p("logs"))),
                blank(),
                comment("---- build ------------------------------------------------------------------------------------------------------"),
                build(),
                blank(),
                comment("---- start ------------------------------------------------------------------------------------------------------\n"
                        + "The tests run on the JVM, so this is what catches a native image that cannot start: one missing a resource or a\n"
                        + "foreign call it never registered in its reachability metadata."),
                or(cmd("[", p("start"), "=", "1", "]"), cmd("exit", "0")),
                if_(and(cmd("[", "-z", dq(orDefault("DISPLAY")), "]"), cmd("[", "-z", dq(orDefault("WAYLAND_DISPLAY")), "]")),
                        cmd("echo", dq("No desktop session (neither DISPLAY nor WAYLAND_DISPLAY is set), so the applications cannot be started.")).redirect(toErr()),
                        cmd("echo", dq("Run this from a terminal on the VM's desktop, or pass --no-start to skip the check.")).redirect(toErr()),
                        cmd("exit", "1")),
                assigns(set("dead", "0")),
                for_("r", words(sub(and(cmd("[", "-n", dq(p("only")), "]"), cmd("echo", dq(p("only")))).or(cmd("echo", dq(p("APPS", "@")))))),
                        or(cmd("has_linux", dq(p("r"))), cmd("continue")),
                        for_("s", words(sub(cmd("linux_sources", dq(p("r"))))),
                                case_(w(dq(p("s"))), item(words(glob("*.tar.gz"), glob("*.tgz"), glob("*.txt"), glob("*.jar")), cmd("continue"))),
                                assigns(set("bin", dq(p("root"), "/", p("r"), "/", p("s"))), set("name", sub(cmd("basename", dq(p("s")))))),
                                or(cmd("[", "-x", dq(p("bin")), "]"), group(
                                        cmd("echo", dq("  ", p("name"), ": no executable ", p("bin"))).redirect(toErr()),
                                        assigns(set("dead", "1")),
                                        cmd("continue"))),
                                bg(redirected(subshell(and(cmd("cd", dq(sub(cmd("dirname", dq(p("bin")))))), cmd("exec", dq(p("bin"))))),
                                        to(dq(p("logs"), "/start-", p("name"), ".log")), errToOut())),
                                assigns(set("pid", p("!"))),
                                cmd("sleep", dq(p("seconds"))),
                                if_(cmd("kill", "-0", dq(p("pid"))).redirect(toFd(2, "/dev/null")),
                                        cmd("echo", dq("  ", p("name"), " up")),
                                        cmd("kill", dq(p("pid"))).redirect(toFd(2, "/dev/null")),
                                        cmd("sleep", "1"),
                                        cmd("kill", "-9", dq(p("pid"))).redirect(toFd(2, "/dev/null")),
                                        cmd("wait", dq(p("pid"))).redirect(toFd(2, "/dev/null")))
                                        .orElse(body(
                                                cmd("echo", dq("  ", p("name"), " exited within ", p("seconds"), "s; see logs/linux/start-", p("name"), ".log")).redirect(toErr()),
                                                assigns(set("dead", "1")))))),
                or(cmd("[", p("dead"), "=", "0", "]"), group(cmd("echo", dq("An application does not start.")).redirect(toErr()), cmd("exit", "1"))),
                cmd("echo", dq("Every Linux application started."))));
    }

    private static BashCommand build() {
        return if_(cmd("[", p("build"), "=", "1", "]"),
                assigns(array("missing")),
                or(cmd("command", "-v", "mvn").redirect(to("/dev/null")), assigns(appendArray("missing", dq("Maven (mvn on PATH)")))),
                if_(cmd("command", "-v", "java").redirect(to("/dev/null")),
                        assigns(set("major", sub(pipe(
                                cmd("java", "-version").redirect(errToOut()),
                                cmd("sed", "-n", sq("s/.*version \"\\([0-9]*\\).*/\\1/p")),
                                cmd("head", "-1"))))),
                        or(cmd("[", dq(orDefault("major", "0")), "-ge", "25", "]"),
                                assigns(appendArray("missing", dq("GraalVM 25 (java on PATH is ", orDefault("major", "unknown"), ")")))))
                        .orElse(body(assigns(appendArray("missing", dq("GraalVM 25 (java on PATH)"))))),
                if_(and(not(cmd("command", "-v", "native-image").redirect(to("/dev/null"))),
                                cmd("[", "!", "-x", dq(orDefault("JAVA_HOME", "/nonexistent"), "/bin/native-image"), "]")),
                        assigns(appendArray("missing", dq("native-image: GraalVM 25 as JAVA_HOME, not a plain JDK")))),
                if_(cmd("[", length("missing", "@"), "-gt", "0", "]"),
                        cmd("echo", dq("Install these first (DEPLOYING.md, One-time setup):")).redirect(toErr()),
                        cmd("printf", sq("  - %s\\n"), dq(p("missing", "@"))).redirect(toErr()),
                        cmd("exit", "1")),
                blank(),
                if_(cmd("[", "-n", dq(p("only")), "]"), assigns(array("libs"), array("apps", dq(p("only")))))
                        .orElse(body(
                                comment("a build that fails part way must not pass for the last good one"),
                                cmd("rm", "-f", dq(p("stamp"))),
                                assigns(array("libs", dq(p("LIBS", "@"))), array("apps", dq(p("APPS", "@")))))),
                blank(),
                for_("r", words(dq(p("libs", "@"))),
                        cmd("echo", dq("[", p("r"), "] clean install")),
                        if_(not(mvn(List.of("mvn", "-B", "clean", "install"), "/")),
                                cmd("echo", dq("[", p("r"), "] FAILED, see logs/linux/", p("r"), ".log")).redirect(toErr()),
                                cmd("exit", "1"))),
                blank(),
                assigns(set("failed", "0"), array("built")),
                for_("r", words(dq(p("apps", "@"))),
                        if_(not(cmd("has_linux", dq(p("r")))),
                                cmd("echo", dq("[", p("r"), "] no linux section in installer.json; not built")),
                                cmd("continue")),
                        cmd("echo", dq("[", p("r"), "] tests")),
                        if_(not(mvn(List.of("mvn", "-B", "clean", "install"), "/app-")),
                                cmd("echo", dq("[", p("r"), "] FAILED, see logs/linux/app-", p("r"), ".log")).redirect(toErr()),
                                assigns(set("failed", "1")),
                                cmd("continue")),
                        cmd("echo", dq("[", p("r"), "] native image")),
                        if_(not(mvn(List.of("mvn", "-B", "-Pnative-linux", "clean", "package", "-DskipTests"), "/native-")),
                                cmd("echo", dq("[", p("r"), "] NATIVE FAILED, see logs/linux/native-", p("r"), ".log")).redirect(toErr()),
                                assigns(set("failed", "1")),
                                cmd("continue")),
                        for_("s", words(sub(cmd("linux_sources", dq(p("r"))))),
                                or(cmd("[", "-f", dq(p("root"), "/", p("r"), "/", p("s")), "]"), group(
                                        cmd("echo", dq("[", p("r"), "] the build left no ", p("s"), ", which installer.json ships")).redirect(toErr()),
                                        assigns(set("failed", "1"))))),
                        assigns(appendArray("built", dq(p("r"))))),
                or(cmd("[", p("failed"), "=", "0", "]"), group(cmd("echo", dq("Some applications failed.")).redirect(toErr()), cmd("exit", "1"))),
                blank(),
                comment("What was built, a checkout a line: <checkout> <commit>, and \"changed\" when it had uncommitted changes.\n"
                        + "preflight.ps1 reads it. --only keeps the other lines of the whole build it rebuilds one application of."),
                function("built_from", group(
                        cmd("local", "changed="),
                        and(cmd("[", "-n", dq(sub(cmd("git", "-C", dq(p("root"), "/", p("1")), "status", "--porcelain", "-uno"))), "]"),
                                assigns(set("changed", sq(" changed")))),
                        cmd("printf", sq("%s %s%s\\n"), dq(p("1")), dq(sub(cmd("git", "-C", dq(p("root"), "/", p("1")), "rev-parse", "HEAD"))), dq(p("changed"))))),
                and(redirected(group(
                                cmd("printf", sq("# Linux build, %s, on %s\\n"), dq(sub(cmd("date", sq("+%Y-%m-%d %H:%M")))), dq(sub(cmd("uname", "-n")))),
                                if_(cmd("[", "-n", dq(p("only")), "]"), cmd("grep", "-v", "-e", sq("^#"), "-e", dq("^", p("only"), " "), dq(p("stamp")))),
                                for_("r", words(
                                                op("libs", "@", new BashParameter.Default(false, USE_ALTERNATE, w(dq(p("libs", "@"))))),
                                                op("built", "@", new BashParameter.Default(false, USE_ALTERNATE, w(dq(p("built", "@")))))),
                                        cmd("built_from", dq(p("r"))))),
                                to(dq(p("stamp"), ".tmp"))),
                        cmd("mv", dq(p("stamp"), ".tmp"), dq(p("stamp")))),
                cmd("echo", dq("Build done: logs/linux/BUILD.txt")));
    }

    /** {@code (cd "$root/$r" && mvn ...) > "$logs<log>$r.log" 2>&1}. */
    private static Redirected mvn(List<String> mvn, String log) {
        return redirected(subshell(and(cmd("cd", dq(p("root"), "/", p("r"))), cmd(mvn.toArray()))),
                to(dq(p("logs"), log, p("r"), ".log")), errToOut());
    }
}
