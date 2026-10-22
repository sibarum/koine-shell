package sibarum.koine.ir.bash;

import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.bash.BashCommand.*;
import sibarum.koine.ir.bash.BashParameter.*;

import static sibarum.koine.ir.bash.Bash.*;

/** vexelray-dist's local-linux.sh, built by hand in the Bash IR: here-documents, here-strings, read loops. */
final class LocalLinuxSh {

    private LocalLinuxSh() {
    }

    /** Lines 2 to 11, which {@code --help} prints. */
    static final String HEADER = """
            deploy.ps1 -Local for Linux, run in the Linux VM: every app that has a "linux" section gets its installer and its
            release, and the suite gets a preflight pinned to those versions, all in one folder of this VM and pointing at it
            through file:// instead of GitHub. Nothing is published, and nothing outside that folder is written.

              ./local-linux.sh                    into ~/vexelray-local (replacing what is there)
              ./local-linux.sh --out <dir>        somewhere else

            Then install from it, for real, on this account: sh ~/vexelray-local/install.sh (INSTALL_ONLY=pix,calculator for
            some). Each install's updater points at that folder until a published install replaces it. It needs the Linux
            binaries in each app's target-linux/ (build.sh) and the installer CLI in vexelray-installer's target-linux/.""";

    static final String MEMBERS_PY = """
            import json, os, sys
            suite = sys.argv[1]
            for m in json.load(open(suite))["members"]:
                config = os.path.realpath(os.path.join(os.path.dirname(suite), m["config"]))
                app = json.load(open(config))
                tag = app.get("tagFormat", "v{version}").replace("{version}", app["version"])
                print("\\t".join([config, app["id"], app["version"], tag, "linux" if "linux" in app else "-", m.get("page", "")]))
            """;

