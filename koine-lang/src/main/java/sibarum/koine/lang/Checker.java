package sibarum.koine.lang;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import sibarum.koine.lang.Type.Prim;

/**
 * Checks a script or a module, and every module it names, against the rules in {@code docs/language.md}: names, types,
 * what may appear where, that every OS is covered, and tiers. It reports every problem it finds rather than stopping at
 * the first, and a value already found wrong has the type {@link Prim#ERROR}, so one mistake is reported once.
 */
public final class Checker {

    enum OS {Windows, Linux, Mac}

    private static final Set<OS> ALL = EnumSet.allOf(OS.class);

    private final Library library;
    private final List<Problem> problems = new ArrayList<>();
    /** Modules by name, as loaded; null for one that could not be. */
    private final Map<String, Unit> modules = new HashMap<>();
    /** For each member that takes a block, the members that take one it calls. */
    private final Map<Member, Set<Member>> blockCalls = new LinkedHashMap<>();

    private Checker(Library library) {
        this.library = library;
    }

    /** Parses and checks one file. A file that does not parse gives one problem, the parser's. */
    public static List<Problem> check(String source, String text, Library library) {
        SourceFile file;
        try {
            file = Parser.parse(source, text);
        } catch (KoineException e) {
            return List.of(new Problem(e.source(), e.pos(), e.problem()));
        }
        return check(source, file, library);
    }

    public static List<Problem> check(String source, SourceFile file, Library library) {
        Checker c = new Checker(library);
        switch (file) {
            case SourceFile.Script s -> c.script(source, s);
            case SourceFile.Module m -> {
                Unit u = c.unit(source, m);
                c.modules.put(m.name(), u);
                c.members(u);
            }
        }
        c.blockCycles();
        return List.copyOf(c.problems);
    }

    // ---- files --------------------------------------------------------------------------------------------------

    /** A script or a module: its own functions and records, and the OSes it is compiled for. */
    private static final class Unit {
        final String source;
        /** Null for a script. */
        final String module;
        final Set<OS> targets;
        final boolean bootstrap;
        final boolean bundled;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, Type.Rec> records = new LinkedHashMap<>();

        Unit(String source, String module, Set<OS> targets, boolean bootstrap, boolean bundled) {
            this.source = source;
            this.module = module;
            this.targets = targets;
            this.bootstrap = bootstrap;
            this.bundled = bundled;
        }
    }

    /** A {@code def}, in a script or a module. */
    private static final class Member {
        final Unit unit;
        final Statement.Def def;
        final List<Type> params = new ArrayList<>();
        /** {@link Prim#NOTHING} for none; a {@link Type.Var} for a script's {@code def} that leaves it out. */
        Type result;
        boolean resultDeclared;
        final Set<OS> bundledOn = EnumSet.noneOf(OS.class);
        boolean checking;
        boolean checked;
        boolean returnsValue;
        boolean returnsNothing;

        Member(Unit unit, Statement.Def def) {
            this.unit = unit;
            this.def = def;
        }

        String display() {
            return unit.module == null ? def.name() : unit.module + "." + def.name();
        }

        boolean takesBlock() {
            return !params.isEmpty() && Type.prune(params.getLast()) == Prim.BLOCK;
        }

        boolean repeated() {
            return !def.parameters().isEmpty() && def.parameters().getLast().repeated();
        }
    }

    private void script(String source, SourceFile.Script s) {
        Unit u = new Unit(source, null, targets(source, s), s.bootstrap(), false);
        List<Statement> story = new ArrayList<>();
        List<Statement.Record> records = new ArrayList<>();
        List<Statement.Def> defs = new ArrayList<>();
        for (Statement st : s.body()) {
            switch (st) {
                case Statement.Record r -> records.add(r);
                case Statement.Def d -> defs.add(d);
                default -> story.add(st);
            }
        }
        records(u, records);
        for (Statement.Def d : defs) member(u, d);

        Scope scope = new Scope(null);
        Ctx c = new Ctx(u, null, scope, u.targets, false);
        for (Statement.Param p : s.inputs()) {
            Type t = p.type().map(r -> resolve(r, u)).orElse(null);
            if (p.repeated()) problem(u, p.pos(), "a script's input cannot be repeated");
            if (p.defaultValue().isPresent()) {
                Type d = value(p.defaultValue().get(), c);
                if (t == null) t = d;
                else if (!Type.unify(t, d))
                    problem(u, p.pos(), p.name() + " is " + Type.describe(t) + ", but its default is " + Type.describe(d));
            }
            if (t == null) {
                problem(u, p.pos(), p.name() + " needs a type or a default");
                t = Prim.ERROR;
            }
            declare(c, p.name(), t, p.pos());
        }
        statements(story, c);

        for (Member m : u.members.values()) {
            if (m.checked) continue;
            boolean unknown = m.params.stream().anyMatch(t -> !Type.known(t));
            if (unknown) problem(u, m.def.pos(), m.display() + " is never called, so the types of its parameters are not "
                    + "known; declare them");
            else member(m);
        }
    }

