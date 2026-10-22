package ic2cextras.tile;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import ic2.api.reactor.ISteamReactor;
import ic2.core.block.generator.tileentity.TileEntityNuclearReactor;
import ic2.core.block.generator.tileentity.TileEntityReactorChamber;

public final class ReactorHelper {

    private ReactorHelper() {}

    /** The steam reactor this tile belongs to: the reactor itself or the reactor of a chamber, else null. */
    public static TileEntityNuclearReactor asSteamReactor(TileEntity te) {
        if (te instanceof TileEntityReactorChamber) te = ((TileEntityReactorChamber) te).getReactor();
        if (te instanceof TileEntityNuclearReactor && te instanceof ISteamReactor && !te.isInvalid()) {
            return (TileEntityNuclearReactor) te;
        }
        return null;
    }

    /** True for reactor and chamber blocks, so ports never push steam back into the reactor. */
    public static boolean isReactorPart(TileEntity te) {
        return te instanceof TileEntityNuclearReactor || te instanceof TileEntityReactorChamber;
    }

    public static TileEntityNuclearReactor findAdjacent(World world, int x, int y, int z) {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            TileEntityNuclearReactor reactor = asSteamReactor(
                world.getTileEntity(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ));
            if (reactor != null) return reactor;
        }
        return null;
    }
}
