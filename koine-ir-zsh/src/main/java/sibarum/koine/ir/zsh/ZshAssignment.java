package sibarum.koine.ir.zsh;

import java.util.List;
import java.util.Optional;

/** {@code name=value}, {@code name+=value}, {@code name[key]=value}, {@code name=(a b c)}. */
public record ZshAssignment(String name, Optional<String> subscript, boolean append, Value value) {

    public sealed interface Value {
    }

    /** One word; with no parts it assigns the empty string, as {@code only=} does. */
    public record Scalar(ZshWord word) implements Value {
    }

    /** {@code (a b c)}. */
    public record Array(List<ZshWord> elements) implements Value {
        public Array {
            elements = List.copyOf(elements);
        }
    }
}
