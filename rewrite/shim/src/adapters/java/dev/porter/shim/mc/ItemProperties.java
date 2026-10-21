// porter:adapter (generic) - 1.19.2 Item.Properties: a builder whose values PropItem / PropBlockItem apply to the
// 1.7.10 Item (creative tab, stack size, durability, container item).
package dev.porter.shim.mc;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

public class ItemProperties {
    public CreativeTabs tab;
    public int maxStackSize = 64;
    public int maxDamage;
    public Item craftRemainder;
    public boolean fireResistant;
    public boolean noRepair;
    public Object rarity;

    public ItemProperties tab(CreativeTabs t) { this.tab = t; return this; }

    public ItemProperties stacksTo(int n) {
        if (maxDamage > 0) throw new RuntimeException("Unable to have damage AND stack.");
        this.maxStackSize = n;
        return this;
    }

    public ItemProperties durability(int d) {
        this.maxDamage = d;
        this.maxStackSize = 1;
        return this;
    }

    public ItemProperties defaultDurability(int d) { return maxDamage == 0 ? durability(d) : this; }

    public ItemProperties craftRemainder(Item i) { this.craftRemainder = i; return this; }

    public ItemProperties fireResistant() { this.fireResistant = true; return this; }

    public ItemProperties setNoRepair() { this.noRepair = true; return this; }

    public ItemProperties rarity(Object r) { this.rarity = r; return this; }

    public ItemProperties food(Object f) { return this; }

    public void apply(Item i) {
        if (tab != null) i.setCreativeTab(tab);
        i.setMaxStackSize(maxStackSize);
        if (maxDamage > 0) i.setMaxDamage(maxDamage);
        if (craftRemainder != null) i.setContainerItem(craftRemainder);
        if (noRepair) i.setNoRepair();
    }
}
