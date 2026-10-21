package dev.porter.core.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/** The CLI surface (command path -> description with the owning module). */
public final class KnownCommands {
    /** command path -> one-line description (owner). Insertion order = help order. */
    public static final Map<String, String> PATHS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("validate", "Validate files against the porter contracts (core)");
        m.put("graph build", "Build the symbol graph for a list of versions (graph)");
        m.put("graph query", "Query a symbol through the version chain (graph)");
        m.put("extract", "Extract IR from mod sources (extract)");
        m.put("generate", "Generate code/assets for a version from IR (generate)");
        m.put("rewrite", "Rewrite mod logic between versions (rewrite)");
        m.put("loop", "Compile/classify/fix loop (loop)");
        m.put("assets", "Convert assets between versions (assets)");
        m.put("test run", "Run scenarios on one version and write traces (testkit)");
        m.put("test roundtrip", "Round-trip test of a mod in its own version (testkit)");
        m.put("test diff", "Differential scenario test between two versions (testkit)");
        m.put("port", "Run a whole port from a port config, resumable by stage (port)");
        m.put("closure", "Dependency closure of seed classes: copy list, hub frontier, known-identical check (port)");
        m.put("coverage", "Share of a mod's distinct API symbols that existing rules resolve (port)");
        m.put("stubs", "Hub stubs: frontier classes reduced to the members the closure uses (port)");
        m.put("passes", "Source passes: srgmoj, java8, tector, edits, imports (port)");
        m.put("castfix", "Error-driven fixes of decompiler cast/generic artefacts (port)");
        m.put("symbols", "Aggregate javac errors by distinct symbol (port)");
        m.put("obfcheck", "Reobf collision check of a built jar (port)");
        m.put("drafts", "Export the loop's draft rules as issue-ready markdown (good first issues) (port)");
        m.put("report", "Aggregate run metrics (core; planned)");
        PATHS = java.util.Collections.unmodifiableMap(m);
    }

    private KnownCommands() {
    }
}
