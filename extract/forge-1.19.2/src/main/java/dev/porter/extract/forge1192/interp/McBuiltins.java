package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.Fn;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.Pos;
import dev.porter.extract.forge1192.interp.V.ResLoc;
import dev.porter.extract.forge1192.interp.V.Seq;
import dev.porter.extract.forge1192.interp.V.State;
import dev.porter.extract.forge1192.interp.V.Sym;
import dev.porter.extract.forge1192.interp.V.Unknown;
import dev.porter.extract.forge1192.interp.V.VObj;
import dev.porter.extract.forge1192.vanilla.VanillaIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtType;

/**
 * Minecraft 1.19.2 / Forge 43 API used by declarative mod code: resource locations, positions, directions, block
 * states, item stacks, builder recorders (Properties), registries and their {@code register} calls, tag keys,
 * networking and menu registration. Names are official Mojang names.
 */
public final class McBuiltins implements Builtins {
    public static final String DIRECTION = "net.minecraft.core.Direction";
    public static final String BLOCK_POS = "net.minecraft.core.BlockPos";
    static final String MUTABLE_POS = "net.minecraft.core.BlockPos$MutableBlockPos";
    static final String RL = "net.minecraft.resources.ResourceLocation";

    /** Direction constants in ordinal (3D data value) order with their step vectors. */
    static final String[] DIRS = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
    static final int[][] STEP = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    /** Builder classes whose instance calls are recorded and return the builder itself. */
    static final Set<String> BUILDERS = Set.of(
            "net.minecraft.world.level.block.state.BlockBehaviour$Properties",
            "net.minecraft.world.item.Item$Properties",
            "net.minecraft.world.food.FoodProperties$Builder",
            "net.minecraftforge.fluids.FluidType$Properties",
            "net.minecraftforge.fluids.ForgeFlowingFluid$Properties",
            "net.minecraft.world.level.block.entity.BlockEntityType$Builder",
            "net.minecraftforge.network.simple.SimpleChannel$MessageBuilder",
            "net.minecraftforge.network.NetworkRegistry$ChannelBuilder",
            "net.minecraft.world.level.block.SoundType",
            "net.minecraft.world.level.block.state.StateDefinition$Builder");

    private final Facts facts;
    private final String modId;

    public McBuiltins(Facts facts, String modId) {
        this.facts = facts;
        this.modId = modId;
    }

    public Facts facts() {
        return facts;
    }

    // ---- static fields -------------------------------------------------------------------------------------

    @Override
    public Object getStatic(Interpreter in, String owner, String field) {
        VanillaIndex vi = in.vanilla();
        switch (owner) {
            case DIRECTION:
                for (String d : DIRS) {
                    if (d.equals(field)) {
                        return new Sym(DIRECTION, d);
                    }
                }
                break;
            case "net.minecraft.core.Direction$Plane":
                if (field.equals("HORIZONTAL") || field.equals("VERTICAL")) {
                    return new Sym(owner, field);
                }
                break;
            case BLOCK_POS:
                if (field.equals("ZERO")) {
                    return new Pos(0, 0, 0);
                }
                break;
            case "net.minecraftforge.registries.ForgeRegistries", "net.minecraftforge.registries.ForgeRegistries$Keys":
                return new V.Registry(registryName(field));
            case "net.minecraft.core.Registry":
                if (field.endsWith("_REGISTRY")) {
                    return new V.Registry(registryName(field.substring(0, field.length() - "_REGISTRY".length())));
                }
                return new V.Registry(registryName(field));
            case "net.minecraft.world.item.ItemStack":
                if (field.equals("EMPTY")) {
                    return new V.Stack(new V.RegRef("item", "minecraft:air"), 0, null);
                }
                break;
            default:
                break;
        }
        List<String> enumConsts = vi.enumConstants(owner);
        if (enumConsts != null) {
            for (String ec : enumConsts) {
                if (ec.startsWith(field + "=")) {
                    return new Sym(owner, field);
                }
            }
        }
        String id = vi.staticId(owner, field);
        if (id != null) {
            return switch (owner) {
                case "net.minecraft.world.level.block.Blocks" -> new V.RegRef("block", id);
                case "net.minecraft.world.item.Items" -> new V.RegRef("item", id);
                case "net.minecraft.world.effect.MobEffects" -> new V.RegRef("mob_effect", id);
                case "net.minecraft.sounds.SoundEvents" -> new V.RegRef("sound_event", id);
                case "net.minecraft.world.level.material.Fluids" -> new V.RegRef("fluid", id);
                case "net.minecraft.world.item.enchantment.Enchantments" -> new V.RegRef("enchantment", id);
                case "net.minecraft.world.entity.EntityType" -> new V.RegRef("entity_type", id);
                case "net.minecraft.tags.ItemTags", "net.minecraftforge.common.Tags$Items" -> new V.TagRef("item", id);
                case "net.minecraft.tags.BlockTags", "net.minecraftforge.common.Tags$Blocks" -> new V.TagRef("block", id);
                case "net.minecraft.tags.FluidTags", "net.minecraftforge.common.Tags$Fluids" -> new V.TagRef("fluid", id);
                default -> new V.RegRef("?", id);
            };
        }
        if (owner.equals("net.minecraft.world.level.block.Blocks") || owner.equals("net.minecraft.world.item.Items")
                || owner.equals("net.minecraft.world.effect.MobEffects") || owner.endsWith("Tags")
                || owner.startsWith("net.minecraftforge.common.Tags")) {
            return new Unknown("vanilla " + owner.substring(owner.lastIndexOf('.') + 1) + "." + field
                    + " not in vanilla index");
        }
        if (owner.equals("net.minecraft.world.level.block.state.properties.BlockStateProperties")) {
            VanillaIndex.StateProp p = vi.properties().get(field);
            if (p != null) {
                return new V.Prop(p.name(), p.type(), p.values());
            }
            return new Unknown("BlockStateProperties." + field + " not in vanilla index");
        }
        return NOT_HANDLED;
    }

