package com.example.examplemod;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class PressScreen extends AbstractContainerScreen<PressMenu> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(ExampleMod.MODID, "textures/gui/press.png");

    public PressScreen(PressMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageHeight = 180;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(PoseStack pose, float partial, int mouseX, int mouseY) {
    }
}
