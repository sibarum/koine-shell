package sibarum.koine.ir;

import java.util.List;
import java.util.Optional;

/**
 * A portable script. Its parameters become a {@code param()} block with comment-based help in PowerShell, and option
 * parsing with a {@code --help} in Bash and Zsh.
 *
 * @param name the script's file name without its extension
 */
public record Script(String name, String synopsis, String description, List<Param> parameters, List<Statement> body) {

    public Script {
        parameters = List.copyOf(parameters);
        body = List.copyOf(body);
    }

    /**
     * A parameter. A bool is a flag ({@code -SkipBuild}, {@code --skip-build}) and false unless given; an int or a
     * string takes a value, and has its default when it is not given.
     */
    public record Param(Var var, Optional<Expr> defaultValue, String help) {
        public Param {
            if (!List.of(Type.BOOL, Type.INT, Type.STRING).contains(var.type()))
                throw new IllegalArgumentException("a parameter is a bool, an int or a string, not " + var.type());
            if (var.type().equals(Type.BOOL) && defaultValue.isPresent())
                throw new IllegalArgumentException(var.name() + " is a flag: it is false unless given");
            defaultValue.ifPresent(d -> Type.require(d, var.type(), "the default of " + var.name()));
        }
    }
}
