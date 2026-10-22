package sibarum.koine.ir.ps;

/** {@code > file}, {@code 2>> file}, {@code *> $null}, {@code 2>&1}. */
public sealed interface PsRedirection {

    enum Stream {
        OUTPUT("1"), ERROR("2"), WARNING("3"), VERBOSE("4"), DEBUG("5"), INFORMATION("6"), ALL("*");

        public final String token;

        Stream(String token) {
            this.token = token;
        }
    }

    /** {@code stream> target} or {@code stream>> target}; the output stream prints as a plain {@code >}. */
    record ToFile(Stream stream, boolean append, PsExpression target) implements PsRedirection {
    }

    /** {@code from>&to}, as in {@code 2>&1}. */
    record Merge(Stream from, Stream to) implements PsRedirection {
    }
}
