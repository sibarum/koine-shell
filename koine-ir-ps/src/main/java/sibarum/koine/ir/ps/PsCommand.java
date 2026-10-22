package sibarum.koine.ir.ps;

import java.util.ArrayList;
import java.util.List;

/**
 * A command invocation: {@code Write-Host $x -ForegroundColor Red}, {@code & $python --version *> $null}.
 *
 * @param name a bare word for a command by name; any other expression after {@code &} or {@code .}
 */
public record PsCommand(Invocation invocation, PsExpression name, List<PsCommandElement> arguments,
                        List<PsRedirection> redirections) implements PsPipelineElement {

    public PsCommand {
        arguments = List.copyOf(arguments);
        redirections = List.copyOf(redirections);
    }

    public enum Invocation {
        NONE(""), CALL("&"), DOT(".");

        public final String token;

        Invocation(String token) {
            this.token = token;
        }
    }

    public PsCommand redirect(PsRedirection... more) {
        List<PsRedirection> all = new ArrayList<>(redirections);
        all.addAll(List.of(more));
        return new PsCommand(invocation, name, arguments, all);
    }
}
