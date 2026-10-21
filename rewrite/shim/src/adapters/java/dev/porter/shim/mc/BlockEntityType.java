// porter:adapter (generic) - 1.19.2 BlockEntityType: a factory + valid blocks (1.7.10 registers the class).
package dev.porter.shim.mc;

import dev.porter.shim.BlockPos;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;

public class BlockEntityType<T extends TileEntity> {
    private final BlockEntitySupplier<? extends T> factory;
    private final Set<Block> validBlocks;
    private final String name;

    public BlockEntityType(String name, BlockEntitySupplier<? extends T> factory, Set<Block> validBlocks) {
        this.name = name;
        this.factory = factory;
        this.validBlocks = validBlocks;
    }

    public T create(BlockPos pos, BlockState state) { return factory.create(pos, state); }

    public boolean isValid(BlockState state) { return validBlocks.isEmpty() || validBlocks.contains(state.getBlock()); }

    public void addValidBlocks(Block... blocks) { validBlocks.addAll(Arrays.asList(blocks)); }

    @SuppressWarnings("unchecked")
    public T getBlockEntity(IBlockAccess w, BlockPos pos) {
        TileEntity te = w.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
        if (te == null) return null;
        if (te instanceof ModernTileEntity && ((ModernTileEntity) te).porterType() == this) return (T) te;
        return validBlocks.contains(w.getBlock(pos.getX(), pos.getY(), pos.getZ())) ? (T) te : null;
    }

    public String getName() { return name; }

    @Override
    public String toString() { return "BlockEntityType[" + name + "]"; }

    public interface BlockEntitySupplier<T extends TileEntity> {
        T create(BlockPos pos, BlockState state);
    }

    public static final class Builder<T extends TileEntity> {
        private final BlockEntitySupplier<? extends T> factory;
        private final Set<Block> blocks;

        private Builder(BlockEntitySupplier<? extends T> f, Set<Block> b) {
            this.factory = f;
            this.blocks = b;
        }

        public static <T extends TileEntity> Builder<T> of(BlockEntitySupplier<? extends T> f, Block... blocks) {
            return new Builder<>(f, new HashSet<>(Arrays.asList(blocks)));
        }

        public BlockEntityType<T> build(Object dataFixer) { return new BlockEntityType<>("", factory, blocks); }
    }
}
