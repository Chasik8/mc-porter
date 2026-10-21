package dev.porter.extract.forge1192.interp;

import dev.porter.extract.forge1192.interp.V.Unknown;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.List;
import java.util.Map;
import spoon.reflect.declaration.CtElement;

/**
 * NBT recorders (CompoundTag/ListTag puts, ContainerHelper, FluidTank), level mocks for side-dependent code
 * (getTicker) and BaseEntityBlock#createTickerHelper.
 */
public final class DataBuiltins implements Builtins {
    public static final String COMPOUND = "net.minecraft.nbt.CompoundTag";
    public static final String LIST = "net.minecraft.nbt.ListTag";
    public static final String LEVEL = "net.minecraft.world.level.Level";
    public static final String BUF = "net.minecraft.network.FriendlyByteBuf";

    /** Records every call made on it (consumers passed into initializeClient and similar). */
    public static final String RECORDER = "porter.Recorder";

    public static VObj recorder() {
        return new VObj(RECORDER, "mock", List.of());
    }

    public static VObj buffer() {
        return new VObj(BUF, "mock", List.of());
    }

    /** CompoundTag put method → IR nbt type. */
    static final Map<String, String> PUTS = Map.ofEntries(
            Map.entry("putByte", "byte"), Map.entry("putShort", "short"), Map.entry("putInt", "int"),
            Map.entry("putLong", "long"), Map.entry("putFloat", "float"), Map.entry("putDouble", "double"),
            Map.entry("putString", "string"), Map.entry("putBoolean", "boolean"),
            Map.entry("putIntArray", "intArray"), Map.entry("putByteArray", "byteArray"),
            Map.entry("putLongArray", "longArray"), Map.entry("putUUID", "intArray"));

    public static VObj compound() {
        return new VObj(COMPOUND, "new", List.of());
    }

    /** A Level whose isClientSide is fixed (for side-dependent code paths). */
    public static VObj level(boolean client) {
        VObj l = new VObj(LEVEL, "mock", List.of());
        l.fields.put("isClientSide", client);
        return l;
    }

    @Override
    public Object construct(Interpreter in, String type, List<Object> args, CtElement site) {
        if (type.equals(COMPOUND) || type.equals(LIST)) {
            return new VObj(type, "new", args);
        }
        return NOT_HANDLED;
    }

    @Override
    public Object invoke(Interpreter in, Call c) {
        Object t = c.target();
        String n = c.name();
        List<Object> a = c.args();
        if (t instanceof VObj vo && vo.type.equals(COMPOUND)) {
            if (n.startsWith("put") && a.size() == 2) {
                vo.calls.add(new V.Call(n, List.copyOf(a)));
                return V.NULL;
            }
            if (n.startsWith("get") || n.equals("contains")) {
                return new Unknown("reading NBT");
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj vo && vo.type.equals(LIST) && n.equals("add")) {
            vo.calls.add(new V.Call(n, List.copyOf(a)));
            return true;
        }
        if (t == null && c.owner() != null && c.owner().endsWith("ContainerHelper") && n.equals("saveAllItems")
                && !a.isEmpty() && a.get(0) instanceof VObj tag && tag.type.equals(COMPOUND)) {
            // ContainerHelper.saveAllItems writes the stacks under "Items"
            tag.calls.add(new V.Call("$itemStacks", List.of("Items")));
            return tag;
        }
        if (n.equals("writeToNBT") && a.size() == 1 && a.get(0) instanceof VObj tag && tag.type.equals(COMPOUND)) {
            tag.calls.add(new V.Call("$fluidStack", List.of()));
            return tag;
        }
        if (n.equals("serializeNBT") && a.isEmpty() && t != null) {
            VObj tag = compound();
            tag.calls.add(new V.Call("$serialized", List.of()));
            return tag;
        }
        if (t instanceof VObj rec && rec.type.equals(RECORDER)) {
            rec.calls.add(new V.Call(n, List.copyOf(a)));
            return V.NULL;
        }
        if (t instanceof VObj buf && buf.type.equals(BUF)) {
            if (n.startsWith("write") && !a.isEmpty()) {
                String argName = null;
                if (c.site() != null && !c.site().getArguments().isEmpty()) {
                    spoon.reflect.code.CtExpression<?> e = c.site().getArguments().get(0);
                    if (e instanceof spoon.reflect.code.CtVariableAccess<?> va) {
                        argName = in.vanilla().official(va.getVariable().getSimpleName());
                    }
                }
                String enumType = null;
                if (n.equals("writeEnum") && a.get(0) instanceof V.Obj eo && eo.enumName != null) {
                    enumType = eo.type.getQualifiedName();
                } else if (n.equals("writeEnum") && c.site() != null && !c.site().getArguments().isEmpty()
                        && c.site().getArguments().get(0).getType() != null) {
                    enumType = c.site().getArguments().get(0).getType().getQualifiedName();
                }
                List<Object> rec = new java.util.ArrayList<>();
                rec.add(argName == null ? V.NULL : argName);
                rec.add(enumType == null ? V.NULL : enumType);
                rec.addAll(a.subList(1, a.size()));
                buf.calls.add(new V.Call(n, rec));
                return buf;
            }
            if (n.startsWith("read")) {
                return new Unknown("reading network buffer");
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj p && p.type.equals("net.minecraft.world.entity.player.Player")) {
            if (n.equals("getInventory") || n.equals("$field:inventory")) {
                return p.fields.get("inventory");
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj inv && inv.type.equals("net.minecraft.world.entity.player.Inventory")) {
            if (n.equals("$field:player")) {
                return inv.fields.getOrDefault("player", new Unknown("player"));
            }
            return NOT_HANDLED;
        }
        if (t instanceof VObj l && l.type.equals(LEVEL)) {
            if (n.equals("isClientSide") || n.equals("$field:isClientSide")) {
                return l.fields.get("isClientSide");
            }
            return NOT_HANDLED;
        }
        if (n.equals("createTickerHelper") && a.size() == 3) {
            Object x = a.get(0);
            Object y = a.get(1);
            if (x instanceof Unknown u) {
                return u;
            }
            if (x == y) {
                return a.get(2);
            }
            return new Unknown("createTickerHelper type identity");
        }
        return NOT_HANDLED;
    }
}
