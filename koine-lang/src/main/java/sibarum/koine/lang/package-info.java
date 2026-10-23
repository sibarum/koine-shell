/**
 * The Koine language's front end: {@link sibarum.koine.lang.Parser} reads a script or a module, as described in
 * {@code docs/language.md}, into a {@link sibarum.koine.lang.SourceFile}. Scripts and modules share one grammar.
 * {@link sibarum.koine.lang.Checker} then checks one against the modules it names, which a
 * {@link sibarum.koine.lang.Library} finds.
 */
package sibarum.koine.lang;
