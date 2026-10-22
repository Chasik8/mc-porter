// porter:adapter (generic) - the 1.19.2 Item API on a 1.7.10 Item. Ported items extend this class (their root's
// "extends Item" is redirected here by a rule): Item.Properties constructor, use(level, player, hand) behind
// onItemRightClick, appendHoverText behind addInformation, 1.19.2 description ids ("item.<modid>.<path>", the keys
// the carried-over lang files use for derived entries such as tooltips).
package dev.porter.shim.mc;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

public class PropItem extends Item {
    protected final ItemProperties properties;

    public PropItem(ItemProperties p) {
        this.properties = p;
        p.apply(this);
    }

    // ------------------------------------------------------------------ 1.19.2 hooks

    public InteractionResultHolder<ItemStack> use(World level, EntityPlayer player, InteractionHand hand) {
        return InteractionResultHolder.pass(player.getHeldItem());
    }

    public void appendHoverText(ItemStack stack, World level, List<IChatComponent> tooltip, TooltipFlag flag) {}

    public String getDescriptionId() { return descriptionId(this); }

    public String getDescriptionId(ItemStack stack) { return getDescriptionId(); }

    /** Forge IForgeItem#isDamageable(ItemStack) */
    public boolean isDamageable(ItemStack stack) { return isDamageable(); }

    /** Forge IForgeItem#damageItem: the amount the stack should lose (the caller applies it); default = amount */
    public <T extends EntityLivingBase> int damageItem(ItemStack stack, int amount, T entity, Consumer<T> onBroken) { return amount; }

    /** 1.19.2 Item#finishUsingItem (eaten / drunk); 1.7.10 onEaten calls it. */
    public ItemStack finishUsingItem(ItemStack stack, World level, EntityLivingBase entity) { return stack; }

    /** 1.19.2 Item#getName(ItemStack); 1.7.10 getItemStackDisplayName shows it. */
    public IChatComponent getName(ItemStack stack) {
        return new net.minecraft.util.ChatComponentTranslation(getUnlocalizedNameInefficiently(stack) + ".name");
    }

    /** 1.19.2 Item#allowedIn(CreativeModeTab) */
    public boolean allowedIn(net.minecraft.creativetab.CreativeTabs tab) {
        return tab == null || tab == net.minecraft.creativetab.CreativeTabs.tabAllSearch || tab == getCreativeTab();
    }

    /** 1.19.2 Item#fillItemCategory; 1.7.10 getSubItems collects it. */
    public void fillItemCategory(net.minecraft.creativetab.CreativeTabs tab, NonNullList<ItemStack> items) {
        if (allowedIn(tab)) items.add(new ItemStack(this));
    }

    /** Item.fillItemCategory on any 1.7.10 item. */
    @SuppressWarnings("unchecked")
    public static void fillItemCategory(Item item, net.minecraft.creativetab.CreativeTabs tab, List<ItemStack> items) {
        if (item instanceof PropItem || item instanceof PropBlockItem) {
            NonNullList<ItemStack> l = NonNullList.create();
            if (item instanceof PropItem) ((PropItem) item).fillItemCategory(tab, l);
            else ((PropBlockItem) item).fillItemCategory(tab, l);
            items.addAll(l);
        } else {
            item.getSubItems(item, tab, items);
        }
    }

    // ------------------------------------------------------------------ 1.7.10 bridge

    @Override
    public ItemStack onEaten(ItemStack stack, World w, EntityPlayer p) { return finishUsingItem(stack, w, p); }

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        IChatComponent c = getName(stack);
        return c == null ? super.getItemStackDisplayName(stack) : c.getUnformattedText();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void getSubItems(Item item, net.minecraft.creativetab.CreativeTabs tab, List list) {
        NonNullList<ItemStack> l = NonNullList.create();
        fillItemCategory(tab, l);
        list.addAll(l);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World w, EntityPlayer p) {
        InteractionResultHolder<ItemStack> r = use(w, p, InteractionHand.MAIN_HAND);
        return r == null ? stack : r.getObject();
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        addTooltip(stack, player, lines, advanced, (s, w, l, f) -> appendHoverText(s, w, l, f));
    }

    interface Hover {
        void append(ItemStack s, World w, List<IChatComponent> l, TooltipFlag f);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void addTooltip(ItemStack stack, EntityPlayer player, List lines, boolean advanced, Hover h) {
        List<IChatComponent> l = new ArrayList<>();
        h.append(stack, player == null ? null : player.worldObj, l, TooltipFlag.of(advanced));
        for (IChatComponent c : l) lines.add(c.getFormattedText());
    }

    /** 1.19.2 description id of any 1.7.10 item: "item.<modid>.<path>" / "block.<modid>.<path>" from the registry name */
    public static String descriptionId(Item item) {
        Object n = Item.itemRegistry.getNameForObject(item);
        if (n == null) return item.getUnlocalizedName();
        String s = n.toString();
        int c = s.indexOf(':');
        String id = c < 0 ? "minecraft." + s : s.substring(0, c) + "." + s.substring(c + 1);
        return (item instanceof ItemBlock ? "block." : "item.") + id;
    }

    public static String descriptionId(Item item, ItemStack stack) {
        return item instanceof PropItem ? ((PropItem) item).getDescriptionId(stack)
            : item instanceof PropBlockItem ? ((PropBlockItem) item).getDescriptionId(stack) : descriptionId(item);
    }

    public static String descriptionId(Block block) {
        Object n = Block.blockRegistry.getNameForObject(block);
        if (n == null) return block.getUnlocalizedName();
        String s = n.toString();
        int c = s.indexOf(':');
        return "block." + (c < 0 ? "minecraft." + s : s.substring(0, c) + "." + s.substring(c + 1));
    }
}
