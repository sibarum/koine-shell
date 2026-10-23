package sibarum.koine.ir.lower;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.ps.PsBlock;
import sibarum.koine.ir.ps.PsCommand;
import sibarum.koine.ir.ps.PsCommandElement;
import sibarum.koine.ir.ps.PsExpression;
import sibarum.koine.ir.ps.PsExpression.BinaryOperator;
import sibarum.koine.ir.ps.PsParameter;
import sibarum.koine.ir.ps.PsRedirection;
import sibarum.koine.ir.ps.PsScript;
import sibarum.koine.ir.ps.PsStatement;
import sibarum.koine.ir.ps.PsStringPart;

import static sibarum.koine.ir.ps.Ps.*;

/**
 * Lowers a portable script to PowerShell (5.1 and later). Every portable expression becomes a PowerShell expression,
 * so nothing is hoisted out of a condition and {@code and}, {@code or} and {@code choose} evaluate only what they
 * must.
 * <ul>
 *   <li>Lists are arrays; a function that returns one is called inside {@code @(...)}, which undoes PowerShell's
 *       unrolling of a returned array into nothing or a single value.</li>
 *   <li>Maps are {@code OrderedDictionary}s: insertion order, and keys compared case-sensitively, as in Bash and Zsh,
 *       where a hashtable literal would ignore case.</li>
 *   <li>String comparisons and matches are case-sensitive ({@code -ceq}, {@code -cmatch}); POSIX classes in a pattern
 *       become .NET ones.</li>
 *   <li>A record is a {@code [pscustomobject]}. Data read as one goes through a {@code koine_as_<Record>} function,
 *       written into the scripts that use it, which reads only the record's fields: a missing string, number or bool
 *       reads as empty, 0 or false, a missing record as {@code $null}, and a list is always an array.</li>
 *   <li>A native function's body is copied as it is, after a {@code param()} block with each parameter typed.</li>
 *   <li>{@link Statement.Fail} calls {@code koine_fail}, written into the scripts that use it, native bodies included:
 *       it prints the message in red, with the step {@code $script:KoineStep} names, and exits with 1.
 *       {@link Statement.Finally} is {@code try}/{@code finally}, which runs on {@code exit} too.</li>
 *   <li>{@link Expr.Succeeds} calls a {@code Test-Succeeds} function, written into the scripts that use it: under
 *       {@code $ErrorActionPreference = 'Stop'}, Windows PowerShell turns a native program's redirected stderr into
 *       a terminating error, and a program that is not there leaves {@code $LASTEXITCODE} as it was.</li>
 *   <li>Inside a function, a command's output goes to the host: in PowerShell a function's output is its result.</li>
 *   <li>Arguments to a program are escaped for the C runtime's rules, as Windows PowerShell passes an empty one as
 *       nothing and a {@code "} as it is: a literal here, anything else by a {@code ConvertTo-NativeArgument} function
 *       written into the scripts that use it. PowerShell 7.3 and later are set to pass them as 5.1 does. A
 *       {@code .ps1} script, {@code cmd} and batch files read their arguments their own way and get them as they
 *       are.</li>
 *   <li>A program named like one of Windows PowerShell's aliases, {@code curl}, {@code sort} or {@code where}, is
 *       called with {@code .exe}, as the alias would run a cmdlet instead.</li>
 *   <li>Programs' output is decoded as UTF-8, where Windows PowerShell would use the console's code page.</li>
 * </ul>
 */
public final class PsLowering {

    /** Automatic variables a portable name must not land on (PowerShell's names ignore case). */
    private static final Set<String> RESERVED = Set.of("args", "input", "_", "this", "null", "true", "false", "home",
            "host", "error", "matches", "pid", "profile", "pwd", "psitem", "ofs", "myinvocation", "lastexitcode",
            "foreach", "switch", "sender", "event", "eventargs", "executioncontext", "nestedpromptlevel", "shellid",
            "stacktrace", "psscriptroot", "pscommandpath", "psboundparameters", "pscmdlet", "isglobal", "env");

