package sibarum.koine.ir.ps;

import java.util.List;

/** A statement list between braces: the body of an {@code if}, a loop, a function, or a script block. */
public record PsBlock(List<PsStatement> statements) {
    public PsBlock {
        statements = List.copyOf(statements);
    }
}
