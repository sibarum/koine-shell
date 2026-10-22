package sibarum.koine.ir.lower;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.FunctionSig;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.zsh.ZshAssignment;
import sibarum.koine.ir.zsh.ZshCommand;
import sibarum.koine.ir.zsh.ZshCondition;
import sibarum.koine.ir.zsh.ZshParameter;
import sibarum.koine.ir.zsh.ZshPrinter;
import sibarum.koine.ir.zsh.ZshRedirection;
import sibarum.koine.ir.zsh.ZshScript;
import sibarum.koine.ir.zsh.ZshWord;
import sibarum.koine.ir.zsh.ZshWordPart;

import static sibarum.koine.ir.zsh.Zsh.*;

/**
 * Lowers a portable script to Zsh (5.x, which macOS ships). The script and each function start with
 * {@code emulate -L zsh}, so the user's options don't change what it does.
 * <ul>
 *   <li>Every value is text. A bool is 1 or 0, tested with {@code (( ))}; a JSON value is its compact text, and every
 *       field read goes through {@code jq}, a missing field reading as empty (and {@code false} too, as {@code //}
 *       has it).</li>
 *   <li>Lists are arrays, which count from 1, so {@code At(i)} reads {@code [i+1]}. Maps are {@code typeset -A}, in
 *       no particular order. Where output splits into lines ({@link Expr.ReadLines}, a JSON list), blank lines are
 *       dropped, as {@code ${(f)...}} has it.</li>
 *   <li>A value that needs a command run first (a function's result, a choice, a spawned process) has it run just
 *       before the command that uses it; in a condition, inside it, so {@code and}, {@code or} and {@code choose}
 *       evaluate only what they must.</li>
 *   <li>A function returns a value in {@code REPLY}, a list (or a map's keys and values) in {@code reply}, and a bool
 *       as its exit status: {@code $(f)} would run it in a subshell, which loses what it sets, and its exit. Every
 *       variable a function sets is local to it, as in PowerShell.</li>
 *   <li>{@link Statement.OnFailure#ABORT} is {@code cmd || exit $?}, written out: {@code set -e} is switched off in
 *       conditions and in functions called from them.</li>
 *   <li>{@link Statement.EachLine} pipes into {@code while read}: Zsh runs a pipeline's last command in this shell,
 *       so what the body sets stays set, and {@code $pipestatus} has the command's status. The body reads its stdin
 *       from the command.</li>
 * </ul>
 */
public final class ZshLowering {

    /** Zsh's special parameters, which a portable name must not land on. */
    private static final Set<String> RESERVED = Set.of("status", "pipestatus", "path", "PATH", "argv", "ARGC", "match",
            "MATCH", "mbegin", "mend", "MBEGIN", "MEND", "reply", "REPLY", "options", "commands", "functions", "aliases",
            "parameters", "builtins", "modules", "signals", "funcstack", "functrace", "LINENO", "RANDOM", "SECONDS",
            "HOME", "IFS", "PWD", "OLDPWD", "USER", "UID", "EUID", "GID", "EGID", "HOST", "PPID", "SHLVL", "TTY",
            "ERRNO", "histchars", "cdpath", "fpath", "manpath", "mailpath", "module_path", "psvar", "prompt", "watch",
            "fignore", "dirstack", "jobstates", "jobtexts", "jobdirs", "nameddirs", "userdirs", "LANG", "TERM",
            "COLUMNS", "LINES", "NULLCMD", "TRY_BLOCK_ERROR", "ZSH_ARGZERO", "ZSH_VERSION", "_");

    private static final Map<Statement.Color, String> COLORS = Map.of(Statement.Color.RED, "\u001b[31m",
            Statement.Color.GREEN, "\u001b[32m", Statement.Color.YELLOW, "\u001b[33m", Statement.Color.CYAN, "\u001b[36m");

    /** Commands that the expression being lowered needs run before the command that uses it. */
    private List<ZshCommand> setup = new ArrayList<>();
    /** The temporaries of the function being lowered, which it declares local; null outside a function. */
    private Map<String, Type> functionTemps;
    private int temporaries;

    private ZshLowering() {
    }

    public static ZshScript lower(Script script) {
        return new ZshLowering().script(script);
    }

    // ---- the script ---------------------------------------------------------------------------------------------

    private ZshScript script(Script s) {
        List<ZshCommand> body = new ArrayList<>();
        body.add(cmd("emulate", "-L", "zsh"));
        body.add(blank());
        body.addAll(options(s));
        body.add(blank());
        body.addAll(topLevel(s.body()));
        return new ZshScript(Optional.of("/usr/bin/env zsh"), body);
    }

    /** The parameters' defaults, then a loop that reads {@code --name value}, {@code --flag} and {@code --help}. */
    private List<ZshCommand> options(Script s) {
        List<ZshCommand> out = new ArrayList<>();
        if (!s.parameters().isEmpty())
            out.addAll(scoped(() -> {
                List<ZshAssignment> defaults = new ArrayList<>();
                for (Script.Param p : s.parameters())
                    defaults.add(p.var().type().equals(Type.BOOL) ? set(name(p.var()), "0")
                            : set(name(p.var()), p.defaultValue().map(d -> bareWord(text(d))).orElse(w())));
                return List.of(assigns(defaults.toArray(ZshAssignment[]::new)));
            }));
        List<ZshCommand.CaseItem> items = new ArrayList<>();
        for (Script.Param p : s.parameters()) {
            String option = "--" + kebab(p.var().name());
            items.add(p.var().type().equals(Type.BOOL)
                    ? item(words(option), assigns(set(name(p.var()), "1")), cmd("shift"))
                    : item(words(option), assigns(set(name(p.var()), orFail("2", option + " needs a value"))), cmd("shift", "2")));
        }
        items.add(item(words("-h", "--help"), cmd("cat").redirect(hereDoc("EOF", help(s))), cmd("exit", "0")));
        items.add(item(List.of(w(glob("*"))),
                cmd("print", "-u2", "-r", "--", w(dq("unknown option: ", p("1"), " (--help lists them)"))), cmd("exit", "2")));
        out.add(while_(arith("$#"), case_(w(p("1")), items.toArray(ZshCommand.CaseItem[]::new))));
        for (Script.Param p : s.parameters())
            if (p.var().type().equals(Type.INT))
                out.add(either(test(new ZshCondition.Binary(w(p(name(p.var()))), ZshCondition.BinaryTest.MATCHES, w(glob("<->")))),
                        group(cmd("print", "-u2", "-r", "--", w(dq("--" + kebab(p.var().name()) + " takes a whole number, not ", p(name(p.var()))))),
                                cmd("exit", "2"))));
        return out;
    }

