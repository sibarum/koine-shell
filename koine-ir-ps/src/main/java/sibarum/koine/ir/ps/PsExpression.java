package sibarum.koine.ir.ps;

import java.util.List;

/**
 * A PowerShell expression. Parentheses are nodes ({@link Paren}), never inferred: the tree is the concrete syntax,
 * and prints exactly as it is built.
 */
public sealed interface PsExpression extends PsPipelineElement, PsCommandElement {

    /** {@code $name}; a scoped or drive-qualified name keeps its prefix, as in {@code env:USERPROFILE}. */
    record Variable(String name) implements PsExpression, PsStringPart {
    }

    /** {@code @name}, splatting an array or hashtable into a command's arguments. */
    record Splat(String name) implements PsExpression {
    }

    record IntLiteral(long value) implements PsExpression {
    }

    /** {@code 'text'}: nothing expands. */
    record SingleQuoted(String value) implements PsExpression {
    }

    /** {@code "text $var $(expr)"}. */
    record Expandable(List<PsStringPart> parts) implements PsExpression {
        public Expandable {
            parts = List.copyOf(parts);
        }
    }

    /**
     * An unquoted word: a command's name ({@code Write-Host}), an argument ({@code Directory}, {@code --short},
     * {@code /c}) or a hashtable key.
     */
    record BareWord(String text) implements PsExpression {
    }

    /** {@code a, b, c}. */
    record ArrayLiteral(List<PsExpression> elements) implements PsExpression {
        public ArrayLiteral {
            elements = List.copyOf(elements);
        }
    }

    /** {@code @(statements)}. */
    record ArraySubexpression(List<PsStatement> statements) implements PsExpression {
        public ArraySubexpression {
            statements = List.copyOf(statements);
        }
    }

    /** {@code $(statements)}. */
    record SubExpression(List<PsStatement> statements) implements PsExpression, PsStringPart {
        public SubExpression {
            statements = List.copyOf(statements);
        }
    }

    /** {@code (pipeline)}; an assignment is allowed, as in {@code ($x = f).Count}. */
    record Paren(PsStatement statement) implements PsExpression {
    }

    /** {@code @{ key = value; ... }}. A value is a statement: {@code @{ App = Get-Content $f | ConvertFrom-Json }}. */
    record Hashtable(List<Entry> entries) implements PsExpression {
        public Hashtable {
            entries = List.copyOf(entries);
        }
    }

    record Entry(PsExpression key, PsStatement value) {
    }

    /** {@code { statements }}. */
    record ScriptBlock(PsBlock body) implements PsExpression {
    }

    /** {@code [type]operand}: a cast, or {@code [ordered]} and {@code [pscustomobject]} before a hashtable. */
    record Convert(PsTypeName type, PsExpression operand) implements PsExpression {
    }

    /** {@code [type]} on its own, as the target of {@code ::}. */
    record TypeLiteral(PsTypeName type) implements PsExpression {
    }

    /** {@code target.name}, or {@code target::name} when static. */
    record Member(PsExpression target, String name, boolean isStatic) implements PsExpression {
    }

    /** {@code target.name(args)}, or {@code target::name(args)} when static. */
    record InvokeMember(PsExpression target, String name, List<PsExpression> arguments, boolean isStatic)
            implements PsExpression {
        public InvokeMember {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code target[index]}. */
    record Index(PsExpression target, PsExpression index) implements PsExpression {
    }

    record Unary(UnaryOperator operator, PsExpression operand) implements PsExpression {
    }

    record Binary(PsExpression left, BinaryOperator operator, PsExpression right) implements PsExpression {
    }

    enum UnaryOperator {
        NOT("-not "), BANG("!"), NEGATE("-"), COMMA(","), JOIN("-join "), SPLIT("-split ");

        /** What prints before the operand, with the space a word operator needs. */
        public final String token;

        UnaryOperator(String token) {
            this.token = token;
        }
    }

    enum BinaryOperator {
        EQ("-eq"), NE("-ne"), GT("-gt"), GE("-ge"), LT("-lt"), LE("-le"),
        LIKE("-like"), NOTLIKE("-notlike"), MATCH("-match"), NOTMATCH("-notmatch"),
        CEQ("-ceq"), CNE("-cne"), CLIKE("-clike"), CMATCH("-cmatch"), CNOTMATCH("-cnotmatch"),
        CONTAINS("-contains"), NOTCONTAINS("-notcontains"), IN("-in"), NOTIN("-notin"),
        AND("-and"), OR("-or"), XOR("-xor"),
        JOIN("-join"), SPLIT("-split"), REPLACE("-replace"), FORMAT("-f"),
        AS("-as"), IS("-is"), ISNOT("-isnot"),
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"), RANGE("..");

        public final String token;

        BinaryOperator(String token) {
            this.token = token;
        }
    }
}
