package sibarum.koine.ir.zsh;

import java.util.List;
import java.util.Optional;

/**
 * A Zsh script file.
 *
 * @param interpreter what follows {@code #!} on the first line, as in {@code /usr/bin/env zsh}
 */
public record ZshScript(Optional<String> interpreter, List<ZshCommand> body) {
    public ZshScript {
        body = List.copyOf(body);
    }
}
