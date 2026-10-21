package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.core.json.Json;
import dev.porter.extract.forge1192.ExtractContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * {@code tag}: data-pack tag files {@code data/<ns>/tags/<blocks|items|fluids|entity_types>/<path>.json}. Forge
 * {@code forge:<group>/<material>} tags get their conventional OreDictionary name (ingots/copper → ingotCopper).
 */
public final class TagExtractor {
    public static final String ID = "tag/json";
    private static final Pattern FILE = Pattern.compile(
            "^data/([a-z0-9_.-]+)/tags/(blocks|items|fluids|entity_types)/(.+)\\.json$");
    private static final Pattern REF = Pattern.compile("^#?[a-z0-9_.-]+:[a-z0-9_./-]+$");

    /** Forge tag group → OreDictionary prefix (Forge's own 1.13 conversion table). */
    static final Map<String, String> ORE_PREFIX = Map.ofEntries(
            Map.entry("ingots", "ingot"), Map.entry("ores", "ore"), Map.entry("dusts", "dust"),
            Map.entry("nuggets", "nugget"), Map.entry("gems", "gem"), Map.entry("storage_blocks", "block"),
            Map.entry("plates", "plate"), Map.entry("gears", "gear"), Map.entry("rods", "stick"),
            Map.entry("crops", "crop"), Map.entry("seeds", "seed"));

    private TagExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        for (Path f : ctx.layout.resourceFiles(p -> FILE.matcher(p).matches())) {
            String rel = ctx.layout.resourcePath(f);
            Matcher m = FILE.matcher(rel);
            if (!m.matches()) {
                continue;
            }
            String id = m.group(1) + ":" + m.group(3);
            String registry = switch (m.group(2)) {
                case "blocks" -> "block";
                case "items" -> "item";
                case "fluids" -> "fluid";
                default -> "entity_type";
            };
            Provenance prov = ctx.provResource(f, ID);
            JsonNode root;
            try {
                root = Json.json().readTree(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                ctx.sink.unknown("tag", id, "unreadable tag json", rel, prov);
                continue;
            }
            List<IrNode.TagValue> values = new ArrayList<>();
            boolean ok = Vals.isResourceId(id);
            for (JsonNode v : root.path("values")) {
                if (v.isString() && REF.matcher(v.asString()).matches()) {
                    values.add(IrNode.TagValue.of(v.asString()));
                } else if (v.isObject() && v.has("id") && REF.matcher(v.get("id").asString()).matches()) {
                    values.add(new IrNode.TagValue(v.get("id").asString(),
                            v.has("required") ? v.get("required").asBoolean() : null));
                } else {
                    ok = false;
                }
            }
            if (!ok) {
                ctx.sink.unknown("tag", id, "tag file with unsupported values", rel, prov);
                continue;
            }
            Boolean replace = root.has("replace") ? root.get("replace").asBoolean() : null;
            ctx.sink.add(new IrNode.Tag(id, registry, replace, values, oreDict(id), prov, null, null));
        }
    }

    /** forge:ingots/copper → [ingotCopper]; null when there is no conventional name. */
    public static List<String> oreDict(String id) {
        if (!id.startsWith("forge:")) {
            return null;
        }
        String path = id.substring("forge:".length());
        int slash = path.indexOf('/');
        if (slash < 0 || path.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String prefix = ORE_PREFIX.get(path.substring(0, slash));
        if (prefix == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(prefix);
        for (String part : path.substring(slash + 1).split("_")) {
            if (part.isEmpty()) {
                return null;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String name = sb.toString();
        return name.matches("^[a-zA-Z][a-zA-Z0-9_]*$") ? List.of(name) : null;
    }
}
