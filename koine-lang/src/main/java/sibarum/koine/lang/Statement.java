package sibarum.koine.lang;

import java.util.List;
import java.util.Optional;

/** A statement as written. Blocks are lists of statements, in the order they appear. */
public sealed interface Statement {

    Pos pos();

    record Let(String name, Expr value, Pos pos) implements Statement {
    }

    /** {@code name = …} or {@code map[key] = …}. The target is a {@link Expr.Name} or an {@link Expr.Index}. */
    record Assign(Expr target, Expr value, Pos pos) implements Statement {
    }

    /** {@code if}, its {@code else if}s in order, and an {@code else} that may be empty. */
    record If(List<Branch> branches, List<Statement> otherwise, Pos pos) implements Statement {
        public If {
            branches = List.copyOf(branches);
            otherwise = List.copyOf(otherwise);
        }

        public record Branch(Expr condition, List<Statement> body) {
            public Branch {
                body = List.copyOf(body);
            }
        }
    }

    /** Branches by OS when compiling. */
    record On(List<String> systems, List<Statement> body, Pos pos) implements Statement {
        public On {
            systems = List.copyOf(systems);
            body = List.copyOf(body);
        }
    }

    record For(String item, Expr source, List<Statement> body, Pos pos) implements Statement {
        public For {
            body = List.copyOf(body);
        }
    }

    record While(Expr condition, List<Statement> body, Pos pos) implements Statement {
        public While {
            body = List.copyOf(body);
        }
    }

    record Within(Expr directory, List<Statement> body, Pos pos) implements Statement {
        public Within {
            body = List.copyOf(body);
        }
    }

    /** {@code try `…`} on its own: runs the command and ignores its exit status. */
    record Try(Expr.Command command, Pos pos) implements Statement {
    }

    record Fail(Expr message, Pos pos) implements Statement {
    }

    record Step(String name, List<Statement> body, Pos pos) implements Statement {
        public Step {
            body = List.copyOf(body);
        }
    }

    record Return(Optional<Expr> value, Pos pos) implements Statement {
    }

    record Print(Expr message, Pos pos) implements Statement {
    }

    /** A call or a literal command, for what it does. */
    record Run(Expr expr, Pos pos) implements Statement {
    }

    /**
     * {@code def}. Its body is Koine, or a set of branches by OS, each an {@link On} or a {@link Native}; telling the
     * two apart, and checking every OS is covered, is the checker's job.
     */
    record Def(String name, List<Param> parameters, Optional<TypeRef> result, boolean bundled, List<Statement> body,
               Pos pos) implements Statement {
        public Def {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
        }
    }

    /** A member's body in a shell's own language, copied into the output as it is. */
    record Native(List<String> systems, boolean bundled, String body, Pos pos) implements Statement {
        public Native {
            systems = List.copyOf(systems);
        }
    }

    record Record(String name, List<Field> fields, Pos pos) implements Statement {
        public Record {
            fields = List.copyOf(fields);
        }
    }

    /** A record's field. One marked {@code ?} may be missing. */
    record Field(String name, TypeRef type, boolean optional, Pos pos) {
    }

    /** A parameter of a script or a {@code def}. A script's own {@code def}s may leave the type out. */
    record Param(String name, Optional<TypeRef> type, Optional<Expr> defaultValue, Pos pos) {
    }
}
