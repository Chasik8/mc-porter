package dev.porter.extract.forge1192.interp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.CtType;

/**
 * Values of the extraction interpreter. Plain Java values (Integer, Long, Float, Double, Boolean, Character, String)
 * are used as they are; {@link #NULL} is Java null. Everything the interpreter cannot compute is an {@link Unknown}
 * carrying the reason — it poisons whatever uses it, so extractors never see a guessed value.
 */
public final class V {
    private V() {
    }

    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Not computable; reason is propagated into IR unknown nodes. */
    public record Unknown(String reason) {
        @Override
        public String toString() {
            return "?(" + reason + ")";
        }
    }

    public static boolean known(Object v) {
        return !(v instanceof Unknown);
    }

    public static Unknown unknown(String reason) {
        return new Unknown(reason);
    }

    /** First unknown among the values, or null. */
    public static Unknown firstUnknown(Iterable<?> values) {
        for (Object o : values) {
            if (o instanceof Unknown u) {
                return u;
            }
        }
        return null;
    }

    /** A static constant of a non-source class that has no richer model (Material.WOOD, Rarity.EPIC, MobEffectCategory.HARMFUL...). */
    public record Sym(String owner, String name) {
        @Override
        public String toString() {
            return owner.substring(owner.lastIndexOf('.') + 1) + "." + name;
        }
    }

    /** A registry entry known by id only (vanilla Blocks.STONE → block minecraft:stone). */
    public record RegRef(String registry, String id) {
    }

    /** A registry handle (ForgeRegistries.BLOCKS, ForgeRegistries.Keys.ITEMS, Registry.BLOCK_REGISTRY...). */
    public record Registry(String registry) {
    }

    /** A tag key. */
    public record TagRef(String registry, String id) {
    }

    /** {@code X.class}. */
    public record ClassV(String qualifiedName) {
    }

    /** A source enum constant without a source model is a Sym; source enum constants are {@link Obj} with enumName. */
    public record ResLoc(String namespace, String path) {
        @Override
        public String toString() {
            return namespace + ":" + path;
        }
    }

    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }
    }

    /** Mutable BlockPos. */
    public static final class MutPos {
        public int x;
        public int y;
        public int z;

        public MutPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** BlockState: a block value (Obj/VObj/RegRef) + explicitly set properties (name → value). */
    public record State(Object block, Map<String, Object> props) {
    }

    /** The item of a block ({@code block.asItem()}, {@code Item.byBlock(block)}): resolved to an item id later. */
    public record AsItem(Object block) {
    }

    /** A RegistryObject / registry holder: resolved through the recorded registrations. */
    public record Holder(String registry, String id) {
    }

    /** A registry value whose supplier has not run yet (DeferredRegister suppliers run after class init). */
    public record Lazy(Fn supplier) {
    }

    /** ItemStack. */
    public record Stack(Object item, Object count, Object nbt) {
    }

    /** A state property definition: BooleanProperty.create("lit") etc. */
    public record Prop(String name, String type, List<String> values) {
    }

    /** Method call recorded on a vanilla object or on the vanilla part of a source object. */
    public record Call(String name, List<Object> args) {
    }

    /**
     * Instance of a vanilla (non-source) class, recorded: constructor/factory arguments and the calls made on it.
     * Builder chains (Properties) mutate and return the same VObj.
     */
    public static final class VObj {
        public final String type;
        public final String factory;
        public final List<Object> args;
        public final List<Call> calls = new ArrayList<>();
        public final Map<String, Object> fields = new LinkedHashMap<>();

        public VObj(String type, String factory, List<Object> args) {
            this.type = type;
            this.factory = factory;
            this.args = new ArrayList<>(args);
        }

        public Call lastCall(String name) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).name().equals(name)) {
                    return calls.get(i);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "VObj(" + type + (factory == null ? "" : "." + factory) + ")";
        }
    }

    /** Instance of a source class (the mod's own code), interpreted. */
    public static final class Obj {
        public final CtType<?> type;
        public final Map<String, Object> fields = new LinkedHashMap<>();
        /** First non-source superclass and the arguments of the super(...) call into it. */
        public String vanillaSuper;
        public List<Object> superArgs = List.of();
        /** Calls on {@code this} that went to vanilla methods (registerDefaultState, addSlot...). */
        public final List<Call> vanillaCalls = new ArrayList<>();
        /** Set for source enum constants. */
        public String enumName;
        public int ordinal = -1;
        /** Non-null when some statement touching this object was skipped (undecidable control flow). */
        public String taint;
        /** Enclosing instance (inner classes) and captured locals (anonymous/local classes). */
        public Object outer;
        public Scope captured;
        /** The {@code new} expression that created this object (null for enum constants and mocks). */
        public spoon.reflect.declaration.CtElement site;

        public Obj(CtType<?> type) {
            this.type = type;
        }

        public String typeName() {
            return type.getQualifiedName();
        }

        @Override
        public String toString() {
            return "Obj(" + type.getSimpleName() + (enumName == null ? "" : "." + enumName) + ")";
        }
    }

    /** Lambda or method reference with its captured scope. */
    public static final class Fn {
        public final CtLambda<?> lambda;
        public final CtExecutableReferenceExpression<?, ?> ref;
        public final Object receiver; // for bound method refs (this::m, obj::m)
        public final Scope captured;
        public final Object thiz;
        public final CtType<?> cls;

        public Fn(CtLambda<?> lambda, CtExecutableReferenceExpression<?, ?> ref, Object receiver, Scope captured,
                Object thiz, CtType<?> cls) {
            this.lambda = lambda;
            this.ref = ref;
            this.receiver = receiver;
            this.captured = captured;
            this.thiz = thiz;
            this.cls = cls;
        }

        @Override
        public String toString() {
            return lambda != null ? "Fn(lambda)" : "Fn(" + ref + ")";
        }
    }

    /** A builtin functional value (e.g. Direction::getName) implemented in Java. */
    public interface NativeFn {
        Object apply(List<Object> args);
    }

    /** Lists, sets and arrays share this representation; {@code kind} tells them apart. */
    public static final class Seq {
        public final String kind; // list | set | array
        public final List<Object> items;

        public Seq(String kind, List<Object> items) {
            this.kind = kind;
            this.items = items;
        }

        @Override
        public String toString() {
            return kind + items;
        }
    }

    public static final class MapV {
        public final Map<Object, Object> map = new LinkedHashMap<>();
        public Object defaultValue = NULL;
    }

    /** Map.Entry (also fastutil entries). */
    public static final class Entry {
        public final MapV owner;
        public final Object key;

        public Entry(MapV owner, Object key) {
            this.owner = owner;
            this.key = key;
        }
    }

    /** Iterator over a snapshot. */
    public static final class Iter {
        public final List<Object> items;
        public int index;

        public Iter(List<Object> items) {
            this.items = items;
        }
    }

    /** Equality used by == on values: identity for objects, value equality for primitives/strings/symbols. */
    public static boolean same(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return na.doubleValue() == nb.doubleValue();
            }
            return na.longValue() == nb.longValue();
        }
        if (a instanceof Character ca && b instanceof Number nb) {
            return ca == nb.longValue();
        }
        if (b instanceof Character cb && a instanceof Number na) {
            return cb == na.longValue();
        }
        if (a instanceof String || a instanceof Boolean || a instanceof Character || a instanceof Sym
                || a instanceof RegRef || a instanceof Registry || a instanceof TagRef || a instanceof ClassV
                || a instanceof ResLoc || a instanceof Pos) {
            return Objects.equals(a, b);
        }
        return false;
    }
}
