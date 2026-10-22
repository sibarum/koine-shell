package sibarum.koine.ir.ps;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import sibarum.koine.ir.ps.PsExpression.*;
import sibarum.koine.ir.ps.PsStatement.*;

/**
 * Prints a PowerShell IR tree as PowerShell source. It adds nothing the tree does not hold, parentheses least of all;
 * it only chooses the layout: four-space indentation, and a body on one line when it is short and has no compound
 * statements in it.
 */
public final class PsPrinter {

    private static final int WIDTH = 120;
    private static final Pattern PLAIN_VARIABLE = Pattern.compile("(\\w+:)?\\w+|[?$^]");

    private final StringBuilder out = new StringBuilder();
    /** Every body on one line: inside a string's {@code $(...)}, and while trying a body on one line. */
    private final boolean inline;
    private int depth;
    private boolean lineStart = true;

    private PsPrinter(boolean inline) {
        this.inline = inline;
    }

    public static String print(PsScript script) {
        PsPrinter p = new PsPrinter(false);
        script.help().ifPresent(h -> {
            p.write("<#" + h + "#>");
            p.newline();
        });
        script.paramBlock().ifPresent(b -> {
            p.write("param(");
            p.parameters(b.parameters());
            p.write(")");
            p.newline();
        });
        p.statements(script.body().statements());
        return p.out.toString();
    }

    public static String print(PsStatement statement) {
        PsPrinter p = new PsPrinter(false);
        p.statement(statement);
        return p.out.toString();
    }

