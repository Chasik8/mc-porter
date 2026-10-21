// porter:adapter (generic) - Forge 1.19.2 @Mod / @Mod.EventBusSubscriber on 1.7.10. The 1.7.10 entry point is a
// separate @cpw.mods.fml.common.Mod class (generated or wiring); this annotation only marks the ported 1.19.2 mod
// class. EventBusSubscriber classes are found through FML's ASM data table by ModLifecycle.construct and their
// static @SubscribeEvent methods are registered on the matching bus.
package dev.porter.shim.mc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface EventBusSubscriber {
        /** 1.19.2 Dist values; the reference rules map Dist to cpw Side. */
        cpw.mods.fml.relauncher.Side[] value() default {cpw.mods.fml.relauncher.Side.CLIENT, cpw.mods.fml.relauncher.Side.SERVER};

        String modid() default "";

        Bus bus() default Bus.FORGE;

        enum Bus { FORGE, MOD }
    }
}
