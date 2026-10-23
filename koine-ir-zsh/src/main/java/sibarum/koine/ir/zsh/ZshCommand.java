package sibarum.koine.ir.zsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Zsh command, simple or compound, and the lists that join them. Comments and blank lines are commands too, so
 * that a printed tree reads like the script it came from; a script's {@code --help} that prints its own header
 * comments needs them where they were.
 */
public sealed interface ZshCommand {

    /** {@code a=1 b=2 name args... redirections}; assignments alone, or redirections alone, are commands too. */
    record Simple(List<ZshAssignment> assignments, List<ZshWord> words, List<ZshRedirection> redirections)
            implements ZshCommand {
        public Simple {
            assignments = List.copyOf(assignments);
            words = List.copyOf(words);
            redirections = List.copyOf(redirections);
            if (assignments.isEmpty() && words.isEmpty() && redirections.isEmpty())
                throw new IllegalArgumentException("an empty command");
        }

        public Simple redirect(ZshRedirection... more) {
            List<ZshRedirection> all = new ArrayList<>(redirections);
            all.addAll(List.of(more));
            return new Simple(assignments, words, all);
        }
    }

    /** {@code a | b | c}, or {@code ! a} with {@code negated}. */
    record Pipeline(boolean negated, List<ZshCommand> commands) implements ZshCommand {
        public Pipeline {
            commands = List.copyOf(commands);
            if (commands.isEmpty()) throw new IllegalArgumentException("an empty pipeline");
        }
    }

    /** {@code first && a || b ...}, left to right with equal precedence. */
    record AndOr(ZshCommand first, List<Link> rest) implements ZshCommand {
        public AndOr {
            rest = List.copyOf(rest);
            if (rest.isEmpty()) throw new IllegalArgumentException("an and-or list of one");
        }

        public AndOr and(ZshCommand next) {
            return with(true, next);
        }

        public AndOr or(ZshCommand next) {
            return with(false, next);
        }

        private AndOr with(boolean and, ZshCommand next) {
            List<Link> all = new ArrayList<>(rest);
            all.add(new Link(and, next));
            return new AndOr(first, all);
        }
    }

    /** {@code && command}, or {@code || command}. */
    record Link(boolean and, ZshCommand command) {
    }

    /** {@code command &}, or {@code command &!} when the shell is to forget the job (disown it) at once. */
    record Background(ZshCommand command, boolean disown) implements ZshCommand {
    }

    /** {@code { list; }}. */
    record BraceGroup(List<ZshCommand> body) implements ZshCommand {
        public BraceGroup {
            body = List.copyOf(body);
        }
    }

    /** {@code ( list )}. */
    record Subshell(List<ZshCommand> body) implements ZshCommand {
        public Subshell {
            body = List.copyOf(body);
        }
    }

    /** {@code if list; then list; elif list; then list; else list; fi}. */
    record If(List<Clause> clauses, Optional<List<ZshCommand>> otherwise) implements ZshCommand {
        public If {
            clauses = List.copyOf(clauses);
            otherwise = otherwise.map(List::copyOf);
            if (clauses.isEmpty()) throw new IllegalArgumentException("an if with no condition");
        }

        public If elif(List<ZshCommand> condition, List<ZshCommand> body) {
            List<Clause> all = new ArrayList<>(clauses);
            all.add(new Clause(condition, body));
            return new If(all, otherwise);
        }

        public If orElse(List<ZshCommand> body) {
            return new If(clauses, Optional.of(body));
        }
    }

    record Clause(List<ZshCommand> condition, List<ZshCommand> body) {
        public Clause {
            condition = List.copyOf(condition);
            body = List.copyOf(body);
        }
    }

    /**
     * {@code for name in words; do list; done}; with no words, {@code for name; do}, over {@code "$@"}. With more than
     * one variable, {@code for k v in ...} takes the words that many at a time.
     */
    record For(List<String> variables, Optional<List<ZshWord>> words, List<ZshCommand> body) implements ZshCommand {
        public For {
            variables = List.copyOf(variables);
            words = words.map(List::copyOf);
            body = List.copyOf(body);
            if (variables.isEmpty()) throw new IllegalArgumentException("a for with no variable");
        }
    }

    /** {@code repeat count do list done}. */
    record Repeat(ZshWord count, List<ZshCommand> body) implements ZshCommand {
        public Repeat {
            body = List.copyOf(body);
        }
    }

    /** {@code { list } always { list }}: the second list runs however the first one ends, as a finally does. */
    record Always(List<ZshCommand> body, List<ZshCommand> always) implements ZshCommand {
        public Always {
            body = List.copyOf(body);
            always = List.copyOf(always);
        }
    }

    /** {@code for ((init; test; update)); do list; done}, in arithmetic syntax. */
    record ArithmeticFor(String init, String test, String update, List<ZshCommand> body) implements ZshCommand {
        public ArithmeticFor {
            body = List.copyOf(body);
        }
    }

    /** {@code while list; do list; done}, or {@code until}. */
    record While(boolean until, List<ZshCommand> condition, List<ZshCommand> body) implements ZshCommand {
        public While {
            condition = List.copyOf(condition);
            body = List.copyOf(body);
        }
    }

    /** {@code case word in pattern|pattern) list ;; esac}. */
    record Case(ZshWord subject, List<CaseItem> items) implements ZshCommand {
        public Case {
            items = List.copyOf(items);
        }
    }

    enum CaseTerminator {
        BREAK(";;"), FALL_THROUGH(";&"), TEST_NEXT(";|");

        public final String token;

        CaseTerminator(String token) {
            this.token = token;
        }
    }

    record CaseItem(List<ZshWord> patterns, List<ZshCommand> body, CaseTerminator terminator) {
        public CaseItem {
            patterns = List.copyOf(patterns);
            body = List.copyOf(body);
            if (patterns.isEmpty()) throw new IllegalArgumentException("a case item with no pattern");
        }
    }

    /** {@code (( expression ))}, in arithmetic syntax. */
    record Arithmetic(String expression) implements ZshCommand {
    }

    /** {@code [[ condition ]]}. */
    record Conditional(ZshCondition condition) implements ZshCommand {
    }

    /**
     * {@code name() body}, or {@code function name body} with {@code keyword}; several names define one function under
     * each. The body is a compound command.
     */
    record Function(List<String> names, boolean keyword, ZshCommand body) implements ZshCommand {
        public Function {
            names = List.copyOf(names);
            if (names.isEmpty()) throw new IllegalArgumentException("a function with no name");
        }
    }

    /** {@code () { list } args}: a function defined, called with the arguments, and gone. Its locals stay in it. */
    record AnonymousFunction(List<ZshCommand> body, List<ZshWord> arguments) implements ZshCommand {
        public AnonymousFunction {
            body = List.copyOf(body);
            arguments = List.copyOf(arguments);
        }
    }

    /** A compound command with redirections: {@code { ...; } > file}, {@code ( ... ) 2>&1}, {@code done <<< "$x"}. */
    record Redirected(ZshCommand command, List<ZshRedirection> redirections) implements ZshCommand {
        public Redirected {
            redirections = List.copyOf(redirections);
            if (command instanceof Simple) throw new IllegalArgumentException("a simple command holds its own redirections");
        }
    }

    /** {@code # text}, one {@code #} per line of the text. */
    record Comment(String text) implements ZshCommand {
    }

    /** Lines of Zsh written by hand, such as a native body: each printed as it is, at the block's indentation. */
    record Raw(String text) implements ZshCommand {
    }

    /** An empty line. */
    record Blank() implements ZshCommand {
    }
}
