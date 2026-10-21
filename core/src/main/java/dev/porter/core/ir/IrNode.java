package dev.porter.core.ir;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One IR node. Mirrors {@code contracts/ir.schema.json} one-to-one; the schema is authoritative and every
 * document is validated against it before binding ({@link IrIo}). Absent optional fields are {@code null}.
 *
 * <p>Logic is never part of the IR: nodes point at logic classes ({@code logicClass}, {@code handlerClass}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IrNode.Mod.class, name = "mod"),
    @JsonSubTypes.Type(value = IrNode.Block.class, name = "block"),
    @JsonSubTypes.Type(value = IrNode.Item.class, name = "item"),
    @JsonSubTypes.Type(value = IrNode.BlockEntity.class, name = "blockEntity"),
    @JsonSubTypes.Type(value = IrNode.Fluid.class, name = "fluid"),
    @JsonSubTypes.Type(value = IrNode.Recipe.class, name = "recipe"),
    @JsonSubTypes.Type(value = IrNode.Menu.class, name = "menu"),
    @JsonSubTypes.Type(value = IrNode.Packet.class, name = "packet"),
    @JsonSubTypes.Type(value = IrNode.Lang.class, name = "lang"),
    @JsonSubTypes.Type(value = IrNode.Sound.class, name = "sound"),
    @JsonSubTypes.Type(value = IrNode.Effect.class, name = "effect"),
    @JsonSubTypes.Type(value = IrNode.Multiblock.class, name = "multiblock"),
    @JsonSubTypes.Type(value = IrNode.Tag.class, name = "tag"),
    @JsonSubTypes.Type(value = IrNode.Unknown.class, name = "unknown"),
})
public sealed interface IrNode {
    /** Value of the {@code kind} field. */
    String kind();

    /** Concept id: namespaced resource id, bare mod id for {@link Mod}, may be null for {@link Unknown}. */
    String id();

    Provenance provenance();

    /** Extension data, keys namespaced {@code owner:name}; may be null. */
    Map<String, JsonNode> extensions();

    /** Confidence in [0, 1]; null means 1.0. Always null for {@link Unknown}. */
    Double confidence();

    /** {@link #confidence()} with the default applied. */
    default double effectiveConfidence() {
        return confidence() == null ? 1.0 : confidence();
    }

    // ---- shared value types -------------------------------------------------------------------

    /** @param chance probability of a custom-recipe output (RFC 003), null = 1 */
    record ItemStack(String item, Integer count, JsonNode nbt, Double chance) {
        public ItemStack(String item, Integer count, JsonNode nbt) {
            this(item, count, nbt, null);
        }
    }

    /** RFC 003: an amount of fluid in mB; chance for outputs (null = 1). */
    record FluidStack(String fluid, int amount, Double chance) {
    }

    /** RFC 003: a recipe-viewer line, plain text (written as a string) or a translation key with its English text. */
    record Note(String key, String text) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Note of(String text) {
            return new Note(null, text);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public Note {
        }

        @JsonValue
        Object json() {
            if (key == null) {
                return text;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("text", text);
            return m;
        }
    }

    /** Exactly one of item, tag, anyOf is set. */
    record Ingredient(String item, String tag, List<Ingredient> anyOf, Integer count) {
    }

    /** Palette entry: a block id (written as a plain string), a block with state, or a wildcard (any = true). */
    record BlockRef(String block, Map<String, String> state, Boolean any) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static BlockRef of(String block) {
            return new BlockRef(block, null, null);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public BlockRef {
        }

        @JsonValue
        Object json() {
            if (state == null && any == null) {
                return block;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            if (any != null) {
                m.put("any", any);
            } else {
                m.put("block", block);
                if (state != null) {
                    m.put("state", state);
                }
            }
            return m;
        }
    }

    /** Tag entry: an id or #tag (plain string), or {id, required}. */
    record TagValue(String id, Boolean required) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static TagValue of(String id) {
            return new TagValue(id, null);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public TagValue {
        }

        @JsonValue
        Object json() {
            if (required == null) {
                return id;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("required", required);
            return m;
        }
    }

    // ---- concepts -------------------------------------------------------------------------------

