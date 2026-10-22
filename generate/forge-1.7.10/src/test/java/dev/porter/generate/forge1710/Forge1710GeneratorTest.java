package dev.porter.generate.forge1710;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrIo;
import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.generate.GenContext;
import dev.porter.generate.GenOptions;
import dev.porter.generate.Generate;
import dev.porter.generate.GenerateCommand;
import dev.porter.generate.GenerationException;
import dev.porter.generate.IrIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class Forge1710GeneratorTest {
    static final Path IR = Path.of(System.getProperty("porter.repoRoot"), "generate", "testdata", "synthetic.ir.json");
    static final String J = "src/main/java/com/example/examplemod/gen/";
    static final Path NEI_IR = IR.resolveSibling("nei.ir.json");

    static GenOptions options(String guiIds) {
        return new GenOptions("1.7.10", null, null, null, null, false, GenOptions.IntRange.parse(guiIds),
                GenOptions.IntRange.parse("24-31"), null, null, null, GenOptions.LegacyNames.PATH, true);
    }

    static GenContext run(String guiIds) {
        return Generate.run(new Forge1710Generator(), IrIndex.load(List.of(IR)), options(guiIds)).context();
    }

    static String file(GenContext ctx, String path) {
        byte[] b = ctx.out.files().get(path);
        assertTrue(b != null, "missing " + path + " in " + ctx.out.files().keySet());
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void generatesAllConcepts() {
        GenContext ctx = run("50-59");
        String content = file(ctx, J + "ExamplemodContent.java");
        assertTrue(content.contains("GameRegistry.registerBlock(blockCopperPress, \"copper_press\")"), content);
        assertTrue(content.contains("FluidRegistry.registerFluid(fluidBerryJuice)"), content);
        assertTrue(content.contains("new JitteryEffect(idJittery)") && content.contains("potionId(POTION_ID_JITTERY, \"examplemod:jittery\")"), content);
        assertTrue(content.contains("registerTileEntity(CopperPressBlockEntity.class, \"examplemod:copper_press\")"), content);
        String block = file(ctx, J + "block/CopperPressBlockBase.java");
        assertTrue(block.contains("public IIcon getIcon(int side, int meta)"), block);
        assertTrue(block.contains("\"examplemod:copper_press_front_on\""), "state icon used");
        assertTrue(block.contains("setBlockName(\"examplemod.copperPress\")"), "porter:legacyName honoured");
        assertTrue(block.contains("setResistance(10.0F)"), "1.19.2 resistance 6 -> 1.7.10 setResistance(10)");
        assertTrue(file(ctx, J + "ExamplemodGuiHandler.java").contains("GUI_COPPER_PRESS = 50"));
        assertTrue(file(ctx, J + "menu/CopperPressContainerBase.java").contains("sendProgressBarUpdate"));
        assertTrue(file(ctx, J + "client/CopperPressGuiBase.java").contains("drawGuiContainerBackgroundLayer"));
        assertTrue(file(ctx, J + "network/ExamplemodNetwork.java").contains("newSimpleChannel(CHANNEL_NAME)"));
        assertTrue(file(ctx, J + "network/SyncStateMessage.java").contains("ByteBufUtils.writeUTF8String"));
        String recipes = file(ctx, J + "recipe/ExamplemodRecipes.java");
        assertTrue(recipes.contains("new ShapedOreRecipe(out, \"CCC\", \"CPC\", \"CRC\", 'C', in0"), recipes);
        assertTrue(recipes.contains("\"ingotCopper\""));
        assertTrue(recipes.contains("stack(\"examplemod\", \"copper_plate\", 2, 0)") && recipes.contains("recipeExamplemodCopperPlateStonecutting"),
                "stonecutting -> shapeless crafting");
        assertTrue(recipes.contains("CUSTOM_IDS") && recipes.contains("{\"examplemod:copper_plate\", \"examplemod\", \"copper_plate\", \"0\"}"),
                "ids of custom recipes resolved for the machine logic");
        assertTrue(recipes.contains("addOre(\"plateCopper\""), "derived OreDictionary name");
        assertTrue(file(ctx, J + "multiblock/ExamplemodMultiblocks.java").contains("MultiblockDef.builder(\"examplemod:big_press\")"));
        assertTrue(ctx.out.files().containsKey("src/main/resources/mcmod.info"));
        assertTrue(ctx.out.stubs().containsKey("com.example.examplemod.block.CopperPressBlock"));
        assertTrue(ctx.out.stubs().containsKey("com.example.examplemod.network.EjectOutputPacket"));
        assertTrue(file(ctx, "src/main/java/com/example/examplemod/block/CopperPressBlock.java").contains("porter:stub"));
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("ir-unknown")), "unknown nodes reported");
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("recipe-type-unsupported")), "blasting reported");
    }

    @Test
    void spillsStatesBeyondSixteen() {
        GenContext ctx = run("0-99");
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("meta-spill") && d.subject().equals("examplemod:signal_lamp")));
        String tile = file(ctx, J + "tile/SignalLampStateTile.java");
        assertTrue(tile.contains("public int stateColor"), tile);
        String block = file(ctx, J + "block/SignalLampBlockBase.java");
        assertTrue(block.contains("getIcon(IBlockAccess world"), "world-aware icon for spilled state");
        IrNode.Block lamp = IrIndex.load(List.of(IR)).get(IrNode.Block.class, "examplemod:signal_lamp").orElseThrow();
        MetaLayout l = MetaLayout.of(lamp);
        assertEquals(12, l.metaStates);
        assertEquals(48, l.totalStates);
        assertEquals(List.of("color"), l.spilled().stream().map(MetaLayout.Prop::name).toList());
    }

    @Test
    void iconsFollowFacingAndState() {
        IrIndex ir = IrIndex.load(List.of(IR));
        GenContext ctx = new GenContext(ir, Generate.resolve(options("0-99"), ir));
        Plan plan = new Plan(ctx);
        IrNode.Block press = ir.get(IrNode.Block.class, "examplemod:copper_press").orElseThrow();
        MetaLayout l = plan.layouts.get(press.id());
        BlockGen.Icons icons = new BlockGen(plan).icons(press, l);
        int front = icons.names().indexOf("examplemod:copper_press_front");
        int frontOn = icons.names().indexOf("examplemod:copper_press_front_on");
        // facing values [north, south, west, east] (x1), lit [false, true] (x4); sides 2=N 3=S 4=W 5=E
        assertEquals(front, icons.table()[0][2], "north: front on north side");
        assertEquals(front, icons.table()[1][3], "south: front on south side");
        assertEquals(front, icons.table()[2][4], "west: front on west side");
        assertEquals(front, icons.table()[3][5], "east: front on east side");
        assertEquals(frontOn, icons.table()[3 + 4][5], "east + lit: lit front (stateIcons) rotated too");
    }

    @Test
    void guiRangeTooSmall() {
        Provenance prov = new Provenance("x", 0, "forge-1.19.2", null, null);
        IrNode.Menu m = new IrNode.Menu("examplemod:other", null, null, null, null, new IrNode.Menu.Size(176, 166), null,
                List.of(), null, null, null, null, prov, null, null);
        IrDocument extra = new IrDocument(new IrDocument.Source("forge-1.19.2", null, null), List.of(m));
        assertThrows(GenerationException.class, () -> Generate.run(new Forge1710Generator(),
                IrIndex.of(List.of(IrIo.read(IR), extra)), options("5-5")));
    }

    @Test
    void cliIsDeterministicAndRemovesStaleFiles(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gen");
        String[] args = {"--ir", IR.toString(), "--version", "1.7.10", "--out", out.toString(), "--gui-ids", "50-59"};
        System.setProperty("porter.reports.dir", tmp.resolve("reports").toString());
        assertEquals(0, new CommandLine(new GenerateCommand()).execute(args));
        Map<String, byte[]> first = snapshot(out);
        Path manifest = out.resolve("porter-generate.json");
        String stalePath = "src/main/java/com/example/examplemod/gen/Stale.java";
        Files.writeString(manifest, Files.readString(manifest).replace("\"files\" : [", "\"files\" : [ \"" + stalePath + "\","));
        Files.writeString(out.resolve(stalePath), "class Stale {}");
        Path foreign = out.resolve("README.local");
        Files.writeString(foreign, "not ours");
        assertEquals(0, new CommandLine(new GenerateCommand()).execute(args));
        assertFalse(Files.exists(out.resolve(stalePath)), "stale generated file removed");
        assertTrue(Files.exists(foreign), "foreign file kept");
        Files.delete(foreign);
        Map<String, byte[]> second = snapshot(out);
        assertEquals(first.keySet(), second.keySet());
        for (String k : first.keySet()) {
            assertArrayEquals(first.get(k), second.get(k), k);
        }
        String report = Files.readString(tmp.resolve("reports/runs/generate.json"));
        assertTrue(report.contains("\"generator\" : \"forge-1.7.10\""), report);
        System.clearProperty("porter.reports.dir");
    }

    static Map<String, byte[]> snapshot(Path root) throws Exception {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.filter(Files::isRegularFile).toList()) {
                m.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    @Test
    void embedModeForHostMod() {
        GenOptions o = new GenOptions("1.7.10", "exampleaddon.gen", "exampleaddon", null, null, true, GenOptions.IntRange.parse("50-69"),
                GenOptions.IntRange.parse("24-31"), null, null, "exampleaddon", GenOptions.LegacyNames.PATH, false);
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(IR)), o).context();
        assertFalse(ctx.out.files().keySet().stream().anyMatch(p -> p.endsWith("ExampleaddonMod.java")));
        assertFalse(ctx.out.files().containsKey("src/main/resources/mcmod.info"));
        assertTrue(ctx.out.stubs().isEmpty());
        String block = file(ctx, "src/main/java/exampleaddon/gen/block/PressCasingBlockBase.java");
        assertTrue(block.contains("\"exampleaddon:press_casing\""), "asset namespace renamed: " + block);
        assertFalse(file(ctx, "src/main/java/exampleaddon/gen/ExampleaddonContent.java").contains("registerGuiHandler"));
    }

    /**
     * Screens and names that a ported (non-generated) logic class must not lose: the screen title falls back to the
     * block's name (centred on request), a logic block that does not extend its base gets the legacy name + an icon,
     * an effect's own status icon is drawn. Synthetic.
     */
    @Test
    void titlesLegacyNamesAndEffectIcons(@TempDir Path tmp) throws Exception {
        String prov = "\"provenance\": {\"file\": \"Mod.java\", \"line\": 1, \"version\": \"forge-1.19.2\"}";
        String ir = """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2", "modId": "demo", "tool": "test"}, "nodes": [
                 {"kind": "mod", "id": "demo", "name": "Demo", "version": "1.0", "mainClass": "org.demo.Demo", PROV},
                 {"kind": "block", "id": "demo:still", "logicClass": "org.demo.StillBlock", "blockEntity": "demo:still",
                  "textures": {"all": "demo:block/still"}, PROV},
                 {"kind": "blockEntity", "id": "demo:still", "logicClass": "org.demo.StillTile", "blocks": ["demo:still"], "menu": "demo:still", PROV},
                 {"kind": "menu", "id": "demo:still", "blockEntity": "demo:still", "size": {"width": 176, "height": 166},
                  "slots": [{"index": 0, "x": 8, "y": 8}], "playerInventory": {"x": 8, "y": 84},
                  "extensions": {"porter:titleAlign": "center"}, PROV},
                 {"kind": "block", "id": "demo:shell", "logicClass": "org.demo.ShellBlock", PROV},
                 {"kind": "effect", "id": "demo:dizzy", "category": "harmful", PROV},
                 {"kind": "effect", "id": "demo:plain", "category": "beneficial", PROV}
                ]}
                """.replace("PROV", prov);
        Path f = tmp.resolve("demo.ir.json");
        Files.writeString(f, ir);
        Path assets = tmp.resolve("assets-out");
        Files.createDirectories(assets.resolve("assets/demo/textures/mob_effect"));
        Files.write(assets.resolve("assets/demo/textures/mob_effect/dizzy.png"), new byte[] {1});
        GenOptions o = new GenOptions("1.7.10", "org.demo.gen", "demo", null, null, true, GenOptions.IntRange.parse("50-69"),
                GenOptions.IntRange.parse("25-31"), null, assets, "demox", GenOptions.LegacyNames.NS_CAMEL, true, Map.of());
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(f)), o).context();
        String gui = file(ctx, "src/main/java/org/demo/gen/client/StillGuiBase.java");
        assertTrue(gui.contains("String title = StatCollector.translateToLocal(\"tile.demo.still.name\")"), gui);
        assertTrue(gui.contains("(xSize - fontRendererObj.getStringWidth(title)) / 2, 6"), "centred title");
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("menu-title-from-block")));
        String base = file(ctx, "src/main/java/org/demo/gen/block/StillBlockBase.java");
        assertTrue(base.contains("public static void applyLegacy(Block block)"), base);
        assertTrue(base.contains("if (\"tile.null\".equals(block.getUnlocalizedName()))"), base);
        assertTrue(base.contains("block.setBlockName(\"demo.still\")"), base);
        assertTrue(base.contains("block.setBlockTextureName(ICON_NAMES[STATE_ICONS[DEFAULT_META][2]])"), base);
        String shell = file(ctx, "src/main/java/org/demo/gen/block/ShellBlockBase.java");
        assertTrue(shell.contains("block.setBlockName(\"demo.shell\")"), shell);
        assertFalse(shell.contains("setBlockTextureName"), "no textures known: no guessed (missing) icon");
        String content = file(ctx, "src/main/java/org/demo/gen/DemoContent.java");
        assertTrue(content.contains("blockStill = new StillBlock();\n        StillBlockBase.applyLegacy(blockStill);"), content);
        String dizzy = file(ctx, "src/main/java/org/demo/gen/potion/DizzyPotionBase.java");
        assertTrue(dizzy.contains("new ResourceLocation(\"demox\", \"textures/mob_effect/dizzy.png\")"), dizzy);
        assertTrue(dizzy.contains("public void renderInventoryEffect(int x, int y, PotionEffect effect, Minecraft mc)"), dizzy);
        assertTrue(dizzy.contains("Gui.func_146110_a(x + 6, y + 7, 0, 0, 18, 18, 18F, 18F)"), dizzy);
        assertFalse(file(ctx, "src/main/java/org/demo/gen/potion/PlainPotionBase.java").contains("renderInventoryEffect"),
                "no texture in the assets: vanilla behaviour");
    }

    /**
     * --source-constructors: logic classes ported whole are built with the source registry's constructor arguments
     * (no shared base, names set after construction); --gui-delegate: menus with a logic class open through the
     * delegate. Synthetic.
     */
    @Test
    void sourceConstructorsAndGuiDelegate(@TempDir Path tmp) throws Exception {
        String prov = "\"provenance\": {\"file\": \"Items.java\", \"line\": 1, \"version\": \"forge-1.19.2\"}";
        String ir = """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2", "modId": "demo", "tool": "test"}, "nodes": [
                 {"kind": "mod", "id": "demo", "name": "Demo", "version": "1.0", "mainClass": "org.demo.Demo", PROV},
                 {"kind": "item", "id": "demo:cup_tea", "logicClass": "org.demo.CupItem",
                  "extensions": {"porter-extract:newArgs": ["\\"cup_tea\\"", "org.demo.Drinks.TEA"], "porter-extract:holders": ["org.demo.Reg#CUP_TEA"]}, PROV},
                 {"kind": "item", "id": "demo:cup_milk", "logicClass": "org.demo.CupItem",
                  "extensions": {"porter-extract:newArgs": ["\\"cup_milk\\"", "org.demo.Drinks.MILK"]}, PROV},
                 {"kind": "block", "id": "demo:still", "logicClass": "org.demo.StillBlock", "blockEntity": "demo:still",
                  "extensions": {"porter-extract:holders": ["org.demo.Reg#STILL"]}, PROV},
                 {"kind": "blockEntity", "id": "demo:still", "logicClass": "org.demo.StillTile", "blocks": ["demo:still"], "menu": "demo:still", PROV},
                 {"kind": "menu", "id": "demo:still", "logicClass": "org.demo.StillContainer", "blockEntity": "demo:still",
                  "size": {"width": 176, "height": 166}, "slots": [{"index": 0, "x": 8, "y": 8}], "playerInventory": {"x": 8, "y": 84}, PROV}
                ]}
                """.replace("PROV", prov);
        Path f = tmp.resolve("demo.ir.json");
        Files.writeString(f, ir);
        GenOptions o = new GenOptions("1.7.10", "org.demo.gen", "demo", null, null, true, GenOptions.IntRange.parse("50-69"),
                GenOptions.IntRange.parse("25-31"), null, null, null, GenOptions.LegacyNames.NS_CAMEL, false, Map.of(), false,
                true, "org.demo.port.Guis");
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(f)), o).context();
        String content = file(ctx, "src/main/java/org/demo/gen/DemoContent.java");
        assertTrue(content.contains("itemCupTea = new CupItem(\"cup_tea\", org.demo.Drinks.TEA);"), content);
        assertTrue(content.contains("itemCupTea.setUnlocalizedName(\"demo.cupTea\");"), content);
        assertTrue(content.contains("itemCupMilk = new CupItem(\"cup_milk\", org.demo.Drinks.MILK);"), content);
        assertTrue(content.contains("blockStill = new StillBlock();"), content);
        assertFalse(ctx.out.files().keySet().stream().anyMatch(p -> p.contains("SharedItemBase")), "no shared base");
        String handler = file(ctx, "src/main/java/org/demo/gen/DemoGuiHandler.java");
        assertTrue(handler.contains("if (id == GUI_STILL || Guis.handles(id)) {\n            return Guis.server(id, player, world, x, y, z);"), handler);
        assertTrue(handler.contains("return Guis.client(id, player, world, x, y, z);"), handler);
        assertFalse(handler.contains("new StillContainer("), handler);
        String rules = file(ctx, "porter-rules/holders.rule.yaml");
        assertTrue(rules.contains("  field: \"org.demo.Reg#CUP_TEA\"\nreplace: \"DemoContent.itemCupTea\""), rules);
        assertTrue(rules.contains("  field: \"org.demo.Reg#STILL\"\nreplace: \"DemoContent.blockStill\""), rules);
        assertTrue(rules.contains("    before: \"Object o = Reg.STILL;\"\n    after: \"Object o = DemoContent.blockStill;\""), rules);
    }

    /** Shared logic classes (one class, several items) and --id-map host references. Synthetic. */
    @Test
    void sharedLogicClassAndIdMap(@TempDir Path tmp) throws Exception {
        String prov = "\"provenance\": {\"file\": \"Items.java\", \"line\": 1, \"version\": \"forge-1.19.2\"}";
        String ir = """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2", "modId": "demo", "tool": "test"}, "nodes": [
                 {"kind": "mod", "id": "demo", "name": "Demo", "version": "1.0", "mainClass": "org.demo.Demo", PROV},
                 {"kind": "item", "id": "demo:cup_tea", "logicClass": "org.demo.CupItem", PROV},
                 {"kind": "item", "id": "demo:cup_milk", "logicClass": "org.demo.CupItem", PROV},
                 {"kind": "recipe", "id": "demo:cup_tea", "recipeType": "shapeless",
                  "ingredients": [{"item": "demo:leaf"}, {"item": "demo:cup"}, {"item": "minecraft:sugar_cane"}],
                  "result": {"item": "demo:cup_tea"}, PROV}
                ]}
                """.replace("PROV", prov);
        Path f = tmp.resolve("demo.ir.json");
        Files.writeString(f, ir);
        Path ids = tmp.resolve("ids.yaml");
        Files.writeString(ids, "version: forge-1.7.10\nitems:\n  \"demo:cup\": \"Host:item.cup@3\"\n  \"minecraft:sugar_cane\": \"minecraft:reeds\"\n"
                + "  \"alias:x\": { id: \"Host:x\", count: 2 }\n");
        Map<String, String> idMap = dev.porter.generate.IdMaps.load(ids);
        assertEquals(Map.of("demo:cup", "Host:item.cup@3", "minecraft:sugar_cane", "minecraft:reeds"), idMap);
        GenOptions o = new GenOptions("1.7.10", "org.demo.gen", "demo", null, null, true, GenOptions.IntRange.parse("50-69"),
                GenOptions.IntRange.parse("25-31"), null, null, null, GenOptions.LegacyNames.NS_CAMEL, true, idMap);
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(f)), o).context();
        String content = file(ctx, "src/main/java/org/demo/gen/DemoContent.java");
        assertTrue(content.contains("new CupItem(\"cup_tea\", \"demo.cupTea\", \"demo:cup_tea\")"), content);
        assertTrue(content.contains("new CupItem(\"cup_milk\", \"demo.cupMilk\", \"demo:cup_milk\")"), content);
        assertTrue(ctx.out.stubs().containsKey("org.demo.CupItem"));
        String stub = file(ctx, "src/main/java/org/demo/CupItem.java");
        assertTrue(stub.contains("extends CupItemSharedItemBase"), stub);
        assertTrue(stub.contains("public CupItem(String registryName, String unlocalizedName, String textureName)"), stub);
        String recipes = file(ctx, "src/main/java/org/demo/gen/recipe/DemoRecipes.java");
        assertTrue(recipes.contains("stack(\"Host\", \"item.cup\", 1, 3)"), recipes);
        assertTrue(recipes.contains("stack(\"minecraft\", \"reeds\", 1, 0)"), recipes);
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("own-id-without-node") && d.subject().equals("demo:leaf")));
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("shared-logic-class")));
    }

    /** NEI integration: custom recipe types with fluids/machines/notes, multiblocks, hidden items (RFC 003). */
    @Test
    void neiIntegration() {
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(IR, NEI_IR)), options("50-59")).context();
        String n = J + "nei/";
        String data = file(ctx, n + "ExamplemodNeiData.java");
        assertTrue(data.contains("new NeiModel.Category(\"examplemod:pressing\", \"examplemod.nei.examplemod.pressing\", \"Pressing\")"), data);
        assertTrue(data.contains(".in(NeiModel.item(\"minecraft\", \"wheat\", 0, 4))"), data);
        assertTrue(data.contains(".in(NeiModel.any(1, NeiModel.item(\"minecraft\", \"apple\", 0, 1), NeiModel.ore(\"ingotCopper\", 1)))"), data);
        assertTrue(data.contains(".fluidIn(\"minecraft:water\", \"water\", 250)"), data);
        assertTrue(data.contains(".out(NeiModel.item(\"minecraft\", \"sugar\", 0, 1), 0.25)"), data);
        assertTrue(data.contains(".fluidOut(\"examplemod:berry_juice\", \"berry_juice\", 1000, 0.5)"), data);
        assertTrue(data.contains(".machine(NeiModel.item(\"examplemod\", \"copper_press\", 0, 1))"), data);
        assertTrue(data.contains(".time(24000)") && data.contains(".note(\"examplemod.note.cold\", \"Keep it cold\")")
                && data.contains(".note(null, \"Press slowly\")"), data);
        assertTrue(data.contains(".note(null, \"Ticks: 100\")"), "data shown when the IR has no notes: " + data);
        assertTrue(data.contains("new NeiModel.Structure(\"examplemod:big_press\"") && data.contains(".block('?', null)"), data);
        assertFalse(data.contains("\"air\""), "air = empty position, not a block to place");
        assertTrue(data.contains("list.add(NeiModel.item(\"examplemod\", \"debug_wand\", 32767, 1))"), data);
        assertFalse(data.contains("honey_bottle"), "recipe with a non-1.7.10 item is not shown");
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("nei-category-empty") && d.subject().equals("examplemod:cask_ageing")));
        assertFalse(ctx.out.files().containsKey(n + "ExamplemodCaskAgeingNeiHandler.java"));
        String handler = file(ctx, n + "ExamplemodPressingNeiHandler.java");
        assertTrue(handler.contains("extends NeiRecipeHandler") && handler.contains("super(ExamplemodNeiData.category(\"examplemod:pressing\"))"), handler);
        String config = file(ctx, n + "NEIExamplemodConfig.java");
        assertTrue(config.contains("implements IConfigureNEI"), config);
        assertTrue(config.contains("API.registerRecipeHandler(handler0)") && config.contains("API.registerUsageHandler(structures)")
                && config.contains("API.hideItem(s)"), config);
        assertTrue(file(ctx, n + "ExamplemodStructureNeiHandler.java").contains("super(ExamplemodNeiData.structures())"));
        assertTrue(file(ctx, n + "NeiRecipeHandler.java").contains("package com.example.examplemod.gen.nei;"));
        assertTrue(file(ctx, n + "NeiStructureHandler.java").contains("extends TemplateRecipeHandler"));
        // server safety: NEI types only in the NEI pages and the config; the model/data load without NEI
        for (var e : ctx.out.files().entrySet()) {
            String text = new String(e.getValue(), StandardCharsets.UTF_8);
            boolean neiSide = e.getKey().endsWith("Handler.java") || e.getKey().endsWith("NEIExamplemodConfig.java");
            assertTrue(neiSide || !text.contains("codechicken"), "NEI type outside the NEI classes: " + e.getKey());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nei = (Map<String, Object>) ((Map<String, Object>) ctx.manifest.get("forge-1.7.10")).get("nei");
        assertEquals("com.example.examplemod.gen.nei.NEIExamplemodConfig", nei.get("config"));
        assertEquals(List.of("examplemod:big_press"), nei.get("structures"));
    }

    @Test
    void neiCanBeTurnedOff() {
        GenOptions o = options("50-59");
        GenOptions off = new GenOptions(o.version(), o.basePackage(), o.modId(), o.modName(), o.modVersion(), o.embed(), o.guiIds(),
                o.potionIds(), o.channel(), o.assetsDir(), o.assetNamespace(), o.legacyNames(), o.stubs(), o.idMap(), false);
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(IR, NEI_IR)), off).context();
        assertFalse(ctx.out.files().keySet().stream().anyMatch(p -> p.contains("/nei/")));
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("nei-off")));
    }

    @Test
    void sharedBlockLogicClass(@TempDir Path tmp) throws Exception {
        // before: two blocks with one logic class (different 1.19.2 constructor arguments) made generate fail with
        // "two different contents" for the stub; after: each block is registered with its generated base, no stub
        String prov = "\"provenance\": {\"file\": \"Blocks.java\", \"line\": 1, \"version\": \"forge-1.19.2\"}";
        String ir = """
                {"schemaVersion": 1, "source": {"version": "forge-1.19.2", "modId": "demo", "tool": "test"}, "nodes": [
                 {"kind": "mod", "id": "demo", "name": "Demo", "version": "1.0", "mainClass": "org.demo.Demo", PROV},
                 {"kind": "block", "id": "demo:big_lamp", "logicClass": "org.demo.LampBlock", PROV},
                 {"kind": "block", "id": "demo:small_lamp", "logicClass": "org.demo.LampBlock", PROV},
                 {"kind": "block", "id": "demo:press", "logicClass": "org.demo.PressBlock", PROV},
                 {"kind": "item", "id": "demo:big_lamp", "placesBlock": "demo:big_lamp", "logicClass": "org.demo.LampItem", PROV},
                 {"kind": "item", "id": "demo:small_lamp", "placesBlock": "demo:small_lamp", "logicClass": "org.demo.LampItem", PROV}
                ]}
                """.replace("PROV", prov);
        Path f = tmp.resolve("demo.ir.json");
        Files.writeString(f, ir);
        GenOptions o = new GenOptions("1.7.10", "org.demo.gen", "demo", null, null, false, GenOptions.IntRange.parse("50-69"),
                GenOptions.IntRange.parse("25-31"), null, null, null, GenOptions.LegacyNames.PATH, true);
        GenContext ctx = Generate.run(new Forge1710Generator(), IrIndex.load(List.of(f)), o).context();
        String content = file(ctx, "src/main/java/org/demo/gen/DemoContent.java");
        assertTrue(content.contains("new BigLampBlockBase()") && content.contains("new SmallLampBlockBase()"), content);
        assertTrue(content.contains("new PressBlock()"), "unshared logic class still instantiated: " + content);
        assertFalse(ctx.out.stubs().containsKey("org.demo.LampBlock"));
        assertTrue(ctx.out.stubs().containsKey("org.demo.PressBlock"));
        assertTrue(ctx.decisions().stream().anyMatch(d -> d.code().equals("shared-logic-class") && d.subject().equals("demo:big_lamp")));
        // one block-item class for several block items: one stub (before: "two different contents" as well)
        assertTrue(file(ctx, "src/main/java/org/demo/LampItem.java").contains("demo:big_lamp, demo:small_lamp"));
        assertTrue(content.contains("registerBlock(blockSmallLamp, LampItem.class, \"small_lamp\")"), content);
    }
}
