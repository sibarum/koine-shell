package sibarum.koine.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import sibarum.koine.lang.Token.Code;
import sibarum.koine.lang.Token.Kind;
import sibarum.koine.lang.Token.Quoted;
import sibarum.koine.lang.Token.Segment;
import sibarum.koine.lang.Token.Text;

/**
 * Turns source text into tokens. Blocks are indentation, so each line ends with {@link Kind#NEWLINE} and a change of
 * indentation becomes {@link Kind#INDENT} or {@link Kind#DEDENT}. Inside brackets a line break is only a space.
 * <p>
 * Strings and literal commands are lexed whole, with the expressions in their brackets lexed on the spot, so a
 * string inside an insert inside a string needs no escaping. The lines under a {@code native} line are not lexed at
 * all: they become one {@link Kind#RAW} token.
 */
final class Lexer {

    private final String source;
    private final String text;
    private final List<Token> out = new ArrayList<>();
    private final Deque<Integer> indents = new ArrayDeque<>();
    private int i;
    private int line = 1;
    private int lineStart;

    private Lexer(String source, String text) {
        this.source = source;
        this.text = text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static List<Token> lex(String source, String text) {
        return new Lexer(source, text).run();
    }

    private List<Token> run() {
        indents.push(0);
        while (i < text.length()) {
            int indent = 0;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
                indent++;
            }
            if (i >= text.length()) break;
            char c = text.charAt(i);
            if (c == '\t') throw error(pos(), "indent with spaces, not tabs");
            if (c == '\n') {
                newline();
                continue;
            }
            if (c == '#') {
                skipToEndOfLine();
                continue;
            }
            Pos at = pos();
            if (indent > indents.peek()) {
                indents.push(indent);
                out.add(Token.of(Kind.INDENT, "", at));
            } else {
                while (indent < indents.peek()) {
                    indents.pop();
                    out.add(Token.of(Kind.DEDENT, "", at));
                }
                if (indent != indents.peek()) throw error(at, "this line's indentation matches no block it could be in");
            }
            int first = out.size();
            scanLine();
            out.add(Token.of(Kind.NEWLINE, "", pos()));
            if (out.get(first).kind() == Kind.NATIVE) readNativeBody(indent);
        }
        while (indents.peek() > 0) {
            indents.pop();
            out.add(Token.of(Kind.DEDENT, "", pos()));
        }
        out.add(Token.of(Kind.EOF, "", pos()));
        return out;
    }

    /** The tokens of one line, and of the lines after it while a bracket is open. Stops before the line break. */
    private void scanLine() {
        int depth = 0;
        while (true) {
            skipSpaces();
            if (i >= text.length()) return;
            char c = text.charAt(i);
            if (c == '#') {
                skipToEndOfLine();
                continue;
            }
            if (c == '\n') {
                if (depth == 0) return;
                newline();
                continue;
            }
            Token t = scanToken();
            switch (t.kind()) {
                case LPAREN, LBRACKET, LBRACE -> depth++;
                case RPAREN, RBRACKET, RBRACE -> depth = Math.max(0, depth - 1);
                default -> {
                }
            }
            out.add(t);
        }
    }

    /** The token at {@code i}, which is not a space, a line break or a comment. */
    private Token scanToken() {
        Pos at = pos();
        char c = text.charAt(i);
        if (commandStarts()) return scanCommand();
        if (isDigit(c)) {
            int start = i;
            while (i < text.length() && isDigit(text.charAt(i))) i++;
            if (i < text.length() && isLetter(text.charAt(i))) throw error(pos(), "a name cannot start with a digit");
            return Token.of(Kind.NUMBER, text.substring(start, i), at);
        }
        if (isLetter(c)) {
            int start = i;
            while (i < text.length() && (isLetter(text.charAt(i)) || isDigit(text.charAt(i)))) i++;
            if (i < text.length() && text.charAt(i) == '_') throw error(pos(), "names cannot contain _");
            String word = text.substring(start, i);
            return Token.of(Token.KEYWORDS.getOrDefault(word, Kind.NAME), word, at);
        }
        if (c == '"' || c == '\'') return scanString(c);
        i++;
        return switch (c) {
            case '(' -> Token.of(Kind.LPAREN, "(", at);
            case ')' -> Token.of(Kind.RPAREN, ")", at);
            case '[' -> Token.of(Kind.LBRACKET, "[", at);
            case ']' -> Token.of(Kind.RBRACKET, "]", at);
            case '{' -> Token.of(Kind.LBRACE, "{", at);
            case '}' -> Token.of(Kind.RBRACE, "}", at);
            case ',' -> Token.of(Kind.COMMA, ",", at);
            case '.' -> Token.of(Kind.DOT, ".", at);
            case ':' -> Token.of(Kind.COLON, ":", at);
            case '?' -> Token.of(Kind.QUESTION, "?", at);
            case '=' -> next('=') ? Token.of(Kind.EQ, "==", at) : Token.of(Kind.ASSIGN, "=", at);
            case '<' -> next('=') ? Token.of(Kind.LE, "<=", at) : Token.of(Kind.LT, "<", at);
            case '>' -> next('=') ? Token.of(Kind.GE, ">=", at) : Token.of(Kind.GT, ">", at);
            case '!' -> {
                if (next('=')) yield Token.of(Kind.NE, "!=", at);
                throw error(at, "'!' is not an operator; write not");
            }
            case '_' -> throw error(at, "names cannot contain _");
            default -> throw error(at, "unexpected character '" + c + "'");
        };
    }