    private static String help(Script s) {
        StringBuilder b = new StringBuilder("Usage: ").append(s.name()).append(".zsh");
        List<String> heads = new ArrayList<>();
        for (Script.Param p : s.parameters()) {
            String head = "--" + kebab(p.var().name()) + (p.var().type().equals(Type.BOOL) ? "" : " " + kebab(p.var().name()).toUpperCase(Locale.ROOT));
            heads.add(head);
            b.append(" [").append(head).append("]");
        }
        b.append("\n\n").append(s.synopsis()).append("\n");
        if (!s.description().isBlank()) b.append("\n").append(s.description()).append("\n");
        heads.add("-h, --help");
        int width = heads.stream().mapToInt(String::length).max().orElse(0) + 3;
        b.append("\nOptions:\n");
        for (int i = 0; i < s.parameters().size(); i++) {
            Script.Param p = s.parameters().get(i);
            String help = p.help() + p.defaultValue().map(d -> switch (d) {
                case Expr.IntLit n -> " (" + n.value() + ")";
                case Expr.StringLit t -> " (" + t.value() + ")";
                default -> "";
            }).orElse("");
            b.append(option(heads.get(i), help, width));
        }
        return b.append(option("-h, --help", "Show this help.", width)).toString();
    }

    private static String option(String head, String help, int width) {
        String pad = " ".repeat(2 + width);
        return "  " + head + " ".repeat(width - head.length()) + String.join("\n" + pad, help.lines().toList()) + "\n";
    }

