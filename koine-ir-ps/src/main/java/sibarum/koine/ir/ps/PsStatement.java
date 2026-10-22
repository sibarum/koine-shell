package sibarum.koine.ir.ps;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A PowerShell statement. An expression or command on its own is a pipeline of one. Comments and blank lines are
 * statements too, so that a printed tree reads like the script it came from.
 */
public sealed interface PsStatement {

    /** {@code a | b | c}. Only the first element may be an expression. */
    record Pipeline(List<PsPipelineElement> elements) implements PsStatement {
        public Pipeline {
            elements = List.copyOf(elements);
            if (elements.isEmpty()) throw new IllegalArgumentException("an empty pipeline");
            for (PsPipelineElement e : elements.subList(1, elements.size()))
                if (!(e instanceof PsCommand))
                    throw new IllegalArgumentException("only a pipeline's first element can be an expression: " + e);
        }
    }

    /**
     * {@code target op value}. The value is a statement: {@code $x = foreach (...) {...}} and
     * {@code $x = if (...) {...}} are assignments too.
     */
    record Assignment(PsExpression target, AssignmentOperator operator, PsStatement value) implements PsStatement {
    }

    enum AssignmentOperator {
        ASSIGN("="), ADD("+="), SUBTRACT("-="), MULTIPLY("*="), DIVIDE("/="), REMAINDER("%=");

        public final String token;

        AssignmentOperator(String token) {
            this.token = token;
        }
    }

    /** {@code if (c) {...} elseif (c) {...} else {...}}. A condition is a pipeline, not just an expression. */
    record If(List<Clause> clauses, Optional<PsBlock> otherwise) implements PsStatement {
        public If {
            clauses = List.copyOf(clauses);
            if (clauses.isEmpty()) throw new IllegalArgumentException("an if with no condition");
        }

        public If elseIf(PsStatement condition, PsBlock body) {
            List<Clause> all = new ArrayList<>(clauses);
            all.add(new Clause(condition, body));
            return new If(all, otherwise);
        }

        public If orElse(PsBlock body) {
            return new If(clauses, Optional.of(body));
        }
    }

    record Clause(PsStatement condition, PsBlock body) {
    }

    /** {@code foreach ($v in collection) {...}}. */
    record Foreach(PsExpression.Variable variable, PsStatement collection, PsBlock body) implements PsStatement {
    }

    /** {@code while (condition) {...}}. */
    record While(PsStatement condition, PsBlock body) implements PsStatement {
    }

    /** {@code function Name($a, $b) {...}}; with no parameters, {@code function Name {...}}. */
    record Function(String name, List<PsParameter> parameters, PsBlock body) implements PsStatement {
        public Function {
            parameters = List.copyOf(parameters);
        }
    }

    /** {@code try {...} catch [T] {...} finally {...}}. */
    record Try(PsBlock body, List<Catch> catches, Optional<PsBlock> fin) implements PsStatement {
        public Try {
            catches = List.copyOf(catches);
            if (catches.isEmpty() && fin.isEmpty()) throw new IllegalArgumentException("a try with no catch or finally");
        }
    }

    /** {@code catch [T1], [T2] {...}}; with no types it catches everything. */
    record Catch(List<PsTypeName> types, PsBlock body) {
        public Catch {
            types = List.copyOf(types);
        }
    }

    record Throw(Optional<PsStatement> value) implements PsStatement {
    }

    record Return(Optional<PsStatement> value) implements PsStatement {
    }

    record Exit(Optional<PsStatement> value) implements PsStatement {
    }

    record Break() implements PsStatement {
    }

    record Continue() implements PsStatement {
    }

    /** {@code # text}, one {@code #} per line of the text. */
    record Comment(String text) implements PsStatement {
    }

    /** {@code <#text#>}, verbatim. */
    record BlockComment(String text) implements PsStatement {
    }

    /** An empty line. */
    record Blank() implements PsStatement {
    }
}
