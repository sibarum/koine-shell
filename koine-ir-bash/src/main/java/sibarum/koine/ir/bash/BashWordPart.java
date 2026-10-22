package sibarum.koine.ir.bash;

import java.util.List;

/** A piece of a word. */
public sealed interface BashWordPart permits BashWordPart.Literal, BashWordPart.Glob, BashWordPart.Tilde,
        BashWordPart.SingleQuoted, BashWordPart.AnsiC, BashWordPart.DoubleQuoted, BashParameter,
        BashWordPart.CommandSubstitution, BashWordPart.ArithmeticExpansion, BashWordPart.ProcessSubstitution {

    /** Exactly this text, unquoted: the printer backslash-escapes whatever its place in the script needs. */
    record Literal(String text) implements BashWordPart {
    }

    /** A pattern, printed as it is: {@code *}, {@code ?} and {@code [...]} match. */
    record Glob(String pattern) implements BashWordPart {
    }

    /** {@code ~}, the home directory, at the start of a word. */
    record Tilde() implements BashWordPart {
    }

    /** {@code 'text'}: nothing expands. The text cannot hold a single quote. */
    record SingleQuoted(String text) implements BashWordPart {
        public SingleQuoted {
            if (text.indexOf('\'') >= 0) throw new IllegalArgumentException("a single quote in '...': " + text);
        }
    }

    /** {@code $'text'}: the text as it is meant, the printer writes {@code \t}, {@code \n} and the like. */
    record AnsiC(String text) implements BashWordPart {
    }

    /**
     * {@code "..."}. Inside: literal text, parameters, command substitutions and arithmetic, nothing that does not
     * expand in double quotes.
     */
    record DoubleQuoted(List<BashWordPart> parts) implements BashWordPart {
        public DoubleQuoted {
            parts = List.copyOf(parts);
            for (BashWordPart p : parts)
                if (!(p instanceof Literal || p instanceof BashParameter || p instanceof CommandSubstitution
                        || p instanceof ArithmeticExpansion))
                    throw new IllegalArgumentException("not something double quotes hold: " + p);
        }
    }

    /** {@code $(commands)}. */
    record CommandSubstitution(List<BashCommand> commands) implements BashWordPart {
        public CommandSubstitution {
            commands = List.copyOf(commands);
        }
    }

    /** {@code $((expression))}, the expression in Bash's arithmetic syntax. */
    record ArithmeticExpansion(String expression) implements BashWordPart {
    }

    /** {@code <(commands)}, or {@code >(commands)} when the commands read what is written to it. */
    record ProcessSubstitution(boolean output, List<BashCommand> commands) implements BashWordPart {
        public ProcessSubstitution {
            commands = List.copyOf(commands);
        }
    }
}
