package sibarum.koine.ir;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Expr.*;
import sibarum.koine.ir.Statement.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrTest {

    @Test
    void preflightBuilds() {
        Script s = Preflight.script();
        assertEquals("preflight", s.name());
        assertEquals(3, s.parameters().size());
    }

    @Test
    void aValueMustHaveItsVariablesType() {
        Var n = new Var("n", Type.INT);
        assertThrows(IllegalArgumentException.class, () -> new Set(n, new StringLit("8")));
    }

    @Test
    void numbersAndStringsDoNotCompare() {
        assertThrows(IllegalArgumentException.class,
                () -> new Compare(new IntLit(0), Comparison.EQ, new StringLit("0")));
        assertThrows(IllegalArgumentException.class,
                () -> new Compare(new StringLit("a"), Comparison.LT, new StringLit("b")));
    }

    @Test
    void aLoopVariableHasTheItemsType() {
        Expr strings = new ListLit(Type.STRING, List.of(new StringLit("a")));
        assertThrows(IllegalArgumentException.class, () -> new ForEach(new Var("x", Type.INT), strings, List.of()));
    }

    @Test
    void aCallMatchesItsFunction() {
        FunctionSig f = new FunctionSig("f", List.of(new Var("a", Type.STRING)), Optional.of(Type.STRING));
        assertThrows(IllegalArgumentException.class, () -> new Call(f, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Call(f, List.of(new IntLit(1))));
        FunctionSig g = new FunctionSig("g", List.of(), Optional.empty());
        assertThrows(IllegalStateException.class, () -> new Call(g, List.of()).type());
    }

    @Test
    void aFlagHasNoDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> new Script.Param(new Var("x", Type.BOOL), Optional.of(new BoolLit(true)), ""));
    }
}
