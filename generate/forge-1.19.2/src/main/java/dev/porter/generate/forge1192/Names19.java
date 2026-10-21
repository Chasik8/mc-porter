package dev.porter.generate.forge1192;

import com.squareup.javapoet.ClassName;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.Set;

/** Class and field names of one 1.19.2 run. */
final class Names19 {
    static final Set<String> MATERIALS = Set.of("AIR", "STRUCTURAL_AIR", "PORTAL", "CLOTH_DECORATION", "PLANT", "WATER_PLANT",
            "REPLACEABLE_PLANT", "REPLACEABLE_FIREPROOF_PLANT", "REPLACEABLE_WATER_PLANT", "WATER", "BUBBLE_COLUMN", "LAVA",
            "TOP_SNOW", "FIRE", "DECORATION", "WEB", "SCULK", "BUILDABLE_GLASS", "CLAY", "DIRT", "GRASS", "ICE_SOLID", "SAND",
            "SPONGE", "SHULKER_SHELL", "WOOD", "NETHER_WOOD", "BAMBOO_SAPLING", "BAMBOO", "WOOL", "EXPLOSIVE", "LEAVES", "GLASS",
            "ICE", "CACTUS", "STONE", "METAL", "SNOW", "HEAVY_METAL", "BARRIER", "PISTON", "MOSS", "VEGETABLE", "EGG", "CAKE",
            "AMETHYST", "POWDER_SNOW", "FROGSPAWN", "FROGLIGHT");
    static final Set<String> SOUNDS = Set.of("WOOD", "GRAVEL", "GRASS", "LILY_PAD", "STONE", "METAL", "GLASS", "WOOL", "SAND",
            "SNOW", "POWDER_SNOW", "LADDER", "ANVIL", "SLIME_BLOCK", "HONEY_BLOCK", "WET_GRASS", "CORAL_BLOCK", "BAMBOO",
            "BAMBOO_SAPLING", "SCAFFOLDING", "SWEET_BERRY_BUSH", "CROP", "HARD_CROP", "VINE", "NETHER_WART", "LANTERN", "STEM",
            "NYLIUM", "FUNGUS", "ROOTS", "SHROOMLIGHT", "WEEPING_VINES", "TWISTING_VINES", "SOUL_SAND", "SOUL_SOIL", "BASALT",
            "WART_BLOCK", "NETHERRACK", "NETHER_BRICKS", "NETHER_SPROUTS", "NETHER_ORE", "BONE_BLOCK", "NETHERITE_BLOCK",
            "ANCIENT_DEBRIS", "LODESTONE", "CHAIN", "NETHER_GOLD_ORE", "GILDED_BLACKSTONE", "CANDLE", "AMETHYST",
            "AMETHYST_CLUSTER", "SMALL_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD", "LARGE_AMETHYST_BUD", "TUFF", "CALCITE",
            "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE", "COPPER", "CAVE_VINES", "SPORE_BLOSSOM", "AZALEA", "FLOWERING_AZALEA",
            "MOSS_CARPET", "MOSS", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "ROOTED_DIRT", "HANGING_ROOTS", "AZALEA_LEAVES",
            "SCULK_SENSOR", "SCULK_CATALYST", "SCULK", "SCULK_VEIN", "SCULK_SHRIEKER", "GLOW_LICHEN", "DEEPSLATE",
            "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "POLISHED_DEEPSLATE", "FROGLIGHT", "FROGSPAWN", "MANGROVE_ROOTS",
            "MUDDY_MANGROVE_ROOTS", "MUD", "MUD_BRICKS", "PACKED_MUD");

    final GenContext ctx;
    final String pkg;
    final String modPascal;
    final ClassName registries;
    final ClassName modClass;
    final ClassName network;
    final ClassName client;
    final ClassName multiblocks;
    final ClassName multiblockDef;

