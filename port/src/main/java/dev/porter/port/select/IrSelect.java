package dev.porter.port.select;

import dev.porter.core.json.Json;
import dev.porter.port.Relocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Subsystem selection from a whole-mod extract ({@code <modid>.<kind>.ir.json} files): the IR that one port unit
 * generates. Deterministic: same IR + same spec = same output.
 *
 * <ol>
 *   <li>seeds: nodes whose id matches {@code seed} (ids the target already has, {@code hostIds}, are left out unless
 *       listed in {@code hostKeep});</li>
 *   <li>references, to a fixpoint: block -> blockEntity/item, item -> placesBlock, blockEntity -> menu, menu ->
 *       blockEntity;</li>
 *   <li>recipes whose result is selected or whose {@code customType} is listed; unknown nodes from the listed
 *       provenance files or mentioning a selected id;</li>
 *   <li>transforms: logic/screen/handler classes relocated ({@code relocate}); classes shared with nodes outside the
 *       selection or listed in {@code generic} are dropped (the generated base is used);
 *       {@code porter:legacyName} prefixes renamed ({@code legacyNames}); an optional screen-class convention
 *       ({@code screenConvention}: the class derived from the menu class by string replacements, when its source
 *       exists);</li>
 *   <li>an overlay ({@link Overlay}): node merges, additions and removals for what the extract does not know.</li>
 * </ol>
 */
public final class IrSelect {
    /** IR extension of the extractor: source arguments of the logic class's constructor call. */
    static final String NEW_ARGS = "porter-extract:newArgs";

    static final List<String> KINDS = List.of("block", "blockEntity", "item", "fluid", "effect", "menu", "packet", "sound", "multiblock");
    static final List<String> DEFAULT_ID_KEYS = List.of("item", "block", "fluid", "placesBlock", "blockEntity", "menu", "remainder", "effect", "container");

    /** The selection spec (YAML). */
    public record Spec(String unit, String modId, Pattern seed, List<String> customTypes, List<String> unknownFiles,
            Set<String> hostIds, Set<String> hostKeep, List<Relocation> relocate, Set<String> generic,
            String legacyFrom, String legacyTo, String legacySource, List<String[]> screenReplace, String[] screenSuffix, Path screenSources,
            List<String> idKeys, JsonNode overlay) {
    }

    public static Spec readSpec(Path file, java.util.function.Function<String, Path> resolve) {
        return readSpec(file, resolve, false);
    }

    /** {@code allowMissingOverlay}: an overlay file that does not exist yet is no error (it is being made). */
    public static Spec readSpec(Path file, java.util.function.Function<String, Path> resolve, boolean allowMissingOverlay) {
        JsonNode s = Json.yaml().readTree(read(file));
        Set<String> host = new HashSet<>();
        for (JsonNode h : s.path("hostIds")) {
            JsonNode doc = Json.yaml().readTree(read(resolve.apply(h.asString())));
            for (String sect : List.of("items", "blocks", "fluids")) {
                doc.path(sect).properties().forEach(e -> host.add(e.getKey()));
            }
        }
        List<Relocation> rel = new ArrayList<>();
        s.path("relocate").forEach(r -> rel.add(Relocation.parse(r.asString())));
        List<String[]> screen = new ArrayList<>();
        JsonNode sc = s.path("screenConvention");
        sc.path("replace").forEach(r -> screen.add(new String[] {r.get(0).asString(), r.get(1).asString()}));
        JsonNode lg = s.path("legacyNames");
        JsonNode ov = s.path("overlay");
        if (ov.isString()) {
            Path f = resolve.apply(ov.asString());
            ov = Files.exists(f) || !allowMissingOverlay ? Json.yaml().readTree(read(f)) : tools.jackson.databind.node.MissingNode.getInstance();
        }
        return new Spec(s.path("unit").asString(), s.path("modId").asString(""), Pattern.compile(s.path("seed").asString()),
                strings(s.path("customTypes")), strings(s.path("unknownFiles")), host, new HashSet<>(strings(s.path("hostKeep"))), rel,
                new HashSet<>(strings(s.path("generic"))), lg.path("from").asString(null), lg.path("to").asString(null),
                lg.path("source").asString(null), screen,
                sc.has("suffix") ? new String[] {sc.get("suffix").get(0).asString(), sc.get("suffix").get(1).asString()} : null,
                sc.has("sources") ? resolve.apply(sc.path("sources").asString()) : null,
                s.has("idKeys") ? strings(s.path("idKeys")) : DEFAULT_ID_KEYS, ov.isMissingNode() ? null : ov);
    }

