package dev.porter.generate;

import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrIo;
import dev.porter.core.ir.IrNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/** Programmatic entry point of {@code porter generate} (used by the command and by tests). */
public final class Generate {
    private Generate() {
    }

    /** Result of a run: the context (output + decisions) and the manifest that was (or would be) written. */
    public record Result(GenContext context, Map<String, Object> manifest) {
    }

    public static List<TargetGenerator> generators() {
        List<TargetGenerator> list = new ArrayList<>();
        ServiceLoader.load(TargetGenerator.class, Generate.class.getClassLoader()).forEach(list::add);
        list.sort((a, b) -> a.version().compareTo(b.version()));
        return list;
    }

    public static Optional<TargetGenerator> generator(String version) {
        String v = version.startsWith("forge-") ? version.substring(6) : version;
        return generators().stream().filter(g -> g.version().equals(v)).findFirst();
    }

    /** Resolves defaults that depend on the IR (mod id, package, name, version, channel). */
    public static GenOptions resolve(GenOptions o, IrIndex ir) {
        Optional<IrNode.Mod> mod = ir.mod();
        String modId = o.modId();
        if (modId == null) {
            modId = mod.map(IrNode.Mod::id).orElseGet(() -> ir.contentNamespaces().isEmpty() ? "portergen"
                    : ir.contentNamespaces().first());
        }
        String pkg = o.basePackage();
        if (pkg == null) {
            String main = mod.map(IrNode.Mod::mainClass).orElse(null);
            pkg = main != null && main.contains(".") ? Names.packageOf(main) + ".gen" : "porter.gen." + Names.packageSegment(modId);
        }
        String name = o.modName() != null ? o.modName() : mod.map(IrNode.Mod::name).orElse(modId);
        String ver = o.modVersion() != null ? o.modVersion() : mod.map(IrNode.Mod::version).orElse("0.0.0");
        String channel = o.channel() != null ? o.channel() : modId.length() > 20 ? modId.substring(0, 20) : modId;
        return new GenOptions(o.version(), pkg, modId, name, ver, o.embed(), o.guiIds(), o.potionIds(), channel,
                o.assetsDir(), o.assetNamespace(), o.legacyNames(), o.stubs(), o.idMap(), o.nei(), o.sourceConstructors(), o.guiDelegate());
    }

    /** Runs a generator in memory. */
    public static Result run(TargetGenerator gen, IrIndex ir, GenOptions options) {
        List<String> merged = List.of();
        if (options.assetsDir() != null) {
            Path rendered = options.assetsDir().resolve("meta/blocks.textures.ir.json");
            if (Files.isRegularFile(rendered)) {
                IrDocument doc = IrIo.read(rendered);
                merged = ir.mergeBlockTextures(doc);
            }
        }
        GenOptions o = resolve(options, ir);
        GenContext ctx = new GenContext(ir, o);
        for (String d : ir.duplicates()) {
            ctx.warn("duplicate-node", d, "node appears in several IR files; the first one (sorted file order) is used");
        }
        for (String id : merged) {
            ctx.info("assets-textures-merged", id, "textures/icons taken from porter assets meta/blocks.textures.ir.json");
        }
        for (IrNode.Unknown u : ir.unknown()) {
            ctx.skip("ir-unknown", u.id() == null ? u.provenance().file() : u.id(),
                    "unknown IR node (" + (u.conceptHint() == null ? "?" : u.conceptHint()) + "): " + u.reason());
        }
        if (o.assetsDir() != null) {
            copyAssets(ctx);
        }
        gen.generate(ctx);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generator", gen.platform());
        manifest.put("modId", o.modId());
        manifest.put("modName", o.modName());
        manifest.put("modVersion", o.modVersion());
        manifest.put("package", o.basePackage());
        manifest.put("embed", o.embed());
        manifest.put("sourceVersions", new ArrayList<>(ir.sourceVersions()));
        manifest.put("irCounts", ir.counts());
        manifest.putAll(ctx.manifest);
        manifest.put("decisionSummary", ctx.decisionSummary());
        manifest.put("decisions", ctx.decisions());
        return new Result(ctx, manifest);
    }

    /** Copies {@code <assets>/assets/**} into the resources, renaming the mod's namespace if requested. */
    private static void copyAssets(GenContext ctx) {
        Path root = ctx.options.assetsDir().resolve("assets");
        if (!Files.isDirectory(root)) {
            ctx.warn("assets-missing", ctx.options.assetsDir().toString(), "no assets/ directory in --assets; nothing copied");
            return;
        }
        String rename = ctx.options.assetNamespace();
        int copied = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                int s = rel.indexOf('/');
                String ns = s < 0 ? rel : rel.substring(0, s);
                if (rename != null && ctx.ir.contentNamespaces().contains(ns)) {
                    rel = rename + rel.substring(ns.length());
                }
                ctx.out.put(GenOutput.RESOURCES_ROOT + "assets/" + rel, Files.readAllBytes(p));
                copied++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.info("assets-copied", ctx.options.assetsDir().toString(), copied + " asset files copied"
                + (rename != null ? " (namespace " + String.join(",", ctx.ir.contentNamespaces()) + " -> " + rename + ")" : ""));
    }
}
