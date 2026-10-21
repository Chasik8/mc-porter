package dev.porter.extract.forge1192.concepts;

import dev.porter.core.ir.IrNode;
import dev.porter.core.ir.Provenance;
import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.DataBuiltins;
import dev.porter.extract.forge1192.interp.Facts;
import dev.porter.extract.forge1192.interp.Interpreter;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.extract.forge1192.interp.V.VObj;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtType;

/**
 * {@code packet}: SimpleChannel registrations (registerMessage / messageBuilder...add). Wire fields come from
 * running the encoder on a recording FriendlyByteBuf; the direction from the optional NetworkDirection (absent:
 * the message is accepted both ways).
 */
public final class PacketExtractor {
    public static final String ID = "packet/simple-channel";

    static final Map<String, String> WRITES = Map.ofEntries(
            Map.entry("writeBoolean", "boolean"), Map.entry("writeByte", "byte"), Map.entry("writeShort", "short"),
            Map.entry("writeInt", "int"), Map.entry("writeVarInt", "varInt"), Map.entry("writeLong", "long"),
            Map.entry("writeVarLong", "varLong"), Map.entry("writeFloat", "float"), Map.entry("writeDouble", "double"),
            Map.entry("writeUtf", "string"), Map.entry("writeUUID", "uuid"),
            Map.entry("writeResourceLocation", "resourceId"), Map.entry("writeBlockPos", "blockPos"),
            Map.entry("writeItem", "itemStack"), Map.entry("writeItemStack", "itemStack"),
            Map.entry("writeFluidStack", "fluidStack"), Map.entry("writeNbt", "nbt"),
            Map.entry("writeByteArray", "bytes"), Map.entry("writeEnum", "enum"), Map.entry("writeVarIntArray", "bytes"));

    /** Pack hook: a message class whose fields come from a method other than the registered encoder. */
    public interface EncoderOverride {
        /** Returns the encode method to run on a blank message instance, or null. */
        String encodeMethodFor(ExtractContext ctx, CtType<?> messageClass);
    }

    private PacketExtractor() {
    }

    public static void extract(ExtractContext ctx, List<EncoderOverride> overrides) {
        Set<String> seen = new HashSet<>();
        for (Facts.Message m : ctx.facts.messages) {
            Provenance prov = ctx.prov(m.site(), ID);
            String cls = m.messageClass() instanceof V.ClassV c ? c.qualifiedName() : null;
            String channel = channelId(m.channel());
            Integer disc = Vals.integer(m.index());
            if (cls == null || channel == null) {
                ctx.sink.unknown("packet", null, "message class or channel not computable", Interpreter.snippet(m.site()),
                        prov);
                continue;
            }
            String simple = cls.substring(Math.max(cls.lastIndexOf('.'), cls.lastIndexOf('$')) + 1);
            String id = channel.substring(0, channel.indexOf(':')) + ":" + snake(simple);
            if (!seen.add(id)) {
                id = id + "_" + (disc == null ? seen.size() : disc);
            }
            String direction = direction(m.direction());
            if (direction == null) {
                ctx.sink.unknown("packet", id, "network direction not computable", Interpreter.snippet(m.site()), prov);
                continue;
            }
            CtType<?> type = ctx.model.type(cls);
            if (type == null) {
                ctx.sink.unknown("packet", id, "message class is not part of the mod", cls, prov);
                continue;
            }
            VObj buf = DataBuiltins.buffer();
            Obj blank = blank(ctx, type);
            String override = null;
            for (EncoderOverride o : overrides) {
                override = override != null ? override : o.encodeMethodFor(ctx, type);
            }
            if (override != null) {
                ctx.interp.callIfDefined(blank, override, List.of(buf));
            } else if (m.encoder() instanceof V.Fn fn) {
                ctx.interp.callFn(fn, List.of(blank, buf));
            } else {
                ctx.sink.unknown("packet", id, "encoder not computable", Interpreter.snippet(m.site()), prov);
                continue;
            }
            List<IrNode.Packet.Field> fields = new ArrayList<>();
            boolean ok = true;
            Set<String> names = new HashSet<>();
            int i = 0;
            for (V.Call c : buf.calls) {
                String type1 = WRITES.get(c.name());
                if (type1 == null) {
                    ok = false;
                    break;
                }
                String name = c.args().get(0) instanceof String s && s.matches("^[A-Za-z_][A-Za-z0-9_]*$") ? s
                        : "field" + i;
                if (!names.add(name)) {
                    name = name + "_" + i;
                    names.add(name);
                }
                String enumClass = type1.equals("enum") && c.args().get(1) instanceof String e && Vals.isClassName(e)
                        ? e : null;
                if (type1.equals("enum") && enumClass == null) {
                    ok = false;
                    break;
                }
                Integer max = type1.equals("string") && c.args().size() > 2 ? Vals.integer(c.args().get(2)) : null;
                fields.add(new IrNode.Packet.Field(name, type1, null, enumClass, max != null && max >= 1 ? max : null));
                i++;
            }
            boolean partial = blank.taint != null;
            if (!ok) {
                ctx.sink.unknown("packet", id, "encoder writes a value type outside the IR vocabulary", cls, prov);
                continue;
            }
            String handler = handlerClass(m.handler());
            ctx.sink.add(new IrNode.Packet(id, channel, disc, direction, cls, handler, fields, prov, partial ? 0.8 : null,
                    null));
        }
    }

    static Obj blank(ExtractContext ctx, CtType<?> type) {
        Obj o = new Obj(type);
        for (CtType<?> t = type; t != null; t = ctx.interp.superType(t)) {
            for (var f : t.getFields()) {
                if (!f.isStatic()) {
                    o.fields.putIfAbsent(f.getSimpleName(), new V.Unknown("message field " + f.getSimpleName()));
                }
            }
        }
        return o;
    }

    static String channelId(Object ch) {
        if (ch instanceof VObj vo && vo.fields.get("name") instanceof V.ResLoc rl) {
            return rl.toString();
        }
        return null;
    }

    static String direction(Object d) {
        if (d == null) {
            return "both";
        }
        Object v = d;
        if (d instanceof VObj o && o.type.equals("java.util.Optional")) {
            v = o.fields.get("value");
            if (v == null) {
                return "both";
            }
        }
        if (v instanceof V.Sym s && s.owner().endsWith("NetworkDirection")) {
            return switch (s.name()) {
                case "PLAY_TO_SERVER", "LOGIN_TO_SERVER" -> "toServer";
                case "PLAY_TO_CLIENT", "LOGIN_TO_CLIENT" -> "toClient";
                default -> null;
            };
        }
        return null;
    }

    static String handlerClass(Object h) {
        if (h instanceof V.Fn fn && fn.ref != null) {
            CtExecutableReferenceExpression<?, ?> ref = fn.ref;
            if (ref.getTarget() instanceof CtTypeAccess<?> ta) {
                String q = ta.getAccessedType().getQualifiedName();
                return Vals.isClassName(q) ? q : null;
            }
            if (fn.receiver instanceof Obj o && Vals.isClassName(o.typeName())) {
                return o.typeName();
            }
        }
        if (h instanceof V.Fn fn && fn.lambda != null && fn.cls != null && Vals.isClassName(fn.cls.getQualifiedName())) {
            return fn.cls.getQualifiedName();
        }
        return null;
    }

    public static String snake(String camel) {
        String s = camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2");
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
