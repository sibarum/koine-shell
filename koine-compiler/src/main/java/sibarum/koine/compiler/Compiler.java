package sibarum.koine.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import sibarum.koine.ir.Command;
import sibarum.koine.ir.Expr;
import sibarum.koine.ir.FunctionSig;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.lang.Checked;
import sibarum.koine.lang.Checker;
import sibarum.koine.lang.KoineException;
import sibarum.koine.lang.Library;
import sibarum.koine.lang.Parser;
import sibarum.koine.lang.Problem;
import sibarum.koine.lang.Resolution;
import sibarum.koine.lang.SourceFile;
import sibarum.koine.lang.StandardLibrary;
import sibarum.koine.lang.Word;

/**
 * Compiles a checked script, for one OS, to the portable IR, as {@code docs/ir.md} maps it:
 * <ul>
 *   <li>Every library member the script uses, directly or through other members, is emitted once: a native one as a
 *       {@link Statement.NativeFunction} with its body for the OS, a Koine one as a {@link Statement.Function}, named
 *       {@code Module_member}. Then the script's own {@code def}s it uses, then its statements.</li>
 *   <li>{@code on} picks the OS's branch. A member that takes a block, {@code within} and {@code step} among them, is
 *       expanded where it is called: an argument that is a literal, a variable, or used once is put in place of its
 *       parameter, any other is set in a variable first, and the member's own variables get names of their own.</li>
 *   <li>A block that ends with {@code finally} is a {@link Statement.Finally} of what comes before it.</li>
 *   <li>{@code for line in} a command is an {@link Statement.EachLine} followed by a {@link Statement.Fail} if the
 *       command failed; {@code return} at the script's level is {@link Statement.Exit} with 0.</li>
 *   <li>The comment above the script line is its help: its first paragraph the synopsis, the rest the description.
 *       Other comments above a statement are carried along, but for those above a {@code def} or a record.</li>
 * </ul>
 * Names and types are the checker's: the compiler asks {@link Checked} for them, and never works one out again.
 */
public final class Compiler {

    private final Checked checked;
    private final OS os;
    private final Checker.OS target;
    /** Every member emitted, with its signature, in the order each was first used. */
    private final Map<Checker.Member, FunctionSig> functions = new IdentityHashMap<>();
    private final List<Statement> natives = new ArrayList<>();
    private final List<Statement> moduleFunctions = new ArrayList<>();
    private final List<Statement> scriptFunctions = new ArrayList<>();
    private final Map<sibarum.koine.lang.Type.Rec, Type.Record> records = new IdentityHashMap<>();

    private Compiler(Checked checked, OS os) {
        this.checked = checked;
        this.os = os;
        this.target = Checker.OS.valueOf(os.name());
    }

    /** Parses, checks and compiles a script, with the standard library; {@code name} is its file's, without the extension. */
    public static Script compile(String name, String text, OS os) {
        return compile(name, text, StandardLibrary.INSTANCE, os);
    }

    public static Script compile(String name, String text, Library library, OS os) {
        String source = name + ".koine";
        SourceFile file;
        try {
            file = Parser.parse(source, text);
        } catch (KoineException e) {
            throw new CompileException(List.of(new Problem(e.source(), e.pos(), e.problem())));
        }
        if (!(file instanceof SourceFile.Script script)) throw new IllegalArgumentException(source + " is a module, not a script");
        Checked checked = Checker.analyse(source, script, library);
        if (!checked.problems().isEmpty()) throw new CompileException(checked.problems());
        Checker.Unit unit = checked.script().orElseThrow();
        if (!unit.targets().contains(Checker.OS.valueOf(os.name())))
            throw new IllegalArgumentException(source + " is not for " + os + ": it is for " + unit.targets());
        return new Compiler(checked, os).script(name, script);
    }

    // ---- the script ---------------------------------------------------------------------------------------------

