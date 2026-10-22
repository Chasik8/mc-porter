package dev.porter.generate.forge1710;

import com.squareup.javapoet.ClassName;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.GenerationException;
import dev.porter.generate.Names;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/** Names, ids and layouts of one 1.7.10 run, decided once and shared by all emitters. */
final class Plan {
    final GenContext ctx;
    final String pkg;
    final String modPascal;
    final ClassName content;
    final ClassName lookup;
    final ClassName guiHandler;
    final ClassName network;
    final ClassName recipes;
    final ClassName modClass;
    final ClassName clientEvents;
    final ClassName multiblocks;
    final ClassName multiblockDef;
    /** menu id → GUI id. */
    final Map<String, Integer> guiIds = new TreeMap<>();
    /** effect id → Potion id. */
    final Map<String, Integer> potionIds = new TreeMap<>();
    /** block id → layout. */
    final Map<String, MetaLayout> layouts = new TreeMap<>();
    /** packet id → discriminator. */
    final Map<String, Integer> discriminators = new TreeMap<>();
    final Set<String> vanillaBlocks = new TreeSet<>();
    final Set<String> vanillaItems = new TreeSet<>();
    /** Manifest sections. */
    final Map<String, Object> blockManifest = new TreeMap<>();
    final Map<String, Object> menuManifest = new TreeMap<>();
    final Map<String, String> oreDictManifest = new TreeMap<>();
    /** block id → generated state-holder tile (blocks without IR block entity whose state spills). */
    final Map<String, ClassName> stateTiles = new TreeMap<>();
    /** IR channel names merged into the single 1.7.10 channel. */
    final Set<String> channelNames = new TreeSet<>();

    /** Forge 1.7.10 OreDictionary.initVanillaEntries names. */
    static final Set<String> FORGE_VANILLA_ORES = Set.of("blockCoal", "blockDiamond", "blockEmerald", "blockGlass",
            "blockGlassColorless", "blockGold", "blockIron", "blockLapis", "blockQuartz", "blockRedstone", "cobblestone",
            "cropCarrot", "cropPotato", "cropWheat", "dustGlowstone", "dustRedstone", "dye", "gemDiamond", "gemEmerald",
            "gemLapis", "gemQuartz", "glowstone", "ingotBrick", "ingotBrickNether", "ingotGold", "ingotIron", "logWood",
            "nuggetGold", "oreCoal", "oreDiamond", "oreEmerald", "oreGold", "oreIron", "oreLapis", "oreQuartz", "oreRedstone",
            "paneGlass", "paneGlassColorless", "plankWood", "record", "sand", "sandstone", "slabWood", "slimeball",
            "stairWood", "stickWood", "stone", "treeLeaves", "treeSapling");
    /** Forge/common tag folders → 1.7.10 OreDictionary prefixes (Forge naming convention). */
    static final Map<String, String> ORE_PREFIX = Map.ofEntries(Map.entry("ingots", "ingot"), Map.entry("nuggets", "nugget"),
            Map.entry("ores", "ore"), Map.entry("gems", "gem"), Map.entry("dusts", "dust"), Map.entry("plates", "plate"),
            Map.entry("gears", "gear"), Map.entry("rods", "stick"), Map.entry("storage_blocks", "block"),
            Map.entry("dyes", "dye"), Map.entry("crops", "crop"), Map.entry("seeds", "seed"));
    /** Vanilla 1.19.2 tags with a Forge 1.7.10 OreDictionary counterpart. */
    static final Map<String, String> VANILLA_TAG_ORES = Map.ofEntries(Map.entry("minecraft:planks", "plankWood"),
            Map.entry("minecraft:logs", "logWood"),
            Map.entry("minecraft:saplings", "treeSapling"),
            Map.entry("minecraft:leaves", "treeLeaves"),
            Map.entry("minecraft:wooden_slabs", "slabWood"),
            Map.entry("minecraft:wooden_stairs", "stairWood"),
            Map.entry("forge:cobblestone", "cobblestone"),
            Map.entry("forge:stone", "stone"),
            Map.entry("forge:sand", "sand"),
            Map.entry("forge:slimeballs", "slimeball"),
            Map.entry("forge:glass", "blockGlass"));

