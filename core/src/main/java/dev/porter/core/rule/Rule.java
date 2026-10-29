package dev.porter.core.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A rewrite rule ({@code contracts/rule.schema.json}). Absent optional fields are {@code null}. */
public record Rule(int schemaVersion, String id, String description, String from, String to, Kind kind, Match match,
        String replace, List<String> imports, List<String> preconditions, Boolean reversible, Integer priority,
        double confidence, Provenance provenance, List<Test> tests, Edit edit) {

    /** Expression/type rules (no declaration edit). */
    public Rule(int schemaVersion, String id, String description, String from, String to, Kind kind, Match match,
            String replace, List<String> imports, List<String> preconditions, Boolean reversible, Integer priority,
            double confidence, Provenance provenance, List<Test> tests) {
        this(schemaVersion, id, description, from, to, kind, match, replace, imports, preconditions, reversible, priority,
                confidence, provenance, tests, null);
    }

    public enum Kind {
        @JsonProperty("mechanical") MECHANICAL,
        @JsonProperty("adapter") ADAPTER,
        @JsonProperty("idiom") IDIOM
    }

    /** Exactly one selector (call, newCall, field, type, override, pattern, declaration) is set. */
    public record Match(String call, @JsonProperty("new") String newCall, String field, String type, String override,
            String pattern, Bind bind, String declaration) {
        public Match(String call, String newCall, String field, String type, String override, String pattern, Bind bind) {
            this(call, newCall, field, type, override, pattern, bind, null);
        }
    }

    public record Bind(String recv, List<String> args, String type, String result) {
    }

    /** @param source graph | manual | agent | mined | upfront */
    public record Provenance(String source, String ref, List<String> chain) {
    }

    public record Test(String name, String context, String before, String after) {
    }

    /**
     * Edits of a declaration selected by {@code match.declaration} (RFC 006). Texts are inserted verbatim (they may use
     * {@code $target(a.b.C)} for target names that must not be relocated).
     *
     * @param addSupertypes  type: appended to {@code implements} (class) / {@code extends} (interface)
     * @param addMembers     type: inserted right after the opening brace of the body
     * @param appendMembers  type: inserted right after the last member (after the opening brace when there is none)
     * @param initializer    field: the new initializer expression
     * @param body           method/constructor: the new text between the braces of the body
     * @param remove         member: delete the declaration (annotations included)
     */
    public record Edit(List<String> addSupertypes, String addMembers, String appendMembers, String initializer, String body,
            Boolean remove) {
    }

    public boolean reversibleOrDefault() {
        return Boolean.TRUE.equals(reversible);
    }

    public int priorityOrDefault() {
        return priority == null ? 0 : priority;
    }
}