    private Script script(String name, SourceFile.Script s) {
        Fn fn = new Fn();
        List<sibarum.koine.lang.Statement> story = new ArrayList<>();
        for (sibarum.koine.lang.Statement st : s.body())
            if (!(st instanceof sibarum.koine.lang.Statement.Def) && !(st instanceof sibarum.koine.lang.Statement.Record)) story.add(st);
        for (sibarum.koine.lang.Statement.Param p : s.inputs()) fn.taken.add(p.name());
        names(story, fn.taken);
        Frame frame = new Frame(null, fn, false, null, true);

        List<Script.Param> params = new ArrayList<>();
        for (sibarum.koine.lang.Statement.Param p : s.inputs()) {
            Var v = new Var(p.name(), type(checked.declared(p)));
            frame.names.put(p.name(), new Binding.Value(new Expr.Ref(v)));
            Optional<Expr> defaultValue = p.defaultValue().map(d -> expr(d, frame));
            // a flag is false unless given, which a default of false says again
            if (v.type().equals(Type.BOOL) && defaultValue.filter(d -> d.equals(new Expr.BoolLit(false))).isPresent())
                defaultValue = Optional.empty();
            params.add(new Script.Param(v, defaultValue, ""));
        }
        List<Statement> main = block(s.body(), frame);

        List<Statement> body = new ArrayList<>(natives);
        body.addAll(moduleFunctions);
        body.addAll(scriptFunctions);
        body.addAll(main);
        String[] paragraphs = s.doc().split("\n\n", 2);
        String synopsis = String.join(" ", paragraphs[0].lines().toList());
        String description = paragraphs.length > 1 ? paragraphs[1].strip() : "";
        return new Script(name, synopsis, description, params, body);
    }

    // ---- where a statement is -----------------------------------------------------------------------------------

    /** What a name stands for: a value, or a block given to the member being expanded. */
    private sealed interface Binding {

        record Value(Expr expr) implements Binding {
        }

        record Block(List<sibarum.koine.lang.Statement> body, Frame frame) implements Binding {
        }
    }

    /** One IR function, or the script's story: the names it must not give a variable of its own, and those in use. */
    private static final class Fn {
        final Set<String> taken = new HashSet<>();
        final Set<String> active = new HashSet<>();
        final Map<String, Type> types = new HashMap<>();
    }

    /**
     * A scope: its names, its function, whether it is an expansion (whose variables are renamed), the names that
     * expansion took, and whether {@code return} ends the script.
     */
    private static final class Frame {
        final Frame parent;
        final Fn fn;
        final boolean expansion;
        final List<String> fresh;
        final boolean story;
        final Map<String, Binding> names = new HashMap<>();

        Frame(Frame parent, Fn fn, boolean expansion, List<String> fresh, boolean story) {
            this.parent = parent;
            this.fn = fn;
            this.expansion = expansion;
            this.fresh = fresh;
            this.story = story;
        }

        Frame child() {
            return new Frame(this, fn, expansion, fresh, story);
        }

        /** What the name stands for here; null for one that is not a variable, such as a member's. */
        Binding find(String name) {
            for (Frame f = this; f != null; f = f.parent) {
                Binding b = f.names.get(name);
                if (b != null) return b;
            }
            return null;
        }
    }

    /** A variable for a {@code let} or a {@code for}: its own name, unless the function has it with another type. */
    private Var declare(Frame f, String name, Type type) {
        String n = f.expansion ? fresh(f, name) : name;
        Type before = f.fn.types.get(n);
        if (before != null && !before.equals(type)) n = fresh(f, name);
        f.fn.types.put(n, type);
        Var v = new Var(n, type);
        f.names.put(name, new Binding.Value(new Expr.Ref(v)));
        return v;
    }

    /** A name no variable of the function's own has, and no expansion now running uses: {@code outer}, {@code outer2}. */
    private static String fresh(Frame f, String base) {
        String n = base;
        for (int i = 2; f.fn.taken.contains(n) || f.fn.active.contains(n); i++) n = base + i;
        f.fn.active.add(n);
        if (f.fresh != null) f.fresh.add(n);
        return n;
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    private List<Statement> block(List<sibarum.koine.lang.Statement> statements, Frame f) {
        List<Statement> out = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            sibarum.koine.lang.Statement s = statements.get(i);
            if (s instanceof sibarum.koine.lang.Statement.Finally fin) {
                List<Statement> body = new ArrayList<>(out);
                out.clear();
                out.add(new Statement.Finally(body, block(fin.body(), f.child())));
                continue;
            }
            if (s instanceof sibarum.koine.lang.Statement.Comment c) {
                sibarum.koine.lang.Statement next = i + 1 < statements.size() ? statements.get(i + 1) : null;
                // a def's or a record's comment says what it is, and they are not statements here
                if (!(next instanceof sibarum.koine.lang.Statement.Def) && !(next instanceof sibarum.koine.lang.Statement.Record))
                    out.add(new Statement.Comment(c.text()));
                continue;
            }
            out.addAll(statement(s, f));
        }
        return out;
    }