    /** Windows PowerShell 5.1's built-in aliases, which hide a program of the same name. */
    private static final Set<String> ALIASES = Set.of("ac", "asnp", "cat", "cd", "cfs", "chdir", "clc", "clear",
            "clhy", "cli", "clp", "cls", "clv", "cnsn", "compare", "copy", "cp", "cpi", "cpp", "curl", "cvpa", "dbp",
            "del", "diff", "dir", "dnsn", "ebp", "echo", "epal", "epcsv", "epsn", "erase", "etsn", "exsn", "fc", "fhx",
            "fl", "foreach", "ft", "fw", "gal", "gbp", "gc", "gci", "gcm", "gcs", "gdr", "ghy", "gi", "gjb", "gl", "gm",
            "gmo", "gp", "gps", "gpv", "group", "gsn", "gsnp", "gsv", "gu", "gv", "gwmi", "h", "history", "icm", "iex",
            "ihy", "ii", "ipal", "ipcsv", "ipmo", "ipsn", "irm", "ise", "iwmi", "iwr", "kill", "lp", "ls", "man", "md",
            "measure", "mi", "mount", "move", "mp", "mv", "nal", "ndr", "ni", "nmo", "npssc", "nsn", "nv", "ogv", "oh",
            "popd", "ps", "pushd", "pwd", "r", "rbp", "rcjb", "rcsn", "rd", "rdr", "ren", "ri", "rjb", "rm", "rmdir",
            "rmo", "rni", "rnp", "rp", "rsn", "rsnp", "rujb", "rv", "rvpa", "rwmi", "sajb", "sal", "saps", "sasv",
            "sbp", "sc", "select", "set", "shcm", "si", "sl", "sleep", "sls", "sort", "sp", "spjb", "spps", "spsv",
            "start", "sujb", "sv", "swmi", "tee", "trcm", "type", "wget", "where", "wjb", "write");

    private boolean inFunction;
    private boolean usesTestSucceeds;
    private boolean usesNativeArgument;
    private boolean usesFail;
    private boolean runsPrograms;
    private boolean readsOutput;
    /** The records data is read as, each with its {@code koine_as_} function, in the order they are first needed. */
    private final Map<String, Type.Record> readAs = new LinkedHashMap<>();
    private int temporaries;

    private PsLowering() {
    }

    public static PsScript lower(Script script) {
        return new PsLowering().script(script);
    }

    // ---- the script ---------------------------------------------------------------------------------------------

    private PsScript script(Script s) {
        List<PsParameter> params = new ArrayList<>();
        for (Script.Param p : s.parameters()) {
            String type = p.var().type().equals(Type.BOOL) ? "switch" : p.var().type().equals(Type.INT) ? "int" : "string";
            params.add(p.defaultValue()
                    .map(d -> param(type, name(p.var()), expr(d)))
                    .orElse(param(type, name(p.var()))));
        }
        List<PsStatement> main = topLevel(s.body());
        List<PsStatement> body = new ArrayList<>();
        body.add(assign(var("ErrorActionPreference"), sq("Stop")));
        if (runsPrograms) {
            body.add(comment("PowerShell 7.3 and later: pass programs' arguments, and leave their exit statuses, as Windows PowerShell does."));
            body.add(assign(var("PSNativeCommandArgumentPassing"), sq("Legacy")));
            body.add(assign(var("PSNativeCommandUseErrorActionPreference"), var("false")));
        }
        if (readsOutput) {
            body.add(comment("Programs' output is UTF-8, not in the console's code page. The ISE has no console to set."));
            body.add(tryCatch(block(assign(staticMember("Console", "OutputEncoding"), invokeStatic("Text.UTF8Encoding", "new", var("false")))),
                    block()));
        }
        if (usesTestSucceeds) {
            body.add(blank());
            body.add(comment("Runs a program: true when it is there and exits 0. Quiet discards its output and errors, and neither"
                    + "\nits complaints on stderr nor its absence stop the script."));
            body.add(testSucceeds());
        }
        if (usesNativeArgument) {
            body.add(blank());
            body.add(comment("An argument as a program's C runtime reads it back. Windows PowerShell passes an empty argument as nothing"
                    + "\nand a \" as it is, and quotes an argument with a space only where an even number of \" come before it."));
            body.add(convertToNativeArgument());
        }
        if (usesFail) {
            body.add(blank());
            body.add(comment("Fails the script: the message in red, with the step it is in, then exit, which runs every finally."));
            body.add(koineFail());
        }
        if (!readAs.isEmpty()) {
            body.add(blank());
            body.add(comment("Data read as a record: only the record's fields, a missing one as empty, and every list an array."));
            for (Type.Record r : readAs.values()) body.add(readAsFunction(r));
        }
        body.add(blank());
        body.addAll(main);
        return new PsScript(Optional.of(help(s)),
                params.isEmpty() ? Optional.empty() : Optional.of(new PsScript.ParamBlock(params)),
                new PsBlock(body));
    }

