package sibarum.koine.ir;

import java.util.List;

/**
 * A portable expression: what a value means, not how one shell spells it. Every expression knows its {@link Type},
 * and a record whose operands have the wrong types cannot be built.
 */
public sealed interface Expr {

    Type type();

    // ---- literals and variables ---------------------------------------------------------------------------------

    record StringLit(String value) implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    record IntLit(long value) implements Expr {
        public Type type() {
            return Type.INT;
        }
    }

    record BoolLit(boolean value) implements Expr {
        public Type type() {
            return Type.BOOL;
        }
    }

    record Ref(Var var) implements Expr {
        public Type type() {
            return var.type();
        }
    }

    /** The parts, strings and numbers, written one after another. */
    record Interp(List<Expr> parts) implements Expr {
        public Interp {
            parts = List.copyOf(parts);
            for (Expr p : parts) Type.requireOneOf(p, "a part of a string", Type.STRING, Type.INT);
        }

        public Type type() {
            return Type.STRING;
        }
    }

    // ---- collections --------------------------------------------------------------------------------------------

    record ListLit(Type element, List<Expr> items) implements Expr {
        public ListLit {
            items = List.copyOf(items);
            for (Expr i : items) Type.require(i, element, "a list item");
        }

        public Type type() {
            return Type.listOf(element);
        }
    }

    record EmptyMap(Type value) implements Expr {
        public Type type() {
            return Type.mapOf(value);
        }
    }

    /** The number of items in a list or entries in a map. */
    record Count(Expr collection) implements Expr {
        public Count {
            if (!(collection.type() instanceof Type.ListOf || collection.type() instanceof Type.MapOf))
                throw new IllegalArgumentException("only a list or a map has a count: " + collection);
        }

        public Type type() {
            return Type.INT;
        }
    }

    /** The item at a zero-based index. */
    record At(Expr list, Expr index) implements Expr {
        public At {
            Type.elementOf(list, "what is indexed");
            Type.require(index, Type.INT, "an index");
        }

        public Type type() {
            return ((Type.ListOf) list.type()).element();
        }
    }

    record Get(Expr map, Expr key) implements Expr {
        public Get {
            Type.valueOf(map, "what a key is looked up in");
            Type.require(key, Type.STRING, "a key");
        }

        public Type type() {
            return ((Type.MapOf) map.type()).value();
        }
    }