    private List<Statement> statement(sibarum.koine.lang.Statement statement, Frame f) {
        return switch (statement) {
            case sibarum.koine.lang.Statement.Let s -> {
                Expr value = expr(s.value(), f);
                yield List.of(new Statement.Set(declare(f, s.name(), type(checked.declared(s))), value));
            }
            case sibarum.koine.lang.Statement.Assign s -> List.of(assign(s, f));
            case sibarum.koine.lang.Statement.If s -> List.of(ifStatement(s.branches(), 0, s.otherwise(), f));
            case sibarum.koine.lang.Statement.For s -> forStatement(s, f);
            case sibarum.koine.lang.Statement.While s -> List.of(new Statement.While(expr(s.condition(), f), block(s.body(), f.child())));
            case sibarum.koine.lang.Statement.Within s ->
                    expand(member("Dir", "runIn"), List.of(new Arg.Koine(s.directory())), new Binding.Block(s.body(), f), f);
            case sibarum.koine.lang.Statement.Step s ->
                    expand(member("Script", "runStep"), List.of(new Arg.Ir(new Expr.StringLit(s.name()))), new Binding.Block(s.body(), f), f);
            case sibarum.koine.lang.Statement.Try s -> List.of(new Statement.Run(command(s.command(), f), Statement.OnFailure.IGNORE));
            case sibarum.koine.lang.Statement.Fail s -> List.of(new Statement.Fail(expr(s.message(), f)));
            case sibarum.koine.lang.Statement.Return s -> {
                if (f.story) yield List.of(new Statement.Exit(new Expr.IntLit(0)));
                yield List.of(new Statement.Return(s.value().map(v -> expr(v, f))));
            }
            case sibarum.koine.lang.Statement.Print s -> List.of(new Statement.Print(expr(s.message(), f)));
            case sibarum.koine.lang.Statement.Run s -> run(s, f);
            case sibarum.koine.lang.Statement.Def s -> List.of();
            case sibarum.koine.lang.Statement.Record s -> List.of();
            case sibarum.koine.lang.Statement.Comment s -> List.of(new Statement.Comment(s.text()));
            case sibarum.koine.lang.Statement.Finally s -> throw new IllegalStateException("a finally is handled by its block");
            case sibarum.koine.lang.Statement.On s -> throw new IllegalStateException("on is only a def's whole body");
            case sibarum.koine.lang.Statement.Native s -> throw new IllegalStateException("native is only a def's whole body");
        };
    }

    /** {@code if}, its {@code else if}s as an if in each else, and the last else. */
    private Statement ifStatement(List<sibarum.koine.lang.Statement.If.Branch> branches, int i,
                                  List<sibarum.koine.lang.Statement> otherwise, Frame f) {
        // in the order they are written, so that the library members come out in the order they are first used
        sibarum.koine.lang.Statement.If.Branch b = branches.get(i);
        Expr condition = expr(b.condition(), f);
        List<Statement> then = block(b.body(), f.child());
        List<Statement> rest = i + 1 < branches.size() ? List.of(ifStatement(branches, i + 1, otherwise, f))
                : block(otherwise, f.child());
        return new Statement.If(condition, then, rest);
    }

    private Statement assign(sibarum.koine.lang.Statement.Assign s, Frame f) {
        Expr value = expr(s.value(), f);
        return switch (s.target()) {
            case sibarum.koine.lang.Expr.Name n -> new Statement.Set(variable(n, f), value);
            case sibarum.koine.lang.Expr.Index ix -> {
                if (!(ix.target() instanceof sibarum.koine.lang.Expr.Name n) || !(checked.type(ix.target()) instanceof sibarum.koine.lang.Type.MapOf))
                    throw unsupported(s, "only an entry of a map variable can be assigned, map[key] = value");
                yield new Statement.Put(variable(n, f), expr(ix.index(), f), value);
            }
            default -> throw new IllegalStateException("the parser allows only a name or an index");
        };
    }

    private List<Statement> forStatement(sibarum.koine.lang.Statement.For s, Frame f) {
        Frame body = f.child();
        if (s.source() instanceof sibarum.koine.lang.Expr.Command c) {
            Command command = command(c, f);
            Var line = declare(body, s.item(), Type.STRING);
            String n = fresh(f, "status");
            Var status = new Var(n, Type.INT);
            List<Statement> out = List.of(
                    new Statement.EachLine(command, line, block(s.body(), body), status),
                    new Statement.If(new Expr.Compare(new Expr.Ref(status), Expr.Comparison.NE, new Expr.IntLit(0)),
                            List.of(new Statement.Fail(new Expr.Interp(List.of(command.program(),
                                    new Expr.StringLit(" exited with "), new Expr.Ref(status))))), List.of()));
            f.fn.active.remove(n);
            return out;
        }
        Expr source = expr(s.source(), f);
        Var item = declare(body, s.item(), type(checked.declared(s)));
        return List.of(new Statement.ForEach(item, source, block(s.body(), body)));
    }