    /** The script's own statements, with a blank line before each function and each comment that starts a part. */
    private List<ZshCommand> topLevel(List<Statement> statements) {
        List<ZshCommand> out = new ArrayList<>();
        Statement previous = null;
        for (Statement s : statements) {
            boolean opensPart = s instanceof Statement.Function && !(previous instanceof Statement.Comment)
                    || s instanceof Statement.Comment && !(previous instanceof Statement.Comment)
                    || previous instanceof Statement.Function && !(s instanceof Statement.Function);
            if (opensPart && previous != null) out.add(blank());
            out.addAll(statement(s));
            previous = s;
        }
        return out;
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    /** Runs a lowering with a fresh list of setup commands, and gives back those commands followed by its own. */
    private List<ZshCommand> scoped(Supplier<List<ZshCommand>> lowering) {
        List<ZshCommand> outer = setup;
        setup = new ArrayList<>();
        List<ZshCommand> main = lowering.get();
        List<ZshCommand> out = new ArrayList<>(setup);
        out.addAll(main);
        setup = outer;
        return out;
    }

    /** A body: Zsh has no empty one, so nothing at all is {@code :}. */
    private List<ZshCommand> body(List<Statement> statements) {
        List<ZshCommand> out = new ArrayList<>();
        for (Statement s : statements) out.addAll(statement(s));
        return out.isEmpty() ? List.of(cmd(":")) : out;
    }

    private List<ZshCommand> statement(Statement s) {
        return scoped(() -> switch (s) {
            case Statement.Set x -> assign(name(x.var()), x.var().type(), x.value());
            case Statement.Append x -> List.of(assigns(new ZshAssignment(name(x.list()), Optional.empty(), true,
                    new ZshAssignment.Array(List.of(arg(element(x.item())))))));
            case Statement.Put x -> List.of(assigns(new ZshAssignment(name(x.map()), Optional.of(subscript(text(x.key()))), false,
                    new ZshAssignment.Scalar(bareWord(element(x.value()))))));
            case Statement.If x -> List.of(ifCommand(x));
            case Statement.ForEach x -> List.of(new ZshCommand.For(List.of(name(x.item())), Optional.of(listWords(x.collection())), body(x.body())));
            case Statement.While x -> List.of(new ZshCommand.While(false, conditionList(x.condition()), body(x.body())));
            case Statement.Break x -> List.of(cmd("break"));
            case Statement.Continue x -> List.of(cmd("continue"));
            case Statement.Function x -> List.of(function(x));
            case Statement.Return x -> return_(x);
            case Statement.Invoke x -> List.of(call(x.function(), x.arguments()));
            case Statement.Exit x -> List.of(cmd("exit", arg(text(x.status()))));
            case Statement.Print x -> List.of(cmd("print", "-r", "--", colored(arg(text(x.message())), x.color())));
            case Statement.Pause x -> {
                List<ZshWordPart> prompt = new ArrayList<>(List.of(new ZshWordPart.Literal("?")));
                prompt.addAll(text(x.message()));
                yield List.of(cmd("read", "-r", arg(prompt)));
            }
            case Statement.Run x -> {
                ZshCommand.Simple c = command(x.command());
                yield List.of(x.onFailure() == Statement.OnFailure.ABORT ? either(c, cmd("exit", p("?"))) : c);
            }
            case Statement.EachLine x -> eachLine(x);
            case Statement.Kill x -> List.of(cmd("kill", arg(text(x.process()))));
            case Statement.Sleep x -> List.of(cmd("sleep", arg(text(x.seconds()))));
            case Statement.MakeDirs x -> List.of(cmd("mkdir", "-p", arg(text(x.path()))));
            case Statement.Comment x -> List.of(comment(x.text()));
        });
    }

    /** An if, with an else that holds nothing but another if folded into {@code elif}. */
    private ZshCommand ifCommand(Statement.If x) {
        ZshCommand.If f = new ZshCommand.If(List.of(new ZshCommand.Clause(conditionList(x.condition()), body(x.then()))), Optional.empty());
        List<Statement> rest = x.otherwise();
        while (rest.size() == 1 && rest.getFirst() instanceof Statement.If next) {
            f = f.elif(conditionList(next.condition()), body(next.then()));
            rest = next.otherwise();
        }
        return rest.isEmpty() ? f : f.orElse(body(rest));
    }

    /**
     * Sets a variable. A choice sets it in each branch, a call copies it from {@code REPLY} or {@code reply}, a bool
     * is set to 1 or 0 by its test.
     */
    private List<ZshCommand> assign(String name, Type type, Expr value) {
        if (value instanceof Expr.Choose c && !type.equals(Type.BOOL))
            return List.of(new ZshCommand.If(List.of(new ZshCommand.Clause(conditionList(c.condition()),
                    scoped(() -> assign(name, type, c.then())))), Optional.of(scoped(() -> assign(name, type, c.otherwise())))));
        if (value instanceof Expr.Call c && !type.equals(Type.BOOL)) {
            List<ZshCommand> out = new ArrayList<>(List.of(call(c.function(), c.arguments())));
            out.addAll(switch (type) {
                case Type.ListOf l -> List.of(assigns(new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Array(List.of(w(dq(p("reply", "@"))))))));
                case Type.MapOf m -> mapAssign(name, List.of(w(dq(p("reply", "@")))));
                default -> List.of(assigns(set(name, p("REPLY"))));
            });
            return out;
        }
        return switch (type) {
            case Type.Bool b -> switch (value) {
                case Expr.BoolLit l -> List.of(assigns(set(name, l.value() ? "1" : "0")));
                case Expr.Ref r -> List.of(assigns(set(name, p(name(r.var())))));
                default -> List.of(either(both(check(value), assigns(set(name, "1"))), assigns(set(name, "0"))));
            };
            case Type.ListOf l -> List.of(assigns(new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Array(listWords(value)))));
            case Type.MapOf m -> mapAssign(name, mapWords(value));
            default -> List.of(assigns(set(name, bareWord(text(value)))));
        };
    }

    /** {@code name=(k v ...)}; outside a function, after a {@code typeset -A} that makes it a map. */
    private List<ZshCommand> mapAssign(String name, List<ZshWord> keysAndValues) {
        ZshCommand set = assigns(new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Array(keysAndValues)));
        return functionTemps != null ? List.of(set) : List.of(cmd("typeset", "-A", name), set);
    }

    /** A map's keys and values, one after another. */
    private List<ZshWord> mapWords(Expr map) {
        return map instanceof Expr.EmptyMap ? List.of() : List.of(w(dq(param(named(map)).flags("@kv").build())));
    }

    private ZshCommand function(Statement.Function x) {
        Map<String, Type> outerTemps = functionTemps;
        functionTemps = new LinkedHashMap<>();
        List<ZshCommand> b = body(x.body());
        List<ZshCommand> all = new ArrayList<>(List.of(cmd("emulate", "-L", "zsh")));
        all.addAll(parameters(x.signature()));
        Map<String, Type> locals = new LinkedHashMap<>();
        assigned(x.body(), locals);
        x.signature().parameters().forEach(v -> locals.remove(name(v)));
        locals.putAll(functionTemps);
        all.addAll(declarations(locals));
        all.addAll(b);
        functionTemps = outerTemps;
        return new ZshCommand.Function(List.of(x.signature().name()), false, group(all.toArray(ZshCommand[]::new)));
    }

    /** {@code local name=$1 ...}; a list takes the rest of the arguments, so it can only be the last. */
    private List<ZshCommand> parameters(FunctionSig sig) {
        List<Object> scalars = new ArrayList<>(List.of("local"));
        List<ZshCommand> out = new ArrayList<>();
        for (int i = 0; i < sig.parameters().size(); i++) {
            Var v = sig.parameters().get(i);
            switch (v.type()) {
                case Type.ListOf l -> {
                    if (i != sig.parameters().size() - 1)
                        throw new IllegalArgumentException(sig.name() + ": a list parameter takes the rest of the arguments, so it must be last");
                    out.add(cmd("local", "-a", name(v)));
                    out.add(assigns(new ZshAssignment(name(v), Optional.empty(), false,
                            new ZshAssignment.Array(List.of(w(dq(p("@", (i + 1) + ",-1"))))))));
                }
                case Type.MapOf m -> throw new IllegalArgumentException(sig.name() + ": Zsh cannot pass a map to a function");
                default -> scalars.add(w(name(v) + "=", p(String.valueOf(i + 1))));
            }
        }
        if (scalars.size() > 1) out.addFirst(cmd(scalars.toArray()));
        return out;
    }

    /** The variables a body sets, not counting those of the functions it defines. */
    private static void assigned(List<Statement> body, Map<String, Type> out) {
        for (Statement s : body)
            switch (s) {
                case Statement.Set x -> out.putIfAbsent(name(x.var()), x.var().type());
                case Statement.ForEach x -> {
                    out.putIfAbsent(name(x.item()), x.item().type());
                    assigned(x.body(), out);
                }
                case Statement.If x -> {
                    assigned(x.then(), out);
                    assigned(x.otherwise(), out);
                }
                case Statement.While x -> assigned(x.body(), out);
                case Statement.EachLine x -> {
                    out.putIfAbsent(name(x.line()), x.line().type());
                    assigned(x.body(), out);
                    out.putIfAbsent(name(x.status()), x.status().type());
                }
                default -> {
                }
            }
    }

    /** {@code local a b}, {@code local -a xs}, {@code local -A m}. */
    private static List<ZshCommand> declarations(Map<String, Type> locals) {
        List<Object> scalars = new ArrayList<>(List.of("local")), lists = new ArrayList<>(List.of("local", "-a")),
                maps = new ArrayList<>(List.of("local", "-A"));
        locals.forEach((n, t) -> (t instanceof Type.ListOf ? lists : t instanceof Type.MapOf ? maps : scalars).add(n));
        List<ZshCommand> out = new ArrayList<>();
        if (scalars.size() > 1) out.add(cmd(scalars.toArray()));
        if (lists.size() > 2) out.add(cmd(lists.toArray()));
        if (maps.size() > 2) out.add(cmd(maps.toArray()));
        return out;
    }

    /** A value goes back in {@code REPLY} or {@code reply}, a bool as the exit status. */
    private List<ZshCommand> return_(Statement.Return x) {
        if (x.value().isEmpty()) return List.of(cmd("return"));
        Expr v = x.value().get();
        List<ZshCommand> out = new ArrayList<>(switch (v.type()) {
            case Type.Bool b when v instanceof Expr.BoolLit l -> List.<ZshCommand>of();
            case Type.Bool b -> List.of(check(v));
            case Type.ListOf l -> assign("reply", l, v);
            case Type.MapOf m -> List.of(assigns(new ZshAssignment("reply", Optional.empty(), false, new ZshAssignment.Array(mapWords(v)))));
            default -> assign("REPLY", v.type(), v);
        });
        out.add(v instanceof Expr.BoolLit l ? cmd("return", l.value() ? "0" : "1") : cmd("return"));
        return out;
    }

    private List<ZshCommand> eachLine(Statement.EachLine x) {
        String line = name(x.line());
        // || [[ -n $line ]] keeps a last line that has no line break
        ZshCommand read = either(new ZshCommand.Simple(List.of(set("IFS")), words("read", "-r", line), List.of()),
                test(new ZshCondition.Unary(ZshCondition.UnaryTest.NOT_EMPTY, w(p(line)))));
        return List.of(
                pipe(command(x.command()), new ZshCommand.While(false, List.of(read), body(x.body()))),
                assigns(set(name(x.status()), p("pipestatus", "1"))));
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    private ZshCommand.Simple command(Command c) {
        List<ZshWord> ws = new ArrayList<>();
        ws.add(c.program() instanceof Expr.StringLit s ? literalWord(s.value()) : arg(text(c.program())));
        for (Expr a : c.arguments()) {
            if (a.type() instanceof Type.ListOf) ws.addAll(listWords(a));
            else ws.add(arg(text(a)));
        }
        ZshCommand.Simple s = new ZshCommand.Simple(List.of(), ws, List.of());
        return c.quiet() ? s.redirect(new ZshRedirection.ToFile(Optional.empty(), ZshRedirection.FileOperator.OUT_AND_ERR, w("/dev/null"))) : s;
    }

    private ZshCommand.Simple call(FunctionSig f, List<Expr> arguments) {
        List<ZshWord> ws = new ArrayList<>(List.of(w(f.name())));
        ws.addAll(callArguments(f, arguments));
        return new ZshCommand.Simple(List.of(), ws, List.of());
    }

    private List<ZshWord> callArguments(FunctionSig f, List<Expr> arguments) {
        List<ZshWord> out = new ArrayList<>();
        for (Expr a : arguments)
            switch (a.type()) {
                case Type.ListOf l -> out.addAll(listWords(a));
                case Type.MapOf m -> throw new IllegalArgumentException(f.name() + ": Zsh cannot pass a map to a function");
                default -> out.add(arg(text(a)));
            }
        return out;
    }

    /** {@code (cd dir && exec program args) > out 2> err &}; its process id is then {@code $!}. */
    private ZshCommand spawn(Expr.Spawn s) {
        ZshCommand.Simple c = command(s.command());
        List<ZshWord> exec = new ArrayList<>(List.of(w("exec")));
        exec.addAll(c.words());
        ZshCommand inner = both(cmd("cd", arg(text(s.workDir()))), new ZshCommand.Simple(List.of(), exec, List.of()));
        return new ZshCommand.Background(redirected(subshell(inner), to(arg(text(s.stdout()))), toFd(2, arg(text(s.stderr())))), false);
    }

    private static ZshWord colored(ZshWord message, Statement.Color color) {
        if (color == Statement.Color.DEFAULT) return message;
        String on = COLORS.get(color), off = "\u001b[0m";
        if (message.parts().stream().allMatch(p -> p instanceof ZshWordPart.Literal || p instanceof ZshWordPart.SingleQuoted
                || p instanceof ZshWordPart.AnsiC))
            return w(ansi(on + literalText(message) + off));
        List<ZshWordPart> parts = new ArrayList<>(List.of(ansi(on)));
        for (ZshWordPart p : message.parts()) addMerging(parts, p);
        addMerging(parts, ansi(off));
        return new ZshWord(parts);
    }

    private static String literalText(ZshWord word) {
        StringBuilder b = new StringBuilder();
        for (ZshWordPart p : word.parts())
            b.append(switch (p) {
                case ZshWordPart.Literal l -> l.text();
                case ZshWordPart.SingleQuoted q -> q.text();
                case ZshWordPart.AnsiC a -> a.text();
                default -> throw new IllegalStateException(p.toString());
            });
        return b.toString();
    }

    /** Adds a part, run together with an {@code $'...'} before it. */
    private static void addMerging(List<ZshWordPart> parts, ZshWordPart p) {
        if (p instanceof ZshWordPart.AnsiC a && !parts.isEmpty() && parts.getLast() instanceof ZshWordPart.AnsiC before)
            parts.set(parts.size() - 1, ansi(before.text() + a.text()));
        else parts.add(p);
    }

    // ---- conditions ---------------------------------------------------------------------------------------------

    /** A bool as the commands whose exit status is its value: what it needs run first, then its test. */
    private List<ZshCommand> conditionList(Expr e) {
        return scoped(() -> List.of(check(e)));
    }

    /** A bool as one command, in braces when it needs commands run first. */
    private ZshCommand condition(Expr e) {
        List<ZshCommand> c = conditionList(e);
        return c.size() == 1 ? c.getFirst() : new ZshCommand.BraceGroup(c);
    }

    private ZshCommand check(Expr e) {
        return switch (e) {
            case Expr.BoolLit b -> cmd(b.value() ? "true" : "false");
            case Expr.Ref r -> arith(name(r.var()));
            case Expr.Not n -> switch (n.operand()) {
                case Expr.IsEmpty x -> notEmpty(x.value());
                case Expr.Compare c -> check(new Expr.Compare(c.left(), switch (c.op()) {
                    case EQ -> Expr.Comparison.NE;
                    case NE -> Expr.Comparison.EQ;
                    case LT -> Expr.Comparison.GE;
                    case LE -> Expr.Comparison.GT;
                    case GT -> Expr.Comparison.LE;
                    case GE -> Expr.Comparison.LT;
                }, c.right()));
                default -> negate(condition(n.operand()));
            };
            case Expr.And a -> both(condition(a.left()), condition(a.right()));
            case Expr.Or o -> either(condition(o.left()), condition(o.right()));
            case Expr.Compare c -> c.left().type().equals(Type.INT)
                    ? arith(arithmetic(c.left()) + " " + switch (c.op()) {
                        case EQ -> "==";
                        case NE -> "!=";
                        case LT -> "<";
                        case LE -> "<=";
                        case GT -> ">";
                        case GE -> ">=";
                    } + " " + arithmetic(c.right()))
                    : binary(bareWord(text(c.left())), c.op() == Expr.Comparison.EQ ? ZshCondition.BinaryTest.MATCHES
                            : ZshCondition.BinaryTest.NOT_MATCHES, patternWord(text(c.right())));
            case Expr.Choose c -> new ZshCommand.If(List.of(new ZshCommand.Clause(conditionList(c.condition()), conditionList(c.then()))),
                    Optional.of(conditionList(c.otherwise())));
            case Expr.Matches m -> binary(bareWord(text(m.text())), ZshCondition.BinaryTest.REGEX, literalWord(m.pattern()));
            case Expr.EndsWith x -> {
                List<ZshWordPart> pattern = new ArrayList<>(List.of(glob("*")));
                pattern.addAll(patternWord(text(x.suffix())).parts());
                yield binary(bareWord(text(x.text())), ZshCondition.BinaryTest.MATCHES, new ZshWord(pattern));
            }
            case Expr.HasKey h -> arith(ZshPrinter.print(w(param(named(h.map())).prefix(ZshParameter.Prefix.IS_SET)
                    .subscript(subscript(text(h.key()))).build())));
            case Expr.IsEmpty x -> isEmpty(x.value());
            case Expr.Exists x -> unary(ZshCondition.UnaryTest.EXISTS, bareWord(text(x.path())));
            case Expr.Field f -> jqTest(f, " == true");
            case Expr.Succeeds s -> command(s.command());
            case Expr.CommandExists c -> cmd("whence", "-p", "--", arg(text(c.name()))).redirect(to("/dev/null"));
            case Expr.IsRunning r -> cmd("kill", "-0", arg(text(r.process()))).redirect(toFd(2, "/dev/null"));
            case Expr.IsInteractive i -> unary(ZshCondition.UnaryTest.TERMINAL, w("0"));
            case Expr.Call c -> call(c.function(), c.arguments());
            case Expr.At a -> arith(ZshPrinter.print(new ZshWord(text(a))));
            case Expr.Get g -> arith(ZshPrinter.print(new ZshWord(text(g))));
            default -> throw new IllegalArgumentException("not a bool: " + e);
        };
    }

    private ZshCommand isEmpty(Expr value) {
        return switch (value.type()) {
            case Type.Str s -> unary(ZshCondition.UnaryTest.EMPTY, bareWord(text(value)));
            case Type.Json j when value instanceof Expr.Field f -> jqTest(f, " == null");
            case Type.Json j -> {
                ZshWord v = bareWord(text(value));
                yield test(new ZshCondition.Or(new ZshCondition.Unary(ZshCondition.UnaryTest.EMPTY, v),
                        new ZshCondition.Binary(v, ZshCondition.BinaryTest.MATCHES, w("null"))));
            }
            default -> arith(ZshPrinter.print(w(count(value))) + " == 0");
        };
    }

    /** Not empty, said the way round Zsh reads best. */
    private ZshCommand notEmpty(Expr value) {
        return switch (value.type()) {
            case Type.Str s -> unary(ZshCondition.UnaryTest.NOT_EMPTY, bareWord(text(value)));
            case Type.Json j when value instanceof Expr.Field f -> jqTest(f, " != null");
            case Type.Json j -> {
                ZshWord v = bareWord(text(value));
                yield test(new ZshCondition.And(new ZshCondition.Unary(ZshCondition.UnaryTest.NOT_EMPTY, v),
                        new ZshCondition.Binary(v, ZshCondition.BinaryTest.NOT_MATCHES, w("null"))));
            }
            default -> arith(ZshPrinter.print(w(count(value))) + " != 0");
        };
    }

    private static ZshCommand unary(ZshCondition.UnaryTest t, ZshWord operand) {
        return test(new ZshCondition.Unary(t, operand));
    }

    private static ZshCommand binary(ZshWord left, ZshCondition.BinaryTest t, ZshWord right) {
        return test(new ZshCondition.Binary(left, t, right));
    }

    private static ZshCommand negate(ZshCommand c) {
        return switch (c) {
            case ZshCommand.Conditional x -> test(new ZshCondition.Not(x.condition() instanceof ZshCondition.And
                    || x.condition() instanceof ZshCondition.Or ? new ZshCondition.Paren(x.condition()) : x.condition()));
            case ZshCommand.Arithmetic a -> arith("! " + (a.expression().matches("[A-Za-z_][A-Za-z0-9_]*|\\$\\{[^}]*}")
                    ? a.expression() : "(" + a.expression() + ")"));
            case ZshCommand.Pipeline p when p.negated() && p.commands().size() == 1 -> p.commands().getFirst();
            case ZshCommand.AndOr a -> not(group(a));
            default -> not(c);
        };
    }

    /** {@code [[ a && b ]]} or {@code (( a && b ))} when both sides are one of those, else {@code a && b}. */
    private static ZshCommand both(ZshCommand a, ZshCommand b) {
        if (a instanceof ZshCommand.Conditional x && b instanceof ZshCommand.Conditional y)
            return test(new ZshCondition.And(orInParens(x.condition()), orInParens(y.condition())));
        if (a instanceof ZshCommand.Arithmetic x && b instanceof ZshCommand.Arithmetic y)
            return arith(orInParens(x.expression()) + " && " + orInParens(y.expression()));
        // && and || group from the left, so only an and-or list on the right needs braces
        ZshCommand right = b instanceof ZshCommand.AndOr ? group(b) : b;
        return a instanceof ZshCommand.AndOr l ? l.and(right) : and(a, right);
    }

    private static ZshCommand either(ZshCommand a, ZshCommand b) {
        if (a instanceof ZshCommand.Conditional x && b instanceof ZshCommand.Conditional y)
            return test(new ZshCondition.Or(x.condition(), y.condition()));
        if (a instanceof ZshCommand.Arithmetic x && b instanceof ZshCommand.Arithmetic y)
            return arith(x.expression() + " || " + y.expression());
        ZshCommand right = b instanceof ZshCommand.AndOr ? group(b) : b;
        return a instanceof ZshCommand.AndOr l ? l.or(right) : or(a, right);
    }

    private static ZshCondition orInParens(ZshCondition c) {
        return c instanceof ZshCondition.Or ? new ZshCondition.Paren(c) : c;
    }

    /** An arithmetic expression with a {@code ||} outside parentheses, in parentheses. */
    private static String orInParens(String arithmetic) {
        int depth = 0;
        for (int i = 0; i < arithmetic.length(); i++) {
            char c = arithmetic.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && arithmetic.startsWith("||", i)) return "(" + arithmetic + ")";
        }
        return arithmetic;
    }

    // ---- JSON ---------------------------------------------------------------------------------------------------

    /**
     * {@code jq option 'program'} on a JSON value: the file itself for a document read straight from one, else the
     * text as a here-string. A field of a field is one path.
     */
    private ZshCommand.Simple jq(Expr json, List<String> path, String option, String rest) {
        List<String> all = new ArrayList<>(path);
        while (json instanceof Expr.Field f && f.type().equals(Type.JSON)) {
            all.addAll(0, f.path());
            json = f.json();
        }
        StringBuilder program = new StringBuilder();
        for (String n : all)
            // .a["b-c"] rather than .a.["b-c"], which jq 1.6 does not read
            program.append(n.matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + n
                    : (program.isEmpty() ? "." : "") + "[\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
        ZshWord filter = literalWord(program.append(rest).toString());
        if (json instanceof Expr.ReadJson r) return cmd("jq", option, filter, arg(text(r.path())));
        return cmd("jq", option, filter).redirect(hereString(bareWord(text(json))));
    }

    private ZshWordPart field(Expr.Field f) {
        return sub(switch (f.type()) {
            case Type.Str s -> jq(f.json(), f.path(), "-r", " // empty");
            case Type.Int i -> jq(f.json(), f.path(), "-r", " // 0");
            case Type.ListOf l -> jq(f.json(), f.path(), l.element().equals(Type.STRING) ? "-r" : "-c", "[]?");
            default -> jq(f.json(), f.path(), "-c", " // empty");
        });
    }

    /** {@code jq -e}, whose exit status says whether the field compares as given. */
    private ZshCommand jqTest(Expr.Field f, String comparison) {
        return jq(f.json(), f.path(), "-e", comparison).redirect(to("/dev/null"));
    }

    // ---- values -------------------------------------------------------------------------------------------------

    /**
     * A string, number, bool, JSON value or process as word parts that double quotes can hold: literal text,
     * parameters and command substitutions.
     */
    private List<ZshWordPart> text(Expr e) {
        if (e.type() instanceof Type.ListOf || e.type() instanceof Type.MapOf)
            throw new IllegalArgumentException("a list or map is not text: " + e);
        return switch (e) {
            case Expr.StringLit s -> s.value().isEmpty() ? List.of() : List.of(new ZshWordPart.Literal(s.value()));
            case Expr.IntLit i -> List.of(new ZshWordPart.Literal(Long.toString(i.value())));
            case Expr.BoolLit b -> List.of(new ZshWordPart.Literal(b.value() ? "1" : "0"));
            case Expr.Ref r -> List.of(p(name(r.var())));
            case Expr.Interp i -> {
                List<ZshWordPart> out = new ArrayList<>();
                for (Expr p : i.parts()) out.addAll(text(p));
                yield out;
            }
            case Expr.Count c -> List.of(count(c.collection()));
            case Expr.At a -> List.of(param(named(a.list())).subscript(a.index() instanceof Expr.IntLit n
                    ? Long.toString(n.value() + 1) : arithmetic(a.index()) + "+1").build());
            case Expr.Get g -> List.of(param(named(g.map())).subscript(subscript(text(g.key()))).build());
            case Expr.Choose c when !c.type().equals(Type.BOOL) -> List.of(p(temporary(c)));
            case Expr.Take t -> List.of(take(text(t.text()), arithmetic(t.count())));
            case Expr.Join j -> List.of(join(j));
            case Expr.ScriptDir d -> List.of(scriptDir());
            case Expr.ScriptPath p -> List.of(scriptDir(), new ZshWordPart.Literal("/" + p.name() + ".zsh"));
            case Expr.Home h -> List.of(p("HOME"));
            case Expr.Env v -> {
                if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw new IllegalArgumentException("not a name Zsh can read: " + v.name());
                yield List.of(p(v.name()));
            }
            case Expr.PathJoin p -> {
                List<ZshWordPart> out = new ArrayList<>(text(p.base()));
                for (Expr part : p.parts()) {
                    out.add(new ZshWordPart.Literal("/"));
                    out.addAll(text(part));
                }
                yield out;
            }
            case Expr.Parent p -> List.of(modify(text(p.path()), "h"));
            case Expr.FileName f -> List.of(modify(text(f.path()), "t"));
            case Expr.FullPath f -> List.of(modify(text(f.path()), "A"));
            case Expr.ReadJson r -> List.of(readFile(r.path()));
            case Expr.Field f when !f.type().equals(Type.BOOL) -> List.of(field(f));
            case Expr.Capture c -> List.of(new ZshWordPart.CommandSubstitution(List.of(command(c.command()))));
            case Expr.Spawn s -> {
                setup.add(spawn(s));
                yield List.of(p("!"));
            }
            case Expr.Call c when !c.type().equals(Type.BOOL) -> List.of(p(temporary(c)));
            // a bool from a test: 1 or 0, through a temporary
            default -> List.of(p(temporary(e)));
        };
    }

    /** A list or map element, a value or a flag. */
    private List<ZshWordPart> element(Expr e) {
        if (e.type() instanceof Type.ListOf || e.type() instanceof Type.MapOf)
            throw new IllegalArgumentException("Zsh arrays do not nest: " + e);
        return text(e);
    }

    /** A list as the words it spreads into: {@code "${xs[@]}"}, {@code ${=line}}, {@code ${(f)"$(...)"}}. */
    private List<ZshWord> listWords(Expr e) {
        return switch (e) {
            case Expr.ListLit l -> l.items().stream().map(i -> arg(element(i))).toList();
            case Expr.Keys k -> List.of(w(dq(param(named(k.map())).flags("@k").build())));
            case Expr.Words t -> List.of(w(param(named(t.text())).prefix(ZshParameter.Prefix.SPLIT).build()));
            case Expr.Field f -> List.of(w(param(dq(field(f))).flags("f").build()));
            case Expr.ReadLines r -> List.of(w(param(dq(readFile(r.path()))).flags("f").build()));
            default -> List.of(w(dq(p(named(e), "@"))));
        };
    }

    private ZshWordPart readFile(Expr path) {
        return sub(new ZshCommand.Simple(List.of(), List.of(),
                List.of(new ZshRedirection.ToFile(Optional.empty(), ZshRedirection.FileOperator.IN, bareWord(text(path))))));
    }

    /** {@code ${#xs}}: a list's items, a map's entries. */
    private ZshParameter count(Expr collection) {
        if (collection instanceof Expr.Keys k) return length(named(k.map()));
        return length(collection.type() instanceof Type.MapOf ? named(collection) : named(collection));
    }

    private ZshParameter join(Expr.Join j) {
        String flag;
        if (j.separator() instanceof Expr.StringLit s && s.value().equals("\n")) flag = "F";
        else if (j.separator() instanceof Expr.StringLit s && s.value().chars().noneMatch(c -> c < 0x20)) {
            String d = ":/|.,;@!#%+=~".chars().mapToObj(c -> String.valueOf((char) c))
                    .filter(c -> !s.value().contains(c)).findFirst().orElseThrow();
            flag = "j" + d + s.value() + d;
        } else flag = "pj:$" + named(j.separator()) + ":";
        // keys straight from the map, with @ so that they stay apart inside double quotes
        return (j.list() instanceof Expr.Keys k ? param(param(named(k.map())).flags("@k").build()) : param(named(j.list())))
                .flags(flag).build();
    }

    /** A modifier on a value: on the parameter itself where it is one, else on {@code ${:-value}}, as {@code ${${:-$dist/x}:h}}. */
    private static ZshParameter modify(List<ZshWordPart> value, String modifier) {
        if (value.size() == 1 && value.getFirst() instanceof ZshParameter p
                && p.flags().isEmpty() && p.prefixes().isEmpty() && p.operation().isEmpty()) {
            List<String> modifiers = new ArrayList<>(p.modifiers());
            modifiers.add(modifier);
            return new ZshParameter(List.of(), List.of(), p.target(), p.subscript(), modifiers, Optional.empty());
        }
        return param(defaulted(value)).modifiers(modifier).build();
    }

    /** The first {@code count} characters: {@code ${x:0:count}}. */
    private static ZshParameter take(List<ZshWordPart> value, String count) {
        ZshParameter.Substring first = new ZshParameter.Substring("0", Optional.of(count));
        if (value.size() == 1 && value.getFirst() instanceof ZshParameter p
                && p.flags().isEmpty() && p.prefixes().isEmpty() && p.operation().isEmpty() && p.modifiers().isEmpty())
            return new ZshParameter(List.of(), List.of(), p.target(), p.subscript(), List.of(), Optional.of(first));
        return param(defaulted(value)).operation(first).build();
    }

    /** {@code ${:-value}}: any value, as a parameter that others can nest. */
    private static ZshParameter defaulted(List<ZshWordPart> value) {
        return param("").operation(new ZshParameter.Default(true, ZshParameter.DefaultKind.USE_DEFAULT, new ZshWord(value))).build();
    }

    /** The directory of the script: {@code $0} names a function inside one, where {@code %x} still names the file. */
    private ZshParameter scriptDir() {
        if (functionTemps == null) return modified("0", "A", "h");
        return param(param("").flags("%").operation(new ZshParameter.Default(true, ZshParameter.DefaultKind.USE_DEFAULT, w("%x"))).build())
                .modifiers("A", "h").build();
    }

    /** An int in arithmetic syntax: a variable by its bare name. */
    private String arithmetic(Expr e) {
        return switch (e) {
            case Expr.IntLit i -> Long.toString(i.value());
            case Expr.Ref r -> name(r.var());
            default -> ZshPrinter.print(new ZshWord(text(e)));
        };
    }

    /** A key in brackets: a plain word as it is, anything else as the shell would read it unquoted. */
    private static String subscript(List<ZshWordPart> key) {
        if (key.stream().allMatch(p -> p instanceof ZshWordPart.Literal)) {
            String k = literalText(new ZshWord(key));
            return k.matches("[A-Za-z0-9_.-]+") ? k : ZshPrinter.print(literalWord(k));
        }
        return ZshPrinter.print(new ZshWord(key));
    }

    // ---- temporaries --------------------------------------------------------------------------------------------

    /** A variable set, before the command that needs it, to the value of an expression. */
    private String temporary(Expr value) {
        String name = "koineTemp" + ++temporaries;
        if (functionTemps != null) functionTemps.put(name, value.type());
        setup.addAll(assign(name, value.type(), value));
        return name;
    }

    /** A variable that holds the value: the one it is read from, or a temporary. */
    private String named(Expr e) {
        return e instanceof Expr.Ref r ? name(r.var()) : temporary(e);
    }

    // ---- words --------------------------------------------------------------------------------------------------

    /** Literal text as one word: bare where nothing in it is special, else in quotes. */
    private static ZshWord literalWord(String text) {
        if (text.isEmpty()) return w();
        if (text.chars().anyMatch(c -> c < 0x20)) return w(ansi(text));
        if (text.matches("[A-Za-z0-9_./:,+@%-][A-Za-z0-9_./:,+@%=-]*")) return w(text);
        if (text.indexOf('\'') < 0) return w(sq(text));
        return w(dq(text));
    }

    /** A value as one command argument: in double quotes, which keep it one word even when empty. */
    private static ZshWord arg(List<ZshWordPart> parts) {
        if (parts.stream().allMatch(p -> p instanceof ZshWordPart.Literal)) return literalWord(literalText(new ZshWord(parts)));
        List<ZshWordPart> out = new ArrayList<>();
        List<ZshWordPart> quoted = new ArrayList<>();
        for (ZshWordPart p : parts) {
            if (p instanceof ZshWordPart.Literal l) {
                // line breaks and the like are $'\n', outside the double quotes
                StringBuilder run = new StringBuilder();
                for (char c : l.text().toCharArray()) {
                    if (c < 0x20) {
                        if (!run.isEmpty()) quoted.add(new ZshWordPart.Literal(run.toString()));
                        run.setLength(0);
                        if (!quoted.isEmpty()) out.add(new ZshWordPart.DoubleQuoted(quoted));
                        quoted = new ArrayList<>();
                        addMerging(out, ansi(String.valueOf(c)));
                    } else run.append(c);
                }
                if (!run.isEmpty()) quoted.add(new ZshWordPart.Literal(run.toString()));
            } else quoted.add(p);
        }
        if (!quoted.isEmpty()) out.add(new ZshWordPart.DoubleQuoted(quoted));
        return new ZshWord(out);
    }

    /**
     * A value where Zsh neither splits nor globs what a parameter expands to: an assignment, either side of a
     * {@code [[ ]]} test but a pattern, a redirection. Unquoted where it reads as well.
     */
    private static ZshWord bareWord(List<ZshWordPart> parts) {
        boolean plain = parts.stream().allMatch(p -> switch (p) {
            case ZshWordPart.Literal l -> literalWord(l.text()).parts().equals(List.of(l));
            // a nested expansion unquoted can take a command substitution's output as words
            case ZshParameter x -> x.target() instanceof ZshParameter.Name;
            case ZshWordPart.CommandSubstitution c -> parts.size() == 1;
            default -> false;
        });
        return plain ? new ZshWord(parts) : arg(parts);
    }

    /** The right side of {@code ==}: a parameter in quotes, or its value would be a pattern. */
    private static ZshWord patternWord(List<ZshWordPart> parts) {
        return arg(parts);
    }

    private static String name(Var v) {
        if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("not a name Zsh can use: " + v.name());
        return RESERVED.contains(v.name()) ? v.name() + "_" : v.name();
    }

    /** {@code SkipBuild} as the option {@code skip-build}. */
    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
