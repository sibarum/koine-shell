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

    @Test
    void aRecordIsMadeAndReadByItsFields() {
        Type.Record point = new Type.Record("Point", List.of(new Type.Record.Field("x", Type.INT, false)));
        assertThrows(IllegalArgumentException.class, () -> new MakeRecord(point, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MakeRecord(point, List.of(new StringLit("1"))));
        Expr p = new MakeRecord(point, List.of(new IntLit(1)));
        assertEquals(Type.INT, new FieldOf(p, "x").type());
        assertThrows(IllegalArgumentException.class, () -> new FieldOf(p, "y"));
    }

    @Test
    void onlyDataIsReadAsARecord() {
        Type.Record point = new Type.Record("Point", List.of(new Type.Record.Field("x", Type.INT, false)));
        assertThrows(IllegalArgumentException.class, () -> new ReadAs(new StringLit("{}"), point));
    }

    @Test
    void inLooksForAStringInAStringOrAnItemInAList() {
        Expr words = new ListLit(Type.STRING, List.of(new StringLit("a")));
        assertEquals(Type.BOOL, new In(new StringLit("a"), words).type());
        assertThrows(IllegalArgumentException.class, () -> new In(new IntLit(1), words));
        assertThrows(IllegalArgumentException.class, () -> new In(new IntLit(1), new StringLit("1")));
    }

    @Test
    void aNativeFunctionTakesNoMapOrData() {
        FunctionSig f = new FunctionSig("f", List.of(new Var("m", Type.mapOf(Type.STRING))), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new NativeFunction(f, OS.Linux, ":"));
    }
}