    record HasKey(Expr map, Expr key) implements Expr {
        public HasKey {
            Type.valueOf(map, "what a key is looked for in");
            Type.require(key, Type.STRING, "a key");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    record Keys(Expr map) implements Expr {
        public Keys {
            Type.valueOf(map, "what has keys");
        }

        public Type type() {
            return Type.listOf(Type.STRING);
        }
    }

    /** An empty string, list or map, or a JSON value that is null or missing. */
    record IsEmpty(Expr value) implements Expr {
        public IsEmpty {
            if (!(value.type() instanceof Type.Str || value.type() instanceof Type.ListOf
                    || value.type() instanceof Type.MapOf || value.type() instanceof Type.Json))
                throw new IllegalArgumentException("only a string, list, map or JSON value can be empty: " + value);
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    // ---- logic --------------------------------------------------------------------------------------------------

    record Not(Expr operand) implements Expr {
        public Not {
            Type.require(operand, Type.BOOL, "what -not negates");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** Short-circuits: the right is evaluated only when the left is true. */
    record And(Expr left, Expr right) implements Expr {
        public And {
            Type.require(left, Type.BOOL, "an operand of and");
            Type.require(right, Type.BOOL, "an operand of and");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** Short-circuits: the right is evaluated only when the left is false. */
    record Or(Expr left, Expr right) implements Expr {
        public Or {
            Type.require(left, Type.BOOL, "an operand of or");
            Type.require(right, Type.BOOL, "an operand of or");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    enum Comparison {EQ, NE, LT, LE, GT, GE}

    /** Two numbers, or two strings (equal or not only, case-sensitively). */
    record Compare(Expr left, Comparison op, Expr right) implements Expr {
        public Compare {
            Type.requireOneOf(left, "a compared value", Type.INT, Type.STRING);
            Type.require(right, left.type(), "what " + left + " is compared with");
            if (left.type().equals(Type.STRING) && op != Comparison.EQ && op != Comparison.NE)
                throw new IllegalArgumentException("strings compare only as equal or not: " + op);
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** {@code then} if the condition holds, else {@code otherwise}; only the chosen one is evaluated. */
    record Choose(Expr condition, Expr then, Expr otherwise) implements Expr {
        public Choose {
            Type.require(condition, Type.BOOL, "a condition");
            Type.require(otherwise, then.type(), "the other choice");
        }

        public Type type() {
            return then.type();
        }
    }

    // ---- text ---------------------------------------------------------------------------------------------------

    /**
     * Whether a regular expression matches anywhere in the text. The pattern is POSIX ERE, with character classes
     * such as {@code [[:space:]]}: what Bash's and Zsh's {@code =~} take; PowerShell's lowering translates it.
     */
    record Matches(Expr text, String pattern) implements Expr {
        public Matches {
            Type.require(text, Type.STRING, "matched text");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    record EndsWith(Expr text, Expr suffix) implements Expr {
        public EndsWith {
            Type.require(text, Type.STRING, "text");
            Type.require(suffix, Type.STRING, "a suffix");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** The first {@code count} characters, or all of a shorter text. */
    record Take(Expr text, Expr count) implements Expr {
        public Take {
            Type.require(text, Type.STRING, "text");
            Type.require(count, Type.INT, "a count");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    /** The text split at runs of whitespace, with none at either end. */
    record Words(Expr text) implements Expr {
        public Words {
            Type.require(text, Type.STRING, "text");
        }

        public Type type() {
            return Type.listOf(Type.STRING);
        }
    }

    record Join(Expr list, Expr separator) implements Expr {
        public Join {
            Type.require(list, Type.listOf(Type.STRING), "what is joined");
            Type.require(separator, Type.STRING, "a separator");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    // ---- paths and files ----------------------------------------------------------------------------------------

    /** The directory the running script is in. */
    record ScriptDir() implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    /**
     * The path of a sibling Koine script, by name: the file it was lowered to for the shell running this one
     * ({@code deploy.ps1}, {@code deploy.sh}).
     */
    record ScriptPath(String name) implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    /** The user's home directory. */
    record Home() implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    record Env(String name) implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    /** The path joined with each part in turn, with the platform's separator. */
    record PathJoin(Expr base, List<Expr> parts) implements Expr {
        public PathJoin {
            parts = List.copyOf(parts);
            Type.require(base, Type.STRING, "a path");
            for (Expr p : parts) Type.require(p, Type.STRING, "a path part");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    /** The directory a path is in. */
    record Parent(Expr path) implements Expr {
        public Parent {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    /** A path's last part. */
    record FileName(Expr path) implements Expr {
        public FileName {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    /** The absolute path, with {@code .} and {@code ..} resolved; it need not exist. */
    record FullPath(Expr path) implements Expr {
        public FullPath {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.STRING;
        }
    }

    /** Whether a file or directory is there. */
    record Exists(Expr path) implements Expr {
        public Exists {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    record ReadLines(Expr path) implements Expr {
        public ReadLines {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.listOf(Type.STRING);
        }
    }

    record ReadJson(Expr path) implements Expr {
        public ReadJson {
            Type.require(path, Type.STRING, "a path");
        }

        public Type type() {
            return Type.JSON;
        }
    }

    /**
     * The value at a path of field names in a JSON document, read as the given type; a missing field reads as empty.
     * The type says how to read it, as {@code jq -r} or {@code jq -c} do: a string, a number, a boolean, a JSON value,
     * or an array of either.
     */
    record Field(Expr json, List<String> path, Type type) implements Expr {
        public Field {
            path = List.copyOf(path);
            Type.require(json, Type.JSON, "what a field is read from");
            if (path.isEmpty()) throw new IllegalArgumentException("a field with no name");
            if (!List.of(Type.STRING, Type.INT, Type.BOOL, Type.JSON, Type.listOf(Type.JSON), Type.listOf(Type.STRING)).contains(type))
                throw new IllegalArgumentException("a JSON field cannot be read as " + type);
        }
    }

    // ---- processes ----------------------------------------------------------------------------------------------

    /** Runs a command; true when it exits 0. Its output goes where the script's goes unless it is quiet. */
    record Succeeds(Command command) implements Expr {
        public Type type() {
            return Type.BOOL;
        }
    }

    /** Runs a command and takes its output, without its trailing line breaks. Its exit status is ignored. */
    record Capture(Command command) implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    /** Whether a program by this name or path can be run. */
    record CommandExists(Expr name) implements Expr {
        public CommandExists {
            Type.require(name, Type.STRING, "a program");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** Starts a command in the background, in {@code workDir}, with its output and errors to two files. */
    record Spawn(Command command, Expr workDir, Expr stdout, Expr stderr) implements Expr {
        public Spawn {
            Type.require(workDir, Type.STRING, "a working directory");
            Type.require(stdout, Type.STRING, "the output file");
            Type.require(stderr, Type.STRING, "the error file");
        }

        public Type type() {
            return Type.PROCESS;
        }
    }

    record IsRunning(Expr process) implements Expr {
        public IsRunning {
            Type.require(process, Type.PROCESS, "a process");
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** Whether a person can answer a prompt: input from a terminal, and the shell not told it is non-interactive. */
    record IsInteractive() implements Expr {
        public Type type() {
            return Type.BOOL;
        }
    }

    // ---- functions ----------------------------------------------------------------------------------------------

    record Call(FunctionSig function, List<Expr> arguments) implements Expr {
        public Call {
            arguments = List.copyOf(arguments);
            if (arguments.size() != function.parameters().size())
                throw new IllegalArgumentException(function.name() + " takes " + function.parameters().size() + " arguments, not " + arguments.size());
            for (int i = 0; i < arguments.size(); i++)
                Type.require(arguments.get(i), function.parameters().get(i).type(), function.name() + "'s " + function.parameters().get(i).name());
        }

        /** What the function returns; a call to one that returns nothing can only be a {@link Statement.Invoke}. */
        public Type type() {
            return function.returns().orElseThrow(() -> new IllegalStateException(function.name() + " returns nothing"));
        }
    }
}
