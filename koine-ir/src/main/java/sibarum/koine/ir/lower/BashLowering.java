package sibarum.koine.ir.lower;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.FunctionSig;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.bash.BashAssignment;
import sibarum.koine.ir.bash.BashCommand;
import sibarum.koine.ir.bash.BashCondition;
import sibarum.koine.ir.bash.BashCondition.BinaryTest;
import sibarum.koine.ir.bash.BashCondition.UnaryTest;
import sibarum.koine.ir.bash.BashParameter;
import sibarum.koine.ir.bash.BashParameter.Default;
import sibarum.koine.ir.bash.BashParameter.DefaultKind;
import sibarum.koine.ir.bash.BashParameter.Substring;
import sibarum.koine.ir.bash.BashPrinter;
import sibarum.koine.ir.bash.BashRedirection;
import sibarum.koine.ir.bash.BashScript;
import sibarum.koine.ir.bash.BashWord;
import sibarum.koine.ir.bash.BashWordPart;
import sibarum.koine.ir.bash.BashWordPart.Literal;

import static sibarum.koine.ir.bash.Bash.*;

/**
 * Lowers a portable script to Bash (4.4 and later). An expression becomes a word or, if it is a bool, a command whose
 * status is its value.
 * <ul>
 *   <li>Nothing relies on {@code set -e}, which Bash turns off inside functions and conditions: a {@link Statement.Run}
 *       that must succeed is written {@code cmd || exit $?}.</li>
 *   <li>Lists are arrays, expanded as {@code "${xs[@]}"}, and maps are {@code declare -A} arrays, whose keys come back
 *       in Bash's order rather than the order they were put in. Bash has no lists or maps of collections.</li>
 *   <li>A bool is 0 or 1 and tests as {@code (( b ))}; ints compare in {@code (( ))}, strings in {@code [[ ]]}, and a
 *       regular expression is matched with {@code [[ =~ ]]}, from a variable when it is not a plain word.</li>
 *   <li>What a function sets is {@code local}. A function that returns a value takes the name of the variable to put it
 *       in as its first argument, and sets it through a nameref: {@code $(f)} would run it in a subshell, and lose
 *       what it sets and an {@code exit} in it. A name the function has locals of goes through a temporary.</li>
 *   <li>A JSON value is its compact text, and every field read runs {@code jq}; a missing field reads as empty, or as 0
 *       or false for a number or a boolean.</li>
 *   <li>What a word needs computed first, a function's result, a list to count or join, a {@code choose}, goes in a
 *       temporary just before the command that uses it. In a condition it goes in the condition's own list, so that
 *       an {@code elif}, a {@code while}, the right of {@code &&} and {@code ||} still evaluate only when reached.</li>
 *   <li>{@link Statement.EachLine} reads the command's lines on descriptor 3 from a process substitution: the loop runs
 *       in this shell, so what it sets stays set, and what its body reads on stdin is not the command's output. Then
 *       {@code wait $!} gives the command's status.</li>
 *   <li>The script's parameters are long options, {@code --skip-build}, parsed by hand, with a {@code --help} that
 *       prints the script's header comment.</li>
 * </ul>
 */
public final class BashLowering {

    /** Builtins, keywords and the programs the lowering itself runs, which a function must not take the name of. */
    private static final Set<String> TAKEN = Set.of("alias", "bg", "bind", "break", "builtin", "caller", "cd", "command",
            "compgen", "complete", "compopt", "continue", "declare", "dirs", "disown", "echo", "enable", "eval", "exec",
            "exit", "export", "false", "fc", "fg", "getopts", "hash", "help", "history", "jobs", "kill", "let", "local",
            "logout", "mapfile", "popd", "printf", "pushd", "pwd", "read", "readarray", "readonly", "return", "set",
            "shift", "shopt", "source", "suspend", "test", "times", "trap", "true", "type", "typeset", "ulimit", "umask",
            "unalias", "unset", "wait", "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while",
            "until", "do", "done", "in", "function", "time", "coproc", "basename", "dirname", "jq", "mkdir", "realpath",
            "sed", "sleep");

    /** Text that is the same word unquoted. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=+@%,^-]+");
    /** A regular expression that can follow {@code =~} as it is. */
    private static final Pattern PLAIN_REGEX = Pattern.compile("\\^?[A-Za-z0-9_.,:/=@%-]+\\$?");

    private static final String RESULT = "koine_result";
    private static final String DIR = "koine_dir";

    /** {@code ||} below {@code &&} below comparisons below names and {@code !}, as {@code (( ))} groups them. */
    private static final int OR = 0, AND = 1, COMPARE = 2, ATOM = 3;

    private enum Kind {SCALAR, ARRAY, MAP}

    /** A bool, lowered: a {@code [[ ]]} test, a {@code (( ))} expression, or a command; the first two merge. */
    private sealed interface Cond {
    }

    private record Test(BashCondition condition) implements Cond {
    }

    private record Arith(String text, int level) implements Cond {
    }

    private record Cmd(BashCommand command) implements Cond {
    }

    /** The function being lowered: its locals, by kind, in the order they are first set. */
    private static final class Scope {
        final boolean returns;
        final Map<String, Kind> locals = new LinkedHashMap<>();

        Scope(boolean returns) {
            this.returns = returns;
        }
    }

    /** Each pattern that needs a variable, and the variable's name. */
    private final Map<String, String> regexes = new LinkedHashMap<>();
    /** The names a function's nameref must not be given, by function: its parameters and locals. */
    private final Map<String, Set<String>> shadowed = new HashMap<>();
    private boolean usesDir;
    private boolean usesPrint;
    private int temporaries;
    private Scope scope;

    private BashLowering() {
    }

    public static BashScript lower(Script script) {
        return new BashLowering().script(script);
    }

    // ---- the script ---------------------------------------------------------------------------------------------

