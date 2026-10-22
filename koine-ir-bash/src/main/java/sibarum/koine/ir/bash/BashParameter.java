package sibarum.koine.ir.bash;

import java.util.Optional;

/**
 * A parameter expansion: {@code $x}, {@code ${x:-default}}, {@code ${#xs[@]}}, {@code ${x%,*}}. The printer leaves
 * out the braces only where Bash allows it.
 *
 * @param name      a variable name, a positional parameter ({@code 1}) or a special one ({@code #}, {@code ?},
 *                  {@code !}, {@code @}, {@code *}, {@code $}, {@code 0}, {@code -})
 * @param subscript what goes between the brackets, verbatim: {@code @}, {@code *}, an index or a key
 */
public record BashParameter(Prefix prefix, String name, Optional<String> subscript, Optional<Operation> operation)
        implements BashWordPart {

    public enum Prefix {
        NONE(""),
        /** {@code ${#x}}: the length, or the number of elements of {@code ${#xs[@]}}. */
        LENGTH("#"),
        /** {@code ${!x}}: the variable named by x's value; {@code ${!xs[@]}}, the keys. */
        INDIRECT("!");

        public final String token;

        Prefix(String token) {
            this.token = token;
        }
    }

    /** What happens to the value, after the name. */
    public sealed interface Operation {
    }

    public enum DefaultKind {
        /** {@code -}: the word when unset. */
        USE_DEFAULT("-"),
        /** {@code =}: the word when unset, assigned to it too. */
        ASSIGN_DEFAULT("="),
        /** {@code ?}: fail with the word as the message when unset. */
        ERROR("?"),
        /** {@code +}: the word when set, else nothing. */
        USE_ALTERNATE("+");

        public final String token;

        DefaultKind(String token) {
            this.token = token;
        }
    }

    /** {@code ${x:-word}}; without the colon, only unset counts, not empty as well. */
    public record Default(boolean colon, DefaultKind kind, BashWord word) implements Operation {
    }

    /** {@code ${x#pattern}}, {@code ${x##pattern}} from the start; {@code ${x%pattern}}, {@code ${x%%pattern}} from the end. */
    public record Remove(boolean fromEnd, boolean longest, BashWord pattern) implements Operation {
    }

    public enum ReplaceMode {
        FIRST("/"), ALL("//"), PREFIX("/#"), SUFFIX("/%");

        public final String token;

        ReplaceMode(String token) {
            this.token = token;
        }
    }

    /** {@code ${x/pattern/replacement}}; with no replacement, the matches are deleted. */
    public record Replace(ReplaceMode mode, BashWord pattern, Optional<BashWord> replacement) implements Operation {
    }

    /** {@code ${x:offset}} or {@code ${x:offset:length}}, both in arithmetic syntax. */
    public record Substring(String offset, Optional<String> length) implements Operation {
    }

    /** {@code ${x^}}, {@code ${x^^}} to upper case; {@code ${x,}}, {@code ${x,,}} to lower case. */
    public record CaseChange(boolean upper, boolean all, Optional<BashWord> pattern) implements Operation {
    }

    /** {@code ${x@Q}} and the other transformations, by their letter. */
    public record Transform(char operator) implements Operation {
    }
}
