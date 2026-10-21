package dev.porter.extract.forge1192;

import dev.porter.core.ir.IrNode;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Synthetic fixtures (invented mod "examplemod"; vanilla facts only where a test needs them). */
final class Fixtures {
    private Fixtures() {
    }

    static Path path(String name) {
        try {
            java.net.URL url = Fixtures.class.getResource("/fixtures/" + name);
            return url == null ? Path.of("build", "missing-fixture-" + name) : Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A tiny vanilla index with the handful of vanilla facts the fixtures reference. */
    static VanillaIndex vanilla() {
        return new VanillaIndex(VanillaIndex.FORMAT, "1.19.2", "test", Map.of("m_60913_", "strength"),
                Map.of("net.minecraft.world.item.Items#GLASS_BOTTLE", "minecraft:glass_bottle",
                        "net.minecraft.world.item.Items#STICK", "minecraft:stick",
                        "net.minecraft.world.effect.MobEffects#REGENERATION", "minecraft:regeneration",
                        "net.minecraft.world.level.block.Blocks#STONE", "minecraft:stone",
                        "net.minecraftforge.common.Tags$Items#DUSTS_REDSTONE", "forge:dusts/redstone"),
                Map.of(),
                Map.of("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageWidth", 176,
                        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen#imageHeight", 166));
    }

    private static ExtractContext example;

    static synchronized ExtractContext example() {
        if (example == null) {
            example = Extractor.analyse(new Extractor.Options(path("examplemod"), null, null, vanilla(), List.of(), true,
                    List.of()));
        }
        return example;
    }

    @SuppressWarnings("unchecked")
    static <T extends IrNode> T node(ExtractContext ctx, String kind, String id) {
        for (IrNode n : ctx.sink.all()) {
            if (n.kind().equals(kind) && id.equals(n.id())) {
                return (T) n;
            }
        }
        throw new AssertionError("no " + kind + " " + id + " in " + ids(ctx, kind));
    }

    static List<String> ids(ExtractContext ctx, String kind) {
        return ctx.sink.all().stream().filter(n -> n.kind().equals(kind)).map(n -> String.valueOf(n.id())).toList();
    }

    static List<IrNode.Unknown> unknowns(ExtractContext ctx, String concept) {
        return ctx.sink.byConcept().get(concept).stream().filter(n -> n instanceof IrNode.Unknown)
                .map(n -> (IrNode.Unknown) n).toList();
    }
}