    /**
     * OreDictionary name for a tag: the tag node's {@code oreDict[0]}, else the Forge naming convention
     * ({@code forge:ingots/copper} → {@code ingotCopper}), else the tag id itself (reported).
     */
    String oreName(String tagId, String subject) {
        Optional<IrNode.Tag> tag = ctx.ir.get(IrNode.Tag.class, tagId);
        if (tag.isPresent() && tag.get().oreDict() != null && !tag.get().oreDict().isEmpty()) {
            return tag.get().oreDict().get(0);
        }
        String name = VANILLA_TAG_ORES.get(tagId);
        if (name == null) {
            String ns = Names.namespace(tagId);
            String[] parts = Names.path(tagId).split("/");
            if (("forge".equals(ns) || "c".equals(ns)) && parts.length == 2 && ORE_PREFIX.containsKey(parts[0])) {
                name = ORE_PREFIX.get(parts[0]) + Names.pascal(parts[1]);
            }
        }
        if (name != null) {
            if (oreDictManifest.putIfAbsent(tagId, name) == null) {
                boolean known = FORGE_VANILLA_ORES.contains(name) || tag.isPresent();
                if (known) {
                    ctx.info("oredict-derived", tagId, "tag -> OreDictionary " + name + " (naming convention)");
                } else {
                    ctx.warn("oredict-derived", tagId, "tag -> OreDictionary " + name
                            + " (naming convention; registered by other mods, not by this one)");
                }
            }
            return name;
        }
        if (oreDictManifest.putIfAbsent(tagId, tagId) == null) {
            ctx.warn("oredict-unresolved", tagId, "no OreDictionary name for tag (used in " + subject
                    + "); the tag id is used as name, nothing registers it unless a tag node lists values");
        }
        return tagId;
    }

    Plan(GenContext ctx) {
        this.ctx = ctx;
        this.pkg = ctx.options.basePackage();
        this.modPascal = Names.pascal(ctx.options.modId());
        content = ClassName.get(pkg, modPascal + "Content");
        lookup = ClassName.get(pkg, modPascal + "Lookup");
        guiHandler = ClassName.get(pkg, modPascal + "GuiHandler");
        network = ClassName.get(pkg + ".network", modPascal + "Network");
        recipes = ClassName.get(pkg + ".recipe", modPascal + "Recipes");
        modClass = ClassName.get(pkg, modPascal + "Mod");
        clientEvents = ClassName.get(pkg + ".client", modPascal + "ClientEvents");
        multiblocks = ClassName.get(pkg + ".multiblock", modPascal + "Multiblocks");
        multiblockDef = ClassName.get(pkg + ".multiblock", "MultiblockDef");
        loadVanilla();
        allocate();
    }

