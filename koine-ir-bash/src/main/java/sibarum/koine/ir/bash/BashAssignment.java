package sibarum.koine.ir.bash;

import java.util.List;
import java.util.Optional;

/** {@code name=value}, {@code name+=value}, {@code name[key]=value}, {@code name=(a b c)}. */
public record BashAssignment(String name, Optional<String> subscript, boolean append, Value value) {

    public sealed interface Value {
    }

    /** One word; with no parts it assigns the empty string, as {@code only=} does. */
    public record Scalar(BashWord word) implements Value {
    }

    /** {@code (a b c)}. */
    public record Array(List<BashWord> elements) implements Value {
        public Array {
            elements = List.copyOf(elements);
        }
    }
}