    public static String print(PsExpression expression) {
        PsPrinter p = new PsPrinter(false);
        p.expression(expression);
        return p.out.toString();
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    private void write(String s) {
        if (s.isEmpty()) return;
        if (lineStart) {
            out.append("    ".repeat(depth));
            lineStart = false;
        }
        out.append(s);
    }

    private void newline() {
        out.append('\n');
        lineStart = true;
    }

    private int column() {
        return lineStart ? 4 * depth : out.length() - out.lastIndexOf("\n") - 1;
    }

    /** Statements one per line, each followed by a line break. */
    private void statements(List<PsStatement> ss) {
        for (PsStatement s : ss) {
            statement(s);
            newline();
        }
    }

    /** Statements on one line, separated by {@code ;}. */
    private void inlineStatements(List<PsStatement> ss) {
        for (int i = 0; i < ss.size(); i++) {
            if (i > 0) write("; ");
            statement(ss.get(i));
        }
    }

    private void block(PsBlock b) {
        block(b, true);
    }

    /** @param mayInline whether a short body may go on one line; an {@code if} with an {@code else} decides for all its bodies at once */
    private void block(PsBlock b, boolean mayInline) {
        List<PsStatement> ss = b.statements();
        if (ss.isEmpty()) {
            write("{ }");
            return;
        }
        if (inline) {
            write("{ ");
            inlineStatements(ss);
            write(" }");
            return;
        }
        if (mayInline && ss.stream().allMatch(PsPrinter::isSimple)) {
            String one = oneLine(ss);
            if (!one.contains("\n") && column() + one.length() + 4 <= WIDTH) {
                write("{ " + one + " }");
                return;
            }
        }
        write("{");
        newline();
        depth++;
        statements(ss);
        depth--;
        write("}");
    }

    private static String oneLine(List<PsStatement> ss) {
        PsPrinter p = new PsPrinter(true);
        p.inlineStatements(ss);
        return p.out.toString();
    }

    private static java.util.stream.Stream<PsBlock> bodies(If f) {
        return java.util.stream.Stream.concat(f.clauses().stream().map(Clause::body), f.otherwise().stream());
    }

    /** A statement that can share a line with others: no body of its own, or only script-block arguments. */
    private static boolean isSimple(PsStatement s) {
        return switch (s) {
            case Pipeline p -> true;
            case Assignment a -> isSimple(a.value());
            case Throw t -> t.value().map(PsPrinter::isSimple).orElse(true);
            case Return r -> r.value().map(PsPrinter::isSimple).orElse(true);
            case Exit e -> e.value().map(PsPrinter::isSimple).orElse(true);
            case Break b -> true;
            case Continue c -> true;
            default -> false;
        };
    }

    // ---- statements ---------------------------------------------------------------------------------------------

    private void statement(PsStatement s) {
        switch (s) {
            case Pipeline p -> {
                for (int i = 0; i < p.elements().size(); i++) {
                    if (i > 0) write(" | ");
                    element(p.elements().get(i));
                }
            }
            case Assignment a -> {
                expression(a.target());
                write(" " + a.operator().token + " ");
                statement(a.value());
            }
            case If f -> {
                // With more than one body, either the whole statement fits on one line or every body gets its own lines
                boolean single = f.clauses().size() == 1 && f.otherwise().isEmpty();
                if (!single && !inline && bodies(f).allMatch(b -> b.statements().stream().allMatch(PsPrinter::isSimple))) {
                    PsPrinter p = new PsPrinter(true);
                    p.statement(f);
                    String one = p.out.toString();
                    if (!one.contains("\n") && column() + one.length() <= WIDTH) {
                        write(one);
                        return;
                    }
                }
                for (int i = 0; i < f.clauses().size(); i++) {
                    Clause c = f.clauses().get(i);
                    write(i == 0 ? "if (" : " elseif (");
                    statement(c.condition());
                    write(") ");
                    block(c.body(), single);
                }
                f.otherwise().ifPresent(b -> {
                    write(" else ");
                    block(b, false);
                });
            }
            case Foreach f -> {
                write("foreach (");
                expression(f.variable());
                write(" in ");
                statement(f.collection());
                write(") ");
                block(f.body());
            }
            case While w -> {
                write("while (");
                statement(w.condition());
                write(") ");
                block(w.body());
            }
            case Function f -> {
                write("function " + f.name());
                if (!f.parameters().isEmpty()) {
                    write("(");
                    parameters(f.parameters());
                    write(")");
                }
                write(" ");
                block(f.body());
            }
            case Try t -> {
                write("try ");
                block(t.body());
                for (Catch c : t.catches()) {
                    write(" catch ");
                    for (int i = 0; i < c.types().size(); i++)
                        write((i > 0 ? ", [" : "[") + c.types().get(i).name() + "]" + (i == c.types().size() - 1 ? " " : ""));
                    block(c.body());
                }
                t.fin().ifPresent(b -> {
                    write(" finally ");
                    block(b);
                });
            }
            case Throw t -> keyword("throw", t.value());
            case Return r -> keyword("return", r.value());
            case Exit e -> keyword("exit", e.value());
            case Break b -> write("break");
            case Continue c -> write("continue");
            case Comment c -> {
                requireLines(s);
                String[] lines = c.text().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) newline();
                    write(lines[i].isEmpty() ? "#" : "# " + lines[i]);
                }
            }
            case BlockComment c -> write("<#" + c.text() + "#>");
            case Blank b -> requireLines(s);
        }
    }

    private void requireLines(PsStatement s) {
        if (inline) throw new IllegalStateException("a line comment or blank line cannot go on one line: " + s);
    }

    private void keyword(String word, Optional<PsStatement> value) {
        write(word);
        value.ifPresent(v -> {
            write(" ");
            statement(v);
        });
    }

    private void parameters(List<PsParameter> ps) {
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) write(", ");
            PsParameter p = ps.get(i);
            p.type().ifPresent(t -> write("[" + t.name() + "]"));
            write(variable(p.name(), false));
            p.defaultValue().ifPresent(d -> {
                write(" = ");
                expression(d);
            });
        }
    }

    private void element(PsPipelineElement e) {
        switch (e) {
            case PsCommand c -> {
                if (c.invocation() != PsCommand.Invocation.NONE) write(c.invocation().token + " ");
                expression(c.name());
                for (PsCommandElement a : c.arguments()) {
                    write(" ");
                    switch (a) {
                        case PsCommandElement.Parameter p -> {
                            write("-" + p.name());
                            p.colonArgument().ifPresent(v -> {
                                write(":");
                                expression(v);
                            });
                        }
                        case PsExpression x -> expression(x);
                    }
                }
                for (PsRedirection r : c.redirections()) {
                    write(" ");
                    switch (r) {
                        case PsRedirection.ToFile f -> {
                            String stream = f.stream() == PsRedirection.Stream.OUTPUT ? "" : f.stream().token;
                            write(stream + (f.append() ? ">> " : "> "));
                            expression(f.target());
                        }
                        case PsRedirection.Merge m -> write(m.from().token + ">&" + m.to().token);
                    }
                }
            }
            case PsExpression x -> expression(x);
        }
    }

    // ---- expressions --------------------------------------------------------------------------------------------

    private void expression(PsExpression e) {
        switch (e) {
            case Variable v -> write(variable(v.name(), false));
            case Splat s -> write("@" + s.name());
            case IntLiteral i -> write(Long.toString(i.value()));
            case SingleQuoted q -> write(singleQuoted(q.value()));
            case Expandable x -> write(expandable(x.parts()));
            case BareWord w -> write(w.text());
            case ArrayLiteral a -> {
                for (int i = 0; i < a.elements().size(); i++) {
                    if (i > 0) write(", ");
                    expression(a.elements().get(i));
                }
            }
            case ArraySubexpression a -> {
                write("@(");
                inlineStatements(a.statements());
                write(")");
            }
            case SubExpression s -> {
                write("$(");
                inlineStatements(s.statements());
                write(")");
            }
            case Paren p -> {
                write("(");
                statement(p.statement());
                write(")");
            }
            case Hashtable h -> {
                if (h.entries().isEmpty()) {
                    write("@{}");
                    return;
                }
                write("@{ ");
                for (int i = 0; i < h.entries().size(); i++) {
                    if (i > 0) write("; ");
                    expression(h.entries().get(i).key());
                    write(" = ");
                    statement(h.entries().get(i).value());
                }
                write(" }");
            }
            case ScriptBlock b -> {
                // An argument reads better kept on its command's line, so a short if may join it there
                List<PsStatement> ss = b.body().statements();
                boolean flat = ss.stream().allMatch(s -> isSimple(s)
                        || s instanceof If f && bodies(f).allMatch(x -> x.statements().stream().allMatch(PsPrinter::isSimple)));
                if (!inline && flat && !ss.isEmpty()) {
                    String one = oneLine(ss);
                    if (!one.contains("\n") && column() + one.length() + 4 <= WIDTH) {
                        write("{ " + one + " }");
                        return;
                    }
                }
                block(b.body());
            }
            case Convert c -> {
                write("[" + c.type().name() + "]");
                expression(c.operand());
            }
            case TypeLiteral t -> write("[" + t.type().name() + "]");
            case Member m -> {
                expression(m.target());
                write((m.isStatic() ? "::" : ".") + m.name());
            }
            case InvokeMember m -> {
                expression(m.target());
                write((m.isStatic() ? "::" : ".") + m.name() + "(");
                for (int i = 0; i < m.arguments().size(); i++) {
                    if (i > 0) write(", ");
                    expression(m.arguments().get(i));
                }
                write(")");
            }
            case Index i -> {
                expression(i.target());
                write("[");
                expression(i.index());
                write("]");
            }
            case Unary u -> {
                write(u.operator().token);
                expression(u.operand());
            }
            case Binary b -> {
                expression(b.left());
                write(b.operator() == BinaryOperator.RANGE ? ".." : " " + b.operator().token + " ");
                expression(b.right());
            }
        }
    }

    /**
     * {@code $name}, or {@code ${name}} when the name has characters a plain variable cannot, or (in a string) when
     * the text after it would otherwise run into it: {@code "${Seconds}s"}, {@code "${repo}: ..."}.
     */
    private static String variable(String name, boolean followedByNameChar) {
        if (PLAIN_VARIABLE.matcher(name).matches() && !followedByNameChar) return "$" + name;
        return "${" + name.replace("`", "``").replace("}", "`}") + "}";
    }

    private static String singleQuoted(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            // PowerShell takes the typographic single quotes as quotes too
            if (c == '\'' || c == '‘' || c == '’' || c == '‚' || c == '‛') b.append(c);
            b.append(c);
        }
        return b.append('\'').toString();
    }

    private static String expandable(List<PsStringPart> parts) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < parts.size(); i++) {
            char next = i + 1 < parts.size() ? firstChar(parts.get(i + 1)) : '"';
            switch (parts.get(i)) {
                case PsStringPart.Text t -> {
                    String v = t.value();
                    for (int j = 0; j < v.length(); j++) {
                        char c = v.charAt(j);
                        char after = j + 1 < v.length() ? v.charAt(j + 1) : next;
                        switch (c) {
                            case '`' -> b.append("``");
                            case '"', '“', '”', '„' -> b.append('`').append(c);
                            case '\n' -> b.append("`n");
                            case '\r' -> b.append("`r");
                            case '\t' -> b.append("`t");
                            case '\0' -> b.append("`0");
                            case '$' -> b.append(startsVariable(after) ? "`$" : "$");
                            default -> b.append(c);
                        }
                    }
                }
                case Variable v -> b.append(variable(v.name(), next == ':' || next == '?' || isNameChar(next)));
                case SubExpression s -> {
                    PsPrinter p = new PsPrinter(true);
                    p.inlineStatements(s.statements());
                    b.append("$(").append(p.out).append(')');
                }
            }
        }
        return b.append('"').toString();
    }

    /** The first character a part prints as, or {@code "} for an empty text (none follows that matters). */
    private static char firstChar(PsStringPart part) {
        return switch (part) {
            case PsStringPart.Text t -> t.value().isEmpty() ? '"' : t.value().charAt(0);
            case Variable v -> '$';
            case SubExpression s -> '$';
        };
    }

    private static boolean startsVariable(char c) {
        return isNameChar(c) || "{(?^$:".indexOf(c) >= 0;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
