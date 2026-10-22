package sibarum.koine.ir.zsh;

import java.util.List;

/** A piece of a word. */
public sealed interface ZshWordPart permits ZshWordPart.Literal, ZshWordPart.Glob, ZshWordPart.Tilde,
        ZshWordPart.SingleQuoted, ZshWordPart.AnsiC, ZshWordPart.DoubleQuoted, ZshParameter,
        ZshWordPart.CommandSubstitution, ZshWordPart.ArithmeticExpansion, ZshWordPart.ProcessSubstitution {

    /** Exactly this text, unquoted: the printer backslash-escapes whatever its place in the script needs. */
    record Literal(String text) implements ZshWordPart {
    }

    /**
     * A pattern, printed as it is: {@code *}, {@code ?}, {@code [...]}, {@code **}{@code /}, and glob qualifiers such as
     * {@code (N)} and {@code (.)} at its end.
     */
    record Glob(String pattern) implements ZshWordPart {
    }

    /** {@code ~}, the home directory, at the start of a word. */
    record Tilde() implements ZshWordPart {
    }

    /** {@code 'text'}: nothing expands. The text cannot hold a single quote. */
    record SingleQuoted(String text) implements ZshWordPart {
        public SingleQuoted {
            if (text.indexOf('\'') >= 0) throw new IllegalArgumentException("a single quote in '...': " + text);
        }
    }

    /** {@code $'text'}: the text as it is meant, the printer writes {@code \t}, {@code \n} and the like. */
    record AnsiC(String text) implements ZshWordPart {
    }

    /**
     * {@code "..."}. Inside: literal text, parameters, command substitutions and arithmetic, nothing that does not
     * expand in double quotes.
     */
    record DoubleQuoted(List<ZshWordPart> parts) implements ZshWordPart {
        public DoubleQuoted {
            parts = List.copyOf(parts);
            for (ZshWordPart p : parts)
                if (!(p instanceof Literal || p instanceof ZshParameter || p instanceof CommandSubstitution
                        || p instanceof ArithmeticExpansion))
                    throw new IllegalArgumentException("not something double quotes hold: " + p);
        }
    }

    /** {@code $(commands)}. */
    record CommandSubstitution(List<ZshCommand> commands) implements ZshWordPart {
        public CommandSubstitution {
            commands = List.copyOf(commands);
        }
    }

    /** {@code $((expression))}, the expression in Zsh's arithmetic syntax. */
    record ArithmeticExpansion(String expression) implements ZshWordPart {
    }

    enum Substitution {
        /** {@code <(commands)}: a pipe to read their output from. */
        INPUT("<("),
        /** {@code >(commands)}: a pipe they read from. */
        OUTPUT(">("),
        /** {@code =(commands)}: a temporary file holding their output, for a program that seeks. */
        FILE("=(");

        public final String token;

        Substitution(String token) {
            this.token = token;
        }
    }

    record ProcessSubstitution(Substitution kind, List<ZshCommand> commands) implements ZshWordPart {
        public ProcessSubstitution {
            commands = List.copyOf(commands);
        }
    }
}
