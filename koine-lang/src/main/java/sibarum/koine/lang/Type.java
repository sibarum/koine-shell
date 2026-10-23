package sibarum.koine.lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A type, as the checker knows it. An empty list, an empty map, and a parameter or result a script's {@code def} leaves
 * out start as a {@link Var}, which the first use that says more {@link #unify unifies} with a known type.
 */
sealed interface Type {

    enum Prim implements Type {
        STRING("String"), NUMBER("Number"), BOOL("Bool"),
        /** What a read such as {@code Json.read} gives: it can only be read into a record with {@code as}. */
        DATA("Data"),
        /** A block of the caller's code, given to a member that takes one. */
        BLOCK("Block"),
        /** The "result" of a member that has none. */
        NOTHING("nothing"),
        /** The type of something already reported as wrong; it agrees with everything, so one mistake is one problem. */
        ERROR("error");

        final String name;

        Prim(String name) {
            this.name = name;
        }
    }

    record ListOf(Type element) implements Type {
    }

    record MapOf(Type key, Type value) implements Type {
    }

    /** A record. Two are the same type only if they are the same declaration. */
    final class Rec implements Type {
        final String name;
        /** The module that declares it, or null for a script's own record. */
        final String module;
        final Map<String, Field> fields = new LinkedHashMap<>();

        Rec(String name, String module) {
            this.name = name;
            this.module = module;
        }
    }

    record Field(Type type, boolean optional) {
    }

    /** A type not known yet. */
    final class Var implements Type {
        Type bound;
    }

    static Type prune(Type t) {
        while (t instanceof Var v && v.bound != null) t = v.bound;
        return t;
    }

    /** Makes {@code a} and {@code b} the same type if they can be, binding what is not known yet. */
    static boolean unify(Type a, Type b) {
        a = prune(a);
        b = prune(b);
        if (a == b || a == Prim.ERROR || b == Prim.ERROR) return true;
        if (a instanceof Var v) return bind(v, b);
        if (b instanceof Var v) return bind(v, a);
        if (a instanceof ListOf x && b instanceof ListOf y) return unify(x.element(), y.element());
        if (a instanceof MapOf x && b instanceof MapOf y) return unify(x.key(), y.key()) && unify(x.value(), y.value());
        return false;
    }

    private static boolean bind(Var v, Type t) {
        if (occurs(v, t)) return false;
        v.bound = t;
        return true;
    }

    private static boolean occurs(Var v, Type t) {
        t = prune(t);
        return t == v
                || t instanceof ListOf l && occurs(v, l.element())
                || t instanceof MapOf m && (occurs(v, m.key()) || occurs(v, m.value()));
    }

    static boolean known(Type t) {
        return !(prune(t) instanceof Var);
    }

    /** How the type reads in a message: {@code String}, {@code [Binary]}, {@code a map of String to String}. */
    static String describe(Type t) {
        return switch (prune(t)) {
            case Prim p -> p.name;
            case ListOf l -> "[" + describe(l.element()) + "]";
            case MapOf m -> "a map of " + describe(m.key()) + " to " + describe(m.value());
            case Rec r -> r.name;
            case Var v -> "a type not known yet";
        };
    }
}
