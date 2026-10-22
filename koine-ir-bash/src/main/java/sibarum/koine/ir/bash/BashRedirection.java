package sibarum.koine.ir.bash;

import java.util.List;
import java.util.Optional;

/** A redirection; {@code fd} is the descriptor written before the operator, when there is one. */
public sealed interface BashRedirection {

    enum FileOperator {
        OUT(">"), APPEND(">>"), IN("<"), IN_OUT("<>"), CLOBBER(">|"), OUT_AND_ERR("&>"), APPEND_OUT_AND_ERR("&>>");

        public final String token;

        FileOperator(String token) {
            this.token = token;
        }
    }

    /** {@code > file}, {@code 2>> file}, {@code < file}, {@code &> file}. */
    record ToFile(Optional<Integer> fd, FileOperator operator, BashWord target) implements BashRedirection {
    }

    /** {@code 2>&1}, {@code >&2}, {@code <&3}, {@code >&-}: {@code target} is a descriptor or {@code -}. */
    record Duplicate(Optional<Integer> fd, boolean output, String target) implements BashRedirection {
    }

    /** {@code <<< word}. */
    record HereString(Optional<Integer> fd, BashWord word) implements BashRedirection {
    }

    /**
     * {@code <<EOF}, its lines, then {@code EOF}. With a quoted delimiter ({@code <<'EOF'}) nothing in the body
     * expands, and the body is one literal. Otherwise it holds literal text, parameters and command substitutions,
     * as double quotes do.
     *
     * @param stripTabs {@code <<-}: leading tabs are removed from each line
     */
    record HereDoc(Optional<Integer> fd, String delimiter, boolean quoted, boolean stripTabs, List<BashWordPart> body)
            implements BashRedirection {
        public HereDoc {
            body = List.copyOf(body);
            if (quoted && !body.stream().allMatch(p -> p instanceof BashWordPart.Literal))
                throw new IllegalArgumentException("nothing expands in a here-document with a quoted delimiter");
            for (BashWordPart p : body)
                if (!(p instanceof BashWordPart.Literal || p instanceof BashParameter
                        || p instanceof BashWordPart.CommandSubstitution || p instanceof BashWordPart.ArithmeticExpansion))
                    throw new IllegalArgumentException("not something a here-document holds: " + p);
        }
    }
}
