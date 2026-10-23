package sibarum.koine.ir.lower;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.Probe;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement.*;
import sibarum.koine.ir.Std;
import sibarum.koine.ir.Type;
import sibarum.koine.ir.Var;
import sibarum.koine.ir.bash.BashPrinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sibarum.koine.ir.Type.*;

class BashLoweringTest {

    /**
     * The probe, with the standard library's Linux bodies, lowers to expected/probe.sh: native functions after their
     * prologue, lists passed as their length then their items, records as one variable per field, data read with jq,
     * results through REPLY, reply and the exit status, captures with each fallback, and finally on the cleanup stack.
     */
    @Test
    void probeLowersAsExpected() throws Exception {
        assertEquals(expected("expected/probe.sh"), BashPrinter.print(BashLowering.lower(Probe.script(OS.Linux))));
    }

    @Test
    void aNativeFunctionIsForItsOwnShell() {
        Script s = new Script("wrong", "", "", List.of(), List.of(Std.native_("Path", "parent", OS.Mac)));
        assertThrows(IllegalArgumentException.class, () -> BashLowering.lower(s));
    }

    @Test
    void aListOfRecordsDoesNotFitYet() {
        Type.Record binary = new Type.Record("Binary", List.of(new Type.Record.Field("name", STRING, false)));
        Type.Record platform = new Type.Record("Platform", List.of(new Type.Record.Field("binaries", listOf(binary), false)));
        Var p = new Var("p", platform);
        Script s = new Script("records", "", "", List.of(), List.of(
                new Set(p, new MakeRecord(platform, List.of(new ListLit(binary, List.of()))))));
        assertThrows(IllegalArgumentException.class, () -> BashLowering.lower(s));
    }

    @Test
    void aFunctionTakesListsAnywhere() {
        Var a = new Var("a", listOf(STRING)), b = new Var("b", STRING), c = new Var("c", listOf(STRING));
        Script s = new Script("lists", "", "", List.of(), List.of(new Function(
                new sibarum.koine.ir.FunctionSig("f", List.of(a, b, c), Optional.empty()), List.of())));
        assertEquals("""
                f() { local -a a=("${@:2:$1}"); shift $(($1 + 1)); local b=$1; shift 1; local -a c=("${@:2:$1}"); }
                """, body(BashPrinter.print(BashLowering.lower(s))));
    }

    /** The script after its header and option parsing. */
    private static String body(String script) {
        return script.substring(script.indexOf("esac\ndone\n\n") + "esac\ndone\n\n".length());
    }

    /** A test resource's text, with LF line breaks whatever the checkout gave it. */
    private static String expected(String name) throws Exception {
        Path p = Path.of(BashLoweringTest.class.getClassLoader().getResource(name).toURI());
        return Files.readString(p).replace("\r\n", "\n");
    }
}
