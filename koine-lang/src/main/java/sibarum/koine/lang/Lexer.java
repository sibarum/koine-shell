package sibarum.koine.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>
 * A comment is not a token. A run of whole-line comments directly above a line, with no blank line between, is kept
 * with that line's number, for the parser to make a statement of.
 */
final class Lexer {

    private final String source;
    private final String text;
    private final List<Token> out = new ArrayList<>();
    private final Deque<Integer> indents = new ArrayDeque<>();
    private int i;
    private int line = 1;
    private int lineStart;
    /** The comments kept, by the line they are above, and the run of comment lines not yet above anything. */
    private final Map<Integer, String> comments = new HashMap<>();
    private final List<String> pending = new ArrayList<>();

    private Lexer(String source, String text) {
        this.source = source;
        this.text = text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The tokens, and the comments above lines, by line. */
    record Lexed(List<Token> tokens, Map<Integer, String> comments) {
    }

    static Lexed lexAll(String source, String text) {
        Lexer l = new Lexer(source, text);
        List<Token> tokens = l.run();
        return new Lexed(tokens, Map.copyOf(l.comments));
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
                pending.clear();
                newline();
                continue;
            }
            if (c == '#') {
                int start = i + 1;
                skipToEndOfLine();
                String comment = text.substring(start, i).stripTrailing();
                pending.add(comment.startsWith(" ") ? comment.substring(1) : comment);
                if (i < text.length()) newline();
                continue;
            }
            if (!pending.isEmpty()) {
                comments.put(line, String.join("\n", pending));
                pending.clear();
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
        int prefix = c == '`' || c == '"' || c == '\'' ? 0 : prefixLength();
        if (prefix >= 0) {
            int start = i;
            char open = prefix >= 2 ? text.charAt(i) : NONE;
            char close = prefix >= 2 ? text.charAt(i + 1) : NONE;
            char escape = prefix % 2 == 1 ? text.charAt(i + prefix - 1) : NONE;
            if (escape != NONE && (escape == open || escape == close))
                throw error(at, "the escape character " + escape + " is one of the value brackets; choose another");
            i += prefix;
            return text.charAt(i) == '`'
                    ? scanCommand(at, start, open, close, escape)
                    : scanString(at, start, open, close, escape);
        }
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

    /**
     * A string, from its run of quotes at {@code i}. It ends at a run of exactly as many of the same quote, and a
     * shorter or longer run inside is literal, so there is no empty string literal: {@code ''} opens a string. Nothing
     * in it is special unless its prefix defined value brackets, which insert an expression, or an escape character,
     * which makes the character after it literal. The token's text starts at {@code start}, before the prefix.
     */
    private Token scanString(Pos at, int start, char open, char close, char escape) {
        String quotes = quoteRun();
        List<Segment> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        Pos runAt = pos();
        while (true) {
            if (atEndOfLine()) throw error(at, notClosed("the string", quotes, "on this line"));
            char c = text.charAt(i);
            if (c == escape) {
                if (run.isEmpty()) runAt = pos();
                run.append(escaped());
            } else if (c == quotes.charAt(0)) {
                int n = run(c);
                if (n == quotes.length()) {
                    i += n;
                    break;
                }
                if (run.isEmpty()) runAt = pos();
                run.append(text, i, i + n);
                i += n;
            } else if (c == open) {
                if (!run.isEmpty()) parts.add(new Text(run.toString(), runAt));
                run.setLength(0);
                i++;
                parts.add(scanCode(close));
                runAt = pos();
            } else {
                if (run.isEmpty()) runAt = pos();
                run.append(c);
                i++;
            }
        }
        if (!run.isEmpty()) parts.add(new Text(run.toString(), runAt));
        return Token.string(text.substring(start, i), at, parts, quotes, open, close, escape);
    }

    /** The run of quotes at {@code i}, which it consumes. */
    private String quoteRun() {
        int n = run(text.charAt(i));
        String quotes = text.substring(i, i + n);
        i += n;
        return quotes;
    }

    /** What a string or a quote that is not closed needs, and the way to write an empty string if it looks like one. */
    private static String notClosed(String what, String quotes, String where) {
        return what + " is not closed: it needs " + quotes + " " + where
                + (quotes.length() == 2 ? "; there is no empty string literal: write String.empty" : "");
    }

    /** The character after the escape character at {@code i}, which must be on the same line. Consumes both. */
    private char escaped() {
        Pos at = pos();
        i++;
        if (atEndOfLine()) throw error(at, "the escape character " + text.charAt(i - 1) + " needs a character after it");
        return text.charAt(i++);
    }

    // ---- prefixes -----------------------------------------------------------------------------------------------

    /** No value brackets, or no escape character. */
    static final char NONE = 0;

    /** Symbols Koine's own syntax does not use, which can be value brackets and escape characters. */
    private static final String FREE_SYMBOLS = "~@$%^&*+-/\\|;";

    /**
     * How many characters at {@code i} make the prefix of the string or command right after them, or -1 if they are
     * not one. A prefix defines what is special inside, since by default nothing is:
     * <ul>
     *   <li>one symbol is an escape character: {@code \'it\'s'};</li>
     *   <li>two characters are value brackets: {@code {}"{name} is up"};</li>
     *   <li>three are value brackets and then an escape character: {@code {}\"{name} said \"hi\""}.</li>
     * </ul>
     * Value brackets are an opening bracket and its closing one ({@code ()}, {@code []}, {@code {}} or {@code <>}), or
     * any two symbols Koine's own syntax does not use, the same one twice included ({@code %%}, {@code ^|}). An escape
     * character is one of those symbols. Koine's own symbols cannot be used otherwise, because they already mean
     * something before a string: {@code [['a']]}, {@code a=='x'}, {@code f(a,['x'])}.
     */
    private int prefixLength() {
        if (opensLiteral(i + 1) && isFree(i)) return 1;
        if (!opensLiteral(i + 2) && !opensLiteral(i + 3)) return -1;
        if (i + 1 >= text.length()) return -1;
        char open = text.charAt(i);
        char close = text.charAt(i + 1);
        if (closerOf(open) != close && !(isFree(i) && isFree(i + 1))) return -1;
        if (opensLiteral(i + 2)) return 2;
        return isFree(i + 2) ? 3 : -1;
    }

    /** Whether a string or a command opens at {@code at}. */
    private boolean opensLiteral(int at) {
        if (at >= text.length()) return false;
        char c = text.charAt(at);
        return c == '`' || c == '"' || c == '\'';
    }

    private boolean isFree(int at) {
        return at < text.length() && FREE_SYMBOLS.indexOf(text.charAt(at)) >= 0;
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
     * A literal command, from its backticks at {@code i}. It ends at a run of exactly as many backticks as opened it,
     * wherever that is; a shorter or longer run inside is literal. Words are split at spaces and quotes group them.
     * Value brackets and an escape character work only if the prefix defined them: the brackets insert a value, and
     * the escape character makes the character after it literal text in the word, even a space, a quote or a backtick.
     * The token's text starts at {@code start}, before the prefix.
     */
    private Token scanCommand(Pos at, int start, char open, char close, char escape) {
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
                if (c == escape) {
                    if (run.isEmpty()) runAt = pos();
                    run.append(escaped());
                } else if (c == '\'' || c == '"' || c == open) {
                    if (!run.isEmpty()) word.add(new Text(run.toString(), runAt));
                    run.setLength(0);
                    if (c == open) {
                        i++;
                        word.add(scanCode(close));
                    } else {
                        word.add(scanQuoted(ticks, escape));
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
        return Token.command(text.substring(start, i), at, words, open, close, escape);
    }

    /**
     * Text in quotes inside a command, taken as it is except for the command's escape character, if it has one. Like
     * a string, it opens with a run of quotes and ends at a run of exactly as many. The command's closing backticks
     * may not fall inside unless escaped.
     */
    private Quoted scanQuoted(int ticks, char escape) {
        Pos at = pos();
        String quotes = quoteRun();
        StringBuilder quoted = new StringBuilder();
        while (true) {
            if (atEndOfLine() || backtickRun() == ticks)
                throw error(at, notClosed("the quote", quotes, "before the command ends"));
            char c = text.charAt(i);
            if (c == escape) {
                quoted.append(escaped());
                continue;
            }
            int n = c == '`' || c == quotes.charAt(0) ? run(c) : 1;
            if (c == quotes.charAt(0) && n == quotes.length()) {
                i += n;
                break;
            }
            quoted.append(text, i, i + n);
            i += n;
        }
        return new Quoted(quoted.toString(), quotes, at);
    }

    /** How many backticks start at {@code i}. */
    private int backtickRun() {
        return run('`');
    }

    /** How many of {@code c} start at {@code i}. */
    private int run(char c) {
        int n = 0;
        while (i + n < text.length() && text.charAt(i + n) == c) n++;
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
