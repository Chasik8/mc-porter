package dev.porter.core.cli;

/** Global parallelism: {@code --jobs N} (system property {@code porter.jobs}), else {@code $PORTER_JOBS}, else the core count. */
public final class Jobs {
    private Jobs() {
    }

    public static int get() {
        for (String v : new String[] {System.getProperty("porter.jobs"), System.getenv("PORTER_JOBS")}) {
            if (v != null && !v.isBlank()) {
                try {
                    return Math.max(1, Integer.parseInt(v.trim()));
                } catch (NumberFormatException e) {
                    // fall through to the next source
                }
            }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
