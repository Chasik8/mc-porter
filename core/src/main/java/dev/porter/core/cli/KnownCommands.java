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
        m.put("test obtainable", "Survival check: items of a mod that nothing makes, build A vs build B (testkit)");
        m.put("port", "Run a whole port from a port config, resumable by stage (port)");
        m.put("shimgen", "Java 8 shim skeletons from the match table: mirrors, fragments, Ext helpers, typed TODOs (port)");
        m.put("precheck", "Fast check before a full port run: rules, affected classes, javac (port)");
        m.put("api-inventory", "Every 1.19.2 platform symbol a mod references: kind, descriptor, uses, declaring class (port)");
        m.put("catalog", "Generate the table of ports in ports/README.md from ports/catalog.yaml (port)");
        m.put("run", "One command for a player: check the original files, set everything up, run the port (port)");
        m.put("closure", "Dependency closure of seed classes: copy list, hub frontier, known-identical check (port)");
        m.put("coverage", "Share of a mod's distinct API symbols that existing rules resolve (port)");
        m.put("match", "Match table: what every API symbol becomes on the target version, scored and data-driven (port)");
        m.put("stubs", "Hub stubs: frontier classes reduced to the members the closure uses (port)");
        m.put("passes", "Source passes: srgmoj, java8, tector, edits, imports (port)");
        m.put("castfix", "Error-driven fixes of decompiler cast/generic artefacts (port)");
        m.put("symbols", "Aggregate javac errors by distinct symbol (port)");
        m.put("obfcheck", "Reobf collision check of a built jar (port)");
        m.put("inventory", "What a port profile ships and its token 8-gram overlap with the original (port)");
        m.put("flattening", "Registry-constant table: 1.19.2 Items/Blocks/... to 1.7.10 expressions, from the user's own jars (port)");
        m.put("emit-rules", "Rules for the unresolved rows of a match table: map, expand, registry, mirror, ext (port)");
        m.put("drafts", "Export the loop's draft rules as issue-ready markdown (good first issues) (port)");
        m.put("patch make", "Write unified diffs between two trees, also across paths (port)");
        m.put("patch apply", "Apply unified diffs exactly, base tree -> target tree (port)");
        m.put("decompile", "Decompile a jar with a pinned CFR and extract its resources, sha256-checked (port)");
        m.put("api-index", "Per-version-pair API indexes for the upfront layer: lineage, target API, hierarchy (port)");
        m.put("todo-batch", "Split the shimgen TODO list into one batch file per fill worker, owners never split (port)");
        m.put("upfront", "Run the whole upfront translation layer for a mod (index, inventory, flattening, match, emit-rules, shimgen, todo-batch) and report the numbers (port)");
        m.put("bodies check", "Compile bodies DB entries against the 1.7.10 jar and adapters in one javac, mark verified (port)");
        m.put("select", "Select a subsystem from extracted IR (seeds, references, overlay) (port)");
        m.put("report", "Aggregate run metrics (core; planned)");
        PATHS = java.util.Collections.unmodifiableMap(m);
    }

    private KnownCommands() {
    }
}
