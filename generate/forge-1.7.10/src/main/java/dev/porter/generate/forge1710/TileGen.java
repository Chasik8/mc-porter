package dev.porter.generate.forge1710;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Modifier;

/** IR {@code blockEntity} → {@code <Name>TileBase extends TileEntity [implements IInventory]} + logic stub. */
final class TileGen {
    private final Plan plan;
    private final GenContext ctx;

    TileGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    void run() {
        for (IrNode.BlockEntity be : ctx.ir.all(IrNode.BlockEntity.class)) {
            emit(be);
        }
    }

    /** Spilled state properties of all blocks served by a block entity (union, first declaration wins). */
    private List<MetaLayout.Prop> spilled(IrNode.BlockEntity be) {
        Map<String, MetaLayout.Prop> out = new LinkedHashMap<>();
        for (String bid : be.blocks()) {
            MetaLayout l = plan.layouts.get(bid);
            if (l != null) {
                l.spilled().forEach(p -> out.putIfAbsent(p.name(), p));
            }
        }
        return new ArrayList<>(out.values());
    }

    private void emit(IrNode.BlockEntity be) {
        ClassName base = plan.tileBase(be.id());
        Optional<IrNode.Menu> menu = MenuGen.menuOf(ctx, be);
        int inv = be.inventorySize() != null ? be.inventorySize()
                : menu.map(m -> m.slots().stream().mapToInt(s -> s.index() + 1).max().orElse(0)).orElse(0);
        if (be.inventorySize() == null && inv > 0) {
            ctx.info("inventory-size-derived", be.id(), "inventorySize " + inv + " derived from menu slots");
        }
        int data = menu.map(MenuGen::dataSlotCount).orElse(0);
        String itemsKey = "Items";
        if (be.nbt() != null) {
            for (IrNode.BlockEntity.NbtKey k : be.nbt()) {
                if ("itemStacks".equals(k.type()) || "inventory".equals(k.type())) {
                    itemsKey = k.key();
                }
            }
            List<String> others = be.nbt().stream().filter(k -> !"itemStacks".equals(k.type()) && !"inventory".equals(k.type()))
                    .map(IrNode.BlockEntity.NbtKey::key).toList();
            if (!others.isEmpty()) {
                ctx.info("nbt-by-logic", be.id(), "NBT keys " + others + " are read/written by the logic class");
            }
        }
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.TILE)
                .addJavadoc("Block entity $L (blocks $L). Logic: {@code $L} extends this class.\n", be.id(), be.blocks(), be.logicClass());
        boolean ticking = be.ticking() != null && !"none".equals(be.ticking());
        t.addMethod(MethodSpec.methodBuilder("canUpdate").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN).addStatement("return $L", ticking).build());
        if (ticking) {
            MethodSpec.Builder u = MethodSpec.methodBuilder("updateEntity").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
            String tk = be.ticking();
            if ("both".equals(tk)) {
                u.beginControlFlow("if (worldObj.isRemote)").addStatement("clientTick()").nextControlFlow("else")
                        .addStatement("serverTick()").endControlFlow();
            } else if ("client".equals(tk)) {
                u.beginControlFlow("if (worldObj.isRemote)").addStatement("clientTick()").endControlFlow();
            } else {
                u.beginControlFlow("if (!worldObj.isRemote)").addStatement("serverTick()").endControlFlow();
            }
            t.addMethod(u.build());
            t.addMethod(MethodSpec.methodBuilder("serverTick").addModifiers(Modifier.PROTECTED)
                    .addJavadoc("Hook: server tick (1.19.2 ticker). Implemented by the logic class.\n").build());
            t.addMethod(MethodSpec.methodBuilder("clientTick").addModifiers(Modifier.PROTECTED)
                    .addJavadoc("Hook: client tick. Implemented by the logic class.\n").build());
        }
        List<MetaLayout.Prop> spilled = spilled(be);
        addStateFields(t, spilled);
        if (data > 0) {
            t.addField(FieldSpec.builder(ArrayTypeName.of(TypeName.INT), "dataSlots", Modifier.PROTECTED, Modifier.FINAL)
                    .addJavadoc("Values synced to open menus (IR menu dataSlots).\n").initializer("new int[$L]", data).build());
            t.addMethod(MethodSpec.methodBuilder("getDataSlot").addModifiers(Modifier.PUBLIC).returns(TypeName.INT)
                    .addParameter(TypeName.INT, "index").addStatement("return dataSlots[index]").build());
            t.addMethod(MethodSpec.methodBuilder("setDataSlot").addModifiers(Modifier.PUBLIC)
                    .addParameter(TypeName.INT, "index").addParameter(TypeName.INT, "value")
                    .addStatement("dataSlots[index] = value").build());
        }
        MethodSpec.Builder read = MethodSpec.methodBuilder("readFromNBT").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.NBT, "tag").addStatement("super.readFromNBT(tag)");
        MethodSpec.Builder write = MethodSpec.methodBuilder("writeToNBT").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.NBT, "tag").addStatement("super.writeToNBT(tag)");
        if (inv > 0) {
            t.addSuperinterface(T.INVENTORY);
            t.addField(FieldSpec.builder(ArrayTypeName.of(T.ITEM_STACK), "inventory", Modifier.PROTECTED, Modifier.FINAL)
                    .initializer("new $T[$L]", T.ITEM_STACK, inv).build());
            read.addStatement("$T list = tag.getTagList($S, 10)", T.NBT_LIST, itemsKey)
                    .addStatement("java.util.Arrays.fill(inventory, null)")
                    .beginControlFlow("for (int i = 0; i < list.tagCount(); i++)")
                    .addStatement("$T e = list.getCompoundTagAt(i)", T.NBT)
                    .addStatement("int slot = e.getByte($S) & 255", "Slot")
                    .beginControlFlow("if (slot < inventory.length)")
                    .addStatement("inventory[slot] = $T.loadItemStackFromNBT(e)", T.ITEM_STACK)
                    .endControlFlow().endControlFlow();
            write.addStatement("$T list = new $T()", T.NBT_LIST, T.NBT_LIST)
                    .beginControlFlow("for (int i = 0; i < inventory.length; i++)")
                    .beginControlFlow("if (inventory[i] != null)")
                    .addStatement("$T e = new $T()", T.NBT, T.NBT)
                    .addStatement("e.setByte($S, (byte) i)", "Slot")
                    .addStatement("inventory[i].writeToNBT(e)")
                    .addStatement("list.appendTag(e)")
                    .endControlFlow().endControlFlow()
                    .addStatement("tag.setTag($S, list)", itemsKey);
            addInventory(t, be, menu.orElse(null));
        }
        stateNbt(read, write, spilled);
        t.addMethod(read.build());
        t.addMethod(write.build());
        if (!spilled.isEmpty()) {
            addSync(t, spilled);
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (ctx.options.stubs()) {
            ClassName logic = plan.tileClass(be);
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of block entity $L. Rewriter output goes here; keep the public no-arg constructor\n"
                            + "(1.7.10 creates tile entities reflectively).\n", be.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).build()).build(),
                    "blockEntity " + be.id());
        }
    }

    static void addStateFields(TypeSpec.Builder t, List<MetaLayout.Prop> spilled) {
        for (MetaLayout.Prop p : spilled) {
            t.addField(FieldSpec.builder(TypeName.INT, p.field(), Modifier.PUBLIC)
                    .addJavadoc("Block-state property {@code $L} (does not fit in metadata): value index into $L.\n", p.name(), p.values())
                    .initializer("$L", p.defaultIndex()).build());
        }
    }

    static void stateNbt(MethodSpec.Builder read, MethodSpec.Builder write, List<MetaLayout.Prop> spilled) {
        for (MetaLayout.Prop p : spilled) {
            String key = "porter.state." + p.name();
            read.beginControlFlow("if (tag.hasKey($S))", key).addStatement("$L = tag.getInteger($S)", p.field(), key).endControlFlow();
            write.addStatement("tag.setInteger($S, $L)", key, p.field());
        }
    }

    /** Description packet carrying the spilled state, so clients render it. */
    static void addSync(TypeSpec.Builder t, List<MetaLayout.Prop> spilled) {
        MethodSpec.Builder d = MethodSpec.methodBuilder("getDescriptionPacket").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).returns(T.PACKET)
                .addStatement("$T tag = new $T()", T.NBT, T.NBT);
        MethodSpec.Builder r = MethodSpec.methodBuilder("onDataPacket").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.NETWORK_MANAGER, "net").addParameter(T.S35, "pkt")
                .addStatement("$T tag = pkt.func_148857_g()", T.NBT);
        for (MetaLayout.Prop p : spilled) {
            d.addStatement("tag.setInteger($S, $L)", p.name(), p.field());
            r.addStatement("$L = tag.getInteger($S)", p.field(), p.name());
        }
        d.addStatement("return new $T(xCoord, yCoord, zCoord, 0, tag)", T.S35);
        r.beginControlFlow("if (worldObj != null)").addStatement("worldObj.markBlockForUpdate(xCoord, yCoord, zCoord)").endControlFlow();
        t.addMethod(d.build());
        t.addMethod(r.build());
    }

    /** Concrete state holder for a block that has no IR block entity but too many states for metadata. */
    static void emitStateTile(Plan plan, IrNode.Block b, MetaLayout layout) {
        ClassName cls = plan.stateTile(b.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(cls).addModifiers(Modifier.PUBLIC).superclass(T.TILE)
                .addJavadoc("Holds the block-state properties of $L that do not fit in metadata ($L states).\n", b.id(), layout.totalStates);
        t.addMethod(MethodSpec.methodBuilder("canUpdate").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN).addStatement("return false").build());
        addStateFields(t, layout.spilled());
        MethodSpec.Builder read = MethodSpec.methodBuilder("readFromNBT").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.NBT, "tag").addStatement("super.readFromNBT(tag)");
        MethodSpec.Builder write = MethodSpec.methodBuilder("writeToNBT").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .addParameter(T.NBT, "tag").addStatement("super.writeToNBT(tag)");
        stateNbt(read, write, layout.spilled());
        t.addMethod(read.build());
        t.addMethod(write.build());
        addSync(t, layout.spilled());
        plan.ctx.out.addJava(cls.packageName(), t.build());
        plan.stateTiles.put(b.id(), cls);
    }

    private void addInventory(TypeSpec.Builder t, IrNode.BlockEntity be, IrNode.Menu menu) {
        t.addMethod(m("getSizeInventory", TypeName.INT).addStatement("return inventory.length").build());
        t.addMethod(m("getStackInSlot", T.ITEM_STACK).addParameter(TypeName.INT, "slot").addStatement("return inventory[slot]").build());
        t.addMethod(m("decrStackSize", T.ITEM_STACK).addParameter(TypeName.INT, "slot").addParameter(TypeName.INT, "count")
                .beginControlFlow("if (inventory[slot] == null)").addStatement("return null").endControlFlow()
                .addStatement("$T out", T.ITEM_STACK)
                .beginControlFlow("if (inventory[slot].stackSize <= count)")
                .addStatement("out = inventory[slot]").addStatement("inventory[slot] = null")
                .nextControlFlow("else")
                .addStatement("out = inventory[slot].splitStack(count)")
                .beginControlFlow("if (inventory[slot].stackSize == 0)").addStatement("inventory[slot] = null").endControlFlow()
                .endControlFlow()
                .addStatement("markDirty()").addStatement("return out").build());
        t.addMethod(m("getStackInSlotOnClosing", T.ITEM_STACK).addParameter(TypeName.INT, "slot")
                .addStatement("$T s = inventory[slot]", T.ITEM_STACK).addStatement("inventory[slot] = null").addStatement("return s").build());
        t.addMethod(m("setInventorySlotContents", TypeName.VOID).addParameter(TypeName.INT, "slot").addParameter(T.ITEM_STACK, "stack")
                .addStatement("inventory[slot] = stack")
                .beginControlFlow("if (stack != null && stack.stackSize > getInventoryStackLimit())")
                .addStatement("stack.stackSize = getInventoryStackLimit()").endControlFlow()
                .addStatement("markDirty()").build());
        String title = menu != null && menu.title() != null ? menu.title() : "container." + ctx.options.modId() + "." + plan.registryName(be.id());
        t.addMethod(m("getInventoryName", ClassName.get(String.class)).addStatement("return $S", title).build());
        t.addMethod(m("hasCustomInventoryName", TypeName.BOOLEAN).addStatement("return false").build());
        t.addMethod(m("getInventoryStackLimit", TypeName.INT).addStatement("return 64").build());
        t.addMethod(m("isUseableByPlayer", TypeName.BOOLEAN).addParameter(T.PLAYER, "player")
                .addStatement("return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this"
                        + " && player.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64.0D").build());
        t.addMethod(m("openInventory", TypeName.VOID).build());
        t.addMethod(m("closeInventory", TypeName.VOID).build());
        MethodSpec.Builder valid = m("isItemValidForSlot", TypeName.BOOLEAN).addParameter(TypeName.INT, "slot")
                .addParameter(T.ITEM_STACK, "stack");
        if (menu != null) {
            valid.addStatement("return $T.accepts($L, slot, stack)", plan.containerBase(menu.id()), "this");
        } else {
            valid.addStatement("return true");
        }
        t.addMethod(valid.build());
    }

    private static MethodSpec.Builder m(String name, TypeName ret) {
        MethodSpec.Builder b = MethodSpec.methodBuilder(name).addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
        if (!TypeName.VOID.equals(ret)) {
            b.returns(ret);
        }
        return b;
    }
}
