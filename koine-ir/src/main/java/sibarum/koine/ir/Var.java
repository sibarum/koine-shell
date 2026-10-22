package sibarum.koine.ir;

/** A variable, with the type every value assigned to it must have. */
public record Var(String name, Type type) {
}