    record Mod(String id, String name, String version, String description, List<String> authors, String license,
            String mainClass, List<Dependency> dependencies,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record Dependency(String modId, String versionRange, Boolean required, String ordering) {
        }

        @Override
        public String kind() {
            return "mod";
        }
    }

    record Block(String id, String translationKey, String logicClass, String material, Double hardness, Double resistance,
            Integer lightLevel, String soundType, Boolean requiresTool, Harvest harvest, String creativeTab,
            List<StateProperty> stateProperties, Map<String, String> textures, Model model, String blockEntity,
            String item, String lootTable, Boolean hidden,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        /** Without the RFC 003 fields. */
        public Block(String id, String translationKey, String logicClass, String material, Double hardness, Double resistance,
                Integer lightLevel, String soundType, Boolean requiresTool, Harvest harvest, String creativeTab,
                List<StateProperty> stateProperties, Map<String, String> textures, Model model, String blockEntity,
                String item, String lootTable, Provenance provenance, Double confidence, Map<String, JsonNode> extensions) {
            this(id, translationKey, logicClass, material, hardness, resistance, lightLevel, soundType, requiresTool, harvest,
                    creativeTab, stateProperties, textures, model, blockEntity, item, lootTable, null, provenance, confidence,
                    extensions);
        }

        /** @param tool pickaxe | axe | shovel | hoe | sword | shears */
        public record Harvest(String tool, Integer level) {
        }

        /** @param type boolean | integer | enum | direction; defaultValue is serialized as "default" */
        public record StateProperty(String name, String type, List<String> values,
                @JsonProperty("default") String defaultValue) {
        }

        public record Model(String ref, String shape, String renderType) {
        }

        @Override
        public String kind() {
            return "block";
        }
    }

    record Item(String id, String translationKey, String logicClass, Integer maxStackSize, Integer maxDamage, String rarity,
            String creativeTab, String placesBlock, String containerItem, Map<String, String> textures, Food food,
            Boolean hidden,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        /** Without the RFC 003 fields. */
        public Item(String id, String translationKey, String logicClass, Integer maxStackSize, Integer maxDamage, String rarity,
                String creativeTab, String placesBlock, String containerItem, Map<String, String> textures, Food food,
                Provenance provenance, Double confidence, Map<String, JsonNode> extensions) {
            this(id, translationKey, logicClass, maxStackSize, maxDamage, rarity, creativeTab, placesBlock, containerItem,
                    textures, food, null, provenance, confidence, extensions);
        }

        public record Food(Integer nutrition, Double saturation, Boolean alwaysEdible, Boolean drink, List<FoodEffect> effects) {
        }

        /** @param duration ticks */
        public record FoodEffect(String effect, Integer duration, Integer amplifier, Double probability) {
        }

        @Override
        public String kind() {
            return "item";
        }
    }

    /** @param ticking none | server | client | both */
    record BlockEntity(String id, List<String> blocks, String logicClass, String ticking, String menu, Integer inventorySize,
            List<NbtKey> nbt,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record NbtKey(String key, String type) {
        }

        @Override
        public String kind() {
            return "blockEntity";
        }
    }

    record Fluid(String id, String translationKey, String logicClass, String stillTexture, String flowingTexture, String color,
            Integer density, Integer viscosity, Integer temperature, Integer luminosity, Boolean gaseous, String block,
            String bucket,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        @Override
        public String kind() {
            return "fluid";
        }
    }

    /**
     * @param recipeType shaped | shapeless | smelting | blasting | smoking | campfire | stonecutting | smithing | custom
     * @param fluidIngredients RFC 003: fluids consumed (custom recipes)
     * @param fluidResults RFC 003: fluids produced
     * @param duration RFC 003: processing time in ticks at base speed
     * @param machines RFC 003: blocks/items performing the recipe, first = main machine
     * @param notes RFC 003: recipe-viewer lines (conditions, ranges, ageing...)
     */
    record Recipe(String id, String recipeType, String customType, List<String> pattern, Map<String, Ingredient> key,
            List<Ingredient> ingredients, ItemStack result, List<ItemStack> results, List<FluidStack> fluidIngredients,
            List<FluidStack> fluidResults, Double experience, Integer cookingTime, Integer duration, List<String> machines,
            List<Note> notes, JsonNode data,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        /** Without the RFC 003 fields. */
        public Recipe(String id, String recipeType, String customType, List<String> pattern, Map<String, Ingredient> key,
                List<Ingredient> ingredients, ItemStack result, List<ItemStack> results, Double experience, Integer cookingTime,
                JsonNode data, Provenance provenance, Double confidence, Map<String, JsonNode> extensions) {
            this(id, recipeType, customType, pattern, key, ingredients, result, results, null, null, experience, cookingTime,
                    null, null, null, data, provenance, confidence, extensions);
        }

        @Override
        public String kind() {
            return "recipe";
        }
    }

