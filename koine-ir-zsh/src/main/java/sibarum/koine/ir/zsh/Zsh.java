package sibarum.koine.ir.zsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sibarum.koine.ir.zsh.ZshCommand.*;
import sibarum.koine.ir.zsh.ZshParameter.*;
import sibarum.koine.ir.zsh.ZshWordPart.*;

/**
 * Short factories for building Zsh IR by hand. Where a word is expected they take a string (literal text), a word
 * part, or a word.
 */
public final class Zsh {

    private Zsh() {
    }

    // ---- words --------------------------------------------------------------------------------------------------

    /** A word from strings (literal text), parts and words, in order. */
    public static ZshWord w(Object... parts) {
        List<ZshWordPart> ps = new ArrayList<>();
        for (Object o : parts)
            switch (o) {
                case String s -> ps.add(new Literal(s));
                case ZshWordPart p -> ps.add(p);
                case ZshWord x -> ps.addAll(x.parts());
                default -> throw new IllegalArgumentException("not a word part: " + o);
            }
        return new ZshWord(ps);
    }

    public static List<ZshWord> words(Object... words) {
        return java.util.Arrays.stream(words).map(Zsh::w).toList();
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
    public static ZshParameter p(String name) {
        return param(name).build();
    }

    /** {@code ${name[subscript]}}. */
    public static ZshParameter p(String name, String subscript) {
        return param(name).subscript(subscript).build();
    }

    /** {@code ${#name}}. */
    public static ZshParameter length(String name) {
        return param(name).prefix(Prefix.LENGTH).build();
    }

    /** {@code ${name:m1:m2}}: modifiers, as {@code ${0:A:h}}. */
    public static ZshParameter modified(String name, String... modifiers) {
        return param(name).modifiers(modifiers).build();
    }

    /** {@code ${name:-word}}. */
    public static ZshParameter orDefault(String name, Object... word) {
        return param(name).operation(new Default(true, DefaultKind.USE_DEFAULT, w(word))).build();
    }

    /** {@code ${name:?word}}. */
    public static ZshParameter orFail(String name, Object... word) {
        return param(name).operation(new Default(true, DefaultKind.ERROR, w(word))).build();
    }

    /** A parameter expansion of a name, built up a piece at a time. */
    public static ParameterBuilder param(String name) {
        return new ParameterBuilder(new Name(name));
    }

    /** A parameter expansion of another expansion: {@code ${(f)"$(cmd)"}}. */
    public static ParameterBuilder param(ZshWordPart nested) {
        return new ParameterBuilder(new Nested(nested));
    }

    public static final class ParameterBuilder {
        private final Target target;
        private final List<String> flags = new ArrayList<>();
        private final List<Prefix> prefixes = new ArrayList<>();
        private Optional<String> subscript = Optional.empty();
        private final List<String> modifiers = new ArrayList<>();
        private Optional<Operation> operation = Optional.empty();

        private ParameterBuilder(Target target) {
            this.target = target;
        }

        public ParameterBuilder flags(String... fs) {
            flags.addAll(List.of(fs));
            return this;
        }

        public ParameterBuilder prefix(Prefix p) {
            prefixes.add(p);
            return this;
        }

        public ParameterBuilder subscript(String s) {
            subscript = Optional.of(s);
            return this;
        }

        public ParameterBuilder modifiers(String... ms) {
            modifiers.addAll(List.of(ms));
            return this;
        }

        public ParameterBuilder operation(Operation op) {
            operation = Optional.of(op);
            return this;
        }

        public ZshParameter build() {
            return new ZshParameter(flags, prefixes, target, subscript, modifiers, operation);
        }
    }

    /** {@code $(commands)}. */
    public static CommandSubstitution sub(ZshCommand... commands) {
        return new CommandSubstitution(List.of(commands));
    }

    // ---- simple commands ----------------------------------------------------------------------------------------

    /** A simple command of words. */
    public static Simple cmd(Object... words) {
        return new Simple(List.of(), words(words), List.of());
    }

    /** A simple command of assignments only. */
    public static Simple assigns(ZshAssignment... assignments) {
        return new Simple(List.of(assignments), List.of(), List.of());
    }

    /** {@code name=word}; with no parts, {@code name=}. */
    public static ZshAssignment set(String name, Object... word) {
        return new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Scalar(w(word)));
    }

    /** {@code name[key]=word}. */
    public static ZshAssignment setAt(String name, String subscript, Object... word) {
        return new ZshAssignment(name, Optional.of(subscript), false, new ZshAssignment.Scalar(w(word)));
    }

