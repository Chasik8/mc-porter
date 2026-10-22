// porter:adapter (IC2C-specific) - the IC2C 2.x energy API (ported, relocated) on the installed IC2C 1.2.6
// energy net: EnergyNet.INSTANCE posts the 1.2.6 load/unload events; the ported 2.x tile interfaces extend the 1.2.6
// ones with default bridge methods (see tools/manual_edits.py) that call back into these helpers.
package ic2cextras.ported.compat;

import ic2cextras.ported.api.energy.IEnergyNet;
import ic2cextras.ported.api.energy.PacketStats;
import ic2cextras.ported.api.energy.TransferStats;
import ic2cextras.ported.api.energy.tile.IEnergyAcceptor;
import ic2cextras.ported.api.energy.tile.IEnergyEmitter;
import ic2cextras.ported.api.energy.tile.IEnergyTile;
import ic2cextras.ported.api.energy.tile.IMultiEnergyTile;
import ic2cextras.ported.shim.BlockPos;
import ic2cextras.ported.shim.Direction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

public class Ic2EnergyBridge implements IEnergyNet {
    private static final String[] TIERS = {"ULV", "LV", "MV", "HV", "EV", "IV"};

    @Override
    public IEnergyTile getTile(World w, BlockPos p) {
        TileEntity te = w.getTileEntity(p.getX(), p.getY(), p.getZ());
        return te instanceof IEnergyTile ? (IEnergyTile) te : null;
    }

    @Override
    public IEnergyTile getSubTile(World w, BlockPos p) { return getTile(w, p); }

    @Override
    public GridTile getTiles(World w, BlockPos p) {
        IEnergyTile t = getTile(w, p);
        return t == null ? null : new GridTile(t, t);
    }

    @Override
    public void addTile(IEnergyTile t) {
        if (t instanceof TileEntity && !((TileEntity) t).getWorldObj().isRemote) {
            MinecraftForge.EVENT_BUS.post(new ic2.api.energy.event.EnergyTileLoadEvent(t));
        }
    }

    @Override
    public void removeTile(IEnergyTile t) {
        if (t instanceof TileEntity && ((TileEntity) t).getWorldObj() != null && !((TileEntity) t).getWorldObj().isRemote) {
            MinecraftForge.EVENT_BUS.post(new ic2.api.energy.event.EnergyTileUnloadEvent(t));
        }
    }

    @Override
    public void updateTile(IEnergyTile t) {
        removeTile(t);
        addTile(t);
    }

    @Override
    public int getPowerFromTier(int tier) { return (int) ic2.api.energy.EnergyNet.instance.getPowerFromTier(tier); }

    @Override
    public int getTierFromPower(int power) { return ic2.api.energy.EnergyNet.instance.getTierFromPower(power); }

    @Override
    public String getDisplayTier(int tier) { return tier >= 0 && tier < TIERS.length ? TIERS[tier] : "T" + tier; }

    @Override
    public TransferStats getStats(IEnergyTile t) { return null; }

    @Override
    public List<PacketStats> getPacketStats(IEnergyTile t) { return Collections.emptyList(); }

    // ------------------------------------------------------------ helpers for the default bridge methods

    public static Direction dir(ForgeDirection d) {
        return d == null || d == ForgeDirection.UNKNOWN ? null : Direction.from3DDataValue(d.ordinal());
    }

    /** a 1.2.6 tile as the 2.x acceptor the ported canEmitEnergy(...) expects. */
    public static IEnergyAcceptor acceptor(final TileEntity te) {
        if (te instanceof IEnergyAcceptor) return (IEnergyAcceptor) te;
        return new IEnergyAcceptor() {
            public boolean canAcceptEnergy(IEnergyEmitter e, Direction d) {
                return te instanceof ic2.api.energy.tile.IEnergyAcceptor && e instanceof TileEntity
                    && ((ic2.api.energy.tile.IEnergyAcceptor) te).acceptsEnergyFrom((TileEntity) e, d == null ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(d.get3DDataValue()));
            }

            public World getWorldObj() { return te == null ? null : te.getWorldObj(); }

            public BlockPos getPosition() { return te == null ? null : new BlockPos(te.xCoord, te.yCoord, te.zCoord); }
        };
    }

    public static IEnergyEmitter emitter(final TileEntity te) {
        if (te instanceof IEnergyEmitter) return (IEnergyEmitter) te;
        return new IEnergyEmitter() {
            public boolean canEmitEnergy(IEnergyAcceptor a, Direction d) {
                return te instanceof ic2.api.energy.tile.IEnergyEmitter && a instanceof TileEntity
                    && ((ic2.api.energy.tile.IEnergyEmitter) te).emitsEnergyTo((TileEntity) a, d == null ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(d.get3DDataValue()));
            }

            public World getWorldObj() { return te == null ? null : te.getWorldObj(); }

            public BlockPos getPosition() { return te == null ? null : new BlockPos(te.xCoord, te.yCoord, te.zCoord); }
        };
    }

    /** 1.2.6 IMetaDelegate.getSubTiles(): the tile entities at the positions of the 2.x sub tiles (LinkedSource etc.). */
    private static final Map<IMultiEnergyTile, List<TileEntity>> LAST_SUB_TILES = Collections
        .synchronizedMap(new WeakHashMap<>());

    public static List<TileEntity> subTiles(IMultiEnergyTile multi) {
        List<IEnergyTile> tiles;
        try {
            tiles = multi.getTiles();
        } catch (RuntimeException e) {
            // the structure was already invalidated (its bounds are gone) while the tile leaves the energy net:
            // unload the sub tiles the net was given the last time we were asked
            List<TileEntity> last = LAST_SUB_TILES.remove(multi);
            if (last != null) return last;
            List<TileEntity> self = new ArrayList<>();
            if (multi instanceof TileEntity) self.add((TileEntity) multi);
            return self;
        }
        List<TileEntity> out = new ArrayList<>();
        for (IEnergyTile t : tiles) {
            if (t instanceof TileEntity) {
                if (!out.contains(t)) out.add((TileEntity) t);
                continue;
            }
            World w = t.getWorldObj();
            BlockPos p = t.getPosition();
            if (w == null || p == null) continue;
            TileEntity te = w.getTileEntity(p.getX(), p.getY(), p.getZ());
            if (te == null) te = proxy(w, p); // 2.x sub tiles are positions (structure blocks without a tile entity)
            if (!out.contains(te)) out.add(te);
        }
        if (multi instanceof TileEntity && !out.contains(multi)) out.add(0, (TileEntity) multi);
        LAST_SUB_TILES.put(multi, new ArrayList<>(out));
        return out;
    }

    /** a detached tile entity carrying a sub tile's position (1.2.6 IMetaDelegate needs TileEntity coordinates). */
    static TileEntity proxy(World w, BlockPos p) {
        TileEntity te = new TileEntity();
        te.setWorldObj(w);
        te.xCoord = p.getX();
        te.yCoord = p.getY();
        te.zCoord = p.getZ();
        return te;
    }
}
