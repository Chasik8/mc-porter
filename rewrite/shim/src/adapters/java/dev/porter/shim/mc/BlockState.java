// porter:adapter (generic) - 1.19.2 BlockState on 1.7.10: canonical (block, property values) for blocks with a
// StateDefinition (IStateBlock); a canonical (block, meta) legacy state for every other 1.7.10 block.
package dev.porter.shim.mc;

import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;

public final class BlockState {
    final StateDefinition<?, ?> def; // null for legacy states
    final int index;
    final Block legacyBlock;
    final int legacyMeta;
    private static final Map<Block, BlockState[]> LEGACY = new ConcurrentHashMap<>();

    BlockState(StateDefinition<?, ?> def, int index) {
        this.def = def;
        this.index = index;
        this.legacyBlock = null;
        this.legacyMeta = 0;
    }

    private BlockState(Block b, int meta) {
        this.def = null;
        this.index = 0;
        this.legacyBlock = b;
        this.legacyMeta = meta;
    }

    /** the canonical state of any 1.7.10 block + metadata. */
    public static BlockState of(Block b, int meta) {
        if (b == null) b = Blocks.air;
        if (b instanceof MetaBlock) {
            meta = ((MetaBlock) b).meta;
            b = ((MetaBlock) b).host;
        }
        if (b instanceof IStateBlock) {
            IStateBlock sb = (IStateBlock) b;
            return sb.getStateDefinition().fromMeta(meta, sb.defaultBlockState());
        }
        BlockState[] arr = LEGACY.computeIfAbsent(b, k -> new BlockState[16]);
        BlockState s = arr[meta & 15];
        if (s == null) arr[meta & 15] = s = new BlockState(b, meta & 15);
        return s;
    }

    public static BlockState of(Block b) {
        return b instanceof IStateBlock ? ((IStateBlock) b).defaultBlockState() : of(b, 0);
    }

    /** 1.19.2 Block.getId(state) on 1.7.10: block id << 4 | meta. */
    public static int getId(BlockState s) { return s == null ? 0 : Block.getIdFromBlock(s.getBlock()) << 4 | (s.getMeta() & 15); }

    public static BlockState byId(int id) { return of(Block.getBlockById(id >> 4), id & 15); }

    public Block getBlock() { return def != null ? def.owner : legacyBlock; }

    /** 1.7.10 metadata of this state. */
    public int getMeta() { return def != null ? def.toMeta(this) : legacyMeta; }

    public boolean is(Block b) { return MetaBlock.matches(b, getBlock(), getMeta()); }

    public boolean is(TagKey<?> tag) { return tag != null && tag.contains(getBlock()); }

    /** BlockState.is(TagKey) where the rule could not type the receiver. */
    public static boolean isIn(BlockState s, TagKey<?> tag) { return s != null && s.is(tag); }

    public boolean isRedstoneConductor(net.minecraft.world.IBlockAccess w, dev.porter.shim.BlockPos p) {
        return getBlock().isNormalCube(w, p.getX(), p.getY(), p.getZ());
    }

    public net.minecraft.item.ItemStack getCloneItemStack(net.minecraft.util.MovingObjectPosition target, net.minecraft.world.IBlockAccess w,
            dev.porter.shim.BlockPos p, net.minecraft.entity.player.EntityPlayer player) {
        return w instanceof net.minecraft.world.World ? getBlock().getPickBlock(target, (net.minecraft.world.World) w, p.getX(), p.getY(), p.getZ(), player) : null;
    }

    /** BlockStateBase#use(level, player, hand, hit): the block's use hook (StateBlock) or its 1.7.10 activation. */
    public InteractionResult use(net.minecraft.world.World w, net.minecraft.entity.player.EntityPlayer player, InteractionHand hand, BlockHitResult hit) {
        dev.porter.shim.BlockPos p = hit.getBlockPos();
        Block b = getBlock();
        if (b instanceof StateBlock) return ((StateBlock) b).use(this, w, p, player, hand, hit);
        int side = hit.getDirection() == null ? 0 : hit.getDirection().get3DDataValue();
        net.minecraft.util.Vec3 v = hit.getLocation();
        float hx = v == null ? 0.5F : (float) (v.xCoord - p.getX()), hy = v == null ? 0.5F : (float) (v.yCoord - p.getY()), hz = v == null ? 0.5F : (float) (v.zCoord - p.getZ());
        return b.onBlockActivated(w, p.getX(), p.getY(), p.getZ(), player, side, hx, hy, hz) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** BlockStateBase#attack(level, pos, player) */
    public void attack(net.minecraft.world.World w, dev.porter.shim.BlockPos p, net.minecraft.entity.player.EntityPlayer player) {
        Block b = getBlock();
        if (b instanceof StateBlock) ((StateBlock) b).attack(this, w, p, player);
        else b.onBlockClicked(w, p.getX(), p.getY(), p.getZ(), player);
    }

    /** 1.19.2 BlockState.rotate / mirror (the block's hooks) */
    public BlockState rotate(Rotation r) { return getBlock() instanceof StateBlock ? ((StateBlock) getBlock()).rotate(this, r) : this; }

    public BlockState mirror(Mirror m) { return getBlock() instanceof StateBlock ? ((StateBlock) getBlock()).mirror(this, m) : this; }

    public boolean isAir() { return getBlock().getMaterial() == Material.air; }

    public Material getMaterial() { return getBlock().getMaterial(); }

    public FluidState getFluidState() { return FluidState.of(this); }

    public boolean hasProperty(Property<?> p) { return def != null && def.index(p) >= 0; }

    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> T getValue(Property<T> p) {
        int pi = def == null ? -1 : def.index(p);
        if (pi < 0) throw new IllegalArgumentException("Cannot get property " + p + " as it does not exist in " + this);
        return (T) p.getPossibleValues().get(def.valueIndex(index, pi));
    }

    public <T extends Comparable<T>> Optional<T> getOptionalValue(Property<T> p) {
        return hasProperty(p) ? Optional.of(getValue(p)) : Optional.<T>empty();
    }

    public <T extends Comparable<T>, V extends T> BlockState setValue(Property<T> p, V v) {
        int pi = def == null ? -1 : def.index(p);
        if (pi < 0) throw new IllegalArgumentException("Cannot set property " + p + " as it does not exist in " + this);
        int vi = p.indexOf(v);
        if (vi < 0) throw new IllegalArgumentException("Cannot set property " + p + " to " + v);
        return def.with(index, pi, vi);
    }

    public <T extends Comparable<T>> BlockState cycle(Property<T> p) {
        int pi = def.index(p);
        return def.with(index, pi, (def.valueIndex(index, pi) + 1) % p.count());
    }

    public Collection<Property<?>> getProperties() {
        return def == null ? Collections.<Property<?>>emptyList() : def.getProperties();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ImmutableMap<Property<?>, Comparable<?>> getValues() {
        ImmutableMap.Builder<Property<?>, Comparable<?>> b = ImmutableMap.builder();
        if (def != null) for (Property<?> p : def.getProperties()) b.put(p, getValue((Property) p));
        return b.build();
    }

    @Override
    public String toString() {
        return Block.blockRegistry.getNameForObject(getBlock()) + (def != null ? def.describe(this).toString() : "@" + legacyMeta);
    }
}
