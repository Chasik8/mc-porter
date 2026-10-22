package dev.porter.port.select;

import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * IR overlay: declarative changes on top of a selection, for facts the extract does not produce (hand-checked recipe
 * data, a node the selection rules miss). YAML/JSON:
 * <pre>
 * documents: {recipe: {schemaVersion: 2}}         # top-level fields of a unit file
 * nodes:
 *   - {file: recipe, id: "mod:x", merge: {...}}   # RFC 7386 JSON merge patch on the node (occurrence: n for duplicate ids)
 *   - {file: recipe, add: {...}}                  # a node appended to the file
 *   - {file: unknown, remove: "mod:y"}            # a node removed
 * </pre>
 * {@link #make} writes the overlay that turns one IR into another, so a profile can record hand edits of an IR as
 * data instead of a script.
 */
public final class Overlay {
    private Overlay() {
    }

    /** Applies an overlay to the unit documents (kind -> document); returns counts. */
    public static Map<String, Object> apply(Map<String, ObjectNode> docs, JsonNode overlay, ObjectNode mod) {
        int merged = 0;
        int added = 0;
        int removed = 0;
        for (Map.Entry<String, JsonNode> d : overlay.path("documents").properties()) {
            ObjectNode doc = docs.computeIfAbsent(d.getKey(), k -> IrSelect.doc(mod, Json.json().createArrayNode()));
            for (Map.Entry<String, JsonNode> f : d.getValue().properties()) {
                doc.set(f.getKey(), f.getValue().deepCopy());
            }
        }
        for (JsonNode op : overlay.path("nodes")) {
            String file = op.path("file").asString();
            ObjectNode doc = docs.computeIfAbsent(file, k -> IrSelect.doc(mod, Json.json().createArrayNode()));
            ArrayNode nodes = (ArrayNode) doc.get("nodes");
            if (op.has("add")) {
                nodes.add(op.get("add").deepCopy());
                added++;
                continue;
            }
            if (op.has("removeNode")) { // a node without an id, matched by content
                int at = -1;
                for (int i = 0; i < nodes.size() && at < 0; i++) {
                    if (nodes.get(i).equals(op.get("removeNode"))) {
                        at = i;
                    }
                }
                if (at < 0) {
                    throw new IllegalStateException("overlay: node to remove not found in " + file + " (the extract changed; re-make the overlay)");
                }
                nodes.remove(at);
                removed++;
                continue;
            }
            String id = op.has("remove") ? op.get("remove").asString() : op.path("id").asString();
            int occ = op.path("occurrence").asInt(0);
            int at = find(nodes, id, occ);
            if (at < 0) {
                throw new IllegalStateException("overlay: no node " + id + (occ > 0 ? " #" + occ : "") + " in " + file
                        + " (the extract changed; re-make the overlay)");
            }
            if (op.has("remove")) {
                nodes.remove(at);
                removed++;
            } else {
                nodes.set(at, mergePatch(nodes.get(at), op.path("merge")));
                merged++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merged", merged);
        m.put("added", added);
        m.put("removed", removed);
        return m;
    }

    private static int find(ArrayNode nodes, String id, int occ) {
        int seen = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (id.equals(nodes.get(i).path("id").asString(null)) && seen++ == occ) {
                return i;
            }
        }
        return -1;
    }

    /** RFC 7386: objects merge recursively, null removes a member, everything else replaces. */
    public static JsonNode mergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode t = target != null && target.isObject() ? (ObjectNode) target.deepCopy() : Json.json().createObjectNode();
        for (Map.Entry<String, JsonNode> e : patch.properties()) {
            if (e.getValue().isNull()) {
                t.remove(e.getKey());
            } else {
                t.set(e.getKey(), mergePatch(t.get(e.getKey()), e.getValue()));
            }
        }
        return t;
    }

    /** The merge patch that turns {@code a} into {@code b} (null when equal). */
    public static JsonNode diff(JsonNode a, JsonNode b) {
        if (a.equals(b)) {
            return null;
        }
        if (!a.isObject() || !b.isObject()) {
            return b.deepCopy();
        }
        ObjectNode p = Json.json().createObjectNode();
        for (Map.Entry<String, JsonNode> e : b.properties()) {
            JsonNode av = a.get(e.getKey());
            if (av == null) {
                p.set(e.getKey(), e.getValue().deepCopy());
            } else {
                JsonNode d = diff(av, e.getValue());
                if (d != null) {
                    p.set(e.getKey(), d);
                }
            }
        }
        for (Map.Entry<String, JsonNode> e : a.properties()) {
            if (!b.has(e.getKey())) {
                p.putNull(e.getKey());
            }
        }
        return p;
    }

    /** The overlay that turns the selection {@code from} into {@code to} (kind -> document each). */
    public static ObjectNode make(Map<String, ObjectNode> from, Map<String, ObjectNode> to) {
        ObjectNode ov = Json.json().createObjectNode();
        ObjectNode documents = Json.json().createObjectNode();
        ArrayNode ops = Json.json().createArrayNode();
        Set<String> files = new LinkedHashSet<>(from.keySet());
        files.addAll(to.keySet());
        for (String f : files) {
            ObjectNode a = from.get(f);
            ObjectNode b = to.get(f);
            if (b == null) {
                continue; // a file the target does not have: kept (empty files are harmless)
            }
            ObjectNode top = Json.json().createObjectNode();
            for (Map.Entry<String, JsonNode> e : b.properties()) {
                if (!e.getKey().equals("nodes") && (a == null || !e.getValue().equals(a.get(e.getKey())))) {
                    top.set(e.getKey(), e.getValue().deepCopy());
                }
            }
            if (!top.isEmpty()) {
                documents.set(f, top);
            }
            Map<String, JsonNode> an = keyed(a == null ? null : (ArrayNode) a.get("nodes"));
            Map<String, JsonNode> bn = keyed((ArrayNode) b.get("nodes"));
            for (Map.Entry<String, JsonNode> e : an.entrySet()) {
                if (!bn.containsKey(e.getKey())) {
                    ObjectNode op = Json.json().createObjectNode();
                    op.put("file", f);
                    if (!e.getValue().has("id")) {
                        op.set("removeNode", e.getValue().deepCopy());
                        ops.add(op);
                        continue;
                    }
                    op.put("remove", e.getValue().path("id").asString());
                    int occ = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('#') + 1));
                    if (occ > 0) {
                        op.put("occurrence", occ);
                    }
                    ops.add(op);
                }
            }
            for (Map.Entry<String, JsonNode> e : bn.entrySet()) {
                JsonNode old = an.get(e.getKey());
                ObjectNode op = Json.json().createObjectNode();
                op.put("file", f);
                if (old == null) {
                    op.set("add", e.getValue().deepCopy());
                } else {
                    JsonNode d = diff(old, e.getValue());
                    if (d == null) {
                        continue;
                    }
                    op.put("id", e.getValue().path("id").asString());
                    int occ = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('#') + 1));
                    if (occ > 0) {
                        op.put("occurrence", occ);
                    }
                    op.set("merge", d);
                }
                ops.add(op);
            }
        }
        if (!documents.isEmpty()) {
            ov.set("documents", documents);
        }
        ov.set("nodes", ops);
        return ov;
    }

    /** id#occurrence -> node, in file order (nodes without an id get a positional key). */
    private static Map<String, JsonNode> keyed(ArrayNode nodes) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (nodes == null) {
            return out;
        }
        Map<String, Integer> seen = new HashMap<>();
        List<JsonNode> list = new ArrayList<>();
        nodes.forEach(list::add);
        for (int i = 0; i < list.size(); i++) {
            // nodes without an id are keyed by their content (an edit shows as remove + add)
            String id = list.get(i).has("id") ? list.get(i).path("id").asString() : "<content>" + list.get(i).toString();
            int n = seen.merge(id, 1, Integer::sum) - 1;
            out.put(id + "#" + n, list.get(i));
        }
        return out;
    }

    /** Semantic equality of two unit selections: same files, same top fields, same nodes in the same order (member order ignored). */
    public static boolean same(Map<String, ObjectNode> a, Map<String, ObjectNode> b) {
        return a.keySet().equals(b.keySet()) && a.entrySet().stream().allMatch(e -> e.getValue().equals(b.get(e.getKey())));
    }
}
