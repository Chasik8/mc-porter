// porter:adapter (generic) - 1.19.2 BlockBehaviour.Properties: a builder whose values StateBlock applies to the
// 1.7.10 Block (material, hardness, resistance, sound, light, ticking).
package dev.porter.shim.mc;

import java.util.function.ToIntFunction;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

public class BlockBehaviour {
    /** 1.19.2 BlockBehaviour.StatePredicate / StateArgumentPredicate (accepted, not evaluated on 1.7.10). */
    @FunctionalInterface
    public interface StatePredicate {
        boolean test(Object state, Object level, Object pos);
    }

    public static class Properties {
        public Material material = Material.rock;
        public float hardness = 0F;
        /** 1.19.2 explosion resistance (1.7.10 Block.setResistance takes value/3 * 5 / 5 -> see apply). */
        public float resistance = 0F;
        public Block.SoundType sound = Block.soundTypeStone;
        public ToIntFunction<BlockState> light = s -> 0;
        public boolean randomTicks;
        public boolean noOcclusion;
        public boolean noCollission;
        public boolean requiresTool;
        public float friction = 0.6F;

        public static Properties of(Material m) {
            Properties p = new Properties();
            p.material = m;
            return p;
        }

        public static Properties of(Material m, Object color) { return of(m); }

        public static Properties copy(Object other) {
            Properties p = new Properties();
            if (other instanceof Properties) {
                Properties o = (Properties) other;
                p.material = o.material; p.hardness = o.hardness; p.resistance = o.resistance; p.sound = o.sound;
                p.light = o.light; p.randomTicks = o.randomTicks; p.noOcclusion = o.noOcclusion;
                p.noCollission = o.noCollission; p.requiresTool = o.requiresTool; p.friction = o.friction;
            } else if (other instanceof Block) {
                Block b = (Block) other;
                p.material = b.getMaterial();
                p.sound = b.stepSound;
            }
            return p;
        }

        public Properties strength(float hardness, float resistance) {
            this.hardness = hardness;
            this.resistance = Math.max(0F, resistance);
            return this;
        }

        public Properties strength(float both) { return strength(both, both); }

        public Properties instabreak() { return strength(0F); }

        public Properties explosionResistance(float r) { this.resistance = r; return this; }

        public Properties destroyTime(float h) { this.hardness = h; return this; }

        public Properties sound(Block.SoundType s) { this.sound = s; return this; }

        public Properties lightLevel(ToIntFunction<BlockState> f) { this.light = f; return this; }

        public Properties randomTicks() { this.randomTicks = true; return this; }

        public Properties noOcclusion() { this.noOcclusion = true; return this; }

        public Properties noCollission() { this.noCollission = true; return this; }

        public Properties requiresCorrectToolForDrops() { this.requiresTool = true; return this; }

        public Properties friction(float f) { this.friction = f; return this; }

        public Properties noLootTable() { return this; }

        /** 1.19.2 Properties.air() (AirBlock decides air-ness on 1.7.10) */
        public Properties air() { return this; }

        public Properties dynamicShape() { return this; }

        public Properties isValidSpawn(StatePredicate o) { return this; }

        public Properties isRedstoneConductor(StatePredicate o) { return this; }

        public Properties isSuffocating(StatePredicate o) { return this; }

        public Properties isViewBlocking(StatePredicate o) { return this; }

        public Properties color(Object o) { return this; }

        /** applies the values to a 1.7.10 block (called by StateBlock's constructor). */
        public void apply(Block b) {
            b.setHardness(hardness);
            // 1.7.10 Block.setResistance(r) stores r*3 and explosions divide by 5 (= 0.6 r); 1.19.2 uses the value as is
            b.setResistance(resistance / 0.6F);
            b.setStepSound(sound);
            if (randomTicks) b.setTickRandomly(true);
            b.slipperiness = friction;
        }
    }
}
