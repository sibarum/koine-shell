package sibarum.koine.ir;

import java.util.ArrayList;
import java.util.List;

import sibarum.koine.lang.Parser;
import sibarum.koine.lang.SourceFile;
import sibarum.koine.lang.StandardLibrary;
import sibarum.koine.lang.TypeRef;

/**
 * The standard library's members as the compiler will give them to the IR: a native member as a
 * {@link Statement.NativeFunction} for one OS, named {@code Module_member}. The tests build their scripts from these, so
 * a lowering is tested on the bodies the library really has.
 */
public final class Std {

    private Std() {
    }

    /** {@code Path_join}'s signature: its parameters and result, with a repeated parameter as a list. */
    public static FunctionSig sig(String module, String member) {
        SourceFile.Module m = module(module);
        sibarum.koine.lang.Statement.Def def = def(m, member);
        List<Var> params = new ArrayList<>();
        for (sibarum.koine.lang.Statement.Param p : def.parameters()) {
            Type t = type(m, p.type().orElseThrow());
            params.add(new Var(p.name(), p.repeated() ? Type.listOf(t) : t));
        }
        return new FunctionSig(module + "_" + member, params, def.result().map(r -> type(m, r)));
    }

    /** The member's native body for the OS, with its signature. */
    public static Statement.NativeFunction native_(String module, String member, OS os) {
        SourceFile.Module m = module(module);
        for (sibarum.koine.lang.Statement s : def(m, member).body())
            if (s instanceof sibarum.koine.lang.Statement.Native n && n.systems().contains(os.name()))
                return new Statement.NativeFunction(sig(module, member), os, n.body());
        throw new IllegalArgumentException(module + "." + member + " has no native " + os + " body");
    }

    /** A record a module declares, as an IR type. */
    public static Type.Record record(String module, String name) {
        SourceFile.Module m = module(module);
        return (Type.Record) type(m, new TypeRef.Named(name, null));
    }

    private static SourceFile.Module module(String name) {
        String text = StandardLibrary.INSTANCE.module(name).orElseThrow(() -> new IllegalArgumentException("no module " + name));
        return (SourceFile.Module) Parser.parse(name + ".koine", text);
    }

    private static sibarum.koine.lang.Statement.Def def(SourceFile.Module m, String member) {
        for (sibarum.koine.lang.Statement s : m.members())
            if (s instanceof sibarum.koine.lang.Statement.Def d && d.name().equals(member)) return d;
        throw new IllegalArgumentException(m.name() + " has no member " + member);
    }

    private static Type type(SourceFile.Module m, TypeRef ref) {
        return switch (ref) {
            case TypeRef.ListOf l -> Type.listOf(type(m, l.element()));
            case TypeRef.Named n -> switch (n.name()) {
                case "String" -> Type.STRING;
                case "Number" -> Type.INT;
                case "Bool" -> Type.BOOL;
                case "Data" -> Type.DATA;
                default -> {
                    for (sibarum.koine.lang.Statement s : m.members())
                        if (s instanceof sibarum.koine.lang.Statement.Record r && r.name().equals(n.name())) {
                            List<Type.Record.Field> fields = new ArrayList<>();
                            for (sibarum.koine.lang.Statement.Field f : r.fields())
                                fields.add(new Type.Record.Field(f.name(), type(m, f.type()), f.optional()));
                            yield new Type.Record(r.name(), fields);
                        }
                    throw new IllegalArgumentException(m.name() + " has no type " + n.name());
                }
            };
        };
    }
}
