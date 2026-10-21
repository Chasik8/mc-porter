package com.example.examplemod;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ExampleMod.MODID);

    public static final RegistryObject<MenuType<PressMenu>> PRESS = MENU_TYPES.register("press",
            () -> IForgeMenuType.create(PressMenu::new));
}