    private static String help(Script s) {
        StringBuilder b = new StringBuilder("\n.SYNOPSIS\n").append(indent(s.synopsis())).append("\n");
        if (!s.description().isBlank()) b.append("\n.DESCRIPTION\n").append(indent(s.description())).append("\n");
        for (Script.Param p : s.parameters())
            b.append("\n.PARAMETER ").append(p.var().name()).append("\n").append(indent(p.help())).append("\n");
        return b.toString();
    }

    private static String indent(String text) {
        return String.join("\n", text.lines().map(l -> l.isEmpty() ? "" : "  " + l).toList());
    }

    private static boolean isFunction(Statement s) {
        return s instanceof Statement.Function || s instanceof Statement.NativeFunction;
    }

    /** The script's own statements, with a blank line before each function and each comment that starts a part. */
    private List<PsStatement> topLevel(List<Statement> statements) {
        List<PsStatement> out = new ArrayList<>();
        Statement previous = null;
        for (Statement s : statements) {
            boolean opensPart = isFunction(s) && !(previous instanceof Statement.Comment)
                    || s instanceof Statement.Comment && !(previous instanceof Statement.Comment)
                    || isFunction(previous) && !isFunction(s);
            if (opensPart && previous != null) out.add(blank());
            out.addAll(statement(s));
            previous = s;
        }
        return out;
    }

    private static PsStatement testSucceeds() {
        PsCommand run = call(var("Program"), splat("Arguments"));
        return function("Test-Succeeds", List.of(param("string", "Program"), param("object[]", "Arguments", arr()), param("switch", "Quiet")), block(
                if_(not(paren(cmd("Get-Command", var("Program"), opt("ErrorAction"), bare("SilentlyContinue")))), block(return_(var("false")))),
                assign(var("ErrorActionPreference"), sq("Continue")),
                assign(var("global:LASTEXITCODE"), num(0)),
                new PsStatement.Try(
                        block(if_(var("Quiet"), block(run.redirect(redirect(PsRedirection.Stream.ALL, var("null")))))
                                .orElse(block(pipe(run, cmd("Out-Host"))))),
                        List.of(new PsStatement.Catch(List.of(), block(return_(var("false"))))),
                        Optional.empty()),
                bin(var("LASTEXITCODE"), BinaryOperator.EQ, num(0))));
    }

    /** {@link #nativeArgument(String)} as a PowerShell function, for each of the values it is given. */
    private static PsStatement convertToNativeArgument() {
        PsExpression s = var("s");
        PsExpression trailingDoubled = invokeStatic("regex", "Replace", s, sq("(\\\\+)\\z"), sq("$1$1"));
        PsExpression quotesDoubled = invokeStatic("regex", "Replace",
                invokeStatic("regex", "Replace", cast("string", var("v")), sq("(\\\\*)\""), sq("$1$1\"\"")), sq("(\\\\+)\\z"), sq("$1$1"));
        return function("ConvertTo-NativeArgument", List.of(param("object[]", "Value")), block(
                foreach("v", var("Value"), block(
                        assign(s, invokeStatic("regex", "Replace", cast("string", var("v")), sq("(\\\\*)\""), sq("$1$1\\\""))),
                        if_(bin(s, BinaryOperator.EQ, sq("")), block(sq("\"\"")))
                                .elseIf(st(bin(s, BinaryOperator.NOTMATCH, sq("\\s"))), block(s))
                                .elseIf(st(bin(s, BinaryOperator.MATCH, sq(EVEN_QUOTES_THEN_SPACE))), block(trailingDoubled))
                                .orElse(block(bin(bin(sq("\""), BinaryOperator.ADD, quotesDoubled), BinaryOperator.ADD, sq("\""))))))));
    }

    private static PsStatement koineFail() {
        PsExpression message = var("Message"), step = var("script:KoineStep");
        return function("koine_fail", List.of(param("string", "Message")), block(
                if_(step, block(assign(message, dq(step, " failed: ", message)))),
                cmd("Write-Host", message, opt("ForegroundColor"), bare("Red")),
                exit(num(1))));
    }