    private Set<OS> targets(String source, SourceFile.Script s) {
        if (s.systems().equals(List.of("OS"))) return ALL;
        Unit placeholder = new Unit(source, null, ALL, false, false);
        if (s.systems().contains("OS")) {
            problem(placeholder, s.pos(), "<OS> stands for every OS on its own; name the OSes instead");
            return ALL;
        }
        return systems(placeholder, s.systems(), s.pos());
    }

    private Unit unit(String source, SourceFile.Module m) {
        Unit u = new Unit(source, m.name(), ALL, false, m.bundled());
        List<Statement.Record> records = new ArrayList<>();
        for (Statement st : m.members()) if (st instanceof Statement.Record r) records.add(r);
        records(u, records);
        for (Statement st : m.members()) if (st instanceof Statement.Def d) member(u, d);
        return u;
    }

    private void members(Unit u) {
        for (Member m : u.members.values()) member(m);
    }

    /**
     * The module of that name, loaded and checked the first time it is named; null if there is none or it does not
     * parse. {@code quiet} looks without reporting a missing module, as a type name does.
     */
    private Unit module(String name, Unit from, Pos pos, boolean quiet) {
        if (modules.containsKey(name)) return modules.get(name);
        var text = library.module(name);
        if (text.isEmpty()) {
            if (!quiet) problem(from, pos, "no module " + name);
            return null;
        }
        String source = name + ".koine";
        SourceFile file;
        try {
            file = Parser.parse(source, text.get());
        } catch (KoineException e) {
            problems.add(new Problem(e.source(), e.pos(), e.problem()));
            modules.put(name, null);
            return null;
        }
        if (!(file instanceof SourceFile.Module m) || !m.name().equals(name)) {
            problem(new Unit(source, name, ALL, false, false), file.pos(), source + " must hold module " + name);
            modules.put(name, null);
            return null;
        }
        Unit u = unit(source, m);
        modules.put(name, u);
        members(u);
        return u;
    }

    // ---- declarations -------------------------------------------------------------------------------------------

    private void records(Unit u, List<Statement.Record> records) {
        for (Statement.Record r : records) {
            if (u.records.containsKey(r.name())) problem(u, r.pos(), "record " + r.name() + " is declared twice");
            else u.records.put(r.name(), new Type.Rec(r.name(), u.module));
        }
        for (Statement.Record r : records) {
            Type.Rec rec = u.records.get(r.name());
            for (Statement.Field f : r.fields()) {
                if (rec.fields.containsKey(f.name())) problem(u, f.pos(), r.name() + " has two fields " + f.name());
                Type t = resolve(f.type(), u);
                if (t == Prim.BLOCK || t == Prim.DATA) {
                    problem(u, f.pos(), "a field cannot be a " + Type.describe(t));
                    t = Prim.ERROR;
                }
                rec.fields.put(f.name(), new Type.Field(t, f.optional()));
            }
        }
    }

    private void member(Unit u, Statement.Def d) {
        Member m = new Member(u, d);
        if (u.members.containsKey(d.name())) problem(u, d.pos(), d.name() + " is defined twice");
        else u.members.put(d.name(), m);

        boolean isNative = d.body().stream().anyMatch(s -> s instanceof Statement.Native);
        boolean contract = u.module != null || isNative;
        List<Statement.Param> ps = d.parameters();
        for (int i = 0; i < ps.size(); i++) {
            Statement.Param p = ps.get(i);
            boolean last = i == ps.size() - 1;
            if (p.defaultValue().isPresent()) problem(u, p.pos(), "only a script's inputs have defaults");
            if (p.repeated() && !last) problem(u, p.pos(), "only the last parameter can be repeated");
            Type t;
            if (p.type().isPresent()) {
                t = resolve(p.type().get(), u);
            } else {
                if (contract) problem(u, p.pos(), m.display() + " must declare the type of " + p.name()
                        + (u.module != null ? ": a module is a contract" : ": it has a native body"));
                t = contract ? Prim.ERROR : new Type.Var();
            }
            if (t == Prim.BLOCK && !last) problem(u, p.pos(), "a Block parameter comes last");
            if (t == Prim.DATA) problem(u, p.pos(), "a parameter cannot be Data; read it into a record with as");
            m.params.add(t);
        }
        if (d.result().isPresent()) {
            m.result = resolve(d.result().get(), u);
            m.resultDeclared = true;
            if (m.result == Prim.BLOCK) problem(u, d.result().get().pos(), "a function cannot return a Block");
        } else if (contract) {
            m.result = Prim.NOTHING;
            m.resultDeclared = true;
        } else {
            m.result = new Type.Var();
        }

        if (u.bundled || d.bundled()) m.bundledOn.addAll(ALL);
        for (Statement s : d.body())
            if (s instanceof Statement.Native n && n.bundled()) m.bundledOn.addAll(systems(u, n.systems(), n.pos()));
    }