    static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asString()));
        return out;
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The extract: kind -> document ({@code <prefix>.<kind>.ir.json}). */
    static Map<String, ObjectNode> load(Path irDir) {
        Map<String, ObjectNode> docs = new TreeMap<>();
        try (Stream<Path> s = Files.list(irDir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".ir.json")).sorted().toList()) {
                String n = f.getFileName().toString();
                String kind = n.substring(0, n.length() - ".ir.json".length());
                kind = kind.substring(kind.lastIndexOf('.') + 1);
                docs.put(kind, (ObjectNode) Json.json().readTree(read(f)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return docs;
    }

    static List<ObjectNode> nodes(Map<String, ObjectNode> docs, String kind) {
        List<ObjectNode> out = new ArrayList<>();
        ObjectNode d = docs.get(kind);
        if (d != null) {
            d.path("nodes").forEach(n -> out.add((ObjectNode) n));
        }
        return out;
    }

    /** Result: kind -> selected documents (unit file names), plus the report. */
    public record Result(Map<String, ObjectNode> docs, Map<String, Object> report) {
    }

    public static Result select(Path irDir, Spec spec) {
        Map<String, ObjectNode> docs = load(irDir);
        Map<String, Map<String, ObjectNode>> sel = new LinkedHashMap<>();
        for (String k : KINDS) {
            Map<String, ObjectNode> m = new LinkedHashMap<>();
            for (ObjectNode n : nodes(docs, k)) {
                String id = n.path("id").asString(null);
                if (id != null && spec.seed().matcher(id).lookingAt() && !hosted(spec, id)) {
                    m.put(id, n);
                }
            }
            sel.put(k, m);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            java.util.TreeSet<String> want = new java.util.TreeSet<>();
            sel.get("block").values().forEach(b -> {
                want.add("blockEntity\u0000" + b.path("blockEntity").asString(""));
                want.add("item\u0000" + b.path("item").asString(""));
            });
            sel.get("item").values().forEach(i -> want.add("block\u0000" + i.path("placesBlock").asString("")));
            sel.get("blockEntity").values().forEach(be -> want.add("menu\u0000" + be.path("menu").asString("")));
            sel.get("menu").values().forEach(m -> want.add("blockEntity\u0000" + m.path("blockEntity").asString("")));
            for (String w : want) {
                String k = w.substring(0, w.indexOf('\u0000'));
                String id = w.substring(w.indexOf('\u0000') + 1);
                if (id.isEmpty() || sel.get(k).containsKey(id) || hosted(spec, id)) {
                    continue;
                }
                for (ObjectNode n : nodes(docs, k)) {
                    if (id.equals(n.path("id").asString(null))) {
                        sel.get(k).put(id, n);
                        changed = true;
                        break;
                    }
                }
            }
        }
        Set<String> own = new LinkedHashSet<>();
        sel.values().forEach(m -> own.addAll(m.keySet()));
        List<ObjectNode> recipes = new ArrayList<>();
        for (ObjectNode r : nodes(docs, "recipe")) {
            if (!"recipe".equals(r.path("kind").asString())) {
                continue;
            }
            List<String> res = new ArrayList<>();
            if (r.has("result")) {
                res.add(r.path("result").path("item").asString(null));
            }
            r.path("results").forEach(x -> res.add(x.path("item").asString(null)));
            if (res.stream().anyMatch(own::contains) || spec.customTypes().contains(r.path("customType").asString(null))) {
                recipes.add(r);
            }
        }
        String ns = spec.modId().isEmpty() ? null : spec.modId() + ":";
        List<String> ownSorted = new ArrayList<>(new java.util.TreeSet<>(own));
        Pattern ownRe = ownSorted.isEmpty() ? null : Pattern.compile("\"" + Pattern.quote(ns == null ? "" : ns) + "("
                + String.join("|", ownSorted.stream().map(x -> Pattern.quote(x.substring(x.indexOf(':') + 1))).toList()) + ")\"");
        List<ObjectNode> unknowns = new ArrayList<>();
        List<ObjectNode> unknownPool = new ArrayList<>(nodes(docs, "unknown"));
        nodes(docs, "recipe").stream().filter(n -> "unknown".equals(n.path("kind").asString())).forEach(unknownPool::add);
        for (ObjectNode u : unknownPool) {
            String f = u.path("provenance").path("file").asString("");
            boolean byFile = spec.unknownFiles().stream().anyMatch(f::endsWith);
            if (byFile || (ownRe != null && ownRe.matcher(Json.json().writeValueAsString(u)).find())) {
                unknowns.add(u);
            }
        }
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("unit", spec.unit());
        rep.put("seed", spec.seed().pattern());
        Map<String, String> relocated = new TreeMap<>();
        Map<String, String> genericDropped = new TreeMap<>();
        Map<String, String> screen = new TreeMap<>();
        Set<String> shared = new HashSet<>();
        for (String k : KINDS) {
            for (ObjectNode n : nodes(docs, k)) {
                String id = n.path("id").asString(null);
                for (String fld : List.of("logicClass", "screenClass", "handlerClass")) {
                    if (n.has(fld) && !own.contains(id) && !spec.hostIds().contains(id)) {
                        shared.add(n.get(fld).asString());
                    }
                }
            }
        }
        int legacy = 0;
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        ObjectNode mod = docs.get("mod");
        for (String k : KINDS) {
            ArrayNode lst = Json.json().createArrayNode();
            for (ObjectNode n0 : sel.get(k).values()) {
                ObjectNode n = n0.deepCopy();
                if (k.equals("menu") && !n.has("screenClass") && spec.screenSuffix() != null && n.has("logicClass")
                        && n.get("logicClass").asString().endsWith(spec.screenSuffix()[0])) {
                    String c = n.get("logicClass").asString();
                    for (String[] r : spec.screenReplace()) {
                        c = c.replace(r[0], r[1]);
                    }
                    c = c.substring(0, c.length() - spec.screenSuffix()[0].length()) + spec.screenSuffix()[1];
                    if (spec.screenSources() == null || Files.isRegularFile(spec.screenSources().resolve(c.replace('.', '/') + ".java"))) {
                        n.put("screenClass", c);
                        screen.put(n.path("id").asString(), c);
                    }
                }
                for (String fld : List.of("logicClass", "screenClass", "handlerClass")) {
                    if (!n.has(fld)) {
                        continue;
                    }
                    String c = n.get(fld).asString();
                    if (spec.generic().contains(c) || shared.contains(c)) {
                        genericDropped.put(n.path("kind").asString() + " " + n.path("id").asString(), c);
                        n.remove(fld);
                    } else {
                        String r = Relocation.apply(spec.relocate(), c);
                        if (!r.equals(c)) {
                            n.put(fld, r);
                            relocated.put(c, r);
                        }
                    }
                }
                JsonNode ext = n.path("extensions");
                if (ext.isObject() && ext.has(NEW_ARGS)) { // constructor arguments of the logic class: relocated with it
                    ObjectNode e = (ObjectNode) ext;
                    if (!n.has("logicClass")) {
                        e.remove(NEW_ARGS);
                    } else {
                        ArrayNode a = Json.json().createArrayNode();
                        e.get(NEW_ARGS).forEach(x -> a.add(Relocation.applyInSource(spec.relocate(), x.asString())));
                        e.set(NEW_ARGS, a);
                    }
                }
                if (spec.legacyFrom() != null && ext.isObject() && ext.path("porter:legacyName").isString()
                        && ext.path("porter:legacyName").asString().startsWith(spec.legacyFrom())) {
                    ObjectNode e = (ObjectNode) ext;
                    e.put("porter:legacyName", spec.legacyTo() + e.path("porter:legacyName").asString().substring(spec.legacyFrom().length()));
                    if (spec.legacySource() != null) {
                        e.put("porter:legacyNameSource", spec.legacySource());
                    }
                    legacy++;
                }
                lst.add(n);
            }
            if (!lst.isEmpty()) {
                out.put(k, doc(mod, lst));
            }
        }
        ArrayNode rs = Json.json().createArrayNode();
        recipes.forEach(r -> rs.add(r.deepCopy()));
        out.put("recipe", doc(mod, rs));
        ArrayNode us = Json.json().createArrayNode();
        unknowns.forEach(u -> us.add(u.deepCopy()));
        out.put("unknown", doc(mod, us));
        if (mod != null) {
            out.put("mod", doc(mod, (ArrayNode) mod.path("nodes").deepCopy()));
        }
        Map<String, Object> ov = spec.overlay() == null ? Map.of() : Overlay.apply(out, spec.overlay(), mod);
        rep.put("relocated", relocated);
        rep.put("genericDropped", genericDropped);
        rep.put("screenByConvention", screen);
        rep.put("legacyRenamed", legacy);
        Map<String, Integer> counts = new LinkedHashMap<>();
        out.forEach((k, d) -> counts.put(k, d.path("nodes").size()));
        rep.put("counts", counts);
        rep.put("overlay", ov);
        rep.put("ownIds", ownSorted);
        return new Result(out, rep);
    }

    private static boolean hosted(Spec spec, String id) {
        return spec.hostIds().contains(id) && !spec.hostKeep().contains(id);
    }

    static ObjectNode doc(ObjectNode mod, ArrayNode nodes) {
        ObjectNode d = Json.json().createObjectNode();
        d.set("schemaVersion", mod == null ? Json.json().getNodeFactory().numberNode(1) : mod.path("schemaVersion").deepCopy());
        if (mod != null && mod.has("source")) {
            d.set("source", mod.get("source").deepCopy());
        }
        d.set("nodes", nodes);
        return d;
    }

    /** Writes {@code <unit>.<kind>.ir.json} (other *.ir.json in the directory are removed) and select-report.json. */
    public static void write(Result r, String unit, Path out) {
        try {
            Files.createDirectories(out);
            try (Stream<Path> s = Files.list(out)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".ir.json")).toList()) {
                    Files.delete(f);
                }
            }
            for (Map.Entry<String, ObjectNode> e : r.docs().entrySet()) {
                Files.writeString(out.resolve(unit + "." + e.getKey() + ".ir.json"), Json.json().writeValueAsString(e.getValue()) + "\n");
            }
            Files.writeString(out.resolve("select-report.json"), Json.json().writeValueAsString(r.report()) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> unused() {
        return new HashMap<>();
    }
}
