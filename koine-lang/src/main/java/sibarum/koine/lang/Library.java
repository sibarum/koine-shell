package sibarum.koine.lang;

import java.util.Optional;

/**
 * Where the checker finds the modules a file names. A script finds a module in the standard library first, then next
 * to itself; putting those two places in order is the library's job.
 */
@FunctionalInterface
public interface Library {

    /** The text of {@code <name>.koine}, if there is one. */
    Optional<String> module(String name);
}
