package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.model.ModLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Manifest;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtType;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * {@code mod}: META-INF/mods.toml ([[mods]] entries and [[dependencies.modid]]) plus the {@code @Mod} class.
 * A {@code ${file.jarVersion}} placeholder is resolved from META-INF/MANIFEST.MF Implementation-Version when
 * present (that is what FML does at runtime).
 */
public final class ModExtractor {
    public static final String ID = "mod/mods-toml";

    /** Raw [[mods]] entry. */
    public record ModEntry(String modId, String displayName, String version, String description, String authors,
            String license, Map<String, List<JsonNode>> deps, Path file) {
    }

    /** Reads mods.toml; empty list if absent. */
    public static List<ModEntry> readToml(ModLayout layout) {
        List<ModEntry> out = new ArrayList<>();
        for (Path f : layout.resourceFiles(p -> p.equals("META-INF/mods.toml"))) {
            JsonNode root;
            try {
                root = new TomlMapper().readTree(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String license = text(root.get("license"));
            JsonNode deps = root.get("dependencies");
            for (JsonNode m : iter(root.get("mods"))) {
                String id = text(m.get("modId"));
                Map<String, List<JsonNode>> d = new LinkedHashMap<>();
                if (deps != null && id != null && deps.get(id) != null) {
                    List<JsonNode> list = new ArrayList<>();
                    iter(deps.get(id)).forEach(list::add);
                    d.put(id, list);
                }
                out.add(new ModEntry(id, text(m.get("displayName")), text(m.get("version")),
                        text(m.get("description")), text(m.get("authors")), license, d, f));
            }
        }
        return out;
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        List<JsonNode> l = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(l::add);
        } else if (n != null && n.isObject()) {
            l.add(n);
        }
        return l;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }

    public static void extract(ExtractContext ctx, List<ModEntry> entries) {
        Map<String, CtType<?>> modClasses = new LinkedHashMap<>();
        for (CtType<?> t : ctx.model.types()) {
            for (CtAnnotation<?> a : t.getAnnotations()) {
                String q = a.getAnnotationType().getQualifiedName();
                if (q.equals("net.minecraftforge.fml.common.Mod")) {
                    Object v = annotationValue(ctx, a, t);
                    if (v instanceof String s) {
                        modClasses.put(s, t);
                    }
                }
            }
        }
        String manifestVersion = manifestVersion(ctx.layout);
        if (entries.isEmpty()) {
            for (Map.Entry<String, CtType<?>> e : modClasses.entrySet()) {
                ctx.sink.unknown("mod", null, "@Mod(\"" + e.getKey() + "\") without META-INF/mods.toml (name/version unknown)",
                        e.getValue().getQualifiedName(), ctx.prov(e.getValue(), ID));
            }
            return;
        }
        for (ModEntry m : entries) {
            Provenance prov = ctx.provResource(m.file(), ID);
            if (m.modId() == null || !m.modId().matches("^[a-z][a-z0-9_]{1,63}$")) {
                ctx.sink.unknown("mod", null, "invalid or missing modId in mods.toml", m.modId(), prov);
                continue;
            }
            String version = m.version();
            if (version != null && version.contains("${file.jarVersion}")) {
                version = manifestVersion != null ? version.replace("${file.jarVersion}", manifestVersion) : null;
            }
            if (m.displayName() == null || version == null || version.isBlank()) {
                ctx.sink.unknown("mod", null, "mods.toml entry " + m.modId() + " lacks displayName or a resolvable version",
                        m.version(), prov);
                continue;
            }
            List<String> authors = null;
            if (m.authors() != null && !m.authors().isBlank()) {
                authors = new ArrayList<>();
                for (String a : m.authors().split(",")) {
                    if (!a.isBlank()) {
                        authors.add(a.trim());
                    }
                }
            }
            List<IrNode.Mod.Dependency> deps = new ArrayList<>();
            for (JsonNode d : m.deps().getOrDefault(m.modId(), List.of())) {
                String depId = text(d.get("modId"));
                if (depId == null || !depId.matches("^[a-z][a-z0-9_]{1,63}$")) {
                    continue;
                }
                Boolean req = d.get("mandatory") == null ? null : d.get("mandatory").asBoolean();
                String ord = text(d.get("ordering"));
                deps.add(new IrNode.Mod.Dependency(depId, text(d.get("versionRange")), req,
                        ord == null ? null : ord.toLowerCase(Locale.ROOT)));
            }
            CtType<?> main = modClasses.get(m.modId());
            String desc = m.description() == null || m.description().isBlank() ? null : m.description().strip();
            ctx.sink.add(new IrNode.Mod(m.modId(), m.displayName(), version, desc, authors, m.license(),
                    main == null ? null : main.getQualifiedName(), deps.isEmpty() ? null : deps, prov, null, null));
        }
    }

    /** value() of an annotation, evaluated without loading the annotation class (noclasspath). */
    public static Object annotationValue(ExtractContext ctx, CtAnnotation<?> a, CtType<?> t) {
        spoon.reflect.code.CtExpression<?> e = a.getValues().get("value");
        if (e == null) {
            return null;
        }
        return ctx.interp.evalIn(e, t, null);
    }

    private static String manifestVersion(ModLayout layout) {
        for (Path f : layout.resourceFiles(p -> p.equals("META-INF/MANIFEST.MF"))) {
            try (var in = Files.newInputStream(f)) {
                String v = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return null;
    }
}