    // ---- strings ------------------------------------------------------------------------------------------------

    /** A string in {@code quote}s. {@code {…}} inserts an expression; nothing else is special. */
    private Token scanString(char quote) {
        Pos at = pos();
        int start = i;
        i++;
        List<Segment> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        Pos runAt = pos();
        while (true) {
            if (atEndOfLine()) throw error(at, "the string is not closed: it needs a " + quote + " on this line");
            char c = text.charAt(i);
            if (c == quote) {
                i++;
                break;
            }
            if (c == '{') {
                if (!run.isEmpty()) parts.add(new Text(run.toString(), runAt));
                run.setLength(0);
                i++;
                parts.add(scanCode('}'));
                runAt = pos();
            } else {
                if (run.isEmpty()) runAt = pos();
                run.append(c);
                i++;
            }
        }
        if (!run.isEmpty()) parts.add(new Text(run.toString(), runAt));
        return Token.string(text.substring(start, i), at, parts, quote);
    }

    /**
     * The expression between brackets, from just after the opening one up to {@code close}, which is consumed. A
     * {@code close} inside a nested bracket, string or command belongs to that.
     */
    private Code scanCode(char close) {
        Pos at = pos();
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        while (true) {
            skipSpaces();
            if (atEndOfLine()) throw error(at, "the value is not closed: it needs a " + close + " on this line");
            if (depth == 0 && text.charAt(i) == close) {
                i++;
                break;
            }
            Token t = scanToken();
            switch (t.kind()) {
                case LPAREN, LBRACKET, LBRACE -> depth++;
                case RPAREN, RBRACKET, RBRACE -> depth = Math.max(0, depth - 1);
                default -> {
                }
            }
            tokens.add(t);
        }
        tokens.add(Token.of(Kind.EOF, "", at));
        return new Code(tokens, at);
    }

    // ---- literal commands ---------------------------------------------------------------------------------------

    /**
     * A command starts at a run of backticks, or at the two brackets that choose its value brackets right in front
     * of one. The brackets are an opening character and its closing one: {@code ()}, {@code []}, {@code {}} or
     * {@code <>}.
     */
    private boolean commandStarts() {
        char c = text.charAt(i);
        if (c == '`') return true;
        if (i + 2 >= text.length() || text.charAt(i + 2) != '`') return false;
        return closerOf(c) == text.charAt(i + 1);
    }

