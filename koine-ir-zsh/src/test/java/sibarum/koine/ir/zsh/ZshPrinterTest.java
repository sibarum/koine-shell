package sibarum.koine.ir.zsh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.zsh.Zsh.*;

class ZshPrinterTest {

    @Test
    void aParameterIsBracedWhereZshWouldReadOnIntoASubscriptOrModifier() {
        assertEquals("\"${only}: ${xs}[1] $r/x ${seconds}s\"",
                ZshPrinter.print(w(dq(p("only"), ": ", p("xs"), "[1] ", p("r"), "/x ", p("seconds"), "s"))));
    }

    @Test
    void flagsModifiersAndNestingPrintInZshsOrder() {
        assertEquals("${0:A:h}", ZshPrinter.print(w(modified("0", "A", "h"))));
        assertEquals("${(f)\"$(cmd x)\"}", ZshPrinter.print(w(param(dq(sub(cmd("cmd", "x")))).flags("f").build())));
        assertEquals("${${x}:h}", ZshPrinter.print(w(param(p("x")).modifiers("h").build())));
        assertEquals("${(j:, :)xs}", ZshPrinter.print(w(param("xs").flags("j:, :").build())));
        assertEquals("\"  - \"${^xs}", ZshPrinter.print(w(dq("  - "), param("xs").prefix(ZshParameter.Prefix.RC_EXPAND).build())));
        assertEquals("${#xs}", ZshPrinter.print(w(length("xs"))));
    }

    @Test
    void modifiersAndAnOperationDoNotMix() {
        assertThrows(IllegalArgumentException.class, () -> param("x").modifiers("h")
                .operation(new ZshParameter.Substring("1", Optional.empty())).build());
    }

    @Test
    void zshsOwnCommandsPrint() {
        assertEquals("{ a; } always { b; }",
                ZshPrinter.print(new ZshCommand.Always(body(cmd("a")), body(cmd("b")))));
        assertEquals("() { local x=1; print $x; } arg",
                ZshPrinter.print(new ZshCommand.AnonymousFunction(body(cmd("local", "x=1"), cmd("print", p("x"))), words("arg"))));
        assertEquals("repeat 3 do print hi; done",
                ZshPrinter.print(new ZshCommand.Repeat(w("3"), body(cmd("print", "hi")))));
        assertEquals("job &!", ZshPrinter.print(new ZshCommand.Background(cmd("job"), true)));
        assertEquals("for k v in a b; do print $k; done",
                ZshPrinter.print(new ZshCommand.For(List.of("k", "v"), Optional.of(words("a", "b")), body(cmd("print", p("k"))))));
    }

    @Test
    void aLiteralEscapesWhatZshWouldExpand() {
        assertEquals("\\=cmd a\\^b", ZshPrinter.print(cmd("=cmd", "a^b")));
    }

    /** build.zsh, the Zsh port of build.sh, rebuilt in the IR, prints as expected/build.zsh character for character. */
    @Test
    void buildZshPrintsAsExpected() throws Exception {
        Path p = Path.of(ZshPrinterTest.class.getClassLoader().getResource("expected/build.zsh").toURI());
        assertEquals(Files.readString(p).replace("\r\n", "\n"), ZshPrinter.print(BuildZsh.script()));
    }
}