    /** Checks a member's body, once. A script's {@code def} is checked at its first call, once its arguments are known. */
    private void member(Member m) {
        if (m.checked || m.checking) return;
        m.checking = true;
        Unit u = m.unit;
        Scope scope = new Scope(null);
        Ctx c = new Ctx(u, m, scope, u.targets, false);
        List<Statement.Param> ps = m.def.parameters();
        for (int i = 0; i < ps.size(); i++) {
            Type t = m.params.get(i);
            declare(c, ps.get(i).name(), ps.get(i).repeated() ? new Type.ListOf(t) : t, ps.get(i).pos());
        }

        List<Statement> body = m.def.body();
        if (body.stream().anyMatch(s -> s instanceof Statement.On || s instanceof Statement.Native)) {
            Map<OS, Pos> covered = new EnumMap<>(OS.class);
            for (Statement s : body) {
                switch (s) {
                    case Statement.On on -> {
                        Set<OS> oses = cover(m, covered, on.systems(), on.pos());
                        Set<OS> compiled = EnumSet.copyOf(oses);
                        compiled.retainAll(u.targets);
                        statements(on.body(), c.on(compiled));
                    }
                    case Statement.Native n -> cover(m, covered, n.systems(), n.pos());
                    default -> problem(u, s.pos(), "a def's body is either Koine, or branches by OS with on and native, "
                            + "not both");
                }
            }
            List<String> missing = u.targets.stream().filter(os -> !covered.containsKey(os)).map(OS::name).toList();
            for (String os : missing) problem(u, m.def.pos(), m.display() + " has no " + os + " body");
        } else {
            statements(body, c);
        }

        if (!m.resultDeclared && !m.returnsValue && m.result instanceof Type.Var v && v.bound == null) v.bound = Prim.NOTHING;
        m.checking = false;
        m.checked = true;
    }

    private Set<OS> cover(Member m, Map<OS, Pos> covered, List<String> names, Pos pos) {
        Set<OS> oses = systems(m.unit, names, pos);
        for (OS os : oses) {
            if (covered.containsKey(os)) problem(m.unit, pos, m.display() + " has two " + os + " bodies");
            covered.put(os, pos);
        }
        return oses;
    }

    private Set<OS> systems(Unit u, List<String> names, Pos pos) {
        Set<OS> oses = EnumSet.noneOf(OS.class);
        for (String name : names) {
            try {
                oses.add(OS.valueOf(name));
            } catch (IllegalArgumentException e) {
                problem(u, pos, name + " is not an OS: Windows, Linux or Mac");
            }
        }
        return oses;
    }

    private Type resolve(TypeRef ref, Unit u) {
        return switch (ref) {
            case TypeRef.ListOf l -> {
                Type element = resolve(l.element(), u);
                if (element == Prim.BLOCK || element == Prim.DATA) {
                    problem(u, l.pos(), "a list cannot hold " + Type.describe(element));
                    yield Prim.ERROR;
                }
                yield new Type.ListOf(element);
            }
            case TypeRef.Named n -> switch (n.name()) {
                case "String" -> Prim.STRING;
                case "Number" -> Prim.NUMBER;
                case "Bool" -> Prim.BOOL;
                case "Block" -> Prim.BLOCK;
                case "Data" -> Prim.DATA;
                default -> {
                    Type.Rec r = record(n.name(), u, n.pos());
                    if (r != null) yield r;
                    problem(u, n.pos(), "no type " + n.name());
                    yield Prim.ERROR;
                }
            };
        };
    }

    /** A record the unit declares, or the main record of the module of that name. */
    private Type.Rec record(String name, Unit u, Pos pos) {
        Type.Rec r = u.records.get(name);
        if (r != null || !capitalised(name) || name.equals(u.module)) return r;
        Unit m = module(name, u, pos, true);
        return m == null ? null : m.records.get(name);
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    /** Names in scope, one block at a time. Each function, and the script's story, starts a scope of its own. */
    private static final class Scope {
        final Scope parent;
        final Map<String, Type> names = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Type find(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Type t = s.names.get(name);
                if (t != null) return t;
            }
            return null;
        }
    }

    /**
     * Where a statement is: in which file and member (null in a script's story), its scope, the OSes it is compiled
     * for, and whether it is in a block given to a member, where {@code return} is not allowed yet.
     */
    private record Ctx(Unit unit, Member member, Scope scope, Set<OS> systems, boolean inBlock) {

        Ctx child() {
            return new Ctx(unit, member, new Scope(scope), systems, inBlock);
        }

        Ctx given() {
            return new Ctx(unit, member, new Scope(scope), systems, true);
        }

        Ctx on(Set<OS> oses) {
            return new Ctx(unit, member, new Scope(scope), oses, inBlock);
        }
    }

    private void statements(List<Statement> body, Ctx c) {
        for (Statement s : body) statement(s, c);
    }

