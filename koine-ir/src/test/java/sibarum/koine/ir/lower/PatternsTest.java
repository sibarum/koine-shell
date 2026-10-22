package sibarum.koine.ir.lower;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatternsTest {

    /** POSIX classes become .NET ones, and only inside a bracket expression. */
    @Test
    void posixClassesBecomeDotNetOnes() {
        assertEquals("^[\\s]*(#|\\z)", Patterns.ereToDotNet("^[[:space:]]*(#|$)"));
        assertEquals("[^a-zA-Z0-9_]", Patterns.ereToDotNet("[^[:alnum:]_]"));
        assertEquals("[:digit:]", Patterns.ereToDotNet("[:digit:]"));
    }

    /** What is plain in an ERE's bracket expression is escaped for .NET, where it would mean something. */
    @Test
    void bracketExpressionsKeepTheirMeaning() {
        assertEquals("[\\\\]", Patterns.ereToDotNet("[\\]"));
        assertEquals("[a\\[]", Patterns.ereToDotNet("[a[]"));
        assertEquals("^[\\]a]\\z", Patterns.ereToDotNet("^[]a]$"));
        assertEquals("[^\\]-]", Patterns.ereToDotNet("[^]-]"));
    }

    /** $ is the end of the text, not before a final line break, and . matches a line break too. */
    @Test
    void anchorsAndDotsMatchAsInPosix() {
        assertEquals("a\\z", Patterns.ereToDotNet("a$"));
        assertEquals("(?s)^a.b\\z", Patterns.ereToDotNet("^a.b$"));
        assertEquals("a\\.b\\$", Patterns.ereToDotNet("a\\.b\\$"));
        assertEquals("[.$]", Patterns.ereToDotNet("[.$]"));
    }
}
