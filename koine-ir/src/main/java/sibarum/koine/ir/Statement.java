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

    /**
     * Runs the body, then the cleanup however the body ends: normally, or through {@link Fail}, a failing command or
     * {@link Exit}. A {@link Return} must not leave the body; the language does not allow one there.
     */
    record Finally(List<Statement> body, List<Statement> cleanup) implements Statement {
        public Finally {
            body = List.copyOf(body);
            cleanup = List.copyOf(cleanup);
        }
    }

    // ---- functions ----------------------------------------------------------------------------------------------

    record Function(FunctionSig signature, List<Statement> body) implements Statement {
        public Function {
            body = List.copyOf(body);
        }
    }

    /**
     * A function whose body is written in the shell's own language, for one OS: a library member's {@code native}
     * body. The lowering writes the parameters' prologue and copies the body as it is; language.md's table of native
     * bodies says what the body can rely on.
     */
    record NativeFunction(FunctionSig signature, OS os, String body) implements Statement {
        public NativeFunction {
            for (Var p : signature.parameters())
                if (p.type() instanceof Type.MapOf || p.type().equals(Type.DATA))
                    throw new IllegalArgumentException(signature.name() + " cannot take " + p.type() + ": " + p.name());
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

    /** Ends the script with this exit status, after the cleanup of every {@link Finally} it is in. */
    record Exit(Expr status) implements Statement {
        public Exit {
            Type.require(status, Type.INT, "an exit status");
        }
    }

    /**
     * Fails the script: prints the message in red, with the step it is in, then exits with 1 after the cleanup. It
     * is {@code koine_fail}, which native bodies call too.
     */
    record Fail(Expr message) implements Statement {
        public Fail {
            Type.require(message, Type.STRING, "a message");
        }
    }

    // ---- output -------------------------------------------------------------------------------------------------

    /** A line to the console. Colour is the {@code Console} module's. */
    record Print(Expr message) implements Statement {
        public Print {
            Type.require(message, Type.STRING, "a message");
        }
    }

    // ---- commands -----------------------------------------------------------------------------------------------

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

    /** A comment, carried into every shell's script. */
    record Comment(String text) implements Statement {
    }
}
