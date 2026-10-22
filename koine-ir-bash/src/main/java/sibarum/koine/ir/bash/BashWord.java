package sibarum.koine.ir.bash;

import java.util.List;

/**
 * One shell word: parts written next to each other with nothing between them, as {@code "$root"/target/*.jar} is a
 * double-quoted part, a literal and a glob. A word with no parts is the empty string.
 */
public record BashWord(List<BashWordPart> parts) {
    public BashWord {
        parts = List.copyOf(parts);
    }
}
