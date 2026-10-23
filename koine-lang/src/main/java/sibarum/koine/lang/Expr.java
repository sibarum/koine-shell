package sibarum.koine.lang;

import java.util.List;
import java.util.Optional;

/**
 * An expression as written. Names are not resolved: {@code Path.join(a, b)} is a {@link Call} of a {@link Member} of the
 * {@link Name} {@code Path}, and only the checker knows that {@code Path} is a module.
 */
public sealed interface Expr {

    Pos pos();

    record Name(String name, Pos pos) implements Expr {
    }

    /** A whole number. */
    record NumberLit(long value, Pos pos) implements Expr {
    }

    record BoolLit(boolean value, Pos pos) implements Expr {
    }

    /**
     * A string between two runs of the same {@code quotes}, such as {@code '…'} or {@code '''…'''}; there is no empty
     * string literal. Nothing in it is special unless its prefix defined value brackets,
     * {@code open} and {@code close}, which insert a value, or an {@code escape} character. Each is
     * {@link Lexer#NONE} when not defined; escapes are already applied to the text.
     */
    record Str(List<Part> parts, String quotes, char open, char close, char escape, Pos pos) implements Expr {
        public Str {
            parts = List.copyOf(parts);
        }

        public sealed interface Part {

            Pos pos();
        }

        public record Text(String text, Pos pos) implements Part {
        }

        public record Insert(Expr value, Pos pos) implements Part {
        }
    }

    record ListLit(List<Expr> items, Pos pos) implements Expr {
        public ListLit {
            items = List.copyOf(items);
        }
    }

    record MapLit(List<Entry> entries, Pos pos) implements Expr {
        public MapLit {
            entries = List.copyOf(entries);
        }

        public record Entry(Expr key, Expr value) {
        }
    }

    /** {@code target.name}: a field, a member of a module, or a member looked up by the target's type. */
    record Member(Expr target, String name, Pos pos) implements Expr {
    }

    record Call(Expr callee, List<Argument> arguments, Pos pos) implements Expr {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /** An argument, named when a record is made: {@code Binary(name: 'app', source: 'app.exe')}. */
    record Argument(Optional<String> name, Expr value, Pos pos) {
    }

    record Index(Expr target, Expr index, Pos pos) implements Expr {
    }

    record Not(Expr operand, Pos pos) implements Expr {
    }

    enum Op {
        OR("or"), AND("and"), EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="), IN("in");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    record Binary(Op op, Expr left, Expr right, Pos pos) implements Expr {
    }

    /** {@code value as Type}: data read into a declared record. */
    record As(Expr value, TypeRef type, Pos pos) implements Expr {
    }

    /**
     * A literal command in backticks. As a statement it runs; as a value it captures the output. Like a {@link Str},
     * it has value brackets, {@code open} and {@code close}, and an {@code escape} character only if its prefix
     * defined them; each is {@link Lexer#NONE} otherwise.
     */
    record Command(List<Word> words, char open, char close, char escape, Pos pos) implements Expr {
        public Command {
            words = List.copyOf(words);
        }
    }

    /** {@code try `…`} in a condition: true if the command succeeds. */
    record Try(Command command, Pos pos) implements Expr {
    }
}
