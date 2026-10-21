// porter:adapter (generic) - the part of 1.19.2's LootContext that block drops use (no loot tables on 1.7.10).
package dev.porter.shim.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.World;

public class LootContext {
    /** 1.19.2 LootContextParams keys. */
    public static final class Params {
        public static final Object ORIGIN = "origin";
        public static final Object BLOCK_STATE = "block_state";
        public static final Object BLOCK_ENTITY = "block_entity";
        public static final Object TOOL = "tool";
        public static final Object THIS_ENTITY = "this_entity";
        public static final Object EXPLOSION_RADIUS = "explosion_radius";

        private Params() {}
    }

    public static class Builder {
        private final World level;
        private final Map<Object, Object> params = new HashMap<>();

        public Builder(World level) { this.level = level; }

        public World getLevel() { return level; }

        public Builder withParameter(Object key, Object value) {
            params.put(key, value);
            return this;
        }

        public Builder withOptionalParameter(Object key, Object value) {
            if (value != null) params.put(key, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        public <T> T getParameter(Object key) { return (T) params.get(key); }

        @SuppressWarnings("unchecked")
        public <T> T getOptionalParameter(Object key) { return (T) params.get(key); }

        public Random getRandom() { return level.rand; }
    }
}
