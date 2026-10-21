package dev.porter.generate.forge1192;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.generate.GenContext;
import dev.porter.generate.GenOptions;
import dev.porter.generate.Generate;
import dev.porter.generate.IrIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Forge1192GeneratorTest {
    static final Path IR = Path.of(System.getProperty("porter.repoRoot"), "generate", "testdata", "synthetic.ir.json");

    static GenContext run() {
        GenOptions o = new GenOptions("1.19.2", null, null, null, null, false, GenOptions.IntRange.parse("0-99"),
                GenOptions.IntRange.parse("24-31"), null, null, null, GenOptions.LegacyNames.PATH, true);
        return Generate.run(new Forge1192Generator(), IrIndex.load(List.of(IR)), o).context();
    }

    static String file(GenContext ctx, String path) {
        byte[] b = ctx.out.files().get(path);
        assertTrue(b != null, "missing " + path + " in " + ctx.out.files().keySet());
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void generatesSourcesAndData() {
        GenContext ctx = run();
        String j = "src/main/java/com/example/examplemod/gen/";
        String reg = file(ctx, j + "ExamplemodRegistries.java");
        assertTrue(reg.contains("BLOCKS.register(\"copper_press\", CopperPressBlock::new)"), reg);
        assertTrue(reg.contains("IForgeMenuType.create(CopperPressMenu::new)"), reg);
        assertTrue(reg.contains("new ForgeFlowingFluid.Source("), reg);
        String block = file(ctx, j + "block/SignalLampBlockBase.java");
        assertTrue(block.contains("EnumProperty<Color> COLOR") || block.contains("EnumProperty<SignalLampBlockBase.Color> COLOR"), block);
        String recipe = file(ctx, "src/main/resources/data/examplemod/recipes/copper_press.json");
        assertTrue(recipe.contains("\"minecraft:crafting_shaped\"") && recipe.contains("\"forge:ingots/copper\""), recipe);
        assertTrue(file(ctx, "src/main/resources/data/minecraft/tags/blocks/mineable/pickaxe.json").contains("examplemod:copper_press"));
        assertTrue(file(ctx, "src/main/resources/assets/examplemod/lang/en_us.json").contains("Copper Press"));
        assertTrue(file(ctx, "src/main/resources/META-INF/mods.toml").contains("modId=\"examplemod\""));
        assertTrue(file(ctx, "src/main/resources/assets/examplemod/blockstates/copper_press.json").contains("\"y\" : 90"));
        assertTrue(ctx.out.stubs().containsKey("com.example.examplemod.blockentity.CopperPressBlockEntity"));
    }

    @Test
    void deterministic() {
        GenContext a = run();
        GenContext b = run();
        assertEquals(a.out.files().keySet(), b.out.files().keySet());
        a.out.files().forEach((k, v) -> assertArrayEquals(v, b.out.files().get(k), k));
    }
}