    static BashScript script() {
        return new BashScript(Optional.of("/usr/bin/env bash"), List.of(
                comment(HEADER),
                cmd("set", "-uo", "pipefail"),
                blank(),
                assigns(set("dist", sub(and(cmd("cd", dq(sub(cmd("dirname", dq(p("0")))))), cmd("pwd"))))),
                assigns(set("root", sub(cmd("dirname", dq(p("dist")))))),
                assigns(set("out", dq(p("HOME"), "/vexelray-local"))),
                blank(),
                while_(cmd("[", p("#"), "-gt", "0", "]"), case_(w(dq(p("1"))),
                        item(words("--out"), assigns(set("out", orFail("2", "--out needs a folder"))), cmd("shift", "2")),
                        item(words("-h", "--help"),
                                pipe(cmd("sed", "-n", sq("2,11p"), dq(p("0"))), cmd("sed", sq("s/^# \\{0,1\\}//"))),
                                cmd("exit", "0")),
                        item(words(glob("*")),
                                cmd("echo", dq("unknown option: ", p("1"), " (--help lists them)")).redirect(toErr()),
                                cmd("exit", "2")))),
                or(cmd("[", dq(sub(cmd("uname", "-s"))), "=", "Linux", "]"), group(
                        cmd("echo", dq("local-linux.sh is the Linux one; on Windows, run deploy.ps1 -Local")).redirect(toErr()),
                        cmd("exit", "1"))),
                assigns(set("cli", sub(pipe(
                        cmd("ls", "-t", w(dq(p("root")), "/vexelray-installer/installer-core/target-linux/installer-core-", glob("*"), "-cli.jar"))
                                .redirect(toFd(2, "/dev/null")),
                        cmd("head", "-n", "1"))))),
                or(cmd("[", "-n", dq(p("cli")), "]"), group(
                        cmd("echo", dq("no installer CLI: mvn -pl installer-core package in vexelray-installer, on Linux")).redirect(toErr()),
                        cmd("exit", "1"))),
                assigns(set("out", sub(cmd("realpath", "-m", dq(p("out")))))),
                case_(w(p("out")), item(List.of(w(dq(p("root"))), w(dq(p("root")), "/", glob("*"))),
                        cmd("echo", dq("--out ", p("out"), " is inside the checkouts; give a folder of this VM's own")).redirect(toErr()),
                        cmd("exit", "2"))),
                cmd("mkdir", "-p", dq(p("out"))),
                assigns(set("base", dq("file://", p("out")))),
                comment("Replaced only when it is this script's own, or empty: --out names a folder, and a typo must not empty one."),
                if_(and(cmd("[", "-n", dq(sub(cmd("ls", "-A", dq(p("out"))))), "]"), cmd("[", "!", "-f", dq(p("out"), "/preflight-suite.json"), "]")),
                        cmd("echo", dq(p("out"), " is not empty and not an earlier local-linux.sh folder; give an empty or new one")).redirect(toErr()),
                        cmd("exit", "2")),
                and(cmd("rm", "-rf", w(dq(op("out", new Default(true, DefaultKind.ERROR, w()))), "/", glob("*"))),
                        cmd("mkdir", "-p", dq(p("out"), "/apps"), dq(p("out"), "/assets"), dq(p("out"), "/releases"))),
                blank(),
                comment("Each member of installer-suite.json, in its order: its config's full path, id, version, release tag, whether it has\n"
                        + "a linux section, and its page."),
                or(assigns(set("members", sub(cmd("python3", "-I", "-", dq(p("dist"), "/installer-suite.json")).redirect(hereDoc("EOF", MEMBERS_PY))))),
                        group(cmd("echo", dq("could not read installer-suite.json")).redirect(toErr()), cmd("exit", "1"))),
                blank(),
                assigns(array("pinned")),
                redirected(new While(false, List.of(new Simple(List.of(set("IFS", ansi("\t"))),
                                words("read", "-r", "config", "id", "version", "tag", "linux", "page"), List.of())), List.of(
                                assigns(set("entry", dq("\"config\": \"", p("config"), "\", \"version\": \"", p("version"), "\""))),
                                and(cmd("[", "-n", dq(p("page")), "]"), assigns(set("entry", dq(p("entry"), ", \"page\": \"", p("page"), "\"")))),
                                if_(cmd("[", dq(p("linux")), "=", "linux", "]"),
                                        cmd("echo", dq("== ", p("id"), " ", p("version"))),
                                        if_(not(redirected(subshell(and(cmd("cd", dq(sub(cmd("dirname", dq(p("config")))))),
                                                                cmd("java", "-jar", dq(p("cli")), "--config", dq(p("config")), "--version", dq(p("version")),
                                                                        "--out", dq(p("out"), "/apps/", p("id")), "--assets", dq(p("out"), "/assets/", p("id")),
                                                                        "--base-url", dq(p("base"), "/apps/", p("id")), "--asset-base-url", dq(p("base"), "/releases")))),
                                                        to(dq(p("out"), "/apps/", p("id"), ".log")), errToOut())),
                                                cmd("echo", dq("Generating ", p("id"), " failed; see ", p("out"), "/apps/", p("id"), ".log")).redirect(toErr()),
                                                cmd("exit", "1")),
                                        comment("Where --asset-base-url says each file is, <base>/<tag>/<file>, as on GitHub. Only what the Linux scripts\n"
                                                + "fetch: the exe and the Windows archives are deleted rather than kept beside them."),
                                        cmd("mkdir", "-p", dq(p("out"), "/releases/", p("tag"))),
                                        for_("f", List.of(w(dq(p("out"), "/assets/", p("id"), "/", p("version")), "/", glob("*"))),
                                                case_(w(p("f")),
                                                        item(words(glob("*.exe"), glob("*-windows-*.zip")), cmd("rm", "-f", dq(p("f")))),
                                                        item(words(glob("*")), cmd("ln", "-s", dq(p("f")),
                                                                dq(p("out"), "/releases/", p("tag"), "/", sub(cmd("basename", dq(p("f"))))))))),
                                        assigns(set("entry", dq(p("entry"), ", \"manifestUrl\": \"", p("base"), "/apps/", p("id"), "/manifest.json\""))))
                                        .orElse(body(cmd("echo", dq("== ", p("id"), " ", p("version"), " (no linux section: the suite says so if it is asked for)")))),
                                assigns(appendArray("pinned", dq("{ ", p("entry"), " }"))))),
                        hereString(dq(p("members")))),
                blank(),
                assigns(set("joined", sub(cmd("printf", sq("%s,\\n    "), dq(p("pinned", "@")))))),
                assigns(set("joined", op("joined", new Remove(true, false, w(",", glob("*")))))),
                cmd("cat").redirect(to(dq(p("out"), "/preflight-suite.json")), expandingHereDoc("EOF",
                        """
                                { "id": "vexelray-preflight", "name": "VexelRay preflight",
                                  "description": "Snapshot builds of every VexelRay application, built together from the same sources, to try before they are released.",
                                  "repo": "sibarum/vexelray-dist", "branch": "main", "repoPath": "preflight",
                                  "members": [
                                """, "    ", p("joined"), "\n  ] }\n")),
                or(cmd("python3", "-I", "-c", sq("import json, sys; json.load(open(sys.argv[1]))"), dq(p("out"), "/preflight-suite.json")),
                        group(cmd("echo", dq("the pinned suite is not valid JSON: ", p("out"), "/preflight-suite.json")).redirect(toErr()), cmd("exit", "1"))),
                cmd("echo", dq("== preflight suite")),
                or(cmd("java", "-jar", dq(p("cli")), "--suite", dq(p("out"), "/preflight-suite.json"), "--out", dq(p("out")), "--base-url", dq(p("base")))
                                .redirect(to(dq(p("out"), "/suite.log")), errToOut()),
                        group(cmd("echo", dq("Generating the suite failed; see ", p("out"), "/suite.log")).redirect(toErr()), cmd("exit", "1"))),
                cmd("echo"),
                cmd("echo", dq("To install from it: sh ", p("out"), "/install.sh"))));
    }
}
