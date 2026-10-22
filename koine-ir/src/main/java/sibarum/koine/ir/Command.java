package sibarum.koine.ir;

import java.util.List;

/**
 * An external program and its arguments. A list argument spreads into one argument per element. Whether it must
 * succeed is up to what runs it: {@link Statement.Run}, {@link Expr.Succeeds}, {@link Expr.Capture}.
 *
 * @param program a program name found on the path, a path to one, or a {@link Expr.ScriptPath}
 * @param quiet   discard its output and errors
 */
public record Command(Expr program, List<Expr> arguments, boolean quiet) {
    public Command {
        arguments = List.copyOf(arguments);
        Type.require(program, Type.STRING, "a command's program");
        for (Expr a : arguments)
            Type.requireOneOf(a, "a command's argument", Type.STRING, Type.INT, Type.listOf(Type.STRING));
    }
}
