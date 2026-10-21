package dev.porter.generate.forge1710;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.porter.core.ir.IrNode;
import dev.porter.generate.GenContext;
import dev.porter.generate.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Modifier;
import tools.jackson.databind.JsonNode;

/**
 * IR {@code menu} → {@code <Name>ContainerBase extends Container}, {@code <Name>GuiBase extends GuiContainer}, and
 * the mod's {@code IGuiHandler} with GUI ids from the configured range.
 */
final class MenuGen {
    private final Plan plan;
    private final GenContext ctx;

    MenuGen(Plan plan) {
        this.plan = plan;
        this.ctx = plan.ctx;
    }

    /** IR extension of a menu: {@code "center"} = the title is centred (default: at x 8 like 1.19.2's titleLabelX). */
    static final String TITLE_ALIGN = "porter:titleAlign";

    /**
     * Lang key of a screen title: the menu's {@code title}, else the name of the block that opens it (1.19.2 menu
     * providers mostly title a block entity's screen with its block's name), else none.
     */
    String titleKey(IrNode.Menu m) {
        if (m.title() != null) {
            return m.title();
        }
        if (m.blockEntity() == null) {
            return null;
        }
        List<IrNode.Block> blocks = ctx.ir.all(IrNode.Block.class).stream()
                .filter(b -> m.blockEntity().equals(b.blockEntity()) || ctx.ir.get(IrNode.BlockEntity.class, m.blockEntity())
                        .map(be -> be.blocks() != null && be.blocks().contains(b.id())).orElse(false))
                .toList();
        if (blocks.size() != 1) {
            return null;
        }
        IrNode.Block b = blocks.get(0);
        ctx.info("menu-title-from-block", m.id(), "no IR title; the screen shows the name of block " + b.id());
        return "tile." + plan.legacyName(b.id(), b.extensions()) + ".name";
    }

    static Optional<IrNode.Menu> menuOf(GenContext ctx, IrNode.BlockEntity be) {
        if (be.menu() != null) {
            Optional<IrNode.Menu> m = ctx.ir.get(IrNode.Menu.class, be.menu());
            if (m.isPresent()) {
                return m;
            }
        }
        return ctx.ir.all(IrNode.Menu.class).stream().filter(m -> be.id().equals(m.blockEntity())).findFirst();
    }

    static Optional<IrNode.BlockEntity> blockEntityOf(GenContext ctx, IrNode.Menu m) {
        if (m.blockEntity() != null) {
            Optional<IrNode.BlockEntity> be = ctx.ir.get(IrNode.BlockEntity.class, m.blockEntity());
            if (be.isPresent()) {
                return be;
            }
        }
        return ctx.ir.all(IrNode.BlockEntity.class).stream().filter(b -> m.id().equals(b.menu())).findFirst();
    }

    static int dataSlotCount(IrNode.Menu m) {
        return m.dataSlots() == null ? 0 : m.dataSlots().stream().mapToInt(d -> d.index() + 1).max().orElse(0);
    }

    static String guiConstant(IrNode.Menu m) {
        return "GUI_" + Names.constant(Names.path(m.id()));
    }

    void run() {
        for (IrNode.Menu m : ctx.ir.all(IrNode.Menu.class)) {
            container(m);
            gui(m);
        }
        handler();
    }

    private int machineSlots(IrNode.Menu m) {
        return m.slots().stream().mapToInt(s -> s.index() + 1).max().orElse(0);
    }