    /** {@code koine_as_App}: a record made from data, field by field. */
    private PsStatement readAsFunction(Type.Record r) {
        PsExpression value = var("Value");
        List<PsExpression.Entry> entries = new ArrayList<>();
        for (Type.Record.Field f : r.fields()) entries.add(entry(f.name(), stmt(readField(f.type(), member(value, f.name())))));
        return function(readAsName(r), List.of(param("Value")), block(
                if_(bin(var("null"), BinaryOperator.EQ, value), block(return_(var("null")))),
                cast("pscustomobject", hash(entries.toArray(PsExpression.Entry[]::new)))));
    }

    /** A field of data, read as its type. */
    private PsExpression readField(Type type, PsExpression value) {
        return switch (type) {
            case Type.Str s -> cast("string", value);
            case Type.Int i -> cast("long", value);
            case Type.Bool b -> cast("bool", value);
            case Type.Record r -> paren(cmd(readAsName(r), arg(value)));
            // foreach goes round no times for a missing list, and @() keeps one item a list
            case Type.ListOf l -> arr(foreach("koineItem", value, block(readField(l.element(), var("koineItem")))));
            default -> throw new IllegalArgumentException("data cannot be read as " + type);
        };
    }

    /** The name of the function that reads data as the record, registering it and the records it holds. */
    private String readAsName(Type.Record r) {
        if (!readAs.containsKey(r.name())) {
            readAs.put(r.name(), r);
            for (Type.Record.Field f : r.fields()) {
                Type t = f.type() instanceof Type.ListOf l ? l.element() : f.type();
                if (t instanceof Type.Record inner) readAsName(inner);
            }
        }
        return "koine_as_" + r.name();
    }

    /** A space with an even number of {@code "} before it, which Windows PowerShell puts the argument in quotes for. */
    private static final String EVEN_QUOTES_THEN_SPACE = "^(?:[^\"]*\"[^\"]*\")*[^\"]*\\s";

