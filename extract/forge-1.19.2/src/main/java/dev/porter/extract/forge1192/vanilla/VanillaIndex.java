package dev.porter.extract.forge1192.vanilla;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Facts about vanilla Minecraft 1.19.2 and Forge 43 that the extractors need, read from the real jars and
 * mappings (rule 1: never invent API).
 *
 * <ul>
 *   <li>{@code srg}: SRG member name ({@code m_60913_}, {@code f_76320_}) → official (Mojang) name. Forge 1.19.2
 *       production jars and their decompiles use SRG member names with official class names.</li>
 *   <li>{@code staticIds}: {@code owner#FIELD} → registry id for static registry holders (Blocks, Items,
 *       MobEffects, SoundEvents, Fluids, vanilla and Forge tags), read from the class initialisers.</li>
 *   <li>{@code properties}: block state properties of {@code BlockStateProperties}.</li>
 *   <li>{@code constants}: individual verified constants (e.g. AbstractContainerScreen default image size).</li>
 * </ul>
 *
 * The index is derived from Mojang mappings and the client jar, so it is never committed; it is built into the
 * cache directory by {@link VanillaIndexBuilder}. Without an index, SRG names stay as they are and vanilla
 * references resolve to {@code unknown}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VanillaIndex(int formatVersion, String minecraft, String forge, Map<String, String> srg,
        Map<String, String> staticIds, Map<String, StateProp> properties, Map<String, Integer> constants,
        Map<String, List<String>> enums) {

    public static final int FORMAT = 4;
    private static final Pattern SRG = Pattern.compile("^[mf]_\\d+_$");

    public VanillaIndex {
        srg = srg == null ? Map.of() : srg;
        staticIds = staticIds == null ? Map.of() : staticIds;
        properties = properties == null ? Map.of() : properties;
        constants = constants == null ? Map.of() : constants;
        enums = enums == null ? Map.of() : enums;
    }

    public VanillaIndex(int formatVersion, String minecraft, String forge, Map<String, String> srg,
            Map<String, String> staticIds, Map<String, StateProp> properties, Map<String, Integer> constants) {
        this(formatVersion, minecraft, forge, srg, staticIds, properties, constants, Map.of());
    }

    /** Enum constants of a vanilla enum in ordinal order as "CONSTANT=serializedName[#firstIntArg]", or null. */
    public List<String> enumConstants(String owner) {
        return enums.get(owner);
    }

    /** @param type boolean | integer | direction | enum; values in declaration order */
    public record StateProp(String name, String type, List<String> values) {
    }

    public static VanillaIndex empty() {
        return new VanillaIndex(FORMAT, "1.19.2", null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Official name of a member: SRG names are translated when known, anything else is returned as is. */
    public String official(String name) {
        if (name != null && SRG.matcher(name).matches()) {
            String o = srg.get(name);
            return o == null ? name : o;
        }
        return name;
    }

    public boolean isSrg(String name) {
        return name != null && SRG.matcher(name).matches();
    }

    /** Registry id of a static holder field ({@code net.minecraft.world.level.block.Blocks#STONE}), or null. */
    public String staticId(String owner, String officialField) {
        return staticIds.get(owner + "#" + officialField);
    }

    public static VanillaIndex read(Path file) {
        try {
            return Json.json().readValue(Files.readString(file, StandardCharsets.UTF_8), VanillaIndex.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            VanillaIndex sorted = new VanillaIndex(formatVersion, minecraft, forge, new TreeMap<>(srg),
                    new TreeMap<>(staticIds), new TreeMap<>(properties), new TreeMap<>(constants), new TreeMap<>(enums));
            Files.writeString(file, Json.json().writeValueAsString(sorted).replace("\r\n", "\n") + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
