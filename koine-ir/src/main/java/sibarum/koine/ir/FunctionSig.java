package sibarum.koine.ir;

import java.util.List;
import java.util.Optional;

/**
 * A function's name, parameters and result: what a {@link Expr.Call} needs, apart from the body, so that a call can
 * be built before the function it calls.
 */
public record FunctionSig(String name, List<Var> parameters, Optional<Type> returns) {
    public FunctionSig {
        parameters = List.copyOf(parameters);
    }
}