    /** A statement that does something: a command, a call, a block-taking member with its block, running a block. */
    private List<Statement> run(sibarum.koine.lang.Statement.Run s, Frame f) {
        sibarum.koine.lang.Expr e = s.expr();
        if (e instanceof sibarum.koine.lang.Expr.Command c) return List.of(new Statement.Run(command(c, f), Statement.OnFailure.ABORT));
        if (e instanceof sibarum.koine.lang.Expr.Name n && f.find(n.name()) instanceof Binding.Block b)
            return block(b.body(), b.frame().child());
        Optional<Binding.Block> given = s.block().isEmpty() ? Optional.empty() : Optional.of(new Binding.Block(s.block(), f));
        Resolution r = checked.resolution(e);
        if (r instanceof Resolution.Builtin b && b.name().equals("add")) {
            sibarum.koine.lang.Expr.Call call = (sibarum.koine.lang.Expr.Call) e;
            sibarum.koine.lang.Expr.Member m = (sibarum.koine.lang.Expr.Member) call.callee();
            if (!(m.target() instanceof sibarum.koine.lang.Expr.Name n)) throw unsupported(s, "only a list variable can be added to");
            return List.of(new Statement.Append(variable(n, f), expr(call.arguments().getFirst().value(), f)));
        }
        if (!(r instanceof Resolution.Call rc)) throw unsupported(s, "this does nothing");
        List<Arg> args = arguments(e, rc);
        if (rc.member().takesBlock()) {
            Binding.Block block = given.orElseGet(() -> blockArgument(args, f));
            return expand(rc.member(), args.subList(0, rc.member().params().size() - 1), block, f);
        }
        FunctionSig sig = function(rc.member());
        return List.of(new Statement.Invoke(sig, irArguments(rc.member(), args, f)));
    }

    /** A block passed on to a member that takes one: the Block parameter the caller has. */
    private static Binding.Block blockArgument(List<Arg> args, Frame f) {
        if (args.getLast() instanceof Arg.Koine k && k.expr() instanceof sibarum.koine.lang.Expr.Name n && f.find(n.name()) instanceof Binding.Block b)
            return b;
        throw new IllegalStateException("a member that takes a block is given one");
    }

    private Var variable(sibarum.koine.lang.Expr.Name n, Frame f) {
        if (f.find(n.name()) instanceof Binding.Value v && v.expr() instanceof Expr.Ref r) return r.var();
        throw new IllegalStateException(n.name() + " is not a variable here");
    }

    // ---- members ------------------------------------------------------------------------------------------------

    /** An argument as written, or one the compiler made, such as a step's name. */
    private sealed interface Arg {

        record Koine(sibarum.koine.lang.Expr expr) implements Arg {
        }

        record Ir(Expr expr) implements Arg {
        }
    }

    /** A call's arguments in order: the value before the dot first, when the member was looked up by its type. */
    private static List<Arg> arguments(sibarum.koine.lang.Expr e, Resolution.Call rc) {
        List<Arg> args = new ArrayList<>();
        switch (e) {
            case sibarum.koine.lang.Expr.Call call -> {
                if (rc.receiver()) args.add(new Arg.Koine(((sibarum.koine.lang.Expr.Member) call.callee()).target()));
                for (sibarum.koine.lang.Expr.Argument a : call.arguments()) args.add(new Arg.Koine(a.value()));
            }
            case sibarum.koine.lang.Expr.Member m -> {
                if (rc.receiver()) args.add(new Arg.Koine(m.target()));
            }
            default -> {
            }
        }
        return args;
    }

    /** The IR arguments of a call: a repeated parameter's arguments gathered into one list. */
    private List<Expr> irArguments(Checker.Member m, List<Arg> args, Frame f) {
        List<Expr> out = new ArrayList<>();
        int fixed = m.repeated() ? m.params().size() - 1 : m.params().size();
        for (int i = 0; i < fixed; i++) out.add(argument(args.get(i), f));
        if (m.repeated()) {
            List<Expr> items = new ArrayList<>();
            for (int i = fixed; i < args.size(); i++) items.add(argument(args.get(i), f));
            out.add(new Expr.ListLit(type(m.params().getLast()), items));
        }
        return out;
    }

    private Expr argument(Arg a, Frame f) {
        return switch (a) {
            case Arg.Koine k -> expr(k.expr(), f);
            case Arg.Ir i -> i.expr();
        };
    }

