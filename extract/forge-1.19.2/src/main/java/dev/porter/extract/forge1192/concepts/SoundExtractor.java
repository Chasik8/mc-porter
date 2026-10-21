package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.core.json.Json;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * {@code sound}: {@code assets/<ns>/sounds.json} events; registered SoundEvents without a sounds.json entry are
 * unknown (no file mapping).
 */
public final class SoundExtractor {
    public static final String ID = "sound/sounds-json";
    private static final Pattern FILE = Pattern.compile("^assets/([a-z0-9_.-]+)/sounds\\.json$");
    private static final Set<String> CATEGORIES = Set.of("master", "music", "record", "weather", "block", "hostile",
            "neutral", "player", "ambient", "voice");

    private SoundExtractor() {
    }

    public static void extract(ExtractContext ctx) {
        Set<String> defined = new HashSet<>();
        for (Path f : ctx.layout.resourceFiles(p -> FILE.matcher(p).matches())) {
            Matcher m = FILE.matcher(ctx.layout.resourcePath(f));
            if (!m.matches()) {
                continue;
            }
            String ns = m.group(1);
            Provenance prov = ctx.provResource(f, ID);
            JsonNode root;
            try {
                root = Json.json().readTree(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                ctx.sink.unknown("sound", null, "unreadable sounds.json", ctx.layout.resourcePath(f), prov);
                continue;
            }
            for (var e : root.properties()) {
                String id = ns + ":" + e.getKey();
                JsonNode ev = e.getValue();
                List<IrNode.Sound.SoundFile> files = new ArrayList<>();
                boolean ok = Vals.isResourceId(id);
                for (JsonNode s : ev.path("sounds")) {
                    if (s.isString()) {
                        String name = qualify(ns, s.asString());
                        ok &= name != null;
                        files.add(new IrNode.Sound.SoundFile(name, null, null, null, null));
                    } else if (s.isObject() && !"event".equals(s.path("type").asString("file"))) {
                        String name = qualify(ns, s.path("name").asString(null));
                        ok &= name != null;
                        files.add(new IrNode.Sound.SoundFile(name,
                                s.has("volume") ? s.get("volume").asDouble() : null,
                                s.has("pitch") ? s.get("pitch").asDouble() : null,
                                s.has("weight") ? s.get("weight").asInt() : null,
                                s.has("stream") ? s.get("stream").asBoolean() : null));
                    } else {
                        ok = false; // event references are not modelled
                    }
                }
                if (!ok || files.isEmpty()) {
                    ctx.sink.unknown("sound", id, "sound event with unsupported or no sound entries", e.getKey(), prov);
                    continue;
                }
                String cat = ev.path("category").asString(null);
                String sub = ev.path("subtitle").asString(null);
                Boolean replace = ev.has("replace") ? ev.get("replace").asBoolean() : null;
                ctx.sink.add(new IrNode.Sound(id, cat != null && CATEGORIES.contains(cat) ? cat : null, sub, replace, files,
                        prov, null, null));
                defined.add(id);
            }
        }
        for (Facts.Registration r : ctx.facts.in("sound_event")) {
            if (r.id() != null && !defined.contains(r.id())) {
                ctx.sink.unknown("sound", r.id(), "sound event registered in code but absent from sounds.json",
                        Interpreter.snippet(r.site()), ctx.prov(r.site(), "sound/registry"));
            }
        }
    }

    static String qualify(String ns, String name) {
        if (name == null) {
            return null;
        }
        String q = name.contains(":") ? name : ns + ":" + name;
        return Vals.isResourceId(q) ? q : null;
    }
}
