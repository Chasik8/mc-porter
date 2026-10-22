package ic2cextras.book;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import ic2.core.IC2;
import ic2cextras.Config;
import ic2cextras.IC2CExtras;
import ic2cextras.book.client.BookClient;

/**
 * Right click: opens the guide. Shift + right click on a multiblock controller: toggles a ghost projection of the
 * structure (client only, nothing is sent or placed).
 */
public class ItemGuideBook extends Item {

    public ItemGuideBook() {
        setUnlocalizedName(IC2CExtras.MODID + ".guideBook");
        setTextureName(IC2CExtras.MODID + ":guide_book");
        setMaxStackSize(1);
        setCreativeTab(IC2.tabIC2);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) BookClient.open();
        return stack;
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hx,
        float hy, float hz) {
        if (!player.isSneaking() || !Config.guideHologram) return false;
        MultiblockDef def = GuideBook.structureFor(world.getBlock(x, y, z));
        if (def == null) return false;
        if (world.isRemote) BookClient.toggleHologram(def, x, y, z, GuideBook.facingAt(world, x, y, z));
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(StatCollector.translateToLocal("ic2cextras.guide.tooltip"));
        if (Config.guideHologram) lines.add(StatCollector.translateToLocal("ic2cextras.guide.tooltip.hologram"));
    }
}
