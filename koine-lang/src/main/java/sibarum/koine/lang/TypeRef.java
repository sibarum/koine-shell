package sibarum.koine.lang;

/**
 * A type as written: {@code String}, {@code Number}, {@code Bool}, a record's name, or a list such as {@code [String]}.
 * The parser does not know which names are records; the checker does.
 */
public sealed interface TypeRef {

    Pos pos();

    record Named(String name, Pos pos) implements TypeRef {
    }

    record ListOf(TypeRef element, Pos pos) implements TypeRef {
    }
}
