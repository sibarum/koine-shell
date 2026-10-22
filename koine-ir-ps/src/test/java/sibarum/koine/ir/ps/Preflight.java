package sibarum.koine.ir.ps;

import java.util.List;
import java.util.Optional;

import static sibarum.koine.ir.ps.Ps.*;
import static sibarum.koine.ir.ps.PsExpression.BinaryOperator.*;

/** vexelray-dist's preflight.ps1, built by hand in the PowerShell IR. */
final class Preflight {

    private Preflight() {
    }

    static final String HELP = """

              .SYNOPSIS
                Everything before publishing: builds the stack, starts every application, checks the notices and versions, and
                generates the installers without publishing them.

              .DESCRIPTION
                1. build      build.cmd: libraries in order with their tests, then each application's tests and native image
                2. start      each application's exe, from its installer.json, must still be running after a few seconds. Tests run
                              on the JVM, so this is what catches a native image that cannot start (a resource it never registered)
                3. linux      each application with a linux section needs its Linux binaries, which build.sh builds in the Linux
                              VM: logs\\linux\\BUILD.txt must name exactly the commits checked out here. Run from a console a
                              person is at, it waits for that build; otherwise it fails
                4. notices    notices\\notices.py --check
                5. versions   lists each application whose installer.json version is already published: its exe will be
                              regenerated as that same release, so bump it if it changed
                6. generate   deploy.ps1 without -Publish, then the diff here and in ..\\sibarum.github.io

                Nothing is uploaded, committed or pushed. The generated files are left in place to look at: publishing is
                deploy.ps1 -Publish, and git checkout -- apps puts them back. Logs land in logs\\. Stops at the first failing step.

              .PARAMETER SkipBuild
                Use the exes already in each application's target\\ (after fixing one application and rebuilding only it).

              .PARAMETER Seconds
                How long each application must stay up.

              .PARAMETER BuildOnly
                Stop after the notices: build, start, the Linux build and the notices checked, generating nothing. release-preflight.ps1 generates its own.
              """;

