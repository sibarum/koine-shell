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
import sibarum.koine.ir.OS;
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
 *   <li>Every value is text. A bool is 1 or 0, tested with {@code (( ))}.</li>
 *   <li>Lists are arrays, which count from 1, so {@code At(i)} reads {@code [i+1]}. Maps are {@code typeset -A}, in
 *       no particular order.</li>
 *   <li>A record is one variable per field, {@code p_id} and {@code p_log}, a nested one {@code app_windows_name}. A
 *       record that holds a list of records, and a missing optional record, do not fit, and are refused.</li>
 *   <li>A value that needs a command run first (a function's result, a capture) has it run just before the command
 *       that uses it; in a condition, inside it, so {@code and} and {@code or} evaluate only what they must.</li>
 *   <li>A function's parameters are unpacked by a prologue: a list is passed as its length then its items, and a
 *       record as its fields. It returns a value in {@code REPLY}, a list in {@code reply}, a record in
 *       {@code reply_<field>}, and a bool as its exit status: {@code $(f)} would run it in a subshell, which loses what
 *       it sets, and its exit. Every variable a function sets is local to it, as in PowerShell. A native function's
 *       parameters are {@code local -h}, so that one named like a special parameter, {@code path}, is ordinary.</li>
 *   <li>{@link Statement.OnFailure#ABORT} is {@code cmd || exit $?}, written out: {@code set -e} is switched off in
 *       conditions and in functions called from them.</li>
 *   <li>{@link Statement.Finally} pushes a function holding its cleanup onto {@code koine_cleanups}, which an
 *       {@code EXIT} trap runs, innermost first, since an {@code exit} skips {@code always} blocks; on the normal path
 *       it pops it and runs it there. {@link Statement.Fail} is {@code koine_fail}.</li>
 *   <li>Data from a read is the document's compact text, and each field of the record it is read as is read with
 *       {@code jq}, from the bundle.</li>
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

    private static final String FAIL = """
            koine_fail() {
                local message=$1
                if [[ -n ${koine_step-} ]]; then message="$koine_step failed: $message"; fi
                if [[ -t 2 ]]; then print -u2 -r -- $'\\e[31m'"$message"$'\\e[0m'; else print -u2 -r -- "$message"; fi
                exit 1
            }""";

    private static final String CLEANUPS = """
            koine_cleanups=()
            koine_run_cleanups() {
                local i
                for (( i = ${#koine_cleanups}; i >= 1; i-- )); do "${koine_cleanups[i]}"; done
            }
            trap koine_run_cleanups EXIT""";

    /** A field of a record that is one variable: its name after the record's, {@code _windows_name}, and its type. */
    private record Leaf(String suffix, List<String> path, Type type) {
    }

    /** Commands that the expression being lowered needs run before the command that uses it. */
    private List<ZshCommand> setup = new ArrayList<>();
    /** The temporaries of the function being lowered, which it declares local; null outside a function. */
    private Map<String, Type> functionTemps;
    /** The functions that hold a {@link Statement.Finally}'s cleanup, defined before the script's own statements. */
    private final List<ZshCommand> cleanupFunctions = new ArrayList<>();
    private boolean usesFail;
    private int temporaries;
    private int cleanups;

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
        List<ZshCommand> main = topLevel(s.body());
        if (usesFail) {
            body.add(comment("Fails the script: the message in red, with the step it is in, then exit, which runs the cleanup."));
            body.add(new ZshCommand.Raw(FAIL));
            body.add(blank());
        }
        if (cleanups > 0) {
            body.add(comment("The cleanup of each finally still open, run innermost first however the script ends."));
            body.add(new ZshCommand.Raw(CLEANUPS));
            body.addAll(cleanupFunctions);
            body.add(blank());
        }
        body.addAll(main);
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

    private static boolean isFunction(Statement s) {
        return s instanceof Statement.Function || s instanceof Statement.NativeFunction;
    }

    /** The script's own statements, with a blank line before each function and each comment that starts a part. */
    private List<ZshCommand> topLevel(List<Statement> statements) {
        List<ZshCommand> out = new ArrayList<>();
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
            case Statement.Set x -> x.var().type() instanceof Type.Record r
                    ? assignRecord(name(x.var()), r, x.value())
                    : assign(name(x.var()), x.var().type(), x.value());
            case Statement.Append x -> List.of(assigns(new ZshAssignment(name(x.list()), Optional.empty(), true,
                    new ZshAssignment.Array(List.of(arg(element(x.item())))))));
            case Statement.Put x -> List.of(assigns(new ZshAssignment(name(x.map()), Optional.of(subscript(text(x.key()))), false,
                    new ZshAssignment.Scalar(bareWord(element(x.value()))))));
            case Statement.If x -> List.of(ifCommand(x));
            case Statement.ForEach x -> {
                if (x.item().type() instanceof Type.Record) throw new IllegalArgumentException("Zsh has no lists of records: " + x);
                yield List.of(new ZshCommand.For(List.of(name(x.item())), Optional.of(listWords(x.collection())), body(x.body())));
            }
            case Statement.While x -> List.of(new ZshCommand.While(false, conditionList(x.condition()), body(x.body())));
            case Statement.Finally x -> finallyBlock(x);
            case Statement.Function x -> List.of(function(x));
            case Statement.NativeFunction x -> List.of(nativeFunction(x));
            case Statement.Return x -> return_(x);
            case Statement.Invoke x -> List.of(call(x.function(), x.arguments()));
            case Statement.Exit x -> List.of(cmd("exit", arg(text(x.status()))));
            case Statement.Fail x -> {
                usesFail = true;
                yield List.of(cmd("koine_fail", arg(text(x.message()))));
            }
            case Statement.Print x -> List.of(cmd("print", "-r", "--", arg(text(x.message()))));
            case Statement.Run x -> {
                ZshCommand.Simple c = command(x.command());
                yield List.of(x.onFailure() == Statement.OnFailure.ABORT ? either(c, cmd("exit", p("?"))) : c);
            }
            case Statement.EachLine x -> eachLine(x);
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

    /** Sets a variable. A call copies it from {@code REPLY} or {@code reply}, a bool is set to 1 or 0 by its test. */
    private List<ZshCommand> assign(String name, Type type, Expr value) {
        if (value instanceof Expr.Call c && !type.equals(Type.BOOL)) {
            List<ZshCommand> out = new ArrayList<>(List.of(call(c.function(), c.arguments())));
            out.addAll(copy(name, type, type instanceof Type.ListOf ? "reply" : "REPLY"));
            return out;
        }
        if (value instanceof Expr.Capture c) return List.of(capture(c, name));
        return switch (type) {
            case Type.Bool b -> switch (value) {
                case Expr.BoolLit l -> List.of(assigns(set(name, l.value() ? "1" : "0")));
                case Expr.Ref r -> List.of(assigns(set(name, p(name(r.var())))));
                case Expr.FieldOf f -> List.of(assigns(set(name, p(recordName(f.record()) + "_" + f.field()))));
                default -> List.of(either(both(check(value), assigns(set(name, "1"))), assigns(set(name, "0"))));
            };
            case Type.ListOf l -> List.of(assigns(new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Array(listWords(value)))));
            case Type.MapOf m -> mapAssign(name, mapWords(value));
            case Type.Record r -> throw new IllegalStateException("a record is assigned field by field: " + value);
            default -> List.of(assigns(set(name, bareWord(text(value)))));
        };
    }

    /** {@code name=$(cmd) || ...}: stopping exits with its status, a fallback is set instead, a message fails the script. */
    private ZshCommand capture(Expr.Capture c, String name) {
        ZshCommand set = assigns(set(name, new ZshWordPart.CommandSubstitution(List.of(command(c.command())))));
        return switch (c.onFailure()) {
            case Expr.Fallback.Stop x -> either(set, cmd("exit", p("?")));
            case Expr.Fallback.Value x -> either(set, assigns(set(name, bareWord(text(x.value())))));
            case Expr.Fallback.Fail x -> {
                usesFail = true;
                yield either(set, cmd("koine_fail", arg(text(x.message()))));
            }
        };
    }

    /** {@code target} set to what the variable {@code source} holds. */
    private List<ZshCommand> copy(String target, Type type, String source) {
        return switch (type) {
            case Type.ListOf l -> List.of(assigns(new ZshAssignment(target, Optional.empty(), false,
                    new ZshAssignment.Array(List.of(w(dq(p(source, "@"))))))));
            case Type.MapOf m -> mapAssign(target, List.of(w(dq(param(source).flags("@kv").build()))));
            default -> List.of(assigns(set(target, p(source))));
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

    /** Sets a record's variables, {@code prefix_field} for each field, from the value. */
    private List<ZshCommand> assignRecord(String prefix, Type.Record r, Expr value) {
        List<ZshCommand> out = new ArrayList<>();
        switch (value) {
            case Expr.Call c -> {
                out.add(call(c.function(), c.arguments()));
                for (Leaf l : leaves(r)) out.addAll(copy(prefix + l.suffix(), l.type(), "reply" + l.suffix()));
            }
            case Expr.MakeRecord m -> {
                for (int i = 0; i < m.values().size(); i++) {
                    Type.Record.Field f = r.fields().get(i);
                    String n = prefix + "_" + f.name();
                    out.addAll(f.type() instanceof Type.Record inner ? assignRecord(n, inner, m.values().get(i))
                            : assign(n, f.type(), m.values().get(i)));
                }
            }
            case Expr.ReadAs x -> {
                ZshWord data = bareWord(text(x.data()));
                for (Leaf l : leaves(r)) out.add(readLeaf(prefix + l.suffix(), l, data));
            }
            default -> {
                String source = recordName(value);
                if (!source.equals(prefix)) for (Leaf l : leaves(r)) out.addAll(copy(prefix + l.suffix(), l.type(), source + l.suffix()));
            }
        }
        return out;
    }

    /**
     * One field of data, read with {@code jq}: a string that is null or missing as empty, a number as 0 unless it is
     * one, a bool as false unless it is true, a list one item a line, blank ones dropped.
     */
    private static ZshCommand readLeaf(String target, Leaf l, ZshWord data) {
        StringBuilder path = new StringBuilder();
        for (String n : l.path())
            // .a["b-c"] rather than .a.["b-c"], which jq 1.6 does not read
            path.append(n.matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + n
                    : (path.isEmpty() ? "." : "") + "[\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]");
        ZshRedirection from = hereString(data);
        return switch (l.type()) {
            case Type.Int i -> assigns(set(target, sub(cmd("jq", "-r", literalWord("(" + path + " | tonumber? | floor) // 0")).redirect(from))));
            case Type.Bool b -> either(both(cmd("jq", "-e", literalWord(path + " == true")).redirect(to("/dev/null"), from),
                    assigns(set(target, "1"))), assigns(set(target, "0")));
            case Type.ListOf x when x.element().equals(Type.STRING) -> assigns(new ZshAssignment(target, Optional.empty(), false,
                    new ZshAssignment.Array(List.of(w(param(dq(sub(cmd("jq", "-r", literalWord(path + "[]?")).redirect(from)))).flags("f").build())))));
            case Type.Str s -> assigns(set(target, sub(cmd("jq", "-r", literalWord(path + " | values")).redirect(from))));
            default -> throw new IllegalArgumentException("Zsh cannot read data as " + l.type());
        };
    }

    /**
     * The cleanup becomes a function, pushed onto {@code koine_cleanups} before the body and popped and run after it.
     * It runs in the caller's scope, so it sees and sets the caller's variables; only its temporaries are its own.
     */
    private List<ZshCommand> finallyBlock(Statement.Finally x) {
        String fname = "koine_cleanup_" + ++cleanups;
        Map<String, Type> outerTemps = functionTemps;
        functionTemps = new LinkedHashMap<>();
        List<ZshCommand> cleanup = body(x.cleanup());
        List<ZshCommand> all = new ArrayList<>(declarations(functionTemps));
        all.addAll(cleanup);
        functionTemps = outerTemps;
        cleanupFunctions.add(new ZshCommand.Function(List.of(fname), false, group(all.toArray(ZshCommand[]::new))));

        List<ZshCommand> out = new ArrayList<>();
        out.add(assigns(appendArray("koine_cleanups", fname)));
        for (Statement s : x.body()) out.addAll(statement(s));
        out.add(cmd("shift", "-p", "koine_cleanups"));
        out.add(cmd(fname));
        return out;
    }

    private ZshCommand function(Statement.Function x) {
        Map<String, Type> outerTemps = functionTemps;
        functionTemps = new LinkedHashMap<>();
        List<ZshCommand> b = body(x.body());
        List<ZshCommand> all = new ArrayList<>(List.of(cmd("emulate", "-L", "zsh")));
        all.addAll(prologue(x.signature(), false));
        Map<String, Type> locals = new LinkedHashMap<>();
        assigned(x.body(), locals);
        for (Var v : x.signature().parameters()) {
            if (v.type() instanceof Type.Record r) for (Leaf l : leaves(r)) locals.remove(name(v) + l.suffix());
            else locals.remove(name(v));
        }
        locals.putAll(functionTemps);
        all.addAll(declarations(locals));
        all.addAll(b);
        functionTemps = outerTemps;
        return new ZshCommand.Function(List.of(x.signature().name()), false, group(all.toArray(ZshCommand[]::new)));
    }

    /** The prologue, then the body as it is. Its parameters keep the names they were written with. */
    private ZshCommand nativeFunction(Statement.NativeFunction x) {
        if (x.os() != OS.Mac) throw new IllegalArgumentException(x.signature().name() + " is native to " + x.os() + ", not Mac");
        if (x.body().contains("koine_fail")) usesFail = true;
        List<ZshCommand> all = new ArrayList<>(List.of(cmd("emulate", "-L", "zsh")));
        all.addAll(prologue(x.signature(), true));
        all.add(new ZshCommand.Raw(x.body()));
        return new ZshCommand.Function(List.of(x.signature().name()), false, group(all.toArray(ZshCommand[]::new)));
    }

    /**
     * {@code local a=$1 b=$2}: the parameters, unpacked in order. A list comes as its length then its items, so the
     * arguments before it are shifted off first: {@code shift 2; local -a xs; xs=("${@[2,$1+1]}"); shift $(( $1 + 1 ))}.
     * A native function's are {@code local -h}.
     */
    private static List<ZshCommand> prologue(FunctionSig sig, boolean written) {
        List<ZshCommand> out = new ArrayList<>();
        List<Object> local = written ? List.of("local", "-h") : List.of("local");
        List<Object> scalars = new ArrayList<>(local);
        List<Var> ps = sig.parameters();
        for (int i = 0; i < ps.size(); i++) {
            Var v = ps.get(i);
            String n = written ? writtenName(v) : name(v);
            List<Leaf> leaves = v.type() instanceof Type.Record r ? leaves(r) : List.of(new Leaf("", List.of(), v.type()));
            for (int j = 0; j < leaves.size(); j++) {
                Leaf l = leaves.get(j);
                boolean last = i == ps.size() - 1 && j == leaves.size() - 1;
                if (l.type() instanceof Type.MapOf) throw new IllegalArgumentException(sig.name() + ": Zsh cannot pass a map to a function");
                if (l.type() instanceof Type.ListOf) {
                    if (scalars.size() > local.size()) {
                        out.add(cmd(scalars.toArray()));
                        out.add(cmd("shift", Integer.toString(scalars.size() - local.size())));
                        scalars = new ArrayList<>(local);
                    }
                    List<Object> declare = new ArrayList<>(local);
                    declare.add("-a");
                    declare.add(n + l.suffix());
                    out.add(cmd(declare.toArray()));
                    out.add(assigns(new ZshAssignment(n + l.suffix(), Optional.empty(), false,
                            new ZshAssignment.Array(List.of(w(dq(p("@", "2,$1+1"))))))));
                    if (!last) out.add(cmd("shift", w(new ZshWordPart.ArithmeticExpansion("$1 + 1"))));
                } else {
                    scalars.add(w(n + l.suffix() + "=", p(Integer.toString(scalars.size() - local.size() + 1))));
                }
            }
        }
        if (scalars.size() > local.size()) out.add(cmd(scalars.toArray()));
        return out;
    }

    /** The variables a body sets, a record as its fields, not counting those of the functions it defines. */
    private static void assigned(List<Statement> body, Map<String, Type> out) {
        for (Statement s : body)
            switch (s) {
                case Statement.Set x -> declare(x.var(), out);
                case Statement.ForEach x -> {
                    declare(x.item(), out);
                    assigned(x.body(), out);
                }
                case Statement.If x -> {
                    assigned(x.then(), out);
                    assigned(x.otherwise(), out);
                }
                case Statement.While x -> assigned(x.body(), out);
                case Statement.Finally x -> {
                    assigned(x.body(), out);
                    assigned(x.cleanup(), out);
                }
                case Statement.EachLine x -> {
                    declare(x.line(), out);
                    assigned(x.body(), out);
                    declare(x.status(), out);
                }
                default -> {
                }
            }
    }

    private static void declare(Var v, Map<String, Type> out) {
        if (v.type() instanceof Type.Record r) for (Leaf l : leaves(r)) out.putIfAbsent(name(v) + l.suffix(), l.type());
        else out.putIfAbsent(name(v), v.type());
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

    /** A value goes back in {@code REPLY}, {@code reply} or {@code reply_<field>}, a bool as the exit status. */
    private List<ZshCommand> return_(Statement.Return x) {
        // at the script's level, return ends the script successfully
        if (x.value().isEmpty()) return List.of(functionTemps == null ? cmd("exit", "0") : cmd("return"));
        Expr v = x.value().get();
        List<ZshCommand> out = new ArrayList<>(switch (v.type()) {
            case Type.Bool b when v instanceof Expr.BoolLit l -> List.<ZshCommand>of();
            case Type.Bool b -> List.of(check(v));
            case Type.ListOf l -> assign("reply", l, v);
            case Type.Record r -> assignRecord("reply", r, v);
            case Type.MapOf m -> throw new IllegalArgumentException("a function cannot return a map: " + v);
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

    // ---- records ------------------------------------------------------------------------------------------------

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
                        throw new IllegalArgumentException("Zsh cannot tell a missing record apart yet: " + r + "." + f.name());
                    leaves(inner, s, p, out);
                }
                case Type.ListOf l when l.element() instanceof Type.Record || l.element() instanceof Type.ListOf ->
                        throw new IllegalArgumentException("Zsh has no lists of records or lists yet: " + r + "." + f.name());
                case Type.MapOf m -> throw new IllegalArgumentException("Zsh has no maps in records: " + r + "." + f.name());
                default -> out.add(new Leaf(s, p, f.type()));
            }
        }
    }

    /** The name a record's variables start with: its variable's, a field's under it, or a temporary's. */
    private String recordName(Expr e) {
        return switch (e) {
            case Expr.Ref r -> name(r.var());
            case Expr.FieldOf f -> recordName(f.record()) + "_" + f.field();
            default -> {
                Type.Record r = (Type.Record) e.type();
                String t = "koineTemp" + ++temporaries;
                if (functionTemps != null) for (Leaf l : leaves(r)) functionTemps.put(t + l.suffix(), l.type());
                setup.addAll(assignRecord(t, r, e));
                yield t;
            }
        };
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

    /** {@code f a b}: a list as its length then its items, a record as its fields, as the prologue unpacks them. */
    private ZshCommand.Simple call(FunctionSig f, List<Expr> arguments) {
        List<ZshWord> ws = new ArrayList<>(List.of(w(f.name())));
        for (Expr a : arguments)
            switch (a.type()) {
                case Type.ListOf l -> listArgument(a, ws);
                case Type.MapOf m -> throw new IllegalArgumentException(f.name() + ": Zsh cannot pass a map to a function");
                case Type.Record r -> {
                    String n = recordName(a);
                    for (Leaf l : leaves(r)) {
                        if (l.type() instanceof Type.ListOf) {
                            ws.add(w(length(n + l.suffix())));
                            ws.add(w(dq(p(n + l.suffix(), "@"))));
                        } else {
                            ws.add(w(dq(p(n + l.suffix()))));
                        }
                    }
                }
                default -> ws.add(arg(text(a)));
            }
        return new ZshCommand.Simple(List.of(), ws, List.of());
    }

    private void listArgument(Expr a, List<ZshWord> ws) {
        switch (a) {
            case Expr.ListLit l -> {
                ws.add(w(Integer.toString(l.items().size())));
                for (Expr i : l.items()) ws.add(arg(element(i)));
            }
            case Expr.Keys k -> {
                String map = named(k.map());
                ws.add(w(length(map)));
                ws.add(w(dq(param(map).flags("@k").build())));
            }
            default -> {
                String list = named(a);
                ws.add(w(length(list)));
                ws.add(w(dq(p(list, "@"))));
            }
        }
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
            case Expr.FieldOf f -> arith(recordName(f.record()) + "_" + f.field());
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
            case Expr.Matches m -> binary(bareWord(text(m.text())), ZshCondition.BinaryTest.REGEX, literalWord(m.pattern()));
            case Expr.In x -> {
                if (x.container().type().equals(Type.STRING)) {
                    List<ZshWordPart> pattern = new ArrayList<>(List.of(glob("*")));
                    pattern.addAll(patternWord(text(x.item())).parts());
                    pattern.add(glob("*"));
                    yield binary(bareWord(text(x.container())), ZshCondition.BinaryTest.MATCHES, new ZshWord(pattern));
                }
                // (Ie): the index of the item that is exactly this, or 0
                yield arith(ZshPrinter.print(w(param(named(x.container())).subscript("(Ie)$" + named(x.item())).build())));
            }
            case Expr.HasKey h -> arith(ZshPrinter.print(w(param(named(h.map())).prefix(ZshParameter.Prefix.IS_SET)
                    .subscript(subscript(text(h.key()))).build())));
            case Expr.IsEmpty x -> isEmpty(x.value());
            case Expr.Succeeds s -> command(s.command());
            case Expr.Call c -> call(c.function(), c.arguments());
            case Expr.At a -> arith(ZshPrinter.print(new ZshWord(text(a))));
            case Expr.Get g -> arith(ZshPrinter.print(new ZshWord(text(g))));
            default -> throw new IllegalArgumentException("not a bool: " + e);
        };
    }

    private ZshCommand isEmpty(Expr value) {
        return switch (value.type()) {
            case Type.Str s -> unary(ZshCondition.UnaryTest.EMPTY, bareWord(text(value)));
            case Type.Record r -> throw new IllegalArgumentException("Zsh cannot tell a missing record apart yet: " + value);
            default -> arith(ZshPrinter.print(w(length(named(value)))) + " == 0");
        };
    }

    /** Not empty, said the way round Zsh reads best. */
    private ZshCommand notEmpty(Expr value) {
        return switch (value.type()) {
            case Type.Str s -> unary(ZshCondition.UnaryTest.NOT_EMPTY, bareWord(text(value)));
            case Type.Record r -> throw new IllegalArgumentException("Zsh cannot tell a missing record apart yet: " + value);
            default -> arith(ZshPrinter.print(w(length(named(value)))) + " != 0");
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

    // ---- values -------------------------------------------------------------------------------------------------

    /** A string, number, bool or data as word parts that double quotes can hold: literal text and parameters. */
    private List<ZshWordPart> text(Expr e) {
        if (e.type() instanceof Type.ListOf || e.type() instanceof Type.MapOf || e.type() instanceof Type.Record)
            throw new IllegalArgumentException("a list, a map or a record is not text: " + e);
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
            case Expr.At a -> List.of(param(named(a.list())).subscript(a.index() instanceof Expr.IntLit n
                    ? Long.toString(n.value() + 1) : arithmetic(a.index()) + "+1").build());
            case Expr.Get g -> List.of(param(named(g.map())).subscript(subscript(text(g.key()))).build());
            case Expr.FieldOf f -> List.of(p(recordName(f.record()) + "_" + f.field()));
            case Expr.Call c when !c.type().equals(Type.BOOL) -> List.of(p(temporary(c)));
            case Expr.Capture c -> List.of(p(temporary(c)));
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

    /** A list as the words it spreads into: {@code "${xs[@]}"}, the items of a literal, a map's keys. */
    private List<ZshWord> listWords(Expr e) {
        return switch (e) {
            case Expr.ListLit l -> l.items().stream().map(i -> arg(element(i))).toList();
            case Expr.Keys k -> List.of(w(dq(param(named(k.map())).flags("@k").build())));
            default -> List.of(w(dq(p(named(e), "@"))));
        };
    }

    /** An int in arithmetic syntax: a variable by its bare name. */
    private String arithmetic(Expr e) {
        return switch (e) {
            case Expr.IntLit i -> Long.toString(i.value());
            case Expr.Ref r -> name(r.var());
            case Expr.FieldOf f -> recordName(f.record()) + "_" + f.field();
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

    // ---- temporaries --------------------------------------------------------------------------------------------

    /** A variable set, before the command that needs it, to the value of an expression. */
    private String temporary(Expr value) {
        String name = "koineTemp" + ++temporaries;
        if (functionTemps != null) functionTemps.put(name, value.type());
        setup.addAll(assign(name, value.type(), value));
        return name;
    }

    /** A variable that holds the value: the one it is read from, a record's field, or a temporary. */
    private String named(Expr e) {
        if (e instanceof Expr.Ref r) return name(r.var());
        if (e instanceof Expr.FieldOf f) return recordName(f.record()) + "_" + f.field();
        return temporary(e);
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

    /** A native function's parameter, by the name its body uses. */
    private static String writtenName(Var v) {
        if (!v.name().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("not a name Zsh can use: " + v.name());
        return v.name();
    }

    /** {@code SkipBuild} as the option {@code skip-build}. */
    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
