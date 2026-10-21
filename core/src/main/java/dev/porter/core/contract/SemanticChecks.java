package dev.porter.core.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Constraints that JSON Schema cannot express. They are part of the contract (see contracts/README.md,
 * "Semantic checks") and run after a document passed its schema.
 */
public final class SemanticChecks {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

    private SemanticChecks() {
    }

    public static List<ValidationIssue> check(Contract contract, JsonNode doc, int docIndex) {
        Sink sink = new Sink(docIndex);
        switch (contract) {
            case IR -> checkIr(doc, sink);
            case RULE -> checkRule(doc, sink);
            default -> {
                // no semantic checks for the other contracts yet
            }
        }
        return sink.issues;
    }

    private static final class Sink {
        final int doc;
        final List<ValidationIssue> issues = new ArrayList<>();

        Sink(int doc) {
            this.doc = doc;
        }

        void add(String location, String message) {
            issues.add(new ValidationIssue(doc, location, message, ValidationIssue.SEMANTIC));
        }
    }

    // ---- IR ---------------------------------------------------------------------------------

    /** Fields added by RFC 003 (IR schemaVersion 2), per kind. */
    private static final Map<String, List<String>> RFC003_FIELDS = Map.of(
            "recipe", List.of("fluidIngredients", "fluidResults", "duration", "machines", "notes"),
            "item", List.of("hidden"),
            "block", List.of("hidden"));

    private static void checkIr(JsonNode doc, Sink sink) {
        Set<String> seen = new HashSet<>();
        JsonNode nodes = doc.path("nodes");
        boolean v1 = doc.path("schemaVersion").asInt() == 1;
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode n = nodes.get(i);
            String at = "/nodes/" + i;
            String kind = n.path("kind").asString("");
            if (v1) {
                for (String f : RFC003_FIELDS.getOrDefault(kind, List.of())) {
                    if (n.has(f)) {
                        sink.add(at + "/" + f, "'" + f + "' needs schemaVersion 2 (RFC 003)");
                    }
                }
                if (kind.equals("recipe")) {
                    List<JsonNode> stacks = new ArrayList<>();
                    stacks.add(n.path("result"));
                    n.path("results").forEach(stacks::add);
                    for (JsonNode st : stacks) {
                        if (st.has("chance")) {
                            sink.add(at, "item stack 'chance' needs schemaVersion 2 (RFC 003)");
                        }
                    }
                }
            }
            if (!kind.equals("unknown") && !seen.add(kind + " " + n.path("id").asString(""))) {
                sink.add(at + "/id", "duplicate " + kind + " id '" + n.path("id").asString("") + "'");
            }
            switch (kind) {
                case "multiblock" -> checkMultiblock(n, at, sink);
                case "recipe" -> checkShapedRecipe(n, at, sink);
                case "lang" -> {
                    String id = n.path("id").asString("");
                    String path = id.substring(id.indexOf(':') + 1);
                    if (!path.equals(n.path("locale").asString(""))) {
                        sink.add(at + "/id", "lang id path '" + path + "' must equal locale '" + n.path("locale").asString("") + "'");
                    }
                }
                default -> {
                    // nothing extra
                }
            }
        }
    }

    private static void checkMultiblock(JsonNode n, String at, Sink sink) {
        int sx = n.path("size").path(0).asInt();
        int sy = n.path("size").path(1).asInt();
        int sz = n.path("size").path(2).asInt();
        JsonNode layers = n.path("layers");
        JsonNode palette = n.path("palette");
        if (layers.size() != sy) {
            sink.add(at + "/layers", "expected " + sy + " layers (size[1]), found " + layers.size());
        }
        Set<String> unknownChars = new LinkedHashSet<>();
        for (int y = 0; y < layers.size(); y++) {
            JsonNode rows = layers.get(y);
            if (rows.size() != sz) {
                sink.add(at + "/layers/" + y, "expected " + sz + " rows (size[2]), found " + rows.size());
            }
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z).asString("");
                if (row.length() != sx) {
                    sink.add(at + "/layers/" + y + "/" + z, "expected " + sx + " chars (size[0]), found " + row.length());
                }
                row.codePoints().mapToObj(Character::toString).filter(c -> !palette.has(c)).forEach(unknownChars::add);
            }
        }
        if (!unknownChars.isEmpty()) {
            sink.add(at + "/layers", "chars not in palette: " + String.join(", ", unknownChars));
        }
        JsonNode pos = n.path("controller").path("pos");
        if (pos.size() == 3) {
            int[] max = {sx, sy, sz};
            for (int i = 0; i < 3; i++) {
                int v = pos.get(i).asInt();
                if (v < 0 || v >= max[i]) {
                    sink.add(at + "/controller/pos", "controller position " + pos + " is outside size " + n.path("size"));
                    break;
                }
            }
        }
    }

    private static void checkShapedRecipe(JsonNode n, String at, Sink sink) {
        if (!n.path("recipeType").asString("").equals("shaped")) {
            return;
        }
        JsonNode pattern = n.path("pattern");
        JsonNode key = n.path("key");
        Set<String> used = new LinkedHashSet<>();
        int width = -1;
        for (int i = 0; i < pattern.size(); i++) {
            String row = pattern.get(i).asString("");
            if (width >= 0 && row.length() != width) {
                sink.add(at + "/pattern/" + i, "all pattern rows must have the same length");
            }
            width = row.length();
            row.codePoints().mapToObj(Character::toString).filter(c -> !c.equals(" ")).forEach(used::add);
        }
        for (String c : used) {
            if (!key.has(c)) {
                sink.add(at + "/key", "pattern char '" + c + "' has no key entry");
            }
        }
        for (Map.Entry<String, JsonNode> e : key.properties()) {
            if (!used.contains(e.getKey())) {
                sink.add(at + "/key/" + e.getKey(), "key '" + e.getKey() + "' is not used by the pattern");
            }
        }
    }

    // ---- rules ------------------------------------------------------------------------------

    private static void checkRule(JsonNode doc, Sink sink) {
        JsonNode match = doc.path("match");
        Set<String> bound = new HashSet<>();
        JsonNode bind = match.path("bind");
        for (Map.Entry<String, JsonNode> e : bind.properties()) {
            if (e.getValue().isArray()) {
                e.getValue().forEach(v -> bound.add(v.asString()));
            } else {
                bound.add(e.getValue().asString());
            }
        }
        Matcher pm = PLACEHOLDER.matcher(match.path("pattern").asString(""));
        while (pm.find()) {
            bound.add(pm.group());
        }
        Matcher m = PLACEHOLDER.matcher(doc.path("replace").asString(""));
        Set<String> unbound = new LinkedHashSet<>();
        while (m.find()) {
            if (!bound.contains(m.group())) {
                unbound.add(m.group());
            }
        }
        if (!unbound.isEmpty()) {
            sink.add("/replace", "placeholders not bound by match: " + String.join(", ", unbound));
        }
        if (doc.path("from").asString("").equals(doc.path("to").asString(""))) {
            sink.add("/to", "'from' and 'to' must differ");
        }
    }
}
