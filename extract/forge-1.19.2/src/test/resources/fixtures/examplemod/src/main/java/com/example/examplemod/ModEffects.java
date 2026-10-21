package com.example.examplemod;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ExampleMod.MODID);

    public static final RegistryObject<MobEffect> DIZZY = MOB_EFFECTS.register("dizzy", DizzyEffect::new);

    public static class DizzyEffect extends MobEffect {
        public DizzyEffect() {
            super(MobEffectCategory.HARMFUL, 0x4422AA);
        }
    }
}
