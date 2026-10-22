package sibarum.koine.ir.ps;

/** A piece of an expandable ({@code "..."}) string: literal text, {@code $variable}, or {@code $(subexpression)}. */
public sealed interface PsStringPart permits PsStringPart.Text, PsExpression.Variable, PsExpression.SubExpression {

    /** Literal characters, unescaped: the printer adds the backticks. */
    record Text(String value) implements PsStringPart {
    }
}