    private void container(IrNode.Menu m) {
        ClassName base = plan.containerBase(m.id());
        Optional<IrNode.BlockEntity> be = blockEntityOf(ctx, m);
        ClassName tileBase = be.map(b -> plan.tileBase(b.id())).orElse(null);
        int slots = machineSlots(m);
        int data = dataSlotCount(m);
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.CONTAINER)
                .addJavadoc("Menu $L: $L machine slots, $L data slots.\n", m.id(), slots, data);
        if (m.logicClass() != null) {
            t.addJavadoc("Logic: {@code $L} extends this class.\n", m.logicClass());
        }
        t.addField(FieldSpec.builder(TypeName.INT, "MACHINE_SLOTS", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", slots).build());
        t.addField(FieldSpec.builder(T.INVENTORY, "inventory", Modifier.PROTECTED, Modifier.FINAL).build());
        t.addField(FieldSpec.builder(T.TILE, "tile", Modifier.PROTECTED, Modifier.FINAL).build());
        t.addField(FieldSpec.builder(ArrayTypeName.of(TypeName.INT), "lastData", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new int[$L]", data).build());
        t.addField(FieldSpec.builder(ArrayTypeName.of(TypeName.INT), "clientData", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new int[$L]", data).build());
        for (IrNode.Menu.DataSlot d : m.dataSlots() == null ? List.<IrNode.Menu.DataSlot>of() : m.dataSlots()) {
            t.addField(FieldSpec.builder(TypeName.INT, "DATA_" + Names.constant(d.name()), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", d.index()).build());
        }

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addParameter(T.PLAYER_INVENTORY, "playerInventory").addParameter(T.TILE, "tile")
                .addStatement("this.tile = tile")
                .addStatement("this.inventory = tile instanceof $T ? ($T) tile : new $T($S, false, $L)", T.INVENTORY, T.INVENTORY,
                        T.INVENTORY_BASIC, m.title() == null ? m.id() : m.title(), Math.max(1, slots));
        for (IrNode.Menu.Slot s : m.slots()) {
            ctor.addStatement("addSlotToContainer(new MachineSlot(inventory, $L, $L, $L))", s.index(), s.x(), s.y());
        }
        if (m.playerInventory() != null) {
            int px = m.playerInventory().x();
            int py = m.playerInventory().y();
            int hy = m.playerInventory().hotbarY() != null ? m.playerInventory().hotbarY() : py + 58;
            ctor.beginControlFlow("for (int row = 0; row < 3; row++)")
                    .beginControlFlow("for (int col = 0; col < 9; col++)")
                    .addStatement("addSlotToContainer(new $T(playerInventory, col + row * 9 + 9, $L + col * 18, $L + row * 18))", T.SLOT, px, py)
                    .endControlFlow().endControlFlow()
                    .beginControlFlow("for (int col = 0; col < 9; col++)")
                    .addStatement("addSlotToContainer(new $T(playerInventory, col, $L + col * 18, $L))", T.SLOT, px, hy)
                    .endControlFlow();
        } else {
            ctx.info("menu-no-player-inventory", m.id(), "menu has no player inventory slots");
        }
        t.addMethod(ctor.build());

        // slot rules
        MethodSpec.Builder accepts = MethodSpec.methodBuilder("accepts").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.BOOLEAN).addParameter(T.INVENTORY, "inventory").addParameter(TypeName.INT, "slot")
                .addParameter(T.ITEM_STACK, "stack")
                .addJavadoc("Slot rules from IR (role output = extract only; filter = item id or #tag via OreDictionary).\n")
                .beginControlFlow("switch (slot)");
        for (IrNode.Menu.Slot s : m.slots()) {
            if ("output".equals(s.role())) {
                accepts.addStatement("case $L: return false", s.index());
            } else if (s.filter() != null) {
                accepts.addStatement("case $L: return $L", s.index(), filterExpr(m, s.filter()));
            }
        }
        accepts.addStatement("default: return true").endControlFlow();
        t.addMethod(accepts.build());
        t.addType(TypeSpec.classBuilder("MachineSlot").addModifiers(Modifier.PUBLIC, Modifier.STATIC).superclass(T.SLOT)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(T.INVENTORY, "inventory")
                        .addParameter(TypeName.INT, "index").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y")
                        .addStatement("super(inventory, index, x, y)").build())
                .addMethod(MethodSpec.methodBuilder("isItemValid").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.BOOLEAN).addParameter(T.ITEM_STACK, "stack")
                        .addStatement("return accepts(inventory, getSlotIndex(), stack)").build())
                .build());

        t.addMethod(MethodSpec.methodBuilder("canInteractWith").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN).addParameter(T.PLAYER, "player")
                .addStatement("return inventory.isUseableByPlayer(player)").build());

        // shift click
        boolean hasPlayer = m.playerInventory() != null;
        MethodSpec.Builder tr = MethodSpec.methodBuilder("transferStackInSlot").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).returns(T.ITEM_STACK).addParameter(T.PLAYER, "player").addParameter(TypeName.INT, "index")
                .addStatement("$T slot = inventorySlots.get(index)", T.SLOT)
                .beginControlFlow("if (slot == null || !slot.getHasStack())").addStatement("return null").endControlFlow()
                .addStatement("$T stack = slot.getStack()", T.ITEM_STACK)
                .addStatement("$T copy = stack.copy()", T.ITEM_STACK);
        if (hasPlayer) {
            tr.beginControlFlow("if (index < MACHINE_SLOTS)")
                    .beginControlFlow("if (!mergeItemStack(stack, MACHINE_SLOTS, MACHINE_SLOTS + 36, true))").addStatement("return null").endControlFlow()
                    .nextControlFlow("else")
                    .addStatement("boolean moved = false")
                    .beginControlFlow("for (int i = 0; i < MACHINE_SLOTS && !moved; i++)")
                    .beginControlFlow("if (accepts(inventory, i, stack) && mergeItemStack(stack, i, i + 1, false))")
                    .addStatement("moved = true").endControlFlow()
                    .endControlFlow()
                    .beginControlFlow("if (!moved)").addStatement("return null").endControlFlow()
                    .endControlFlow();
        } else {
            tr.addStatement("return null");
        }
        if (hasPlayer) {
            tr.beginControlFlow("if (stack.stackSize == 0)").addStatement("slot.putStack(null)")
                    .nextControlFlow("else").addStatement("slot.onSlotChanged()").endControlFlow()
                    .beginControlFlow("if (stack.stackSize == copy.stackSize)").addStatement("return null").endControlFlow()
                    .addStatement("slot.onPickupFromSlot(player, stack)")
                    .addStatement("return copy");
        }
        t.addMethod(tr.build());

        // data slots
        if (data > 0) {
            if (tileBase == null) {
                ctx.warn("menu-data-without-tile", m.id(), "data slots without a block entity: values only settable via setData");
            }
            MethodSpec.Builder get = MethodSpec.methodBuilder("data").addModifiers(Modifier.PUBLIC).returns(TypeName.INT)
                    .addParameter(TypeName.INT, "index")
                    .addJavadoc("Data slot value (server: from the tile; client: last synced value).\n");
            if (tileBase != null) {
                get.beginControlFlow("if (tile instanceof $T && !tile.getWorldObj().isRemote)", tileBase)
                        .addStatement("return (($T) tile).getDataSlot(index)", tileBase).endControlFlow();
            }
            get.addStatement("return clientData[index]");
            t.addMethod(get.build());
            t.addMethod(MethodSpec.methodBuilder("detectAndSendChanges").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .addStatement("super.detectAndSendChanges()")
                    .beginControlFlow("for (int i = 0; i < lastData.length; i++)")
                    .addStatement("int v = data(i)")
                    .beginControlFlow("if (v != lastData[i])")
                    .beginControlFlow("for (Object c : crafters)")
                    .addStatement("(($T) c).sendProgressBarUpdate(this, i, v)", T.CRAFTING)
                    .endControlFlow()
                    .addStatement("lastData[i] = v")
                    .endControlFlow().endControlFlow().build());
            t.addMethod(MethodSpec.methodBuilder("addCraftingToCrafters").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .addParameter(T.CRAFTING, "crafter")
                    .addStatement("super.addCraftingToCrafters(crafter)")
                    .beginControlFlow("for (int i = 0; i < lastData.length; i++)")
                    .addStatement("crafter.sendProgressBarUpdate(this, i, data(i))")
                    .endControlFlow().build());
            MethodSpec.Builder upd = MethodSpec.methodBuilder("updateProgressBar").addAnnotation(Override.class)
                    .addAnnotation(BlockGen.clientOnly()).addModifiers(Modifier.PUBLIC)
                    .addParameter(TypeName.INT, "index").addParameter(TypeName.INT, "value")
                    .beginControlFlow("if (index >= 0 && index < clientData.length)")
                    .addStatement("clientData[index] = value");
            if (tileBase != null) {
                upd.beginControlFlow("if (tile instanceof $T)", tileBase)
                        .addStatement("(($T) tile).setDataSlot(index, value)", tileBase).endControlFlow();
            }
            upd.endControlFlow();
            t.addMethod(upd.build());
            ctx.info("menu-data-short", m.id(), "1.7.10 sendProgressBarUpdate carries 16-bit values; data slots above 32767 need a packet");
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (m.logicClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(m.logicClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addJavadoc("Logic of menu $L. Rewriter output goes here.\n", m.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                            .addParameter(T.PLAYER_INVENTORY, "playerInventory").addParameter(T.TILE, "tile")
                            .addStatement("super(playerInventory, tile)").build()).build(), "menu " + m.id());
        }
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("guiId", plan.guiIds.get(m.id()));
        mf.put("container", base.canonicalName());
        mf.put("gui", plan.guiBase(m.id()).canonicalName());
        mf.put("machineSlots", slots);
        mf.put("dataSlots", data);
        plan.menuManifest.put(m.id(), mf);
    }

    private CodeBlock filterExpr(IrNode.Menu m, String filter) {
        if (filter.startsWith("#")) {
            String ore = plan.oreName(filter.substring(1), m.id());
            return CodeBlock.of("stack != null && $T.hasOre(stack, $S)", plan.lookup, ore);
        }
        String[] t = plan.target(filter, false);
        if (t == null) {
            ctx.warn("vanilla-id-unmapped", m.id(), "slot filter " + filter + " is not a 1.7.10 item; slot accepts nothing");
            return CodeBlock.of("false");
        }
        return CodeBlock.of("stack != null && stack.getItem() == $T.item($S, $S)", plan.lookup, t[0], t[1]);
    }

    private void gui(IrNode.Menu m) {
        ClassName base = plan.guiBase(m.id());
        ClassName container = plan.containerBase(m.id());
        TypeSpec.Builder t = TypeSpec.classBuilder(base).addModifiers(Modifier.PUBLIC).superclass(T.GUI_CONTAINER)
                .addAnnotation(BlockGen.clientOnly())
                .addJavadoc("Screen of menu $L ($Lx$L).\n", m.id(), m.size().width(), m.size().height());
        if (m.screenClass() != null) {
            t.addJavadoc("Logic: {@code $L} extends this class.\n", m.screenClass());
        }
        int tw = 256;
        int th = 256;
        int u0 = 0;
        int v0 = 0;
        if (m.texture() != null) {
            String[] r = plan.resource(m.texture().path());
            t.addField(FieldSpec.builder(T.RESOURCE_LOCATION, "TEXTURE", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T($S, $S)", T.RESOURCE_LOCATION, r[0], r[1]).build());
            tw = m.texture().textureWidth() != null ? m.texture().textureWidth() : 256;
            th = m.texture().textureHeight() != null ? m.texture().textureHeight() : 256;
            u0 = m.texture().u() != null ? m.texture().u() : 0;
            v0 = m.texture().v() != null ? m.texture().v() : 0;
        } else {
            ctx.warn("menu-no-texture", m.id(), "menu has no background texture; nothing drawn");
        }
        t.addField(FieldSpec.builder(container, "container", Modifier.PROTECTED, Modifier.FINAL).build());
        List<IrNode.Menu.Button> buttons = m.buttons() == null ? List.of() : m.buttons();
        t.addField(FieldSpec.builder(ArrayTypeName.of(String.class), "BUTTON_IDS", Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                .initializer("{$L}", String.join(", ", buttons.stream().map(b -> CodeBlock.of("$S", b.id()).toString()).toList())).build());
        t.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(container, "container")
                .addStatement("super(container)").addStatement("this.container = container")
                .addStatement("this.xSize = $L", m.size().width()).addStatement("this.ySize = $L", m.size().height()).build());
        if (!buttons.isEmpty()) {
            MethodSpec.Builder init = MethodSpec.methodBuilder("initGui").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                    .addStatement("super.initGui()");
            for (int i = 0; i < buttons.size(); i++) {
                IrNode.Menu.Button b = buttons.get(i);
                init.addStatement("buttonList.add(new $T($L, guiLeft + $L, guiTop + $L, $L, $L, $T.translateToLocal($S)))", T.GUI_BUTTON,
                        i, b.x(), b.y(), b.width(), b.height(), T.STAT_COLLECTOR, b.label() == null ? "" : b.label());
            }
            t.addMethod(init.build());
            t.addMethod(MethodSpec.methodBuilder("actionPerformed").addAnnotation(Override.class).addModifiers(Modifier.PROTECTED)
                    .addParameter(T.GUI_BUTTON, "button")
                    .beginControlFlow("if (button.id >= 0 && button.id < BUTTON_IDS.length)")
                    .addStatement("onButton(BUTTON_IDS[button.id])").endControlFlow().build());
            MethodSpec.Builder on = MethodSpec.methodBuilder("onButton").addModifiers(Modifier.PROTECTED).addParameter(String.class, "id")
                    .addJavadoc("Hook: a button was pressed. The logic class sends the packet with the right field values:\n");
            for (IrNode.Menu.Button b : buttons) {
                if (b.packet() != null) {
                    on.addJavadoc("$L → {@link $T} via {@code $T.CHANNEL.sendToServer(...)}\n", b.id(), plan.message(b.packet()), plan.network);
                }
            }
            t.addMethod(on.build());
            ctx.info("menu-button-hook", m.id(), buttons.size() + " button(s): onButton(id) hook, packets are sent by the logic class");
        }
        t.addMethod(MethodSpec.methodBuilder("blit").addModifiers(Modifier.PROTECTED)
                .addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "u")
                .addParameter(TypeName.INT, "v").addParameter(TypeName.INT, "w").addParameter(TypeName.INT, "h")
                .addStatement(tw == 256 && th == 256 ? CodeBlock.of("drawTexturedModalRect(x, y, u, v, w, h)")
                        : CodeBlock.of("$T.func_146110_a(x, y, u, v, w, h, $LF, $LF)", T.GUI, tw, th))
                .build());
        MethodSpec.Builder bg = MethodSpec.methodBuilder("drawGuiContainerBackgroundLayer").addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED).addParameter(TypeName.FLOAT, "partialTicks")
                .addParameter(TypeName.INT, "mouseX").addParameter(TypeName.INT, "mouseY");
        if (m.texture() != null) {
            bg.addStatement("$T.glColor4f(1.0F, 1.0F, 1.0F, 1.0F)", T.GL11)
                    .addStatement("mc.getTextureManager().bindTexture(TEXTURE)")
                    .addStatement("blit(guiLeft, guiTop, $L, $L, xSize, ySize)", u0, v0);
            for (IrNode.Menu.Bar bar : m.bars() == null ? List.<IrNode.Menu.Bar>of() : m.bars()) {
                bar(bg, m, bar);
            }
        }
        t.addMethod(bg.build());
        MethodSpec.Builder fg = MethodSpec.methodBuilder("drawGuiContainerForegroundLayer").addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED).addParameter(TypeName.INT, "mouseX").addParameter(TypeName.INT, "mouseY");
        String title = titleKey(m);
        if (title != null) {
            JsonNode align = m.extensions() == null ? null : m.extensions().get(TITLE_ALIGN);
            if (align != null && "center".equals(align.asString())) {
                fg.addStatement("String title = $T.translateToLocal($S)", T.STAT_COLLECTOR, title)
                        .addStatement("fontRendererObj.drawString(title, (xSize - fontRendererObj.getStringWidth(title)) / 2, 6, 4210752)");
            } else {
                fg.addStatement("fontRendererObj.drawString($T.translateToLocal($S), 8, 6, 4210752)", T.STAT_COLLECTOR, title);
            }
        }
        if (m.playerInventory() != null) {
            fg.addStatement("fontRendererObj.drawString($T.translateToLocal($S), 8, ySize - 94, 4210752)", T.STAT_COLLECTOR, "container.inventory");
        }
        t.addMethod(fg.build());
        if (m.bars() != null && m.bars().stream().anyMatch(b -> dataIndex(m, b.value()) < 0)) {
            t.addMethod(MethodSpec.methodBuilder("barValue").addModifiers(Modifier.PROTECTED).returns(TypeName.INT)
                    .addParameter(String.class, "bar")
                    .addJavadoc("Hook: value of a bar whose IR value is not a data slot.\n").addStatement("return 0").build());
        }
        ctx.out.addJava(base.packageName(), t.build());
        if (m.screenClass() != null && ctx.options.stubs()) {
            ClassName logic = Plan.cls(m.screenClass());
            ctx.out.addStub(logic.packageName(), TypeSpec.classBuilder(logic).addModifiers(Modifier.PUBLIC).superclass(base)
                    .addAnnotation(BlockGen.clientOnly())
                    .addJavadoc("Screen logic of menu $L. Rewriter output goes here.\n", m.id())
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(container, "container")
                            .addStatement("super(container)").build()).build(), "screen " + m.id());
        }
    }

    private int dataIndex(IrNode.Menu m, String name) {
        if (name == null || m.dataSlots() == null) {
            return -1;
        }
        return m.dataSlots().stream().filter(d -> d.name().equals(name)).mapToInt(IrNode.Menu.DataSlot::index).findFirst().orElse(-1);
    }

    private void bar(MethodSpec.Builder bg, IrNode.Menu m, IrNode.Menu.Bar bar) {
        int vi = dataIndex(m, bar.value());
        CodeBlock value = vi >= 0 ? CodeBlock.of("container.data($L)", vi) : CodeBlock.of("barValue($S)", bar.id());
        CodeBlock max;
        JsonNode mx = bar.max();
        if (mx == null || mx.isNull()) {
            max = CodeBlock.of("100");
            ctx.warn("bar-max-missing", m.id() + "#" + bar.id(), "bar without max; 100 assumed");
        } else if (mx.isNumber()) {
            max = CodeBlock.of("$L", mx.asInt());
        } else {
            int mi = dataIndex(m, mx.asString());
            if (mi < 0) {
                ctx.warn("bar-max-unresolved", m.id() + "#" + bar.id(), "bar max " + mx.asString() + " is not a data slot; 0 used (bar hidden)");
                max = CodeBlock.of("0");
            } else {
                max = CodeBlock.of("container.data($L)", mi);
            }
        }
        boolean horizontal = bar.direction() == null || "right".equals(bar.direction()) || "left".equals(bar.direction());
        int len = horizontal ? bar.width() : bar.height();
        bg.beginControlFlow("")
                .addStatement("int value = $L", value)
                .addStatement("int max = $L", max)
                .beginControlFlow("if (max > 0 && value > 0)")
                .addStatement("int n = Math.min($L, value * $L / max)", len, len);
        String dir = bar.direction() == null ? "right" : bar.direction();
        switch (dir) {
            case "left" -> bg.addStatement("blit(guiLeft + $L + $L - n, guiTop + $L, $L + $L - n, $L, n, $L)",
                    bar.x(), bar.width(), bar.y(), bar.u(), bar.width(), bar.v(), bar.height());
            case "up" -> bg.addStatement("blit(guiLeft + $L, guiTop + $L + $L - n, $L, $L + $L - n, $L, n)",
                    bar.x(), bar.y(), bar.height(), bar.u(), bar.v(), bar.height(), bar.width());
            case "down" -> bg.addStatement("blit(guiLeft + $L, guiTop + $L, $L, $L, $L, n)",
                    bar.x(), bar.y(), bar.u(), bar.v(), bar.width());
            default -> {
                if (!"right".equals(dir)) {
                    ctx.warn("bar-direction-unknown", m.id() + "#" + bar.id(), "direction " + dir + "; drawn left-to-right");
                }
                bg.addStatement("blit(guiLeft + $L, guiTop + $L, $L, $L, n, $L)", bar.x(), bar.y(), bar.u(), bar.v(), bar.height());
            }
        }
        bg.endControlFlow().endControlFlow();
    }

    private void handler() {
        TypeSpec.Builder t = TypeSpec.classBuilder(plan.guiHandler).addModifiers(Modifier.PUBLIC).addSuperinterface(T.GUI_HANDLER)
                .addJavadoc("GUI ids $L (configurable with --gui-ids). Embedded in a host mod: delegate ids for which\n"
                        + "{@link #handles(int)} is true to {@link #INSTANCE}.\n", ctx.options.guiIds());
        t.addField(FieldSpec.builder(plan.guiHandler, "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", plan.guiHandler).build());
        t.addField(FieldSpec.builder(TypeName.INT, "FIRST_ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", ctx.options.guiIds().from()).build());
        t.addField(FieldSpec.builder(TypeName.INT, "LAST_ID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", ctx.options.guiIds().to()).build());
        List<IrNode.Menu> menus = new ArrayList<>(ctx.ir.all(IrNode.Menu.class));
        for (IrNode.Menu m : menus) {
            t.addField(FieldSpec.builder(TypeName.INT, guiConstant(m), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("Menu $L.\n", m.id()).initializer("$L", plan.guiIds.get(m.id())).build());
        }
        t.addMethod(MethodSpec.methodBuilder("handles").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.BOOLEAN)
                .addParameter(TypeName.INT, "id").addStatement("return id >= FIRST_ID && id <= LAST_ID").build());
        MethodSpec.Builder server = MethodSpec.methodBuilder("getServerGuiElement").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).returns(Object.class).addParameter(TypeName.INT, "id").addParameter(T.PLAYER, "player")
                .addParameter(T.WORLD, "world").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z")
                .addStatement("$T te = world.getTileEntity(x, y, z)", T.TILE);
        MethodSpec.Builder client = MethodSpec.methodBuilder("getClientGuiElement").addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC).returns(Object.class).addParameter(TypeName.INT, "id").addParameter(T.PLAYER, "player")
                .addParameter(T.WORLD, "world").addParameter(TypeName.INT, "x").addParameter(TypeName.INT, "y").addParameter(TypeName.INT, "z")
                .addStatement("$T te = world.getTileEntity(x, y, z)", T.TILE);
        for (IrNode.Menu m : menus) {
            Optional<IrNode.BlockEntity> be = blockEntityOf(ctx, m);
            CodeBlock check = be.isPresent() ? CodeBlock.of(" && te instanceof $T", plan.tileBase(be.get().id())) : CodeBlock.of("");
            server.beginControlFlow("if (id == $L$L)", guiConstant(m), check)
                    .addStatement("return new $T(player.inventory, te)", plan.containerClass(m)).endControlFlow();
            client.beginControlFlow("if (id == $L$L)", guiConstant(m), check)
                    .addStatement("return new $T(new $T(player.inventory, te))", plan.guiClass(m), plan.containerClass(m)).endControlFlow();
        }
        server.addStatement("return null");
        client.addStatement("return null");
        t.addMethod(server.build());
        t.addMethod(client.build());
        ctx.out.addJava(plan.guiHandler.packageName(), t.build());
    }
}
