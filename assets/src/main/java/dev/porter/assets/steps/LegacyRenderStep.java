package dev.porter.assets.steps;

import dev.porter.assets.AssetTree;
import dev.porter.assets.AssetVersion;
import dev.porter.assets.Context;
import dev.porter.assets.Step;
import dev.porter.assets.model.LegacyIcons;
import dev.porter.assets.model.ModelConverter;
import dev.porter.assets.model.RenderResult;
import dev.porter.assets.model.TexturePackages;
import dev.porter.assets.pack.SheetPacker;
import dev.porter.assets.sound.SoundsStrict;
import dev.porter.assets.texture.TextureLegacy;
import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 1.8.9 → 1.7.10 (both pack_format 1): no JSON models/blockstates before 1.8, blocks pick icons per side in code.
 *
 * <ul>
 *   <li>Block models and face-alias texture packages → per-side icons, written as IR {@code block} nodes with
 *       {@code textures} (source layout) plus {@code extensions["assets:icons"]} (1.7.10 icon names) and
 *       {@code extensions["assets:stateIcons"]} (other states/variants) — {@code meta/blocks.textures.ir.json};</li>
 *   <li>everything else → {@code meta/needs-manual-render.json} (+ OBJ exports);</li>
 *   <li>strict 1.7.10 checks for {@code sounds.json}, {@code .mcmeta} and atlas sprites;</li>
 *   <li>sheet packing of GUI sprite sets ({@code meta/sheets/*.json}).</li>
 * </ul>
 */
public final class LegacyRenderStep implements Step {
    private static final Pattern TEXTURE_PATH = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    @Override
    public AssetVersion from() {
        return AssetVersion.V1_8_9;
    }

    @Override
    public AssetVersion to() {
        return AssetVersion.V1_7_10;
    }

    @Override
    public String description() {
        return "models/texture packages -> per-side icons + needs-manual-render; strict 1.7.10 sounds/mcmeta/sprites; sheets";
    }

    @Override
    public void apply(AssetTree tree, Context ctx) {
        SoundsStrict.apply(tree, ctx);
        TextureLegacy.apply(tree, ctx);
        if (ctx.knownBlocks.isEmpty()) {
            ctx.irNodes(IrNode.Block.class).forEach(b -> ctx.knownBlocks.put(b.id(), "ir"));
        }
        RenderResult result = new RenderResult();
        new ModelConverter(tree, ctx, result).run();
        TexturePackages packages = new TexturePackages(tree, ctx, result);
        packages.run();
        writeIr(result, ctx);
        writeManual(result, ctx);
        ctx.putMeta("texture-packages.json", Json.json().writeValueAsString(packages.packages) + "\n");
        pack(tree, ctx, packages);
        if (ctx.dropModelFiles) {
            int dropped = 0;
            for (String p : tree.paths()) {
                String local = AssetTree.local(p);
                if (local.startsWith("models/") && local.endsWith(".json") || local.startsWith("blockstates/")
                        || p.endsWith("/" + TexturePackages.FILE)) {
                    tree.remove(p);
                    dropped++;
                }
            }
            ctx.metric("render.modelFilesDropped", dropped);
        }
    }

    private void writeIr(RenderResult result, Context ctx) {
        boolean flattened = ctx.source != null && ctx.source.ordinal() <= AssetVersion.V1_14_4.ordinal();
        String version = "forge-" + (ctx.source == null ? AssetVersion.V1_8_9 : ctx.source).mc;
        List<IrNode> nodes = new ArrayList<>();
        Map<String, IrNode.Block> byId = new TreeMap<>();
        for (RenderResult.CubeBlock b : result.blocks.values()) {
            Map<String, String> textures = new LinkedHashMap<>();
            Map<String, String> icons = new LinkedHashMap<>();
            boolean ok = true;
            for (Map.Entry<String, String> e : b.textures().entrySet()) {
                String src = LegacyIcons.sourceForm(e.getValue(), flattened);
                if (!TEXTURE_PATH.matcher(src).matches()) {
                    ctx.warn("ir-texture-invalid", b.id() + "#" + e.getKey(), "texture ref " + src + " is not a valid IR texture path");
                    ok = false;
                    continue;
                }
                textures.put(e.getKey(), src);
                String icon = LegacyIcons.icon(e.getValue());
                if (icon == null) {
                    ctx.warn("icon-outside-atlas", b.id() + "#" + e.getKey(), e.getValue() + " is not under textures/blocks; cannot be a 1.7.10 icon");
                } else {
                    icons.put(e.getKey(), icon);
                }
            }
            Map<String, JsonNode> ext = new TreeMap<>();
            ext.put("assets:icons", Json.json().valueToTree(icons));
            if (!b.variants().isEmpty()) {
                Map<String, Map<String, String>> si = new TreeMap<>();
                b.variants().forEach((state, faces) -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    faces.forEach((f, t) -> m.put(f, LegacyIcons.icon(t)));
                    si.put(state, m);
                });
                ext.put("assets:stateIcons", Json.json().valueToTree(si));
            }
            if (b.note() != null) {
                ext.put("assets:note", Json.json().valueToTree(b.note()));
            }
            if (!ok) {
                ext.put("assets:incomplete", Json.json().valueToTree(true));
            }
            String ref = b.modelRef();
            IrNode.Block.Model model = new IrNode.Block.Model(ref != null && TEXTURE_PATH.matcher(ref).matches() ? ref : null,
                    b.shape(), null);
            String file = "assets/" + b.file();
            IrNode.Block node = new IrNode.Block(b.id(), null, null, null, null, null, null, null, null, null, null, null,
                    textures.isEmpty() ? null : textures, model, null, null, null,
                    new Provenance(file, 0, version, "resource", b.modelRef() != null ? "assets/model-cube" : "assets/texture-package"),
                    b.confidence() >= 1.0 ? null : b.confidence(), ext);
            nodes.add(node);
            byId.put(b.id(), node);
        }
        IrDocument doc = new IrDocument(new IrDocument.Source(version, null, "porter assets"), nodes);
        ctx.putMeta("blocks.textures.ir.json", Json.json().writeValueAsString(
                ContractIo.toValidatedTree(doc, Contract.IR, "blocks.textures.ir.json")) + "\n");
        ctx.metric("render.cubeBlocks", nodes.size());
        // Write-back into the given IR: fill textures/model of blocks that have none.
        for (int i = 0; i < ctx.ir.size(); i++) {
            IrDocument in = ctx.ir.get(i);
            List<IrNode> merged = new ArrayList<>();
            int filled = 0;
            for (IrNode n : in.nodes()) {
                if (n instanceof IrNode.Block b && b.textures() == null && byId.containsKey(b.id())) {
                    IrNode.Block t = byId.get(b.id());
                    Map<String, JsonNode> ext = new TreeMap<>(b.extensions() == null ? Map.of() : b.extensions());
                    ext.putAll(t.extensions());
                    n = new IrNode.Block(b.id(), b.translationKey(), b.logicClass(), b.material(), b.hardness(), b.resistance(),
                            b.lightLevel(), b.soundType(), b.requiresTool(), b.harvest(), b.creativeTab(), b.stateProperties(),
                            t.textures(), b.model() != null ? b.model() : t.model(), b.blockEntity(), b.item(), b.lootTable(),
                            b.provenance(), b.confidence(), ext);
                    filled++;
                }
                merged.add(n);
            }
            ctx.putMeta("ir/input-" + i + ".textures.ir.json", Json.json().writeValueAsString(ContractIo.toValidatedTree(
                    new IrDocument(in.schemaVersion(), in.source(), merged), Contract.IR, "merged IR " + i)) + "\n");
            ctx.count("render.irBlocksFilled", filled);
        }
    }

    private void writeManual(RenderResult result, Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RenderResult.Manual m : result.manual.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("subject", m.subject());
            e.put("source", m.source());
            e.put("reason", m.reason());
            if (m.obj() != null) {
                e.put("obj", m.obj());
            }
            list.add(e);
        }
        ctx.putMeta("needs-manual-render.json", Json.json().writeValueAsString(list) + "\n");
        ctx.metric("render.needsManualRender", list.size());
        ctx.metric("render.objExports", (int) result.manual.values().stream().filter(m -> m.obj() != null).count());
    }

    private void pack(AssetTree tree, Context ctx, TexturePackages packages) {
        int sheets = 0;
        int sprites = 0;
        if (ctx.autoPack) {
            for (Map.Entry<String, Map<String, String>> e : packages.sheetCandidates.entrySet()) {
                String ns = e.getKey().substring(0, e.getKey().indexOf(':'));
                String name = e.getKey().substring(e.getKey().indexOf(':') + 1).replace('/', '_');
                sprites += SheetPacker.pack(name, ns, e.getValue(), tree, ctx, ctx.sheetSize, ctx.trimSprites);
                sheets++;
            }
        }
        for (Map.Entry<String, String> spec : ctx.packSpecs.entrySet()) {
            String v = spec.getValue();
            int c = v.indexOf(':');
            if (c < 0) {
                ctx.loss("pack-spec-invalid", spec.getKey(), "expected <ns>:<glob>, got " + v);
                continue;
            }
            String ns = v.substring(0, c);
            Pattern glob = glob(v.substring(c + 1));
            String prefix = ns + "/textures/";
            Map<String, String> found = new TreeMap<>();
            for (String p : tree.paths(prefix)) {
                String rel = p.substring(prefix.length());
                if (p.endsWith(".png") && !rel.startsWith("porter_sheets/") && glob.matcher(rel).matches()) {
                    found.put(rel.substring(0, rel.length() - 4), p);
                }
            }
            if (found.isEmpty()) {
                ctx.warn("pack-empty", spec.getKey(), "no PNG matches " + v);
                continue;
            }
            sprites += SheetPacker.pack(spec.getKey(), ns, found, tree, ctx, ctx.sheetSize, ctx.trimSprites);
            sheets++;
        }
        ctx.metric("sheets.packs", sheets);
        ctx.metric("sheets.sprites", sprites);
    }

    /** Platform-independent glob: {@code **} any path, {@code *} within a segment, {@code ?} one char. */
    static Pattern glob(String g) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char ch = g.charAt(i);
            if (ch == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                b.append(".*");
                i++;
            } else if (ch == '*') {
                b.append("[^/]*");
            } else if (ch == '?') {
                b.append("[^/]");
            } else {
                b.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(b.toString());
    }
}
