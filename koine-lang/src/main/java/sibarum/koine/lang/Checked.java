package sibarum.koine.lang;

import java.util.List;
import java.util.Optional;

/**
 * A file as the checker left it: its problems, and, when there are none, what the compiler needs. Every expression's
 * type, every declaration's, what every name and call resolved to, and the modules the file uses, loaded and checked.
 */
public final class Checked {

    private final Checker checker;

    Checked(Checker checker) {
        this.checker = checker;
    }

    public List<Problem> problems() {
        return checker.problems();
    }

    /** The script that was checked; empty when it was a module. */
    public Optional<Checker.Unit> script() {
        return Optional.ofNullable(checker.scriptUnit());
    }

    /** The expression's type, as far as it is known; a {@link Type.Var} where it is not. */
    public Type type(Expr e) {
        Type t = checker.types().get(e);
        if (t == null) throw new IllegalArgumentException("not checked: " + e);
        return Type.prune(t);
    }

    /** The type of what a {@code let}, a {@code for} or a parameter introduces. */
    public Type declared(Object node) {
        Type t = checker.declared().get(node);
        if (t == null) throw new IllegalArgumentException("not a declaration that was checked: " + node);
        return Type.prune(t);
    }

    public Resolution resolution(Expr e) {
        Resolution r = checker.resolutions().get(e);
        if (r == null) throw new IllegalArgumentException("not resolved: " + e);
        return r;
    }

    /** A module, loaded and checked now if the file did not name it: {@code step} needs {@code Script}. */
    public Optional<Checker.Unit> module(String name) {
        return Optional.ofNullable(checker.load(name));
    }
}
