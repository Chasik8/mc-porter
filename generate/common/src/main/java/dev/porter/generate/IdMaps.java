package dev.porter.generate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads {@code --id-map} files: {@code {blocks|items|fluids: {irId: "modid:name[@meta]" | {id, meta}}}} (the testkit
 * id-map format; other keys such as {@code version} are ignored, entries with {@code nbt}/{@code count} and
 * {@code alias:} ids are skipped because they describe stacks, not registry objects).
 */
public final class IdMaps {
    private IdMaps() {
    }

    public static Map<String, String> load(Path file) {
        JsonNode root;
        try {
            String text = Files.readString(file);
            ObjectMapper m = file.toString().endsWith(".json") ? new JsonMapper() : new YAMLMapper();
            root = m.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new TreeMap<>();
        for (String section : new String[] {"blocks", "items", "fluids"}) {
            JsonNode s = root.get(section);
            if (s == null || !s.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                String id = e.getKey();
                if (id.startsWith("alias:") || id.startsWith("_")) {
                    continue;
                }
                JsonNode v = e.getValue();
                String ref;
                if (v.isString()) {
                    ref = v.asString();
                } else if (v.isObject() && v.get("id") != null && v.get("nbt") == null && v.get("count") == null) {
                    ref = v.get("id").asString() + (v.get("meta") != null ? "@" + v.get("meta").asInt() : "");
                } else {
                    continue;
                }
                if (!ref.contains(":")) {
                    throw new IllegalArgumentException("--id-map " + file + ": " + id + " -> '" + ref + "' is not modid:name");
                }
                out.putIfAbsent(id, ref);
            }
        }
        return out;
    }
}