    /** {@code name=(words)}. */
    public static ZshAssignment array(String name, Object... words) {
        return new ZshAssignment(name, Optional.empty(), false, new ZshAssignment.Array(words(words)));
    }

    /** {@code name+=(words)}. */
    public static ZshAssignment appendArray(String name, Object... words) {
        return new ZshAssignment(name, Optional.empty(), true, new ZshAssignment.Array(words(words)));
    }

    /** {@code > target}. */
    public static ZshRedirection.ToFile to(Object... target) {
        return new ZshRedirection.ToFile(Optional.empty(), ZshRedirection.FileOperator.OUT, w(target));
    }

    /** {@code fd> target}. */
    public static ZshRedirection.ToFile toFd(int fd, Object... target) {
        return new ZshRedirection.ToFile(Optional.of(fd), ZshRedirection.FileOperator.OUT, w(target));
    }

    /** {@code 2>&1}. */
    public static ZshRedirection.Duplicate errToOut() {
        return new ZshRedirection.Duplicate(Optional.of(2), true, "1");
    }

    /** {@code >&2}. */
    public static ZshRedirection.Duplicate toErr() {
        return new ZshRedirection.Duplicate(Optional.empty(), true, "2");
    }

    public static ZshRedirection.HereString hereString(Object... word) {
        return new ZshRedirection.HereString(Optional.empty(), w(word));
    }

    /** {@code <<'delimiter'}: a body where nothing expands. */
    public static ZshRedirection.HereDoc hereDoc(String delimiter, String body) {
        return new ZshRedirection.HereDoc(Optional.empty(), delimiter, true, false, List.of(new Literal(body)));
    }

    /** {@code <<delimiter}: a body of strings (literal text) and expansions. */
    public static ZshRedirection.HereDoc expandingHereDoc(String delimiter, Object... body) {
        return new ZshRedirection.HereDoc(Optional.empty(), delimiter, false, false, w(body).parts());
    }

    // ---- lists and compound commands ----------------------------------------------------------------------------

    public static Pipeline pipe(ZshCommand... commands) {
        return new Pipeline(false, List.of(commands));
    }

    /** {@code ! command}. */
    public static Pipeline not(ZshCommand command) {
        return new Pipeline(true, List.of(command));
    }

    public static AndOr and(ZshCommand first, ZshCommand second) {
        return new AndOr(first, List.of(new Link(true, second)));
    }

    public static AndOr or(ZshCommand first, ZshCommand second) {
        return new AndOr(first, List.of(new Link(false, second)));
    }

    public static Background bg(ZshCommand command) {
        return new Background(command, false);
    }

    public static BraceGroup group(ZshCommand... body) {
        return new BraceGroup(List.of(body));
    }

    public static Subshell subshell(ZshCommand... body) {
        return new Subshell(List.of(body));
    }

    public static Redirected redirected(ZshCommand command, ZshRedirection... redirections) {
        return new Redirected(command, List.of(redirections));
    }

    public static If if_(ZshCommand condition, ZshCommand... body) {
        return new If(List.of(new Clause(List.of(condition), List.of(body))), Optional.empty());
    }

    public static List<ZshCommand> body(ZshCommand... commands) {
        return List.of(commands);
    }

    public static For for_(String variable, List<ZshWord> words, ZshCommand... body) {
        return new For(List.of(variable), Optional.of(words), List.of(body));
    }

    public static While while_(ZshCommand condition, ZshCommand... body) {
        return new While(false, List.of(condition), List.of(body));
    }

    public static Case case_(ZshWord subject, CaseItem... items) {
        return new Case(subject, List.of(items));
    }

    /** {@code pattern|pattern) body ;;}. */
    public static CaseItem item(List<ZshWord> patterns, ZshCommand... body) {
        return new CaseItem(patterns, List.of(body), CaseTerminator.BREAK);
    }

    /** {@code [[ condition ]]}. */
    public static Conditional test(ZshCondition condition) {
        return new Conditional(condition);
    }

    /** {@code (( expression ))}. */
    public static Arithmetic arith(String expression) {
        return new Arithmetic(expression);
    }

    /** {@code name() body}. */
    public static Function function(String name, ZshCommand body) {
        return new Function(List.of(name), false, body);
    }

    public static Comment comment(String text) {
        return new Comment(text);
    }

    public static Blank blank() {
        return new Blank();
    }
}
