package dev.porter.generate.forge1710;

import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.TargetGenerator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IR → Forge 1.7.10 (10.13.4.1614, MCP stable_12) sources.
 *
 * <ul>
 *   <li>registration via GameRegistry in preInit/init ({@code <Mod>Content}); {@code @Mod} class unless {@code --embed};</li>
 *   <li>blocks with {@code getIcon(side, meta)} from {@code assets:icons}/{@code assets:stateIcons}; block-state
 *       properties in metadata, spilled into TileEntity fields beyond 16 states ({@link MetaLayout});</li>
 *   <li>{@code Container} + {@code GuiContainer} + {@code IGuiHandler} from menus, GUI ids from {@code --gui-ids};</li>
 *   <li>{@code SimpleNetworkWrapper} + {@code IMessage} from packets; recipes via GameRegistry/OreDictionary
 *       (tags → OreDictionary); {@code Fluid} + FluidRegistry; {@code Potion} with ids from {@code --potion-ids};</li>
 *   <li>multiblocks as data definitions (addon {@code MultiblockDef} style);</li>
 *   <li>NotEnoughItems pages for custom recipe types, fluids and multiblocks, hidden objects ({@link NeiGen}).</li>
 * </ul>
 * Logic classes named by the IR get stubs extending the generated bases (see {@code porter-generate.json} "stubs").
 */
public final class Forge1710Generator implements TargetGenerator {
    @Override
    public String version() {
        return "1.7.10";
    }

    @Override
    public String platform() {
        return "forge-1.7.10";
    }

    @Override
    public void generate(GenContext ctx) {
        for (String v : ctx.ir.sourceVersions()) {
            if (!"forge-1.19.2".equals(v) && !"forge-1.7.10".equals(v)) {
                ctx.warn("source-version", v, "IR from " + v + "; generator rules are written for forge-1.19.2 input");
            }
        }
        Plan plan = new Plan(ctx);
        BlockGen blocks = new BlockGen(plan);
        FluidGen fluids = new FluidGen(plan);
        blocks.run();
        new TileGen(plan).run();
        new ItemGen(plan).run();
        fluids.run();
        new PotionGen(plan).run();
        new MenuGen(plan).run();
        if (!ctx.ir.all(IrNode.Packet.class).isEmpty()) {
            new NetworkGen(plan).run();
        }
        RecipeGen recipes = new RecipeGen(plan);
        recipes.run();
        new MultiblockGen(plan).run();
        NeiGen nei = new NeiGen(plan);
        if (ctx.options.nei()) {
            nei.run();
        } else {
            ctx.info("nei-off", ctx.options.modId(), "--no-nei: no NotEnoughItems integration");
        }
        new ContentGen(plan, blocks, fluids).run();
        new HolderRules(plan).run();
        for (IrNode.Lang l : ctx.ir.all(IrNode.Lang.class)) {
            ctx.info("lang-by-assets", l.id(), "lang files come from `porter assets` (--assets); IR lang node not rendered here");
        }
        for (IrNode.Sound s : ctx.ir.all(IrNode.Sound.class)) {
            ctx.info("sound-by-assets", s.id(), "sounds.json comes from `porter assets`; play by name " + s.id());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guiIds", plan.guiIds);
        m.put("potionIds", plan.potionIds);
        m.put("packetDiscriminators", plan.discriminators);
        m.put("channel", ctx.options.channel());
        m.put("blocks", plan.blockManifest);
        m.put("menus", plan.menuManifest);
        m.put("oreDictionary", plan.oreDictManifest);
        m.put("recipes", recipes.generated);
        m.put("nei", nei.manifest.isEmpty() ? null : nei.manifest);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("content", plan.content.canonicalName());
        entry.put("guiHandler", plan.guiHandler.canonicalName());
        entry.put("network", ctx.ir.all(IrNode.Packet.class).isEmpty() ? null : plan.network.canonicalName());
        entry.put("mod", ctx.options.embed() ? null : plan.modClass.canonicalName());
        m.put("entryPoints", entry);
        ctx.manifest.put("forge-1.7.10", m);
    }
}
