package sibarum.koine.ir.bash;

/** What goes between {@code [[} and {@code ]]}. */
public sealed interface BashCondition {

    enum UnaryTest {
        EXISTS("-e"), FILE("-f"), DIRECTORY("-d"), SYMLINK("-L"), READABLE("-r"), WRITABLE("-w"), EXECUTABLE("-x"),
        NOT_EMPTY_FILE("-s"), TERMINAL("-t"), SET("-v"), OPTION("-o"), EMPTY("-z"), NOT_EMPTY("-n");

        public final String token;

        UnaryTest(String token) {
            this.token = token;
        }
    }

    enum BinaryTest {
        /** The right side is a pattern, unless quoted. */
        MATCHES("=="), NOT_MATCHES("!="),
        /** The right side is an extended regular expression. */
        REGEX("=~"),
        BEFORE("<"), AFTER(">"),
        EQ("-eq"), NE("-ne"), LT("-lt"), LE("-le"), GT("-gt"), GE("-ge"),
        NEWER("-nt"), OLDER("-ot"), SAME_FILE("-ef");

        public final String token;

        BinaryTest(String token) {
            this.token = token;
        }
    }

    record Unary(UnaryTest test, BashWord operand) implements BashCondition {
    }

    record Binary(BashWord left, BinaryTest test, BashWord right) implements BashCondition {
    }

    /** A word on its own: true when it is not empty. */
    record Word(BashWord word) implements BashCondition {
    }

    record Not(BashCondition operand) implements BashCondition {
    }

    record And(BashCondition left, BashCondition right) implements BashCondition {
    }

    record Or(BashCondition left, BashCondition right) implements BashCondition {
    }

    record Paren(BashCondition inner) implements BashCondition {
    }
}
