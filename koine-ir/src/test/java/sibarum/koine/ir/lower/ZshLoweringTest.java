package sibarum.koine.ir.lower;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.Probe;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement.*;
import sibarum.koine.ir.Std;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.zsh.ZshPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.Type.*;

class ZshLoweringTest {

    /**
     * The probe, with the standard library's Mac bodies, lowers to expected/probe.zsh: native functions after their
     * {@code local -h} prologue, lists passed as their length then their items, 1-based indexes, records as one
     * variable per field, data read with jq, captures with each fallback, and finally on the cleanup stack.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.zsh"), ZshPrinter.print(ZshLowering.lower(Probe.script(OS.Mac))));
    }

    @Test
    void zshArraysDoNotNest() {
        Var lists = new Var("lists", listOf(listOf(STRING)));
        Script s = new Script("nested", "", "", List.of(), List.of(
                new Set(lists, new ListLit(listOf(STRING), List.of(new ListLit(STRING, List.of(new StringLit("a"))))))));
        assertThrows(IllegalArgumentException.class, () -> ZshLowering.lower(s));
    }

    @Test
    void aNativeFunctionIsForItsOwnShell() {
        Script s = new Script("wrong", "", "", List.of(), List.of(Std.native_("Path", "parent", OS.Linux)));
        assertThrows(IllegalArgumentException.class, () -> ZshLowering.lower(s));
    }

    /** A test resource's text, with LF line breaks whatever the checkout gave it. */
    private static String expected(String name) throws Exception {
        Path p = Path.of(ZshLoweringTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
