package sibarum.koine.ir.ps;

/** One stage of a pipeline: a command, or (first stage only) an expression. */
public sealed interface PsPipelineElement permits PsCommand, PsExpression {
}
