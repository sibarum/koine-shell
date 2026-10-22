package sibarum.koine.ir.zsh;

import java.util.List;
import java.util.Optional;

/**
 * A parameter expansion: {@code $x}, {@code ${(j:, :)xs}}, {@code ${0:A:h}}, {@code ${(f)"$(cmd)"}},
 * {@code ${#xs}}, {@code ${x:-default}}. Zsh reads them as {@code ${(flags)prefixes target[subscript]:modifiers op}};
 * the printer leaves out the braces only where Zsh allows it.
 *
 * @param flags     what goes between the parentheses, each as written: {@code f}, {@code j:, :}, {@code @}, {@code k}
 * @param subscript what goes between the brackets, verbatim: {@code 1}, {@code -1}, {@code (i)x}, {@code @}
 * @param modifiers each as written after its colon: {@code h}, {@code t}, {@code A}, {@code r}, {@code s/a/b/}
 */
public record ZshParameter(List<String> flags, List<Prefix> prefixes, Target target, Optional<String> subscript,
                           List<String> modifiers, Optional<Operation> operation) implements ZshWordPart {

    public ZshParameter {
        flags = List.copyOf(flags);
        prefixes = List.copyOf(prefixes);
        modifiers = List.copyOf(modifiers);
        if (!modifiers.isEmpty() && operation.isPresent())
            throw new IllegalArgumentException("modifiers and an operation do not go in one expansion");
    }

    public enum Prefix {
        /** {@code ${#x}}: the length, or the number of elements. */
        LENGTH("#"),
        /** {@code ${+x}}: 1 when x is set, else 0. */
        IS_SET("+"),
        /** {@code ${~x}}: the value's pattern characters are live, as in a glob. */
        GLOB_SUBST("~"),
        /** {@code ${=x}}: the value is split into words at {@code $IFS}. */
        SPLIT("="),
        /** {@code ${^xs}}: each element combines with the text around it, as {@code a${^xs}b}. */
        RC_EXPAND("^");

        public final String token;

        Prefix(String token) {
            this.token = token;
        }
    }

    /** What is expanded: a parameter by name, or the value of another expansion. */
    public sealed interface Target {
    }

    /** A variable, a positional parameter ({@code 1}) or a special one ({@code #}, {@code ?}, {@code @}, {@code 0}). */
    public record Name(String name) implements Target {
    }

    /** {@code ${${x}:h}}, {@code ${(f)"$(cmd)"}}: an expansion or a double-quoted word in place of a name. */
    public record Nested(ZshWordPart inner) implements Target {
        public Nested {
            if (!(inner instanceof ZshParameter || inner instanceof ZshWordPart.CommandSubstitution
                    || inner instanceof ZshWordPart.DoubleQuoted))
                throw new IllegalArgumentException("not something an expansion nests: " + inner);
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
    public record Default(boolean colon, DefaultKind kind, ZshWord word) implements Operation {
    }

    /** {@code ${x#pattern}}, {@code ${x##pattern}} from the start; {@code ${x%pattern}}, {@code ${x%%pattern}} from the end. */
    public record Remove(boolean fromEnd, boolean longest, ZshWord pattern) implements Operation {
    }

    public enum ReplaceMode {
        FIRST("/"), ALL("//"), PREFIX("/#"), SUFFIX("/%");

        public final String token;

        ReplaceMode(String token) {
            this.token = token;
        }
    }

    /** {@code ${x/pattern/replacement}}; with no replacement, the matches are deleted. */
    public record Replace(ReplaceMode mode, ZshWord pattern, Optional<ZshWord> replacement) implements Operation {
    }

    /** {@code ${x:offset}} or {@code ${x:offset:length}}, both in arithmetic syntax. */
    public record Substring(String offset, Optional<String> length) implements Operation {
    }

    /** {@code ${xs:#pattern}}: the elements that do not match; with the {@code M} flag, those that do. */
    public record Filter(ZshWord pattern) implements Operation {
    }

    /** {@code ${xs:|ys}}, the elements not in ys; {@code ${xs:*ys}}, those in it. */
    public record ArrayOperation(boolean intersection, String other) implements Operation {
    }
}
