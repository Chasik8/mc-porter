package dev.porter.generate.forge1192;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.lang.model.element.Modifier;

/** IR {@code packet} → message classes and a SimpleChannel (Forge 43). */
final class Network19 {
    record Codec(TypeName type, String write, String read) {
    }

    static final Map<String, Codec> SCALARS = Map.ofEntries(
            Map.entry("boolean", new Codec(TypeName.BOOLEAN, "buf.writeBoolean($L)", "buf.readBoolean()")),
            Map.entry("byte", new Codec(TypeName.BYTE, "buf.writeByte($L)", "buf.readByte()")),
            Map.entry("short", new Codec(TypeName.SHORT, "buf.writeShort($L)", "buf.readShort()")),
            Map.entry("int", new Codec(TypeName.INT, "buf.writeInt($L)", "buf.readInt()")),
            Map.entry("varInt", new Codec(TypeName.INT, "buf.writeVarInt($L)", "buf.readVarInt()")),
            Map.entry("long", new Codec(TypeName.LONG, "buf.writeLong($L)", "buf.readLong()")),
            Map.entry("varLong", new Codec(TypeName.LONG, "buf.writeVarLong($L)", "buf.readVarLong()")),
            Map.entry("float", new Codec(TypeName.FLOAT, "buf.writeFloat($L)", "buf.readFloat()")),
            Map.entry("double", new Codec(TypeName.DOUBLE, "buf.writeDouble($L)", "buf.readDouble()")),
            Map.entry("string", new Codec(ClassName.get(String.class), "buf.writeUtf($L)", "buf.readUtf()")),
            Map.entry("uuid", new Codec(ClassName.get(java.util.UUID.class), "buf.writeUUID($L)", "buf.readUUID()")),
            Map.entry("resourceId", new Codec(T.RL, "buf.writeResourceLocation($L)", "buf.readResourceLocation()")),
            Map.entry("blockPos", new Codec(T.BLOCK_POS, "buf.writeBlockPos($L)", "buf.readBlockPos()")),
            Map.entry("direction", new Codec(T.DIRECTION, "buf.writeEnum($L)", "buf.readEnum(net.minecraft.core.Direction.class)")),
            Map.entry("itemStack", new Codec(T.ITEM_STACK, "buf.writeItem($L)", "buf.readItem()")),
            Map.entry("fluidStack", new Codec(T.FLUID_STACK, "buf.writeFluidStack($L)", "buf.readFluidStack()")),
            Map.entry("nbt", new Codec(T.COMPOUND, "buf.writeNbt($L)", "buf.readNbt()")),
            Map.entry("bytes", new Codec(ArrayTypeName.of(TypeName.BYTE), "buf.writeByteArray($L)", "buf.readByteArray()")),
            Map.entry("enum", new Codec(TypeName.INT, "buf.writeVarInt($L)", "buf.readVarInt()")));

    private final Names19 n;
    private final GenContext ctx;

    Network19(Names19 n) {
        this.n = n;
        this.ctx = n.ctx;
    }