    /**
     * A program's argument, written so that Windows PowerShell passes it on as the C runtime reads it back: a
     * {@code "} as {@code \"}, and an empty argument as {@code ""}. With a space, Windows PowerShell puts it in quotes
     * itself, so only its trailing backslashes are doubled; but where every space has an odd number of {@code "}
     * before it, it would not, and the argument is quoted here, each {@code "} as {@code ""}, which the C runtime of
     * Visual Studio 2008 and later reads as one.
     */
    static String nativeArgument(String value) {
        String s = value.replaceAll("(\\\\*)\"", "$1$1\\\\\"");
        if (s.isEmpty()) return "\"\"";
        if (!Pattern.compile("(?U)\\s").matcher(s).find()) return s;
        if (Pattern.compile("(?U)" + EVEN_QUOTES_THEN_SPACE).matcher(s).find()) return s.replaceAll("(\\\\+)\\z", "$1$1");
        return "\"" + value.replaceAll("(\\\\*)\"", "$1$1\"\"").replaceAll("(\\\\+)\\z", "$1$1") + "\"";
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    private PsBlock body(List<Statement> statements) {
        List<PsStatement> out = new ArrayList<>();
        for (Statement s : statements) out.addAll(statement(s));
        return new PsBlock(out);
    }

    private List<PsStatement> statement(Statement s) {
        return switch (s) {
            case Statement.Set x -> List.of(assign(var(name(x.var())), stmt(expr(x.value()))));
            case Statement.Append x -> {
                PsExpression item = expr(x.item());
                // += with a list would add its elements; the unary comma adds it as one
                if (x.item().type() instanceof Type.ListOf) item = new PsExpression.Unary(PsExpression.UnaryOperator.COMMA, item);
                yield List.of(addAssign(var(name(x.list())), item));
            }
            case Statement.Put x -> List.of(assign(index(var(name(x.map())), expr(x.key())), stmt(expr(x.value()))));
            case Statement.If x -> List.of(ifStatement(x));
            case Statement.ForEach x -> List.of(foreach(name(x.item()), stmt(expr(x.collection())), body(x.body())));
            case Statement.While x -> List.of(while_(stmt(expr(x.condition())), body(x.body())));
            case Statement.Finally x -> List.of(new PsStatement.Try(body(x.body()), List.of(), Optional.of(body(x.cleanup()))));
            case Statement.NativeFunction x -> {
                if (x.os() != OS.Windows) throw new IllegalArgumentException(x.signature().name() + " is native to " + x.os() + ", not Windows");
                if (x.body().contains("koine_fail")) usesFail = true;
                List<PsParameter> params = x.signature().parameters().stream().map(v -> {
                    String type = nativeType(v.type());
                    return type == null ? param(name(v)) : param(type, name(v));
                }).toList();
                yield List.of(function(x.signature().name(), params, block(new PsStatement.Raw(x.body()))));
            }
            case Statement.Fail x -> {
                usesFail = true;
                yield List.of(st(cmd("koine_fail", arg(expr(x.message())))));
            }
            case Statement.Function x -> {
                boolean outer = inFunction;
                inFunction = true;
                PsBlock b = body(x.body());
                inFunction = outer;
                yield List.of(function(x.signature().name(), x.signature().parameters().stream().map(v -> param(name(v))).toList(), b));
            }
            case Statement.Return x -> List.of(x.value().<PsStatement>map(v -> return_(stmt(expr(v))))
                    .orElse(new PsStatement.Return(Optional.empty())));
            case Statement.Invoke x -> {
                PsCommand c = functionCall(x.function().name(), x.arguments());
                // a result nobody asked for would otherwise become the caller's output
                yield List.of(x.function().returns().isPresent() ? pipe(c, cmd("Out-Null")) : st(c));
            }
            case Statement.Exit x -> List.of(exit(stmt(expr(x.status()))));
            case Statement.Print x -> List.of(st(cmd("Write-Host", arg(expr(x.message())))));
            case Statement.Run x -> run(x);
            case Statement.EachLine x -> eachLine(x);
            case Statement.Comment x -> List.of(comment(x.text()));
        };
    }

    /** A native parameter's type, as language.md's table of native bodies gives it; null for a record, untyped. */
    private static String nativeType(Type t) {
        return switch (t) {
            case Type.Str s -> "string";
            case Type.Int i -> "long";
            case Type.Bool b -> "bool";
            case Type.ListOf l when l.element().equals(Type.STRING) -> "string[]";
            case Type.ListOf l when l.element().equals(Type.INT) -> "long[]";
            default -> null;
        };
    }

    /** An if, with an else that holds nothing but another if folded into {@code elseif}. */
    private PsStatement ifStatement(Statement.If x) {
        PsStatement.If f = if_(stmt(expr(x.condition())), body(x.then()));
        List<Statement> rest = x.otherwise();
        while (rest.size() == 1 && rest.getFirst() instanceof Statement.If next) {
            f = f.elseIf(stmt(expr(next.condition())), body(next.then()));
            rest = next.otherwise();
        }
        return rest.isEmpty() ? f : f.orElse(body(rest));
    }

    private List<PsStatement> run(Statement.Run x) {
        Command c = x.command();
        boolean abort = x.onFailure() == Statement.OnFailure.ABORT;
        if (c.quiet()) {
            // *> $null on a native program would stop the script at its first line on stderr
            PsExpression ok = paren(testSucceedsCall(c));
            return List.of(abort ? if_(not(ok), block(exit(num(1)))) : pipe(testSucceedsCall(c), cmd("Out-Null")));
        }
        List<PsStatement> out = new ArrayList<>();
        // a program named by a value may be a script, and one that ends without exit leaves $LASTEXITCODE as the
        // last program it ran left it
        if (abort && !(c.program() instanceof Expr.StringLit)) out.add(assign(var("global:LASTEXITCODE"), num(0)));
        PsCommand command = command(c);
        out.add(inFunction ? pipe(command, cmd("Out-Host")) : st(command));
        if (abort) out.add(if_(bin(var("LASTEXITCODE"), BinaryOperator.NE, num(0)), block(exit(var("LASTEXITCODE")))));
        return out;
    }

    private List<PsStatement> eachLine(Statement.EachLine x) {
        List<PsStatement> lines = new ArrayList<>();
        lines.add(assign(var(name(x.line())), var("_")));
        lines.addAll(body(x.body()).statements());
        // ForEach-Object runs its block in this scope, so what the body sets is still set afterwards
        readsOutput = true;
        return List.of(
                pipe(command(x.command()), cmd("ForEach-Object", new PsExpression.ScriptBlock(new PsBlock(lines)))),
                assign(var(name(x.status())), var("LASTEXITCODE")));
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    /** A program and its arguments: a plain name as a command, anything else after {@code &}. */
    private PsCommand command(Command c) {
        runsPrograms = true;
        boolean escape = escapesArguments(c.program());
        List<PsCommandElement> args = c.arguments().stream().map(a -> (PsCommandElement) programArgument(a, escape, true)).toList();
        Expr program = program(c.program());
        PsCommand command = program instanceof Expr.StringLit s && s.value().matches("[A-Za-z0-9._-]+")
                ? new PsCommand(PsCommand.Invocation.NONE, bare(s.value()), args, List.of())
                : new PsCommand(PsCommand.Invocation.CALL, arg(expr(program)), args, List.of());
        return c.quiet() ? command.redirect(redirect(PsRedirection.Stream.ALL, var("null"))) : command;
    }

    /** A literal argument as a bare word where PowerShell would pass it through unchanged, else as an expression. */
    private PsExpression argument(Expr a) {
        if (a instanceof Expr.StringLit s && s.value().matches("[A-Za-z0-9._/:=+-]+") && !s.value().equals("--"))
            return bare(s.value());
        return arg(expr(a));
    }

    /** A program's argument, escaped where it needs to be: a literal now, anything else when the script runs. */
    private PsExpression programArgument(Expr a, boolean escape, boolean bareWord) {
        if (escape && a instanceof Expr.StringLit s) a = new Expr.StringLit(nativeArgument(s.value()));
        else if (escape && !a.type().equals(Type.INT)) {
            usesNativeArgument = true;
            return paren(cmd("ConvertTo-NativeArgument", arg(expr(a))));
        }
        return bareWord ? argument(a) : expr(a);
    }

    /**
     * Whether the program reads its command line by the C runtime's rules: not {@code cmd} or a batch file. A
     * {@code .ps1} named by a value cannot be told apart, so its arguments are escaped as a program's would be.
     */
    private static boolean escapesArguments(Expr program) {
        return !(program instanceof Expr.StringLit s && s.value().toLowerCase(Locale.ROOT).matches("cmd|cmd\\.exe|.*\\.(cmd|bat)"));
    }

    /** The program, with {@code .exe} where its name alone would run one of Windows PowerShell's aliases. */
    private static Expr program(Expr p) {
        return p instanceof Expr.StringLit s && ALIASES.contains(s.value().toLowerCase(Locale.ROOT)) ? new Expr.StringLit(s.value() + ".exe") : p;
    }

    private PsCommand testSucceedsCall(Command c) {
        usesTestSucceeds = true;
        runsPrograms = true;
        boolean escape = escapesArguments(c.program());
        List<PsCommandElement> args = new ArrayList<>();
        args.add(arg(expr(program(c.program()))));
        if (!c.arguments().isEmpty())
            args.add(arr(list(c.arguments().stream().map(a -> programArgument(a, escape, false)).toArray(PsExpression[]::new))));
        if (c.quiet()) args.add(opt("Quiet"));
        return cmd("Test-Succeeds", args.toArray(PsCommandElement[]::new));
    }

    private PsCommand functionCall(String name, List<Expr> arguments) {
        return cmd(name, arguments.stream().map(a -> (PsCommandElement) arg(expr(a))).toArray(PsCommandElement[]::new));
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    private PsExpression expr(Expr e) {
        return switch (e) {
            // a line break or tab reads as `n or `t, which only double quotes have
            case Expr.StringLit s -> s.value().chars().anyMatch(c -> c < 0x20) ? dq(s.value()) : sq(s.value());
            case Expr.IntLit i -> num(i.value());
            case Expr.BoolLit b -> var(b.value() ? "true" : "false");
            case Expr.Ref r -> var(name(r.var()));
            case Expr.Interp i -> interp(i.parts());
            case Expr.ListLit l -> l.items().isEmpty() ? arr() : arr(list(l.items().stream().map(this::expr).toArray(PsExpression[]::new)));
            case Expr.EmptyMap m -> invokeStatic("System.Collections.Specialized.OrderedDictionary", "new");
            case Expr.At a -> index(primary(expr(a.list())), expr(a.index()));
            case Expr.Get g -> index(primary(expr(g.map())), expr(g.key()));
            case Expr.HasKey h -> invoke(primary(expr(h.map())), "Contains", expr(h.key()));
            case Expr.Keys k -> arr(member(primary(expr(k.map())), "Keys"));
            case Expr.IsEmpty x -> isEmpty(x.value());
            case Expr.Not n -> n.operand() instanceof Expr.IsEmpty x ? notEmpty(x.value()) : not(unaryOperand(expr(n.operand())));
            case Expr.And a -> binary(expr(a.left()), BinaryOperator.AND, expr(a.right()));
            case Expr.Or o -> binary(expr(o.left()), BinaryOperator.OR, expr(o.right()));
            case Expr.Compare c -> binary(expr(c.left()), comparison(c), expr(c.right()));
            case Expr.In x -> x.container().type().equals(Type.STRING)
                    ? invoke(primary(expr(x.container())), "Contains", expr(x.item()))
                    : binary(expr(x.container()), BinaryOperator.CCONTAINS, expr(x.item()));
            case Expr.Matches m -> binary(expr(m.text()), BinaryOperator.CMATCH, sq(Patterns.ereToDotNet(m.pattern())));
            case Expr.MakeRecord m -> {
                List<PsExpression.Entry> entries = new ArrayList<>();
                for (int i = 0; i < m.values().size(); i++)
                    entries.add(entry(m.record().fields().get(i).name(), stmt(expr(m.values().get(i)))));
                yield cast("pscustomobject", hash(entries.toArray(PsExpression.Entry[]::new)));
            }
            case Expr.FieldOf f -> member(primary(expr(f.record())), f.field());
            case Expr.ReadAs r -> paren(cmd(readAsName(r.record()), arg(expr(r.data()))));
            case Expr.Succeeds s -> paren(testSucceedsCall(s.command()));
            case Expr.Capture c -> capture(c);
            case Expr.Call c -> {
                PsCommand call = functionCall(c.function().name(), c.arguments());
                // a returned array comes back as nothing, one value, or an array: @() makes it an array again
                yield c.type() instanceof Type.ListOf ? arr(call) : paren(call);
            }
        };
    }

    private PsExpression interp(List<Expr> parts) {
        List<Object> ps = new ArrayList<>();
        for (Expr p : parts)
            ps.add(switch (p) {
                case Expr.StringLit s -> s.value();
                case Expr.IntLit i -> Long.toString(i.value());
                case Expr.Ref r -> (PsStringPart) var(name(r.var()));
                default -> expr(p) instanceof PsExpression.SubExpression s ? s : sub(stmt(expr(p)));
            });
        return dq(ps.toArray());
    }

    /** Not empty, said the way round PowerShell reads best. */
    private PsExpression notEmpty(Expr value) {
        PsExpression v = expr(value);
        return switch (value.type()) {
            case Type.ListOf l -> bin(member(arr(stmt(v)), "Count"), BinaryOperator.NE, num(0));
            case Type.MapOf m -> bin(member(primary(v), "Count"), BinaryOperator.NE, num(0));
            case Type.Record r -> bin(var("null"), BinaryOperator.NE, binaryOperand(v, BinaryOperator.NE, true));
            default -> not(isEmpty(value));
        };
    }

    private PsExpression isEmpty(Expr value) {
        PsExpression v = expr(value);
        return switch (value.type()) {
            case Type.Str s -> invokeStatic("string", "IsNullOrEmpty", v);
            case Type.ListOf l -> bin(member(arr(stmt(v)), "Count"), BinaryOperator.EQ, num(0));
            case Type.MapOf m -> bin(member(primary(v), "Count"), BinaryOperator.EQ, num(0));
            // a missing record is $null; $null on the left, or an array would be filtered rather than compared
            default -> bin(var("null"), BinaryOperator.EQ, binaryOperand(v, BinaryOperator.EQ, true));
        };
    }

    private static BinaryOperator comparison(Expr.Compare c) {
        boolean text = c.left().type().equals(Type.STRING);
        return switch (c.op()) {
            case EQ -> text ? BinaryOperator.CEQ : BinaryOperator.EQ;
            case NE -> text ? BinaryOperator.CNE : BinaryOperator.NE;
            case LT -> BinaryOperator.LT;
            case LE -> BinaryOperator.LE;
            case GT -> BinaryOperator.GT;
            case GE -> BinaryOperator.GE;
        };
    }

    /**
     * {@code $($t = (cmd) -join "`n"; if ($LASTEXITCODE -ne 0) { fallback } else { $t })}: the output, unless the
     * command failed. Stopping exits with its status, as a failed {@link Statement.Run} does.
     */
    private PsExpression capture(Expr.Capture c) {
        readsOutput = true;
        PsExpression.Variable out = var("koineTemp" + ++temporaries);
        PsStatement failed = switch (c.onFailure()) {
            case Expr.Fallback.Stop s -> exit(var("LASTEXITCODE"));
            case Expr.Fallback.Value v -> stmt(expr(v.value()));
            case Expr.Fallback.Fail f -> {
                usesFail = true;
                yield st(cmd("koine_fail", arg(expr(f.message()))));
            }
        };
        return sub(assign(out, bin(paren(command(c.command())), BinaryOperator.JOIN, dq("\n"))),
                if_(bin(var("LASTEXITCODE"), BinaryOperator.NE, num(0)), block(failed)).orElse(block(out)));
    }

    // ---- PowerShell's precedence --------------------------------------------------------------------------------

    private static PsExpression binary(PsExpression left, BinaryOperator op, PsExpression right) {
        return bin(binaryOperand(left, op, false), op, binaryOperand(right, op, true));
    }

    /**
     * An operand of a binary operator, in parentheses only where PowerShell's precedence needs them: an operator that
     * binds less tightly than the parent, or as tightly on the right (they group from the left).
     */
    private static PsExpression binaryOperand(PsExpression e, BinaryOperator parent, boolean right) {
        if (!(e instanceof PsExpression.Binary b)) return e;
        int inner = precedence(b.operator()), outer = precedence(parent);
        return inner < outer || inner == outer && right ? paren(e) : e;
    }

    /** -and, -or, -xor below the comparisons and -join, -split, -replace; those below arithmetic. */
    private static int precedence(BinaryOperator op) {
        return switch (op) {
            case AND, OR, XOR -> 0;
            case ADD, SUBTRACT -> 2;
            case MULTIPLY, DIVIDE, REMAINDER -> 3;
            case RANGE -> 4;
            case FORMAT -> 5;
            default -> 1;
        };
    }

    /** The operand of -not or -split: a binary expression goes in parentheses. */
    private static PsExpression unaryOperand(PsExpression e) {
        return e instanceof PsExpression.Binary ? paren(e) : e;
    }

    /** The target of {@code .member}, {@code [index]} or {@code .Method()}: anything but a primary goes in parentheses. */
    private static PsExpression primary(PsExpression e) {
        return switch (e) {
            case PsExpression.Variable v -> e;
            case PsExpression.Paren p -> e;
            case PsExpression.SubExpression s -> e;
            case PsExpression.ArraySubexpression a -> e;
            case PsExpression.Member m -> e;
            case PsExpression.InvokeMember m -> e;
            case PsExpression.Index i -> e;
            case PsExpression.SingleQuoted q -> e;
            case PsExpression.Expandable x -> e;
            default -> paren(e);
        };
    }

    /** A command's argument: PowerShell reads {@code [int]$x} or {@code $a -eq $b} there as words, so they go in parentheses. */
    private static PsExpression arg(PsExpression e) {
        return switch (e) {
            case PsExpression.Variable v -> e;
            case PsExpression.SingleQuoted q -> e;
            case PsExpression.Expandable x -> e;
            case PsExpression.BareWord w -> e;
            case PsExpression.IntLiteral i -> e;
            case PsExpression.Paren p -> e;
            case PsExpression.SubExpression s -> e;
            case PsExpression.ArraySubexpression a -> e;
            case PsExpression.Member m when rootedInVariable(m) -> e;
            case PsExpression.Index i when rootedInVariable(i) -> e;
            case PsExpression.InvokeMember m when rootedInVariable(m) -> e;
            default -> paren(e);
        };
    }

    private static boolean rootedInVariable(PsExpression e) {
        return switch (e) {
            case PsExpression.Variable v -> true;
            case PsExpression.Member m -> !m.isStatic() && rootedInVariable(m.target());
            case PsExpression.Index i -> rootedInVariable(i.target());
            case PsExpression.InvokeMember m -> !m.isStatic() && rootedInVariable(m.target());
            default -> false;
        };
    }

    /** An expression where a statement goes: {@code $x = (Join-Path ...)} is written {@code $x = Join-Path ...}. */
    private static PsStatement stmt(PsExpression e) {
        return e instanceof PsExpression.Paren p ? p.statement() : st(e);
    }

    private static String name(Var v) {
        return RESERVED.contains(v.name().toLowerCase(Locale.ROOT)) ? v.name() + "_" : v.name();
    }
}