    private void statement(Statement statement, Ctx c) {
        Unit u = c.unit();
        switch (statement) {
            case Statement.Let s -> declare(c, s.name(), value(s.value(), c), s.pos());
            case Statement.Assign s -> assign(s, c);
            case Statement.If s -> {
                for (Statement.If.Branch b : s.branches()) {
                    condition(b.condition(), c);
                    statements(b.body(), c.child());
                }
                statements(s.otherwise(), c.child());
            }
            case Statement.For s -> {
                Type item;
                if (s.source() instanceof Expr.Command command) {
                    command(command, c);
                    item = Prim.STRING;
                } else {
                    Type t = Type.prune(value(s.source(), c));
                    item = switch (t) {
                        case Type.ListOf l -> l.element();
                        case Prim p when p == Prim.ERROR -> Prim.ERROR;
                        case Type.MapOf m -> {
                            problem(u, s.source().pos(), "loop over a map's keys: for key in map.keys");
                            yield Prim.ERROR;
                        }
                        default -> {
                            problem(u, s.source().pos(), "for loops over a list or a command's lines, not "
                                    + Type.describe(t));
                            yield Prim.ERROR;
                        }
                    };
                }
                Ctx body = c.child();
                declare(body, s.item(), item, s.pos());
                statements(s.body(), body);
            }
            case Statement.While s -> {
                condition(s.condition(), c);
                statements(s.body(), c.child());
            }
            case Statement.Within s -> {
                expect(value(s.directory(), c), Prim.STRING, s.directory().pos(), "within takes a directory: a String", c);
                statements(s.body(), c.given());
            }
            case Statement.Try s -> command(s.command(), c);
            case Statement.Fail s -> message(s.message(), "fail", c);
            case Statement.Step s -> statements(s.body(), c.given());
            case Statement.Return s -> returns(s, c);
            case Statement.Print s -> message(s.message(), "print", c);
            case Statement.Run s -> run(s, c);
            case Statement.Finally s -> statements(s.body(), c.given());
            case Statement.Def s -> problem(u, s.pos(), "def goes at the top level of a script or a module");
            case Statement.Record s -> problem(u, s.pos(), "record goes at the top level of a script or a module");
            // Reported where the def's body is checked: on and native are only ever a def's whole body.
            case Statement.On s -> {
            }
            case Statement.Native s -> {
            }
        }
    }

    private void declare(Ctx c, String name, Type type, Pos pos) {
        if (c.scope().find(name) != null) problem(c.unit(), pos, name + " is already defined; reassign it with " + name
                + " = …");
        c.scope().names.put(name, type);
    }

    private void assign(Statement.Assign s, Ctx c) {
        Unit u = c.unit();
        switch (s.target()) {
            case Expr.Name n -> {
                Type t = c.scope().find(n.name());
                Type v = value(s.value(), c);
                if (t == null) problem(u, n.pos(), "no variable " + n.name() + "; introduce it with let " + n.name()
                        + " = …");
                else if (Type.prune(t) == Prim.BLOCK) problem(u, n.pos(), n.name() + " is a block, and cannot be assigned");
                else if (!Type.unify(t, v)) problem(u, s.value().pos(), n.name() + " is " + Type.describe(t)
                        + ", so it cannot be given " + Type.describe(v));
            }
            case Expr.Index ix -> {
                Type target = Type.prune(value(ix.target(), c));
                Type index = value(ix.index(), c);
                Type v = value(s.value(), c);
                switch (target) {
                    case Type.ListOf l -> {
                        expect(index, Prim.NUMBER, ix.index().pos(), "a list's index must be a Number", c);
                        expect(v, l.element(), s.value().pos(), "the list holds " + Type.describe(l.element()), c);
                    }
                    case Type.MapOf m -> {
                        expect(index, m.key(), ix.index().pos(), "the map's keys are " + Type.describe(m.key()), c);
                        expect(v, m.value(), s.value().pos(), "the map's values are " + Type.describe(m.value()), c);
                    }
                    default -> {
                        if (target != Prim.ERROR) problem(u, ix.pos(), "only an item of a list or a map can be assigned");
                    }
                }
            }
            default -> throw new IllegalStateException("the parser allows only a name or an index");
        }
    }

    private void returns(Statement.Return s, Ctx c) {
        Unit u = c.unit();
        Member m = c.member();
        if (c.inBlock()) {
            problem(u, s.pos(), "return cannot be used inside a block given to a member, such as within or step, "
                    + "for now");
            s.value().ifPresent(v -> value(v, c));
            return;
        }
        if (m == null) {
            if (s.value().isPresent()) {
                problem(u, s.pos(), "a script returns no value: return ends it successfully, and fail unsuccessfully");
                value(s.value().get(), c);
            }
            return;
        }
        if (s.value().isPresent()) {
            Type t = value(s.value().get(), c);
            if (m.returnsNothing) problem(u, s.pos(), m.display() + " returns a value here, and none elsewhere");
            m.returnsValue = true;
            if (m.resultDeclared && m.result == Prim.NOTHING)
                problem(u, s.pos(), m.display() + " declares no result, so its return takes no value");
            else if (!Type.unify(m.result, t))
                problem(u, s.value().get().pos(), m.display() + " returns " + Type.describe(m.result) + ", not "
                        + Type.describe(t));
        } else {
            if (m.returnsValue) problem(u, s.pos(), m.display() + " returns a value elsewhere, and none here");
            m.returnsNothing = true;
            if (m.resultDeclared && m.result != Prim.NOTHING)
                problem(u, s.pos(), m.display() + " must return " + Type.describe(m.result));
        }
    }

