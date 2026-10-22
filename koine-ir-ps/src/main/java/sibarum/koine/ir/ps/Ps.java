package sibarum.koine.ir.ps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.ps.PsExpression.*;
import sibarum.koine.ir.ps.PsStatement.*;

/**
 * Short factories for building PowerShell IR by hand. Where a statement is expected they also take a command or an
 * expression, and make it the pipeline of one it is in PowerShell.
 */
public final class Ps {

    private Ps() {
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    /** A statement as it is, or a command or expression as a pipeline of one. */
    public static PsStatement st(Object o) {
        return switch (o) {
            case PsStatement s -> s;
            case PsPipelineElement e -> new Pipeline(List.of(e));
            default -> throw new IllegalArgumentException("not a statement, command or expression: " + o);
        };
    }

    private static List<PsStatement> sts(Object... os) {
        return Arrays.stream(os).map(Ps::st).toList();
    }

    public static PsBlock block(Object... statements) {
        return new PsBlock(sts(statements));
    }

    public static Pipeline pipe(PsPipelineElement... elements) {
        return new Pipeline(List.of(elements));
    }

    public static Assignment assign(PsExpression target, Object value) {
        return new Assignment(target, AssignmentOperator.ASSIGN, st(value));
    }

    public static Assignment addAssign(PsExpression target, Object value) {
        return new Assignment(target, AssignmentOperator.ADD, st(value));
    }

    public static If if_(Object condition, PsBlock body) {
        return new If(List.of(new Clause(st(condition), body)), Optional.empty());
    }

    public static Foreach foreach(String variable, Object collection, PsBlock body) {
        return new Foreach(var(variable), st(collection), body);
    }

    public static While while_(Object condition, PsBlock body) {
        return new While(st(condition), body);
    }

    public static Function function(String name, List<PsParameter> parameters, PsBlock body) {
        return new Function(name, parameters, body);
    }

    public static Try tryCatch(PsBlock body, PsBlock catchAll) {
        return new Try(body, List.of(new Catch(List.of(), catchAll)), Optional.empty());
    }

    public static Return return_(Object value) {
        return new Return(Optional.of(st(value)));
    }

    public static Exit exit(Object value) {
        return new Exit(Optional.of(st(value)));
    }

    public static Throw throw_(Object value) {
        return new Throw(Optional.of(st(value)));
    }

    public static Continue continue_() {
        return new Continue();
    }

    public static Break break_() {
        return new Break();
    }

    public static Comment comment(String text) {
        return new Comment(text);
    }

    public static Blank blank() {
        return new Blank();
    }

    public static PsParameter param(String name) {
        return new PsParameter(Optional.empty(), name, Optional.empty());
    }

    public static PsParameter param(String type, String name) {
        return new PsParameter(Optional.of(new PsTypeName(type)), name, Optional.empty());
    }

    public static PsParameter param(String type, String name, PsExpression defaultValue) {
        return new PsParameter(Optional.of(new PsTypeName(type)), name, Optional.of(defaultValue));
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    /** A command by name: {@code Name args...}. */
    public static PsCommand cmd(String name, PsCommandElement... arguments) {
        return new PsCommand(PsCommand.Invocation.NONE, bare(name), List.of(arguments), List.of());
    }

    /** {@code & name args...}. */
    public static PsCommand call(PsExpression name, PsCommandElement... arguments) {
        return new PsCommand(PsCommand.Invocation.CALL, name, List.of(arguments), List.of());
    }

    /** {@code -Name}. */
    public static PsCommandElement.Parameter opt(String name) {
        return new PsCommandElement.Parameter(name, Optional.empty());
    }

    public static PsRedirection.ToFile redirect(PsRedirection.Stream stream, PsExpression target) {
        return new PsRedirection.ToFile(stream, false, target);
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    public static Variable var(String name) {
        return new Variable(name);
    }

    public static Splat splat(String name) {
        return new Splat(name);
    }

    public static IntLiteral num(long value) {
        return new IntLiteral(value);
    }

    public static SingleQuoted sq(String value) {
        return new SingleQuoted(value);
    }

    /** {@code "..."} from strings (literal text) and string parts. */
    public static Expandable dq(Object... parts) {
        List<PsStringPart> ps = new ArrayList<>();
        for (Object o : parts)
            ps.add(switch (o) {
                case String s -> new PsStringPart.Text(s);
                case PsStringPart p -> p;
                default -> throw new IllegalArgumentException("not a string part: " + o);
            });
        return new Expandable(ps);
    }

    public static BareWord bare(String text) {
        return new BareWord(text);
    }

    /** {@code a, b, c}. */
    public static ArrayLiteral list(PsExpression... elements) {
        return new ArrayLiteral(List.of(elements));
    }

    /** {@code @(...)}. */
    public static ArraySubexpression arr(Object... statements) {
        return new ArraySubexpression(sts(statements));
    }

    /** {@code $(...)}. */
    public static SubExpression sub(Object... statements) {
        return new SubExpression(sts(statements));
    }

    public static Paren paren(Object statement) {
        return new Paren(st(statement));
    }

    public static Hashtable hash(Entry... entries) {
        return new Hashtable(List.of(entries));
    }

    public static Entry entry(String key, Object value) {
        return new Entry(bare(key), st(value));
    }

    /** {@code { ... }}. */
    public static ScriptBlock sb(Object... statements) {
        return new ScriptBlock(block(statements));
    }

    public static Convert cast(String type, PsExpression operand) {
        return new Convert(new PsTypeName(type), operand);
    }

    public static TypeLiteral type(String name) {
        return new TypeLiteral(new PsTypeName(name));
    }

    /** {@code target.a.b.c}. */
    public static PsExpression member(PsExpression target, String... names) {
        PsExpression e = target;
        for (String n : names) e = new Member(e, n, false);
        return e;
    }

    /** {@code [type]::name}. */
    public static Member staticMember(String type, String name) {
        return new Member(type(type), name, true);
    }

    public static InvokeMember invoke(PsExpression target, String name, PsExpression... arguments) {
        return new InvokeMember(target, name, List.of(arguments), false);
    }

    /** {@code [type]::name(args)}. */
    public static InvokeMember invokeStatic(String type, String name, PsExpression... arguments) {
        return new InvokeMember(type(type), name, List.of(arguments), true);
    }

    public static Index index(PsExpression target, PsExpression index) {
        return new Index(target, index);
    }

    public static Unary not(PsExpression operand) {
        return new Unary(UnaryOperator.NOT, operand);
    }

    public static Binary bin(PsExpression left, BinaryOperator operator, PsExpression right) {
        return new Binary(left, operator, right);
    }
}