    private BashScript script(Script s) {
        List<String> help = help(s);
        List<BashCommand> options = options(s, help.size() + 1);
        List<BashCommand> main = topLevel(s.body());
        Map<String, Var> assigned = new LinkedHashMap<>();
        collectAssigned(s.body(), assigned);
        List<String> maps = assigned.values().stream().filter(v -> v.type() instanceof Type.MapOf).map(BashLowering::name).toList();

        List<BashCommand> body = new ArrayList<>();
        body.add(comment(String.join("\n", help)));
        body.add(blank());
        if (usesDir) {
            BashCommand cd = new BashCommand.Simple(List.of(set("CDPATH")),
                    words("cd", "--", dq(sub(cmd("dirname", "--", dq(p("BASH_SOURCE", "0")))))), List.of());
            body.add(assigns(set(DIR, sub(and(cd, cmd("pwd"))))));
        }
        regexes.forEach((pattern, name) -> body.add(assigns(set(name, lit(pattern)))));
        body.addAll(options);
        body.add(blank());
        if (usesPrint) {
            body.add(comment("Prints a line in a color, when the output is a terminal."));
            body.add(function("koine_print", group(
                    if_(test(new BashCondition.Unary(UnaryTest.TERMINAL, w("1"))),
                            cmd("printf", lit("\\e[%sm%s\\e[0m\\n"), dq(p("1")), dq(p("2"))))
                            .orElse(List.of(cmd("printf", lit("%s\\n"), dq(p("2"))))))));
            body.add(blank());
        }
        if (!maps.isEmpty()) {
            List<Object> words = new ArrayList<>(List.of("declare", "-A"));
            words.addAll(maps);
            body.add(cmd(words.toArray()));
        }
        body.addAll(main);
        return new BashScript(Optional.of("/usr/bin/env bash"), body);
    }

