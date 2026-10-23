package sibarum.koine.lang;

import java.util.Optional;

/**
 * Where the checker finds the modules a file names. A script finds a module in the standard library first, then next
 * to itself: {@code StandardLibrary.INSTANCE.orElse(beside)}.
 */
@FunctionalInterface
public interface Library {

    /** The text of {@code <name>.koine}, if there is one. */
    Optional<String> module(String name);

    /** This library's module, or else {@code next}'s. */
    default Library orElse(Library next) {
        return name -> module(name).or(() -> next.module(name));
    }
}
