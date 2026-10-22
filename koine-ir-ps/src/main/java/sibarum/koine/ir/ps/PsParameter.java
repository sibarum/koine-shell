package sibarum.koine.ir.ps;

import java.util.Optional;

/** {@code [type]$name = default}, in a {@code param()} block or a function's parentheses. */
public record PsParameter(Optional<PsTypeName> type, String name, Optional<PsExpression> defaultValue) {
}
