package sibarum.koine.lang;

import java.util.List;

/** What a {@code .koine} file holds: one script, or one module. */
public sealed interface SourceFile {

    String name();

    Pos pos();

    /**
     * {@code script Name<OS>(inputs)} and its body. {@code systems} are the names between the angle brackets as
     * written: {@code OS} for all three, or the OSes the script is for. A {@code bootstrap} script is an installer.
     * {@code doc} is the comment directly above the script line, or empty.
     */
    record Script(String name, List<String> systems, boolean bootstrap, String doc, List<Statement.Param> inputs,
                  List<Statement> body, Pos pos) implements SourceFile {
        public Script {
            systems = List.copyOf(systems);
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
