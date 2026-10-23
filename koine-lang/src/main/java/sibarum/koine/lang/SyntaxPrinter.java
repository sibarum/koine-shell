package sibarum.koine.lang;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints a syntax tree as s-expressions, one statement to a line, without positions. It is for tests and for looking
 * at what the parser made of a file.
 */
public final class SyntaxPrinter {

    private final StringBuilder out = new StringBuilder();

    private SyntaxPrinter() {
    }

    public static String print(SourceFile file) {
        SyntaxPrinter p = new SyntaxPrinter();
        switch (file) {
            case SourceFile.Script s -> {
                p.line(0, "(" + (s.bootstrap() ? "bootstrap " : "") + "script " + s.name()
                        + " <" + String.join(" ", s.systems()) + ">" + params(s.inputs()) + ")");
                p.block(1, s.body());
            }
            case SourceFile.Module m -> {
                p.line(0, "(module " + m.name() + (m.bundled() ? " bundled" : "") + ")");
                p.block(1, m.members());
            }
        }
        return p.out.toString();
    }

    public static String print(Statement statement) {
        SyntaxPrinter p = new SyntaxPrinter();
        p.statement(0, statement);
        return p.out.toString();
    }

    public static String print(Expr expr) {
        return expr(expr);
    }

    private void block(int depth, List<Statement> body) {
        for (Statement s : body) statement(depth, s);
    }

    private void statement(int depth, Statement statement) {
        switch (statement) {
            case Statement.Let s -> line(depth, "(let " + s.name() + " " + expr(s.value()) + ")");
            case Statement.Assign s -> line(depth, "(= " + expr(s.target()) + " " + expr(s.value()) + ")");
            case Statement.If s -> {
                String keyword = "if";
                for (Statement.If.Branch b : s.branches()) {
                    line(depth, "(" + keyword + " " + expr(b.condition()) + ")");
                    block(depth + 1, b.body());
                    keyword = "else if";
                }
                if (!s.otherwise().isEmpty()) {
                    line(depth, "(else)");
                    block(depth + 1, s.otherwise());
                }
            }
            case Statement.On s -> {
                line(depth, "(on " + String.join(" ", s.systems()) + ")");
                block(depth + 1, s.body());
            }
            case Statement.For s -> {
                line(depth, "(for " + s.item() + " " + expr(s.source()) + ")");
                block(depth + 1, s.body());
            }
            case Statement.While s -> {
                line(depth, "(while " + expr(s.condition()) + ")");
                block(depth + 1, s.body());
            }
            case Statement.Within s -> {
                line(depth, "(within " + expr(s.directory()) + ")");
                block(depth + 1, s.body());
            }
            case Statement.Try s -> line(depth, "(try " + expr(s.command()) + ")");
            case Statement.Fail s -> line(depth, "(fail " + expr(s.message()) + ")");
            case Statement.Step s -> {
                line(depth, "(step " + s.name() + ")");
                block(depth + 1, s.body());
            }
            case Statement.Return s -> line(depth, s.value().map(v -> "(return " + expr(v) + ")").orElse("(return)"));
            case Statement.Print s -> line(depth, "(print " + expr(s.message()) + ")");
            case Statement.Run s -> {
                line(depth, "(run " + expr(s.expr()) + ")");
                block(depth + 1, s.block());
            }
            case Statement.Finally s -> {
                line(depth, "(finally)");
                block(depth + 1, s.body());
            }
            case Statement.Def s -> {
                line(depth, "(" + (s.bundled() ? "bundled " : "") + "def " + s.name() + params(s.parameters())
                        + s.result().map(t -> " : " + type(t)).orElse("") + ")");
                block(depth + 1, s.body());
            }
            case Statement.Native s -> {
                line(depth, "(native " + String.join(" ", s.systems()) + (s.bundled() ? " bundled" : "") + ")");
                for (String l : s.body().split("\n", -1)) line(depth + 1, l.isEmpty() ? "|" : "| " + l);
            }
            case Statement.Record s -> {
                line(depth, "(record " + s.name() + ")");
                for (Statement.Field f : s.fields())
                    line(depth + 1, "(" + f.name() + " " + type(f.type()) + (f.optional() ? "?" : "") + ")");
            }
        }
    }

