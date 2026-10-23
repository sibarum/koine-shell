package sibarum.koine.ir.lower;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.FunctionSig;
import sibarum.koine.ir.OS;
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
 *   <li>A record is one variable per field, {@code p_id} and {@code p_log}, a nested one {@code app_windows_name}. A
 *       record that holds a list of records, and a missing optional record, do not fit, and are refused.</li>
 *   <li>A bool is 0 or 1 and tests as {@code (( b ))}; ints compare in {@code (( ))}, strings in {@code [[ ]]}, and a
 *       regular expression is matched with {@code [[ =~ ]]}, from a variable when it is not a plain word.</li>
 *   <li>What a function sets is {@code local}. Its parameters are unpacked by a prologue: a list is passed as its
 *       length then its items, and a record as its fields. It returns a value in {@code REPLY}, a list in
 *       {@code reply}, a record in {@code reply_<field>}, and a bool as its exit status; the caller copies it at once.
 *       {@code $(f)} would run it in a subshell, and lose what it sets and an {@code exit} in it. A native function is
 *       the same prologue, then its body as it is.</li>
 *   <li>{@link Statement.Finally} pushes a function holding its cleanup onto {@code koine_cleanups}, which an
 *       {@code EXIT} trap runs, innermost first; on the normal path it pops it and runs it there.
 *       {@link Statement.Fail} is {@code koine_fail}, which prints the message in red with the step
 *       {@code koine_step} names, and exits.</li>
 *   <li>Data from a read is the document's compact text, and each field of the record it is read as is read with
 *       {@code jq}, from the bundle: a missing string reads as empty, a number as 0, a bool as false.</li>
 *   <li>What a word needs computed first, a function's result, a capture, a list to join, goes in a temporary just
 *       before the command that uses it. In a condition it goes in the condition's own list, so that an {@code elif},
 *       a {@code while}, the right of {@code &&} and {@code ||} still evaluate only when reached.</li>
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
            "until", "do", "done", "in", "function", "time", "coproc", "jq", "sed");

    /** Text that is the same word unquoted. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=+@%,^-]+");
    /** A regular expression that can follow {@code =~} as it is. */
    private static final Pattern PLAIN_REGEX = Pattern.compile("\\^?[A-Za-z0-9_.,:/=@%-]+\\$?");

    private static final String FAIL = """
            koine_fail() {
                local message=$1
                if [[ -n ${koine_step-} ]]; then message="$koine_step failed: $message"; fi
                if [[ -t 2 ]]; then printf '\\e[31m%s\\e[0m\\n' "$message" >&2; else printf '%s\\n' "$message" >&2; fi
                exit 1
            }""";

    private static final String CLEANUPS = """
            koine_cleanups=()
            koine_run_cleanups() {
                local i
                for (( i = ${#koine_cleanups[@]} - 1; i >= 0; i-- )); do "${koine_cleanups[i]}"; done
            }
            trap koine_run_cleanups EXIT""";

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

    /** A field of a record that is one variable: its name after the record's, {@code _windows_name}, and its type. */
    private record Leaf(String suffix, List<String> path, Type type) {
    }

    /** The function being lowered: its locals, by kind, in the order they are first set. */
    private static final class Scope {
        final Map<String, Kind> locals = new LinkedHashMap<>();
    }

    /** Each pattern that needs a variable, and the variable's name. */
    private final Map<String, String> regexes = new LinkedHashMap<>();
    /** The functions that hold a {@link Statement.Finally}'s cleanup, defined before the script's own statements. */
    private final List<BashCommand> cleanupFunctions = new ArrayList<>();
    private boolean usesFail;
    private int temporaries;
    private int cleanups;
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
        Map<String, Kind> assigned = new LinkedHashMap<>();
        collectAssigned(s.body(), assigned);
        List<String> maps = assigned.entrySet().stream().filter(e -> e.getValue() == Kind.MAP).map(Map.Entry::getKey).toList();

        List<BashCommand> body = new ArrayList<>();
        body.add(comment(String.join("\n", help)));
        body.add(blank());
        regexes.forEach((pattern, name) -> body.add(assigns(set(name, lit(pattern)))));
        body.addAll(options);
        body.add(blank());
        if (usesFail) {
            body.add(comment("Fails the script: the message in red, with the step it is in, then exit, which runs the cleanup."));
            body.add(new BashCommand.Raw(FAIL));
            body.add(blank());
        }
        if (cleanups > 0) {
            body.add(comment("The cleanup of each finally still open, run innermost first however the script ends."));
            body.add(new BashCommand.Raw(CLEANUPS));
            for (BashCommand f : cleanupFunctions) body.add(f);
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

    private static boolean isFunction(Statement s) {
        return s instanceof Statement.Function || s instanceof Statement.NativeFunction;
    }

    /** The script's own statements, with a blank line before each function and each comment that starts a part. */
    private List<BashCommand> topLevel(List<Statement> statements) {
        List<BashCommand> out = new ArrayList<>();
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

    /**
     * The variables the statements set, by their Bash names and kinds, a record as its fields, not counting the
     * bodies of functions they define.
     */
    private static void collectAssigned(List<Statement> statements, Map<String, Kind> out) {
        for (Statement s : statements)
            switch (s) {
                case Statement.Set x -> declare(x.var(), out);
                case Statement.Append x -> declare(x.list(), out);
                case Statement.Put x -> declare(x.map(), out);
                case Statement.If x -> {
                    collectAssigned(x.then(), out);
                    collectAssigned(x.otherwise(), out);
                }
                case Statement.ForEach x -> {
                    declare(x.item(), out);
                    collectAssigned(x.body(), out);
                }
                case Statement.While x -> collectAssigned(x.body(), out);
                case Statement.Finally x -> {
                    collectAssigned(x.body(), out);
                    collectAssigned(x.cleanup(), out);
                }
                case Statement.EachLine x -> {
                    declare(x.line(), out);
                    collectAssigned(x.body(), out);
                    declare(x.status(), out);
                }
                default -> {
                }
            }
    }

    /** A variable's Bash names and kinds: one, or one per field of a record. */
    private static void declare(Var v, Map<String, Kind> out) {
        if (v.type() instanceof Type.Record r) for (Leaf l : leaves(r)) out.putIfAbsent(name(v) + l.suffix(), kind(l.type()));
        else out.putIfAbsent(name(v), kind(v.type()));
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
                if (x.var().type() instanceof Type.Record r) return assignRecord(name(x.var()), r, x.value());
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
                if (x.item().type() instanceof Type.Record) throw new IllegalArgumentException("Bash has no lists of records: " + x);
                List<BashWord> items = listWords(x.collection(), pre);
                pre.add(new BashCommand.For(name(x.item()), Optional.of(items), compoundBody(x.body())));
            }
            case Statement.While x -> pre.add(new BashCommand.While(false, conditionList(x.condition()), compoundBody(x.body())));
            case Statement.Finally x -> finallyBlock(x, pre);
            case Statement.Function x -> pre.add(functionDefinition(x));
            case Statement.NativeFunction x -> pre.add(nativeFunction(x));
            case Statement.Return x -> {
                if (scope == null) {
                    if (x.value().isPresent()) throw new IllegalArgumentException("a script returns nothing; it exits: " + x);
                    pre.add(cmd("exit", "0"));
                } else {
                    x.value().ifPresent(v -> returnValue(v, pre));
                    if (x.value().filter(v -> v instanceof Expr.BoolLit).isEmpty()) pre.add(cmd("return"));
                }
            }
            case Statement.Invoke x -> pre.add(call(x.function(), x.arguments(), pre));
            case Statement.Exit x -> pre.add(cmd("exit", word(str(x.status(), pre))));
            case Statement.Fail x -> {
                usesFail = true;
                pre.add(cmd("koine_fail", word(str(x.message(), pre))));
            }
            case Statement.Print x -> pre.add(cmd("printf", lit("%s\\n"), word(str(x.message(), pre))));
            case Statement.Run x -> {
                BashCommand c = command(x.command(), pre);
                pre.add(x.onFailure() == Statement.OnFailure.ABORT ? or(c, cmd("exit", p("?"))) : c);
            }
            case Statement.EachLine x -> eachLine(x, pre);
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

    /**
     * The cleanup becomes a function, pushed onto {@code koine_cleanups} before the body and popped and run after it.
     * It runs in the caller's scope, so it sees and sets the caller's variables; only its temporaries are its own.
     */
    private void finallyBlock(Statement.Finally x, List<BashCommand> pre) {
        String fname = "koine_cleanup_" + ++cleanups;
        Scope outer = scope;
        scope = new Scope();
        List<BashCommand> cleanup = block(x.cleanup());
        Scope inner = scope;
        scope = outer;
        List<BashCommand> fbody = new ArrayList<>(localDeclarations(inner.locals));
        fbody.addAll(cleanup);
        if (fbody.isEmpty()) fbody.add(cmd(":"));
        cleanupFunctions.add(function(fname, new BashCommand.BraceGroup(fbody)));

        pre.add(assigns(appendArray("koine_cleanups", fname)));
        pre.addAll(block(x.body()));
        pre.add(cmd("unset", sq("koine_cleanups[-1]")));
        pre.add(cmd(fname));
    }

    private BashCommand functionDefinition(Statement.Function x) {
        FunctionSig sig = x.signature();
        Scope outer = scope;
        scope = new Scope();
        Map<String, Kind> params = new LinkedHashMap<>();
        for (Var v : sig.parameters()) declare(v, params);
        Map<String, Kind> assigned = new LinkedHashMap<>();
        collectAssigned(x.body(), assigned);
        assigned.forEach((n, k) -> {
            if (!params.containsKey(n)) scope.locals.put(n, k);
        });

        List<BashCommand> b = block(x.body());
        Scope inner = scope;
        scope = outer;

        List<BashCommand> decls = new ArrayList<>(prologue(sig, false));
        decls.addAll(localDeclarations(inner.locals));
        decls.addAll(b);
        if (decls.isEmpty()) decls.add(cmd(":"));
        return function(functionName(sig), new BashCommand.BraceGroup(decls));
    }

    /** The prologue, then the body as it is. Its parameters keep the names they were written with. */
    private BashCommand nativeFunction(Statement.NativeFunction x) {
        if (x.os() != OS.Linux) throw new IllegalArgumentException(x.signature().name() + " is native to " + x.os() + ", not Linux");
        if (x.body().contains("koine_fail")) usesFail = true;
        List<BashCommand> body = new ArrayList<>(prologue(x.signature(), true));
        body.add(new BashCommand.Raw(x.body()));
        return function(functionName(x.signature()), new BashCommand.BraceGroup(body));
    }

    /**
     * {@code local a=$1 b=$2}: the parameters, unpacked in order. A list comes as its length then its items, so the
     * arguments before it are shifted off first: {@code shift 2; local -a xs=("${@:2:$1}"); shift $(( $1 + 1 ))}.
     */
    private static List<BashCommand> prologue(FunctionSig sig, boolean written) {
        List<BashCommand> out = new ArrayList<>();
        List<Object> scalars = new ArrayList<>(List.of("local"));
        List<Var> ps = sig.parameters();
        for (int i = 0; i < ps.size(); i++) {
            Var v = ps.get(i);
            String n = written ? writtenName(v) : name(v);
            List<Leaf> leaves = v.type() instanceof Type.Record r ? leaves(r) : List.of(new Leaf("", List.of(), v.type()));
            for (int j = 0; j < leaves.size(); j++) {
                Leaf l = leaves.get(j);
                boolean last = i == ps.size() - 1 && j == leaves.size() - 1;
                if (l.type() instanceof Type.MapOf) throw new IllegalArgumentException("a Bash function cannot take a map: " + v);
                if (l.type() instanceof Type.ListOf) {
                    if (scalars.size() > 1) {
                        out.add(cmd(scalars.toArray()));
                        out.add(cmd("shift", Integer.toString(scalars.size() - 1)));
                        scalars = new ArrayList<>(List.of("local"));
                    }
                    BashParameter items = new BashParameter(BashParameter.Prefix.NONE, "@", Optional.empty(),
                            Optional.of(new Substring("2", Optional.of("$1"))));
                    out.add(cmd("local", "-a", w(glob(n + l.suffix() + "=("), dq(items), glob(")"))));
                    if (!last) out.add(cmd("shift", w(new BashWordPart.ArithmeticExpansion("$1 + 1"))));
                } else {
                    scalars.add(w(n + l.suffix() + "=", p(Integer.toString(scalars.size()))));
                }
            }
        }
        if (scalars.size() > 1) out.add(cmd(scalars.toArray()));
        return out;
    }

    private static List<BashCommand> localDeclarations(Map<String, Kind> locals) {
        List<BashCommand> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            List<Object> words = new ArrayList<>(List.of("local"));
            if (k == Kind.ARRAY) words.add("-a");
            if (k == Kind.MAP) words.add("-A");
            int head = words.size();
            locals.forEach((n, kind) -> {
                if (kind == k) words.add(n);
            });
            if (words.size() > head) out.add(cmd(words.toArray()));
        }
        return out;
    }

    /** A function's result, where its caller copies it from: {@code REPLY}, {@code reply}, or its exit status. */
    private void returnValue(Expr v, List<BashCommand> pre) {
        switch (v.type()) {
            case Type.Bool b when v instanceof Expr.BoolLit l -> pre.add(cmd("return", l.value() ? "0" : "1"));
            case Type.Bool b -> pre.add(asCommand(cond(v, pre)));
            case Type.Record r -> pre.addAll(assignRecord("reply", r, v));
            case Type.MapOf m -> throw new IllegalArgumentException("a function cannot return a map: " + v);
            default -> pre.addAll(assign(v.type() instanceof Type.ListOf ? "reply" : "REPLY", Optional.empty(), v.type(), v));
        }
    }

    // ---- assignments --------------------------------------------------------------------------------------------

    /** Commands that set {@code target} (or its {@code subscript}) to the value, with what they need first. */
    private List<BashCommand> assign(String target, Optional<String> subscript, Type type, Expr value) {
        List<BashCommand> pre = new ArrayList<>();
        if (value instanceof Expr.Call c && !type.equals(Type.BOOL)) {
            pre.add(call(c.function(), c.arguments(), pre));
            pre.addAll(copy(target, subscript, type, type instanceof Type.ListOf ? "reply" : "REPLY", pre));
            return pre;
        }
        if (value instanceof Expr.Capture c) {
            pre.add(capture(c, scalar(target, subscript, w(sub(command(c.command(), pre)))), target, subscript, pre));
            return pre;
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
                } else if (value instanceof Expr.Ref || value instanceof Expr.Get || value instanceof Expr.At || value instanceof Expr.FieldOf) {
                    pre.add(assigns(scalar(target, subscript, assignWord(str(value, pre)))));
                } else {
                    BashCommand c = asCommand(cond(value, pre));
                    BashCommand yes = assigns(scalar(target, subscript, w("1"))), no = assigns(scalar(target, subscript, w("0")));
                    pre.add(c instanceof BashCommand.AndOr a ? a.and(yes).or(no) : and(c, yes).or(no));
                }
            }
            case Type.Record r -> throw new IllegalStateException("a record is assigned field by field: " + value);
            default -> pre.add(assigns(scalar(target, subscript, assignWord(str(value, pre)))));
        }
        return pre;
    }

    /**
     * {@code target=$(cmd) || ...}: what a failed capture does. Stopping exits with its status; a fallback is set
     * instead; a failure message fails the script.
     */
    private BashCommand capture(Expr.Capture c, BashAssignment set, String target, Optional<String> subscript, List<BashCommand> pre) {
        BashCommand s = assigns(set);
        return switch (c.onFailure()) {
            case Expr.Fallback.Stop x -> or(s, cmd("exit", p("?")));
            case Expr.Fallback.Value x -> or(s, assigns(scalar(target, subscript, assignWord(str(x.value(), pre)))));
            case Expr.Fallback.Fail x -> {
                usesFail = true;
                yield or(s, cmd("koine_fail", word(str(x.message(), pre))));
            }
        };
    }

    /** Sets a record's variables, {@code prefix_field} for each field, from the value. */
    private List<BashCommand> assignRecord(String prefix, Type.Record r, Expr value) {
        List<BashCommand> pre = new ArrayList<>();
        switch (value) {
            case Expr.Call c -> {
                pre.add(call(c.function(), c.arguments(), pre));
                for (Leaf l : leaves(r)) pre.addAll(copy(prefix + l.suffix(), Optional.empty(), l.type(), "reply" + l.suffix(), pre));
            }
            case Expr.MakeRecord m -> {
                for (int i = 0; i < m.values().size(); i++) {
                    Type.Record.Field f = r.fields().get(i);
                    String n = prefix + "_" + f.name();
                    if (f.type() instanceof Type.Record inner) pre.addAll(assignRecord(n, inner, m.values().get(i)));
                    else pre.addAll(assign(n, Optional.empty(), f.type(), m.values().get(i)));
                }
            }
            case Expr.ReadAs x -> {
                BashWord data = word(str(x.data(), pre));
                for (Leaf l : leaves(r)) pre.add(readLeaf(prefix + l.suffix(), l, data));
            }
            default -> {
                String source = recordName(value, pre);
                if (!source.equals(prefix))
                    for (Leaf l : leaves(r)) pre.addAll(copy(prefix + l.suffix(), Optional.empty(), l.type(), source + l.suffix(), pre));
            }
        }
        return pre;
    }

    /**
     * One field of data, read with {@code jq}: a string that is null or missing as empty, a number as 0 unless it is
     * one, a bool as false unless it is true, a list one item a line.
     */
    private static BashCommand readLeaf(String target, Leaf l, BashWord data) {
        StringBuilder path = new StringBuilder();
        for (String n : l.path()) {
            if (n.matches("[A-Za-z_][A-Za-z0-9_]*")) path.append('.').append(n);
            else path.append(path.isEmpty() ? "." : "").append("[\"").append(n.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
        }
        BashRedirection from = hereString(data);
        return switch (l.type()) {
            case Type.Int i -> assigns(set(target, sub(cmd("jq", "-r", lit("(" + path + " | tonumber? | floor) // 0")).redirect(from))));
            case Type.Bool b -> and(cmd("jq", "-e", lit(path + " == true")).redirect(to("/dev/null"), from), assigns(set(target, "1")))
                    .or(assigns(set(target, "0")));
            case Type.ListOf x when x.element().equals(Type.STRING) ->
                    cmd("mapfile", "-t", target).redirect(fromProcess(cmd("jq", "-r", lit(path + "[]?")).redirect(from)));
            case Type.Str s -> assigns(set(target, sub(cmd("jq", "-r", lit(path + " | values")).redirect(from))));
            default -> throw new IllegalArgumentException("Bash cannot read data as " + l.type());
        };
    }

    /** {@code target} set to the list, for values that are not a call. */
    private List<BashCommand> setList(String target, Expr value, List<BashCommand> pre) {
        return switch (value) {
            case Expr.ListLit l -> {
                List<BashWord> items = new ArrayList<>();
                for (Expr i : l.items()) items.add(word(str(i, pre)));
                yield List.of(assigns(new BashAssignment(target, Optional.empty(), false, new BashAssignment.Array(items))));
            }
            case Expr.Keys k -> List.of(assigns(array(target, dq(keys(mapName(k.map(), pre))))));
            default -> {
                String source = listName(value, pre);
                yield source.equals(target) ? List.of() : List.of(assigns(array(target, dq(p(source, "@")))));
            }
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

    // ---- records ------------------------------------------------------------------------------------------------

    /** A record's fields as variables, nested records flattened; a list of records does not fit, nor does a map. */
    private static List<Leaf> leaves(Type.Record r) {
        List<Leaf> out = new ArrayList<>();
        leaves(r, "", List.of(), out);
        return out;
    }

    private static void leaves(Type.Record r, String suffix, List<String> path, List<Leaf> out) {
        for (Type.Record.Field f : r.fields()) {
            List<String> p = new ArrayList<>(path);
            p.add(f.name());
            String s = suffix + "_" + f.name();
            switch (f.type()) {
                case Type.Record inner -> {
                    if (f.optional())
                        throw new IllegalArgumentException("Bash cannot tell a missing record apart yet: " + r + "." + f.name());
                    leaves(inner, s, p, out);
                }
                case Type.ListOf l when l.element() instanceof Type.Record || l.element() instanceof Type.ListOf ->
                        throw new IllegalArgumentException("Bash has no lists of records or lists yet: " + r + "." + f.name());
                case Type.MapOf m -> throw new IllegalArgumentException("Bash has no maps in records: " + r + "." + f.name());
                default -> out.add(new Leaf(s, p, f.type()));
            }
        }
    }

    /** The name a record's variables start with: its variable's, a field's under it, or a temporary's. */
    private String recordName(Expr e, List<BashCommand> pre) {
        return switch (e) {
            case Expr.Ref r -> name(r.var());
            case Expr.FieldOf f -> recordName(f.record(), pre) + "_" + f.field();
            default -> {
                Type.Record r = (Type.Record) e.type();
                String t = "koine_" + ++temporaries;
                for (Leaf l : leaves(r)) {
                    if (scope != null) scope.locals.put(t + l.suffix(), kind(l.type()));
                }
                pre.addAll(assignRecord(t, r, e));
                yield t;
            }
        };
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    private BashCommand.Simple command(Command c, List<BashCommand> pre) {
        BashCommand.Simple command = new BashCommand.Simple(List.of(), commandWords(c, pre), List.of());
        return c.quiet() ? command.redirect(to("/dev/null"), errToOut()) : command;
    }

    private List<BashWord> commandWords(Command c, List<BashCommand> pre) {
        List<BashWord> words = new ArrayList<>();
        words.add(word(str(c.program(), pre)));
        for (Expr a : c.arguments()) {
            if (a.type() instanceof Type.ListOf) words.addAll(listWords(a, pre));
            else words.add(word(str(a, pre)));
        }
        return words;
    }

    /** {@code f a b}: a list as its length then its items, a record as its fields, as the prologue unpacks them. */
    private BashCommand.Simple call(FunctionSig f, List<Expr> arguments, List<BashCommand> pre) {
        List<BashWord> words = new ArrayList<>();
        words.add(w(functionName(f)));
        for (Expr a : arguments) {
            switch (a.type()) {
                case Type.MapOf m -> throw new IllegalArgumentException("a Bash function cannot take a map: " + a);
                case Type.ListOf l -> listArgument(a, words, pre);
                case Type.Record r -> {
                    String n = recordName(a, pre);
                    for (Leaf l : leaves(r)) {
                        if (l.type() instanceof Type.ListOf) {
                            words.add(w(length(n + l.suffix(), "@")));
                            words.add(w(dq(p(n + l.suffix(), "@"))));
                        } else {
                            words.add(w(dq(p(n + l.suffix()))));
                        }
                    }
                }
                default -> words.add(word(str(a, pre)));
            }
        }
        return new BashCommand.Simple(List.of(), words, List.of());
    }

    private void listArgument(Expr a, List<BashWord> words, List<BashCommand> pre) {
        switch (a) {
            case Expr.ListLit l -> {
                words.add(w(Integer.toString(l.items().size())));
                for (Expr i : l.items()) words.add(word(str(i, pre)));
            }
            case Expr.Keys k -> {
                String map = mapName(k.map(), pre);
                words.add(w(length(map, "@")));
                words.add(w(dq(keys(map))));
            }
            default -> {
                String list = listName(a, pre);
                words.add(w(length(list, "@")));
                words.add(w(dq(p(list, "@"))));
            }
        }
    }

    private static BashRedirection fromProcess(BashCommand command) {
        return new BashRedirection.ToFile(Optional.empty(), BashRedirection.FileOperator.IN,
                w(new BashWordPart.ProcessSubstitution(false, List.of(command))));
    }

    // ---- words --------------------------------------------------------------------------------------------------

    /**
     * A string, number, bool or data, as the parts of a word, the kinds double quotes can hold. What cannot be a part
     * of a word is put in a temporary first.
     */
    private List<BashWordPart> str(Expr e, List<BashCommand> pre) {
        if (e.type() instanceof Type.ListOf || e.type() instanceof Type.MapOf || e.type() instanceof Type.Record)
            throw new IllegalArgumentException("a list, a map or a record is not one word: " + e);
        boolean computed = e instanceof Expr.Call || e instanceof Expr.Capture
                || e.type().equals(Type.BOOL) && !(e instanceof Expr.BoolLit || e instanceof Expr.Ref || e instanceof Expr.Get
                || e instanceof Expr.At || e instanceof Expr.FieldOf);
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
            case Expr.At a -> {
                String list = listName(a.list(), pre);
                yield List.of(p(list, arithmetic(a.index(), pre)));
            }
            case Expr.Get g -> {
                String map = mapName(g.map(), pre);
                yield List.of(p(map, key(g.key(), pre)));
            }
            case Expr.FieldOf f -> List.of(p(recordName(f.record(), pre) + "_" + f.field()));
            default -> throw new IllegalArgumentException("not a word in Bash: " + e);
        };
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

    /** The array a list is in: its variable, a record's field, or a temporary. */
    private String listName(Expr e, List<BashCommand> pre) {
        if (e instanceof Expr.Ref r) return name(r.var());
        if (e instanceof Expr.FieldOf f) return recordName(f.record(), pre) + "_" + f.field();
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
            case Expr.FieldOf f -> recordName(f.record(), pre) + "_" + f.field();
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
            case Expr.FieldOf f -> new Arith(recordName(f.record(), pre) + "_" + f.field(), ATOM);
            case Expr.Not n -> n.operand() instanceof Expr.IsEmpty x ? isEmpty(x.value(), false, pre) : negated(cond(n.operand(), pre));
            case Expr.And a -> combine(a.left(), a.right(), true, pre);
            case Expr.Or o -> combine(o.left(), o.right(), false, pre);
            case Expr.Compare c -> compare(c, pre);
            case Expr.Matches m -> new Test(new BashCondition.Binary(word(str(m.text(), pre)), BinaryTest.REGEX, regex(m.pattern())));
            case Expr.In x -> in(x, pre);
            case Expr.IsEmpty x -> isEmpty(x.value(), true, pre);
            case Expr.HasKey h -> {
                String map = mapName(h.map(), pre);
                BashParameter set = op(map, key(h.key(), pre), new Default(false, DefaultKind.USE_ALTERNATE, w("set")));
                yield new Test(new BashCondition.Unary(UnaryTest.NOT_EMPTY, w(dq(set))));
            }
            case Expr.Succeeds s -> new Cmd(command(s.command(), pre));
            case Expr.Call c -> new Cmd(call(c.function(), c.arguments(), pre));
            default -> {
                // a map entry or a list item: 0 or 1, in a temporary
                String t = temp(Kind.SCALAR, pre);
                pre.addAll(assign(t, Optional.empty(), Type.BOOL, e));
                yield new Arith(t, ATOM);
            }
        };
    }

    /**
     * A string in a string: {@code [[ $text == *"$part"* ]]}, quoted so that it matches as text. An item in a list:
     * a loop that sets a temporary to 1 when it finds it.
     */
    private Cond in(Expr.In x, List<BashCommand> pre) {
        List<BashWordPart> item = str(x.item(), pre);
        if (x.container().type().equals(Type.STRING)) {
            List<BashWordPart> pattern = new ArrayList<>(List.of(glob("*")));
            pattern.addAll(quoted(item).parts());
            pattern.add(glob("*"));
            return new Test(new BashCondition.Binary(word(str(x.container(), pre)), BinaryTest.MATCHES, new BashWord(pattern)));
        }
        String found = temp(Kind.SCALAR, pre), each = temp(Kind.SCALAR, pre);
        pre.add(assigns(set(found, "0")));
        BashCommand matches = test(new BashCondition.Binary(w(p(each)), BinaryTest.MATCHES, quoted(item)));
        pre.add(for_(each, listWords(x.container(), pre), and(matches, group(assigns(set(found, "1")), cmd("break")))));
        return new Arith(found, ATOM);
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
        if (value.type() instanceof Type.Record)
            throw new IllegalArgumentException("Bash cannot tell a missing record apart yet: " + value);
        if (value.type() instanceof Type.ListOf || value.type() instanceof Type.MapOf)
            return new Arith("${#" + collectionName(value, pre) + "[@]} " + (empty ? "==" : "!=") + " 0", COMPARE);
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

    /** A variable's Bash name, snake_case, clear of Bash's own, the lowering's temporaries and the results. */
    private static String name(Var v) {
        if (v.type() instanceof Type.ListOf l && (l.element() instanceof Type.ListOf || l.element() instanceof Type.MapOf)
                || v.type() instanceof Type.MapOf m && (m.value() instanceof Type.ListOf || m.value() instanceof Type.MapOf))
            throw new IllegalArgumentException("Bash has no lists or maps of collections: " + v);
        String n = snake(v.name());
        return n.equals("_") || n.startsWith("koine_") || n.equals("reply") ? n + "_" : n;
    }

    /** A native function's parameter, by the name its body uses. */
    private static String writtenName(Var v) {
        if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("not a name Bash can have: " + v.name());
        return v.name();
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
            if (c == '-' || c == '.') {
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
