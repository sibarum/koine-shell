package sibarum.koine.ir.bash;

import java.util.ArrayList;
import java.util.List;

import sibarum.koine.ir.bash.BashCommand.*;
import sibarum.koine.ir.bash.BashParameter.*;
import sibarum.koine.ir.bash.BashWordPart.*;

/**
 * Prints a Bash IR tree as Bash source. It adds nothing the tree does not hold; it chooses the layout (four-space
 * indentation, a short body on one line), the backslashes a literal needs where it stands, and braces around a
 * parameter only where Bash needs them.
 */
public final class BashPrinter {

    private static final int WIDTH = 120;

    /** Where a word stands, which decides what its literal text must escape. */
    private enum Context {
        /** A command's word, a redirection's target, a pattern: unquoted. An empty word prints as {@code ''}. */
        ARG,
        /** An assignment's value: unquoted, and empty prints as nothing. */
        ASSIGN,
        /** The word in {@code ${x:-word}}: unquoted, but blanks and operators are plain characters there. */
        BRACE,
        /** The pattern in {@code ${x/pattern/...}}: as {@link #BRACE}, and a slash ends it. */
        BRACE_PATTERN,
        /** Inside double quotes. */
        DOUBLE,
        /** The body of a here-document whose delimiter is not quoted. */
        HEREDOC
    }

    private final StringBuilder out = new StringBuilder();
    /** Every body on one line: inside {@code $(...)}, and while trying a body on one line. */
    private final boolean inline;
    private final List<BashRedirection.HereDoc> pendingHereDocs = new ArrayList<>();
    private int depth;
    private boolean lineStart = true;

    private BashPrinter(boolean inline) {
        this.inline = inline;
    }

    public static String print(BashScript script) {
        BashPrinter p = new BashPrinter(false);
        script.interpreter().ifPresent(i -> {
            p.write("#!" + i);
            p.newline();
        });
        p.lines(script.body());
        return p.out.toString();
    }

    public static String print(BashCommand command) {
        BashPrinter p = new BashPrinter(false);
        p.command(command);
        if (!p.pendingHereDocs.isEmpty()) p.newline();
        return p.out.toString();
    }

