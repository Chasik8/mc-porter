// porter:adapter (generic) - 1.19.2 Level/BlockGetter block-state access on a 1.7.10 World / IBlockAccess.
// States that do not fit the metadata (StateDefinition.fullyEncoded() == false) are kept by the tile entity when it
// implements IStateHolder; otherwise the non-meta properties fall back to the block's default.
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public final class Levels {
    private Levels() {}

    /** Optional per-tile storage of the full state (blocks with more than 16 states). */
    public interface IStateHolder {
        BlockState porterGetState();

        void porterSetState(BlockState state);
    }

    public static BlockState getBlockState(IBlockAccess w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        int meta = w.getBlockMetadata(x, y, z);
        if (b instanceof IStateBlock && !((IStateBlock) b).getStateDefinition().fullyEncoded()) {
            TileEntity te = w.getTileEntity(x, y, z);
            if (te instanceof IStateHolder) {
                BlockState held = ((IStateHolder) te).porterGetState();
                if (held != null && held.getBlock() == b) {
                    return ((IStateBlock) b).getStateDefinition().fromMeta(meta, held);
                }
            }
        }
        return BlockState.of(b, meta);
    }

    public static BlockState getBlockState(IBlockAccess w, BlockPos p) { return getBlockState(w, p.getX(), p.getY(), p.getZ()); }

    /** state of the block a tile entity sits in. */
    public static BlockState getBlockState(TileEntity te) {
        if (te.getWorldObj() == null) {
            return te instanceof IStateHolder && ((IStateHolder) te).porterGetState() != null
                ? ((IStateHolder) te).porterGetState() : BlockState.of(Blocks.air, 0);
        }
        return getBlockState(te.getWorldObj(), te.xCoord, te.yCoord, te.zCoord);
    }

    /** 1.19.2 Level.setBlock flags: 1 = neighbours, 2 = clients (same bits as 1.7.10), 16/32/64 ignored. */
    public static boolean setBlock(World w, int x, int y, int z, BlockState s, int flags) {
        Block b = s.getBlock();
        int meta = s.getMeta();
        boolean changed;
        // 1.19.2 LevelChunk.setBlockState is a no-op for an air-like state (isAir, e.g. an
        // AirBlock light source) in a section that holds only air; 1.7.10 would create the section
        if (b.getMaterial() == net.minecraft.block.material.Material.air && y >= 0 && y < 256 && w.blockExists(x, y, z)) {
            net.minecraft.world.chunk.storage.ExtendedBlockStorage sec = w.getChunkFromBlockCoords(x, z).getBlockStorageArray()[y >> 4];
            if (sec == null || sec.isEmpty()) return false;
        }
        if (w.getBlock(x, y, z) == b) {
            changed = w.getBlockMetadata(x, y, z) != meta;
            if (changed) w.setBlockMetadataWithNotify(x, y, z, meta, flags & 3);
        } else {
            changed = w.setBlock(x, y, z, b, meta, flags & 3);
        }
        if (b instanceof IStateBlock && !((IStateBlock) b).getStateDefinition().fullyEncoded()) {
            TileEntity te = w.getTileEntity(x, y, z);
            if (te instanceof IStateHolder) {
                BlockState old = ((IStateHolder) te).porterGetState();
                if (old != s) {
                    ((IStateHolder) te).porterSetState(s);
                    changed = true;
                    if ((flags & 2) != 0) w.markBlockForUpdate(x, y, z);
                }
            }
        }
        return changed;
    }

    public static boolean setBlock(World w, BlockPos p, BlockState s, int flags) { return setBlock(w, p.getX(), p.getY(), p.getZ(), s, flags); }

    public static boolean setBlockAndUpdate(World w, BlockPos p, BlockState s) { return setBlock(w, p, s, 3); }

    public static boolean removeBlock(World w, BlockPos p, boolean moving) { return w.setBlockToAir(p.getX(), p.getY(), p.getZ()); }

    public static boolean destroyBlock(World w, BlockPos p, boolean drop) { return w.func_147480_a(p.getX(), p.getY(), p.getZ(), drop); }

    /** 1.19.2 Level.updateNeighborsAt / blockUpdated. */
    public static void updateNeighborsAt(World w, BlockPos p, Block b) { w.notifyBlocksOfNeighborChange(p.getX(), p.getY(), p.getZ(), b); }

    /** 1.19.2 Level.sendBlockUpdated(pos, old, new, flags). */
    public static void sendBlockUpdated(World w, BlockPos p, BlockState a, BlockState b, int flags) { w.markBlockForUpdate(p.getX(), p.getY(), p.getZ()); }

    /** 1.19.2 LevelChunk.removeBlockEntityTicker(pos): the tile stops ticking (ModernTileEntity flag). */
    public static void removeBlockEntityTicker(net.minecraft.world.chunk.Chunk c, BlockPos p) {
        TileEntity te = c.worldObj.getTileEntity(p.getX(), p.getY(), p.getZ());
        if (te instanceof ModernTileEntity) ((ModernTileEntity) te).porterTicking = false;
    }

    /** 1.19.2 LevelChunk.updateBlockEntityTicker(tile): the tile ticks again. */
    public static void updateBlockEntityTicker(net.minecraft.world.chunk.Chunk c, TileEntity te) {
        if (te instanceof ModernTileEntity) ((ModernTileEntity) te).porterTicking = true;
    }

    /** 1.19.2 LevelChunk.getBlockState(pos) (world coordinates). */
    public static BlockState getBlockState(net.minecraft.world.chunk.Chunk c, BlockPos p) {
        return getBlockState(c.worldObj, p.getX(), p.getY(), p.getZ());
    }

    /** 1.19.2 Level.neighborChanged(pos, block, fromPos). */
    public static void neighborChanged(World w, BlockPos p, Block from, BlockPos fromPos) {
        w.notifyBlockOfNeighborChange(p.getX(), p.getY(), p.getZ(), from);
    }

    /** ForgeEventFactory.onNeighborNotify: posts the ForgeEvents adapter event (1.7.10 Forge has none). */
    public static boolean onNeighborNotify(World w, BlockPos p, BlockState s, java.util.EnumSet<?> sides, boolean force) {
        return ForgeEvents.notifyNeighbors(w, p, s);
    }

    /** 1.19.2 Level.getSignal(pos, direction) = redstone power the block at pos emits in that direction. */
    public static int getSignal(World w, BlockPos p, dev.porter.shim.Direction d) {
        return w.getIndirectPowerLevelTo(p.getX(), p.getY(), p.getZ(), d.get3DDataValue());
    }

    /** 1.19.2 Level.getBlockEntity on any view (BlockGetter). */
    public static TileEntity getBlockEntity(IBlockAccess w, BlockPos p) { return w.getTileEntity(p.getX(), p.getY(), p.getZ()); }

    /** 1.19.2 Level.getChunkAt(pos). */
    public static net.minecraft.world.chunk.Chunk getChunkAt(World w, BlockPos p) { return w.getChunkFromBlockCoords(p.getX(), p.getZ()); }

    /** 1.19.2 Level.updateNeighbourForOutputSignal (comparators). */
    public static void updateNeighbourForOutputSignal(World w, BlockPos p, Block b) { w.func_147453_f(p.getX(), p.getY(), p.getZ(), b); }

    /** 1.19.2 LevelReader.isAreaLoaded(center, range) */
    public static boolean isAreaLoaded(IBlockAccess w, BlockPos p, int r) {
        if (!(w instanceof World)) return true;
        return ((World) w).checkChunksExist(p.getX() - r, p.getY() - r, p.getZ() - r, p.getX() + r, p.getY() + r, p.getZ() + r);
    }

    /** 1.19.2 LevelReader.hasChunk(chunkX, chunkZ) */
    public static boolean hasChunk(IBlockAccess w, int cx, int cz) {
        return !(w instanceof World) || ((World) w).getChunkProvider().chunkExists(cx, cz);
    }

    /** LevelChunk.getBlockEntity(pos) */
    public static TileEntity getBlockEntity(net.minecraft.world.chunk.Chunk c, BlockPos p) {
        return c.worldObj.getTileEntity(p.getX(), p.getY(), p.getZ());
    }

    /** Entity.blockPosition() */
    public static BlockPos blockPosition(net.minecraft.entity.Entity e) { return new BlockPos(e.posX, e.posY, e.posZ); }

    /** Block.popResource(level, pos, stack): drop the stack at the block. */
    public static void popResource(World w, BlockPos p, net.minecraft.item.ItemStack s) {
        if (w.isRemote || s == null || s.stackSize <= 0) return;
        net.minecraft.entity.item.EntityItem e = new net.minecraft.entity.item.EntityItem(w, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, s);
        e.delayBeforeCanPickup = 10;
        w.spawnEntityInWorld(e);
    }

    /** LevelReader.getChunk(x, z, status, load): loaded chunk or null (1.7.10 has no chunk status). */
    public static net.minecraft.world.chunk.Chunk getChunk(IBlockAccess w, int cx, int cz, Object status, boolean load) {
        if (!(w instanceof World)) return null;
        World world = (World) w;
        if (!load && !world.getChunkProvider().chunkExists(cx, cz)) return null;
        return world.getChunkFromChunkCoords(cx, cz);
    }

    /** Mth.getSeed(Vec3i) (same formula as 1.19.2). */
    public static long getSeed(BlockPos p) {
        long l = (long) (p.getX() * 3129871) ^ (long) p.getZ() * 116129781L ^ (long) p.getY();
        l = l * l * 42317861L + l * 11L;
        return l >> 16;
    }

    public static net.minecraft.util.Vec3 atCenterOf(BlockPos p) { return net.minecraft.util.Vec3.createVectorHelper(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5); }

    public static net.minecraft.util.Vec3 atLowerCornerOf(BlockPos p) { return net.minecraft.util.Vec3.createVectorHelper(p.getX(), p.getY(), p.getZ()); }
}
