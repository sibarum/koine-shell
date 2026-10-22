package sibarum.koine.ir;

import java.util.List;
import java.util.Optional;

/**
 * A portable statement. There are no exceptions to catch and no pipelines of objects: what fails is a command's exit
 * status, which a script tests with {@link Expr.Succeeds} or lets abort it with {@link Run}.
 */
public sealed interface Statement {

    // ---- variables ----------------------------------------------------------------------------------------------

    record Set(Var var, Expr value) implements Statement {
        public Set {
            Type.require(value, var.type(), "the value of " + var.name());
        }
    }

    /** Adds an item to the end of a list variable. */
    record Append(Var list, Expr item) implements Statement {
        public Append {
            if (!(list.type() instanceof Type.ListOf l)) throw new IllegalArgumentException(list.name() + " is not a list");
            Type.require(item, l.element(), "an item of " + list.name());
        }
    }

    /** Sets a key of a map variable. */
    record Put(Var map, Expr key, Expr value) implements Statement {
        public Put {
            if (!(map.type() instanceof Type.MapOf m)) throw new IllegalArgumentException(map.name() + " is not a map");
            Type.require(key, Type.STRING, "a key");
            Type.require(value, m.value(), "a value of " + map.name());
        }
    }

    // ---- control ------------------------------------------------------------------------------------------------

    record If(Expr condition, List<Statement> then, List<Statement> otherwise) implements Statement {
        public If {
            then = List.copyOf(then);
            otherwise = List.copyOf(otherwise);
            Type.require(condition, Type.BOOL, "a condition");
        }
    }

    record ForEach(Var item, Expr collection, List<Statement> body) implements Statement {
        public ForEach {
            body = List.copyOf(body);
            Type elementType = Type.elementOf(collection, "what is looped over");
            if (!elementType.equals(item.type()))
                throw new IllegalArgumentException(item.name() + " is " + item.type() + ", the items are " + elementType);
        }
    }

    record While(Expr condition, List<Statement> body) implements Statement {
        public While {
            body = List.copyOf(body);
            Type.require(condition, Type.BOOL, "a condition");
        }
    }

    record Break() implements Statement {
    }

    record Continue() implements Statement {
    }

    // ---- functions ----------------------------------------------------------------------------------------------

    record Function(FunctionSig signature, List<Statement> body) implements Statement {
        public Function {
            body = List.copyOf(body);
        }
    }

    record Return(Optional<Expr> value) implements Statement {
    }

    /** Calls a function for what it does, dropping any result. */
    record Invoke(FunctionSig function, List<Expr> arguments) implements Statement {
        public Invoke {
            arguments = List.copyOf(arguments);
            if (arguments.size() != function.parameters().size())
                throw new IllegalArgumentException(function.name() + " takes " + function.parameters().size() + " arguments, not " + arguments.size());
            for (int i = 0; i < arguments.size(); i++)
                Type.require(arguments.get(i), function.parameters().get(i).type(), function.name() + "'s " + function.parameters().get(i).name());
        }
    }

    /** Ends the script with this exit status. */
    record Exit(Expr status) implements Statement {
        public Exit {
            Type.require(status, Type.INT, "an exit status");
        }
    }

    // ---- output -------------------------------------------------------------------------------------------------

    enum Color {DEFAULT, RED, GREEN, YELLOW, CYAN}

    /** A line to the console, in a color where the console has them. */
    record Print(Expr message, Color color) implements Statement {
        public Print {
            Type.require(message, Type.STRING, "a message");
        }
    }

    /** Waits for a person to press Enter, after showing the message. */
    record Pause(Expr message) implements Statement {
        public Pause {
            Type.require(message, Type.STRING, "a message");
        }
    }

    // ---- commands and processes ---------------------------------------------------------------------------------

    enum OnFailure {
        /** A non-zero exit status ends the script with that status. */
        ABORT,
        /** The exit status is ignored. */
        IGNORE
    }

    record Run(Command command, OnFailure onFailure) implements Statement {
    }

    /**
     * Runs a command and runs the body for each line of its output as it comes, then sets {@code status} to its exit
     * status. Variables the body sets keep their values afterwards (in Bash, that rules out a plain pipeline into
     * {@code while read}).
     */
    record EachLine(Command command, Var line, List<Statement> body, Var status) implements Statement {
        public EachLine {
            body = List.copyOf(body);
            if (!line.type().equals(Type.STRING)) throw new IllegalArgumentException(line.name() + " must be a string");
            if (!status.type().equals(Type.INT)) throw new IllegalArgumentException(status.name() + " must be an int");
        }
    }

    record Kill(Expr process) implements Statement {
        public Kill {
            Type.require(process, Type.PROCESS, "a process");
        }
    }

    record Sleep(Expr seconds) implements Statement {
        public Sleep {
            Type.require(seconds, Type.INT, "seconds");
        }
    }

    /** Creates a directory and any parents it needs; one that exists is left as it is. */
    record MakeDirs(Expr path) implements Statement {
        public MakeDirs {
            Type.require(path, Type.STRING, "a path");
        }
    }

    /** A comment, carried into every shell's script. */
    record Comment(String text) implements Statement {
    }
}
