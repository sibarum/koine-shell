package sibarum.koine.ir.bash;

import java.util.List;
import java.util.Optional;

/**
 * A Bash script file.
 *
 * @param interpreter what follows {@code #!} on the first line, as in {@code /usr/bin/env bash}
 */
public record BashScript(Optional<String> interpreter, List<BashCommand> body) {
    public BashScript {
        body = List.copyOf(body);
    }
}
