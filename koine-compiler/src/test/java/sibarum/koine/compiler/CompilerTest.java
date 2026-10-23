package sibarum.koine.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sibarum.koine.ir.Expr;
import sibarum.koine.ir.OS;
import sibarum.koine.ir.PreflightAccess;
import sibarum.koine.ir.Script;
import sibarum.koine.ir.Statement;
import sibarum.koine.ir.bash.BashPrinter;
import sibarum.koine.ir.lower.BashLowering;
import sibarum.koine.ir.lower.PsLowering;
import sibarum.koine.ir.lower.ZshLowering;
import sibarum.koine.ir.ps.PsPrinter;
import sibarum.koine.ir.zsh.ZshPrinter;
import sibarum.koine.lang.Library;
import sibarum.koine.lang.StandardLibrary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompilerTest {

    /** The example in docs/language.md, which koine-lang's tests parse and check. */
    private static final Path PREFLIGHT = Path.of("../koine-lang/src/test/resources/examples/preflight.koine");

    /**
     * preflight.koine compiles, for Windows, to the IR that koine-ir's Preflight.java builds by hand. The lowered
     * PowerShell is compared first, since a difference there is easier to read than one between two IR trees.
     */
    @Test
    void preflightCompilesToTheHandBuiltIr() throws IOException {
        Script compiled = Compiler.compile("preflight", Files.readString(PREFLIGHT), OS.Windows);
        Script expected = PreflightAccess.script();
        assertEquals(PsPrinter.print(PsLowering.lower(expected)), PsPrinter.print(PsLowering.lower(compiled)));
        assertEquals(expected, compiled);
    }

    /**
     * examples/hello.koine compiles and lowers for every OS. Its PowerShell and Bash were run by hand, and print the
     * same lines, and fail the same way with {@code --name stop}.
     */
    @Test
    void oneScriptCompilesForEveryOS() throws IOException {
        String hello = Files.readString(Path.of("src/test/resources/examples/hello.koine"));
        PsPrinter.print(PsLowering.lower(Compiler.compile("hello", hello, OS.Windows)));
        BashPrinter.print(BashLowering.lower(Compiler.compile("hello", hello, OS.Linux)));
        ZshPrinter.print(ZshLowering.lower(Compiler.compile("hello", hello, OS.Mac)));
    }

    /** A step inside a step: each expansion's own variable gets a name of its own, while it runs. */
    @Test
    void nestedExpansionsKeepTheirVariablesApart() {
        Script s = Compiler.compile("s", """
                script S<OS>
                  step One
                    step Two
                      print 'in'
                  step Three
                    print 'after'
                """, OS.Linux);
        assertEquals(List.of("outer", "outer2", "outer"), sets(s.body()).stream()
                .map(x -> x.var().name()).filter(n -> n.startsWith("outer")).toList());
    }

    /**
     * An argument a block-taking member uses twice, and that is not a literal or a variable, is set in a variable
     * first, so that it runs once.
     */
    @Test
    void anArgumentUsedTwiceIsSetOnce() {
        Library library = name -> name.equals("Twice") ? Optional.of("""
                module Twice

                def show(label: String, body: Block)
                  print label
                  body
                  print label
                """) : StandardLibrary.INSTANCE.module(name);
        Script s = Compiler.compile("s", """
                script S<OS>
                  Twice.show(Path.join('a', 'b'))
                    print 'inside'
                """, library, OS.Mac);
        Statement.Set label = sets(s.body()).getFirst();
        assertEquals("label", label.var().name());
        assertEquals(List.of(new Statement.Print(new Expr.Ref(label.var())), new Statement.Print(new Expr.StringLit("inside")),
                new Statement.Print(new Expr.Ref(label.var()))), s.body().subList(s.body().size() - 3, s.body().size()));
    }

    @Test
    void aScriptCompilesOnlyForItsOSes() {
        assertThrows(IllegalArgumentException.class, () -> Compiler.compile("s", "script S<Windows>\n  print 'x'\n", OS.Linux));
        Compiler.CompileException e = assertThrows(Compiler.CompileException.class,
                () -> Compiler.compile("s", "script S<OS>\n  print nmae\n", OS.Linux));
        assertEquals("s.koine:2:9: no name nmae", e.getMessage());
    }

    /** Every Set at any depth, in order. */
    private static List<Statement.Set> sets(List<Statement> body) {
        List<Statement.Set> out = new ArrayList<>();
        for (Statement st : body)
            switch (st) {
                case Statement.Set x -> out.add(x);
                case Statement.Finally x -> {
                    out.addAll(sets(x.body()));
                    out.addAll(sets(x.cleanup()));
                }
                case Statement.If x -> {
                    out.addAll(sets(x.then()));
                    out.addAll(sets(x.otherwise()));
                }
                default -> {
                }
            }
        return out;
    }
}