    static PsScript script() {
        return new PsScript(Optional.of(HELP),
                Optional.of(new PsScript.ParamBlock(List.of(
                        param("switch", "SkipBuild"), param("int", "Seconds", num(8)), param("switch", "BuildOnly")))),
                block(flat(
                        assign(var("ErrorActionPreference"), sq("Stop")),
                        blank(),
                        assign(var("dist"), var("PSScriptRoot")),
                        assign(var("root"), cmd("Split-Path", var("dist"), opt("Parent"))),
                        assign(var("logs"), cmd("Join-Path", var("dist"), sq("logs"))),
                        pipe(cmd("New-Item", opt("ItemType"), bare("Directory"), opt("Force"), var("logs")), cmd("Out-Null")),
                        assign(var("suite"), pipe(
                                cmd("Get-Content", paren(cmd("Join-Path", var("dist"), sq("installer-suite.json"))), opt("Raw")),
                                cmd("ConvertFrom-Json"))),
                        assign(var("apps"), foreach("m", member(var("suite"), "members"), block(
                                assign(var("config"), invokeStatic("IO.Path", "GetFullPath",
                                        paren(cmd("Join-Path", var("dist"), member(var("m"), "config"))))),
                                cast("pscustomobject", hash(
                                        entry("Config", var("config")),
                                        entry("Repo", cmd("Split-Path", var("config"), opt("Parent"))),
                                        entry("App", pipe(cmd("Get-Content", var("config"), opt("Raw")), cmd("ConvertFrom-Json")))))))),
                        blank(),
                        function("Step", List.of(param("name")), block(
                                cmd("Write-Host", dq("\n== ", var("name")), opt("ForegroundColor"), bare("Cyan")))),
                        function("Fail", List.of(param("msg")), block(
                                cmd("Write-Host", dq("PREFLIGHT FAILED: ", var("msg")), opt("ForegroundColor"), bare("Red")),
                                exit(num(1)))),
                        blank(),
                        comment("Python is not on PowerShell's PATH everywhere; the Store's python.exe stub answers but runs nothing."),
                        findPython(),
                        blank(),
                        build(),
                        blank(),
                        start(),
                        blank(),
                        comment("The Linux binaries are built in the Linux VM by build.sh, which writes logs\\linux\\BUILD.txt: the commit of every\n"
                                + "checkout it built. They are fit to publish beside these exes only while every one of those is still checked out."),
                        testLinuxBuild(),
                        blank(),
                        linux(),
                        blank(),
                        cmd("Step", sq("notices")),
                        assign(var("python"), cmd("Find-Python")),
                        if_(not(var("python")), block(cmd("Fail", sq("no Python found (python, py, or anaconda3)")))),
                        call(var("python"), paren(cmd("Join-Path", var("dist"), sq("notices\\notices.py"))), bare("--check")),
                        if_(bin(var("LASTEXITCODE"), NE, num(0)), block(cmd("Fail", sq("the notices")))),
                        if_(var("BuildOnly"), block(
                                cmd("Write-Host", dq("\nBuilt, started and checked."), opt("ForegroundColor"), bare("Green")),
                                exit(num(0)))),
                        blank(),
                        versions(),
                        blank(),
                        cmd("Step", sq("generate")),
                        tryCatch(block(call(paren(cmd("Join-Path", var("dist"), sq("deploy.ps1"))))),
                                block(cmd("Fail", dq("deploy.ps1: ", var("_"))))),
                        blank(),
                        cmd("Step", sq("diff")),
                        cmd("git", opt("C"), var("dist"), bare("status"), bare("--short")),
                        assign(var("site"), cmd("Join-Path", var("root"), sq("sibarum.github.io"))),
                        if_(cmd("Test-Path", paren(cmd("Join-Path", var("site"), sq(".git")))), block(
                                cmd("git", opt("C"), var("site"), bare("status"), bare("--short")))),
                        blank(),
                        cmd("Write-Host", dq("\nPreflight passed. Look at git diff here (and in ..\\sibarum.github.io), then .\\deploy.ps1 -Publish."),
                                opt("ForegroundColor"), bare("Green")))));
    }

    private static PsStatement findPython() {
        return function("Find-Python", List.of(), block(
                comment("the stub's complaint on stderr would otherwise stop the script"),
                assign(var("ErrorActionPreference"), sq("Continue")),
                foreach("c", arr(list(sq("python"), sq("py"),
                        dq(var("env:USERPROFILE"), "\\anaconda3\\python.exe"),
                        dq(var("env:USERPROFILE"), "\\miniconda3\\python.exe"))), block(
                        if_(cmd("Get-Command", var("c"), opt("ErrorAction"), bare("SilentlyContinue")), block(
                                call(var("c"), bare("--version")).redirect(redirect(PsRedirection.Stream.ALL, var("null"))),
                                if_(bin(var("LASTEXITCODE"), EQ, num(0)), block(return_(var("c")))))))),
                return_(var("null"))));
    }

    private static PsStatement build() {
        return if_(not(var("SkipBuild")), block(
                cmd("Step", sq("build")),
                comment("call: without it cmd /c can return 0 from a batch file that ran exit /b 1 (see the end of build.cmd).\n"
                        + "Its output is checked for FAILED as well, so a failure can never pass as a build."),
                assign(var("failed"), var("false")),
                pipe(call(bare("cmd"), bare("/c"), dq("call \"", var("dist"), "\\build.cmd\"")),
                        cmd("ForEach-Object", sb(
                                cmd("Write-Host", var("_")),
                                if_(bin(var("_"), MATCH, sq("FAILED")), block(assign(var("failed"), var("true"))))))),
                if_(bin(bin(var("LASTEXITCODE"), NE, num(0)), OR, var("failed")), block(
                        cmd("Fail", sq("the build; see logs\\"))))));
    }

