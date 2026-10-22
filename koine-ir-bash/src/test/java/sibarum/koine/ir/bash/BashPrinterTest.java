package sibarum.koine.ir.bash;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.bash.BashParameter.Default;
import sibarum.koine.ir.bash.BashParameter.Remove;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.bash.Bash.*;
import static sibarum.koine.ir.bash.BashParameter.DefaultKind.USE_ALTERNATE;

class BashPrinterTest {

    @Test
    void parametersAreBracedOnlyWhereBashNeedsIt() {
        assertEquals("\"$root/$r ${seconds}s $stamp.tmp ${#missing[@]} ${2:?--only needs a value}\"",
                BashPrinter.print(w(dq(p("root"), "/", p("r"), " ", p("seconds"), "s ", p("stamp"), ".tmp ",
                        length("missing", "@"), " ", orFail("2", "--only needs a value")))));
        assertEquals("${libs[@]+\"${libs[@]}\"}",
                BashPrinter.print(w(op("libs", "@", new Default(false, USE_ALTERNATE, w(dq(p("libs", "@"))))))));
        assertEquals("${joined%,*}", BashPrinter.print(w(op("joined", new Remove(true, false, w(",", glob("*")))))));
    }

    @Test
    void literalsAreEscapedForWhereTheyStand() {
        assertEquals("a\\ b\\;c\\*", BashPrinter.print(w("a b;c*")));
        assertEquals("[", BashPrinter.print(w("[")));
        assertEquals("\"\\$x \\\"q\\\" \\\\\"", BashPrinter.print(w(dq("$x \"q\" \\"))));
        assertEquals("''", BashPrinter.print(w()));
        assertEquals("$'\\t'", BashPrinter.print(w(ansi("\t"))));
    }

    @Test
    void aCommandSubstitutionOfASubshellIsNotArithmetic() {
        assertEquals("$( (cd x && pwd))",
                BashPrinter.print(w(sub(subshell(and(cmd("cd", "x"), cmd("pwd")))))));
    }

    @Test
    void aShortBodyGoesOnOneLine() {
        assertEquals("[ -f x ] || { echo no >&2; exit 1; }",
                BashPrinter.print(or(cmd("[", "-f", "x", "]"), group(cmd("echo", "no").redirect(toErr()), cmd("exit", "1")))));
        assertEquals("{ cmd & sleep 1; }", BashPrinter.print(group(bg(cmd("cmd")), cmd("sleep", "1"))));
        assertEquals("{ sleep 1; cmd & }", BashPrinter.print(group(cmd("sleep", "1"), bg(cmd("cmd")))));
    }

    @Test
    void aHereDocumentsBodyFollowsItsLine() {
        assertEquals("x=$(python3 - <<'EOF'\nprint(1)\nEOF\n)",
                BashPrinter.print(assigns(set("x", sub(cmd("python3", "-").redirect(hereDoc("EOF", "print(1)\n")))))));
    }

    @Test
    void aSingleQuotedStringCannotHoldASingleQuote() {
        assertThrows(IllegalArgumentException.class, () -> sq("it's"));
    }


    /** build.sh rebuilt in the IR prints as expected/build.sh: the original's syntax in the printer's layout. */
    @Test
    void buildShPrintsAsExpected() throws Exception {
        assertEquals(expected("expected/build.sh"), BashPrinter.print(BuildSh.script()));
    }

    /** The same for local-linux.sh: here-documents of both kinds, a here-string, a read loop. */
    @Test
    void localLinuxShPrintsAsExpected() throws Exception {
        assertEquals(expected("expected/local-linux.sh"), BashPrinter.print(LocalLinuxSh.script()));
    }

    /** A test resource's text, with LF line breaks whatever the checkout gave it. */
    private static String expected(String name) throws Exception {
        Path p = Path.of(BashPrinterTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