    private static char closerOf(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '<' -> '>';
            default -> 0;
        };
    }

    /**
     * A literal command. It ends at a run of exactly as many backticks as opened it, wherever that is; a shorter or
     * longer run inside is literal. Words are split at spaces, quotes group them, and the brackets insert a value.
     */
    private Token scanCommand() {
        Pos at = pos();
        int start = i;
        char open = '{';
        char close = '}';
        if (text.charAt(i) != '`') {
            open = text.charAt(i);
            close = text.charAt(i + 1);
            i += 2;
        }
        int ticks = backtickRun();
        i += ticks;
        String closing = "`".repeat(ticks);
        List<List<Segment>> words = new ArrayList<>();
        while (true) {
            skipSpaces();
            if (atEndOfLine()) throw error(at, "the command is not closed: it needs " + closing + " on this line");
            if (backtickRun() == ticks) {
                i += ticks;
                break;
            }
            List<Segment> word = new ArrayList<>();
            StringBuilder run = new StringBuilder();
            Pos runAt = pos();
            while (!atEndOfLine() && text.charAt(i) != ' ' && text.charAt(i) != '\t' && backtickRun() != ticks) {
                char c = text.charAt(i);
                if (c == '\'' || c == '"' || c == open) {
                    if (!run.isEmpty()) word.add(new Text(run.toString(), runAt));
                    run.setLength(0);
                    if (c == open) {
                        i++;
                        word.add(scanCode(close));
                    } else {
                        word.add(scanQuoted(c, ticks));
                    }
                    runAt = pos();
                } else if (c == '`') {
                    int run2 = backtickRun();
                    if (run.isEmpty()) runAt = pos();
                    run.append(text, i, i + run2);
                    i += run2;
                } else {
                    if (run.isEmpty()) runAt = pos();
                    run.append(c);
                    i++;
                }
            }
            if (!run.isEmpty()) word.add(new Text(run.toString(), runAt));
            words.add(word);
        }
        if (words.isEmpty()) throw error(at, "the command is empty");
        return Token.command(text.substring(start, i), at, words, open, close);
    }

    /** Text in quotes inside a command, taken as it is. The command's closing backticks may not fall inside. */
    private Quoted scanQuoted(char quote, int ticks) {
        Pos at = pos();
        i++;
        int start = i;
        while (true) {
            if (atEndOfLine() || backtickRun() == ticks)
                throw error(at, "the quote is not closed: it needs a " + quote + " before the command ends");
            if (text.charAt(i) == quote) break;
            if (text.charAt(i) == '`') i += backtickRun();
            else i++;
        }
        String quoted = text.substring(start, i);
        i++;
        return new Quoted(quoted, quote, at);
    }

    /** How many backticks start at {@code i}. */
    private int backtickRun() {
        int n = 0;
        while (i + n < text.length() && text.charAt(i + n) == '`') n++;
        return n;
    }

    // ---- native bodies ------------------------------------------------------------------------------------------

    /**
     * The lines indented under a {@code native} line, which was indented by {@code indent}, become one RAW token
     * without their shared indentation. Blank lines inside the body are kept; blank lines after it are not.
     */
    private void readNativeBody(int indent) {
        if (i < text.length()) newline();
        List<String> lines = new ArrayList<>();
        Pos at = null;
        int kept = 0;
        while (i < text.length()) {
            int start = i;
            int spaces = 0;
            while (i < text.length() && text.charAt(i) == ' ') {
                i++;
                spaces++;
            }
            int end = text.indexOf('\n', i);
            if (end < 0) end = text.length();
            boolean blank = text.substring(i, end).isBlank();
            if (!blank && spaces <= indent) {
                i = start;
                break;
            }
            if (!blank && at == null) at = new Pos(line, spaces + 1);
            lines.add(blank ? "" : text.substring(start, end));
            if (!blank) kept = lines.size();
            i = end;
            if (i < text.length()) newline();
        }
        lines = lines.subList(0, kept);
        if (lines.isEmpty()) return;
        int shared = lines.stream().filter(l -> !l.isEmpty()).mapToInt(Lexer::leadingSpaces).min().orElse(0);
        StringBuilder body = new StringBuilder();
        for (String l : lines) {
            if (!body.isEmpty()) body.append('\n');
            if (!l.isEmpty()) body.append(l.substring(shared).stripTrailing());
        }
        // The first token of the native line is NATIVE, so the body goes after that line's NEWLINE.
        out.add(Token.of(Kind.RAW, body.toString(), at));
    }

    private static int leadingSpaces(String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') n++;
        return n;
    }

    // ---- characters ---------------------------------------------------------------------------------------------

    private boolean next(char c) {
        if (i < text.length() && text.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
    }

    private void skipToEndOfLine() {
        while (i < text.length() && text.charAt(i) != '\n') i++;
    }

    private boolean atEndOfLine() {
        return i >= text.length() || text.charAt(i) == '\n';
    }

    private void newline() {
        i++;
        line++;
        lineStart = i;
    }

    private Pos pos() {
        return new Pos(line, i - lineStart + 1);
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private KoineException error(Pos at, String problem) {
        return new KoineException(source, at, problem);
    }
}
