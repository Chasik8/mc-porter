package com.example.examplemod;

import net.minecraft.world.item.Item;

public class HammerItem extends Item {
    public HammerItem(Item.Properties props) {
        super(props);
    }

    @Override
    public String getDescriptionId() {
        return "item.examplemod.forge_hammer";
    }
}
