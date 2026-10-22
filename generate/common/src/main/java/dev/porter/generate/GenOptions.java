package dev.porter.generate;

import java.nio.file.Path;

/**
 * Options of one {@code porter generate} run. Everything that changes the output is here, so a run is a pure
 * function of (IR, options).
 *
 * @param version        target Minecraft version, e.g. {@code 1.7.10}
 * @param basePackage    Java package of the generated code (subpackages block/, item/, ... below it)
 * @param modId          target mod id (registry namespace of the generated objects)
 * @param modName        display name
 * @param modVersion     mod version string
 * @param embed          true: no {@code @Mod} class, the host mod calls the generated {@code *Content} entry points
 * @param guiIds         GUI id range for menus (1.7.10 IGuiHandler), inclusive
 * @param potionIds      Potion id range for effects (1.7.10 Potion.potionTypes), inclusive
 * @param channel        network channel name (1.7.10: max 20 chars) or null = derived from the mod id
 * @param assetsDir      output of {@code porter assets} (contains assets/ and meta/), or null
 * @param assetNamespace rename the asset namespace (texture domain) of the mod's own ids, or null
 * @param legacyNames    unlocalized-name style: PATH | NS_PATH | NS_CAMEL (must match {@code porter assets})
 * @param stubs          emit stubs for referenced logic classes (off when the rewriter output is compiled alongside)
 * @param idMap          IR id → target registry reference {@code modid:name[@meta]} for objects that are not generated
 *                       (host-mod or renamed vanilla ids); wins over the default resolution. Empty map = none.
 * @param nei            1.7.10: generate the NotEnoughItems integration (recipe pages for custom recipe types, fluids,
 *                       multiblocks; hidden objects). Needs the NEI API at compile time only (compileOnly).
 * @param sourceConstructors construct logic classes like the source registry did: {@code new Logic(args)} with the
 *                       arguments the extractor recorded ({@code porter-extract:newArgs}), names set after construction;
 *                       for logic classes ported whole (closure), whose constructors are the source's
 * @param guiDelegate    1.7.10: class whose static {@code handles(int)}, {@code server(...)} and {@code client(...)}
 *                       ({@code int id, EntityPlayer, World, int x, int y, int z}) open the GUIs of menus with a logic
 *                       class (ported containers build their own screens) and every id it handles; null = none
 */
public record GenOptions(String version, String basePackage, String modId, String modName, String modVersion,
        boolean embed, IntRange guiIds, IntRange potionIds, String channel, Path assetsDir, String assetNamespace,
        LegacyNames legacyNames, boolean stubs, java.util.Map<String, String> idMap, boolean nei,
        boolean sourceConstructors, String guiDelegate) {

    public GenOptions {
        idMap = idMap == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(idMap));
    }

    /** Options without source constructors and GUI delegate (backwards-compatible constructor). */
    public GenOptions(String version, String basePackage, String modId, String modName, String modVersion,
            boolean embed, IntRange guiIds, IntRange potionIds, String channel, Path assetsDir, String assetNamespace,
            LegacyNames legacyNames, boolean stubs, java.util.Map<String, String> idMap, boolean nei) {
        this(version, basePackage, modId, modName, modVersion, embed, guiIds, potionIds, channel, assetsDir,
                assetNamespace, legacyNames, stubs, idMap, nei, false, null);
    }

    /** Options with NEI integration on (backwards-compatible constructor). */
    public GenOptions(String version, String basePackage, String modId, String modName, String modVersion,
            boolean embed, IntRange guiIds, IntRange potionIds, String channel, Path assetsDir, String assetNamespace,
            LegacyNames legacyNames, boolean stubs, java.util.Map<String, String> idMap) {
        this(version, basePackage, modId, modName, modVersion, embed, guiIds, potionIds, channel, assetsDir,
                assetNamespace, legacyNames, stubs, idMap, true, false, null);
    }

    /** Options without an id map (backwards-compatible constructor). */
    public GenOptions(String version, String basePackage, String modId, String modName, String modVersion,
            boolean embed, IntRange guiIds, IntRange potionIds, String channel, Path assetsDir, String assetNamespace,
            LegacyNames legacyNames, boolean stubs) {
        this(version, basePackage, modId, modName, modVersion, embed, guiIds, potionIds, channel, assetsDir,
                assetNamespace, legacyNames, stubs, java.util.Map.of(), true, false, null);
    }

    /** Inclusive integer range written as {@code from-to}. */
    public record IntRange(int from, int to) {
        public static IntRange parse(String s) {
            String[] p = s.trim().split("-", 2);
            int a = Integer.parseInt(p[0].trim());
            int b = p.length > 1 ? Integer.parseInt(p[1].trim()) : a;
            if (b < a) {
                throw new IllegalArgumentException("empty range " + s);
            }
            return new IntRange(a, b);
        }

        public int size() {
            return to - from + 1;
        }

        @Override
        public String toString() {
            return from + "-" + to;
        }
    }

    /** Unlocalized-name styles, identical to {@code porter assets --legacy-names}. */
    public enum LegacyNames {
        /** {@code ns:steam_turbine} → {@code steam_turbine}. */
        PATH,
        /** → {@code ns.steam_turbine}. */
        NS_PATH,
        /** → {@code ns.steamTurbine}. */
        NS_CAMEL;

        public String apply(String namespace, String path) {
            String p = path.replace('/', '.');
            return switch (this) {
                case PATH -> p;
                case NS_PATH -> namespace + "." + p;
                case NS_CAMEL -> namespace + "." + Names.lowerCamel(p);
            };
        }
    }
}
