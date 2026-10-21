// porter:adapter (generic) - the 1.19.2 BlockItem API on a 1.7.10 ItemBlock. Ported block items extend this class
// ("extends BlockItem" redirected by a rule); the DeferredRegister adapter registers the instance after its block.
package dev.porter.shim.mc;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

public class PropBlockItem extends ItemBlock {
    protected final ItemProperties properties;

    public PropBlockItem(Block block, ItemProperties p) {
        super(block);
        this.properties = p;
        p.apply(this);
        if (p.tab != null) block.setCreativeTab(p.tab); // 1.7.10 lists block items by the tab of their block
    }

    /** 1.7.10 reflective construction (GameRegistry.registerBlock with an item class) */
    public PropBlockItem(Block block) { this(block, new ItemProperties()); }

    public Block getBlock() { return field_150939_a; }

    public void appendHoverText(ItemStack stack, World level, List<IChatComponent> tooltip, TooltipFlag flag) {}

    public String getDescriptionId() { return PropItem.descriptionId(field_150939_a); }

    public String getDescriptionId(ItemStack stack) { return getDescriptionId(); }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("rawtypes")
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        PropItem.addTooltip(stack, player, lines, advanced, (s, w, l, f) -> appendHoverText(s, w, l, f));
    }
}
