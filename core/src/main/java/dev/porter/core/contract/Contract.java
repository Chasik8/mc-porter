package dev.porter.core.contract;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The frozen contracts in {@code contracts/}. Changing any of them requires an RFC (contracts/rfc/). */
public enum Contract {
    IR("ir", List.of(".ir.json", ".ir.yaml", ".ir.yml")),
    RULE("rule", List.of(".rule.yaml", ".rule.yml", ".rule.json")),
    SYMBOL_GRAPH("symbol-graph", List.of(".graph.json", ".symbol-graph.json")),
    SCENARIO("scenario", List.of(".scenario.yaml", ".scenario.yml", ".scenario.json")),
    TRACE("trace", List.of(".trace.json")),
    CANDIDATE("candidate", List.of(".candidate.json", ".candidates.jsonl", ".candidate.jsonl")),
    /** Per-port configuration of {@code porter port} (RFC 004, additive). */
    PORT_CONFIG("port-config", List.of(".port.yaml", ".port.yml", ".port.json")),
    /** Community catalog of ports ({@code ports/catalog.yaml}, RFC 004, additive). */
    PORT_CATALOG("port-catalog", List.of(".catalog.yaml", ".catalog.yml", ".catalog.json")),
    /** api-inventory of the upfront stage: platform symbols a mod references (RFC 011, additive). */
    UPFRONT_INVENTORY("upfront-inventory", List.of(".api-inventory.json")),
    /** The upfront match table: one plan per 1.19.2 symbol (RFC 011, additive). */
    UPFRONT_MATCH_TABLE("upfront-match-table", List.of(".match-table.json"));

    /** schemaVersion that every document of every contract currently carries (IR: see {@link #IR_SCHEMA_VERSION}). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Current IR schemaVersion (RFC 003). IR documents that use none of the RFC 003 fields may still carry 1;
     * {@link dev.porter.core.ir.IrDocument} writes the lowest version that fits its nodes.
     */
    public static final int IR_SCHEMA_VERSION = 2;

    /** Current schemaVersion of this contract. */
    public int schemaVersion() {
        return this == IR ? IR_SCHEMA_VERSION : SCHEMA_VERSION;
    }

    private final String id;
    private final List<String> suffixes;

    Contract(String id, List<String> suffixes) {
        this.id = id;
        this.suffixes = suffixes;
    }

    /** Short name used on the command line and in file names: ir, rule, symbol-graph, ... */
    public String id() {
        return id;
    }

    /** Recognised file name suffixes, e.g. {@code .ir.json}. */
    public List<String> suffixes() {
        return suffixes;
    }

    /** Classpath resource of the bundled schema. */
    public String schemaResource() {
        return "/porter/contracts/" + id + ".schema.json";
    }

    public static Optional<Contract> byId(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.id.equals(wanted)).findFirst();
    }

    /** Contract implied by the file name suffix, if any. */
    public static Optional<Contract> bySuffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.suffixes.stream().anyMatch(lower::endsWith)).findFirst();
    }
}
