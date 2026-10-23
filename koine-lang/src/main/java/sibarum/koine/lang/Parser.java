package sibarum.koine.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import sibarum.koine.lang.Token.Code;
import sibarum.koine.lang.Token.Kind;
import sibarum.koine.lang.Token.Segment;

/**
 * Reads a script or a module into its syntax tree. It checks only the shape of what is written; names, types, tiers
 * and which statements are allowed where are the checker's.
 */
public final class Parser {

    private final String source;
    private final List<Token> tokens;
    /** Comments by the line they are above; see {@link Lexer}. */
    private final Map<Integer, String> comments;
    private int k;

    private Parser(String source, List<Token> tokens, Map<Integer, String> comments) {
        this.source = source;
        this.tokens = tokens;
        this.comments = comments;
    }

    /**
     * Parses one file.
     *
     * @param source the file's name, for messages
     * @param text   what it holds
     */
    public static SourceFile parse(String source, String text) {
        Lexer.Lexed lexed = Lexer.lexAll(source, text);
        Parser p = new Parser(source, lexed.tokens(), lexed.comments());
        SourceFile file = p.file();
        p.expect(Kind.EOF, "the end of the file");
        return file;
    }

    private SourceFile file() {
        if (at(Kind.SCRIPT) || at(Kind.BOOTSTRAP)) return script();
        if (at(Kind.MODULE)) return module();
        throw error(peek(), "a file starts with script, bootstrap script or module");
    }

    private SourceFile.Script script() {
        Pos at = peek().pos();
        String doc = comments.getOrDefault(at.line(), "");
        boolean bootstrap = accept(Kind.BOOTSTRAP);
        expect(Kind.SCRIPT, bootstrap ? "script after bootstrap" : "script");
        String name = name("the script's name");
        expect(Kind.LT, "<OS>, or the OSes the script is for, after its name");
        List<String> systems = systems();
        expect(Kind.GT, ">");
        List<Statement.Param> inputs = at(Kind.LPAREN) ? parameters() : List.of();
        endOfLine();
        return new SourceFile.Script(name, systems, bootstrap, doc, inputs, block(false), at);
    }