    public static String print(BashWord word) {
        return new BashPrinter(false).word(word, Context.ARG);
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

    /** Ends the line, and then writes the bodies of the here-documents it started. */
    private void newline() {
        out.append('\n');
        for (BashRedirection.HereDoc h : pendingHereDocs) {
            String body = hereDocBody(h);
            out.append(body);
            if (!body.isEmpty() && !body.endsWith("\n")) out.append('\n');
            out.append(h.delimiter()).append('\n');
        }
        pendingHereDocs.clear();
        lineStart = true;
    }

    private int column() {
        return lineStart ? 4 * depth : out.length() - out.lastIndexOf("\n") - 1;
    }

    private void lines(List<BashCommand> commands) {
        for (BashCommand c : commands) {
            command(c);
            newline();
        }
    }

    /** Commands on one line: {@code ;} between them, or the {@code &} that already ends one. */
    private void inlineList(List<BashCommand> commands) {
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) separate(commands.get(i - 1));
            command(commands.get(i));
        }
    }

    private void separate(BashCommand previous) {
        if (!pendingHereDocs.isEmpty()) newline();
        else write(previous instanceof Background ? " " : "; ");
    }

    /** What ends the last command of a one-line body before a closing word ({@code fi}, {@code done}, {@code }}). */
    private void terminate(List<BashCommand> commands) {
        if (!pendingHereDocs.isEmpty()) newline();
        else if (!commands.isEmpty() && !(commands.getLast() instanceof Background)) write(";");
        write(" ");
    }

    /** The commands on one line, or null when they cannot go on one (a here-document needs lines of its own). */
    private static String oneLine(BashCommand c) {
        BashPrinter p = new BashPrinter(true);
        p.command(c);
        String s = p.out.toString();
        return p.pendingHereDocs.isEmpty() && !s.contains("\n") ? s : null;
    }

    private static String inlineString(List<BashCommand> commands) {
        BashPrinter p = new BashPrinter(true);
        p.inlineList(commands);
        if (!p.pendingHereDocs.isEmpty()) p.newline();
        return p.out.toString();
    }

    /** Writes a compound command on one line when it is all simple commands and fits; reports whether it did. */
    private boolean triedOneLine(BashCommand c) {
        if (inline) return false;
        if (!isSimpleCompound(c)) return false;
        String one = oneLine(c);
        if (one == null || column() + one.length() > WIDTH) return false;
        write(one);
        return true;
    }

    private static boolean isSimpleCompound(BashCommand c) {
        return switch (c) {
            case If f -> f.clauses().stream().allMatch(x -> allSimple(x.condition()) && allSimple(x.body()))
                    && f.otherwise().map(BashPrinter::allSimple).orElse(true);
            case For f -> allSimple(f.body());
            case ArithmeticFor f -> allSimple(f.body());
            case While w -> allSimple(w.condition()) && allSimple(w.body());
            case Case k -> k.items().size() == 1 && k.items().stream().allMatch(i -> i.body().stream().allMatch(BashPrinter::isSimple));
            case BraceGroup g -> allSimple(g.body());
            case Subshell s -> allSimple(s.body());
            default -> isSimple(c);
        };
    }

    private static boolean allSimple(List<BashCommand> commands) {
        return !commands.isEmpty() && commands.stream().allMatch(BashPrinter::isSimple);
    }

    /** A command that can share a line: no body of its own, or only groups of such commands. */
    private static boolean isSimple(BashCommand c) {
        return switch (c) {
            case Simple s -> true;
            case Arithmetic a -> true;
            case Conditional x -> true;
            case Pipeline p -> p.commands().stream().allMatch(BashPrinter::isSimple);
            case AndOr a -> isSimple(a.first()) && a.rest().stream().allMatch(l -> isSimple(l.command()));
            case Background b -> isSimple(b.command());
            case BraceGroup g -> allSimple(g.body());
            case Subshell s -> allSimple(s.body());
            case Redirected r -> isSimple(r.command());
            default -> false;
        };
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    private void command(BashCommand c) {
        switch (c) {
            case Simple s -> {
                List<String> items = new ArrayList<>();
                for (BashAssignment a : s.assignments()) items.add(assignment(a));
                for (BashWord w : s.words()) items.add(word(w, Context.ARG));
                write(String.join(" ", items));
                for (BashRedirection r : s.redirections()) {
                    if (!items.isEmpty()) write(" ");
                    redirection(r);
                    items.add("");
                }
            }
            case Pipeline p -> {
                if (p.negated()) write("! ");
                for (int i = 0; i < p.commands().size(); i++) {
                    if (i > 0) write(" | ");
                    command(p.commands().get(i));
                }
            }
            case AndOr a -> {
                command(a.first());
                for (Link l : a.rest()) {
                    write(l.and() ? " && " : " || ");
                    command(l.command());
                }
            }
            case Background b -> {
                command(b.command());
                write(" &");
            }
            case BraceGroup g -> group("{", "}", g.body(), true);
            case Subshell s -> group("(", ")", s.body(), false);
            case If f -> {
                if (triedOneLine(f)) return;
                for (int i = 0; i < f.clauses().size(); i++) {
                    Clause cl = f.clauses().get(i);
                    write(i == 0 ? "if " : (inline ? "elif " : "elif "));
                    inlineList(cl.condition());
                    write("; then");
                    body(cl.body());
                }
                f.otherwise().ifPresent(b -> {
                    write("else");
                    body(b);
                });
                write("fi");
            }
            case For f -> {
                if (triedOneLine(f)) return;
                write("for " + f.variable());
                f.words().ifPresent(ws -> {
                    write(" in");
                    for (BashWord w : ws) write(" " + word(w, Context.ARG));
                });
                write("; do");
                body(f.body());
                write("done");
            }
            case ArithmeticFor f -> {
                if (triedOneLine(f)) return;
                write("for ((" + f.init() + "; " + f.test() + "; " + f.update() + ")); do");
                body(f.body());
                write("done");
            }
            case While w -> {
                if (triedOneLine(w)) return;
                write(w.until() ? "until " : "while ");
                inlineList(w.condition());
                write("; do");
                body(w.body());
                write("done");
            }
            case Case k -> {
                if (triedOneLine(k)) return;
                write("case " + word(k.subject(), Context.ARG) + " in");
                if (inline) {
                    for (CaseItem item : k.items()) {
                        write(" ");
                        caseItem(item);
                    }
                    write(" esac");
                    return;
                }
                newline();
                depth++;
                for (CaseItem item : k.items()) {
                    caseItem(item);
                    newline();
                }
                depth--;
                write("esac");
            }
            case Arithmetic a -> write("(( " + a.expression() + " ))");
            case Conditional x -> write("[[ " + condition(x.condition()) + " ]]");
            case Function f -> {
                write(f.keyword() ? "function " + f.name() + " " : f.name() + "() ");
                command(f.body());
            }
            case Redirected r -> {
                command(r.command());
                for (BashRedirection x : r.redirections()) {
                    write(" ");
                    redirection(x);
                }
            }
            case Comment k -> {
                requireLines(c);
                String[] ls = k.text().split("\n", -1);
                for (int i = 0; i < ls.length; i++) {
                    if (i > 0) newline();
                    write(ls[i].isEmpty() ? "#" : "# " + ls[i]);
                }
            }
            case Blank b -> requireLines(c);
        }
    }

    private void requireLines(BashCommand c) {
        if (inline) throw new IllegalStateException("a comment or blank line cannot go on one line: " + c);
    }

    /** A body after {@code then} or {@code do}: on the same line in one-line mode, else indented on its own lines. */
    private void body(List<BashCommand> commands) {
        if (inline) {
            write(" ");
            inlineList(commands);
            terminate(commands);
            return;
        }
        newline();
        depth++;
        lines(commands);
        depth--;
    }

    private void group(String open, String close, List<BashCommand> commands, boolean brace) {
        boolean oneLine = inline;
        if (!oneLine && allSimple(commands)) {
            String one = inlineString(commands);
            oneLine = !one.contains("\n") && column() + one.length() + 5 <= WIDTH;
        }
        if (oneLine) {
            // { a; b; } needs its blanks and semicolon, (a; b) neither
            write(brace ? open + " " : open);
            inlineList(commands);
            if (brace) terminate(commands);
            else if (!pendingHereDocs.isEmpty()) newline();
            write(close);
            return;
        }
        write(open);
        newline();
        depth++;
        lines(commands);
        depth--;
        write(close);
    }

    private void caseItem(CaseItem item) {
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < item.patterns().size(); i++)
            head.append(i > 0 ? "|" : "").append(word(item.patterns().get(i), Context.ARG));
        head.append(")");
        write(head.toString());
        if (item.body().isEmpty()) {
            write(" " + item.terminator().token);
            return;
        }
        if (!inline && allSimple(item.body())) {
            String one = inlineString(item.body());
            if (!one.contains("\n") && column() + one.length() + 5 <= WIDTH) {
                write(" " + one + " " + item.terminator().token);
                return;
            }
        }
        if (inline) {
            write(" ");
            inlineList(item.body());
            write(" " + item.terminator().token);
            return;
        }
        newline();
        depth++;
        lines(item.body());
        write(item.terminator().token);
        depth--;
    }

    private String assignment(BashAssignment a) {
        String head = a.name() + a.subscript().map(s -> "[" + s + "]").orElse("") + (a.append() ? "+=" : "=");
        return switch (a.value()) {
            case BashAssignment.Scalar s -> head + word(s.word(), Context.ASSIGN);
            case BashAssignment.Array arr -> head + "(" + String.join(" ", arr.elements().stream().map(w -> word(w, Context.ARG)).toList()) + ")";
        };
    }

    private void redirection(BashRedirection r) {
        switch (r) {
            case BashRedirection.ToFile f -> {
                // >/dev/null, but > "$log": a plain path reads as one with its operator
                boolean plain = f.target().parts().size() == 1 && f.target().parts().getFirst() instanceof Literal;
                write(fd(f.fd()) + f.operator().token + (plain ? "" : " ") + word(f.target(), Context.ARG));
            }
            case BashRedirection.Duplicate d -> write(fd(d.fd()) + (d.output() ? ">&" : "<&") + d.target());
            case BashRedirection.HereString h -> write(fd(h.fd()) + "<<< " + word(h.word(), Context.ARG));
            case BashRedirection.HereDoc h -> {
                write(fd(h.fd()) + (h.stripTabs() ? "<<-" : "<<") + (h.quoted() ? "'" + h.delimiter() + "'" : h.delimiter()));
                pendingHereDocs.add(h);
            }
        }
    }

    private static String fd(java.util.Optional<Integer> fd) {
        return fd.map(String::valueOf).orElse("");
    }

    private String condition(BashCondition c) {
        return switch (c) {
            case BashCondition.Unary u -> u.test().token + " " + word(u.operand(), Context.ARG);
            case BashCondition.Binary b ->
                    word(b.left(), Context.ARG) + " " + b.test().token + " " + word(b.right(), Context.ARG);
            case BashCondition.Word w -> word(w.word(), Context.ARG);
            case BashCondition.Not n -> "! " + condition(n.operand());
            case BashCondition.And a -> condition(a.left()) + " && " + condition(a.right());
            case BashCondition.Or o -> condition(o.left()) + " || " + condition(o.right());
            case BashCondition.Paren p -> "( " + condition(p.inner()) + " )";
        };
    }

    // ---- words --------------------------------------------------------------------------------------------------

    private String word(BashWord w, Context context) {
        if (w.parts().isEmpty()) return context == Context.ARG ? "''" : "";
        return parts(w.parts(), context, '\0');
    }

    /** Parts printed right to left, so that each parameter knows what follows it. */
    private String parts(List<BashWordPart> parts, Context context, char after) {
        String[] printed = new String[parts.size()];
        char next = after;
        for (int i = parts.size() - 1; i >= 0; i--) {
            printed[i] = part(parts.get(i), context, next, i == 0);
            if (!printed[i].isEmpty()) next = printed[i].charAt(0);
        }
        return String.join("", printed);
    }

    private String part(BashWordPart p, Context context, char next, boolean wordStart) {
        return switch (p) {
            case Literal l -> literal(l.text(), context, wordStart);
            case Glob g -> g.pattern();
            case Tilde t -> "~";
            case SingleQuoted q -> "'" + q.text() + "'";
            case AnsiC a -> "$'" + ansiC(a.text()) + "'";
            case DoubleQuoted d -> "\"" + parts(d.parts(), Context.DOUBLE, '"') + "\"";
            case BashParameter x -> parameter(x, next);
            case CommandSubstitution s -> {
                String inner = inlineString(s.commands());
                yield "$(" + (inner.startsWith("(") ? " " : "") + inner + ")";
            }
            case ArithmeticExpansion a -> "$((" + a.expression() + "))";
            case ProcessSubstitution s -> (s.output() ? ">(" : "<(") + inlineString(s.commands()) + ")";
        };
    }

    private static String literal(String text, Context context, boolean wordStart) {
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < text.length(); j++) {
            char c = text.charAt(j);
            boolean escape = switch (context) {
                case ARG, ASSIGN -> " \t|&;<>()$`\\\"'*?{}".indexOf(c) >= 0
                        || (c == '[' && text.indexOf(']', j) > j)
                        || ((c == '#' || c == '~') && j == 0 && wordStart);
                case BRACE, BRACE_PATTERN -> "}$`\\\"'*?".indexOf(c) >= 0
                        || (c == '[' && text.indexOf(']', j) > j)
                        || (c == '/' && context == Context.BRACE_PATTERN);
                case DOUBLE, HEREDOC -> "$`\\".indexOf(c) >= 0 || (c == '"' && context == Context.DOUBLE);
            };
            if (c == '\n' && (context == Context.ARG || context == Context.ASSIGN)) b.append("'\n'");
            else if (escape) b.append('\\').append(c);
            else b.append(c);
        }
        return b.toString();
    }

    private static String ansiC(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\'' -> b.append("\\'");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                case 27 -> b.append("\\e");
                default -> {
                    if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    private String parameter(BashParameter p, char next) {
        boolean simple = p.prefix() == Prefix.NONE && p.subscript().isEmpty() && p.operation().isEmpty();
        boolean special = p.name().length() == 1 && "#?!@*$-0123456789".indexOf(p.name().charAt(0)) >= 0;
        boolean identifier = p.name().matches("[A-Za-z_][A-Za-z0-9_]*");
        if (simple && (special || identifier && !(Character.isLetterOrDigit(next) || next == '_'))) return "$" + p.name();
        StringBuilder b = new StringBuilder("${").append(p.prefix().token).append(p.name());
        p.subscript().ifPresent(s -> b.append('[').append(s).append(']'));
        p.operation().ifPresent(op -> b.append(operation(op)));
        return b.append('}').toString();
    }

    private String operation(Operation op) {
        return switch (op) {
            case Default d -> (d.colon() ? ":" : "") + d.kind().token + word(d.word(), Context.BRACE);
            case Remove r -> (r.fromEnd() ? "%" : "#").repeat(r.longest() ? 2 : 1) + word(r.pattern(), Context.BRACE);
            case Replace r -> r.mode().token + word(r.pattern(), Context.BRACE_PATTERN)
                    + r.replacement().map(x -> "/" + word(x, Context.BRACE)).orElse("");
            case Substring s -> ":" + s.offset() + s.length().map(l -> ":" + l).orElse("");
            case CaseChange c -> (c.upper() ? "^" : ",").repeat(c.all() ? 2 : 1)
                    + c.pattern().map(x -> word(x, Context.BRACE)).orElse("");
            case Transform t -> "@" + t.operator();
        };
    }

    private String hereDocBody(BashRedirection.HereDoc h) {
        if (h.quoted()) {
            StringBuilder b = new StringBuilder();
            for (BashWordPart p : h.body()) b.append(((Literal) p).text());
            return b.toString();
        }
        return parts(h.body(), Context.HEREDOC, '\n');
    }
}
