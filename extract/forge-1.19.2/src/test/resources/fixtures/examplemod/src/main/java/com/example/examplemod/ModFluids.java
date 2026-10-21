package com.example.examplemod;

import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, ExampleMod.MODID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, ExampleMod.MODID);

    public static final RegistryObject<FluidType> OIL_TYPE = FLUID_TYPES.register("oil", () -> new FluidType(
            FluidType.Properties.create().descriptionId("fluid.examplemod.oil").density(900).viscosity(2000).temperature(320)));

    public static final RegistryObject<ForgeFlowingFluid> OIL = FLUIDS.register("oil",
            () -> new ForgeFlowingFluid.Source(ModFluids.PROPS));
    public static final RegistryObject<ForgeFlowingFluid> OIL_FLOWING = FLUIDS.register("flowing_oil",
            () -> new ForgeFlowingFluid.Flowing(ModFluids.PROPS));

    static final ForgeFlowingFluid.Properties PROPS = new ForgeFlowingFluid.Properties(OIL_TYPE, OIL, OIL_FLOWING)
            .bucket(ModItems.OIL_BUCKET);
}
