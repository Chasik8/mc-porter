package dev.porter.testkit.runner;

import dev.porter.core.contract.Documents;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-platform-version table from IR ids (used by scenarios) to the registry names of that version, plus item/block
 * aliases carrying meta, NBT or a stack size (testkit-owned format, see testkit/README.md "id maps").
 *
 * <pre>
 * version: forge-1.7.10
 * blocks: { "examplemod:machine": { id: "HostMod:blockMachine", meta: 0 }, "minecraft:furnace": "minecraft:furnace" }
 * items:  { "examplemod:fuel_can_full": { id: "HostMod:itemFuelCan", nbt: { value: 10000 } } }
 * fluids: { "minecraft:water": "water" }
 * </pre>
 *
 * Unmapped ids pass through unchanged (vanilla ids are the same in both versions for most blocks/items).
 */
public final class IdMap {
    private final Map<String, ObjectNode> blocks = new LinkedHashMap<>();
    private final Map<String, ObjectNode> items = new LinkedHashMap<>();
    private final Map<String, ObjectNode> fluids = new LinkedHashMap<>();

    public static IdMap empty() {
        return new IdMap();
    }

    public static IdMap read(Path file) {
        IdMap m = new IdMap();
        if (file == null) {
            return m;
        }
        try {
            Documents.Parsed p = Documents.read(file);
            if (!p.issues().isEmpty()) {
                throw new IllegalArgumentException(file + ": " + p.issues());
            }
            for (JsonNode doc : p.documents()) {
                fill(m.blocks, doc.path("blocks"));
                fill(m.items, doc.path("items"));
                fill(m.fluids, doc.path("fluids"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return m;
    }

    private static void fill(Map<String, ObjectNode> into, JsonNode section) {
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            JsonNode v = e.getValue();
            ObjectNode o = Json.json().createObjectNode();
            if (v.isString()) {
                String s = v.asString();
                int at = s.lastIndexOf('@');
                if (at > 0) {
                    o.put("id", s.substring(0, at));
                    o.put("meta", Integer.parseInt(s.substring(at + 1)));
                } else {
                    o.put("id", s);
                }
            } else if (v.isObject()) {
                o.setAll((ObjectNode) v);
            } else {
                throw new IllegalArgumentException("id map entry " + e.getKey() + " must be a string or object");
            }
            into.put(e.getKey(), o);
        }
    }

    public ObjectNode block(String irId) {
        return resolve(blocks, irId);
    }

    public ObjectNode item(String irId) {
        return resolve(items, irId);
    }

    public ObjectNode fluid(String irId) {
        return resolve(fluids, irId);
    }

    private static ObjectNode resolve(Map<String, ObjectNode> m, String irId) {
        ObjectNode o = m.get(irId);
        if (o != null) {
            return o.deepCopy();
        }
        ObjectNode d = Json.json().createObjectNode();
        d.put("id", irId);
        return d;
    }

    /** Registry name (+ optional meta) of this version back to the IR id, for normalising sampled values. */
    public String reverseItem(String registryId, int meta) {
        return reverse(items, blocks, registryId, meta);
    }

    public String reverseBlock(String registryId, int meta) {
        return reverse(blocks, items, registryId, meta);
    }

    public String reverseFluid(String registryId) {
        for (Map.Entry<String, ObjectNode> e : fluids.entrySet()) {
            if (e.getValue().path("id").asString().equals(registryId)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String reverse(Map<String, ObjectNode> first, Map<String, ObjectNode> second, String id, int meta) {
        String loose = null;
        for (Map<String, ObjectNode> m : java.util.List.of(first, second)) {
            for (Map.Entry<String, ObjectNode> e : m.entrySet()) {
                ObjectNode o = e.getValue();
                if (!o.path("id").asString().equals(id) || o.has("nbt") || o.has("count")) {
                    continue;
                }
                if (o.has("meta")) {
                    if (o.path("meta").asInt() == meta) {
                        return e.getKey();
                    }
                } else if (loose == null) {
                    loose = e.getKey();
                }
            }
        }
        return loose;
    }
}