    private SourceFile.Module module() {
        Pos at = expect(Kind.MODULE, "module").pos();
        String name = name("the module's name");
        boolean bundled = accept(Kind.BUNDLED);
        endOfLine();
        List<Statement> members = new ArrayList<>();
        while (!at(Kind.EOF)) {
            comment().ifPresent(members::add);
            Statement s = statement(false);
            if (!(s instanceof Statement.Def) && !(s instanceof Statement.Record))
                throw new KoineException(source, s.pos(), "a module holds only def and record");
            members.add(s);
        }
        return new SourceFile.Module(name, bundled, members, at);
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    /**
     * An indented block. {@code on} and {@code native} branches are allowed only directly in a {@code def}'s body, and
     * {@code finally} only last.
     */
    private List<Statement> block(boolean branchesAllowed) {
        expect(Kind.INDENT, "an indented block");
        List<Statement> body = new ArrayList<>();
        Statement.Finally last = null;
        while (!at(Kind.DEDENT) && !at(Kind.EOF)) {
            if (last != null) throw new KoineException(source, last.pos(), "finally is the last thing in its block");
            comment().ifPresent(body::add);
            Statement s = statement(branchesAllowed);
            if (s instanceof Statement.Finally f) last = f;
            body.add(s);
        }
        expect(Kind.DEDENT, "the end of the block");
        return body;
    }

    /** The comment above the next statement, if one was kept. */
    private Optional<Statement> comment() {
        Pos at = peek().pos();
        String text = comments.get(at.line());
        return text == null ? Optional.empty() : Optional.of(new Statement.Comment(text, at));
    }

    private Statement statement(boolean branchesAllowed) {
        Token t = peek();
        Pos at = t.pos();
        switch (t.kind()) {
            case LET -> {
                advance();
                String name = name("a name after let");
                expect(Kind.ASSIGN, "= after let " + name);
                Expr value = expr();
                endOfLine();
                return new Statement.Let(name, value, at);
            }
            case IF -> {
                return ifStatement();
            }
            case ON -> {
                if (!branchesAllowed) throw error(t, "on goes directly in a def's body");
                advance();
                List<String> systems = systems();
                endOfLine();
                return new Statement.On(systems, block(false), at);
            }
            case FOR -> {
                advance();
                String item = name("a name after for");
                expect(Kind.IN, "in");
                Expr items = expr();
                endOfLine();
                return new Statement.For(item, items, block(false), at);
            }
            case WHILE -> {
                advance();
                Expr condition = expr();
                endOfLine();
                return new Statement.While(condition, block(false), at);
            }
            case WITHIN -> {
                advance();
                Expr directory = expr();
                endOfLine();
                return new Statement.Within(directory, block(false), at);
            }
            case TRY -> {
                advance();
                Expr.Command command = command("a command in backticks after try");
                endOfLine();
                return new Statement.Try(command, at);
            }
            case FAIL -> {
                advance();
                Expr message = expr();
                endOfLine();
                return new Statement.Fail(message, at);
            }
            case STEP -> {
                advance();
                String name = name("the step's name");
                endOfLine();
                return new Statement.Step(name, block(false), at);
            }
            case RETURN -> {
                advance();
                Optional<Expr> value = at(Kind.NEWLINE) ? Optional.empty() : Optional.of(expr());
                endOfLine();
                return new Statement.Return(value, at);
            }
            case FINALLY -> {
                advance();
                endOfLine();
                return new Statement.Finally(block(false), at);
            }
            case PRINT -> {
                advance();
                Expr message = expr();
                endOfLine();
                return new Statement.Print(message, at);
            }
            case DEF, BUNDLED -> {
                return def();
            }
            case RECORD -> {
                return record();
            }
            case NATIVE -> {
                if (!branchesAllowed) throw error(t, "native goes directly in a def's body");
                return nativeBranch();
            }
            case ELSE -> throw error(t, "else without an if before it");
            case INDENT -> throw error(t, "this line is indented, but the line before does not start a block");
            default -> {
                Expr e = expr();
                if (accept(Kind.ASSIGN)) {
                    if (!(e instanceof Expr.Name) && !(e instanceof Expr.Index))
                        throw new KoineException(source, at, "only a name, or an item such as map[key], can be assigned");
                    Expr value = expr();
                    endOfLine();
                    return new Statement.Assign(e, value, at);
                }
                endOfLine();
                return new Statement.Run(e, at(Kind.INDENT) ? block(false) : List.of(), at);
            }
        }
    }

    private Statement.If ifStatement() {
        Pos at = expect(Kind.IF, "if").pos();
        List<Statement.If.Branch> branches = new ArrayList<>();
        Expr condition = expr();
        endOfLine();
        branches.add(new Statement.If.Branch(condition, block(false)));
        List<Statement> otherwise = List.of();
        while (accept(Kind.ELSE)) {
            if (accept(Kind.IF)) {
                Expr c = expr();
                endOfLine();
                branches.add(new Statement.If.Branch(c, block(false)));
            } else {
                endOfLine();
                otherwise = block(false);
                break;
            }
        }
        return new Statement.If(branches, otherwise, at);
    }

    private Statement.Def def() {
        Pos at = peek().pos();
        boolean bundled = accept(Kind.BUNDLED);
        expect(Kind.DEF, bundled ? "def after bundled" : "def");
        String name = name("the function's name");
        List<Statement.Param> parameters = at(Kind.LPAREN) ? parameters() : List.of();
        Optional<TypeRef> result = accept(Kind.COLON) ? Optional.of(type()) : Optional.empty();
        endOfLine();
        return new Statement.Def(name, parameters, result, bundled, block(true), at);
    }

    private Statement.Native nativeBranch() {
        Pos at = expect(Kind.NATIVE, "native").pos();
        List<String> systems = systems();
        boolean bundled = accept(Kind.BUNDLED);
        endOfLine();
        if (!at(Kind.RAW)) throw new KoineException(source, at, "native " + String.join(", ", systems) + " has no body");
        return new Statement.Native(systems, bundled, advance().text(), at);
    }

    private Statement.Record record() {
        Pos at = expect(Kind.RECORD, "record").pos();
        String name = name("the record's name");
        endOfLine();
        expect(Kind.INDENT, "the record's fields, indented");
        List<Statement.Field> fields = new ArrayList<>();
        while (!at(Kind.DEDENT) && !at(Kind.EOF)) {
            Pos fieldAt = peek().pos();
            String field = name("a field's name");
            expect(Kind.COLON, ": and the type of " + field);
            TypeRef type = type();
            boolean optional = accept(Kind.QUESTION);
            endOfLine();
            fields.add(new Statement.Field(field, type, optional, fieldAt));
        }
        expect(Kind.DEDENT, "the end of the record");
        return new Statement.Record(name, fields, at);
    }

    /** {@code (name, name: Type, name = default)}. */
    private List<Statement.Param> parameters() {
        expect(Kind.LPAREN, "(");
        List<Statement.Param> parameters = new ArrayList<>();
        if (!at(Kind.RPAREN)) {
            do {
                Pos at = peek().pos();
                String name = name("a parameter's name");
                Optional<TypeRef> type = accept(Kind.COLON) ? Optional.of(type()) : Optional.empty();
                boolean repeated = type.isPresent() && at(Kind.DOT) && peekAt(1).kind() == Kind.DOT
                        && peekAt(2).kind() == Kind.DOT;
                if (repeated) for (int i = 0; i < 3; i++) advance();
                Optional<Expr> defaultValue = accept(Kind.ASSIGN) ? Optional.of(expr()) : Optional.empty();
                parameters.add(new Statement.Param(name, type, repeated, defaultValue, at));
            } while (accept(Kind.COMMA));
        }
        expect(Kind.RPAREN, ") after the parameters");
        return parameters;
    }

    /** {@code Windows} or {@code Linux, Mac}. */
    private List<String> systems() {
        List<String> systems = new ArrayList<>();
        do systems.add(name("an OS: Windows, Linux or Mac")); while (accept(Kind.COMMA));
        return systems;
    }

    private TypeRef type() {
        Token t = peek();
        if (accept(Kind.LBRACKET)) {
            TypeRef element = type();
            expect(Kind.RBRACKET, "] after the list's type");
            return new TypeRef.ListOf(element, t.pos());
        }
        return new TypeRef.Named(name("a type"), t.pos());
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    private Expr expr() {
        return or();
    }

    private Expr or() {
        Expr left = and();
        while (at(Kind.OR)) {
            Pos at = advance().pos();
            left = new Expr.Binary(Expr.Op.OR, left, and(), at);
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (at(Kind.AND)) {
            Pos at = advance().pos();
            left = new Expr.Binary(Expr.Op.AND, left, not(), at);
        }
        return left;
    }

    private Expr not() {
        if (at(Kind.NOT)) {
            Pos at = advance().pos();
            return new Expr.Not(not(), at);
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = as();
        Expr.Op op = comparisonOp();
        if (op == null) return left;
        Pos at = advance().pos();
        Expr result = new Expr.Binary(op, left, as(), at);
        if (comparisonOp() != null) throw error(peek(), "comparisons do not chain; join them with and");
        return result;
    }

    private Expr.Op comparisonOp() {
        return switch (peek().kind()) {
            case EQ -> Expr.Op.EQ;
            case NE -> Expr.Op.NE;
            case LT -> Expr.Op.LT;
            case LE -> Expr.Op.LE;
            case GT -> Expr.Op.GT;
            case GE -> Expr.Op.GE;
            case IN -> Expr.Op.IN;
            default -> null;
        };
    }

    private Expr as() {
        Expr value = postfix();
        while (at(Kind.AS)) {
            Pos at = advance().pos();
            value = new Expr.As(value, type(), at);
        }
        return value;
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            Token t = peek();
            if (accept(Kind.DOT)) {
                Pos at = peek().pos();
                e = new Expr.Member(e, name("a member's name after ."), at);
            } else if (accept(Kind.LPAREN)) {
                e = new Expr.Call(e, arguments(), e.pos());
            } else if (accept(Kind.LBRACKET)) {
                Expr index = expr();
                expect(Kind.RBRACKET, "]");
                e = new Expr.Index(e, index, e.pos());
            } else {
                return e;
            }
        }
    }

    /** The arguments of a call, after its {@code (}. */
    private List<Expr.Argument> arguments() {
        List<Expr.Argument> arguments = new ArrayList<>();
        if (!at(Kind.RPAREN)) {
            do {
                Pos at = peek().pos();
                Optional<String> name = Optional.empty();
                if (at(Kind.NAME) && peekAt(1).kind() == Kind.COLON) {
                    name = Optional.of(advance().text());
                    advance();
                }
                arguments.add(new Expr.Argument(name, expr(), at));
            } while (accept(Kind.COMMA));
        }
        expect(Kind.RPAREN, ") after the arguments");
        return arguments;
    }

    private Expr primary() {
        Token t = peek();
        Pos at = t.pos();
        switch (t.kind()) {
            case NAME -> {
                advance();
                return new Expr.Name(t.text(), at);
            }
            case NUMBER -> {
                advance();
                try {
                    return new Expr.NumberLit(Long.parseLong(t.text()), at);
                } catch (NumberFormatException e) {
                    throw error(t, t.text() + " is too large a number");
                }
            }
            case TRUE, FALSE -> {
                advance();
                return new Expr.BoolLit(t.kind() == Kind.TRUE, at);
            }
            case STRING -> {
                advance();
                return string(t);
            }
            case COMMAND -> {
                Expr.Command command = command("a command");
                if (!at(Kind.ELSE)) return command;
                advance();
                Pos failAt = peek().pos();
                Expr fallback = accept(Kind.FAIL) ? new Expr.FailWith(expr(), failAt) : postfix();
                return new Expr.OrElse(command, fallback, at);
            }
            case TRY -> {
                advance();
                return new Expr.Try(command("a command in backticks after try"), at);
            }
            case LPAREN -> {
                advance();
                Expr e = expr();
                expect(Kind.RPAREN, ")");
                return e;
            }
            case LBRACKET -> {
                advance();
                List<Expr> items = new ArrayList<>();
                if (!at(Kind.RBRACKET)) {
                    do items.add(expr()); while (accept(Kind.COMMA) && !at(Kind.RBRACKET));
                }
                expect(Kind.RBRACKET, "] after the list");
                return new Expr.ListLit(items, at);
            }
            case LBRACE -> {
                advance();
                List<Expr.MapLit.Entry> entries = new ArrayList<>();
                if (!at(Kind.RBRACE)) {
                    do {
                        Expr key = expr();
                        expect(Kind.COLON, ": after the key");
                        entries.add(new Expr.MapLit.Entry(key, expr()));
                    } while (accept(Kind.COMMA) && !at(Kind.RBRACE));
                }
                expect(Kind.RBRACE, "} after the map");
                return new Expr.MapLit(entries, at);
            }
            default -> throw error(t, "expected a value, found " + t.describe());
        }
    }

    private Expr.Str string(Token t) {
        List<Expr.Str.Part> parts = new ArrayList<>();
        for (Segment s : t.parts()) {
            switch (s) {
                case Token.Text text -> parts.add(new Expr.Str.Text(text.text(), text.pos()));
                case Code code -> parts.add(new Expr.Str.Insert(inserted(code), code.pos()));
                case Token.Quoted q -> throw new IllegalStateException("a string has no quoted parts");
            }
        }
        return new Expr.Str(parts, t.quotes(), t.open(), t.close(), t.escape(), t.pos());
    }

    private Expr.Command command(String what) {
        Token t = expect(Kind.COMMAND, what);
        List<Word> words = new ArrayList<>();
        for (List<Segment> segments : t.words()) {
            List<Word.Part> parts = new ArrayList<>();
            for (Segment s : segments) {
                parts.add(switch (s) {
                    case Token.Text text -> new Word.Literal(text.text(), text.pos());
                    case Token.Quoted q -> new Word.Quoted(q.text(), q.quotes(), q.pos());
                    case Code code -> new Word.Insert(inserted(code), code.pos());
                });
            }
            words.add(new Word(parts, segments.getFirst().pos()));
        }
        return new Expr.Command(words, t.open(), t.close(), t.escape(), t.pos());
    }

    /** The expression between a string's or a command's brackets. */
    private Expr inserted(Code code) {
        Parser p = new Parser(source, code.tokens(), Map.of());
        if (p.at(Kind.EOF)) throw new KoineException(source, code.pos(), "the brackets hold no value");
        Expr e = p.expr();
        if (!p.at(Kind.EOF)) throw p.error(p.peek(), "expected the closing bracket, found " + p.peek().describe());
        return e;
    }

    // ---- tokens -------------------------------------------------------------------------------------------------

    private Token peek() {
        return tokens.get(k);
    }

    private Token peekAt(int ahead) {
        return tokens.get(Math.min(k + ahead, tokens.size() - 1));
    }

    private boolean at(Kind kind) {
        return peek().kind() == kind;
    }

    private Token advance() {
        Token t = peek();
        if (t.kind() != Kind.EOF) k++;
        return t;
    }

    private boolean accept(Kind kind) {
        if (!at(kind)) return false;
        advance();
        return true;
    }

    private Token expect(Kind kind, String what) {
        if (!at(kind)) throw error(peek(), "expected " + what + ", found " + peek().describe());
        return advance();
    }

    private String name(String what) {
        return expect(Kind.NAME, what).text();
    }

    private void endOfLine() {
        expect(Kind.NEWLINE, "the end of the line");
    }

    private KoineException error(Token at, String problem) {
        return new KoineException(source, at.pos(), problem);
    }
}