    private static String params(List<Statement.Param> params) {
        if (params.isEmpty()) return "";
        return " (" + params.stream()
                .map(p -> p.name() + p.type().map(t -> ": " + type(t)).orElse("") + (p.repeated() ? "..." : "")
                        + p.defaultValue().map(v -> " = " + expr(v)).orElse(""))
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String type(TypeRef type) {
        return switch (type) {
            case TypeRef.Named n -> n.name();
            case TypeRef.ListOf l -> "[" + type(l.element()) + "]";
        };
    }

    private static String expr(Expr expr) {
        return switch (expr) {
            case Expr.Name e -> e.name();
            case Expr.NumberLit e -> Long.toString(e.value());
            case Expr.BoolLit e -> Boolean.toString(e.value());
            case Expr.Str e -> prefix(e.open(), e.close(), e.escape()) + e.quotes() + e.parts().stream().map(p -> switch (p) {
                case Expr.Str.Text t -> t.text();
                case Expr.Str.Insert i -> e.open() + expr(i.value()) + e.close();
            }).collect(Collectors.joining()) + e.quotes();
            case Expr.ListLit e -> "[" + e.items().stream().map(SyntaxPrinter::expr).collect(Collectors.joining(" ")) + "]";
            case Expr.MapLit e -> "{" + e.entries().stream().map(x -> expr(x.key()) + ": " + expr(x.value()))
                    .collect(Collectors.joining(", ")) + "}";
            case Expr.Member e -> "(. " + expr(e.target()) + " " + e.name() + ")";
            case Expr.Call e -> "(call " + expr(e.callee()) + e.arguments().stream()
                    .map(a -> " " + a.name().map(n -> n + ": ").orElse("") + expr(a.value()))
                    .collect(Collectors.joining()) + ")";
            case Expr.Index e -> "(index " + expr(e.target()) + " " + expr(e.index()) + ")";
            case Expr.Not e -> "(not " + expr(e.operand()) + ")";
            case Expr.Binary e -> "(" + e.op().symbol + " " + expr(e.left()) + " " + expr(e.right()) + ")";
            case Expr.As e -> "(as " + expr(e.value()) + " " + type(e.type()) + ")";
            case Expr.Command e -> {
                String prefix = prefix(e.open(), e.close(), e.escape());
                yield "(cmd" + (prefix.isEmpty() ? "" : " " + prefix)
                        + e.words().stream().map(w -> word(w, e.open(), e.close())).collect(Collectors.joining()) + ")";
            }
            case Expr.Try e -> "(try? " + expr(e.command()) + ")";
            case Expr.OrElse e -> "(else " + expr(e.command()) + " " + expr(e.fallback()) + ")";
            case Expr.FailWith e -> "(fail " + expr(e.message()) + ")";
        };
    }

    /**
     * A string's or a command's prefix, as written: its value brackets, then its escape character. The text after it
     * is printed with the escapes already applied.
     */
    private static String prefix(char open, char close, char escape) {
        return (open == Lexer.NONE ? "" : "" + open + close) + (escape == Lexer.NONE ? "" : "" + escape);
    }

    /** A word as {@code [part part]}: literals bare, quoted text in its quotes, inserts in the command's brackets. */
    private static String word(Word w, char open, char close) {
        return " [" + w.parts().stream().map(p -> switch (p) {
            case Word.Literal l -> l.text();
            case Word.Quoted q -> q.quotes() + q.text() + q.quotes();
            case Word.Insert i -> open + expr(i.value()) + close;
        }).collect(Collectors.joining(" ")) + "]";
    }

    private void line(int depth, String text) {
        out.append("  ".repeat(depth)).append(text).append('\n');
    }
}
