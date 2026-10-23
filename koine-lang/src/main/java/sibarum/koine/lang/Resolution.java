package sibarum.koine.lang;

/** What a name, a member or a call resolved to when it was checked. */
public sealed interface Resolution {

    /** A variable or a parameter. */
    record Local(String name) implements Resolution {
    }

    /**
     * A call of a {@code def}, of the script or of a module. With {@code receiver}, the value before the dot is its
     * first argument: {@code line.words} is {@code String.words(line)}.
     */
    record Call(Checker.Member member, boolean receiver) implements Resolution {
    }

    /** A field of the record before the dot. */
    record Field(String name) implements Resolution {
    }

    /** A member built into the compiler: {@code isEmpty}, {@code keys}, {@code has}, {@code add} or {@code matches}. */
    record Builtin(String name) implements Resolution {
    }

    /** A record being made, {@code Binary(name: …, source: …)}. */
    record Construct(Type.Rec record) implements Resolution {
    }
}
