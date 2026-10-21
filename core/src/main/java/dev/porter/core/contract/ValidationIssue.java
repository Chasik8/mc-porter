package dev.porter.core.contract;

/**
 * One validation problem.
 *
 * @param document index of the document inside the file (YAML document or JSONL line, 0-based)
 * @param location JSON path inside the document, JSON Pointer, e.g. {@code /nodes/0/id} ({@code ""} = document root)
 * @param message  human-readable message (English)
 * @param source   {@link #SCHEMA}, {@link #SEMANTIC} or {@link #PARSE}
 */
public record ValidationIssue(int document, String location, String message, String source) {
    public static final String SCHEMA = "schema";
    public static final String SEMANTIC = "semantic";
    public static final String PARSE = "parse";

    @Override
    public String toString() {
        return (location == null || location.isEmpty() ? "<root>" : location) + ": " + message + (SCHEMA.equals(source) ? "" : " [" + source + "]");
    }
}