    record Menu(String id, String title, String logicClass, String screenClass, String blockEntity, Size size,
            Texture texture, List<Slot> slots, PlayerInventory playerInventory, List<DataSlot> dataSlots, List<Bar> bars,
            List<Button> buttons,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record Size(int width, int height) {
        }

        public record Texture(String path, Integer textureWidth, Integer textureHeight, Integer u, Integer v) {
        }

        /** @param role input | output | fuel | upgrade | battery | other; filter is an id or #tag */
        public record Slot(int index, int x, int y, String role, String filter) {
        }

        public record PlayerInventory(int x, int y, Integer hotbarY) {
        }

        public record DataSlot(String name, int index) {
        }

        /** @param max integer or dataSlots name */
        public record Bar(String id, int x, int y, int width, int height, String direction, int u, int v, String value,
                JsonNode max) {
        }

        public record Button(String id, int x, int y, int width, int height, String label, String packet) {
        }

        @Override
        public String kind() {
            return "menu";
        }
    }

    /** @param direction toServer | toClient | both */
    record Packet(String id, String channel, Integer discriminator, String direction, String logicClass, String handlerClass,
            List<Field> fields,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record Field(String name, String type, String elementType, String enumClass, Integer maxLength) {
        }

        @Override
        public String kind() {
            return "packet";
        }
    }

    record Lang(String id, String locale, Map<String, String> entries,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        @Override
        public String kind() {
            return "lang";
        }
    }

    record Sound(String id, String category, String subtitle, Boolean replace, List<SoundFile> sounds,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record SoundFile(String name, Double volume, Double pitch, Integer weight, Boolean stream) {
        }

        @Override
        public String kind() {
            return "sound";
        }
    }

    /** @param category beneficial | harmful | neutral */
    record Effect(String id, String translationKey, String logicClass, String category, String color, Boolean instant,
            String icon, List<AttributeModifier> attributeModifiers,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        /** @param operation addition | multiply_base | multiply_total */
        public record AttributeModifier(String attribute, String uuid, double amount, String operation) {
        }

        @Override
        public String kind() {
            return "effect";
        }
    }

    /**
     * Structure as data: size = [x, y, z]; layers bottom-to-top, each layer z rows of x palette chars;
     * positions relative to the min corner.
     */
    record Multiblock(String id, List<Integer> size, Map<String, BlockRef> palette, List<List<String>> layers,
            Controller controller, Boolean rotatable, Boolean mirrorable,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        public record Controller(String block, List<Integer> pos, String facing) {
        }

        /** Palette char at (x, y, z). */
        public char charAt(int x, int y, int z) {
            return layers.get(y).get(z).charAt(x);
        }

        @Override
        public String kind() {
            return "multiblock";
        }
    }

    /** @param registry block | item | fluid | entity_type; oreDict = OreDictionary names for 1.7.10 */
    record Tag(String id, String registry, Boolean replace, List<TagValue> values, List<String> oreDict,
            Provenance provenance, Double confidence, Map<String, JsonNode> extensions) implements IrNode {
        @Override
        public String kind() {
            return "tag";
        }
    }

    /** Emitted whenever an extractor is not certain. Never guess. */
    record Unknown(String id, String reason, String snippet, String conceptHint, Provenance provenance,
            Map<String, JsonNode> extensions) implements IrNode {
        public static Unknown of(String reason, String snippet, Provenance provenance) {
            return new Unknown(null, reason, snippet, null, provenance, null);
        }

        @Override
        public String kind() {
            return "unknown";
        }

        @Override
        public Double confidence() {
            return null;
        }
    }
}
