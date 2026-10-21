// porter:adapter (generic, no mod content) - 1.19.2 Block API on a 1.7.10 Block. Ported 1.19.2 blocks extend this
// class (their root's "extends Block" is redirected here); it builds the StateDefinition like the 1.19.2 Block
// constructor and turns the 1.7.10 callbacks into the 1.19.2 hooks (use, attack, neighborChanged, onPlace,
// onRemove, setPlacedBy/getStateForPlacement, signals, comparator, drops, pick block, random display ticks).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import dev.porter.shim.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public abstract class StateBlock extends Block implements IStateBlock {
    protected final StateDefinition<Block, BlockState> stateDefinition;
    private BlockState defaultState;
    protected final BlockBehaviour.Properties properties;
    /** tile entity captured before 1.7.10 removes it, for getDrops (1.19.2 loot context BLOCK_ENTITY). */
    private static final ThreadLocal<TileEntity> BROKEN_TILE = new ThreadLocal<>();

    protected StateBlock(BlockBehaviour.Properties props) {
        super(props.material);
        this.properties = props;
        props.apply(this);
        if (this instanceof net.minecraft.block.ITileEntityProvider) this.isBlockContainer = true; // EntityBlock
        StateDefinition.Builder<Block, BlockState> b = new StateDefinition.Builder<>(this);
        createBlockStateDefinition(b);
        this.stateDefinition = b.create();
        this.defaultState = stateDefinition.any();
        if (props.noOcclusion) {
            this.setLightOpacity(0);
            this.opaque = false;
        }
    }

    // ---------------------------------------------------------------- 1.19.2 state API

    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {}

    protected final void registerDefaultState(BlockState s) { this.defaultState = s; }

    @Override
    public final BlockState defaultBlockState() { return defaultState; }

    @Override
    public StateDefinition<Block, BlockState> getStateDefinition() { return stateDefinition; }

    public BlockState rotate(BlockState state, Rotation rotation) { return state; }

    public BlockState mirror(BlockState state, Mirror mirror) { return state; }

    public BlockState getStateForPlacement(BlockPlaceContext ctx) { return defaultBlockState(); }

    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    public boolean propagatesSkylightDown(BlockState state, IBlockAccess w, BlockPos pos) { return false; }

    public Block asBlock() { return this; }

    public Item asItem() { return Item.getItemFromBlock(this); }

    // ---------------------------------------------------------------- 1.19.2 hooks (defaults = vanilla behaviour)

    public InteractionResult use(BlockState state, World w, BlockPos pos, EntityPlayer player, InteractionHand hand, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    public void attack(BlockState state, World w, BlockPos pos, EntityPlayer player) {}

    public void neighborChanged(BlockState state, World w, BlockPos pos, Block from, BlockPos fromPos, boolean moving) {}

    public void onNeighborChange(BlockState state, IBlockAccess w, BlockPos pos, BlockPos neighbor) {}

    public void onPlace(BlockState state, World w, BlockPos pos, BlockState old, boolean moving) {}

    public void onRemove(BlockState state, World w, BlockPos pos, BlockState newState, boolean moving) {}

    public void setPlacedBy(World w, BlockPos pos, BlockState state, EntityLivingBase placer, ItemStack stack) {}

    public void entityInside(BlockState state, World w, BlockPos pos, Entity e) {}

    public void animateTick(BlockState state, World w, BlockPos pos, Random rand) {}

    public void tick(BlockState state, World w, BlockPos pos, Random rand) {}

    public void randomTick(BlockState state, World w, BlockPos pos, Random rand) { tick(state, w, pos, rand); }

    public boolean isSignalSource(BlockState state) { return false; }

    public int getSignal(BlockState state, IBlockAccess w, BlockPos pos, Direction side) { return 0; }

    public int getDirectSignal(BlockState state, IBlockAccess w, BlockPos pos, Direction side) { return 0; }

    public boolean shouldCheckWeakPower(BlockState state, IBlockAccess w, BlockPos pos, Direction side) { return isNormalCube(); }

    public boolean canConnectRedstone(BlockState state, IBlockAccess w, BlockPos pos, Direction side) { return isSignalSource(state) && side != null; }

    public boolean hasAnalogOutputSignal(BlockState state) { return false; }

    public int getAnalogOutputSignal(BlockState state, World w, BlockPos pos) { return 0; }

    public List<ItemStack> getDrops(BlockState state, LootContext.Builder builder) {
        List<ItemStack> l = new ArrayList<>();
        l.add(new ItemStack(this, 1, damageDropped(state.getMeta())));
        return l;
    }

    public ItemStack getCloneItemStack(BlockState state, MovingObjectPosition target, IBlockAccess w, BlockPos pos, EntityPlayer player) {
        return getCloneItemStack(w, pos, state);
    }

    public ItemStack getCloneItemStack(IBlockAccess w, BlockPos pos, BlockState state) {
        Item item = Item.getItemFromBlock(this);
        return item == null ? null : new ItemStack(item, 1, damageDropped(state.getMeta()));
    }

    public void fillItemCategory(CreativeTabs tab, NonNullList<ItemStack> items) {
        items.add(new ItemStack(this));
    }

    // ---------------------------------------------------------------- 1.7.10 callbacks -> 1.19.2 hooks

    protected BlockState state(IBlockAccess w, int x, int y, int z) { return Levels.getBlockState(w, x, y, z); }

    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockHitResult hit = new BlockHitResult(Vec3.createVectorHelper(x + hx, y + hy, z + hz), Direction.from3DDataValue(side), pos, false);
        return use(state(w, x, y, z), w, pos, p, InteractionHand.MAIN_HAND, hit).consumesAction();
    }

    @Override
    public void onBlockClicked(World w, int x, int y, int z, EntityPlayer p) { attack(state(w, x, y, z), w, new BlockPos(x, y, z), p); }

    @Override
    public void onNeighborBlockChange(World w, int x, int y, int z, Block from) {
        neighborChanged(state(w, x, y, z), w, new BlockPos(x, y, z), from, new BlockPos(x, y, z), false);
    }

    @Override
    public void onNeighborChange(IBlockAccess w, int x, int y, int z, int tx, int ty, int tz) {
        onNeighborChange(state(w, x, y, z), w, new BlockPos(x, y, z), new BlockPos(tx, ty, tz));
    }

    @Override
    public void onBlockAdded(World w, int x, int y, int z) {
        onPlace(state(w, x, y, z), w, new BlockPos(x, y, z), BlockState.of(net.minecraft.init.Blocks.air), false);
        ForgeEvents.notifyNeighbors(w, new BlockPos(x, y, z), state(w, x, y, z));
    }

    @Override
    public void breakBlock(World w, int x, int y, int z, Block b, int meta) {
        TileEntity te = w.getTileEntity(x, y, z);
        BlockState old = te instanceof Levels.IStateHolder && ((Levels.IStateHolder) te).porterGetState() != null
            ? stateDefinition.fromMeta(meta, ((Levels.IStateHolder) te).porterGetState()) : BlockState.of(b, meta);
        BROKEN_TILE.set(te);
        onRemove(old, w, new BlockPos(x, y, z), Levels.getBlockState(w, x, y, z), false);
        super.breakBlock(w, x, y, z, b, meta);
        ForgeEvents.notifyNeighbors(w, new BlockPos(x, y, z), Levels.getBlockState(w, x, y, z));
    }

    @Override
    public boolean removedByPlayer(World w, EntityPlayer p, int x, int y, int z, boolean willHarvest) {
        BROKEN_TILE.set(w.getTileEntity(x, y, z));
        return super.removedByPlayer(w, p, x, y, z, willHarvest);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World w, int x, int y, int z, int meta, int fortune) {
        TileEntity te = BROKEN_TILE.get();
        if (te != null && (te.xCoord != x || te.yCoord != y || te.zCoord != z)) te = null;
        if (te == null) te = w.getTileEntity(x, y, z);
        BlockState s = te instanceof Levels.IStateHolder && ((Levels.IStateHolder) te).porterGetState() != null
            ? stateDefinition.fromMeta(meta, ((Levels.IStateHolder) te).porterGetState()) : stateDefinition.fromMeta(meta, defaultState);
        LootContext.Builder b = new LootContext.Builder(w).withParameter(LootContext.Params.ORIGIN, new BlockPos(x, y, z))
            .withParameter(LootContext.Params.BLOCK_STATE, s).withOptionalParameter(LootContext.Params.BLOCK_ENTITY, te);
        BROKEN_TILE.remove();
        List<ItemStack> l = getDrops(s, b);
        ArrayList<ItemStack> out = new ArrayList<>();
        if (l != null) for (ItemStack i : l) if (i != null && i.stackSize > 0) out.add(i);
        return out;
    }

    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState s = getStateForPlacement(new BlockPlaceContext(w, pos, placer, stack));
        if (s != null && s.getBlock() == this) Levels.setBlock(w, pos, s, 2);
        setPlacedBy(w, pos, Levels.getBlockState(w, x, y, z), placer, stack);
    }

    @Override
    public void onEntityCollidedWithBlock(World w, int x, int y, int z, Entity e) { entityInside(state(w, x, y, z), w, new BlockPos(x, y, z), e); }

    @Override
    public void randomDisplayTick(World w, int x, int y, int z, Random r) { animateTick(state(w, x, y, z), w, new BlockPos(x, y, z), r); }

    @Override
    public void updateTick(World w, int x, int y, int z, Random r) { randomTick(state(w, x, y, z), w, new BlockPos(x, y, z), r); }

    @Override
    public boolean canProvidePower() { return isSignalSource(defaultState); }

    /** 1.7.10 passes the side as seen from the neighbour (opposite of 1.19.2's direction argument convention). */
    @Override
    public int isProvidingWeakPower(IBlockAccess w, int x, int y, int z, int side) {
        return getSignal(state(w, x, y, z), w, new BlockPos(x, y, z), Direction.from3DDataValue(side));
    }

    @Override
    public int isProvidingStrongPower(IBlockAccess w, int x, int y, int z, int side) {
        return getDirectSignal(state(w, x, y, z), w, new BlockPos(x, y, z), Direction.from3DDataValue(side));
    }

    @Override
    public boolean canConnectRedstone(IBlockAccess w, int x, int y, int z, int side) {
        // 1.7.10: -1 = up/down, 0 = north(-z), 1 = east(+x), 2 = south(+z), 3 = west(-x) as seen from the wire
        Direction d = side == 0 ? Direction.NORTH : side == 1 ? Direction.EAST : side == 2 ? Direction.SOUTH : side == 3 ? Direction.WEST : null;
        return canConnectRedstone(state(w, x, y, z), w, new BlockPos(x, y, z), d);
    }

    @Override
    public boolean shouldCheckWeakPower(IBlockAccess w, int x, int y, int z, int side) {
        return shouldCheckWeakPower(state(w, x, y, z), w, new BlockPos(x, y, z), Direction.from3DDataValue(side));
    }

    @Override
    public boolean hasComparatorInputOverride() { return hasAnalogOutputSignal(defaultState); }

    @Override
    public int getComparatorInputOverride(World w, int x, int y, int z, int side) {
        return getAnalogOutputSignal(state(w, x, y, z), w, new BlockPos(x, y, z));
    }

    @Override
    public ItemStack getPickBlock(MovingObjectPosition target, World w, int x, int y, int z, EntityPlayer p) {
        return getCloneItemStack(state(w, x, y, z), target, w, new BlockPos(x, y, z), p);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        NonNullList<ItemStack> l = NonNullList.create();
        fillItemCategory(tab, l);
        list.addAll(l);
    }

    @Override
    public int damageDropped(int meta) { return 0; }

    // ---------------------------------------------------------------- shapes 

    /** 1.19.2 getShape: the outline, used for the 1.7.10 block bounds (one box: VoxelShape bounds) */
    public VoxelShape getShape(BlockState state, IBlockAccess w, BlockPos pos, CollisionContext ctx) { return VoxelShape.block(); }

    public VoxelShape getCollisionShape(BlockState state, IBlockAccess w, BlockPos pos, CollisionContext ctx) {
        return properties != null && properties.noCollission ? VoxelShape.empty() : getShape(state, w, pos, ctx);
    }

    private boolean porterReady;

    /** after the subclass constructor (DeferredRegister calls it): light opacity / opaque from the final shape */
    public void porterPostInit() {
        porterReady = true;
        boolean op = isOpaqueCube();
        this.opaque = op;
        this.setLightOpacity(op ? 255 : 0);
        setBlockBoundsForItemRender();
    }

    private VoxelShape shapeOr(BlockState state, IBlockAccess w, BlockPos pos) {
        if (!porterReady) return VoxelShape.block();
        VoxelShape s = getShape(state, w, pos, CollisionContext.empty());
        return s == null ? VoxelShape.block() : s;
    }

    @Override
    public void setBlockBoundsBasedOnState(IBlockAccess w, int x, int y, int z) {
        shapeOr(state(w, x, y, z), w, new BlockPos(x, y, z)).applyBounds(this);
    }

    @Override
    public void setBlockBoundsForItemRender() { shapeOr(defaultState, null, null).applyBounds(this); }

    @Override
    public net.minecraft.util.AxisAlignedBB getCollisionBoundingBoxFromPool(World w, int x, int y, int z) {
        if (!porterReady) return super.getCollisionBoundingBoxFromPool(w, x, y, z);
        VoxelShape s = getCollisionShape(state(w, x, y, z), w, new BlockPos(x, y, z), CollisionContext.empty());
        return s == null || s.isEmpty() ? null : s.bounds().getOffsetBoundingBox(x, y, z);
    }

    @Override
    public net.minecraft.util.AxisAlignedBB getSelectedBoundingBoxFromPool(World w, int x, int y, int z) {
        VoxelShape s = shapeOr(state(w, x, y, z), w, new BlockPos(x, y, z));
        return s.isEmpty() ? net.minecraft.util.AxisAlignedBB.getBoundingBox(x, y, z, x, y, z) : s.bounds().getOffsetBoundingBox(x, y, z);
    }

    @Override
    public boolean isOpaqueCube() { // called by Block.<init> before the subclass is constructed
        return (properties == null || !properties.noOcclusion) && shapeOr(defaultState, null, null).isFullBlock();
    }

    @Override
    public boolean renderAsNormalBlock() { return isOpaqueCube(); }

    // ---------------------------------------------------------------- destroy hooks 

    /** 1.19.2 destroy(level, pos, state): after a player broke the block */
    public void destroy(World level, BlockPos pos, BlockState state) {}

    /** 1.19.2 playerDestroy: harvest (drops) by a player */
    public void playerDestroy(World level, EntityPlayer player, BlockPos pos, BlockState state, TileEntity te, ItemStack tool) {
        super.harvestBlock(level, player, pos.getX(), pos.getY(), pos.getZ(), state.getMeta());
    }

    /** 1.19.2 wasExploded */
    public void wasExploded(World level, BlockPos pos, net.minecraft.world.Explosion explosion) {}

    @Override
    public void onBlockDestroyedByPlayer(World w, int x, int y, int z, int meta) {
        destroy(w, new BlockPos(x, y, z), stateDefinition.fromMeta(meta, defaultState));
    }

    @Override
    public void harvestBlock(World w, EntityPlayer p, int x, int y, int z, int meta) {
        playerDestroy(w, p, new BlockPos(x, y, z), stateDefinition.fromMeta(meta, defaultState), BROKEN_TILE.get(), p.getCurrentEquippedItem());
    }

    @Override
    public void onBlockDestroyedByExplosion(World w, int x, int y, int z, net.minecraft.world.Explosion e) {
        wasExploded(w, new BlockPos(x, y, z), e);
    }

    @Override
    public int getLightValue(IBlockAccess w, int x, int y, int z) {
        if (w.getBlock(x, y, z) != this) return super.getLightValue(w, x, y, z);
        return properties.light.applyAsInt(state(w, x, y, z));
    }

    // ---------------------------------------------------------------- client: icons of 1.19.2 baked models

    /**
     * Icon of one side of a state, {@code side} in world directions (the 1.19.2 model's rotation already applied).
     * 1.19.2 blocks render from baked models; a port whose models resolve sprites in code overrides this (e.g. with
     * its model's sprite lookup). null = the 1.7.10 default (registerBlockIcons / textureName).
     */
    @SideOnly(Side.CLIENT)
    public IIcon getStateIcon(BlockState state, Direction side) { return null; }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess w, int x, int y, int z, int side) {
        IIcon i = w.getBlock(x, y, z) == this ? getStateIcon(state(w, x, y, z), Direction.from3DDataValue(side)) : null;
        return i != null ? i : super.getIcon(w, x, y, z, side);
    }

    /** Item rendering and other meta-only callers: the state the metadata encodes (non-meta properties default). */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        IIcon i = getStateIcon(stateDefinition.fromMeta(meta, defaultState), Direction.from3DDataValue(side));
        return i != null ? i : super.getIcon(side, meta);
    }

    /** No 1.7.10 icon of its own (no textureName): nothing registered, so no MISSING_ICON_BLOCK_* texture error. */
    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        if (textureName != null) super.registerBlockIcons(reg);
        else if (reg instanceof TextureMap) blockIcon = ((TextureMap) reg).getAtlasSprite("missingno");
    }
}
