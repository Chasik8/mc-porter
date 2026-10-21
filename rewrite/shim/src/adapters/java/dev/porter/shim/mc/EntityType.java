// porter:adapter (generic) - Forge 1.19.2 EntityType<T> keys and categories over the 1.7.10 EntityList. Vanilla
// entities get their 1.19.2 ids ("PigZombie" -> minecraft:zombified_piglin); modded 1.7.10 names "modid.Name" become
// "modid:name" (snake case). REGISTRY stands in for ForgeRegistries.ENTITY_TYPES.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.util.ResourceLocation;

public final class EntityType<T extends Entity> {
    private static final Map<String, String> VANILLA = new HashMap<>();

    static {
        String[] m = {"Item", "item", "XPOrb", "experience_orb", "LeashKnot", "leash_knot", "Painting", "painting",
            "Arrow", "arrow", "Snowball", "snowball", "Fireball", "fireball", "SmallFireball", "small_fireball",
            "ThrownEnderpearl", "ender_pearl", "EyeOfEnderSignal", "eye_of_ender", "ThrownPotion", "potion",
            "ThrownExpBottle", "experience_bottle", "ItemFrame", "item_frame", "WitherSkull", "wither_skull",
            "PrimedTnt", "tnt", "FallingSand", "falling_block", "FireworksRocketEntity", "firework_rocket",
            "Boat", "boat", "MinecartRideable", "minecart", "MinecartChest", "chest_minecart",
            "MinecartFurnace", "furnace_minecart", "MinecartTNT", "tnt_minecart", "MinecartHopper", "hopper_minecart",
            "MinecartSpawner", "spawner_minecart", "MinecartCommandBlock", "command_block_minecart",
            "Creeper", "creeper", "Skeleton", "skeleton", "Spider", "spider", "Giant", "giant", "Zombie", "zombie",
            "Slime", "slime", "Ghast", "ghast", "PigZombie", "zombified_piglin", "Enderman", "enderman",
            "CaveSpider", "cave_spider", "Silverfish", "silverfish", "Blaze", "blaze", "LavaSlime", "magma_cube",
            "EnderDragon", "ender_dragon", "WitherBoss", "wither", "Bat", "bat", "Witch", "witch", "Pig", "pig",
            "Sheep", "sheep", "Cow", "cow", "Chicken", "chicken", "Squid", "squid", "Wolf", "wolf",
            "MushroomCow", "mooshroom", "SnowMan", "snow_golem", "Ozelot", "ocelot", "VillagerGolem", "iron_golem",
            "EntityHorse", "horse", "Villager", "villager", "EnderCrystal", "end_crystal"};
        for (int i = 0; i < m.length; i += 2) VANILLA.put(m[i], m[i + 1]);
    }

    private static final Map<Class<?>, EntityType<?>> BY_CLASS = new HashMap<>();

    private final Class<? extends Entity> cls;
    private final String legacyName;
    private final ResourceLocation id;

    private EntityType(Class<? extends Entity> cls, String legacyName) {
        this.cls = cls;
        this.legacyName = legacyName;
        this.id = idOf(legacyName);
    }

    /** 1.19.2 id of a 1.7.10 EntityList name */
    public static ResourceLocation idOf(String legacy) {
        if (legacy == null) return null;
        String v = VANILLA.get(legacy);
        if (v != null) return new ResourceLocation("minecraft", v);
        int dot = legacy.indexOf('.');
        String ns = dot > 0 ? legacy.substring(0, dot).toLowerCase(java.util.Locale.ROOT) : "minecraft";
        return new ResourceLocation(ns, snake(dot > 0 ? legacy.substring(dot + 1) : legacy));
    }

    static String snake(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && s.charAt(i - 1) != '_') b.append('_');
                b.append(Character.toLowerCase(c));
            } else b.append(c == ' ' ? '_' : c);
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static synchronized EntityType<?> of(Class<? extends Entity> c) {
        if (c == null) return null;
        EntityType<?> t = BY_CLASS.get(c);
        if (t == null) {
            String n = (String) EntityList.classToStringMapping.get(c);
            if (n == null) return null;
            t = new EntityType<>(c, n);
            BY_CLASS.put(c, t);
        }
        return t;
    }

    /** 1.19.2 Entity#getType() */
    public static EntityType<?> of(Entity e) { return e == null ? null : of(e.getClass()); }

    /** 1.19.2 EntityType.getKey(type) */
    public static ResourceLocation getKey(EntityType<?> t) { return t == null ? null : t.id; }

    public ResourceLocation getId() { return id; }

    public String getLegacyName() { return legacyName; }

    public Class<? extends Entity> getEntityClass() { return cls; }

    public String getDescriptionId() { return "entity." + legacyName + ".name"; }

    public MobCategory getCategory() {
        if (IMob.class.isAssignableFrom(cls) || EntityDragon.class.isAssignableFrom(cls) || EntityWither.class.isAssignableFrom(cls)) return MobCategory.MONSTER;
        if (EntityAmbientCreature.class.isAssignableFrom(cls)) return MobCategory.AMBIENT;
        if (EntityWaterMob.class.isAssignableFrom(cls)) return MobCategory.WATER_CREATURE;
        if (EntityGolem.class.isAssignableFrom(cls) || IMerchant.class.isAssignableFrom(cls)) return MobCategory.MISC;
        if (EntityAnimal.class.isAssignableFrom(cls)) return MobCategory.CREATURE;
        return MobCategory.MISC;
    }

    public boolean isLiving() { return EntityLivingBase.class.isAssignableFrom(cls); }

    @Override
    public String toString() { return "EntityType[" + id + "]"; }

    // ------------------------------------------------------------------ ForgeRegistries.ENTITY_TYPES

    public static final IForgeRegistry<EntityType<?>> REGISTRY = new IForgeRegistry<EntityType<?>>() {
        @SuppressWarnings("unchecked")
        private Map<ResourceLocation, EntityType<?>> all() {
            Map<ResourceLocation, EntityType<?>> m = new LinkedHashMap<>();
            for (Object o : EntityList.classToStringMapping.keySet()) {
                EntityType<?> t = of((Class<? extends Entity>) o);
                if (t != null) m.put(t.id, t);
            }
            return m;
        }

        public ResourceLocation getKey(EntityType<?> v) { return v == null ? null : v.id; }

        public EntityType<?> getValue(ResourceLocation k) { return all().get(k); }

        public boolean containsKey(ResourceLocation k) { return all().containsKey(k); }

        public boolean containsValue(EntityType<?> v) { return v != null && all().containsKey(v.id); }

        public Set<ResourceLocation> getKeys() { return new LinkedHashSet<>(all().keySet()); }

        public Collection<EntityType<?>> getValues() { return new ArrayList<>(all().values()); }

        public ResourceLocation getRegistryName() { return new ResourceLocation("minecraft", "entity_type"); }

        public Iterator<EntityType<?>> iterator() { return getValues().iterator(); }
    };
}
