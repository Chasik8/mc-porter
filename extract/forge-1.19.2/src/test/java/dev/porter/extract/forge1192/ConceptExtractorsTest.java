package dev.porter.extract.forge1192;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.ir.IrNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One test per concept extractor, on the synthetic examplemod fixture (standard Forge 1.19.2 patterns). */
class ConceptExtractorsTest {
    private final ExtractContext ctx = Fixtures.example();

    @Test
    void mod() {
        IrNode.Mod m = Fixtures.node(ctx, "mod", "examplemod");
        assertEquals("Example Mod", m.name());
        assertEquals("1.0.0", m.version());
        assertEquals(List.of("Alice", "Bob"), m.authors());
        assertEquals("MIT", m.license());
        assertEquals("com.example.examplemod.ExampleMod", m.mainClass());
        assertEquals("forge", m.dependencies().get(0).modId());
        assertEquals("none", m.dependencies().get(0).ordering());
    }

    @Test
    void lang() {
        IrNode.Lang en = Fixtures.node(ctx, "lang", "examplemod:en_us");
        assertEquals("Press", en.entries().get("block.examplemod.press"));
        assertEquals(3, en.entries().size());
        IrNode.Lang ru = Fixtures.node(ctx, "lang", "examplemod:ru_ru");
        assertEquals("ru_ru", ru.locale());
        assertEquals(1, Fixtures.unknowns(ctx, "lang").size(), "notes.json is not a locale file");
    }

    @Test
    void item() {
        IrNode.Item ingot = Fixtures.node(ctx, "item", "examplemod:tin_ingot");
        assertEquals(32, ingot.maxStackSize());
        assertEquals("uncommon", ingot.rarity());
        assertEquals("item.examplemod.tin_ingot", ingot.translationKey());
        assertNull(ingot.logicClass());
        assertEquals("examplemod.tinIngot", ingot.extensions().get("porter:legacyName").asString());

        IrNode.Item press = Fixtures.node(ctx, "item", "examplemod:press");
        assertEquals("examplemod:press", press.placesBlock());
        assertEquals("CreativeModeTab.TAB_MISC", press.creativeTab());
        assertEquals("block.examplemod.press", press.translationKey());

        IrNode.Item hammer = Fixtures.node(ctx, "item", "examplemod:hammer");
        assertEquals(250, hammer.maxDamage());
        assertEquals(1, hammer.maxStackSize());
        assertEquals("item.examplemod.forge_hammer", hammer.translationKey());
        assertEquals("com.example.examplemod.HammerItem", hammer.logicClass());

        IrNode.Item juice = Fixtures.node(ctx, "item", "examplemod:juice");
        assertEquals("minecraft:glass_bottle", juice.containerItem());
        assertEquals(4, juice.food().nutrition());
        assertEquals(0.3, juice.food().saturation(), 1e-9);
        assertEquals(Boolean.TRUE, juice.food().alwaysEdible());
        IrNode.Item.FoodEffect fe = juice.food().effects().get(0);
        assertEquals("minecraft:regeneration", fe.effect());
        assertEquals(100, fe.duration());
        assertEquals(1, fe.amplifier());
        assertEquals(0.5, fe.probability(), 1e-9);

        // registrations in a loop over config values are never guessed
        List<IrNode.Unknown> unk = Fixtures.unknowns(ctx, "item");
        assertEquals(1, unk.size());
        assertTrue(unk.get(0).reason().contains("never executed"), unk.get(0).reason());
    }

    @Test
    void block() {
        IrNode.Block press = Fixtures.node(ctx, "block", "examplemod:press");
        assertEquals("metal", press.material());
        assertEquals(3.5, press.hardness());
        assertEquals(6.0, press.resistance());
        assertEquals("metal", press.soundType());
        assertEquals(Boolean.TRUE, press.requiresTool());
        assertEquals(7, press.lightLevel());
        assertEquals("com.example.examplemod.PressBlock", press.logicClass());
        assertEquals("block.examplemod.press", press.translationKey());
        assertEquals("examplemod:press", press.blockEntity());
        assertEquals("examplemod:press", press.item());
        Map<String, IrNode.Block.StateProperty> props = new java.util.LinkedHashMap<>();
        press.stateProperties().forEach(p -> props.put(p.name(), p));
        assertEquals(List.of("north", "east", "south", "west"), props.get("facing").values());
        assertEquals("north", props.get("facing").defaultValue());
        assertEquals("direction", props.get("facing").type());
        assertEquals("false", props.get("lit").defaultValue());
        assertEquals(List.of("0", "1", "2", "3"), props.get("stage").values());

        IrNode.Block tin = Fixtures.node(ctx, "block", "examplemod:tin_block");
        assertEquals(2.0, tin.hardness());
        assertEquals(2.0, tin.resistance());
        assertEquals("stone", tin.material());
        assertNull(tin.logicClass());
        assertTrue(Fixtures.unknowns(ctx, "block").isEmpty());
    }

    @Test
    void blockEntity() {
        IrNode.BlockEntity be = Fixtures.node(ctx, "blockEntity", "examplemod:press");
        assertEquals(List.of("examplemod:press"), be.blocks());
        assertEquals("com.example.examplemod.PressBlockEntity", be.logicClass());
        assertEquals("server", be.ticking());
        Map<String, String> nbt = new java.util.LinkedHashMap<>();
        be.nbt().forEach(k -> nbt.put(k.key(), k.type()));
        assertEquals(Map.of("Progress", "int", "Owner", "string", "Locked", "boolean", "Items", "itemStacks"), nbt);
    }

