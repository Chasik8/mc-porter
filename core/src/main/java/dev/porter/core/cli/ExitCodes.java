package dev.porter.core.cli;

/** Exit codes shared by all porter subcommands. */
public final class ExitCodes {
    /** Success. */
    public static final int OK = 0;
    /** The command ran but its result is negative (invalid document, failed test, compile errors left...). */
    public static final int FAILED = 1;
    /** Bad command line (picocli default). */
    public static final int USAGE = 2;
    /** Subcommand exists in the CLI surface but is not implemented yet. */
    public static final int NOT_IMPLEMENTED = 3;
    /** Unexpected internal error (exception). */
    public static final int ERROR = 4;

    private ExitCodes() {
    }
}
