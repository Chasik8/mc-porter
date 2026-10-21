// porter:adapter (generic) - 1.19.2 CreativeModeTab subclasses ("extends CreativeModeTab" redirected here): the
// icon comes from makeIcon(), created lazily (the tab exists before its items are registered).
package dev.porter.shim.mc;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public abstract class CreativeModeTab extends CreativeTabs {
    private ItemStack icon;

    protected CreativeModeTab(String label) { super(label); }

    protected CreativeModeTab(int index, String label) { super(index, label); }

    public abstract ItemStack makeIcon();

    public ItemStack getIconItem() {
        if (icon == null) icon = makeIcon();
        return icon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ItemStack getIconItemStack() {
        ItemStack s = getIconItem();
        return s != null ? s : new ItemStack(Items.stick);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public Item getTabIconItem() { return getIconItemStack().getItem(); }
}