    /** The member's function, emitted the first time it is used, after the members it uses itself. */
    private FunctionSig function(Checker.Member m) {
        FunctionSig known = functions.get(m);
        if (known != null) return known;
        List<Var> params = new ArrayList<>();
        List<sibarum.koine.lang.Statement.Param> ps = m.def().parameters();
        for (int i = 0; i < ps.size(); i++) {
            Type t = type(m.params().get(i));
            params.add(new Var(ps.get(i).name(), ps.get(i).repeated() ? Type.listOf(t) : t));
        }
        sibarum.koine.lang.Type result = m.result();
        Optional<Type> returns = result == sibarum.koine.lang.Type.Prim.NOTHING ? Optional.empty() : Optional.of(type(result));
        String name = m.unit().module() == null ? m.def().name() : m.unit().module() + "_" + m.def().name();
        FunctionSig sig = new FunctionSig(name, params, returns);
        functions.put(m, sig);

        for (sibarum.koine.lang.Statement s : m.def().body())
            if (s instanceof sibarum.koine.lang.Statement.Native n && n.systems().contains(target.name())) {
                natives.add(new Statement.NativeFunction(sig, os, n.body()));
                return sig;
            }
        Fn fn = new Fn();
        for (sibarum.koine.lang.Statement.Param p : ps) fn.taken.add(p.name());
        List<sibarum.koine.lang.Statement> body = body(m);
        names(body, fn.taken);
        Frame frame = new Frame(null, fn, false, null, false);
        for (int i = 0; i < ps.size(); i++) frame.names.put(ps.get(i).name(), new Binding.Value(new Expr.Ref(params.get(i))));
        Statement.Function function = new Statement.Function(sig, block(body, frame));
        (m.unit().module() == null ? scriptFunctions : moduleFunctions).add(function);
        return sig;
    }

    /** A Koine body: the def's own, or its {@code on} branch for the OS. */
    private List<sibarum.koine.lang.Statement> body(Checker.Member m) {
        List<sibarum.koine.lang.Statement> body = m.def().body();
        if (body.stream().noneMatch(s -> s instanceof sibarum.koine.lang.Statement.On)) return body;
        for (sibarum.koine.lang.Statement s : body)
            if (s instanceof sibarum.koine.lang.Statement.On on && on.systems().contains(target.name())) return on.body();
        throw new IllegalStateException(m.display() + " has no body for " + os);
    }

    /**
     * A member that takes a block, written out where it is called. An argument that is a literal, a variable, or
     * used once takes its parameter's place; any other is set in a variable of its own first.
     */
    private List<Statement> expand(Checker.Member m, List<Arg> args, Binding.Block block, Frame caller) {
        List<String> fresh = new ArrayList<>();
        Frame frame = new Frame(null, caller.fn, true, fresh, caller.story);
        List<Statement> out = new ArrayList<>();
        List<sibarum.koine.lang.Statement> body = body(m);
        List<sibarum.koine.lang.Statement.Param> ps = m.def().parameters();
        for (int i = 0; i < ps.size() - 1; i++) {
            String p = ps.get(i).name();
            Expr value = argument(args.get(i), caller);
            boolean inPlace = value instanceof Expr.StringLit || value instanceof Expr.IntLit || value instanceof Expr.BoolLit
                    || value instanceof Expr.Ref || uses(body, p) <= 1;
            if (inPlace) {
                frame.names.put(p, new Binding.Value(value));
            } else {
                Var v = new Var(fresh(frame, p), value.type());
                out.add(new Statement.Set(v, value));
                frame.names.put(p, new Binding.Value(new Expr.Ref(v)));
            }
        }
        frame.names.put(ps.getLast().name(), block);
        out.addAll(block(body, frame));
        caller.fn.active.removeAll(fresh);
        return out;
    }

