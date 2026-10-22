package sibarum.koine.lang;

/** A mistake in a Koine source file, reported as {@code file:line:column: message}. */
public final class KoineException extends RuntimeException {

    private final String source;
    private final Pos pos;
    private final String problem;

    public KoineException(String source, Pos pos, String problem) {
        super(source + ":" + pos + ": " + problem);
        this.source = source;
        this.pos = pos;
        this.problem = problem;
    }

    public String source() {
        return source;
    }

    public Pos pos() {
        return pos;
    }

    /** The message without the file and position. */
    public String problem() {
        return problem;
    }
}
