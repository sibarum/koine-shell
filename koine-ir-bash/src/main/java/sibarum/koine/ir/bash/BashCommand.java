package sibarum.koine.ir.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Bash command, simple or compound, and the lists that join them. Comments and blank lines are commands too, so
 * that a printed tree reads like the script it came from; a script's {@code --help} that prints its own header
 * comments needs them where they were.
 */
public sealed interface BashCommand {

    /** {@code a=1 b=2 name args... redirections}; assignments alone, or redirections alone, are commands too. */
    record Simple(List<BashAssignment> assignments, List<BashWord> words, List<BashRedirection> redirections)
            implements BashCommand {
        public Simple {
            assignments = List.copyOf(assignments);
            words = List.copyOf(words);
            redirections = List.copyOf(redirections);
            if (assignments.isEmpty() && words.isEmpty() && redirections.isEmpty())
                throw new IllegalArgumentException("an empty command");
        }

        public Simple redirect(BashRedirection... more) {
            List<BashRedirection> all = new ArrayList<>(redirections);
            all.addAll(List.of(more));
            return new Simple(assignments, words, all);
        }
    }

    /** {@code a | b | c}, or {@code ! a} with {@code negated}. */
    record Pipeline(boolean negated, List<BashCommand> commands) implements BashCommand {
        public Pipeline {
            commands = List.copyOf(commands);
            if (commands.isEmpty()) throw new IllegalArgumentException("an empty pipeline");
        }
    }

    /** {@code first && a || b ...}, left to right with equal precedence. */
    record AndOr(BashCommand first, List<Link> rest) implements BashCommand {
        public AndOr {
            rest = List.copyOf(rest);
            if (rest.isEmpty()) throw new IllegalArgumentException("an and-or list of one");
        }

        public AndOr and(BashCommand next) {
            return with(true, next);
        }

        public AndOr or(BashCommand next) {
            return with(false, next);
        }

        private AndOr with(boolean and, BashCommand next) {
            List<Link> all = new ArrayList<>(rest);
            all.add(new Link(and, next));
            return new AndOr(first, all);
        }
    }

    /** {@code && command}, or {@code || command}. */
    record Link(boolean and, BashCommand command) {
    }

    /** {@code command &}. */
    record Background(BashCommand command) implements BashCommand {
    }

    /** {@code { list; }}. */
    record BraceGroup(List<BashCommand> body) implements BashCommand {
        public BraceGroup {
            body = List.copyOf(body);
        }
    }

    /** {@code ( list )}. */
    record Subshell(List<BashCommand> body) implements BashCommand {
        public Subshell {
            body = List.copyOf(body);
        }
    }

    /** {@code if list; then list; elif list; then list; else list; fi}. */
    record If(List<Clause> clauses, Optional<List<BashCommand>> otherwise) implements BashCommand {
        public If {
            clauses = List.copyOf(clauses);
            otherwise = otherwise.map(List::copyOf);
            if (clauses.isEmpty()) throw new IllegalArgumentException("an if with no condition");
        }

        public If elif(List<BashCommand> condition, List<BashCommand> body) {
            List<Clause> all = new ArrayList<>(clauses);
            all.add(new Clause(condition, body));
            return new If(all, otherwise);
        }

        public If orElse(List<BashCommand> body) {
            return new If(clauses, Optional.of(body));
        }
    }

    record Clause(List<BashCommand> condition, List<BashCommand> body) {
        public Clause {
            condition = List.copyOf(condition);
            body = List.copyOf(body);
        }
    }

    /** {@code for name in words; do list; done}; with no words, {@code for name; do}, over {@code "$@"}. */
    record For(String variable, Optional<List<BashWord>> words, List<BashCommand> body) implements BashCommand {
        public For {
            words = words.map(List::copyOf);
            body = List.copyOf(body);
        }
    }

    /** {@code for ((init; test; update)); do list; done}, in arithmetic syntax. */
    record ArithmeticFor(String init, String test, String update, List<BashCommand> body) implements BashCommand {
        public ArithmeticFor {
            body = List.copyOf(body);
        }
    }

    /** {@code while list; do list; done}, or {@code until}. */
    record While(boolean until, List<BashCommand> condition, List<BashCommand> body) implements BashCommand {
        public While {
            condition = List.copyOf(condition);
            body = List.copyOf(body);
        }
    }

    /** {@code case word in pattern|pattern) list ;; esac}. */
    record Case(BashWord subject, List<CaseItem> items) implements BashCommand {
        public Case {
            items = List.copyOf(items);
        }
    }

    enum CaseTerminator {
        BREAK(";;"), FALL_THROUGH(";&"), TEST_NEXT(";;&");

        public final String token;

        CaseTerminator(String token) {
            this.token = token;
        }
    }

    record CaseItem(List<BashWord> patterns, List<BashCommand> body, CaseTerminator terminator) {
        public CaseItem {
            patterns = List.copyOf(patterns);
            body = List.copyOf(body);
            if (patterns.isEmpty()) throw new IllegalArgumentException("a case item with no pattern");
        }
    }

    /** {@code (( expression ))}, in arithmetic syntax. */
    record Arithmetic(String expression) implements BashCommand {
    }

    /** {@code [[ condition ]]}. */
    record Conditional(BashCondition condition) implements BashCommand {
    }

    /** {@code name() body}, or {@code function name body} with {@code keyword}. The body is a compound command. */
    record Function(String name, boolean keyword, BashCommand body) implements BashCommand {
    }

    /** A compound command with redirections: {@code { ...; } > file}, {@code ( ... ) 2>&1}, {@code done <<< "$x"}. */
    record Redirected(BashCommand command, List<BashRedirection> redirections) implements BashCommand {
        public Redirected {
            redirections = List.copyOf(redirections);
            if (command instanceof Simple) throw new IllegalArgumentException("a simple command holds its own redirections");
        }
    }

    /** {@code # text}, one {@code #} per line of the text. */
    record Comment(String text) implements BashCommand {
    }

    /** An empty line. */
    record Blank() implements BashCommand {
    }
}
