package dev.porter.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything a version generator needs: the IR, the options, the output tree and the decision log. */
public final class GenContext {
    /**
     * One logged generator decision.
     *
     * @param level   info (a choice that was made), warn (something may need a look), skip (IR content not generated)
     * @param code    stable machine-readable code, e.g. {@code meta-spill}
     * @param subject IR id (or file) the decision is about
     */
    public record Decision(String level, String code, String subject, String message) {
    }

    public final IrIndex ir;
    public final GenOptions options;
    public final GenOutput out = new GenOutput();
    private final List<Decision> decisions = new ArrayList<>();
    /** Extra manifest entries (allocated ids, layouts, ...), keyed by section. */
    public final Map<String, Object> manifest = new LinkedHashMap<>();

    public GenContext(IrIndex ir, GenOptions options) {
        this.ir = ir;
        this.options = options;
    }

    public void info(String code, String subject, String message) {
        decisions.add(new Decision("info", code, subject, message));
    }

    public void warn(String code, String subject, String message) {
        decisions.add(new Decision("warn", code, subject, message));
    }

    public void skip(String code, String subject, String message) {
        decisions.add(new Decision("skip", code, subject, message));
    }

    public List<Decision> decisions() {
        return decisions;
    }

    /** Decision counts per level and per code. */
    public Map<String, Object> decisionSummary() {
        Map<String, Integer> byLevel = new TreeMap<>();
        Map<String, Integer> byCode = new TreeMap<>();
        for (Decision d : decisions) {
            byLevel.merge(d.level(), 1, Integer::sum);
            byCode.merge(d.code(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byLevel", byLevel);
        m.put("byCode", byCode);
        return m;
    }

    /** The mod's own namespaces: ids in these namespaces are generated here, everything else is external. */
    public boolean isOwn(String id) {
        return id != null && ir.contentNamespaces().contains(Names.namespace(id));
    }
}
