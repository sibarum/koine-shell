package sibarum.koine.ir.ps;

import java.util.List;
import java.util.Optional;

/**
 * A PowerShell script file: comment-based help, a {@code param()} block, then its statements.
 *
 * @param help       the text between {@code <#} and {@code #>}, verbatim
 * @param paramBlock the script's {@code param(...)}, when it has one
 */
public record PsScript(Optional<String> help, Optional<ParamBlock> paramBlock, PsBlock body) {

    /** {@code param(a, b, c)}. */
    public record ParamBlock(List<PsParameter> parameters) {
        public ParamBlock {
            parameters = List.copyOf(parameters);
        }
    }
}
