package dev.porter.assets;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assets one port unit needs, picked from the whole mod's resources (one namespace) by its IR:
 * <ul>
 *   <li>textures: the texture package of every block ({@code porter:texturePackage}), menu textures, block/item
 *       textures and fluid still/flowing textures named by the IR, plus {@code include} globs; the face sprites that
 *       picked {@code texture_package.json} files name are followed;</li>
 *   <li>lang: entries whose key is a {@code translationKey}/{@code title} of the IR or a string literal of the unit's
 *       logic sources (mod code looks lang keys up literally).</li>
 * </ul>
 * Globs are relative to {@code assets/<ns>/}; {@code *} also matches {@code /}.
 */
public final class AssetSelect {
    private AssetSelect() {
    }

    /** Summary: files copied and lang entries kept. */
    public record Result(int textures, int langEntries) {
    }

    public static Result select(Path assetsDir, String ns, List<JsonNode> irDocs, List<String> include, List<Path> literalSources, Path out) {
        Set<String> tex = new TreeSet<>(include);
        Set<String> keys = new TreeSet<>();
        for (JsonNode d : irDocs) {
            for (JsonNode n : d.path("nodes")) {
                if (n.hasNonNull("translationKey")) {
                    keys.add(n.get("translationKey").asString());
                }
                if (n.hasNonNull("title") && n.get("title").isString()) {
                    keys.add(n.get("title").asString());
                }
                String tp = n.path("extensions").path("porter:texturePackage").asString(null);
                if (tp != null) {
                    tex.add("textures/" + after(tp) + "/*");
                }
                if ("menu".equals(n.path("kind").asString()) && n.path("texture").has("path")) {
                    tex.add(after(n.path("texture").path("path").asString()));
                }
                for (Map.Entry<String, JsonNode> t : n.path("textures").properties()) {
                    if (t.getValue().isString() && t.getValue().asString().contains(":")) {
                        tex.add("textures/" + after(t.getValue().asString()) + ".png*");
                    }
                }
                for (String k : List.of("stillTexture", "flowingTexture")) {
                    if (n.hasNonNull(k)) {
                        tex.add("textures/" + after(n.get(k).asString()) + ".png*");
                    }
                }
            }
        }
        Path base = assetsDir.resolve(ns);
        List<String> all = files(base);
        List<Pattern> globs = tex.stream().map(AssetSelect::glob).toList();
        Set<String> picked = new TreeSet<>();
        for (String f : all) {
            if (globs.stream().anyMatch(g -> g.matcher(f).matches())) {
                picked.add(f);
            }
        }
        Set<String> refs = new TreeSet<>();
        for (String f : picked) {
            if (f.endsWith("texture_package.json")) {
                for (Map.Entry<String, JsonNode> e : Json.json().readTree(read(base.resolve(f))).properties()) {
                    String v = e.getValue().isString() ? e.getValue().asString() : "";
                    if (v.startsWith(ns + ":")) {
                        refs.add("textures/" + v.substring(ns.length() + 1) + ".png");
                        refs.add("textures/" + v.substring(ns.length() + 1) + ".png.mcmeta");
                    }
                }
            }
        }
        for (String r : refs) {
            if (Files.isRegularFile(base.resolve(r))) {
                picked.add(r);
            }
        }
        Set<String> lits = new TreeSet<>();
        Pattern lit = Pattern.compile("\"([a-z0-9_.]+)\"");
        for (Path p : literalSources) {
            Matcher m = lit.matcher(read(p));
            while (m.find()) {
                lits.add(m.group(1));
            }
        }
        try {
            deleteTree(out);
            for (String f : picked) {
                Path d = out.resolve("assets").resolve(ns).resolve(f);
                Files.createDirectories(d.getParent());
                Files.copy(base.resolve(f), d);
            }
            int nlang = 0;
            Path lang = base.resolve("lang");
            if (Files.isDirectory(lang)) {
                List<Path> lfs;
                try (Stream<Path> s = Files.list(lang)) {
                    lfs = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
                }
                for (Path lf : lfs) {
                    Map<String, JsonNode> sub = new TreeMap<>();
                    for (Map.Entry<String, JsonNode> e : Json.json().readTree(read(lf)).properties()) {
                        if (keys.contains(e.getKey()) || lits.contains(e.getKey())) {
                            sub.put(e.getKey(), e.getValue());
                        }
                    }
                    if (!sub.isEmpty()) {
                        ObjectNode o = Json.json().createObjectNode();
                        sub.forEach(o::set);
                        Path d = out.resolve("assets").resolve(ns).resolve("lang").resolve(lf.getFileName().toString());
                        Files.createDirectories(d.getParent());
                        Files.writeString(d, Json.json().writeValueAsString(o) + "\n");
                        nlang += sub.size();
                    }
                }
            }
            return new Result(picked.size(), nlang);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String after(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }

    /** fnmatch-style glob: {@code *} any characters (including {@code /}), {@code ?} one, {@code [..]} a class. */
    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '[' -> {
                    int j = g.indexOf(']', i + 1);
                    if (j < 0) {
                        sb.append("\\[");
                    } else {
                        String cls = g.substring(i + 1, j);
                        sb.append('[').append(cls.startsWith("!") ? "^" + cls.substring(1) : cls).append(']');
                        i = j;
                    }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    private static List<String> files(Path base) {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(base)) {
            List<String> out = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(p -> out.add(base.relativize(p).toString().replace('\\', '/')));
            out.sort(null);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
