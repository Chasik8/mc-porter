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

    /** 1.19.2 Item#allowedIn(CreativeModeTab) */
    public boolean allowedIn(net.minecraft.creativetab.CreativeTabs tab) {
        return tab == null || tab == net.minecraft.creativetab.CreativeTabs.tabAllSearch || tab == getCreativeTab();
    }

    /** 1.19.2 Item#fillItemCategory; 1.7.10 getSubItems collects it. */
    public void fillItemCategory(net.minecraft.creativetab.CreativeTabs tab, NonNullList<ItemStack> items) {
        if (allowedIn(tab)) items.add(new ItemStack(this));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void getSubItems(net.minecraft.item.Item item, net.minecraft.creativetab.CreativeTabs tab, List list) {
        NonNullList<ItemStack> l = NonNullList.create();
        fillItemCategory(tab, l);
        list.addAll(l);
    }

    /**
     * 1.19.2 BlockState#canBeReplaced(BlockPlaceContext) of the clicked block for this item (e.g. a scaffold that a
     * barrel replaces). 1.7.10 only replaces blocks that are replaceable for everything; a ported item names the blocks
     * its 1.19.2 counterpart replaced. Default: none.
     */
    public boolean replacesClicked(World world, int x, int y, int z, ItemStack stack) { return false; }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hx, float hy, float hz) {
        if (!replacesClicked(world, x, y, z, stack)) return super.onItemUse(stack, player, world, x, y, z, side, hx, hy, hz);
        if (stack.stackSize == 0 || !player.canPlayerEdit(x, y, z, side, stack)) return false;
        int meta = field_150939_a.onBlockPlaced(world, x, y, z, side, hx, hy, hz, getMetadata(stack.getItemDamage()));
        if (placeBlockAt(stack, player, world, x, y, z, side, hx, hy, hz, meta)) {
            world.playSoundEffect(x + 0.5F, y + 0.5F, z + 0.5F, field_150939_a.stepSound.func_150496_b(),
                (field_150939_a.stepSound.getVolume() + 1.0F) / 2.0F, field_150939_a.stepSound.getPitch() * 0.8F);
            --stack.stackSize;
        }
        return true;
    }
}