    /** The header comment, which {@code --help} prints: the synopsis, the description, and the options. */
    private static List<String> help(Script s) {
        List<String> lines = new ArrayList<>(s.synopsis().lines().toList());
        if (!s.description().isBlank()) {
            lines.add("");
            lines.addAll(s.description().lines().toList());
        }
        lines.add("");
        lines.add("Options:");
        List<String[]> rows = new ArrayList<>();
        for (Script.Param p : s.parameters()) {
            String label = option(p.var()) + (p.var().type().equals(Type.BOOL) ? "" : p.var().type().equals(Type.INT) ? " N" : " VALUE");
            String text = p.help() + p.defaultValue().map(d -> switch (d) {
                case Expr.IntLit i -> " (default: " + i.value() + ")";
                case Expr.StringLit t -> " (default: " + t.value() + ")";
                default -> "";
            }).orElse("");
            rows.add(new String[]{label, text});
        }
        rows.add(new String[]{"-h, --help", "Show this help."});
        int width = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0) + 4;
        for (String[] r : rows) {
            List<String> text = r[1].lines().toList();
            for (int i = 0; i < Math.max(1, text.size()); i++) {
                String head = i == 0 ? r[0] : "";
                String line = text.isEmpty() ? "" : text.get(i);
                lines.add(("  " + head + " ".repeat(width - head.length()) + line).stripTrailing());
            }
        }
        return lines;
    }

    /** The parameters' defaults, then a loop over the arguments that sets them; {@code helpEnd} is help's last line. */
    private List<BashCommand> options(Script s, int helpEnd) {
        List<BashCommand> out = new ArrayList<>();
        List<BashAssignment> defaults = new ArrayList<>();
        List<BashCommand.CaseItem> items = new ArrayList<>();
        for (Script.Param p : s.parameters()) {
            String n = name(p.var());
            String opt = option(p.var());
            if (p.var().type().equals(Type.BOOL)) {
                defaults.add(set(n, "0"));
                items.add(item(words(opt), assigns(set(n, "1")), cmd("shift")));
                continue;
            }
            defaults.add(new BashAssignment(n, Optional.empty(), false,
                    new BashAssignment.Scalar(p.defaultValue().map(d -> assignWord(str(d, out))).orElse(w()))));
            if (p.var().type().equals(Type.INT)) {
                BashCommand isNumber = test(new BashCondition.Binary(w(dq(op("2", new Default(false, DefaultKind.USE_DEFAULT, w())))),
                        BinaryTest.REGEX, w(glob("^-?[0-9]+$"))));
                items.add(item(words(opt), or(isNumber, group(cmd("echo", lit(opt + " needs a number")).redirect(toErr()), cmd("exit", "2"))),
                        assigns(set(n, p("2"))), cmd("shift", "2")));
            } else {
                items.add(item(words(opt), assigns(set(n, op("2", new Default(false, DefaultKind.ERROR, w(opt + " needs a value"))))),
                        cmd("shift", "2")));
            }
        }
        items.add(item(words("-h", "--help"),
                pipe(cmd("sed", "-n", lit("2," + helpEnd + "p"), dq(p("0"))), cmd("sed", lit("s/^# \\{0,1\\}//"))),
                cmd("exit", "0")));
        items.add(item(List.of(w(glob("*"))),
                cmd("echo", dq("unknown option: ", p("1"), " (--help lists them)")).redirect(toErr()), cmd("exit", "2")));
        if (!defaults.isEmpty()) out.add(new BashCommand.Simple(defaults, List.of(), List.of()));
        out.add(while_(new BashCommand.Arithmetic("$# > 0"), case_(w(dq(p("1"))), items.toArray(BashCommand.CaseItem[]::new))));
        return out;
    }

    /** The script's own statements, with a blank line before each function and each comment that starts a part. */
    private List<BashCommand> topLevel(List<Statement> statements) {
        List<BashCommand> out = new ArrayList<>();
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

    /** The variables the statements set, by their Bash names, not counting the bodies of functions they define. */
    private static void collectAssigned(List<Statement> statements, Map<String, Var> out) {
        for (Statement s : statements)
            switch (s) {
                case Statement.Set x -> out.putIfAbsent(name(x.var()), x.var());
                case Statement.Append x -> out.putIfAbsent(name(x.list()), x.list());
                case Statement.Put x -> out.putIfAbsent(name(x.map()), x.map());
                case Statement.If x -> {
                    collectAssigned(x.then(), out);
                    collectAssigned(x.otherwise(), out);
                }
                case Statement.ForEach x -> {
                    out.putIfAbsent(name(x.item()), x.item());
                    collectAssigned(x.body(), out);
                }
                case Statement.While x -> collectAssigned(x.body(), out);
                case Statement.EachLine x -> {
                    out.putIfAbsent(name(x.line()), x.line());
                    collectAssigned(x.body(), out);
                    out.putIfAbsent(name(x.status()), x.status());
                }
                default -> {
                }
            }
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    private List<BashCommand> block(List<Statement> statements) {
        List<BashCommand> out = new ArrayList<>();
        for (Statement s : statements) out.addAll(statement(s));
        return out;
    }

    /** A body of a compound command, which Bash needs at least one command in. */
    private List<BashCommand> compoundBody(List<Statement> statements) {
        List<BashCommand> out = block(statements);
        return out.isEmpty() ? List.of(cmd(":")) : out;
    }

    private List<BashCommand> statement(Statement s) {
        List<BashCommand> pre = new ArrayList<>();
        switch (s) {
            case Statement.Set x -> {
                return assign(name(x.var()), Optional.empty(), x.var().type(), x.value());
            }
            case Statement.Append x -> {
                if (x.item().type() instanceof Type.ListOf) throw new IllegalArgumentException("Bash has no lists of lists: " + x);
                BashWord item = word(str(x.item(), pre));
                pre.add(assigns(appendArray(name(x.list()), item)));
            }
            case Statement.Put x -> {
                String key = key(x.key(), pre);
                pre.addAll(assign(name(x.map()), Optional.of(key), ((Type.MapOf) x.map().type()).value(), x.value()));
            }
            case Statement.If x -> pre.add(ifStatement(x));
            case Statement.ForEach x -> {
                List<BashWord> items = listWords(x.collection(), pre);
                pre.add(new BashCommand.For(name(x.item()), Optional.of(items), compoundBody(x.body())));
            }
            case Statement.While x -> pre.add(new BashCommand.While(false, conditionList(x.condition()), compoundBody(x.body())));
            case Statement.Break x -> pre.add(cmd("break"));
            case Statement.Continue x -> pre.add(cmd("continue"));
            case Statement.Function x -> pre.add(functionDefinition(x));
            case Statement.Return x -> {
                if (scope == null) {
                    if (x.value().isPresent()) throw new IllegalArgumentException("a script returns nothing; it exits: " + x);
                    pre.add(cmd("exit", "0"));
                } else {
                    if (scope.returns) x.value().ifPresent(v -> pre.addAll(assign(RESULT, Optional.empty(), v.type(), v)));
                    pre.add(cmd("return"));
                }
            }
            case Statement.Invoke x -> {
                // a result nobody asked for still needs somewhere to go
                Optional<String> result = x.function().returns().map(t -> temp(kind(t), pre));
                pre.add(call(x.function(), x.arguments(), result, pre));
            }
            case Statement.Exit x -> pre.add(cmd("exit", word(str(x.status(), pre))));
            case Statement.Print x -> {
                BashWord message = word(str(x.message(), pre));
                if (x.color() == Statement.Color.DEFAULT) {
                    pre.add(cmd("printf", lit("%s\\n"), message));
                } else {
                    usesPrint = true;
                    pre.add(cmd("koine_print", colorCode(x.color()), message));
                }
            }
            case Statement.Pause x -> {
                // as Read-Host shows it
                List<BashWordPart> prompt = new ArrayList<>(str(x.message(), pre));
                prompt.add(new Literal(": "));
                pre.add(cmd("read", "-r", "-p", word(prompt)));
            }
            case Statement.Run x -> {
                BashCommand c = command(x.command(), pre);
                pre.add(x.onFailure() == Statement.OnFailure.ABORT ? or(c, cmd("exit", p("?"))) : c);
            }
            case Statement.EachLine x -> eachLine(x, pre);
            case Statement.Kill x -> pre.add(cmd("kill", word(str(x.process(), pre))));
            case Statement.Sleep x -> pre.add(cmd("sleep", word(str(x.seconds(), pre))));
            case Statement.MakeDirs x -> pre.add(cmd("mkdir", "-p", "--", word(str(x.path(), pre))));
            case Statement.Comment x -> pre.add(comment(x.text()));
        }
        return pre;
    }

    /** An if, with an else that holds nothing but another if folded into {@code elif}. */
    private BashCommand ifStatement(Statement.If x) {
        BashCommand.If f = new BashCommand.If(List.of(new BashCommand.Clause(conditionList(x.condition()), compoundBody(x.then()))),
                Optional.empty());
        List<Statement> rest = x.otherwise();
        while (rest.size() == 1 && rest.getFirst() instanceof Statement.If next) {
            f = f.elif(conditionList(next.condition()), compoundBody(next.then()));
            rest = next.otherwise();
        }
        List<BashCommand> otherwise = block(rest);
        return otherwise.isEmpty() ? f : f.orElse(otherwise);
    }

    private void eachLine(Statement.EachLine x, List<BashCommand> pre) {
        BashCommand command = command(x.command(), pre);
        String line = name(x.line());
        // a last line with no line break ends the read, but is a line all the same
        BashCommand read = or(new BashCommand.Simple(List.of(set("IFS")), words("read", "-r", "-u", "3", line), List.of()),
                test(new BashCondition.Unary(UnaryTest.NOT_EMPTY, w(dq(p(line))))));
        BashCommand loop = new BashCommand.While(false, List.of(read), compoundBody(x.body()));
        pre.add(redirected(loop, new BashRedirection.ToFile(Optional.of(3), BashRedirection.FileOperator.IN,
                w(new BashWordPart.ProcessSubstitution(false, List.of(command))))));
        pre.add(cmd("wait", p("!")));
        pre.add(assigns(set(name(x.status()), p("?"))));
    }

    private BashCommand functionDefinition(Statement.Function x) {
        FunctionSig sig = x.signature();
        String fname = functionName(sig);
        Scope outer = scope;
        scope = new Scope(sig.returns().isPresent());
        Set<String> params = new HashSet<>();
        for (Var v : sig.parameters()) params.add(name(v));
        Map<String, Var> assigned = new LinkedHashMap<>();
        collectAssigned(x.body(), assigned);
        assigned.forEach((n, v) -> {
            if (!params.contains(n)) scope.locals.put(n, kind(v.type()));
        });
        Set<String> names = new HashSet<>(scope.locals.keySet());
        names.addAll(params);
        names.add(RESULT);
        shadowed.put(fname, names);

        List<BashCommand> b = block(x.body());
        Scope inner = scope;
        scope = outer;

        List<BashCommand> decls = new ArrayList<>();
        int first = 1;
        if (inner.returns) {
            decls.add(cmd("local", "-n", w(RESULT + "=", p("1"))));
            first = 2;
        }
        List<Object> scalars = new ArrayList<>(List.of("local"));
        for (int i = 0; i < sig.parameters().size(); i++) {
            Var v = sig.parameters().get(i);
            String position = Integer.toString(first + i);
            if (v.type() instanceof Type.MapOf) throw new IllegalArgumentException("a Bash function cannot take a map: " + v);
            if (v.type() instanceof Type.ListOf) {
                if (i != sig.parameters().size() - 1)
                    throw new IllegalArgumentException("a Bash function takes a list only as its last parameter: " + v);
                BashParameter rest = new BashParameter(BashParameter.Prefix.NONE, "@", Optional.empty(),
                        Optional.of(new Substring(position, Optional.empty())));
                decls.add(cmd("local", "-a", w(glob(name(v) + "=("), dq(rest), glob(")"))));
            } else {
                scalars.add(w(name(v) + "=", p(position)));
            }
        }
        if (scalars.size() > 1) decls.add(inner.returns ? 1 : 0, cmd(scalars.toArray()));
        for (Kind k : Kind.values()) {
            List<Object> words = new ArrayList<>(List.of("local"));
            if (k == Kind.ARRAY) words.add("-a");
            if (k == Kind.MAP) words.add("-A");
            int head = words.size();
            inner.locals.forEach((n, kind) -> {
                if (kind == k) words.add(n);
            });
            if (words.size() > head) decls.add(cmd(words.toArray()));
        }
        decls.addAll(b);
        if (decls.isEmpty()) decls.add(cmd(":"));
        return function(fname, new BashCommand.BraceGroup(decls));
    }

    // ---- assignments --------------------------------------------------------------------------------------------

    /** Commands that set {@code target} (or its {@code subscript}) to the value, with what they need first. */
    private List<BashCommand> assign(String target, Optional<String> subscript, Type type, Expr value) {
        List<BashCommand> pre = new ArrayList<>();
        switch (value) {
            case Expr.Choose c -> {
                return List.of(choose(c, v -> assign(target, subscript, type, v)));
            }
            case Expr.Call c -> {
                Set<String> names = shadowed.get(functionName(c.function()));
                if (subscript.isEmpty() && names != null && !names.contains(target)) {
                    pre.add(call(c.function(), c.arguments(), Optional.of(target), pre));
                    return pre;
                }
                String t = temp(kind(type), pre);
                pre.add(call(c.function(), c.arguments(), Optional.of(t), pre));
                pre.addAll(copy(target, subscript, type, t, pre));
                return pre;
            }
            case Expr.Spawn s -> {
                pre.add(spawn(s, pre));
                pre.add(assigns(scalar(target, subscript, w(p("!")))));
                return pre;
            }
            default -> {
            }
        }
        switch (type) {
            case Type.ListOf l -> {
                if (subscript.isPresent()) throw new IllegalArgumentException("Bash has no maps of lists: " + value);
                pre.addAll(setList(target, value, pre));
            }
            case Type.MapOf m -> {
                if (subscript.isPresent()) throw new IllegalArgumentException("Bash has no maps of maps: " + value);
                switch (value) {
                    case Expr.EmptyMap e -> pre.add(assigns(array(target)));
                    case Expr.Ref r -> {
                        if (!name(r.var()).equals(target)) pre.addAll(copy(target, subscript, type, name(r.var()), pre));
                    }
                    default -> throw new IllegalArgumentException("Bash cannot make a map of " + value);
                }
            }
            case Type.Bool b -> {
                if (value instanceof Expr.BoolLit lit) {
                    pre.add(assigns(scalar(target, subscript, w(lit.value() ? "1" : "0"))));
                } else if (value instanceof Expr.Ref || value instanceof Expr.Get || value instanceof Expr.At) {
                    pre.add(assigns(scalar(target, subscript, assignWord(str(value, pre)))));
                } else {
                    BashCommand c = asCommand(cond(value, pre));
                    BashCommand yes = assigns(scalar(target, subscript, w("1"))), no = assigns(scalar(target, subscript, w("0")));
                    pre.add(c instanceof BashCommand.AndOr a ? a.and(yes).or(no) : and(c, yes).or(no));
                }
            }
            default -> pre.add(assigns(scalar(target, subscript, assignWord(str(value, pre)))));
        }
        return pre;
    }

    /** {@code target} set to the list, for values that are not a {@code choose} or a call. */
    private List<BashCommand> setList(String target, Expr value, List<BashCommand> pre) {
        return switch (value) {
            case Expr.ListLit l -> {
                List<BashWord> items = new ArrayList<>();
                for (Expr i : l.items()) items.add(word(str(i, pre)));
                yield List.of(assigns(new BashAssignment(target, Optional.empty(), false, new BashAssignment.Array(items))));
            }
            case Expr.Ref r -> name(r.var()).equals(target) ? List.of()
                    : List.of(assigns(array(target, dq(p(name(r.var()), "@")))));
            case Expr.Keys k -> List.of(assigns(array(target, dq(keys(mapName(k.map(), pre))))));
            case Expr.Field f -> List.of(cmd("mapfile", "-t", target).redirect(fromProcess(jq(f, pre))));
            case Expr.ReadLines r -> List.of(cmd("mapfile", "-t", target).redirect(
                    new BashRedirection.ToFile(Optional.empty(), BashRedirection.FileOperator.IN, word(str(r.path(), pre)))));
            // -d '' reads past the first line break, so every line's words count
            case Expr.Words x -> List.of(cmd("read", "-r", "-d", w(), "-a", target).redirect(hereString(word(str(x.text(), pre)))));
            default -> throw new IllegalArgumentException("Bash cannot make a list of " + value);
        };
    }

    /** {@code target} set to what the variable {@code source} holds. */
    private List<BashCommand> copy(String target, Optional<String> subscript, Type type, String source, List<BashCommand> pre) {
        return switch (type) {
            case Type.ListOf l -> List.of(assigns(array(target, dq(p(source, "@")))));
            case Type.MapOf m -> {
                String k = temp(Kind.SCALAR, pre);
                yield List.of(assigns(array(target)), for_(k, List.of(w(dq(keys(source)))),
                        assigns(new BashAssignment(target, Optional.of("$" + k), false, new BashAssignment.Scalar(w(p(source, "$" + k)))))));
            }
            default -> List.of(assigns(scalar(target, subscript, w(p(source)))));
        };
    }

    private static BashAssignment scalar(String target, Optional<String> subscript, BashWord word) {
        return new BashAssignment(target, subscript, false, new BashAssignment.Scalar(word));
    }

    /** An if that runs {@code branch} of whichever value the choice takes, with a chain of choices as elifs. */
    private BashCommand.If choose(Expr.Choose c, Function<Expr, List<BashCommand>> branch) {
        BashCommand.If f = new BashCommand.If(List.of(new BashCommand.Clause(conditionList(c.condition()), branch.apply(c.then()))),
                Optional.empty());
        List<BashCommand> otherwise = branch.apply(c.otherwise());
        if (otherwise.size() == 1 && otherwise.getFirst() instanceof BashCommand.If next) {
            for (BashCommand.Clause clause : next.clauses()) f = f.elif(clause.condition(), clause.body());
            return next.otherwise().map(f::orElse).orElse(f);
        }
        return f.orElse(otherwise);
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    /** A program and its arguments; a sibling script runs in this Bash, so it need not be executable. */
    private BashCommand.Simple command(Command c, List<BashCommand> pre) {
        BashCommand.Simple command = new BashCommand.Simple(List.of(), commandWords(c, pre), List.of());
        return c.quiet() ? command.redirect(to("/dev/null"), errToOut()) : command;
    }

    private List<BashWord> commandWords(Command c, List<BashCommand> pre) {
        List<BashWord> words = new ArrayList<>();
        if (c.program() instanceof Expr.ScriptPath) words.add(w(dq(p("BASH"))));
        words.add(word(str(c.program(), pre)));
        for (Expr a : c.arguments()) {
            if (a.type() instanceof Type.ListOf) words.addAll(listWords(a, pre));
            else words.add(word(str(a, pre)));
        }
        return words;
    }

    private BashCommand.Simple call(FunctionSig f, List<Expr> arguments, Optional<String> result, List<BashCommand> pre) {
        List<BashWord> words = new ArrayList<>();
        words.add(w(functionName(f)));
        result.ifPresent(r -> words.add(w(r)));
        for (Expr a : arguments) {
            if (a.type() instanceof Type.MapOf) throw new IllegalArgumentException("a Bash function cannot take a map: " + a);
            if (a.type() instanceof Type.ListOf) words.addAll(listWords(a, pre));
            else words.add(word(str(a, pre)));
        }
        return new BashCommand.Simple(List.of(), words, List.of());
    }

    /** {@code (cd -- dir && exec program args) > out 2> err &}: exec, so that {@code $!} is the program itself. */
    private BashCommand spawn(Expr.Spawn s, List<BashCommand> pre) {
        BashWord dir = word(str(s.workDir(), pre));
        List<BashWord> run = new ArrayList<>(List.of(w("exec")));
        run.addAll(commandWords(s.command(), pre));
        BashCommand body = and(cmd("cd", "--", dir), new BashCommand.Simple(List.of(), run, List.of()));
        return bg(redirected(subshell(body),
                new BashRedirection.ToFile(Optional.empty(), BashRedirection.FileOperator.OUT, word(str(s.stdout(), pre))),
                new BashRedirection.ToFile(Optional.of(2), BashRedirection.FileOperator.OUT, word(str(s.stderr(), pre)))));
    }

    /**
     * {@code jq} reading a field, from the file when the document is one just read, else from the variable. A string
     * or JSON field that is null or missing gives no output; a number reads as 0 unless it is one, a boolean as false
     * unless it is true; a list gives one item a line.
     */
    private BashCommand.Simple jq(Expr.Field f, List<BashCommand> pre) {
        StringBuilder path = new StringBuilder();
        for (String n : f.path()) {
            if (n.matches("[A-Za-z_][A-Za-z0-9_]*")) path.append('.').append(n);
            else path.append(path.isEmpty() ? "." : "").append("[\"").append(n.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
        }
        String[] flagAndFilter = switch (f.type()) {
            case Type.Int i -> new String[]{"-r", "(" + path + " | tonumber? | floor) // 0"};
            case Type.Bool b -> new String[]{"-e", path + " == true"};
            case Type.Json j -> new String[]{"-c", path + " | values"};
            case Type.ListOf l when l.element().equals(Type.JSON) -> new String[]{"-c", path + "[]?"};
            case Type.ListOf l -> new String[]{"-r", path + "[]?"};
            default -> new String[]{"-r", path + " | values"};
        };
        List<BashWord> words = new ArrayList<>(List.of(w("jq"), w(flagAndFilter[0]), lit(flagAndFilter[1])));
        if (f.json() instanceof Expr.ReadJson r) {
            words.add(word(str(r.path(), pre)));
            return new BashCommand.Simple(List.of(), words, List.of());
        }
        return new BashCommand.Simple(List.of(), words, List.of(hereString(word(str(f.json(), pre)))));
    }

    private static BashRedirection fromProcess(BashCommand command) {
        return new BashRedirection.ToFile(Optional.empty(), BashRedirection.FileOperator.IN,
                w(new BashWordPart.ProcessSubstitution(false, List.of(command))));
    }

    // ---- words --------------------------------------------------------------------------------------------------

    /**
     * A string, number, bool, JSON value or process, as the parts of a word, the kinds double quotes can hold. What
     * cannot be a part of a word is put in a temporary first.
     */
    private List<BashWordPart> str(Expr e, List<BashCommand> pre) {
        if (e.type() instanceof Type.ListOf || e.type() instanceof Type.MapOf)
            throw new IllegalArgumentException("a list or a map is not one word: " + e);
        boolean computed = e instanceof Expr.Choose || e instanceof Expr.Call || e instanceof Expr.Spawn
                || e.type().equals(Type.BOOL) && !(e instanceof Expr.BoolLit || e instanceof Expr.Ref || e instanceof Expr.Get || e instanceof Expr.At);
        if (computed) {
            String t = temp(Kind.SCALAR, pre);
            pre.addAll(assign(t, Optional.empty(), e.type(), e));
            return List.of(p(t));
        }
        return switch (e) {
            case Expr.StringLit s -> List.of(new Literal(s.value()));
            case Expr.IntLit i -> List.of(new Literal(Long.toString(i.value())));
            case Expr.BoolLit b -> List.of(new Literal(b.value() ? "1" : "0"));
            case Expr.Ref r -> List.of(p(name(r.var())));
            case Expr.Interp i -> {
                List<BashWordPart> out = new ArrayList<>();
                for (Expr part : i.parts()) out.addAll(str(part, pre));
                yield out;
            }
            case Expr.Count c -> List.of(length(collectionName(c.collection(), pre), "@"));
            case Expr.At a -> {
                String list = listName(a.list(), pre);
                yield List.of(p(list, arithmetic(a.index(), pre)));
            }
            case Expr.Get g -> {
                String map = mapName(g.map(), pre);
                yield List.of(p(map, key(g.key(), pre)));
            }
            case Expr.Take t -> List.of(take(t, pre));
            case Expr.Join j -> List.of(p(join(j, pre)));
            case Expr.ScriptDir d -> {
                usesDir = true;
                yield List.of(p(DIR));
            }
            case Expr.ScriptPath s -> {
                usesDir = true;
                yield List.of(p(DIR), new Literal("/" + s.name() + ".sh"));
            }
            case Expr.Home h -> List.of(p("HOME"));
            case Expr.Env v -> {
                if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw new IllegalArgumentException("Bash cannot read an environment variable named " + v.name());
                yield List.of(p(v.name()));
            }
            case Expr.PathJoin j -> {
                List<BashWordPart> out = new ArrayList<>(str(j.base(), pre));
                for (Expr part : j.parts()) {
                    out.add(new Literal("/"));
                    out.addAll(str(part, pre));
                }
                yield out;
            }
            case Expr.Parent x -> List.of(sub(cmd("dirname", "--", word(str(x.path(), pre)))));
            case Expr.FileName x -> List.of(sub(cmd("basename", "--", word(str(x.path(), pre)))));
            // -m: it need not exist; -s: .. is resolved as text, as .NET's GetFullPath does, not through links
            case Expr.FullPath x -> List.of(sub(cmd("realpath", "-ms", "--", word(str(x.path(), pre)))));
            case Expr.ReadJson r -> List.of(sub(cmd("jq", "-c", ".", word(str(r.path(), pre)))));
            case Expr.Field f -> List.of(sub(jq(f, pre)));
            case Expr.Capture c -> List.of(sub(command(c.command(), pre)));
            default -> throw new IllegalArgumentException("not a word in Bash: " + e);
        };
    }

    /** {@code ${text:0:count}}, of a variable, a map entry or a list item, or else of a temporary holding the text. */
    private BashParameter take(Expr.Take t, List<BashCommand> pre) {
        String name;
        Optional<String> subscript = Optional.empty();
        switch (t.text()) {
            case Expr.Ref r -> name = name(r.var());
            case Expr.Get g -> {
                name = mapName(g.map(), pre);
                subscript = Optional.of(key(g.key(), pre));
            }
            case Expr.At a -> {
                name = listName(a.list(), pre);
                subscript = Optional.of(arithmetic(a.index(), pre));
            }
            default -> {
                name = temp(Kind.SCALAR, pre);
                pre.addAll(assign(name, Optional.empty(), Type.STRING, t.text()));
            }
        }
        Substring op = new Substring("0", Optional.of(arithmetic(t.count(), pre)));
        return new BashParameter(BashParameter.Prefix.NONE, name, subscript, Optional.of(op));
    }

    /** A temporary holding the items with the separator between them: the first, then the separator and each other. */
    private String join(Expr.Join j, List<BashCommand> pre) {
        String list = listName(j.list(), pre);
        List<BashWordPart> separator = str(j.separator(), pre);
        String out = temp(Kind.SCALAR, pre);
        String item = temp(Kind.SCALAR, pre);
        List<BashWordPart> appended = new ArrayList<>(separator);
        appended.add(p(item));
        BashParameter rest = new BashParameter(BashParameter.Prefix.NONE, list, Optional.of("@"),
                Optional.of(new Substring("1", Optional.empty())));
        pre.add(assigns(set(out, p(list, "0"))));
        pre.add(for_(item, List.of(w(dq(rest))),
                assigns(new BashAssignment(out, Optional.empty(), true, new BashAssignment.Scalar(word(appended))))));
        return out;
    }

    /** A list as the words it spreads into. */
    private List<BashWord> listWords(Expr e, List<BashCommand> pre) {
        return switch (e) {
            case Expr.ListLit l -> {
                List<BashWord> out = new ArrayList<>();
                for (Expr i : l.items()) out.add(word(str(i, pre)));
                yield out;
            }
            case Expr.Keys k -> List.of(w(dq(keys(mapName(k.map(), pre)))));
            default -> List.of(w(dq(p(listName(e, pre), "@"))));
        };
    }

    /** The array a list is in: its variable, or a temporary. */
    private String listName(Expr e, List<BashCommand> pre) {
        if (e instanceof Expr.Ref r) return name(r.var());
        String t = temp(Kind.ARRAY, pre);
        pre.addAll(assign(t, Optional.empty(), e.type(), e));
        return t;
    }

    /** The associative array a map is in: its variable, or a temporary. */
    private String mapName(Expr e, List<BashCommand> pre) {
        if (e instanceof Expr.Ref r) return name(r.var());
        String t = temp(Kind.MAP, pre);
        pre.addAll(assign(t, Optional.empty(), e.type(), e));
        return t;
    }

    /** The array whose elements are counted: a list's, or a map's for its keys. */
    private String collectionName(Expr e, List<BashCommand> pre) {
        if (e instanceof Expr.Keys k) return mapName(k.map(), pre);
        return e.type() instanceof Type.MapOf ? mapName(e, pre) : listName(e, pre);
    }

    /** A map's key as a subscript: a variable bare, which a key needs no quotes for, else a quoted word. */
    private String key(Expr key, List<BashCommand> pre) {
        if (key instanceof Expr.Ref r) return "$" + name(r.var());
        return BashPrinter.print(word(str(key, pre)));
    }

    /** An int as arithmetic: a number or a variable's name as it is, else a temporary's name. */
    private String arithmetic(Expr e, List<BashCommand> pre) {
        return switch (e) {
            case Expr.IntLit i -> i.value() < 0 ? "(" + i.value() + ")" : Long.toString(i.value());
            case Expr.Ref r -> name(r.var());
            case Expr.Count c -> "${#" + collectionName(c.collection(), pre) + "[@]}";
            default -> {
                String t = temp(Kind.SCALAR, pre);
                pre.addAll(assign(t, Optional.empty(), Type.INT, e));
                yield t;
            }
        };
    }

    private static BashParameter keys(String map) {
        return new BashParameter(BashParameter.Prefix.INDIRECT, map, Optional.of("@"), Optional.empty());
    }

    /** A new temporary variable, {@code local} in a function; a map one is declared where it is made at the top level. */
    private String temp(Kind kind, List<BashCommand> pre) {
        String t = "koine_" + ++temporaries;
        if (scope != null) scope.locals.put(t, kind);
        else if (kind == Kind.MAP) pre.add(cmd("declare", "-A", t));
        return t;
    }

    /** A word that means exactly these parts: plain text as it is, other text quoted, expansions in double quotes. */
    private static BashWord word(List<BashWordPart> parts) {
        List<BashWordPart> merged = merge(parts);
        if (merged.isEmpty()) return w();
        if (merged.size() == 1 && merged.getFirst() instanceof Literal l) return lit(l.text());
        return quoted(merged);
    }

    /** An assignment's value: expansions alone need no quotes there, since nothing splits them. */
    private static BashWord assignWord(List<BashWordPart> parts) {
        List<BashWordPart> merged = merge(parts);
        boolean bare = !merged.isEmpty() && merged.stream().allMatch(part ->
                part instanceof BashParameter x && x.subscript().filter(s -> s.equals("@") || s.equals("*")).isEmpty()
                        || part instanceof BashWordPart.CommandSubstitution || part instanceof BashWordPart.ArithmeticExpansion);
        return bare ? new BashWord(merged) : word(merged);
    }

    /** Literal text as a word: bare when it can be, else in single quotes, or {@code $'...'} for control characters. */
    private static BashWord lit(String text) {
        if (text.isEmpty()) return w();
        if (PLAIN.matcher(text).matches()) return w(text);
        if (text.chars().anyMatch(c -> c < 0x20)) return w(ansi(text));
        if (text.indexOf('\'') < 0) return w(sq(text));
        return quoted(List.of(new Literal(text)));
    }

    /** The parts in double quotes, but for runs of control characters, which go in {@code $'...'} between them. */
    private static BashWord quoted(List<BashWordPart> parts) {
        List<BashWordPart> out = new ArrayList<>();
        List<BashWordPart> group = new ArrayList<>();
        for (BashWordPart part : parts) {
            if (!(part instanceof Literal l)) {
                group.add(part);
                continue;
            }
            String text = l.text();
            for (int i = 0, j; i < text.length(); i = j) {
                boolean control = text.charAt(i) < 0x20;
                j = i;
                while (j < text.length() && (text.charAt(j) < 0x20) == control) j++;
                if (!control) {
                    group.add(new Literal(text.substring(i, j)));
                } else {
                    if (!group.isEmpty()) out.add(new BashWordPart.DoubleQuoted(group));
                    group.clear();
                    out.add(ansi(text.substring(i, j)));
                }
            }
        }
        if (!group.isEmpty()) out.add(new BashWordPart.DoubleQuoted(group));
        return new BashWord(out);
    }

    private static List<BashWordPart> merge(List<BashWordPart> parts) {
        List<BashWordPart> out = new ArrayList<>();
        for (BashWordPart part : parts) {
            if (part instanceof Literal l && l.text().isEmpty()) continue;
            if (part instanceof Literal l && !out.isEmpty() && out.getLast() instanceof Literal last)
                out.set(out.size() - 1, new Literal(last.text() + l.text()));
            else out.add(part);
        }
        return out;
    }

    // ---- conditions ---------------------------------------------------------------------------------------------

    /** A condition as the commands of an {@code if} or {@code while}: what it needs first, then the test. */
    private List<BashCommand> conditionList(Expr e) {
        List<BashCommand> pre = new ArrayList<>();
        Cond c = cond(e, pre);
        pre.add(asCommand(c));
        return pre;
    }

    private Cond cond(Expr e, List<BashCommand> pre) {
        return switch (e) {
            case Expr.BoolLit b -> new Cmd(cmd(b.value() ? "true" : "false"));
            case Expr.Ref r -> new Arith(name(r.var()), ATOM);
            case Expr.Not n -> n.operand() instanceof Expr.IsEmpty x ? isEmpty(x.value(), false, pre) : negated(cond(n.operand(), pre));
            case Expr.And a -> combine(a.left(), a.right(), true, pre);
            case Expr.Or o -> combine(o.left(), o.right(), false, pre);
            case Expr.Compare c -> compare(c, pre);
            case Expr.Choose c -> new Cmd(choose(c, this::conditionList));
            case Expr.Matches m -> new Test(new BashCondition.Binary(word(str(m.text(), pre)), BinaryTest.REGEX, regex(m.pattern())));
            case Expr.EndsWith x -> {
                BashWord text = word(str(x.text(), pre));
                // a quoted suffix matches as text, after the * that matches anything before it
                List<BashWordPart> pattern = new ArrayList<>(List.of(glob("*")));
                pattern.addAll(word(str(x.suffix(), pre)).parts());
                yield new Test(new BashCondition.Binary(text, BinaryTest.MATCHES, new BashWord(pattern)));
            }
            case Expr.IsEmpty x -> isEmpty(x.value(), true, pre);
            case Expr.HasKey h -> {
                String map = mapName(h.map(), pre);
                BashParameter set = op(map, key(h.key(), pre), new Default(false, DefaultKind.USE_ALTERNATE, w("set")));
                yield new Test(new BashCondition.Unary(UnaryTest.NOT_EMPTY, w(dq(set))));
            }
            case Expr.Exists x -> new Test(new BashCondition.Unary(UnaryTest.EXISTS, word(str(x.path(), pre))));
            case Expr.Succeeds s -> new Cmd(command(s.command(), pre));
            case Expr.CommandExists c -> new Cmd(cmd("command", "-v", word(str(c.name(), pre))).redirect(to("/dev/null")));
            case Expr.IsRunning r -> new Cmd(cmd("kill", "-0", word(str(r.process(), pre))).redirect(toFd(2, "/dev/null")));
            case Expr.IsInteractive i -> new Test(new BashCondition.Unary(UnaryTest.TERMINAL, w("0")));
            case Expr.Field f -> new Cmd(jq(f, pre).redirect(to("/dev/null")));
            default -> {
                // a function's result, a map entry or a list item: 0 or 1, in a temporary
                String t = temp(Kind.SCALAR, pre);
                pre.addAll(assign(t, Optional.empty(), Type.BOOL, e));
                yield new Arith(t, ATOM);
            }
        };
    }

    /**
     * {@code and} or {@code or}. Two tests, or two arithmetic expressions, merge into one; anything else is a list of
     * commands, where the right side, with what it needs first, is grouped when it is a list itself, since {@code &&}
     * and {@code ||} there bind alike.
     */
    private Cond combine(Expr left, Expr right, boolean and, List<BashCommand> pre) {
        Cond l = cond(left, pre);
        List<BashCommand> rightPre = new ArrayList<>();
        Cond r = cond(right, rightPre);
        if (rightPre.isEmpty()) {
            if (l instanceof Test x && r instanceof Test y)
                return new Test(and ? new BashCondition.And(andOperand(x.condition()), andOperand(y.condition()))
                        : new BashCondition.Or(x.condition(), y.condition()));
            if (l instanceof Arith x && r instanceof Arith y) {
                int level = and ? AND : OR;
                return new Arith(arithOperand(x, level) + (and ? " && " : " || ") + arithOperand(y, level), level);
            }
        }
        BashCommand lc = asCommand(l), rc = asCommand(r);
        if (!rightPre.isEmpty()) {
            rightPre.add(rc);
            rc = new BashCommand.BraceGroup(rightPre);
        } else if (rc instanceof BashCommand.AndOr) {
            rc = group(rc);
        }
        if (lc instanceof BashCommand.AndOr x) return new Cmd(and ? x.and(rc) : x.or(rc));
        return new Cmd(and ? and(lc, rc) : or(lc, rc));
    }

    private static BashCondition andOperand(BashCondition c) {
        return c instanceof BashCondition.Or ? new BashCondition.Paren(c) : c;
    }

    private static String arithOperand(Arith a, int level) {
        return a.level() < level ? "(" + a.text() + ")" : a.text();
    }

    private static Cond negated(Cond c) {
        return switch (c) {
            case Test t -> new Test(negated(t.condition()));
            case Arith a -> new Arith("!" + arithOperand(a, ATOM), ATOM);
            case Cmd x -> {
                if (x.command() instanceof BashCommand.Pipeline p && p.negated())
                    yield new Cmd(p.commands().size() == 1 ? p.commands().getFirst() : new BashCommand.Pipeline(false, p.commands()));
                yield new Cmd(not(x.command() instanceof BashCommand.AndOr ? group(x.command()) : x.command()));
            }
        };
    }

    private static BashCondition negated(BashCondition c) {
        return switch (c) {
            case BashCondition.Unary u when u.test() == UnaryTest.EMPTY -> new BashCondition.Unary(UnaryTest.NOT_EMPTY, u.operand());
            case BashCondition.Unary u when u.test() == UnaryTest.NOT_EMPTY -> new BashCondition.Unary(UnaryTest.EMPTY, u.operand());
            case BashCondition.Binary b when b.test() == BinaryTest.MATCHES -> new BashCondition.Binary(b.left(), BinaryTest.NOT_MATCHES, b.right());
            case BashCondition.Binary b when b.test() == BinaryTest.NOT_MATCHES -> new BashCondition.Binary(b.left(), BinaryTest.MATCHES, b.right());
            case BashCondition.Not n -> n.operand() instanceof BashCondition.Paren p ? p.inner() : n.operand();
            case BashCondition.And a -> new BashCondition.Not(new BashCondition.Paren(c));
            case BashCondition.Or o -> new BashCondition.Not(new BashCondition.Paren(c));
            default -> new BashCondition.Not(c);
        };
    }

    private Cond compare(Expr.Compare c, List<BashCommand> pre) {
        if (c.left().type().equals(Type.STRING)) {
            // the right side is quoted, or plain, so that it matches as text rather than as a pattern
            BashWord left = word(str(c.left(), pre));
            BashWord right = word(str(c.right(), pre));
            return new Test(new BashCondition.Binary(left, c.op() == Expr.Comparison.EQ ? BinaryTest.MATCHES : BinaryTest.NOT_MATCHES, right));
        }
        String op = switch (c.op()) {
            case EQ -> "==";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
        String left = arithmetic(c.left(), pre);
        return new Arith(left + " " + op + " " + arithmetic(c.right(), pre), COMPARE);
    }

    private Cond isEmpty(Expr value, boolean empty, List<BashCommand> pre) {
        if (value.type() instanceof Type.ListOf || value.type() instanceof Type.MapOf)
            return new Arith("${#" + collectionName(value, pre) + "[@]} " + (empty ? "==" : "!=") + " 0", COMPARE);
        // a JSON value that is null or missing is read as no text at all
        return new Test(new BashCondition.Unary(empty ? UnaryTest.EMPTY : UnaryTest.NOT_EMPTY, word(str(value, pre))));
    }

    /** A pattern after {@code =~}: as it is when it is plain, else from a variable, since quoted it would be text. */
    private BashWord regex(String pattern) {
        if (PLAIN_REGEX.matcher(pattern).matches()) return w(glob(pattern));
        return w(p(regexes.computeIfAbsent(pattern, k -> "koine_re" + (regexes.size() + 1))));
    }

    private static BashCommand asCommand(Cond c) {
        return switch (c) {
            case Test t -> test(t.condition());
            case Arith a -> new BashCommand.Arithmetic(a.text());
            case Cmd x -> x.command();
        };
    }

    // ---- names --------------------------------------------------------------------------------------------------

    private static Kind kind(Type t) {
        return t instanceof Type.ListOf ? Kind.ARRAY : t instanceof Type.MapOf ? Kind.MAP : Kind.SCALAR;
    }

    private static String colorCode(Statement.Color c) {
        return switch (c) {
            case RED -> "31";
            case GREEN -> "32";
            case YELLOW -> "33";
            case CYAN -> "36";
            case DEFAULT -> "0";
        };
    }

    /** A variable's Bash name, snake_case, clear of Bash's own and of the lowering's temporaries. */
    private static String name(Var v) {
        if (v.type() instanceof Type.ListOf l && (l.element() instanceof Type.ListOf || l.element() instanceof Type.MapOf)
                || v.type() instanceof Type.MapOf m && (m.value() instanceof Type.ListOf || m.value() instanceof Type.MapOf))
            throw new IllegalArgumentException("Bash has no lists or maps of collections: " + v);
        String n = snake(v.name());
        return n.equals("_") || n.startsWith("koine_") ? n + "_" : n;
    }

    private static String functionName(FunctionSig f) {
        String n = snake(f.name());
        return TAKEN.contains(n) || n.startsWith("koine_") ? n + "_" : n;
    }

    /** {@code --skip-build} for {@code SkipBuild}. */
    private static String option(Var v) {
        return "--" + name(v).replace('_', '-');
    }

    /** {@code test_linux_build} for {@code Test-LinuxBuild}, {@code built_from} for {@code builtFrom}. */
    private static String snake(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                b.append('_');
                continue;
            }
            if (Character.isUpperCase(c) && i > 0) {
                char before = name.charAt(i - 1);
                boolean lowerAfter = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                if (Character.isLowerCase(before) || Character.isDigit(before) || Character.isUpperCase(before) && lowerAfter)
                    b.append('_');
            }
            b.append(Character.toLowerCase(c));
        }
        String n = b.toString().replaceAll("_+", "_");
        if (!n.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("not a name Bash can have: " + name);
        return n;
    }
}
