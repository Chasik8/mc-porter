package com.example.examplemod;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);

    public static final RegistryObject<Item> PRESS = ITEMS.register("press",
            () -> new BlockItem(ModBlocks.PRESS.get(), new Item.Properties().tab(CreativeModeTab.TAB_MISC)));
    public static final RegistryObject<Item> TIN_BLOCK = ITEMS.register("tin_block",
            () -> new BlockItem(ModBlocks.TIN_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> TIN_INGOT = ITEMS.register("tin_ingot",
            () -> new Item(new Item.Properties().stacksTo(32).rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> HAMMER = ITEMS.register("hammer",
            () -> new HammerItem(new Item.Properties().durability(250)));
    public static final RegistryObject<Item> JUICE = ITEMS.register("juice",
            () -> new Item(new Item.Properties().craftRemainder(Items.GLASS_BOTTLE).food(new FoodProperties.Builder()
                    .nutrition(4).saturationMod(0.3F).alwaysEat()
                    .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 100, 1), 0.5F).build())));
    public static final RegistryObject<Item> OIL_BUCKET = ITEMS.register("oil_bucket",
            () -> new BucketItem(ModFluids.OIL, new Item.Properties().stacksTo(1)));

    static {
        for (String colour : Config.colours()) {
            ITEMS.register(colour + "_dye_pack", () -> new Item(new Item.Properties()));
        }
    }
}