    void run() {
        List<IrNode.Packet> packets = new ArrayList<>(ctx.ir.all(IrNode.Packet.class));
        Map<String, Integer> disc = new TreeMap<>();
        TreeSet<Integer> used = new TreeSet<>();
        for (IrNode.Packet p : packets) {
            if (p.discriminator() != null && used.add(p.discriminator())) {
                disc.put(p.id(), p.discriminator());
            }
        }
        int d = 0;
        for (IrNode.Packet p : packets) {
            if (!disc.containsKey(p.id())) {
                while (used.contains(d)) {
                    d++;
                }
                used.add(d);
                disc.put(p.id(), d);
            }
        }
        TreeSet<String> channels = new TreeSet<>();
        packets.forEach(p -> channels.add(p.channel() != null ? p.channel() : ctx.options.modId() + ":main"));
        String channel = channels.first();
        if (channels.size() > 1) {
            ctx.warn("channel-merged", channel, "IR channels " + channels + " merged into one SimpleChannel");
        }
        MethodSpec.Builder init = MethodSpec.methodBuilder("init").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Registers all messages (common setup).\n");
        for (IrNode.Packet p : packets) {
            message(p);
            handler(p);
            String dir = switch (p.direction()) {
                case "toServer" -> ", $T.PLAY_TO_SERVER";
                case "toClient" -> ", $T.PLAY_TO_CLIENT";
                default -> "";
            };
            String stmt = "CHANNEL.messageBuilder($T.class, $L" + dir + ").encoder($T::encode).decoder($T::new).consumerMainThread($T::handle).add()";
            if (dir.isEmpty()) {
                init.addStatement(stmt, n.message(p.id()), disc.get(p.id()), n.message(p.id()), n.message(p.id()), n.handler(p));
            } else {
                init.addStatement(stmt, n.message(p.id()), disc.get(p.id()), T.NETWORK_DIRECTION, n.message(p.id()), n.message(p.id()), n.handler(p));
            }
        }
        TypeSpec t = TypeSpec.classBuilder(n.network).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(String.class, "PROTOCOL", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", "1").build())
                .addField(FieldSpec.builder(T.SIMPLE_CHANNEL, "CHANNEL", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.newSimpleChannel(new $T($S), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals)",
                                T.NETWORK_REGISTRY, T.RL, channel).build())
                .addMethod(init.build()).build();
        ctx.out.addJava(n.network.packageName(), t);
        ctx.manifest.put("packetDiscriminators", disc);
    }

    private void message(IrNode.Packet p) {
        ClassName cls = n.message(p.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).addJavadoc("Packet $L ($L).\n", p.id(), p.direction());
        MethodSpec.Builder all = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        MethodSpec.Builder dec = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.FRIENDLY_BUF, "buf");
        MethodSpec.Builder enc = MethodSpec.methodBuilder("encode").addModifiers(Modifier.PUBLIC).addParameter(T.FRIENDLY_BUF, "buf");
        for (IrNode.Packet.Field f : p.fields()) {
            String name = Names.ident(f.name());
            if ("list".equals(f.type())) {
                Codec el = f.elementType() == null ? null : SCALARS.get(f.elementType());
                if (el == null) {
                    ctx.skip("packet-field-unsupported", p.id() + "#" + f.name(), "list of " + f.elementType() + " not supported");
                    continue;
                }
                TypeName boxed = el.type().isPrimitive() ? el.type().box() : el.type();
                TypeName lt = ParameterizedTypeName.get(ClassName.get(List.class), boxed);
                t.addField(FieldSpec.builder(lt, name, Modifier.PUBLIC).build());
                all.addParameter(lt, name).addStatement("this.$L = $L", name, name);
                dec.addStatement("int $LSize = buf.readVarInt()", name)
                        .addStatement("this.$L = new $T<>($LSize)", name, ArrayList.class, name)
                        .beginControlFlow("for (int i = 0; i < $LSize; i++)", name).addStatement("this.$L.add($L)", name, el.read()).endControlFlow();
                enc.addStatement("buf.writeVarInt($L.size())", name).beginControlFlow("for ($T e : $L)", boxed, name)
                        .addStatement(el.write(), "e").endControlFlow();
                continue;
            }
            Codec c = SCALARS.get(f.type());
            if (c == null) {
                ctx.skip("packet-field-unsupported", p.id() + "#" + f.name(), "type " + f.type() + " not supported");
                continue;
            }
            t.addField(FieldSpec.builder(c.type(), name, Modifier.PUBLIC).build());
            all.addParameter(c.type(), name).addStatement("this.$L = $L", name, name);
            if ("string".equals(f.type()) && f.maxLength() != null) {
                dec.addStatement("this.$L = buf.readUtf($L)", name, f.maxLength());
                enc.addStatement("buf.writeUtf($L, $L)", name, f.maxLength());
            } else {
                dec.addStatement("this.$L = $L", name, c.read());
                enc.addStatement(c.write(), name);
            }
            if ("enum".equals(f.type())) {
                ctx.info("packet-enum-ordinal", p.id() + "#" + f.name(), "enum " + f.enumClass() + " sent as ordinal varInt");
            }
        }
        if (!p.fields().isEmpty()) {
            t.addMethod(all.build());
        } else {
            t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build());
        }
        t.addMethod(dec.build()).addMethod(enc.build());
        ctx.out.addJava(cls.packageName(), t.build());
    }

    private void handler(IrNode.Packet p) {
        ClassName h = n.handler(p);
        TypeSpec.Builder t = TypeSpec.classBuilder(h).addModifiers(Modifier.PUBLIC)
                .addMethod(MethodSpec.methodBuilder("handle").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addParameter(n.message(p.id()), "message")
                        .addParameter(ParameterizedTypeName.get(ClassName.get(Supplier.class), T.NETWORK_EVENT_CONTEXT), "context")
                        .addComment("TODO(porter:logic) handle $L", p.id()).build());
        if (p.handlerClass() != null) {
            if (ctx.options.stubs()) {
                ctx.out.addStub(h.packageName(), t.addJavadoc("Handler of packet $L: keep {@code public static void handle(msg, context)}.\n", p.id())
                        .build(), "packetHandler " + p.id());
            }
        } else {
            ctx.warn("packet-no-handler", p.id(), "no handlerClass in IR; empty handler generated");
            ctx.out.addJava(h.packageName(), t.build());
        }
    }
}
