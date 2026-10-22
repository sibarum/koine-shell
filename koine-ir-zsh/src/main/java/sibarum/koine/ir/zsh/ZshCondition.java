package sibarum.koine.ir.zsh;

/** What goes between {@code [[} and {@code ]]}. */
public sealed interface ZshCondition {

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
        MATCHES("=="), MATCHES_SINGLE("="), NOT_MATCHES("!="),
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

    record Unary(UnaryTest test, ZshWord operand) implements ZshCondition {
    }

    record Binary(ZshWord left, BinaryTest test, ZshWord right) implements ZshCondition {
    }

    /** A word on its own: true when it is not empty. */
    record Word(ZshWord word) implements ZshCondition {
    }

    record Not(ZshCondition operand) implements ZshCondition {
    }

    record And(ZshCondition left, ZshCondition right) implements ZshCondition {
    }

    record Or(ZshCondition left, ZshCondition right) implements ZshCondition {
    }

    record Paren(ZshCondition inner) implements ZshCondition {
    }
}