    private static List<Object> start() {
        return List.of(
                cmd("Step", sq("start")),
                assign(var("dead"), arr()),
                foreach("a", var("apps"), block(
                        foreach("b", pipe(member(var("a"), "App", "windows", "binaries"),
                                cmd("Where-Object", sb(bin(member(var("_"), "source"), LIKE, sq("*.exe"))))), block(
                                assign(var("exe"), cmd("Join-Path", member(var("a"), "Repo"), member(var("b"), "source"))),
                                if_(not(paren(cmd("Test-Path", var("exe")))), block(
                                        addAssign(var("dead"), dq(sub(member(var("a"), "App", "id")), ": no ", var("exe"))),
                                        continue_())),
                                assign(var("out"), cmd("Join-Path", var("logs"), dq("start-", sub(member(var("b"), "name")), ".log"))),
                                assign(var("p"), cmd("Start-Process", var("exe"),
                                        opt("WorkingDirectory"), paren(cmd("Split-Path", var("exe"))), opt("PassThru"),
                                        opt("RedirectStandardOutput"), var("out"), opt("RedirectStandardError"), dq(var("out"), ".err"))),
                                cmd("Start-Sleep", opt("Seconds"), var("Seconds")),
                                if_(member(var("p"), "HasExited"), block(
                                        addAssign(var("dead"), dq(sub(member(var("b"), "name")), " exited within ", var("Seconds"),
                                                "s; see logs\\start-", sub(member(var("b"), "name")), ".log.err"))))
                                        .orElse(block(
                                                cmd("Write-Host", dq("  ", sub(member(var("b"), "name")), " up")),
                                                cmd("Stop-Process", var("p"), opt("Force")))))))),
                if_(var("dead"), block(
                        pipe(var("dead"), cmd("ForEach-Object", sb(cmd("Write-Host", dq("  ", var("_")), opt("ForegroundColor"), bare("Red"))))),
                        cmd("Fail", sq("an application does not start")))));
    }

    private static PsStatement testLinuxBuild() {
        return function("Test-LinuxBuild", List.of(), block(
                assign(var("stamp"), cmd("Join-Path", var("logs"), sq("linux\\BUILD.txt"))),
                if_(not(paren(cmd("Test-Path", var("stamp")))), block(
                        return_(arr(sq("no Linux build: logs\\linux\\BUILD.txt is missing"))))),
                assign(var("builtFrom"), hash()),
                foreach("line", cmd("Get-Content", var("stamp")), block(
                        if_(bin(var("line"), MATCH, sq("^\\s*(#|$)")), block(continue_())),
                        assign(var("f"), bin(var("line"), SPLIT, sq("\\s+"))),
                        assign(index(var("builtFrom"), index(var("f"), num(0))), index(var("f"), num(1))))),
                assign(var("problems"), arr()),
                foreach("repo", member(var("builtFrom"), "Keys"), block(
                        assign(var("head"), bin(paren(cmd("git", opt("C"), paren(cmd("Join-Path", var("root"), var("repo"))),
                                bare("rev-parse"), bare("HEAD"))), JOIN, sq(""))),
                        if_(bin(var("head"), NE, index(var("builtFrom"), var("repo"))), block(
                                addAssign(var("problems"), dq(var("repo"), ": the Linux build is of ",
                                        sub(invoke(index(var("builtFrom"), var("repo")), "Substring", num(0), num(9))),
                                        ", the checkout is at ",
                                        sub(if_(var("head"), block(invoke(var("head"), "Substring", num(0), num(9))))
                                                .orElse(block(sq("nothing")))))))))),
                foreach("a", pipe(var("apps"), cmd("Where-Object", sb(member(var("_"), "App", "linux")))), block(
                        assign(var("repo"), cmd("Split-Path", member(var("a"), "Repo"), opt("Leaf"))),
                        if_(not(invoke(var("builtFrom"), "ContainsKey", var("repo"))), block(
                                addAssign(var("problems"), dq(var("repo"), ": not in the Linux build")))),
                        foreach("b", member(var("a"), "App", "linux", "binaries"), block(
                                if_(not(paren(cmd("Test-Path", paren(cmd("Join-Path", member(var("a"), "Repo"), member(var("b"), "source")))))), block(
                                        addAssign(var("problems"), dq(sub(member(var("a"), "App", "id")), ": no ", sub(member(var("b"), "source")))))))))),
                var("problems")));
    }