    private void loadVanilla() {
        try (InputStream in = Plan.class.getResourceAsStream("/porter/generate/forge1710/vanilla-names.txt");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("block ")) {
                    vanillaBlocks.add(line.substring(6).trim());
                } else if (line.startsWith("item ")) {
                    vanillaItems.add(line.substring(5).trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void allocate() {
        var o = ctx.options;
        int next = o.guiIds().from();
        for (IrNode.Menu m : ctx.ir.all(IrNode.Menu.class)) {
            if (next > o.guiIds().to()) {
                throw new GenerationException("GUI id range " + o.guiIds() + " too small for "
                        + ctx.ir.all(IrNode.Menu.class).size() + " menus (--gui-ids)");
            }
            guiIds.put(m.id(), next);
            ctx.info("gui-id", m.id(), "GUI id " + next + " (range " + o.guiIds() + ")");
            next++;
        }
        next = o.potionIds().from();
        for (IrNode.Effect e : ctx.ir.all(IrNode.Effect.class)) {
            if (next > o.potionIds().to()) {
                throw new GenerationException("Potion id range " + o.potionIds() + " too small for "
                        + ctx.ir.all(IrNode.Effect.class).size() + " effects (--potion-ids)");
            }
            if (next > 31) {
                ctx.warn("potion-id-array", e.id(), "Potion id " + next + " >= 32: vanilla Potion.potionTypes has 32"
                        + " slots; needs a potion-array extender mod at runtime");
            }
            potionIds.put(e.id(), next);
            ctx.info("potion-id", e.id(), "Potion id " + next + " (range " + o.potionIds() + ")");
            next++;
        }
        TreeSet<Integer> used = new TreeSet<>();
        for (IrNode.Packet p : ctx.ir.all(IrNode.Packet.class)) {
            if (p.discriminator() != null && used.add(p.discriminator())) {
                discriminators.put(p.id(), p.discriminator());
            }
        }
        int d = 0;
        for (IrNode.Packet p : ctx.ir.all(IrNode.Packet.class)) {
            if (!discriminators.containsKey(p.id())) {
                while (used.contains(d)) {
                    d++;
                }
                used.add(d);
                discriminators.put(p.id(), d);
                ctx.info("packet-discriminator", p.id(), "discriminator " + d + " allocated"
                        + (p.discriminator() != null ? " (IR value " + p.discriminator() + " already taken)" : ""));
            }
        }
        if (d > 255 || discriminators.values().stream().anyMatch(v -> v > 255)) {
            throw new GenerationException("SimpleNetworkWrapper discriminators must be 0..255");
        }
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            MetaLayout l = MetaLayout.of(b);
            layouts.put(b.id(), l);
            if (l.spills()) {
                ctx.info("meta-spill", b.id(), l.totalStates + " states > 16: "
                        + l.metaProps().stream().map(MetaLayout.Prop::name).toList() + " in meta (" + l.metaStates
                        + " values), " + l.spilled().stream().map(MetaLayout.Prop::name).toList()
                        + " stored in TileEntity fields" + (b.blockEntity() == null ? " (state-holder TileEntity generated)" : ""));
            } else if (!l.props.isEmpty()) {
                ctx.info("meta-layout", b.id(), l.totalStates + " states in meta: "
                        + l.props.stream().map(p -> p.name() + "*" + p.multiplier()).toList());
            }
        }
    }

    // ---- names -----------------------------------------------------------------------------------

    /** Registry name (without mod id) of one of our objects. */
    String registryName(String id) {
        return Names.path(id).replace('/', '_');
    }

    private final Set<String> clashLogged = new TreeSet<>();

    /**
     * Registry name of one of our blocks. 1.7.10 blocks and items share names (an ItemBlock is registered under the
     * block's name), so a block without block item whose name equals a separate item gets a {@code _block} suffix.
     */
    String blockRegistryName(String blockId) {
        String n = registryName(blockId);
        boolean ownItem = ctx.ir.all(IrNode.Item.class).stream().anyMatch(i -> blockId.equals(i.placesBlock()));
        boolean clash = !ownItem && ctx.ir.all(IrNode.Item.class).stream()
                .anyMatch(i -> i.placesBlock() == null && registryName(i.id()).equals(n));
        if (!clash) {
            return n;
        }
        if (clashLogged.add(blockId)) {
            ctx.warn("registry-name-clash", blockId, "block and item share the name " + n + "; block registered as " + n + "_block");
        }
        return n + "_block";
    }

    String legacyName(String id, Map<String, JsonNode> ext) {
        if (ext != null && ext.get("porter:legacyName") != null) {
            return ext.get("porter:legacyName").asString();
        }
        return ctx.options.legacyNames().apply(Names.namespace(id), Names.path(id));
    }

    String pascal(String id) {
        return Names.pascal(Names.path(id));
    }

    String constant(String id) {
        return Names.constant(Names.path(id));
    }

    ClassName blockBase(String id) {
        return ClassName.get(pkg + ".block", pascal(id) + "BlockBase");
    }

    ClassName itemBase(String id) {
        return ClassName.get(pkg + ".item", pascal(id) + "ItemBase");
    }

    ClassName tileBase(String id) {
        return ClassName.get(pkg + ".tile", pascal(id) + "TileBase");
    }

    /** State-holder TileEntity for a block whose state spills but that has no block entity in the IR. */
    ClassName stateTile(String blockId) {
        return ClassName.get(pkg + ".tile", pascal(blockId) + "StateTile");
    }

    ClassName containerBase(String id) {
        return ClassName.get(pkg + ".menu", pascal(id) + "ContainerBase");
    }

    ClassName guiBase(String id) {
        return ClassName.get(pkg + ".client", pascal(id) + "GuiBase");
    }

    ClassName message(String id) {
        return ClassName.get(pkg + ".network", pascal(id) + "Message");
    }

    ClassName messageHandler(IrNode.Packet p) {
        return p.handlerClass() != null ? cls(p.handlerClass()) : ClassName.get(pkg + ".network", pascal(p.id()) + "MessageHandler");
    }

    ClassName fluidClass(String id) {
        return ClassName.get(pkg + ".fluid", pascal(id) + "Fluid");
    }

    ClassName potionBase(String id) {
        return ClassName.get(pkg + ".potion", pascal(id) + "PotionBase");
    }

    static ClassName cls(String fqn) {
        String p = Names.packageOf(fqn);
        String simple = fqn.substring(p.isEmpty() ? 0 : p.length() + 1);
        String[] parts = simple.split("\\$");
        String[] rest = new String[parts.length - 1];
        System.arraycopy(parts, 1, rest, 0, rest.length);
        return ClassName.get(p, parts[0], rest);
    }

    /** Class that is instantiated for a node: the logic class (stub or rewriter output) if any, else the base. */
    ClassName blockClass(IrNode.Block b) {
        return b.logicClass() != null && !sharedBlockLogic(b) ? cls(b.logicClass()) : blockBase(b.id());
    }

    /**
     * True when several blocks name the same logic class (one Java class cannot extend each block's generated base;
     * the 1.19.2 constructor arguments that tell the blocks apart are not in the IR). Such blocks are registered with
     * their generated base and the logic class gets no stub; the port constructs the logic class itself.
     */
    boolean sharedBlockLogic(IrNode.Block b) {
        return b.logicClass() != null && !sourceConstructed(b) && ctx.ir.all(IrNode.Block.class).stream()
                .filter(o -> b.logicClass().equals(o.logicClass())).count() > 1;
    }

    /** Extension of the extractor: the source registry's constructor arguments of the logic class. */
    static final String NEW_ARGS = "porter-extract:newArgs";

    /**
     * True when the node's logic class is constructed like the source registry did ({@code --source-constructors}):
     * {@code new Logic(<recorded arguments>)}, names set after construction, no generated base or stub for it.
     */
    boolean sourceConstructed(IrNode n) {
        String logic = n instanceof IrNode.Block b ? b.logicClass() : n instanceof IrNode.Item i ? i.logicClass() : null;
        return ctx.options.sourceConstructors() && logic != null;
    }

    /** {@code new Logic(args)} of a source-constructed node (arguments as recorded, raw Java). */
    com.squareup.javapoet.CodeBlock sourceNew(IrNode n, String logicClass) {
        java.util.List<String> args = new java.util.ArrayList<>();
        JsonNode v = n.extensions() == null ? null : n.extensions().get(NEW_ARGS);
        if (v != null && v.isArray()) {
            v.forEach(x -> args.add(x.asString()));
        }
        return com.squareup.javapoet.CodeBlock.of("new $T($L)", cls(logicClass), String.join(", ", args));
    }

    ClassName tileClass(IrNode.BlockEntity be) {
        return cls(be.logicClass());
    }

    ClassName itemClass(IrNode.Item i) {
        return i.logicClass() != null ? cls(i.logicClass()) : itemBase(i.id());
    }

    ClassName containerClass(IrNode.Menu m) {
        return m.logicClass() != null ? cls(m.logicClass()) : containerBase(m.id());
    }

    ClassName guiClass(IrNode.Menu m) {
        return m.screenClass() != null ? cls(m.screenClass()) : guiBase(m.id());
    }

    ClassName potionClass(IrNode.Effect e) {
        return e.logicClass() != null ? cls(e.logicClass()) : potionBase(e.id());
    }

    ClassName fluidClassFor(IrNode.Fluid f) {
        return f.logicClass() != null ? cls(f.logicClass()) : fluidClass(f.id());
    }

    String field(String kindPrefix, String id) {
        return kindPrefix + Names.pascal(Names.path(id));
    }

    // ---- textures --------------------------------------------------------------------------------

    /** Texture ref ({@code ns:block/x}, {@code ns:blocks/x}, {@code ns:item/x}) → 1.7.10 icon name {@code ns:x}. */
    String icon(String ref) {
        if (ref == null) {
            return null;
        }
        String ns = Names.namespace(ref);
        String path = Names.path(ref);
        for (String dir : new String[] {"blocks/", "items/", "block/", "item/"}) {
            if (path.startsWith(dir)) {
                path = path.substring(dir.length());
                break;
            }
        }
        return iconNs(ns) + ":" + path;
    }

    /** An icon name from {@code assets:icons}, with the namespace renamed if requested. */
    String renameIcon(String icon) {
        if (icon == null) {
            return null;
        }
        return icon.contains(":") ? iconNs(Names.namespace(icon)) + ":" + Names.path(icon) : icon;
    }

    String iconNs(String ns) {
        String r = ctx.options.assetNamespace();
        return r != null && ctx.ir.contentNamespaces().contains(ns) ? r : ns;
    }

    /** GUI texture path {@code ns:textures/gui/x.png} → (domain, path). */
    String[] resource(String ref) {
        return new String[] {iconNs(Names.namespace(ref)), Names.path(ref)};
    }

    // ---- ids -------------------------------------------------------------------------------------

    /** Registry reference (modid, name) of an id as seen on 1.7.10, or null if a vanilla id is unknown there. */
    String[] target(String id, boolean block) {
        String mapped = ctx.options.idMap().get(id);
        if (mapped != null) {
            int at = mapped.lastIndexOf('@');
            String ref = at > 0 ? mapped.substring(0, at) : mapped;
            int c = ref.indexOf(':');
            if (idMapLogged.add(id)) {
                ctx.info("id-mapped", id, "--id-map: " + mapped);
            }
            return at > 0 ? new String[] {ref.substring(0, c), ref.substring(c + 1), mapped.substring(at + 1)}
                    : new String[] {ref.substring(0, c), ref.substring(c + 1)};
        }
        String ns = Names.namespace(id);
        if (ctx.isOwn(id) && !hasNode(id)) {
            if (idMapLogged.add(id)) {
                ctx.warn("own-id-without-node", id, "id in the mod's namespace without an IR node and not in --id-map;"
                        + " reference skipped at runtime");
            }
            return null;
        }
        if (ctx.isOwn(id)) {
            boolean isBlock = ctx.ir.get(IrNode.Block.class, id).isPresent();
            boolean isItem = ctx.ir.get(IrNode.Item.class, id).isPresent();
            return new String[] {ctx.options.modId(), isBlock && (block || !isItem) ? blockRegistryName(id) : registryName(id)};
        }
        if ("minecraft".equals(ns)) {
            String p = Names.path(id);
            boolean known = block ? vanillaBlocks.contains(p) : vanillaItems.contains(p) || vanillaBlocks.contains(p);
            return known ? new String[] {"minecraft", p} : null;
        }
        return new String[] {ns, Names.path(id)};
    }

    private final Set<String> idMapLogged = new TreeSet<>();

    /** True if the IR has a node (any kind except lang/mod) with this id. */
    boolean hasNode(String id) {
        return ctx.ir.get(IrNode.Block.class, id).isPresent() || ctx.ir.get(IrNode.Item.class, id).isPresent()
                || ctx.ir.get(IrNode.Fluid.class, id).isPresent() || ctx.ir.get(IrNode.BlockEntity.class, id).isPresent();
    }

    /** Metadata of a target reference (third element), default 0. */
    static int meta(String[] t) {
        return t.length > 2 ? Integer.parseInt(t[2]) : 0;
    }

    /** Ordered map helper. */
    static <K, V> Map<K, V> map() {
        return new LinkedHashMap<>();
    }
}
