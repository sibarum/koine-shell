package sibarum.koine.ir.zsh;

import java.util.List;

/**
 * One shell word: parts written next to each other with nothing between them, as {@code "$root"/target/*.jar} is a
 * double-quoted part, a literal and a glob. A word with no parts is the empty string.
 */
public record ZshWord(List<ZshWordPart> parts) {
    public ZshWord {
        parts = List.copyOf(parts);
    }
}