    private static List<Object> linux() {
        return List.of(
                cmd("Step", sq("linux")),
                assign(var("linuxApps"), arr(pipe(var("apps"),
                        cmd("Where-Object", sb(member(var("_"), "App", "linux"))),
                        cmd("ForEach-Object", sb(member(var("_"), "App", "name")))))),
                if_(not(var("linuxApps")), block(cmd("Write-Host", sq("  no application has a linux section"))))
                        .orElse(block(
                                comment("Read-Host fails under -NonInteractive, and has no one to answer it with redirected input"),
                                assign(var("interactive"), bin(bin(
                                        staticMember("Environment", "UserInteractive"), AND,
                                        not(staticMember("Console", "IsInputRedirected"))), AND,
                                        not(paren(bin(invokeStatic("Environment", "GetCommandLineArgs"), MATCH, sq("^-NonI")))))),
                                while_(bin(member(paren(assign(var("problems"), arr(cmd("Test-LinuxBuild")))), "Count"), GT, num(0)), block(
                                        pipe(var("problems"), cmd("ForEach-Object", sb(
                                                cmd("Write-Host", dq("  ", var("_")), opt("ForegroundColor"), bare("Yellow"))))),
                                        cmd("Write-Host", sq("  Build them in the Linux VM, from a terminal on its desktop, in this folder: ./build.sh")),
                                        if_(not(var("interactive")), block(cmd("Fail", sq("the Linux build is missing, or not of these sources")))),
                                        pipe(cmd("Read-Host", sq("  Press Enter once it has finished, to check again (Ctrl+C stops)")), cmd("Out-Null")))),
                                cmd("Write-Host", dq("  built in the VM from these sources: ", sub(bin(var("linuxApps"), JOIN, sq(", "))))))));
    }

    private static List<Object> versions() {
        return List.of(
                cmd("Step", sq("versions")),
                foreach("a", var("apps"), block(
                        assign(var("manifest"), cmd("Join-Path", var("dist"), dq("apps\\", sub(member(var("a"), "App", "id")), "\\manifest.json"))),
                        assign(var("latest"), if_(cmd("Test-Path", var("manifest")), block(
                                member(paren(pipe(cmd("Get-Content", var("manifest"), opt("Raw")), cmd("ConvertFrom-Json"))), "latest")))),
                        if_(bin(var("latest"), EQ, member(var("a"), "App", "version")), block(
                                cmd("Write-Host", dq("  ", sub(member(var("a"), "App", "name")), " ", sub(member(var("a"), "App", "version")),
                                                " is already published; bump it in ", sub(member(var("a"), "Config")), " if it changed"),
                                        opt("ForegroundColor"), bare("Yellow"))))
                                .orElse(block(
                                        cmd("Write-Host", dq("  ", sub(member(var("a"), "App", "name")), " ", sub(member(var("a"), "App", "version")),
                                                " (published: ", sub(if_(var("latest"), block(var("latest"))).orElse(block(sq("none")))), ")")))))));
    }

    /** The arguments in order, with each list's elements in its place. */
    private static Object[] flat(Object... items) {
        List<Object> all = new java.util.ArrayList<>();
        for (Object o : items) {
            if (o instanceof List<?> l) all.addAll(l);
            else all.add(o);
        }
        return all.toArray();
    }
}
