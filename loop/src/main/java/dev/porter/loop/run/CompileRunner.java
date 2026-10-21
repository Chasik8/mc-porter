package dev.porter.loop.run;

/** Compiles the target project once and returns javac's output. */
public interface CompileRunner {
    /**
     * @param exitCode 0 = compiled
     * @param output   combined stdout/stderr (javac diagnostics in plain or raw format)
     * @param command  what was run (for the report)
     */
    record Result(int exitCode, String output, String command) {
    }

    Result compile() throws Exception;
}
