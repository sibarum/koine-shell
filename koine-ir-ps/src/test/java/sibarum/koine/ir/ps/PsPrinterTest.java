package sibarum.koine.ir.ps;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.ps.Ps.*;
import static sibarum.koine.ir.ps.PsExpression.BinaryOperator.*;

class PsPrinterTest {

    @Test
    void variablesInStringsAreBracedOnlyWhereTheTextWouldRunIntoThem() {
        assertEquals("\"${repo}: ${Seconds}s $out.err $env:USERPROFILE\\x\"",
                PsPrinter.print(dq(var("repo"), ": ", var("Seconds"), "s ", var("out"), ".err ", var("env:USERPROFILE"), "\\x")));
    }

    @Test
    void stringTextIsEscaped() {
        assertEquals("\"`n`\"call`\" `$x $ ``\"", PsPrinter.print(dq("\n\"call\" $x $ `")));
        assertEquals("\"`$$x\"", PsPrinter.print(dq("$", var("x"))));
        assertEquals("'it''s'", PsPrinter.print(sq("it's")));
    }

    @Test
    void aSubexpressionInAStringStaysOnOneLine() {
        assertEquals("\"at $(if ($head) { $head.Substring(0, 9) } else { 'nothing' })\"",
                PsPrinter.print(dq("at ", sub(if_(var("head"), block(invoke(var("head"), "Substring", num(0), num(9))))
                        .orElse(block(sq("nothing")))))));
    }

    @Test
    void aShortSimpleBodyGoesOnOneLineAndACompoundOneDoesNot() {
        assertEquals("if ($x) { continue }", PsPrinter.print(if_(var("x"), block(continue_()))));
        assertEquals("""
                foreach ($a in $b) {
                    if ($a) { break }
                }""", PsPrinter.print(foreach("a", var("b"), block(if_(var("a"), block(break_()))))));
    }

    @Test
    void parenthesesAreNodesNotInferred() {
        assertEquals("$a -ne 0 -or $b", PsPrinter.print(bin(bin(var("a"), NE, num(0)), OR, var("b"))));
        assertEquals("($a = @(f)).Count", PsPrinter.print(member(paren(assign(var("a"), arr(cmd("f")))), "Count")));
    }

    @Test
    void onlyAPipelinesFirstElementCanBeAnExpression() {
        assertThrows(IllegalArgumentException.class, () -> pipe(cmd("a"), var("b")));
    }

    /** preflight.ps1 rebuilt in the IR prints as expected/preflight.ps1: the original's syntax in the printer's layout. */
    @Test
    void preflightPrintsAsExpected() throws Exception {
        Path p = Path.of(PsPrinterTest.class.getClassLoader().getResource("expected/preflight.ps1").toURI());
        assertEquals(Files.readString(p).replace("\r\n", "\n"), PsPrinter.print(Preflight.script()));
    }
}
