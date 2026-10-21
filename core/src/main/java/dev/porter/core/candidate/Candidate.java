package dev.porter.core.candidate;

/**
 * A logged fix that may become a rule ({@code contracts/candidate.schema.json}).
 *
 * @param errorClass missing_class | missing_method | signature_mismatch | incompatible_types | no_override | java_syntax | other
 * @param author     agent:&lt;name&gt; | human:&lt;name&gt; | rule:&lt;rule-id&gt;
 */
public record Candidate(int schemaVersion, String id, String from, String to, String error, String errorClass, String file,
        Integer line, String before, String after, String symbol, String normalized, String author, int count,
        String ruleId) {
}
