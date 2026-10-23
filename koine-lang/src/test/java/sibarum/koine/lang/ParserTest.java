package sibarum.koine.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParserTest {

    // ---- the examples in docs/language.md -----------------------------------------------------------------------

    /** Each example parses to the tree in {@code expected/}, which was read through once by hand. */
    @ParameterizedTest
    @ValueSource(strings = {"preflight", "Python", "Clock", "Process"})
    void theExamplesParse(String name) {
        SourceFile file = Parser.parse(name + ".koine", resource("examples/" + name + ".koine"));
        assertEquals(resource("expected/" + name + ".sexp"), SyntaxPrinter.print(file));
    }

    // ---- literal commands ---------------------------------------------------------------------------------------

    @Test
    void aCommandWithoutAPrefixIsPlain() {
        assertEquals("(cmd [find] [.] [-exec] [rm] [{}] [;])", expr("`find . -exec rm {} ;`"));
        Expr.Command c = (Expr.Command) parsed("`echo {x} \\n`");
        assertEquals(List.of(Word.Literal.class), c.words().get(1).parts().stream().map(Object::getClass).toList());
        assertEquals("\\n", ((Word.Literal) c.words().get(2).parts().getFirst()).text());
    }

    @Test
    void anInsertIsOneWordAndJoinsTheTextBesideIt() {
        assertEquals("(cmd {} [git] [-C] [{checkout}] [rev-parse] [HEAD])", expr("{}`git -C {checkout} rev-parse HEAD`"));
        assertEquals("(cmd {} [cp] [{a} .bak] [--log= {(. x log)}])", expr("{}`cp {a}.bak --log={x.log}`"));
    }

    @Test
    void quotesGroupWordsAndExpandNothing() {
        assertEquals("(cmd [jq] ['.a | .b'] [\"{x}\"])", expr("`jq '.a | .b' \"{x}\"`"));
        assertEquals("(cmd [echo] [\"it's\"] ['say \"hi\"'])", expr("`echo \"it's\" 'say \"hi\"'`"));
    }

    @Test
    void twoCharactersBeforeTheBackticksChooseTheBrackets() {
        assertEquals("(cmd <> [jq] [-r] ['.members[] | {name: .config}'] [<suiteFile>])",
                expr("<>`jq -r '.members[] | {name: .config}' <suiteFile>`"));
        assertEquals("(cmd () [x] [(a)] [{}])", expr("()`x (a) {}`"));
    }

    @Test
    void anyTwoSymbolsKoineDoesNotUseCanBeTheBrackets() {
        assertEquals("(cmd %% [cmd] [/c] [%(. d name)%] [{}])", expr("%%`cmd /c %d.name% {}`"));
        assertEquals("(cmd ^| [echo] [$HOME] [^x|])", expr("^|`echo $HOME ^x|`"));
    }

    @Test
    void aLongerRunOfBackticksLetsACommandHoldShorterOnes() {
        assertEquals("(cmd [powershell] [-Command] [\"Write-Host `\"])",
                expr("``powershell -Command \"Write-Host `\"``"));
        assertEquals("(cmd [a`b] [c```d])", expr("``a`b c```d``"));
    }

    @Test
    void aCommandIsAStatementAValueOrACondition() {
        assertEquals("(run (cmd [build.cmd]))\n", statement("`build.cmd`"));
        assertEquals("(let head (cmd [git] [rev-parse] [HEAD]))\n", statement("let head = `git rev-parse HEAD`"));
        assertEquals("(try (cmd [git] [status]))\n", statement("try `git status`"));
        assertEquals("(not (try? (cmd {} [{python}] [--check])))", expr("not try {}`{python} --check`"));
    }

    @Test
    void anEscapeCharacterInACommandMakesAnyCharacterLiteral() {
        assertEquals("(cmd \\ [echo] [a b] [`] ['it's'])", expr("\\`echo a\\ b \\` 'it\\'s'`"));
        assertEquals("(cmd {}^ [echo] [{] [{x}] [a^b])", expr("{}^`echo ^{ {x} a^^b`"));
    }

    // ---- strings ------------------------------------------------------------------------------------------------

    @Test
    void aStringWithoutAPrefixIsPlain() {
        Expr.Str s = (Expr.Str) parsed("'{x} \\n {'");
        assertEquals(List.of(new Expr.Str.Text("{x} \\n {", s.parts().getFirst().pos())), s.parts());
        assertEquals("'^[[:space:]]*(#|$)'", expr("'^[[:space:]]*(#|$)'"));
        assertEquals("'^[0-9]{3}$'", expr("'^[0-9]{3}$'"));
    }

    @Test
    void aStringEndsAtAsManyQuotesAsOpenedIt() {
        assertEquals("'''it's'''", expr("'''it's'''"));
        assertEquals("'a '' b'", expr("'a '' b'"));
        assertEquals("\"\"say \"hi\" now\"\"", expr("\"\"say \"hi\" now\"\""));
        assertEquals("(cmd [echo] [''it's''] ['x'])", expr("`echo ''it's'' 'x'`"));
    }

    @Test
    void stringsInsertValuesAndAnInsertCanHoldEitherQuote() {
        assertEquals("{}\"{(. app name)} is up\"", expr("{}\"{app.name} is up\""));
        assertEquals("{}\"built: {(call (. apps join) ', ')}\"", expr("{}\"built: {apps.join(', ')}\""));
        assertEquals("{}\"a {(call f {}\"{b}\")} c\"", expr("{}\"a {f({}\"{b}\")} c\""));
    }

    @Test
    void anEscapeCharacterInAStringMakesTheNextCharacterLiteral() {
        assertEquals("\\'it's'", expr("\\'it\\'s'"));
        assertEquals("{}\\\"{name} said \"hi\" {x}\"", expr("{}\\\"{name} said \\\"hi\\\" \\{x}\""));
        assertEquals("%'50%'", expr("%'50%%'"));
    }

    @Test
    void stringsChooseTheirBracketsAsCommandsDo() {
        assertEquals("<>'^[0-9]{3}$ <(. x n)>'", expr("<>'^[0-9]{3}$ <x.n>'"));
        assertEquals("$$\"{a} costs $price$\"", expr("$$\"{a} costs $price$\""));
        assertEquals("()'{ ((call f <>'<y>')) }'", expr("()'{ (f(<>'<y>')) }'"));
        Expr.Str s = (Expr.Str) parsed("<>'{3}<n>'");
        assertEquals(List.of(Expr.Str.Text.class, Expr.Str.Insert.class), s.parts().stream().map(Object::getClass).toList());
    }

    @Test
    void koinesOwnSymbolsBeforeAStringKeepTheirMeaning() {
        assertEquals("[['a'] ['b']]", expr("[['a'], ['b']]"));
        assertEquals("(== a 'x')", expr("a=='x'"));
        assertEquals("(call f a ['x'])", expr("f(a,['x'])"));
        assertEquals("(call f 'x')", expr("f(('x'))"));
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    @Test
    void notBindsLooserThanComparisonAndAndLooserStill() {
        assertEquals("(or (and (not (in 'x' line)) (== a b)) c)", expr("not 'x' in line and a == b or c"));
    }

    @Test
    void asBindsLooserThanMembers() {
        assertEquals("(. (as (call (. Json read) m) Manifest) latest)", expr("(Json.read(m) as Manifest).latest"));
    }

    @Test
    void recordsAreMadeWithNamedArguments() {
        assertEquals("(call Binary name: 'app' source: 'app.exe')", expr("Binary(name: 'app', source: 'app.exe')"));
    }

    @Test
    void listsAndMapsCanSpanLines() {
        assertEquals("(let xs ['a' 'b'])\n", statement("let xs = [\n  'a',\n  'b',\n]"));
        assertEquals("(let m {'a': 1})\n", statement("let m = {'a': 1}"));
    }

    // ---- blocks -------------------------------------------------------------------------------------------------

    @Test
    void elseIfChains() {
        assertEquals("""
                (script S <OS>)
                  (if (== a 1))
                    (print 'one')
                  (else if (== a 2))
                    (print 'two')
                  (else)
                    (print 'many')
                """, SyntaxPrinter.print(Parser.parse("s.koine", """
                script S<OS>
                  if a == 1
                    print 'one'
                  else if a == 2
                    print 'two'
                  else
                    print 'many'
                """)));
    }

    @Test
    void aNativeBodyIsTakenAsItIsUpToWhereTheIndentationEnds() {
        assertEquals("""
                (module M)
                  (def f)
                    (native Windows)
                      | if ($x) {
                      |     # not a comment to Koine
                      |
                      |     Write-Host `"q`"
                      | }
                    (native Linux Mac bundled)
                      | echo "{x}"
                """, SyntaxPrinter.print(Parser.parse("M.koine", """
                module M

                def f
                  native Windows
                    if ($x) {
                        # not a comment to Koine

                        Write-Host `"q`"
                    }

                  native Linux, Mac bundled
                    echo "{x}"
                """)));
    }

    // ---- mistakes -----------------------------------------------------------------------------------------------

    @Test
    void mistakesSayWhereTheyAre() {
        assertError("s.koine:2:3: the command is not closed: it needs `` on this line",
                "script S<OS>\n  ``echo `\n");
        assertError("s.koine:2:9: the quote is not closed: it needs ' before the command ends",
                "script S<OS>\n  `echo 'a b`\n");
        assertError("s.koine:2:8: names cannot contain _", "script S<OS>\n  let a_b = 1\n");
        assertError("s.koine:3:3: else without an if before it", "script S<OS>\n  print 1\n  else\n    print 2\n");
        assertError("s.koine:2:9: the string is not closed: it needs '' on this line; there is no empty string literal: "
                + "write String.empty", "script S<OS>\n  print ''\n");
        assertError("s.koine:2:18: the quote is not closed: it needs '' before the command ends; there is no empty "
                + "string literal: write String.empty", "script S<OS>\n  `git commit -m ''`\n");
        assertError("s.koine:2:12: the escape character \\ needs a character after it", "script S<OS>\n  print \\'a\\\n");
        assertError("s.koine:2:9: the escape character % is one of the value brackets; choose another",
                "script S<OS>\n  print %%%'a'\n");
        assertError("s.koine:2:3: native goes directly in a def's body", "script S<OS>\n  native Windows\n    x\n");
        assertError("s.koine:3:3: native Windows has no body", "module M\ndef f\n  native Windows\n");
        assertError("s.koine:2:3: only a name, or an item such as map[key], can be assigned",
                "script S<OS>\n  a.b = 1\n");
        assertError("s.koine:3:3: this line's indentation matches no block it could be in",
                "script S<OS>\n    print 1\n  print 2\n");
        assertError("s.koine:2:12: comparisons do not chain; join them with and", "script S<OS>\n  if a < b < c\n    x\n");
        assertError("s.koine:2:1: a module holds only def and record", "module M\nlet x = 1\n");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static String expr(String text) {
        return SyntaxPrinter.print(parsed(text));
    }

    private static Expr parsed(String text) {
        SourceFile.Script s = (SourceFile.Script) Parser.parse("e.koine", "script E<OS>\n  print " + text + "\n");
        return ((Statement.Print) s.body().getFirst()).message();
    }

    private static String statement(String text) {
        SourceFile.Script s = (SourceFile.Script) Parser.parse("e.koine", "script E<OS>\n  " + text + "\n");
        return SyntaxPrinter.print(s.body().getFirst());
    }

    private static void assertError(String message, String text) {
        assertEquals(message, assertThrows(KoineException.class, () -> Parser.parse("s.koine", text)).getMessage());
    }

    private static String resource(String path) {
        try (InputStream in = ParserTest.class.getResourceAsStream("/" + path)) {
            if (in == null) throw new IllegalArgumentException("no resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