    /** ForgeRegistries / Registry field name → registry key used in facts. */
    public static String registryName(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "blocks", "block" -> "block";
            case "items", "item" -> "item";
            case "block_entity_types", "block_entity_type" -> "block_entity_type";
            case "menu_types", "menu", "menus", "containers" -> "menu";
            case "mob_effects", "mob_effect" -> "mob_effect";
            case "sound_events", "sound_event" -> "sound_event";
            case "fluids", "fluid" -> "fluid";
            case "fluid_types", "fluid_type", "deferred_fluid_types" -> "fluid_type";
            case "recipe_serializers", "recipe_serializer" -> "recipe_serializer";
            case "recipe_types", "recipe_type" -> "recipe_type";
            case "entity_types", "entity_type" -> "entity_type";
            case "potions", "potion" -> "potion";
            default -> f.endsWith("s") ? f.substring(0, f.length() - 1) : f;
        };
    }

    // ---- constructors --------------------------------------------------------------------------------------

    @Override
    public Object construct(Interpreter in, String type, List<Object> args, CtElement site) {
        Unknown u = V.firstUnknown(args);
        switch (type) {
            case RL: {
                if (u != null) {
                    return u;
                }
                return resLoc(args);
            }
            case BLOCK_POS, "net.minecraft.core.Vec3i": {
                if (args.size() == 3) {
                    int[] xyz = ints(args);
                    return xyz == null ? firstUnknownOr(args, "BlockPos coords") : new Pos(xyz[0], xyz[1], xyz[2]);
                }
                if (args.size() == 1 && pos(args.get(0)) != null) {
                    return pos(args.get(0));
                }
                return firstUnknownOr(args, "BlockPos(" + args.size() + " args)");
            }
            case MUTABLE_POS: {
                if (args.isEmpty()) {
                    return new V.MutPos(0, 0, 0);
                }
                int[] xyz = args.size() == 3 ? ints(args) : null;
                return xyz == null ? firstUnknownOr(args, "MutableBlockPos coords") : new V.MutPos(xyz[0], xyz[1], xyz[2]);
            }
            case "net.minecraft.world.item.ItemStack": {
                if (args.isEmpty()) {
                    return new Unknown("ItemStack()");
                }
                Object item = args.get(0);
                Object count = args.size() > 1 ? args.get(1) : 1;
                if (item instanceof V.Holder || item instanceof Obj || item instanceof VObj || item instanceof V.RegRef
                        || item instanceof V.AsItem || item instanceof Unknown) {
                    return new V.Stack(item, count, args.size() > 2 ? args.get(2) : null);
                }
                return new Unknown("ItemStack of " + Interpreter.describe(item));
            }
            case "net.minecraft.sounds.SoundEvent": {
                VObj o = new VObj(type, null, args);
                return o;
            }
            case "net.minecraft.world.item.Item$Properties", "net.minecraft.world.food.FoodProperties$Builder",
                    "net.minecraftforge.fluids.ForgeFlowingFluid$Properties",
                    "net.minecraftforge.network.simple.SimpleChannel$MessageBuilder":
                return new VObj(type, "new", args);
            default:
                return NOT_HANDLED;
        }
    }

    private static Object firstUnknownOr(List<Object> args, String what) {
        Unknown u = V.firstUnknown(args);
        return u != null ? u : new Unknown(what);
    }

    private Object resLoc(List<Object> args) {
        if (args.size() == 2 && args.get(0) instanceof String ns && args.get(1) instanceof String p) {
            return new ResLoc(ns, p);
        }
        if (args.size() == 1 && args.get(0) instanceof String s) {
            int c = s.indexOf(':');
            return c < 0 ? new ResLoc("minecraft", s) : new ResLoc(s.substring(0, c), s.substring(c + 1));
        }
        return new Unknown("ResourceLocation(" + args + ")");
    }

    static int[] ints(List<Object> args) {
        int[] out = new int[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof Integer || a instanceof Long || a instanceof Short || a instanceof Character) {
                out[i] = Interpreter.num(a).intValue();
            } else if (a instanceof Double || a instanceof Float) {
                out[i] = (int) Math.floor(((Number) a).doubleValue());
            } else {
                return null;
            }
        }
        return out;
    }

    static Pos pos(Object v) {
        if (v instanceof Pos p) {
            return p;
        }
        if (v instanceof V.MutPos m) {
            return new Pos(m.x, m.y, m.z);
        }
        return null;
    }

    static int dirIndex(Object v) {
        if (v instanceof Sym s && s.owner().equals(DIRECTION)) {
            for (int i = 0; i < DIRS.length; i++) {
                if (DIRS[i].equals(s.name())) {
                    return i;
                }
            }
        }
        return -1;
    }

    static Sym dir(int i) {
        return new Sym(DIRECTION, DIRS[i]);
    }

    // ---- packed positions (BlockPos.asLong/of): X 26 bits @38, Z 26 bits @12, Y 12 bits @0 ----------------

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) | ((long) z & 0x3FFFFFFL) << 12;
    }

    static Pos unpack(long l) {
        int x = (int) (l << 0 >> 38);
        int y = (int) (l << 52 >> 52);
        int z = (int) (l << 26 >> 38);
        return new Pos(x, y, z);
    }

    // ---- calls ---------------------------------------------------------------------------------------------

    @Override
    public boolean isIntercept(String owner, String name) {
        if (owner == null) {
            return false;
        }
        return name.equals("register") && (owner.endsWith("IForgeRegistry") || owner.endsWith("ForgeRegistry")
                || owner.endsWith("DeferredRegister") || owner.endsWith("RegisterEvent")
                || owner.equals("net.minecraft.core.Registry") || owner.endsWith("RegisterEvent$RegisterHelper"))
                || name.equals("registerMessage") && owner.endsWith("SimpleChannel")
                || name.equals("add") && owner.endsWith("SimpleChannel$MessageBuilder")
                || name.equals("register") && owner.endsWith("MenuScreens");
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        Object t = c.target();
        String n = c.name();
        List<Object> a = c.args();
        String owner = c.owner() == null ? "" : c.owner();

        // -- registries ------------------------------------------------------------------------------------
        if (t instanceof V.Registry reg) {
            if (n.equals("register") && (a.size() == 2)) {
                return record(in, reg.registry(), a.get(0), a.get(1), c);
            }
            if (n.equals("get")) {
                return reg; // Supplier<IForgeRegistry<...>>.get()
            }
            if (n.equals("tags")) {
                return new VObj("net.minecraftforge.registries.tags.ITagManager", reg.registry(), List.of());
            }
            if (n.equals("getKey") && a.size() == 1) {
                Facts.Registration r = facts.of(a.get(0));
                if (r != null && r.id() != null) {
                    return parseRl(r.id());
                }
                if (a.get(0) instanceof V.RegRef rr) {
                    return parseRl(rr.id());
                }
                return new Unknown("registry key of " + Interpreter.describe(a.get(0)));
            }
            if (n.equals("getValue") && a.size() == 1 && a.get(0) instanceof ResLoc rl) {
                Facts.Registration r = facts.lookup(reg.registry(), rl.toString());
                return r != null ? r.value() : new V.RegRef(reg.registry(), rl.toString());
            }
            if (n.equals("containsKey") && a.size() == 1 && a.get(0) instanceof ResLoc rl) {
                // closed world: only this mod (and vanilla, known by id) registers entries
                if (facts.lookup(reg.registry(), rl.toString()) != null) {
                    return true;
                }
                return rl.namespace().equals("minecraft") ? new Unknown("vanilla registry content of " + rl) : false;
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj vo && vo.type.equals("net.minecraftforge.registries.tags.ITagManager")) {
            if (n.equals("createTagKey") && a.size() == 1 && a.get(0) instanceof ResLoc rl) {
                return new V.TagRef(vo.factory, rl.toString());
            }
            if (n.equals("createOptionalTagKey") && !a.isEmpty() && a.get(0) instanceof ResLoc rl) {
                return new V.TagRef(vo.factory, rl.toString());
            }
            return NOT_HANDLED;
        }
        if (owner.endsWith("DeferredRegister") && t == null && (n.equals("create") || n.equals("createOptional"))
                && a.size() == 2) {
            VObj dr = new VObj("net.minecraftforge.registries.DeferredRegister", "create", a);
            Object reg = a.get(0);
            dr.fields.put("registry", reg instanceof V.Registry r ? r.registry()
                    : reg instanceof ResLoc rl ? rl.path() : null);
            dr.fields.put("ns", a.get(1));
            return dr;
        }
        if (t instanceof VObj dr && dr.type.equals("net.minecraftforge.registries.DeferredRegister")) {
            if (n.equals("register") && a.size() == 2) {
                Object reg = dr.fields.get("registry");
                Object ns = dr.fields.get("ns");
                if (!(reg instanceof String regName)) {
                    return new Unknown("DeferredRegister of unknown registry");
                }
                Object name = a.get(0);
                Object id = name instanceof String s && ns instanceof String nss ? nss + ":" + s
                        : V.firstUnknown(List.of(name, ns)) != null ? V.firstUnknown(List.of(name, ns))
                        : new Unknown("DeferredRegister id " + Interpreter.describe(name));
                Object value = a.get(1) instanceof Fn fn ? new V.Lazy(fn)
                        : new Unknown("DeferredRegister supplier " + Interpreter.describe(a.get(1)));
                recordId(in, regName, id, value, c);
                return id instanceof String s ? new V.Holder(regName, s) : new Unknown("RegistryObject with unknown id");
            }
            if (n.equals("register") && a.size() == 1) {
                return V.NULL; // register(IEventBus)
            }
            if (n.equals("createTagKey") && a.size() == 1) {
                Object reg = dr.fields.get("registry");
                Object rl = a.get(0) instanceof String s && dr.fields.get("ns") instanceof String ns
                        ? new ResLoc(ns, s) : a.get(0);
                return reg instanceof String r && rl instanceof ResLoc l ? new V.TagRef(r, l.toString())
                        : new Unknown("createTagKey");
            }
            return NOT_HANDLED;
        }
        if (owner.endsWith("RegisterEvent") && n.equals("register")) {
            if (a.size() == 2 && a.get(0) instanceof V.Registry reg && a.get(1) instanceof Fn fn) {
                VObj helper = new VObj("net.minecraftforge.registries.RegisterEvent$RegisterHelper", null, List.of());
                helper.fields.put("registry", reg.registry());
                in.callFn(fn, List.of(helper));
                return V.NULL;
            }
            if (a.size() == 3 && a.get(0) instanceof V.Registry reg) {
                Object value = a.get(2) instanceof Fn fn ? in.callFn(fn, List.of()) : a.get(2);
                return record(in, reg.registry(), a.get(1), value, c);
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj h && h.type.equals("net.minecraftforge.registries.RegisterEvent$RegisterHelper")
                && n.equals("register") && a.size() == 2) {
            return record(in, (String) h.fields.get("registry"), a.get(0), a.get(1), c);
        }
        if (owner.equals("net.minecraft.core.Registry") && t == null && n.equals("register") && a.size() == 3
                && a.get(0) instanceof V.Registry reg) {
            return record(in, reg.registry(), a.get(1), a.get(2), c);
        }
        if (t instanceof V.Holder h) {
            switch (n) {
                case "get":
                    Facts.Registration r = facts.lookup(h.registry(), h.id());
                    return r != null ? facts.resolve(in, r) : new Unknown("RegistryObject " + h.id() + " not registered");
                case "getId":
                    return parseRl(h.id());
                case "isPresent":
                    return facts.lookup(h.registry(), h.id()) != null;
                default:
                    break;
            }
        }
        if (owner.endsWith("GameData") && n.equals("checkPrefix") && a.size() >= 1) {
            if (a.get(0) instanceof String s) {
                return s.contains(":") ? parseRl(s) : new ResLoc(modId, s);
            }
            return a.get(0) instanceof Unknown u ? u : new Unknown("checkPrefix(" + Interpreter.describe(a.get(0)) + ")");
        }

        if (t == null && owner.equals("net.minecraft.core.NonNullList")) {
            if (n.equals("withSize") && a.size() == 2 && a.get(0) instanceof Integer size && size >= 0 && size < 100_000) {
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    items.add(a.get(1));
                }
                return new Seq("list", items);
            }
            if (n.equals("create")) {
                return new Seq("list", new ArrayList<>());
            }
        }

        // -- tags ------------------------------------------------------------------------------------------
        if (t == null && n.equals("create") && a.size() == 1 && a.get(0) instanceof ResLoc rl) {
            if (owner.equals("net.minecraft.tags.ItemTags")) {
                return new V.TagRef("item", rl.toString());
            }
            if (owner.equals("net.minecraft.tags.BlockTags")) {
                return new V.TagRef("block", rl.toString());
            }
            if (owner.equals("net.minecraft.tags.FluidTags")) {
                return new V.TagRef("fluid", rl.toString());
            }
        }
        if (owner.equals("net.minecraft.tags.TagKey") && t == null && n.equals("create") && a.size() == 2
                && a.get(0) instanceof V.Registry reg && a.get(1) instanceof ResLoc rl) {
            return new V.TagRef(reg.registry(), rl.toString());
        }
        if (t instanceof V.TagRef tag) {
            if (n.equals("location")) {
                return parseRl(tag.id());
            }
            return NOT_HANDLED;
        }

        // -- resource locations ----------------------------------------------------------------------------
        if (t instanceof ResLoc rl) {
            return switch (n) {
                case "getPath" -> rl.path();
                case "getNamespace" -> rl.namespace();
                case "toString" -> rl.toString();
                case "equals" -> rl.equals(a.get(0));
                default -> NOT_HANDLED;
            };
        }
        if (owner.equals(RL) && t == null && (n.equals("tryParse") || n.equals("of")) && a.size() >= 1
                && a.get(0) instanceof String s) {
            return parseRl(s);
        }

        // -- indexed vanilla enums (DyeColor...) ---------------------------------------------------------------
        if (t == null && in.vanilla().enumConstants(owner) != null && n.equals("values") && a.isEmpty()) {
            List<Object> l = new ArrayList<>();
            for (String ec : in.vanilla().enumConstants(owner)) {
                l.add(new Sym(owner, ec.substring(0, ec.indexOf('='))));
            }
            return new Seq("array", l);
        }
        if (t instanceof Sym es && in.vanilla().enumConstants(es.owner()) != null && a.isEmpty()) {
            List<String> consts = in.vanilla().enumConstants(es.owner());
            for (int i = 0; i < consts.size(); i++) {
                String ec = consts.get(i);
                if (ec.startsWith(es.name() + "=")) {
                    String ser = ec.substring(ec.indexOf('=') + 1);
                    Integer id = null;
                    if (ser.contains("#")) {
                        id = Integer.parseInt(ser.substring(ser.indexOf('#') + 1));
                        ser = ser.substring(0, ser.indexOf('#'));
                    }
                    Integer fid = id;
                    String fser = ser;
                    int ord = i;
                    return switch (n) {
                        case "getName", "getSerializedName", "toString" -> fser;
                        case "name" -> es.name();
                        case "ordinal" -> ord;
                        case "getId" -> fid == null ? (Object) new Unknown("enum id") : fid;
                        default -> NOT_HANDLED;
                    };
                }
            }
        }

        // -- directions --------------------------------------------------------------------------------------
        if (t instanceof Sym s && s.owner().equals(DIRECTION)) {
            return directionCall(dirIndex(t), n, a);
        }
        if (t == null && owner.equals(DIRECTION)) {
            switch (n) {
                case "values": {
                    List<Object> l = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        l.add(dir(i));
                    }
                    return new Seq("array", l);
                }
                case "from3DDataValue":
                    return a.get(0) instanceof Integer i ? dir(Math.floorMod(i, 6)) : NOT_HANDLED;
                case "from2DDataValue":
                    if (a.get(0) instanceof Integer i) {
                        String[] h = {"SOUTH", "WEST", "NORTH", "EAST"};
                        return new Sym(DIRECTION, h[Math.floorMod(i, 4)]);
                    }
                    return NOT_HANDLED;
                default:
                    break;
            }
        }
        if (t instanceof Sym s && s.owner().equals("net.minecraft.core.Direction$Plane")) {
            if (n.equals("iterator") || n.equals("stream")) {
                return planeSeq(s.name());
            }
            return NOT_HANDLED;
        }
        if (t instanceof Sym s && s.owner().equals(DIRECTION + "$Axis")) {
            return switch (n) {
                case "getName", "toString", "getSerializedName" -> s.name().toLowerCase(Locale.ROOT);
                case "isHorizontal" -> !s.name().equals("Y");
                case "isVertical" -> s.name().equals("Y");
                default -> NOT_HANDLED;
            };
        }

        // -- positions -------------------------------------------------------------------------------------
        if (t instanceof Pos p) {
            return posCall(p, n, a);
        }
        if (t instanceof V.MutPos m) {
            return mutCall(m, n, a);
        }
        if (t == null && owner.equals(BLOCK_POS)) {
            if (n.equals("of") && a.size() == 1 && a.get(0) instanceof Long l) {
                return unpack(l);
            }
            if (n.equals("asLong") && a.size() == 3) {
                int[] xyz = ints(a);
                return xyz == null ? firstUnknownOr(a, "asLong") : (Object) pack(xyz[0], xyz[1], xyz[2]);
            }
            if (n.equals("offset") && a.size() == 2 && a.get(0) instanceof Long l && dirIndex(a.get(1)) >= 0) {
                int d = dirIndex(a.get(1));
                Pos p = unpack(l);
                return pack(p.x() + STEP[d][0], p.y() + STEP[d][1], p.z() + STEP[d][2]);
            }
            if (n.equals("offset") && a.size() == 4 && a.get(0) instanceof Long l) {
                int[] d = ints(a.subList(1, 4));
                if (d != null) {
                    Pos p = unpack(l);
                    return pack(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                }
            }
            if (n.equals("getX") && a.size() == 1 && a.get(0) instanceof Long l) {
                return unpack(l).x();
            }
            if (n.equals("getY") && a.size() == 1 && a.get(0) instanceof Long l) {
                return unpack(l).y();
            }
            if (n.equals("getZ") && a.size() == 1 && a.get(0) instanceof Long l) {
                return unpack(l).z();
            }
        }

        // -- blocks and states -----------------------------------------------------------------------------
        if (t instanceof State st) {
            return stateCall(in, st, n, a);
        }
        if (n.equals("asItem") && t instanceof Obj o && isKind(in, o, "net.minecraft.world.item.")) {
            return o;
        }
        if (n.equals("asItem") && (t instanceof V.RegRef r && r.registry().equals("item")
                || t instanceof V.Holder h && h.registry().equals("item"))) {
            return t;
        }
        if (isBlockValue(t) && n.equals("defaultBlockState")) {
            return defaultState(t);
        }
        if (n.equals("asItem") && isBlockValue(t)) {
            return new V.AsItem(t);
        }
        if (owner.equals("net.minecraft.world.item.Item") && t == null && n.equals("byBlock") && a.size() == 1) {
            return new V.AsItem(a.get(0));
        }
        if (n.equals("$field:stateDefinition") && isBlockValue(t)) {
            VObj sd = new VObj("net.minecraft.world.level.block.state.StateDefinition", null, List.of());
            sd.fields.put("block", t);
            return sd;
        }
        if (n.equals("getStateDefinition") && isBlockValue(t)) {
            VObj sd = new VObj("net.minecraft.world.level.block.state.StateDefinition", null, List.of());
            sd.fields.put("block", t);
            return sd;
        }
        if (t instanceof VObj sd && sd.type.equals("net.minecraft.world.level.block.state.StateDefinition")
                && n.equals("any")) {
            return new State(sd.fields.get("block"), new LinkedHashMap<>());
        }
        if (t == null && n.equals("create") && owner.startsWith("net.minecraft.world.level.block.state.properties.")) {
            return createProperty(in, owner, a);
        }
        if (t instanceof V.Prop p) {
            return switch (n) {
                case "getName" -> p.name();
                case "getPossibleValues" -> new Seq("list", new ArrayList<>(p.values()));
                default -> NOT_HANDLED;
            };
        }

        // -- items -----------------------------------------------------------------------------------------
        if (t instanceof V.Stack s) {
            return switch (n) {
                case "getItem" -> s.item();
                case "getCount" -> s.count();
                case "copy" -> s;
                case "isEmpty" -> s.count() instanceof Integer i ? (Object) (i <= 0) : new Unknown("count");
                default -> NOT_HANDLED;
            };
        }

        // -- builders --------------------------------------------------------------------------------------
        if (t == null && isBuilderFactory(owner, n)) {
            return new VObj(owner, n, a);
        }
        if (t instanceof VObj b && BUILDERS.contains(b.type)) {
            if (b.type.endsWith("FoodProperties$Builder") && n.equals("build")) {
                VObj food = new VObj("net.minecraft.world.food.FoodProperties", "build", List.of(b));
                return food;
            }
            if (b.type.endsWith("BlockEntityType$Builder") && n.equals("build")) {
                return new VObj("net.minecraft.world.level.block.entity.BlockEntityType", "build", List.of(b));
            }
            if (b.type.endsWith("SimpleChannel$MessageBuilder") && n.equals("add")) {
                facts.messages.add(new Facts.Message(b.fields.get("channel"), b.args.size() > 1 ? b.args.get(1) : null,
                        b.args.isEmpty() ? null : b.args.get(0), callArg(b, "encoder"), callArg(b, "decoder"),
                        callArg(b, "consumerMainThread", "consumerNetworkThread", "consumer"),
                        b.args.size() > 2 ? b.args.get(2) : null, c.site(), in.activeConditions()));
                return V.NULL;
            }
            if (b.type.endsWith("NetworkRegistry$ChannelBuilder") && n.equals("simpleChannel")) {
                VObj ch = new VObj("net.minecraftforge.network.simple.SimpleChannel", "builder", List.of());
                ch.fields.put("name", b.args.isEmpty() ? null : b.args.get(0));
                return ch;
            }
            b.calls.add(new V.Call(n, List.copyOf(a)));
            return b;
        }

        // -- networking ------------------------------------------------------------------------------------
        if (t == null && owner.endsWith("NetworkRegistry") && n.equals("newSimpleChannel")) {
            VObj ch = new VObj("net.minecraftforge.network.simple.SimpleChannel", "new", a);
            ch.fields.put("name", a.isEmpty() ? null : a.get(0));
            return ch;
        }
        if (t instanceof VObj ch && ch.type.equals("net.minecraftforge.network.simple.SimpleChannel")
                || owner.endsWith("SimpleChannel") && (t instanceof Unknown)) {
            if (n.equals("registerMessage") && a.size() >= 5) {
                facts.messages.add(new Facts.Message(t, a.get(0), a.get(1), a.get(2), a.get(3), a.get(4),
                        a.size() > 5 ? a.get(5) : null, c.site(), in.activeConditions()));
                return V.NULL;
            }
            if (n.equals("messageBuilder") && a.size() >= 2) {
                VObj mb = new VObj("net.minecraftforge.network.simple.SimpleChannel$MessageBuilder", "messageBuilder", a);
                mb.fields.put("channel", t);
                return mb;
            }
        }

        // -- menus -----------------------------------------------------------------------------------------
        if (t == null && owner.endsWith("IForgeMenuType") && n.equals("create") && a.size() == 1) {
            VObj mt = new VObj("net.minecraft.world.inventory.MenuType", "create", a);
            return mt;
        }
        if (t == null && owner.endsWith("MenuScreens") && n.equals("register") && a.size() == 2) {
            facts.screens.add(new Facts.ScreenBinding(a.get(0), a.get(1), c.site()));
            return V.NULL;
        }
        if (t instanceof Obj o && n.equals("addSlot") && a.size() == 1) {
            return a.get(0); // recorded in vanillaCalls by the interpreter
        }
        if (t instanceof VObj bet && bet.type.equals("net.minecraft.world.level.block.entity.BlockEntityType")
                && n.equals("create")) {
            return beInstance(bet);
        }
        if (n.equals("create") && t instanceof Obj o && in.isSameOrSub(o.type,
                "net.minecraft.world.level.block.entity.BlockEntityType")) {
            return beInstance(o);
        }
        if (n.equals("create") && t instanceof Obj o && "net.minecraft.world.level.block.entity.BlockEntityType"
                .equals(o.vanillaSuper)) {
            return beInstance(o);
        }
        return NOT_HANDLED;
    }

    /** Marker value for "an instance of the block entity type X". */
    private Object beInstance(Object type) {
        VObj be = new VObj("net.minecraft.world.level.block.entity.BlockEntity", "create", List.of());
        be.fields.put("type", type);
        return be;
    }

    private static Object callArg(VObj b, String... names) {
        for (String nm : names) {
            V.Call c = b.lastCall(nm);
            if (c != null && !c.args().isEmpty()) {
                return c.args().get(0);
            }
        }
        return null;
    }

    private Object record(Interpreter in, String registry, Object nameOrRl, Object value, Call c) {
        Object id;
        if (nameOrRl instanceof ResLoc rl) {
            id = rl.toString();
        } else if (nameOrRl instanceof String s) {
            id = s.contains(":") ? s : modId + ":" + s;
        } else if (nameOrRl instanceof Unknown u) {
            id = u;
        } else {
            id = new Unknown("registry name " + Interpreter.describe(nameOrRl));
        }
        recordId(in, registry, id, value, c);
        return value;
    }

    private void recordId(Interpreter in, String registry, Object id, Object value, Call c) {
        String sid = id instanceof String s ? s : null;
        String why = id instanceof Unknown u ? u.reason() : sid == null ? "id not a string" : null;
        if (why != null) {
            why = why + " [in " + in.callerContext(4) + "]";
        }
        facts.register(new Facts.Registration(registry, sid, why, value, c.site(), in.activeConditions()));
    }

    static ResLoc parseRl(String s) {
        int c = s.indexOf(':');
        return c < 0 ? new ResLoc("minecraft", s) : new ResLoc(s.substring(0, c), s.substring(c + 1));
    }

    private static boolean isBuilderFactory(String owner, String n) {
        return switch (owner) {
            case "net.minecraft.world.level.block.state.BlockBehaviour$Properties" -> n.equals("of") || n.equals("copy");
            case "net.minecraftforge.fluids.FluidType$Properties" -> n.equals("create");
            case "net.minecraft.world.level.block.entity.BlockEntityType$Builder" -> n.equals("of");
            case "net.minecraftforge.network.NetworkRegistry$ChannelBuilder" -> n.equals("named");
            default -> false;
        };
    }

    /** True if the source object's vanilla supertypes live in the given package prefix. */
    public static boolean isKind(Interpreter in, Obj o, String packagePrefix) {
        for (String s : in.supertypeNames(o.type)) {
            if (s.startsWith(packagePrefix)) {
                return true;
            }
        }
        return o.vanillaSuper != null && o.vanillaSuper.startsWith(packagePrefix);
    }

    public static boolean isBlockValue(Object t) {
        return t instanceof Obj || t instanceof VObj vo && !BUILDERS.contains(vo.type) || t instanceof V.RegRef r
                && r.registry().equals("block") || t instanceof V.Holder h && h.registry().equals("block");
    }

    /** Marker property of a block's default state (dropped by setValue). */
    public static final String DEFAULT = "$default";

    private Object defaultState(Object block) {
        if (block instanceof Obj o) {
            for (int i = o.vanillaCalls.size() - 1; i >= 0; i--) {
                V.Call call = o.vanillaCalls.get(i);
                if (call.name().equals("registerDefaultState") && call.args().size() == 1
                        && call.args().get(0) instanceof State st) {
                    Map<String, Object> props = new LinkedHashMap<>(st.props());
                    props.put(DEFAULT, true);
                    return new State(block, props);
                }
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(DEFAULT, true);
        return new State(block, props);
    }

    private Object stateCall(Interpreter in, State st, String n, List<Object> a) {
        switch (n) {
            case "setValue":
                if (a.size() == 2) {
                    Map<String, Object> props = new LinkedHashMap<>(st.props());
                    props.remove(DEFAULT);
                    if (a.get(0) instanceof V.Prop p) {
                        props.put(p.name(), a.get(1));
                    } else {
                        props.put("?" + props.size(), a.get(0) instanceof Unknown u ? u
                                : new Unknown("property " + Interpreter.describe(a.get(0))));
                    }
                    return new State(st.block(), props);
                }
                return NOT_HANDLED;
            case "getValue":
                if (a.size() == 1 && a.get(0) instanceof V.Prop p && st.props().containsKey(p.name())) {
                    return st.props().get(p.name());
                }
                return new Unknown("state value " + Interpreter.describe(a.isEmpty() ? null : a.get(0)));
            case "getBlock":
                return st.block();
            case "defaultBlockState":
                return defaultState(st.block());
            case "is":
                if (a.size() == 1) {
                    Boolean same = sameBlock(st.block(), a.get(0));
                    return same == null ? new Unknown("block identity") : same;
                }
                return NOT_HANDLED;
            case "hasProperty":
                return a.size() == 1 && a.get(0) instanceof V.Prop p && st.props().containsKey(p.name()) ? (Object) true
                        : new Unknown("hasProperty");
            default:
                return NOT_HANDLED;
        }
    }

    private Boolean sameBlock(Object a, Object b) {
        if (a == b) {
            return true;
        }
        Facts.Registration ra = facts.of(a);
        Facts.Registration rb = facts.of(b);
        String ia = ra != null ? ra.id() : a instanceof V.RegRef r ? r.id() : null;
        String ib = rb != null ? rb.id() : b instanceof V.RegRef r ? r.id() : null;
        if (ia != null && ib != null) {
            return ia.equals(ib);
        }
        return null;
    }

    private Object createProperty(Interpreter in, String owner, List<Object> a) {
        if (a.isEmpty() || !(a.get(0) instanceof String name)) {
            return a.isEmpty() ? new Unknown("property without name") : a.get(0) instanceof Unknown u ? u
                    : new Unknown("property name " + Interpreter.describe(a.get(0)));
        }
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        switch (simple) {
            case "BooleanProperty":
                return new V.Prop(name, "boolean", List.of("true", "false"));
            case "IntegerProperty": {
                if (a.size() == 3 && a.get(1) instanceof Integer min && a.get(2) instanceof Integer max && max >= min
                        && max - min < 1000) {
                    List<String> vals = new ArrayList<>();
                    for (int i = min; i <= max; i++) {
                        vals.add(Integer.toString(i));
                    }
                    return new V.Prop(name, "integer", vals);
                }
                return new Unknown("IntegerProperty range");
            }
            case "DirectionProperty": {
                List<String> vals = new ArrayList<>();
                List<Object> rest = a.subList(1, a.size());
                if (rest.size() == 1 && rest.get(0) instanceof Seq s) {
                    rest = s.items;
                } else if (rest.size() == 1 && rest.get(0) instanceof Sym p
                        && p.owner().equals("net.minecraft.core.Direction$Plane")) {
                    rest = planeSeq(p.name()).items;
                }
                if (rest.isEmpty()) {
                    for (String d : DIRS) {
                        vals.add(d.toLowerCase(Locale.ROOT));
                    }
                }
                for (Object o : rest) {
                    int d = dirIndex(o);
                    if (d < 0) {
                        return new Unknown("DirectionProperty values " + Interpreter.describe(o));
                    }
                    vals.add(DIRS[d].toLowerCase(Locale.ROOT));
                }
                return new V.Prop(name, "direction", vals);
            }
            case "EnumProperty": {
                if (a.size() >= 2 && a.get(1) instanceof V.ClassV cls) {
                    CtType<?> et = in.source().type(cls.qualifiedName());
                    if (et instanceof CtEnum<?> en) {
                        in.classInit(en);
                        List<String> vals = new ArrayList<>();
                        for (var ev : en.getEnumValues()) {
                            Object c = in.getStatic(en.getQualifiedName(), ev.getSimpleName());
                            Object sn = c instanceof Obj o ? in.invokeVirtual(o, "getSerializedName", List.of(), null)
                                    : null;
                            if (!(sn instanceof String s)) {
                                return new Unknown("enum serialized name of " + ev.getSimpleName());
                            }
                            vals.add(s);
                        }
                        if (a.size() == 2) {
                            return new V.Prop(name, "enum", vals);
                        }
                        return new Unknown("EnumProperty with value filter");
                    }
                    return new Unknown("EnumProperty of vanilla enum " + cls.qualifiedName());
                }
                return new Unknown("EnumProperty class");
            }
            default:
                return new Unknown("property type " + simple);
        }
    }

    static Seq planeSeq(String plane) {
        List<Object> l = new ArrayList<>();
        if (plane.equals("HORIZONTAL")) {
            for (String d : new String[] {"NORTH", "EAST", "SOUTH", "WEST"}) {
                l.add(new Sym(DIRECTION, d));
            }
        } else {
            l.add(new Sym(DIRECTION, "UP"));
            l.add(new Sym(DIRECTION, "DOWN"));
        }
        return new Seq("list", l);
    }

    private Object directionCall(int d, String n, List<Object> a) {
        if (d < 0) {
            return NOT_HANDLED;
        }
        switch (n) {
            case "getStepX":
                return STEP[d][0];
            case "getStepY":
                return STEP[d][1];
            case "getStepZ":
                return STEP[d][2];
            case "getNormal":
                return new Pos(STEP[d][0], STEP[d][1], STEP[d][2]);
            case "getOpposite":
                return dir(d ^ 1);
            case "get3DDataValue":
            case "ordinal":
                return d;
            case "get2DDataValue": {
                return switch (DIRS[d]) {
                    case "SOUTH" -> 0;
                    case "WEST" -> 1;
                    case "NORTH" -> 2;
                    case "EAST" -> 3;
                    default -> -1;
                };
            }
            case "getName", "getSerializedName", "toString", "name":
                return n.equals("name") ? DIRS[d] : DIRS[d].toLowerCase(Locale.ROOT);
            case "getAxis":
                return new Sym(DIRECTION + "$Axis", d < 2 ? "Y" : d < 4 ? "Z" : "X");
            case "getClockWise":
            case "getCounterClockWise": {
                String[] cw = {"NORTH", "EAST", "SOUTH", "WEST"};
                for (int i = 0; i < 4; i++) {
                    if (cw[i].equals(DIRS[d])) {
                        int j = n.equals("getClockWise") ? (i + 1) % 4 : (i + 3) % 4;
                        return new Sym(DIRECTION, cw[j]);
                    }
                }
                return new Unknown("rotation of vertical direction");
            }
            case "equals":
                return a.size() == 1 && V.same(dir(d), a.get(0));
            default:
                return NOT_HANDLED;
        }
    }

    private Object posCall(Pos p, String n, List<Object> a) {
        switch (n) {
            case "getX":
                return p.x();
            case "getY":
                return p.y();
            case "getZ":
                return p.z();
            case "asLong":
                return pack(p.x(), p.y(), p.z());
            case "immutable":
                return p;
            case "mutable":
                return new V.MutPos(p.x(), p.y(), p.z());
            case "equals":
                return a.size() == 1 && pos(a.get(0)) != null && pos(a.get(0)).equals(p);
            case "hashCode":
                return p.hashCode();
            default:
                break;
        }
        Pos r = moved(p, n, a);
        return r == null ? NOT_HANDLED : r;
    }

    /** offset/relative/above/below/north... on a position; null if not a move. */
    static Pos moved(Pos p, String n, List<Object> a) {
        int amount = 1;
        switch (n) {
            case "offset":
                if (a.size() == 3) {
                    int[] d = ints(a);
                    return d == null ? null : p.offset(d[0], d[1], d[2]);
                }
                if (a.size() == 1 && pos(a.get(0)) != null) {
                    Pos o = pos(a.get(0));
                    return p.offset(o.x(), o.y(), o.z());
                }
                return null;
            case "subtract":
                if (a.size() == 1 && pos(a.get(0)) != null) {
                    Pos o = pos(a.get(0));
                    return p.offset(-o.x(), -o.y(), -o.z());
                }
                return null;
            case "relative":
                if (a.isEmpty() || dirIndex(a.get(0)) < 0) {
                    return null;
                }
                if (a.size() == 2) {
                    if (!(a.get(1) instanceof Integer i)) {
                        return null;
                    }
                    amount = i;
                }
                int d = dirIndex(a.get(0));
                return p.offset(STEP[d][0] * amount, STEP[d][1] * amount, STEP[d][2] * amount);
            case "above", "below", "north", "south", "west", "east":
                if (a.size() == 1) {
                    if (!(a.get(0) instanceof Integer i)) {
                        return null;
                    }
                    amount = i;
                }
                int di = switch (n) {
                    case "below" -> 0;
                    case "above" -> 1;
                    case "north" -> 2;
                    case "south" -> 3;
                    case "west" -> 4;
                    default -> 5;
                };
                return p.offset(STEP[di][0] * amount, STEP[di][1] * amount, STEP[di][2] * amount);
            default:
                return null;
        }
    }

    private Object mutCall(V.MutPos m, String n, List<Object> a) {
        switch (n) {
            case "set": {
                if (a.size() == 3) {
                    int[] d = ints(a);
                    if (d == null) {
                        return firstUnknownOr(a, "set");
                    }
                    m.x = d[0];
                    m.y = d[1];
                    m.z = d[2];
                    return m;
                }
                if (a.size() == 1 && a.get(0) instanceof Long l) {
                    Pos p = unpack(l);
                    m.x = p.x();
                    m.y = p.y();
                    m.z = p.z();
                    return m;
                }
                if (a.size() == 1 && pos(a.get(0)) != null) {
                    Pos p = pos(a.get(0));
                    m.x = p.x();
                    m.y = p.y();
                    m.z = p.z();
                    return m;
                }
                return firstUnknownOr(a, "MutableBlockPos.set");
            }
            case "setX", "setY", "setZ":
                if (a.size() == 1 && a.get(0) instanceof Integer i) {
                    if (n.equals("setX")) {
                        m.x = i;
                    } else if (n.equals("setY")) {
                        m.y = i;
                    } else {
                        m.z = i;
                    }
                    return m;
                }
                return firstUnknownOr(a, n);
            case "move": {
                Pos cur = new Pos(m.x, m.y, m.z);
                Pos r = a.size() == 3 ? moved(cur, "offset", a) : moved(cur, "relative", a);
                if (r == null) {
                    return firstUnknownOr(a, "MutableBlockPos.move");
                }
                m.x = r.x();
                m.y = r.y();
                m.z = r.z();
                return m;
            }
            case "setWithOffset":
                if (a.size() == 2 && pos(a.get(0)) != null && dirIndex(a.get(1)) >= 0) {
                    Pos r = moved(pos(a.get(0)), "relative", List.of(a.get(1)));
                    m.x = r.x();
                    m.y = r.y();
                    m.z = r.z();
                    return m;
                }
                return firstUnknownOr(a, "setWithOffset");
            default:
                return posCall(new Pos(m.x, m.y, m.z), n, a);
        }
    }
}