    private Checker.Member member(String module, String name) {
        Checker.Unit unit = checked.module(module).orElseThrow(() -> new IllegalStateException("no module " + module));
        Checker.Member m = unit.members().get(name);
        if (m == null) throw new IllegalStateException(module + " has no member " + name);
        return m;
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    private Expr expr(sibarum.koine.lang.Expr e, Frame f) {
        return switch (e) {
            case sibarum.koine.lang.Expr.NumberLit x -> new Expr.IntLit(x.value());
            case sibarum.koine.lang.Expr.BoolLit x -> new Expr.BoolLit(x.value());
            case sibarum.koine.lang.Expr.Str x -> string(x, f);
            case sibarum.koine.lang.Expr.ListLit x -> {
                Type element = ((Type.ListOf) type(checked.type(x))).element();
                yield new Expr.ListLit(element, x.items().stream().map(i -> expr(i, f)).toList());
            }
            case sibarum.koine.lang.Expr.MapLit x -> {
                if (!x.entries().isEmpty()) throw unsupported(x, "a map is made empty, {}, and filled with map[key] = value");
                yield new Expr.EmptyMap(((Type.MapOf) type(checked.type(x))).value());
            }
            case sibarum.koine.lang.Expr.Index x -> checked.type(x.target()) instanceof sibarum.koine.lang.Type.MapOf
                    ? new Expr.Get(expr(x.target(), f), expr(x.index(), f))
                    : new Expr.At(expr(x.target(), f), expr(x.index(), f));
            case sibarum.koine.lang.Expr.Not x -> new Expr.Not(expr(x.operand(), f));
            case sibarum.koine.lang.Expr.Binary x -> binary(x, f);
            case sibarum.koine.lang.Expr.As x -> new Expr.ReadAs(expr(x.value(), f), (Type.Record) type(checked.type(x)));
            case sibarum.koine.lang.Expr.Command x -> new Expr.Capture(command(x, f), new Expr.Fallback.Stop());
            case sibarum.koine.lang.Expr.Try x -> new Expr.Succeeds(command(x.command(), f));
            case sibarum.koine.lang.Expr.OrElse x -> new Expr.Capture(command(x.command(), f),
                    x.fallback() instanceof sibarum.koine.lang.Expr.FailWith fail ? new Expr.Fallback.Fail(expr(fail.message(), f))
                            : new Expr.Fallback.Value(expr(x.fallback(), f)));
            case sibarum.koine.lang.Expr.FailWith x -> throw new IllegalStateException("fail is a value only after else");
            case sibarum.koine.lang.Expr.Name x -> reference(x, f);
            case sibarum.koine.lang.Expr.Member x -> reference(x, f);
            case sibarum.koine.lang.Expr.Call x -> reference(x, f);
        };
    }

    /** A name, a member or a call, as the checker resolved it. */
    private Expr reference(sibarum.koine.lang.Expr e, Frame f) {
        Resolution r = checked.resolution(e);
        return switch (r) {
            case Resolution.Local l -> {
                if (f.find(l.name()) instanceof Binding.Value v) yield v.expr();
                throw new IllegalStateException("a block is not a value: " + l.name());
            }
            case Resolution.Field x -> new Expr.FieldOf(expr(((sibarum.koine.lang.Expr.Member) e).target(), f), x.name());
            case Resolution.Call c -> {
                if (c.member().takesBlock()) throw unsupported(e, "a member that takes a block is a statement");
                yield new Expr.Call(function(c.member()), irArguments(c.member(), arguments(e, c), f));
            }
            case Resolution.Construct c -> construct((sibarum.koine.lang.Expr.Call) e, c.record(), f);
            case Resolution.Builtin b -> builtin(e, b.name(), f);
        };
    }

    private Expr builtin(sibarum.koine.lang.Expr e, String name, Frame f) {
        sibarum.koine.lang.Expr.Member m = e instanceof sibarum.koine.lang.Expr.Call c
                ? (sibarum.koine.lang.Expr.Member) c.callee() : (sibarum.koine.lang.Expr.Member) e;
        List<sibarum.koine.lang.Expr.Argument> args = e instanceof sibarum.koine.lang.Expr.Call c ? c.arguments() : List.of();
        Expr target = expr(m.target(), f);
        return switch (name) {
            case "isEmpty" -> new Expr.IsEmpty(target);
            case "keys" -> new Expr.Keys(target);
            case "has" -> new Expr.HasKey(target, expr(args.getFirst().value(), f));
            case "matches" -> {
                sibarum.koine.lang.Expr.Str pattern = (sibarum.koine.lang.Expr.Str) args.getFirst().value();
                StringBuilder text = new StringBuilder();
                for (sibarum.koine.lang.Expr.Str.Part p : pattern.parts()) text.append(((sibarum.koine.lang.Expr.Str.Text) p).text());
                yield new Expr.Matches(target, text.toString());
            }
            default -> throw unsupported(e, name + " is a statement");
        };
    }

    /** {@code Binary(name: …)}: a value for every field, in the record's order; a missing optional one empty. */
    private Expr construct(sibarum.koine.lang.Expr.Call call, sibarum.koine.lang.Type.Rec rec, Frame f) {
        Type.Record record = (Type.Record) type(rec);
        Map<String, Expr> given = new HashMap<>();
        for (sibarum.koine.lang.Expr.Argument a : call.arguments()) given.put(a.name().orElseThrow(), expr(a.value(), f));
        List<Expr> values = new ArrayList<>();
        for (Type.Record.Field field : record.fields()) {
            Expr v = given.get(field.name());
            values.add(v != null ? v : empty(field.type(), call));
        }
        return new Expr.MakeRecord(record, values);
    }

    private Expr empty(Type t, sibarum.koine.lang.Expr at) {
        if (t.equals(Type.STRING)) return new Expr.StringLit("");
        if (t.equals(Type.INT)) return new Expr.IntLit(0);
        if (t.equals(Type.BOOL)) return new Expr.BoolLit(false);
        if (t instanceof Type.ListOf l) return new Expr.ListLit(l.element(), List.of());
        throw unsupported(at, "a record leaves out only a string, a number, a bool or a list, not " + t);
    }

    private Expr binary(sibarum.koine.lang.Expr.Binary x, Frame f) {
        Expr l = expr(x.left(), f), r = expr(x.right(), f);
        return switch (x.op()) {
            case AND -> new Expr.And(l, r);
            case OR -> new Expr.Or(l, r);
            case IN -> new Expr.In(l, r);
            case EQ, NE, LT, LE, GT, GE -> {
                if (l.type().equals(Type.BOOL)) throw unsupported(x, "bools are not compared; use the bool, or not");
                yield new Expr.Compare(l, switch (x.op()) {
                    case EQ -> Expr.Comparison.EQ;
                    case NE -> Expr.Comparison.NE;
                    case LT -> Expr.Comparison.LT;
                    case LE -> Expr.Comparison.LE;
                    case GT -> Expr.Comparison.GT;
                    default -> Expr.Comparison.GE;
                }, r);
            }
        };
    }

    /** Plain text as a string; with values inserted, its parts one after another. */
    private Expr string(sibarum.koine.lang.Expr.Str s, Frame f) {
        List<Expr> parts = new ArrayList<>();
        for (sibarum.koine.lang.Expr.Str.Part p : s.parts())
            parts.add(switch (p) {
                case sibarum.koine.lang.Expr.Str.Text t -> new Expr.StringLit(t.text());
                case sibarum.koine.lang.Expr.Str.Insert i -> expr(i.value(), f);
            });
        return parts.size() == 1 && parts.getFirst() instanceof Expr.StringLit only ? only : new Expr.Interp(parts);
    }

    /** A literal command: its first word the program, each word one argument, a list inserted alone many. */
    private Command command(sibarum.koine.lang.Expr.Command c, Frame f) {
        List<Expr> words = new ArrayList<>();
        for (Word w : c.words()) {
            List<Expr> parts = new ArrayList<>();
            for (Word.Part p : w.parts())
                parts.add(switch (p) {
                    case Word.Literal l -> new Expr.StringLit(l.text());
                    case Word.Quoted q -> new Expr.StringLit(q.text());
                    case Word.Insert i -> expr(i.value(), f);
                });
            words.add(parts.size() == 1 ? parts.getFirst() : new Expr.Interp(parts));
        }
        return new Command(words.getFirst(), words.subList(1, words.size()), false);
    }

    // ---- types --------------------------------------------------------------------------------------------------

    private Type type(sibarum.koine.lang.Type t) {
        return switch (t) {
            case sibarum.koine.lang.Type.Prim p -> switch (p) {
                case STRING -> Type.STRING;
                case NUMBER -> Type.INT;
                case BOOL -> Type.BOOL;
                case DATA -> Type.DATA;
                default -> throw new IllegalStateException("not a value's type: " + p.displayName());
            };
            case sibarum.koine.lang.Type.ListOf l -> Type.listOf(type(l.element()));
            case sibarum.koine.lang.Type.MapOf m -> {
                if (sibarum.koine.lang.Type.prune(m.key()) != sibarum.koine.lang.Type.Prim.STRING)
                    throw new IllegalStateException("a map's keys are strings");
                yield Type.mapOf(type(m.value()));
            }
            case sibarum.koine.lang.Type.Rec r -> records.computeIfAbsent(r, x -> {
                List<Type.Record.Field> fields = new ArrayList<>();
                r.fields().forEach((name, field) -> fields.add(new Type.Record.Field(name, type(field.type()), field.optional())));
                return new Type.Record(r.name(), fields);
            });
            case sibarum.koine.lang.Type.Var v -> {
                sibarum.koine.lang.Type known = sibarum.koine.lang.Type.prune(v);
                if (known instanceof sibarum.koine.lang.Type.Var) throw new IllegalStateException("a type is never known");
                yield type(known);
            }
        };
    }

    // ---- names --------------------------------------------------------------------------------------------------

    /** Every name a body introduces, at any depth: its variables, which an expansion's own must not take. */
    private static void names(List<sibarum.koine.lang.Statement> body, Set<String> out) {
        for (sibarum.koine.lang.Statement s : body)
            switch (s) {
                case sibarum.koine.lang.Statement.Let x -> out.add(x.name());
                case sibarum.koine.lang.Statement.For x -> {
                    out.add(x.item());
                    names(x.body(), out);
                }
                case sibarum.koine.lang.Statement.If x -> {
                    for (sibarum.koine.lang.Statement.If.Branch b : x.branches()) names(b.body(), out);
                    names(x.otherwise(), out);
                }
                case sibarum.koine.lang.Statement.While x -> names(x.body(), out);
                case sibarum.koine.lang.Statement.Within x -> names(x.body(), out);
                case sibarum.koine.lang.Statement.Step x -> names(x.body(), out);
                case sibarum.koine.lang.Statement.Finally x -> names(x.body(), out);
                case sibarum.koine.lang.Statement.Run x -> names(x.block(), out);
                default -> {
                }
            }
    }

    /** How many times a body reads a name. */
    private static int uses(List<sibarum.koine.lang.Statement> body, String name) {
        int n = 0;
        for (sibarum.koine.lang.Statement s : body) n += uses(s, name);
        return n;
    }

    private static int uses(sibarum.koine.lang.Statement s, String name) {
        return switch (s) {
            case sibarum.koine.lang.Statement.Let x -> uses(x.value(), name);
            case sibarum.koine.lang.Statement.Assign x -> uses(x.target(), name) + uses(x.value(), name);
            case sibarum.koine.lang.Statement.If x -> {
                int n = uses(x.otherwise(), name);
                for (sibarum.koine.lang.Statement.If.Branch b : x.branches()) n += uses(b.condition(), name) + uses(b.body(), name);
                yield n;
            }
            case sibarum.koine.lang.Statement.For x -> uses(x.source(), name) + uses(x.body(), name);
            case sibarum.koine.lang.Statement.While x -> uses(x.condition(), name) + uses(x.body(), name);
            case sibarum.koine.lang.Statement.Within x -> uses(x.directory(), name) + uses(x.body(), name);
            case sibarum.koine.lang.Statement.Step x -> uses(x.body(), name);
            case sibarum.koine.lang.Statement.Try x -> uses(x.command(), name);
            case sibarum.koine.lang.Statement.Fail x -> uses(x.message(), name);
            case sibarum.koine.lang.Statement.Return x -> x.value().map(v -> uses(v, name)).orElse(0);
            case sibarum.koine.lang.Statement.Print x -> uses(x.message(), name);
            case sibarum.koine.lang.Statement.Run x -> uses(x.expr(), name) + uses(x.block(), name);
            case sibarum.koine.lang.Statement.Finally x -> uses(x.body(), name);
            case sibarum.koine.lang.Statement.On x -> uses(x.body(), name);
            default -> 0;
        };
    }

    private static int uses(sibarum.koine.lang.Expr e, String name) {
        return switch (e) {
            case sibarum.koine.lang.Expr.Name x -> x.name().equals(name) ? 1 : 0;
            case sibarum.koine.lang.Expr.Str x -> x.parts().stream()
                    .mapToInt(p -> p instanceof sibarum.koine.lang.Expr.Str.Insert i ? uses(i.value(), name) : 0).sum();
            case sibarum.koine.lang.Expr.ListLit x -> x.items().stream().mapToInt(i -> uses(i, name)).sum();
            case sibarum.koine.lang.Expr.MapLit x -> x.entries().stream().mapToInt(i -> uses(i.key(), name) + uses(i.value(), name)).sum();
            case sibarum.koine.lang.Expr.Member x -> uses(x.target(), name);
            case sibarum.koine.lang.Expr.Call x -> uses(x.callee(), name) + x.arguments().stream().mapToInt(a -> uses(a.value(), name)).sum();
            case sibarum.koine.lang.Expr.Index x -> uses(x.target(), name) + uses(x.index(), name);
            case sibarum.koine.lang.Expr.Not x -> uses(x.operand(), name);
            case sibarum.koine.lang.Expr.Binary x -> uses(x.left(), name) + uses(x.right(), name);
            case sibarum.koine.lang.Expr.As x -> uses(x.value(), name);
            case sibarum.koine.lang.Expr.Command x -> x.words().stream().flatMap(w -> w.parts().stream())
                    .mapToInt(p -> p instanceof Word.Insert i ? uses(i.value(), name) : 0).sum();
            case sibarum.koine.lang.Expr.Try x -> uses(x.command(), name);
            case sibarum.koine.lang.Expr.OrElse x -> uses(x.command(), name) + uses(x.fallback(), name);
            case sibarum.koine.lang.Expr.FailWith x -> uses(x.message(), name);
            default -> 0;
        };
    }

    private static UnsupportedOperationException unsupported(Object at, String why) {
        return new UnsupportedOperationException(why + ": " + at);
    }

    /** A script that does not check, with its problems. */
    public static final class CompileException extends RuntimeException {
        private final List<Problem> problems;

        public CompileException(List<Problem> problems) {
            super(String.join("\n", problems.stream().map(Problem::toString).toList()));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() {
            return problems;
        }
    }
}
