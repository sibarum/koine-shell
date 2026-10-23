package sibarum.koine.lang;

import java.util.List;
import java.util.Map;

/**
 * A token. Strings and commands are lexed whole: a {@link Kind#STRING} carries its {@link #parts}, and a
 * {@link Kind#COMMAND} its {@link #words}, where the code between brackets is already lexed into tokens of its own.
 */
record Token(Kind kind, String text, Pos pos, List<Segment> parts, List<List<Segment>> words, String quotes, char open,
             char close, char escape) {

    enum Kind {
        NAME, NUMBER, STRING, COMMAND,
        /** The lines of a native body, without their indentation. */
        RAW,
        NEWLINE, INDENT, DEDENT, EOF,

        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, DOT, COLON, QUESTION, ASSIGN,
        EQ, NE, LT, LE, GT, GE,

        SCRIPT, MODULE, LET, IF, ELSE, ON, FOR, IN, WHILE, WITHIN, TRY, FAIL, STEP, DEF, RETURN, PRINT, RECORD,
        NATIVE, BUNDLED, AS, AND, OR, NOT, TRUE, FALSE
    }

    static final Map<String, Kind> KEYWORDS = Map.ofEntries(
            Map.entry("script", Kind.SCRIPT), Map.entry("module", Kind.MODULE), Map.entry("let", Kind.LET),
            Map.entry("if", Kind.IF), Map.entry("else", Kind.ELSE), Map.entry("on", Kind.ON),
            Map.entry("for", Kind.FOR), Map.entry("in", Kind.IN), Map.entry("while", Kind.WHILE),
            Map.entry("within", Kind.WITHIN), Map.entry("try", Kind.TRY), Map.entry("fail", Kind.FAIL),
            Map.entry("step", Kind.STEP), Map.entry("def", Kind.DEF), Map.entry("return", Kind.RETURN),
            Map.entry("print", Kind.PRINT), Map.entry("record", Kind.RECORD), Map.entry("native", Kind.NATIVE),
            Map.entry("bundled", Kind.BUNDLED), Map.entry("as", Kind.AS), Map.entry("and", Kind.AND),
            Map.entry("or", Kind.OR), Map.entry("not", Kind.NOT), Map.entry("true", Kind.TRUE),
            Map.entry("false", Kind.FALSE));

    /** A piece of a string or of a command's word. */
    sealed interface Segment {

        Pos pos();
    }

    /** Text as written: outside quotes in a command, or anywhere in a string. */
    record Text(String text, Pos pos) implements Segment {
    }

    /** Text in quotes inside a command. */
    record Quoted(String text, String quotes, Pos pos) implements Segment {
    }

    /** The tokens of an expression between brackets, ending with {@link Kind#EOF}. */
    record Code(List<Token> tokens, Pos pos) implements Segment {
    }

    static Token of(Kind kind, String text, Pos pos) {
        return new Token(kind, text, pos, List.of(), List.of(), "", Lexer.NONE, Lexer.NONE, Lexer.NONE);
    }

    static Token string(String text, Pos pos, List<Segment> parts, String quotes, char open, char close, char escape) {
        return new Token(Kind.STRING, text, pos, List.copyOf(parts), List.of(), quotes, open, close, escape);
    }

    static Token command(String text, Pos pos, List<List<Segment>> words, char open, char close, char escape) {
        return new Token(Kind.COMMAND, text, pos, List.of(), List.copyOf(words), "", open, close, escape);
    }

    /** How the token reads in a message: {@code 'else'}, {@code a line break}. */
    String describe() {
        return switch (kind) {
            case NEWLINE -> "the end of the line";
            case INDENT -> "an indented block";
            case DEDENT -> "the end of the block";
            case EOF -> "the end of the file";
            case STRING -> "a string";
            case COMMAND -> "a command";
            case RAW -> "a native body";
            default -> "'" + text + "'";
        };
    }
}
