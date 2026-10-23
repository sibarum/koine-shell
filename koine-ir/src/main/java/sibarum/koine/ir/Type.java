package sibarum.koine.ir;

import java.util.List;
import java.util.Optional;

/**
 * What a value is. Bash and Zsh need to know: a list expands as {@code "${xs[@]}"}, numbers compare with {@code -eq}
 * and strings with {@code =}, and a record is one variable per field.
 */
public sealed interface Type {

    Type STRING = new Str();
    Type INT = new Int();
    Type BOOL = new Bool();
    Type DATA = new Data();

    static Type listOf(Type element) {
        return new ListOf(element);
    }

    static Type mapOf(Type value) {
        return new MapOf(value);
    }

    /** Text; paths are strings too. */
    record Str() implements Type {
        @Override
        public String toString() {
            return "string";
        }
    }

    record Int() implements Type {
        @Override
        public String toString() {
            return "int";
        }
    }

    record Bool() implements Type {
        @Override
        public String toString() {
            return "bool";
        }
    }

    /**
     * What a read such as {@code Json.read} gives, which only {@link Expr.ReadAs} can use: a parsed document in
     * PowerShell, the document's compact text in Bash and Zsh.
     */
    record Data() implements Type {
        @Override
        public String toString() {
            return "data";
        }
    }

    record ListOf(Type element) implements Type {
        @Override
        public String toString() {
            return "list<" + element + ">";
        }
    }

    /** A map from strings. */
    record MapOf(Type value) implements Type {
        @Override
        public String toString() {
            return "map<" + value + ">";
        }
    }

    /** A record: named, typed fields. A field marked optional may be missing, and is then empty. */
    record Record(String name, List<Field> fields) implements Type {
        public Record {
            fields = List.copyOf(fields);
        }

        public Optional<Field> field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst();
        }

        @Override
        public String toString() {
            return name;
        }

        public record Field(String name, Type type, boolean optional) {
        }
    }

    // ---- checks, for the records' constructors ------------------------------------------------------------------

    /** Fails unless {@code e} is of type {@code t}. */
    static void require(Expr e, Type t, String role) {
        if (!e.type().equals(t)) throw mismatch(role, t.toString(), e);
    }

    /** Fails unless {@code e} is of one of {@code ts}. */
    static void requireOneOf(Expr e, String role, Type... ts) {
        for (Type t : ts) if (e.type().equals(t)) return;
        throw mismatch(role, String.join(" or ", java.util.Arrays.stream(ts).map(Type::toString).toList()), e);
    }

    /** The element type of a list, failing on anything else. */
    static Type elementOf(Expr e, String role) {
        if (e.type() instanceof ListOf l) return l.element();
        throw mismatch(role, "a list", e);
    }

    /** The value type of a map, failing on anything else. */
    static Type valueOf(Expr e, String role) {
        if (e.type() instanceof MapOf m) return m.value();
        throw mismatch(role, "a map", e);
    }

    private static IllegalArgumentException mismatch(String role, String wanted, Expr e) {
        return new IllegalArgumentException(role + " must be " + wanted + ", not " + e.type() + ": " + e);
    }
}
