package dev.porter.assets;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.core.ir.IrIo;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

/**
 * {@code porter assets --src <assets> --from 1.19.2 --to 1.7.10 --out out/}.
 *
 * <p>Output: {@code <out>/assets/<ns>/...} (converted resources) and {@code <out>/meta/} (summary.json,
 * warnings.json, lang-key-map.json, blocks.textures.ir.json, needs-manual-render.json, texture-packages.json,
 * sheets/*.json, obj/**, ir/*). Both directories are replaced on every run. Exit 1 if anything was lost.
 */
@Command(name = "assets", description = "Convert resource-pack assets between Minecraft versions (chain of pack_format steps).")
public final class AssetsCommand implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--src", required = true, description = "The assets/ directory (or a resources root containing assets/).")
    Path src;

    @Option(names = "--from", required = true, description = "Source Minecraft version, e.g. 1.19.2.")
    String from;

    @Option(names = "--to", required = true, description = "Target Minecraft version, e.g. 1.7.10.")
    String to;

    @Option(names = "--out", required = true, description = "Output directory.")
    Path out;

    @Option(names = "--ir", description = "IR file(s) (*.ir.json) or directories of them: lang key table, sound categories, blocks to fill.")
    List<Path> ir = new ArrayList<>();

    @Option(names = "--key-map", description = "JSON object {sourceKey: targetKey} overriding the key table.")
    Path keyMap;

    @Option(names = "--legacy-names", description = "Unlocalized-name style when IR has none: ${COMPLETION-CANDIDATES} (default PATH).")
    Context.LegacyNames legacyNames = Context.LegacyNames.PATH;

    @Option(names = "--keep-source-keys", description = "Also keep renamed lang entries under their source key.")
    boolean keepSourceKeys;

    @Option(names = "--no-merge-split-lang", description = "Do not merge <prefix>_<locale>.json lang files.")
    boolean noMergeSplitLang;

    @Option(names = "--pack", description = "Extra sprite sheet: <name>=<ns>:<glob> (glob relative to <ns>/textures/).")
    Map<String, String> pack = new LinkedHashMap<>();

    @Option(names = "--no-auto-pack", description = "Do not pack non-block/item texture packages into sheets.")
    boolean noAutoPack;

    @Option(names = "--sheet-size", description = "Maximum sheet size in pixels (default 256).")
    int sheetSize = 256;

    @Option(names = "--trim", description = "Trim transparent borders of packed sprites.")
    boolean trim;

    @Option(names = "--keep-model-files", description = "Keep models/, blockstates/ and texture packages in a 1.7.10 output.")
    boolean keepModelFiles;

    @Option(names = "--select", paramLabel = "<ns>", description = "Convert only the assets of namespace <ns> that the --ir files reference "
            + "(texture packages, menu/block/item/fluid textures, lang keys); the subset is written to <out>/selected/.")
    String select;

    @Option(names = "--include", paramLabel = "<glob>", description = "With --select: more files, glob relative to assets/<ns>/ ('*' crosses '/'; repeatable).")
    List<String> include = new ArrayList<>();

    @Option(names = "--literals", paramLabel = "<list>", description = "With --select: file listing Java sources (relative to --literals-root) whose "
            + "string literals that are lang keys, and texture/sound paths that exist in the source assets, are kept too.")
    Path literals;

    @Option(names = "--literals-root", paramLabel = "<dir>", description = "Root of the --literals paths.")
    Path literalsRoot;

    @Override
    public String path() {
        return "assets";
    }

    @Override
    public Integer call() {
        PrintWriter outW = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        RunReport report = new RunReport("assets", spec.commandLine().getParseResult().originalArgs());
        AssetVersion vFrom = AssetVersion.of(from).orElse(null);
        AssetVersion vTo = AssetVersion.of(to).orElse(null);
        if (vFrom == null || vTo == null || vTo.ordinal() < vFrom.ordinal()) {
            String msg = "unsupported conversion " + from + " -> " + to + " (chain nodes: "
                    + java.util.Arrays.stream(AssetVersion.values()).map(v -> v.mc).toList() + ", newer -> older only)";
            err.println("porter assets: " + msg);
            report.put("error", msg).write(RunReport.STATUS_FAILED, ExitCodes.USAGE);
            return ExitCodes.USAGE;
        }
        try {
            Path assetsDir = Files.isDirectory(src.resolve("assets")) ? src.resolve("assets") : src;
            if (select != null) {
                List<JsonNode> docs = new ArrayList<>();
                for (Path p : ir) {
                    docs.add(Json.json().readTree(Files.readString(p)));
                }
                List<Path> lit = new ArrayList<>();
                if (literals != null) {
                    for (String l : Files.readAllLines(literals)) {
                        if (!l.isBlank()) {
                            lit.add((literalsRoot != null ? literalsRoot : literals.toAbsolutePath().getParent()).resolve(l.strip()));
                        }
                    }
                }
                Path sel = out.resolve("selected");
                AssetSelect.Result sr = AssetSelect.select(assetsDir, select, docs, include, lit, sel);
                outW.println("  selected: " + sr.textures() + " texture files, " + sr.langEntries() + " lang entries");
                assetsDir = sel.resolve("assets");
            }
            Context ctx = new Context();
            for (Path p : ir) {
                if (Files.isDirectory(p)) { // a directory stands for all its *.ir.json files (what `porter extract` wrote)
                    try (Stream<Path> files = Files.list(p)) {
                        for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".ir.json")).sorted().toList()) {
                            ctx.ir.add(IrIo.read(f));
                        }
                    }
                } else {
                    ctx.ir.add(IrIo.read(p));
                }
            }
            if (keyMap != null) {
                JsonNode km = Json.json().readTree(Files.readString(keyMap));
                km.properties().forEach(e -> ctx.keyMapOverrides.put(e.getKey(), e.getValue().asString()));
            }
            ctx.legacyNames = legacyNames;
            ctx.keepSourceKeys = keepSourceKeys;
            ctx.mergeSplitLang = !noMergeSplitLang;
            ctx.packSpecs.putAll(pack);
            ctx.autoPack = !noAutoPack;
            ctx.sheetSize = sheetSize;
            ctx.trimSprites = trim;
            ctx.dropModelFiles = !keepModelFiles;

            Result r = convert(assetsDir, vFrom, vTo, ctx);
            clean(out.resolve("assets"));
            clean(out.resolve("meta"));
            r.tree().writeTo(out.resolve("assets"));
            Map<String, Object> summary = summary(ctx, r, vFrom, vTo);
            ctx.putMeta("summary.json", Json.json().writeValueAsString(summary) + "\n");
            ctx.putMeta("warnings.json", Json.json().writeValueAsString(ctx.warnings) + "\n");
            Map<String, Object> keys = new TreeMap<>();
            ctx.langKeyMap.forEach((k, m) -> keys.put(k, Map.of("target", m.target(), "origin", m.origin().name().toLowerCase(),
                    "guessed", m.guessed())));
            ctx.putMeta("lang-key-map.json", Json.json().writeValueAsString(keys) + "\n");
            for (Map.Entry<String, byte[]> e : ctx.meta.entrySet()) {
                Path f = out.resolve("meta").resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue());
            }
            boolean ok = r.lang().lossless() && r.sounds().lossless() && ctx.losses.isEmpty();
            outW.println("porter assets " + vFrom + " -> " + vTo + ": " + r.tree().size() + " files -> " + out);
            outW.println("  lang: " + r.lang().details().get("matched") + "/" + r.lang().details().get("sourceEntries")
                    + " entries carried over, lossless=" + r.lang().lossless());
            outW.println("  sounds: " + r.sounds().details().get("events") + " events, lossless=" + r.sounds().lossless());
            outW.println("  warnings: " + ctx.warnings.size() + ", losses: " + ctx.losses.size()
                    + ", needs-manual-render: " + ctx.metrics.getOrDefault("render.needsManualRender", 0));
            ctx.losses.stream().limit(20).forEach(l -> outW.println("  LOSS " + l.code() + " " + l.subject() + ": " + l.message()));
            outW.flush();
            int code = ok ? ExitCodes.OK : ExitCodes.FAILED;
            summary.forEach(report::put);
            report.write(ok ? RunReport.STATUS_OK : RunReport.STATUS_FAILED, code);
            return code;
        } catch (RuntimeException | IOException e) {
            err.println("porter assets: " + e);
            report.put("error", e.toString()).write(RunReport.STATUS_ERROR, ExitCodes.ERROR);
            return ExitCodes.ERROR;
        }
    }

    /** Converted tree plus verification results. */
    public record Result(AssetTree tree, Verifier.Result lang, Verifier.Result sounds) {
    }

    /** Library entry point: reads, normalises, converts and verifies (no disk writes). */
    public static Result convert(Path assetsDir, AssetVersion from, AssetVersion to, Context ctx) {
        AssetTree tree = AssetTree.read(assetsDir);
        Chain.normalise(tree, ctx, from);
        AssetTree source = AssetTree.read(assetsDir);
        Chain.normalise(source, new Context(), from); // same normalisation, separate warnings
        Chain.run(tree, ctx, from, to);
        return new Result(tree, Verifier.lang(source, tree, ctx, to), Verifier.sounds(source, tree));
    }

    static Map<String, Object> summary(Context ctx, Result r, AssetVersion from, AssetVersion to) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("from", from.mc);
        s.put("to", to.mc);
        s.put("steps", ctx.applied);
        s.put("files", r.tree().size());
        s.put("lang", r.lang().details());
        s.put("sounds", r.sounds().details());
        s.put("metrics", ctx.metrics);
        Map<String, Integer> byCode = new TreeMap<>();
        ctx.warnings.forEach(w -> byCode.merge(w.code(), 1, Integer::sum));
        s.put("warningsByCode", byCode);
        s.put("losses", ctx.losses);
        return s;
    }

    private static void clean(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
