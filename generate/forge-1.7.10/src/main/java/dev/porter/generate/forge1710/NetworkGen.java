package dev.porter.generate.forge1710;

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
import javax.lang.model.element.Modifier;

/** IR {@code packet} → {@code IMessage} classes and a {@code SimpleNetworkWrapper} channel. */
final class NetworkGen {
    /** Scalar field types: Java type, write template ($N = value), read expression. */
    record Codec(TypeName type, String write, String read) {
    }

    static final Map<String, Codec> SCALARS = Map.ofEntries(
            Map.entry("boolean", new Codec(TypeName.BOOLEAN, "buf.writeBoolean($L)", "buf.readBoolean()")),
            Map.entry("byte", new Codec(TypeName.BYTE, "buf.writeByte($L)", "buf.readByte()")),
            Map.entry("short", new Codec(TypeName.SHORT, "buf.writeShort($L)", "buf.readShort()")),
            Map.entry("int", new Codec(TypeName.INT, "buf.writeInt($L)", "buf.readInt()")),
            Map.entry("varInt", new Codec(TypeName.INT, "cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(buf, $L, 5)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readVarInt(buf, 5)")),
            Map.entry("long", new Codec(TypeName.LONG, "buf.writeLong($L)", "buf.readLong()")),
            Map.entry("varLong", new Codec(TypeName.LONG, "buf.writeLong($L)", "buf.readLong()")),
            Map.entry("float", new Codec(TypeName.FLOAT, "buf.writeFloat($L)", "buf.readFloat()")),
            Map.entry("double", new Codec(TypeName.DOUBLE, "buf.writeDouble($L)", "buf.readDouble()")),
            Map.entry("string", new Codec(ClassName.get(String.class), "cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, $L)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf)")),
            Map.entry("resourceId", new Codec(ClassName.get(String.class), "cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(buf, $L)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(buf)")),
            Map.entry("enum", new Codec(TypeName.INT, "cpw.mods.fml.common.network.ByteBufUtils.writeVarInt(buf, $L, 5)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readVarInt(buf, 5)")),
            Map.entry("itemStack", new Codec(T.ITEM_STACK, "cpw.mods.fml.common.network.ByteBufUtils.writeItemStack(buf, $L)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readItemStack(buf)")),
            Map.entry("nbt", new Codec(T.NBT, "cpw.mods.fml.common.network.ByteBufUtils.writeTag(buf, $L)",
                    "cpw.mods.fml.common.network.ByteBufUtils.readTag(buf)")),
            Map.entry("direction", new Codec(T.FORGE_DIRECTION, "buf.writeByte($L.ordinal())",
                    "net.minecraftforge.common.util.ForgeDirection.getOrientation(buf.readByte())")),
            Map.entry("uuid", new Codec(ClassName.get(java.util.UUID.class), "porterWriteUuid(buf, $L)", "porterReadUuid(buf)")),
            Map.entry("fluidStack", new Codec(T.FLUID_STACK, "porterWriteFluid(buf, $L)", "porterReadFluid(buf)")),
            Map.entry("bytes", new Codec(ArrayTypeName.of(TypeName.BYTE), "porterWriteBytes(buf, $L)", "porterReadBytes(buf)")));

    private final Plan plan;
    private final GenContext ctx;

    NetworkGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        List<IrNode.Packet> packets = new ArrayList<>(ctx.ir.all(IrNode.Packet.class));
        for (IrNode.Packet p : packets) {
            message(p);
            handler(p);
        }
        channel(packets);
    }

    private void message(IrNode.Packet p) {
        ClassName cls = plan.message(p.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).addSuperinterface(T.MESSAGE)
                .addJavadoc("Packet $L ($L, discriminator $L).\n", p.id(), p.direction(), plan.discriminators.get(p.id()));
        if (p.logicClass() != null) {
            t.addJavadoc("1.19.2 class: {@code $L}.\n", p.logicClass());
        }
        MethodSpec.Builder all = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        MethodSpec.Builder write = MethodSpec.methodBuilder("toBytes").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.BYTE_BUF, "buf");
        MethodSpec.Builder read = MethodSpec.methodBuilder("fromBytes").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.BYTE_BUF, "buf");
        boolean uuid = false;
        boolean fluid = false;
        boolean bytes = false;
        for (IrNode.Packet.Field f : p.fields()) {
            String name = Names.ident(f.name());
            String type = f.type();
            if ("blockPos".equals(type)) {
                for (String axis : new String[] {"X", "Y", "Z"}) {
                    t.addField(FieldSpec.builder(TypeName.INT, name + axis, Modifier.PUBLIC).build());
                    all.addParameter(TypeName.INT, name + axis).addStatement("this.$L = $L", name + axis, name + axis);
                    write.addStatement("buf.writeInt($L)", name + axis);
                    read.addStatement("$L = buf.readInt()", name + axis);
                }
                ctx.info("packet-blockpos", p.id() + "#" + f.name(), "blockPos field as three ints " + name + "X/Y/Z");
                continue;
            }
            if ("list".equals(type)) {
                Codec el = f.elementType() == null ? null : SCALARS.get(f.elementType());
                if (el == null) {
                    ctx.skip("packet-field-unsupported", p.id() + "#" + f.name(), "list of " + f.elementType() + " not supported; field dropped");
                    continue;
                }
                TypeName boxed = el.type().isPrimitive() ? el.type().box() : el.type();
                TypeName lt = ParameterizedTypeName.get(ClassName.get(List.class), boxed);
                t.addField(FieldSpec.builder(lt, name, Modifier.PUBLIC)
                        .initializer("new $T<$T>()", ArrayList.class, boxed).build());
                all.addParameter(lt, name).addStatement("this.$L = $L", name, name);
                write.addStatement("$T.writeVarInt(buf, $L.size(), 5)", T.BYTE_BUF_UTILS, name)
                        .beginControlFlow("for ($T e : $L)", boxed, name)
                        .addStatement(el.write(), "e").endControlFlow();
                read.addStatement("int $LSize = $T.readVarInt(buf, 5)", name, T.BYTE_BUF_UTILS)
                        .addStatement("$L = new $T<$T>($LSize)", name, ArrayList.class, boxed, name)
                        .beginControlFlow("for (int i = 0; i < $LSize; i++)", name)
                        .addStatement("$L.add($L)", name, el.read()).endControlFlow();
                uuid |= "uuid".equals(f.elementType());
                fluid |= "fluidStack".equals(f.elementType());
                bytes |= "bytes".equals(f.elementType());
                continue;
            }
            Codec c = SCALARS.get(type);
            if (c == null) {
                ctx.skip("packet-field-unsupported", p.id() + "#" + f.name(), "type " + type + " not supported; field dropped");
                continue;
            }
            t.addField(FieldSpec.builder(c.type(), name, Modifier.PUBLIC).build());
            all.addParameter(c.type(), name).addStatement("this.$L = $L", name, name);
            write.addStatement(c.write(), name);
            read.addStatement("$L = $L", name, c.read());
            if ("enum".equals(type)) {
                ctx.info("packet-enum-ordinal", p.id() + "#" + f.name(), "enum " + f.enumClass() + " sent as ordinal int");
            }
            if ("varLong".equals(type)) {
                ctx.info("packet-varlong", p.id() + "#" + f.name(), "varLong sent as fixed 8-byte long");
            }
            uuid |= "uuid".equals(type);
            fluid |= "fluidStack".equals(type);
            bytes |= "bytes".equals(type);
        }
        t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addJavadoc("For the network codec.\n").build());
        if (!p.fields().isEmpty()) {
            t.addMethod(all.build());
        }
        t.addMethod(write.build());
        t.addMethod(read.build());
        if (uuid) {
            t.addMethod(MethodSpec.methodBuilder("porterWriteUuid").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(T.BYTE_BUF, "buf").addParameter(java.util.UUID.class, "id")
                    .addStatement("buf.writeLong(id.getMostSignificantBits())").addStatement("buf.writeLong(id.getLeastSignificantBits())").build());
            t.addMethod(MethodSpec.methodBuilder("porterReadUuid").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(java.util.UUID.class).addParameter(T.BYTE_BUF, "buf")
                    .addStatement("return new $T(buf.readLong(), buf.readLong())", java.util.UUID.class).build());
        }
        if (fluid) {
            t.addMethod(MethodSpec.methodBuilder("porterWriteFluid").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(T.BYTE_BUF, "buf").addParameter(T.FLUID_STACK, "stack")
                    .addStatement("$T.writeTag(buf, stack == null ? null : stack.writeToNBT(new $T()))", T.BYTE_BUF_UTILS, T.NBT).build());
            t.addMethod(MethodSpec.methodBuilder("porterReadFluid").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(T.FLUID_STACK).addParameter(T.BYTE_BUF, "buf")
                    .addStatement("$T tag = $T.readTag(buf)", T.NBT, T.BYTE_BUF_UTILS)
                    .addStatement("return tag == null ? null : $T.loadFluidStackFromNBT(tag)", T.FLUID_STACK).build());
        }
        if (bytes) {
            t.addMethod(MethodSpec.methodBuilder("porterWriteBytes").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(T.BYTE_BUF, "buf").addParameter(ArrayTypeName.of(TypeName.BYTE), "data")
                    .addStatement("$T.writeVarInt(buf, data.length, 5)", T.BYTE_BUF_UTILS).addStatement("buf.writeBytes(data)").build());
            t.addMethod(MethodSpec.methodBuilder("porterReadBytes").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(ArrayTypeName.of(TypeName.BYTE)).addParameter(T.BYTE_BUF, "buf")
                    .addStatement("byte[] data = new byte[$T.readVarInt(buf, 5)]", T.BYTE_BUF_UTILS)
                    .addStatement("buf.readBytes(data)").addStatement("return data").build());
        }
        ctx.out.addJava(cls.packageName(), t.build());
    }

    private void handler(IrNode.Packet p) {
        ClassName h = plan.messageHandler(p);
        TypeName iface = ParameterizedTypeName.get(T.MESSAGE_HANDLER, plan.message(p.id()), T.MESSAGE);
        TypeSpec.Builder t = TypeSpec.classBuilder(h).addModifiers(Modifier.PUBLIC).addSuperinterface(iface)
                .addMethod(MethodSpec.methodBuilder("onMessage").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(T.MESSAGE).addParameter(plan.message(p.id()), "message").addParameter(T.MESSAGE_CONTEXT, "ctx")
                        .addComment("TODO(porter:logic) handle $L ($L)", p.id(), p.direction())
                        .addStatement("return null").build());
        if (p.handlerClass() != null) {
            if (ctx.options.stubs()) {
                ctx.out.addStub(h.packageName(), t.addJavadoc("Handler of packet $L. Rewriter output goes here; keep implementing\n"
                        + "IMessageHandler<$T, IMessage> with a public no-arg constructor.\n", p.id(), plan.message(p.id())).build(),
                        "packetHandler " + p.id());
            }
        } else {
            ctx.warn("packet-no-handler", p.id(), "no handlerClass in IR; empty handler generated");
            ctx.out.addJava(h.packageName(), t.addJavadoc("Handler of packet $L (no handler class in the IR).\n", p.id()).build());
        }
    }

    private void channel(List<IrNode.Packet> packets) {
        String name = ctx.options.channel();
        if (name.length() > 20) {
            ctx.warn("channel-name-truncated", name, "FML channel names are limited to 20 characters");
            name = name.substring(0, 20);
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.network).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Network channel $S ($L packets).\n", name, packets.size())
                .addField(FieldSpec.builder(String.class, "CHANNEL_NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", name).build())
                .addField(FieldSpec.builder(T.SIMPLE_WRAPPER, "CHANNEL", Modifier.PUBLIC, Modifier.STATIC).build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        MethodSpec.Builder init = MethodSpec.methodBuilder("init").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Creates the channel and registers all messages (preInit).\n")
                .addStatement("CHANNEL = $T.INSTANCE.newSimpleChannel(CHANNEL_NAME)", T.NETWORK_REGISTRY);
        for (IrNode.Packet p : packets) {
            int d = plan.discriminators.get(p.id());
            List<String> sides = switch (p.direction()) {
                case "toServer" -> List.of("SERVER");
                case "toClient" -> List.of("CLIENT");
                default -> List.of("SERVER", "CLIENT");
            };
            for (String side : sides) {
                init.addStatement("CHANNEL.registerMessage($T.class, $T.class, $L, $T.$L)", plan.messageHandler(p),
                        plan.message(p.id()), d, T.SIDE, side);
            }
            if (p.channel() != null && !p.channel().equals(name)) {
                plan.channelNames.add(p.channel());
            }
        }
        if (!plan.channelNames.isEmpty()) {
            ctx.info("channel-merged", name, "IR channels " + plan.channelNames + " merged into one 1.7.10 channel");
        }
        t.addMethod(init.build());
        ctx.out.addJava(plan.network.packageName(), t.build());
    }
}
