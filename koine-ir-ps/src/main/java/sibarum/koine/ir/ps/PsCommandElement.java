package sibarum.koine.ir.ps;

import java.util.Optional;

/** What follows a command's name: a parameter token, or an argument (an expression, often a bare word). */
public sealed interface PsCommandElement permits PsCommandElement.Parameter, PsExpression {

    /**
     * {@code -Name}, or {@code -Name:value}. A parameter's separate argument ({@code -ForegroundColor Cyan}) is the
     * next element, not part of this one: PowerShell binds it, the syntax does not.
     */
    record Parameter(String name, Optional<PsExpression> colonArgument) implements PsCommandElement {
    }
}
