package sibarum.koine.ir;

import java.util.List;

/**
 * A portable expression: what a value means, not how one shell spells it. Every expression knows its {@link Type},
 * and a record whose operands have the wrong types cannot be built. What the standard library does (paths, files,
 * processes, most of what strings and lists can do) is not here: it is a {@link Call} of a library member.
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

    /** An empty string, list or map, or a record that is missing: an optional field that was not there. */
    record IsEmpty(Expr value) implements Expr {
        public IsEmpty {
            if (!(value.type() instanceof Type.Str || value.type() instanceof Type.ListOf
                    || value.type() instanceof Type.MapOf || value.type() instanceof Type.Record))
                throw new IllegalArgumentException("only a string, list, map or record can be empty: " + value);
        }

        public Type type() {
            return Type.BOOL;
        }
    }

    /** {@code item in container}: a string in a string, case-sensitively, or an item in a list. */
    record In(Expr item, Expr container) implements Expr {
        public In {
            if (container.type().equals(Type.STRING)) Type.require(item, Type.STRING, "what is looked for in a string");
            else Type.require(item, Type.elementOf(container, "what in looks in"), "what is looked for in a list");
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

    // ---- records ------------------------------------------------------------------------------------------------

    /** A record, made from a value for each field, in the order the record declares them. */
    record MakeRecord(Type.Record record, List<Expr> values) implements Expr {
        public MakeRecord {
            values = List.copyOf(values);
            if (values.size() != record.fields().size())
                throw new IllegalArgumentException(record + " has " + record.fields().size() + " fields, not " + values.size());
            for (int i = 0; i < values.size(); i++)
                Type.require(values.get(i), record.fields().get(i).type(), record + "." + record.fields().get(i).name());
        }

        public Type type() {
            return record;
        }
    }

    record FieldOf(Expr record, String field) implements Expr {
        public FieldOf {
            if (!(record.type() instanceof Type.Record r) || r.field(field).isEmpty())
                throw new IllegalArgumentException(record.type() + " has no field " + field + ": " + record);
        }

        public Type type() {
            return ((Type.Record) record.type()).field(field).orElseThrow().type();
        }
    }

    /** Data from a read, as the record: only its fields are read, and a missing one reads as empty. */
    record ReadAs(Expr data, Type.Record record) implements Expr {
        public ReadAs {
            Type.require(data, Type.DATA, "what is read as " + record);
        }

        public Type type() {
            return record;
        }
    }

    // ---- processes ----------------------------------------------------------------------------------------------

    /** Runs a command; true when it exits 0. Its output goes where the script's goes unless it is quiet. */
    record Succeeds(Command command) implements Expr {
        public Type type() {
            return Type.BOOL;
        }
    }

    /** Runs a command and takes its output, without its trailing line breaks; {@code onFailure} says what a failure does. */
    record Capture(Command command, Fallback onFailure) implements Expr {
        public Type type() {
            return Type.STRING;
        }
    }

    /** What a {@link Capture} gives when its command fails. */
    sealed interface Fallback {

        /** Nothing: the script stops with the command's exit status. */
        record Stop() implements Fallback {
        }

        /** This value instead. */
        record Value(Expr value) implements Fallback {
            public Value {
                Type.require(value, Type.STRING, "a capture's fallback");
            }
        }

        /** The script fails with this message. */
        record Fail(Expr message) implements Fallback {
            public Fail {
                Type.require(message, Type.STRING, "a message");
            }
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
