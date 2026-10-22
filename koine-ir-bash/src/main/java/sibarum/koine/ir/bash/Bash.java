package sibarum.koine.ir.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.bash.BashCommand.*;
import sibarum.koine.ir.bash.BashParameter.*;
import sibarum.koine.ir.bash.BashWordPart.*;

/**
 * Short factories for building Bash IR by hand. Where a word is expected they take a string (literal text), a word
 * part, or a word.
 */
public final class Bash {

    private Bash() {
    }

    // ---- words --------------------------------------------------------------------------------------------------

    /** A word from strings (literal text), parts and words, in order. */
    public static BashWord w(Object... parts) {
        List<BashWordPart> ps = new ArrayList<>();
        for (Object o : parts)
            switch (o) {
                case String s -> ps.add(new Literal(s));
                case BashWordPart p -> ps.add(p);
                case BashWord x -> ps.addAll(x.parts());
                default -> throw new IllegalArgumentException("not a word part: " + o);
            }
        return new BashWord(ps);
    }

    public static List<BashWord> words(Object... words) {
        return java.util.Arrays.stream(words).map(Bash::w).toList();
    }

    /** {@code "..."} from strings (literal text) and parts. */
    public static DoubleQuoted dq(Object... parts) {
        return new DoubleQuoted(w(parts).parts());
    }

    public static SingleQuoted sq(String text) {
        return new SingleQuoted(text);
    }

    public static AnsiC ansi(String text) {
        return new AnsiC(text);
    }

    public static Glob glob(String pattern) {
        return new Glob(pattern);
    }

    /** {@code $name}. */
    public static BashParameter p(String name) {
        return new BashParameter(Prefix.NONE, name, Optional.empty(), Optional.empty());
    }

    /** {@code ${name[subscript]}}. */
    public static BashParameter p(String name, String subscript) {
        return new BashParameter(Prefix.NONE, name, Optional.of(subscript), Optional.empty());
    }

    /** {@code ${#name[subscript]}}. */
    public static BashParameter length(String name, String subscript) {
        return new BashParameter(Prefix.LENGTH, name, Optional.of(subscript), Optional.empty());
    }

    /** {@code ${name:-word}}. */
    public static BashParameter orDefault(String name, Object... word) {
        return op(name, new Default(true, DefaultKind.USE_DEFAULT, w(word)));
    }

    /** {@code ${name:?word}}. */
    public static BashParameter orFail(String name, Object... word) {
        return op(name, new Default(true, DefaultKind.ERROR, w(word)));
    }

    public static BashParameter op(String name, Operation operation) {
        return new BashParameter(Prefix.NONE, name, Optional.empty(), Optional.of(operation));
    }

    public static BashParameter op(String name, String subscript, Operation operation) {
        return new BashParameter(Prefix.NONE, name, Optional.of(subscript), Optional.of(operation));
    }

    /** {@code $(commands)}. */
    public static CommandSubstitution sub(BashCommand... commands) {
        return new CommandSubstitution(List.of(commands));
    }

    // ---- simple commands ----------------------------------------------------------------------------------------

    /** A simple command of words. */
    public static Simple cmd(Object... words) {
        return new Simple(List.of(), words(words), List.of());
    }

    /** A simple command of assignments only. */
    public static Simple assigns(BashAssignment... assignments) {
        return new Simple(List.of(assignments), List.of(), List.of());
    }

    /** {@code name=word}; with no parts, {@code name=}. */
    public static BashAssignment set(String name, Object... word) {
        return new BashAssignment(name, Optional.empty(), false, new BashAssignment.Scalar(w(word)));
    }

    /** {@code name=(words)}. */
    public static BashAssignment array(String name, Object... words) {
        return new BashAssignment(name, Optional.empty(), false, new BashAssignment.Array(words(words)));
    }

    /** {@code name+=(words)}. */
    public static BashAssignment appendArray(String name, Object... words) {
        return new BashAssignment(name, Optional.empty(), true, new BashAssignment.Array(words(words)));
    }

    /** {@code > target}. */
    public static BashRedirection.ToFile to(Object... target) {
        return new BashRedirection.ToFile(Optional.empty(), BashRedirection.FileOperator.OUT, w(target));
    }

    /** {@code fd> target}. */
    public static BashRedirection.ToFile toFd(int fd, Object... target) {
        return new BashRedirection.ToFile(Optional.of(fd), BashRedirection.FileOperator.OUT, w(target));
    }

    /** {@code 2>&1}. */
    public static BashRedirection.Duplicate errToOut() {
        return new BashRedirection.Duplicate(Optional.of(2), true, "1");
    }

    /** {@code >&2}. */
    public static BashRedirection.Duplicate toErr() {
        return new BashRedirection.Duplicate(Optional.empty(), true, "2");
    }

    public static BashRedirection.HereString hereString(Object... word) {
        return new BashRedirection.HereString(Optional.empty(), w(word));
    }

    /** {@code <<'delimiter'}: a body where nothing expands. */
    public static BashRedirection.HereDoc hereDoc(String delimiter, String body) {
        return new BashRedirection.HereDoc(Optional.empty(), delimiter, true, false, List.of(new Literal(body)));
    }

    /** {@code <<delimiter}: a body of strings (literal text) and expansions. */
    public static BashRedirection.HereDoc expandingHereDoc(String delimiter, Object... body) {
        return new BashRedirection.HereDoc(Optional.empty(), delimiter, false, false, w(body).parts());
    }

    // ---- lists and compound commands ----------------------------------------------------------------------------

    public static Pipeline pipe(BashCommand... commands) {
        return new Pipeline(false, List.of(commands));
    }

    /** {@code ! command}. */
    public static Pipeline not(BashCommand command) {
        return new Pipeline(true, List.of(command));
    }

    public static AndOr and(BashCommand first, BashCommand second) {
        return new AndOr(first, List.of(new Link(true, second)));
    }

    public static AndOr or(BashCommand first, BashCommand second) {
        return new AndOr(first, List.of(new Link(false, second)));
    }

    public static Background bg(BashCommand command) {
        return new Background(command);
    }

    public static BraceGroup group(BashCommand... body) {
        return new BraceGroup(List.of(body));
    }

    public static Subshell subshell(BashCommand... body) {
        return new Subshell(List.of(body));
    }

    public static Redirected redirected(BashCommand command, BashRedirection... redirections) {
        return new Redirected(command, List.of(redirections));
    }

    public static If if_(BashCommand condition, BashCommand... body) {
        return new If(List.of(new Clause(List.of(condition), List.of(body))), Optional.empty());
    }

    public static List<BashCommand> body(BashCommand... commands) {
        return List.of(commands);
    }

    public static For for_(String variable, List<BashWord> words, BashCommand... body) {
        return new For(variable, Optional.of(words), List.of(body));
    }

    public static While while_(BashCommand condition, BashCommand... body) {
        return new While(false, List.of(condition), List.of(body));
    }

    public static Case case_(BashWord subject, CaseItem... items) {
        return new Case(subject, List.of(items));
    }

    /** {@code pattern|pattern) body ;;}. */
    public static CaseItem item(List<BashWord> patterns, BashCommand... body) {
        return new CaseItem(patterns, List.of(body), CaseTerminator.BREAK);
    }

    /** {@code [[ condition ]]}. */
    public static Conditional test(BashCondition condition) {
        return new Conditional(condition);
    }

    /** {@code name() body}. */
    public static Function function(String name, BashCommand body) {
        return new Function(name, false, body);
    }

    public static Comment comment(String text) {
        return new Comment(text);
    }

    public static Blank blank() {
        return new Blank();
    }
}
