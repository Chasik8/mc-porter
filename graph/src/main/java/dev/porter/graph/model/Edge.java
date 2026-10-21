package dev.porter.graph.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contract {@code edge} between adjacent versions. {@code to} is null exactly for {@link Status#REMOVED}. */
public record Edge(SymbolRef from, SymbolRef to, Status status, double confidence, List<String> evidence, String note) {
    /** Contract statuses, best to worst. */
    public enum Status {
        SAME("same"), RENAMED("renamed"), MOVED("moved"), SIGNATURE_CHANGED("signature_changed"), SPLIT("split"),
        MERGED("merged"), REMOVED("removed");

        public final String json;

        Status(String json) {
            this.json = json;
        }

        public static Status of(String s) {
            for (Status x : values()) {
                if (x.json.equals(s)) {
                    return x;
                }
            }
            throw new IllegalArgumentException("unknown status " + s);
        }

        /** Rank for "worst along the path"; split and merged are equally bad. */
        public int rank() {
            return this == MERGED ? SPLIT.ordinal() : ordinal();
        }

        public static Status worst(Status a, Status b) {
            return a.rank() >= b.rank() ? a : b;
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toJson());
        if (to != null) {
            m.put("to", to.toJson());
        }
        m.put("status", status.json);
        m.put("confidence", confidence);
        if (evidence != null && !evidence.isEmpty()) {
            m.put("evidence", evidence);
        }
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    /** The same edge seen in the opposite direction (older -> newer); removed edges cannot be inverted. */
    public Edge inverted() {
        if (to == null) {
            throw new IllegalStateException("cannot invert a removed edge");
        }
        Status s = status == Status.SPLIT ? Status.MERGED : status == Status.MERGED ? Status.SPLIT : status;
        return new Edge(to, from, s, confidence, evidence, note);
    }
}
