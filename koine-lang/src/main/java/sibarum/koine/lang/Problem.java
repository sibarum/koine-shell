package sibarum.koine.lang;

/** Something the checker found wrong, reported as {@code file:line:column: message}. */
public record Problem(String source, Pos pos, String message) {

    @Override
    public String toString() {
        return source + ":" + pos + ": " + message;
    }
}
