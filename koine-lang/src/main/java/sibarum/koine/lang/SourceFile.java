package sibarum.koine.lang;

import java.util.List;

/** What a {@code .koine} file holds: one script, or one module. */
public sealed interface SourceFile {

    String name();

    Pos pos();

    /**
     * {@code script Name<OS>(inputs)} and its body. {@code os} is the name between the angle brackets, which the
     * script's {@code on} branches are about.
     */
    record Script(String name, String os, List<Statement.Param> inputs, List<Statement> body, Pos pos)
            implements SourceFile {
        public Script {
            inputs = List.copyOf(inputs);
            body = List.copyOf(body);
        }
    }

    /** {@code module Name}, then its {@code def}s and records at the top level. */
    record Module(String name, boolean bundled, List<Statement> members, Pos pos) implements SourceFile {
        public Module {
            members = List.copyOf(members);
        }
    }
}
