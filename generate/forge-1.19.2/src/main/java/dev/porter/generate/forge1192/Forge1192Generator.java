package dev.porter.generate.forge1192;

import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.TargetGenerator;

/**
 * IR → Forge 1.19.2 (43.x, official mappings) sources and resources, used for the round-trip check (extract →
 * generate in the source version → compile/compare).
 *
 * <p>Registration through DeferredRegister ({@code <Mod>Registries}); blocks/items/block entities/menus/screens/effects
 * as bases that the logic classes (stubs or rewriter output) extend; SimpleChannel messages; data files (recipes, tags,
 * lang, sounds, blockstates/models, mods.toml) rebuilt from the IR.
 */
public final class Forge1192Generator implements TargetGenerator {
    @Override
    public String version() {
        return "1.19.2";
    }

    @Override
    public String platform() {
        return "forge-1.19.2";
    }

    @Override
    public void generate(GenContext ctx) {
        Names19 n = new Names19(ctx);
        new Code19(n).run();
        new Data19(n).run();
        for (IrNode.Block b : ctx.ir.all(IrNode.Block.class)) {
            if (b.lootTable() != null) {
                ctx.skip("loot-table", b.id(), "loot table " + b.lootTable() + " is referenced only; copy it from the source mod");
            }
        }
        ctx.manifest.put("entryPoints", java.util.List.of(n.registries.canonicalName(),
                ctx.options.embed() ? "(embedded: call " + n.registries.simpleName() + ".register(modBus))" : n.modClass.canonicalName()));
    }
}
