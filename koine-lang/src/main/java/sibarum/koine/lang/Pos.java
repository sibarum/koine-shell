package sibarum.koine.lang;

/** Where something starts in a source file, counted from 1. */
public record Pos(int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