    /** A statement that does something: a command, a call, or running a block. */
    private void run(Statement.Run s, Ctx c) {
        Unit u = c.unit();
        List<Statement> block = s.block().isEmpty() ? null : s.block();
        Expr e = s.expr();
        if (e instanceof Expr.Command command) {
            command(command, c);
        } else if (e instanceof Expr.Name n && c.scope().find(n.name()) != null) {
            if (Type.prune(c.scope().find(n.name())) != Prim.BLOCK)
                problem(u, n.pos(), n.name() + " is a value, so this line does nothing with it");
            if (block != null) problem(u, n.pos(), "only a member that takes a block can be given one");
            return;
        } else if (e instanceof Expr.Name || e instanceof Expr.Member || e instanceof Expr.Call) {
            Type t = reference(e, c, block);
            if (Type.prune(t) == Prim.DATA) problem(u, e.pos(), "read data into a record with as: … as Record");
            return;
        } else {
            value(e, c);
            problem(u, e.pos(), "this line does nothing with its value");
        }
        if (block != null) {
            problem(u, e.pos(), "only a member that takes a block can be given one");
            statements(block, c.given());
        }
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    /** An expression used as a value: not a member with no result, not unread data, and not a block. */
    private Type value(Expr e, Ctx c) {
        Type t = eval(e, c);
        Type p = Type.prune(t);
        String problem = p == Prim.NOTHING ? "this gives no value"
                : p == Prim.DATA ? "read data into a record with as: … as Record"
                : p == Prim.BLOCK ? "a block can only be run, or passed on to a member that takes one"
                : null;
        if (problem == null) return t;
        problem(c.unit(), e.pos(), problem);
        return Prim.ERROR;
    }

    private Type eval(Expr expr, Ctx c) {
        Unit u = c.unit();
        return switch (expr) {
            case Expr.Name e -> reference(e, c, null);
            case Expr.Member e -> reference(e, c, null);
            case Expr.Call e -> reference(e, c, null);
            case Expr.NumberLit e -> Prim.NUMBER;
            case Expr.BoolLit e -> Prim.BOOL;
            case Expr.Str e -> {
                for (Expr.Str.Part part : e.parts()) {
                    if (part instanceof Expr.Str.Insert i) {
                        Type t = value(i.value(), c);
                        if (Type.unify(t, Prim.STRING)) continue;
                        problem(u, i.pos(), "only a String can be inserted into a string, not " + Type.describe(t)
                                + (Type.prune(t) == Prim.NUMBER ? "; convert it with .text" : ""));
                    }
                }
                yield Prim.STRING;
            }
            case Expr.ListLit e -> {
                Type element = new Type.Var();
                for (Expr item : e.items()) {
                    Type t = value(item, c);
                    if (!Type.unify(element, t)) problem(u, item.pos(), "the list holds " + Type.describe(element)
                            + ", not " + Type.describe(t));
                }
                yield new Type.ListOf(element);
            }
            case Expr.MapLit e -> {
                Type key = new Type.Var();
                Type v = new Type.Var();
                for (Expr.MapLit.Entry entry : e.entries()) {
                    expect(value(entry.key(), c), key, entry.key().pos(), "the map's keys are " + Type.describe(key), c);
                    expect(value(entry.value(), c), v, entry.value().pos(), "the map's values are " + Type.describe(v), c);
                }
                yield new Type.MapOf(key, v);
            }
            case Expr.Index e -> {
                Type target = Type.prune(value(e.target(), c));
                Type index = value(e.index(), c);
                yield switch (target) {
                    case Type.ListOf l -> {
                        expect(index, Prim.NUMBER, e.index().pos(), "a list's index must be a Number", c);
                        yield l.element();
                    }
                    case Type.MapOf m -> {
                        expect(index, m.key(), e.index().pos(), "the map's keys are " + Type.describe(m.key()), c);
                        yield m.value();
                    }
                    default -> {
                        if (target != Prim.ERROR) problem(u, e.pos(), "only a list or a map can be indexed, not "
                                + Type.describe(target));
                        yield Prim.ERROR;
                    }
                };
            }
            case Expr.Not e -> {
                condition(e.operand(), c);
                yield Prim.BOOL;
            }
            case Expr.Binary e -> binary(e, c);
            case Expr.As e -> {
                Type t = Type.prune(eval(e.value(), c));
                if (t != Prim.DATA && t != Prim.ERROR)
                    problem(u, e.value().pos(), "as reads data, such as Json.read(…), into a record; this is "
                            + Type.describe(t));
                Type target = resolve(e.type(), u);
                if (target instanceof Type.Rec) yield target;
                if (target != Prim.ERROR) problem(u, e.type().pos(), "data is read into a record, not "
                        + Type.describe(target));
                yield Prim.ERROR;
            }
            case Expr.Command e -> {
                command(e, c);
                yield Prim.STRING;
            }
            case Expr.Try e -> {
                command(e.command(), c);
                yield Prim.BOOL;
            }
            case Expr.OrElse e -> {
                command(e.command(), c);
                if (e.fallback() instanceof Expr.FailWith f) message(f.message(), "fail", c);
                else expect(value(e.fallback(), c), Prim.STRING, e.fallback().pos(), "a capture's fallback must be a String", c);
                yield Prim.STRING;
            }
            case Expr.FailWith e -> throw new IllegalStateException("the parser allows fail only after else");
        };
    }

    private Type binary(Expr.Binary e, Ctx c) {
        Unit u = c.unit();
        switch (e.op()) {
            case AND, OR -> {
                condition(e.left(), c);
                condition(e.right(), c);
            }
            case EQ, NE -> {
                Type l = value(e.left(), c);
                Type r = value(e.right(), c);
                if (!Type.unify(l, r)) problem(u, e.pos(), Type.describe(l) + " and " + Type.describe(r)
                        + " cannot be compared");
            }
            case LT, LE, GT, GE -> {
                expect(value(e.left(), c), Prim.NUMBER, e.left().pos(), e.op().symbol + " compares Numbers", c);
                expect(value(e.right(), c), Prim.NUMBER, e.right().pos(), e.op().symbol + " compares Numbers", c);
            }
            case IN -> {
                Type l = value(e.left(), c);
                Type r = Type.prune(value(e.right(), c));
                switch (r) {
                    case Prim p when p == Prim.STRING ->
                            expect(l, Prim.STRING, e.left().pos(), "in looks for a String in a String", c);
                    case Type.ListOf list -> expect(l, list.element(), e.left().pos(), "the list holds "
                            + Type.describe(list.element()), c);
                    case Type.MapOf m -> problem(u, e.pos(), "in does not look in a map; use map.has(key)");
                    default -> {
                        if (r != Prim.ERROR) problem(u, e.right().pos(), "in looks in a String or a list, not "
                                + Type.describe(r));
                    }
                }
            }
        }
        return Prim.BOOL;
    }

    /**
     * A name, a member or a call: a variable, a field, a function of this file, a module's member, a member looked up
     * by the type of the value before the dot, or a record being made. {@code block} is the block indented under a
     * call, or null.
     */
    private Type reference(Expr e, Ctx c, List<Statement> block) {
        Unit u = c.unit();
        switch (e) {
            case Expr.Name n -> {
                Type v = c.scope().find(n.name());
                if (v != null) return v;
                Member m = u.members.get(n.name());
                if (m != null) return call(m, null, List.of(), n.pos(), c, block);
                if (record(n.name(), u, n.pos()) != null)
                    problem(u, n.pos(), n.name() + " is a record; make one with " + n.name() + "(field: …)");
                else if (capitalised(n.name()) && module(n.name(), u, n.pos(), true) != null)
                    problem(u, n.pos(), n.name() + " is a module; name one of its members");
                else problem(u, n.pos(), "no name " + n.name());
                return Prim.ERROR;
            }
            case Expr.Member mm -> {
                return member(mm, List.of(), c, block);
            }
            case Expr.Call call -> {
                switch (call.callee()) {
                    case Expr.Name n -> {
                        if (c.scope().find(n.name()) != null) {
                            problem(u, n.pos(), n.name() + " is a value, not a function");
                            return Prim.ERROR;
                        }
                        Member m = u.members.get(n.name());
                        if (m != null) return call(m, null, call.arguments(), n.pos(), c, block);
                        Type.Rec r = record(n.name(), u, n.pos());
                        if (r != null) {
                            if (block != null) problem(u, n.pos(), "a record is not given a block");
                            return construct(r, call, c);
                        }
                        problem(u, n.pos(), "no function " + n.name());
                        arguments(call.arguments(), c);
                        return Prim.ERROR;
                    }
                    case Expr.Member mm -> {
                        return member(mm, call.arguments(), c, block);
                    }
                    default -> {
                        problem(u, call.pos(), "only a function or a member can be called");
                        arguments(call.arguments(), c);
                        return Prim.ERROR;
                    }
                }
            }
            default -> throw new IllegalStateException("not a reference: " + e);
        }
    }

    /** {@code target.name}, called with {@code arguments} when they are written. */
    private Type member(Expr.Member mm, List<Expr.Argument> arguments, Ctx c, List<Statement> block) {
        Unit u = c.unit();
        if (mm.target() instanceof Expr.Name n && c.scope().find(n.name()) == null && !u.members.containsKey(n.name())
                && capitalised(n.name())) {
            Unit module = n.name().equals(u.module) ? u : module(n.name(), u, n.pos(), false);
            if (module == null) {
                arguments(arguments, c);
                return Prim.ERROR;
            }
            Member m = module.members.get(mm.name());
            if (m == null) {
                problem(u, mm.pos(), n.name() + " has no member " + mm.name());
                arguments(arguments, c);
                return Prim.ERROR;
            }
            return call(m, null, arguments, mm.pos(), c, block);
        }

        Type t = Type.prune(value(mm.target(), c));
        if (t instanceof Type.Rec r && r.fields.containsKey(mm.name())) {
            if (!arguments.isEmpty() || block != null) problem(u, mm.pos(), r.name + "." + mm.name() + " is a field");
            return r.fields.get(mm.name()).type();
        }
        return method(t, mm, arguments, c, block);
    }

    /** A member looked up by the type of the value before the dot, which becomes its first argument. */
    private Type method(Type t, Expr.Member mm, List<Expr.Argument> arguments, Ctx c, List<Statement> block) {
        Unit u = c.unit();
        String name = mm.name();
        if (t == Prim.ERROR) {
            arguments(arguments, c);
            return Prim.ERROR;
        }
        if (t instanceof Type.Var) {
            problem(u, mm.pos(), "the type of this value is not known here, so ." + name + " cannot be looked up");
            arguments(arguments, c);
            return Prim.ERROR;
        }

        // Built into the compiler: they work on any list, map or record, add changes its list in place, and matches
        // needs its pattern when compiling.
        boolean container = t == Prim.STRING || t instanceof Type.ListOf || t instanceof Type.MapOf || t instanceof Type.Rec;
        if (name.equals("isEmpty") && container && arguments.isEmpty()) return Prim.BOOL;
        if (t instanceof Type.MapOf m && name.equals("keys") && arguments.isEmpty()) return new Type.ListOf(m.key());
        if (t instanceof Type.MapOf m && name.equals("has") && arguments.size() == 1) {
            Expr key = arguments.getFirst().value();
            expect(value(key, c), m.key(), key.pos(), "the map's keys are " + Type.describe(m.key()), c);
            return Prim.BOOL;
        }
        // Its pattern is a POSIX ERE, which the compiler translates for PowerShell, so it must be known when compiling.
        if (t == Prim.STRING && name.equals("matches") && arguments.size() == 1) {
            Expr pattern = arguments.getFirst().value();
            if (!(pattern instanceof Expr.Str s && s.parts().stream().allMatch(p -> p instanceof Expr.Str.Text)))
                problem(u, pattern.pos(), "matches takes its pattern as a string written out, with nothing inserted");
            return Prim.BOOL;
        }
        if (t instanceof Type.ListOf l && name.equals("add") && arguments.size() == 1) {
            Expr item = arguments.getFirst().value();
            expect(value(item, c), l.element(), item.pos(), "the list holds " + Type.describe(l.element()), c);
            return Prim.NOTHING;
        }

        String moduleName = switch (t) {
            case Type.Rec r -> r.module;
            case Type.ListOf l -> "List";
            case Type.MapOf m -> "Map";
            case Prim p when p == Prim.STRING || p == Prim.NUMBER || p == Prim.BOOL -> p.name;
            default -> null;
        };
        Unit module = moduleName == null ? null
                : moduleName.equals(u.module) ? u : module(moduleName, u, mm.pos(), true);
        Member m = module == null ? null : module.members.get(name);
        if (m == null || m.params.isEmpty()) {
            problem(u, mm.pos(), Type.describe(t) + " has no " + (t instanceof Type.Rec ? "field or member " : "member ")
                    + name);
            arguments(arguments, c);
            return Prim.ERROR;
        }
        return call(m, t, arguments, mm.pos(), c, block);
    }

    /**
     * Calls a member with its arguments: {@code receiver} first if the member was looked up by a value's type, then
     * the arguments as written, then the block if one is indented under the call.
     */
    private Type call(Member m, Type receiver, List<Expr.Argument> arguments, Pos pos, Ctx c, List<Statement> block) {
        Unit u = c.unit();
        for (Expr.Argument a : arguments)
            if (a.name().isPresent()) problem(u, a.pos(), "only a record is made with named arguments");

        boolean blockGiven = block != null;
        if (blockGiven && !m.takesBlock()) problem(u, pos, m.display() + " does not take a block");
        int expected = m.params.size() - (blockGiven && m.takesBlock() ? 1 : 0);
        int given = (receiver == null ? 0 : 1) + arguments.size();
        boolean countOk = m.repeated() ? given >= expected - 1 : given == expected;
        if (!countOk) {
            problem(u, pos, m.display() + " takes " + count(expected, m.repeated()) + ", not " + given);
        }

        int i = 0;
        if (receiver != null) {
            if (!Type.unify(receiver, m.params.getFirst()))
                problem(u, pos, m.display() + " takes " + Type.describe(m.params.getFirst()) + ", not "
                        + Type.describe(receiver));
            i++;
        }
        for (Expr.Argument a : arguments) {
            int p = m.repeated() ? Math.min(i, m.params.size() - 1) : i;
            i++;
            if (p >= m.params.size()) {
                value(a.value(), c);
                continue;
            }
            Type pt = m.params.get(p);
            String param = m.def.parameters().get(p).name();
            if (Type.prune(pt) == Prim.BLOCK) {
                boolean isBlock = a.value() instanceof Expr.Name n && c.scope().find(n.name()) != null
                        && Type.prune(c.scope().find(n.name())) == Prim.BLOCK;
                if (!isBlock) problem(u, a.pos(), m.display() + " takes a Block for " + param
                        + ": a block indented under the call, or a Block parameter passed on");
                continue;
            }
            Type at = value(a.value(), c);
            if (!Type.unify(at, pt)) problem(u, a.pos(), m.display() + " takes " + Type.describe(pt) + " for " + param
                    + ", not " + Type.describe(at));
        }

        if (m.unit.module == null) member(m);
        tiers(m, pos, c);
        if (c.member() != null && c.member().takesBlock() && m.takesBlock())
            blockCalls.computeIfAbsent(c.member(), k -> new LinkedHashSet<>()).add(m);
        if (blockGiven) statements(block, c.given());
        return m.result;
    }

    private static String count(int n, boolean repeated) {
        if (repeated) return (n - 1) + " or more arguments";
        return n == 1 ? "1 argument" : n + " arguments";
    }

    private Type construct(Type.Rec r, Expr.Call call, Ctx c) {
        Unit u = c.unit();
        Set<String> named = new HashSet<>();
        for (Expr.Argument a : call.arguments()) {
            Type t = value(a.value(), c);
            if (a.name().isEmpty()) {
                problem(u, a.pos(), "a record is made by naming its fields: " + r.name + "(field: …)");
                continue;
            }
            String name = a.name().get();
            Type.Field f = r.fields.get(name);
            if (f == null) problem(u, a.pos(), r.name + " has no field " + name);
            else if (!named.add(name)) problem(u, a.pos(), name + " is given twice");
            else if (!Type.unify(t, f.type())) problem(u, a.pos(), r.name + "." + name + " is "
                    + Type.describe(f.type()) + ", not " + Type.describe(t));
        }
        for (var f : r.fields.entrySet())
            if (!f.getValue().optional() && !named.contains(f.getKey()))
                problem(u, call.pos(), r.name + " needs " + f.getKey());
        return r;
    }

    /** The two tier rules: a native member uses no bundled one, and a bootstrap script uses none at all. */
    private void tiers(Member m, Pos pos, Ctx c) {
        Unit u = c.unit();
        Set<OS> bundled = EnumSet.noneOf(OS.class);
        for (OS os : c.systems()) {
            if (!m.bundledOn.contains(os)) continue;
            if (u.bootstrap || u.module != null && c.member() != null && !c.member().bundledOn.contains(os)) bundled.add(os);
        }
        if (bundled.isEmpty()) return;
        String oses = bundled.stream().map(OS::name).collect(Collectors.joining(", "));
        if (u.bootstrap) problem(u, pos, m.display() + " is bundled on " + oses
                + ", and a bootstrap script may use only native members");
        else problem(u, pos, c.member().display() + " is native on " + oses + ", so it cannot use " + m.display()
                + ", which is bundled there");
    }

    private void arguments(List<Expr.Argument> arguments, Ctx c) {
        for (Expr.Argument a : arguments) value(a.value(), c);
    }

    private void command(Expr.Command command, Ctx c) {
        for (Word w : command.words()) {
            for (Word.Part p : w.parts()) {
                if (!(p instanceof Word.Insert i)) continue;
                Type t = Type.prune(value(i.value(), c));
                if (t instanceof Type.ListOf l && Type.unify(l.element(), Prim.STRING)) continue;
                if (Type.unify(t, Prim.STRING)) continue;
                problem(c.unit(), i.pos(), "a command takes a String or a [String], not " + Type.describe(t)
                        + (t == Prim.NUMBER ? "; convert it with .text" : ""));
            }
        }
    }

    private void condition(Expr e, Ctx c) {
        expect(value(e, c), Prim.BOOL, e.pos(), "a condition must be a Bool", c);
    }

    private void message(Expr e, String keyword, Ctx c) {
        expect(value(e, c), Prim.STRING, e.pos(), keyword + " takes a String", c);
    }

    private void expect(Type t, Type want, Pos pos, String rule, Ctx c) {
        if (!Type.unify(t, want)) problem(c.unit(), pos, rule + ", not " + Type.describe(t));
    }

    // ---- members that take a block ------------------------------------------------------------------------------

    /** A member that takes a block is expanded where it is called, so it cannot reach itself. */
    private void blockCycles() {
        for (Member m : blockCalls.keySet()) {
            if (reaches(m, m, new HashSet<>()))
                problem(m.unit, m.def.pos(), m.display() + " takes a block, so it cannot call itself, directly or "
                        + "through other members that take one");
        }
    }

    private boolean reaches(Member from, Member target, Set<Member> seen) {
        for (Member next : blockCalls.getOrDefault(from, Set.of())) {
            if (next == target) return true;
            if (seen.add(next) && reaches(next, target, seen)) return true;
        }
        return false;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static boolean capitalised(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private void problem(Unit u, Pos pos, String message) {
        problems.add(new Problem(u.source, pos, message));
    }
}
