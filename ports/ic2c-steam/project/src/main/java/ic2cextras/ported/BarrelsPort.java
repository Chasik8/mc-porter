package ic2cextras.ported;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ic2cextras.ported.api.recipes.RecipeRegistry;
import ic2cextras.ported.core.IC2;
import ic2cextras.ported.core.block.base.features.IClickable;
import ic2cextras.ported.core.block.misc.tiles.BarrelTileEntity;
import ic2cextras.ported.core.entity.potion.ShakyPredicate;
import ic2cextras.ported.core.item.food_and_drink.IC2FoodsAndDrinks;
import ic2cextras.ported.core.platform.recipes.misc.GlobalRecipes;
import ic2cextras.ported.gen.Ic2cextrasContent;
import ic2cextras.ported.shim.Direction;
import ic2cextras.ported.shim.mc.InteractionHand;

/**
 * Wiring of the barrels/drinks/refinery closure (what IC2C's main class, recipe loader and mixins did): creative tab,
 * drink containers, the recipe lists (refining, potion brewing) filled on both sides, the Shaky effect's projectile
 * immunity (IC2C ProjectileMixin) and the refinery's container click (IC2C IC2ContainerBlock.use; the refinery block
 * is generated). The host mod calls {@link #init()} after the generated content's init.
 */
public final class BarrelsPort {

    private BarrelsPort() {}

    public static void init() {
        // IC2C's main class assigned the 2.x electric item manager: here the installed IC2C 1.2.6 one answers
        if (ic2cextras.ported.api.items.electric.ElectricItem.MANAGER == null) {
            ic2cextras.ported.api.items.electric.ElectricItem.MANAGER = new ic2cextras.ported.compat.HostElectricItemManager();
        }
        IC2.FOOD_AND_DRINK_GROUP = Ic2cextrasContent.tabIc2FoodAndDrink;
        IC2FoodsAndDrinks.fillContainerList();
        for (boolean server : new boolean[] {true, false}) {
            GlobalRecipes recipes = IC2.RECIPES.get(server);
            if (recipes == null) continue;
            recipes.refining.reload();
            recipes.potions.reload();
            RecipeRegistry.POTION_BREWING.set(recipes.potions, server);
        }
        MinecraftForge.EVENT_BUS.register(new BarrelsPort());
    }

    /** IC2C ProjectileMixin: projectiles miss an entity in the vulnerable phase of its Shaky cycle. */
    @SubscribeEvent
    public void onAttack(LivingAttackEvent event) {
        if (event.source != null && event.source.isProjectile() && !ShakyPredicate.INSTANCE.test(event.entityLiving)) {
            event.setCanceled(true);
        }
    }

    /** IC2C IC2ContainerBlock.use: an IClickable tile handles the click (fill/drain containers) before the GUI opens. */
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || event.world.isRemote) return;
        TileEntity te = event.world.getTileEntity(event.x, event.y, event.z);
        EntityPlayer p = event.entityPlayer;
        // only under the generated (non-IC2C) blocks: the ported IC2C blocks call IClickable themselves
        boolean generatedBlock = event.world.getBlock(event.x, event.y, event.z).getClass().getName().startsWith("ic2cextras.ported.gen.");
        if (generatedBlock && te instanceof IClickable && !p.isSneaking()
            && ((IClickable) te).onRightClick(p, InteractionHand.MAIN_HAND, Direction.from3DDataValue(event.face), null)) {
            handled(event);
            return;
        }
        // IC2C TreeTapTool.useOn: a tree tap (the host's) on a horizontal side of a barrel taps it
        net.minecraft.item.ItemStack held = p.getCurrentEquippedItem();
        if (te instanceof BarrelTileEntity && event.face >= 2 && held != null && ic2.core.Ic2Items.treetap != null
            && held.getItem() == ic2.core.Ic2Items.treetap.getItem() && !((BarrelTileEntity) te).hasTreeTap()) {
            BarrelTileEntity barrel = (BarrelTileEntity) te;
            barrel.setFacing(Direction.from3DDataValue(event.face));
            barrel.setHasTreeTap(true);
            barrel.openBarrel();
            held.stackSize--;
            handled(event);
        }
    }

    /** the click is done: neither the block nor the item acts (not setCanceled: that sends a block-change packet). */
    private static void handled(PlayerInteractEvent event) {
        event.useBlock = cpw.mods.fml.common.eventhandler.Event.Result.DENY;
        event.useItem = cpw.mods.fml.common.eventhandler.Event.Result.DENY;
    }
}
