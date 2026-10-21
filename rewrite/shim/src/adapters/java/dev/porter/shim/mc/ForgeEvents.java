// porter:adapter (generic) - Forge 1.19.2 events that 1.7.10 Forge does not have, posted on the 1.7.10 event bus
// by a bridge subscriber: PlayerInteractEvent.RightClickBlock / LeftClickBlock (from the single 1.7.10
// PlayerInteractEvent) and BlockEvent.NeighborNotifyEvent (1.7.10 has no setBlock hook: posted for block
// break/place events, for StateBlock placement/removal and for Levels.onNeighborNotify). Call ForgeEvents.init()
// once from the mod's init.
package dev.porter.shim.mc;

import cpw.mods.fml.common.eventhandler.Cancelable;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;

public final class ForgeEvents {
    private static boolean registered;

    private ForgeEvents() {}

    public static synchronized void init() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new Bridge());
        }
    }

    /** 1.19.2 MobSpawnType (1.7.10 CheckSpawn is posted for natural spawns and spawners alike). */
    public enum MobSpawnType {
        NATURAL, CHUNK_GENERATION, SPAWNER, STRUCTURE, BREEDING, MOB_SUMMONED, JOCKEY, EVENT, CONVERSION, REINFORCEMENT,
        TRIGGERED, BUCKET, SPAWN_EGG, COMMAND, DISPENSER, PATROL
    }

    /** LivingSpawnEvent.CheckSpawn#getSpawnReason(): spawner-driven checks are not distinguishable in 1.7.10. */
    public static MobSpawnType spawnReason(LivingSpawnEvent.CheckSpawn e) { return MobSpawnType.NATURAL; }

    /** posts a NeighborNotifyEvent for a block change at pos (the 1.19.2 Level.setBlock hook); true = cancelled. */
    public static boolean notifyNeighbors(World w, BlockPos pos, BlockState state) {
        if (w == null || w.isRemote) return false;
        return MinecraftForge.EVENT_BUS.post(new NeighborNotifyEvent(w, pos, state));
    }

    /** common accessors of the 1.19.2 PlayerInteractEvent block events. */
    public abstract static class ClickBlock extends Event {
        final PlayerInteractEvent source;
        private InteractionResult result = InteractionResult.PASS;

        ClickBlock(PlayerInteractEvent source) { this.source = source; }

        public EntityPlayer getEntity() { return source.entityPlayer; }

        public World getLevel() { return source.world; }

        public BlockPos getPos() { return new BlockPos(source.x, source.y, source.z); }

        public Direction getFace() { return source.face < 0 || source.face > 5 ? null : Direction.from3DDataValue(source.face); }

        public InteractionHand getHand() { return InteractionHand.MAIN_HAND; }

        public net.minecraft.item.ItemStack getItemStack() { return source.entityPlayer.getHeldItem(); }

        public void setCancellationResult(InteractionResult r) { this.result = r; }

        public InteractionResult getCancellationResult() { return result; }
    }

    @Cancelable
    public static class RightClickBlock extends ClickBlock {
        public RightClickBlock(PlayerInteractEvent source) { super(source); }
    }

    @Cancelable
    public static class LeftClickBlock extends ClickBlock {
        public LeftClickBlock(PlayerInteractEvent source) { super(source); }
    }

    @Cancelable
    public static class NeighborNotifyEvent extends Event {
        private final World world;
        private final BlockPos pos;
        private final BlockState state;

        public NeighborNotifyEvent(World world, BlockPos pos, BlockState state) {
            this.world = world;
            this.pos = pos;
            this.state = state;
        }

        public World getLevel() { return world; }

        public BlockPos getPos() { return pos; }

        public BlockState getState() { return state; }

        public boolean getForceRedstoneUpdate() { return false; }
    }

    public static final class Bridge {
        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onInteract(PlayerInteractEvent e) {
            ClickBlock c;
            if (e.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) c = new RightClickBlock(e);
            else if (e.action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) c = new LeftClickBlock(e);
            else return;
            if (MinecraftForge.EVENT_BUS.post(c)) e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onBreak(BlockEvent.BreakEvent e) {
            if (!e.isCanceled()) notifyNeighbors(e.world, new BlockPos(e.x, e.y, e.z), BlockState.of(Blocks.air));
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPlace(BlockEvent.PlaceEvent e) {
            if (!e.isCanceled()) notifyNeighbors(e.world, new BlockPos(e.x, e.y, e.z), Levels.getBlockState(e.world, new BlockPos(e.x, e.y, e.z)));
        }
    }
}
