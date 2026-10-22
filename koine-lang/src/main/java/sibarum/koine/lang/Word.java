package sibarum.koine.lang;

import java.util.List;

/**
 * One argument of a literal command. Its parts are written next to each other with no space between, as in
 * {@code --log={log}.txt}, and arrive as one argument.
 */
public record Word(List<Part> parts, Pos pos) {

    public Word {
        parts = List.copyOf(parts);
    }

    public sealed interface Part {

        Pos pos();
    }

    /** Text outside quotes, as written. */
    public record Literal(String text, Pos pos) implements Part {
    }

    /** Text in {@code '…'} or {@code "…"}, without the quotes. Nothing in it is expanded. */
    public record Quoted(String text, char quote, Pos pos) implements Part {
    }

    /** A value inserted between the command's brackets, {@code {…}} by default. */
    public record Insert(Expr value, Pos pos) implements Part {
    }
}