    @Test
    void recipe() {
        IrNode.Recipe press = Fixtures.node(ctx, "recipe", "examplemod:press");
        assertEquals("shaped", press.recipeType());
        assertEquals(List.of("III", "I I", "SSS"), press.pattern());
        assertEquals("forge:ingots/tin", press.key().get("I").tag());
        assertEquals("examplemod:press", press.result().item());
        IrNode.Recipe smelt = Fixtures.node(ctx, "recipe", "examplemod:tin_ingot");
        assertEquals("smelting", smelt.recipeType());
        assertEquals(100, smelt.cookingTime());
        assertEquals(0.5, smelt.experience());
        IrNode.Recipe custom = Fixtures.node(ctx, "recipe", "examplemod:juice_pressing");
        assertEquals("custom", custom.recipeType());
        assertEquals("examplemod:pressing", custom.customType());
        assertEquals(400, custom.data().get("energy").asInt());
        assertEquals(2, custom.result().count());
        assertEquals(1, Fixtures.unknowns(ctx, "recipe").size(), "forge:nbt ingredient is not representable");
    }

    @Test
    void menu() {
        IrNode.Menu m = Fixtures.node(ctx, "menu", "examplemod:press");
        assertEquals(3, m.slots().size());
        assertEquals(new IrNode.Menu.Slot(0, 56, 17, null, null), m.slots().get(0));
        assertEquals(new IrNode.Menu.Slot(2, 116, 35, "output", null), m.slots().get(2));
        assertEquals(new IrNode.Menu.PlayerInventory(8, 98, null), m.playerInventory());
        assertEquals(new IrNode.Menu.Size(176, 180), m.size());
        assertEquals("examplemod:textures/gui/press.png", m.texture().path());
        assertEquals("com.example.examplemod.PressScreen", m.screenClass());
        assertEquals("com.example.examplemod.PressMenu", m.logicClass());
        assertEquals(2, m.dataSlots().size());
    }

    @Test
    void packet() {
        IrNode.Packet set = Fixtures.node(ctx, "packet", "examplemod:set_mode_packet");
        assertEquals("toServer", set.direction());
        assertEquals("examplemod:main", set.channel());
        assertEquals(0, set.discriminator());
        assertEquals(List.of(new IrNode.Packet.Field("pos", "blockPos", null, null, null),
                new IrNode.Packet.Field("mode", "varInt", null, null, null),
                new IrNode.Packet.Field("label", "string", null, null, 32)), set.fields());
        assertEquals("com.example.examplemod.SetModePacket", set.handlerClass());
        IrNode.Packet sync = Fixtures.node(ctx, "packet", "examplemod:sync_packet");
        assertEquals("toClient", sync.direction());
        assertEquals(1, sync.discriminator());
        assertEquals(List.of("long", "boolean"), sync.fields().stream().map(IrNode.Packet.Field::type).toList());
    }

    @Test
    void fluid() {
        IrNode.Fluid oil = Fixtures.node(ctx, "fluid", "examplemod:oil");
        assertEquals("fluid.examplemod.oil", oil.translationKey());
        assertEquals(900, oil.density());
        assertEquals(2000, oil.viscosity());
        assertEquals(320, oil.temperature());
        assertEquals(Boolean.FALSE, oil.gaseous());
        assertEquals("examplemod:oil_bucket", oil.bucket());
        assertFalse(Fixtures.ids(ctx, "fluid").contains("examplemod:flowing_oil"), "flowing variant is folded");
    }

    @Test
    void sound() {
        IrNode.Sound s = Fixtures.node(ctx, "sound", "examplemod:press_run");
        assertEquals("block", s.category());
        assertEquals(2, s.sounds().size());
        assertEquals(0.5, s.sounds().get(1).volume());
        List<IrNode.Unknown> unk = Fixtures.unknowns(ctx, "sound");
        assertEquals(1, unk.size());
        assertEquals("examplemod:orphan", unk.get(0).id());
    }

    @Test
    void effect() {
        IrNode.Effect e = Fixtures.node(ctx, "effect", "examplemod:dizzy");
        assertEquals("harmful", e.category());
        assertEquals("#4422AA", e.color());
        assertEquals("effect.examplemod.dizzy", e.translationKey());
        assertEquals(Boolean.FALSE, e.instant());
        assertEquals("com.example.examplemod.ModEffects$DizzyEffect", e.logicClass());
    }

    @Test
    void tag() {
        IrNode.Tag t = Fixtures.node(ctx, "tag", "forge:ingots/tin");
        assertEquals("item", t.registry());
        assertEquals(List.of("ingotTin"), t.oreDict());
        assertEquals("examplemod:tin_ingot", t.values().get(0).id());
        assertEquals(Boolean.FALSE, t.values().get(1).required());
        assertEquals(Boolean.FALSE, t.replace());
    }

    @Test
    void multiblock() {
        IrNode.Multiblock mb = Fixtures.node(ctx, "multiblock", "examplemod:press_frame");
        assertEquals(List.of(3, 2, 3), mb.size());
        assertEquals(List.of(List.of("BBB", "BAB", "BBB"), List.of("BBB", "BCB", "BBB")), mb.layers());
        assertEquals("examplemod:press", mb.palette().get("A").block());
        assertEquals("minecraft:air", mb.palette().get("C").block());
        assertEquals("examplemod:press", mb.controller().block());
        assertEquals(List.of(1, 0, 1), mb.controller().pos());
    }
}
