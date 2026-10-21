package dev.porter.extract.forge1192;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Collects IR nodes per concept, removes duplicates and keeps recognition statistics. */
public final class NodeSink {
    /** Concepts in extraction order. */
    public static final List<String> CONCEPTS = List.of("mod", "lang", "item", "block", "blockEntity", "recipe", "menu",
            "packet", "fluid", "sound", "effect", "tag", "multiblock");

    private final Map<String, List<IrNode>> byConcept = new LinkedHashMap<>();
    private final List<IrNode> unhinted = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, int[]> stats = new TreeMap<>();
    private final List<String> duplicates = new ArrayList<>();

    public NodeSink() {
        for (String c : CONCEPTS) {
            byConcept.put(c, new ArrayList<>());
            stats.put(c, new int[2]);
        }
    }

    /** Adds a recognised node; a second node with the same (kind, id) is dropped and logged. */
    public boolean add(IrNode node) {
        String key = node.kind() + "|" + node.id();
        if (!keys.add(key)) {
            duplicates.add(key + " @ " + node.provenance().file() + ":" + node.provenance().line());
            return false;
        }
        byConcept.computeIfAbsent(node.kind(), k -> new ArrayList<>()).add(node);
        stats.computeIfAbsent(node.kind(), k -> new int[2])[0]++;
        return true;
    }

    /** Adds an unknown node; {@code concept} is the concept it counts against (may be null). */
    public void unknown(String concept, String id, String reason, String snippet, Provenance prov) {
        IrNode.Unknown u = new IrNode.Unknown(id != null && id.matches("^[a-z0-9_.-]+:[a-z0-9_./-]+$") ? id : null,
                reason, snippet, concept != null && CONCEPTS.contains(concept) ? concept : null, prov, null);
        add(concept, u);
    }

    public void add(String concept, IrNode.Unknown u) {
        if (concept != null && CONCEPTS.contains(concept)) {
            byConcept.get(concept).add(u);
            stats.get(concept)[1]++;
        } else {
            unhinted.add(u);
        }
    }

    public boolean has(String kind, String id) {
        return keys.contains(kind + "|" + id);
    }

    public Map<String, List<IrNode>> byConcept() {
        return byConcept;
    }

    public List<IrNode> unhinted() {
        return unhinted;
    }

    public List<String> duplicates() {
        return duplicates;
    }

    /** concept → [recognised, unknown]. */
    public Map<String, int[]> stats() {
        return stats;
    }

    public List<IrNode> all() {
        List<IrNode> out = new ArrayList<>();
        byConcept.values().forEach(out::addAll);
        out.addAll(unhinted);
        return out;
    }
}
