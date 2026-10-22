package sibarum.koine.ir;

/** The ported preflight, for the tests in other packages. */
public final class PreflightAccess {

    private PreflightAccess() {
    }

    public static Script script() {
        return Preflight.script();
    }
}