    Names19(GenContext ctx) {
        this.ctx = ctx;
        this.pkg = ctx.options.basePackage();
        this.modPascal = Names.pascal(ctx.options.modId());
        registries = ClassName.get(pkg, modPascal + "Registries");
        modClass = ClassName.get(pkg, modPascal + "Mod");
        network = ClassName.get(pkg + ".network", modPascal + "Network");
        client = ClassName.get(pkg + ".client", modPascal + "Client");
        multiblocks = ClassName.get(pkg + ".multiblock", modPascal + "Multiblocks");
        multiblockDef = ClassName.get(pkg + ".multiblock", "MultiblockDef");
    }

    String pascal(String id) {
        return Names.pascal(Names.path(id));
    }

    String constant(String id) {
        return Names.constant(Names.path(id));
    }

    /** Registry path (the IR id path; 1.19.2 allows '/'). */
    String path(String id) {
        return Names.path(id);
    }

    static ClassName cls(String fqn) {
        String p = Names.packageOf(fqn);
        String simple = fqn.substring(p.isEmpty() ? 0 : p.length() + 1);
        String[] parts = simple.split("\\$");
        String[] rest = new String[parts.length - 1];
        System.arraycopy(parts, 1, rest, 0, rest.length);
        return ClassName.get(p, parts[0], rest);
    }

    ClassName blockBase(String id) {
        return ClassName.get(pkg + ".block", pascal(id) + "BlockBase");
    }

    ClassName blockClass(IrNode.Block b) {
        return b.logicClass() != null ? cls(b.logicClass()) : blockBase(b.id());
    }

    ClassName beBase(String id) {
        return ClassName.get(pkg + ".blockentity", pascal(id) + "BlockEntityBase");
    }

    ClassName beClass(IrNode.BlockEntity be) {
        return cls(be.logicClass());
    }

    ClassName itemBase(String id) {
        return ClassName.get(pkg + ".item", pascal(id) + "ItemBase");
    }

    ClassName itemClass(IrNode.Item i) {
        return i.logicClass() != null ? cls(i.logicClass()) : itemBase(i.id());
    }

    ClassName menuBase(String id) {
        return ClassName.get(pkg + ".menu", pascal(id) + "MenuBase");
    }

    ClassName menuClass(IrNode.Menu m) {
        return m.logicClass() != null ? cls(m.logicClass()) : menuBase(m.id());
    }

    ClassName screenBase(String id) {
        return ClassName.get(pkg + ".client", pascal(id) + "ScreenBase");
    }

    ClassName screenClass(IrNode.Menu m) {
        return m.screenClass() != null ? cls(m.screenClass()) : screenBase(m.id());
    }

    ClassName effectBase(String id) {
        return ClassName.get(pkg + ".effect", pascal(id) + "EffectBase");
    }

    ClassName effectClass(IrNode.Effect e) {
        return e.logicClass() != null ? cls(e.logicClass()) : effectBase(e.id());
    }

    ClassName fluidType(String id) {
        return ClassName.get(pkg + ".fluid", pascal(id) + "FluidType");
    }

    ClassName message(String id) {
        return ClassName.get(pkg + ".network", pascal(id) + "Message");
    }

    ClassName handler(IrNode.Packet p) {
        return p.handlerClass() != null ? cls(p.handlerClass()) : ClassName.get(pkg + ".network", pascal(p.id()) + "Handler");
    }

    // registry object fields
    String block(String id) {
        return constant(id);
    }

    String item(String id) {
        return constant(id) + "_ITEM";
    }

    String be(String id) {
        return constant(id) + "_BE";
    }

    String menu(String id) {
        return constant(id) + "_MENU";
    }

    String effect(String id) {
        return constant(id) + "_EFFECT";
    }

    String fluid(String id) {
        return constant(id) + "_FLUID";
    }

    String flowing(String id) {
        return constant(id) + "_FLOWING";
    }

    String fluidTypeField(String id) {
        return constant(id) + "_FLUID_TYPE";
    }

    String sound(String id) {
        return constant(id) + "_SOUND";
    }
}
