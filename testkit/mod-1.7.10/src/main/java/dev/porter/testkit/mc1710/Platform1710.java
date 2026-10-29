package dev.porter.testkit.mc1710;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cpw.mods.fml.relauncher.ReflectionHelper;
import dev.porter.testkit.mod.Executor;
import dev.porter.testkit.mod.Probe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/** Forge 1.7.10 world access for the shared {@link Executor}. */
final class Platform1710 implements Executor.Platform {
    private final WorldServer world;
    private final EntityPlayerMP realPlayer;
    private FakePlayer player;
    /** Chunks of every plan prepared so far (batch runs clear them between scenarios). */
    private final TreeSet<Long> touched = new TreeSet<Long>();

    Platform1710(WorldServer world, EntityPlayerMP realPlayer) {
        this.world = world;
        this.realPlayer = realPlayer;
    }

    @Override
    public String version() {
        return "forge-1.7.10";
    }

    void prepare(JsonObject plan) {
        TreeSet<Long> chunks = new TreeSet<Long>();
        for (JsonElement e : plan.getAsJsonArray("ops")) {
            JsonObject op = e.getAsJsonObject();
            if (op.has("at")) {
                int[] p = Executor.pos(op, "at");
                chunks.add(Long.valueOf(((long) (p[0] >> 4) << 32) | ((p[2] >> 4) & 0xffffffffL)));
            }
        }
        // a ForgeChunkManager ticket keeps the chunks loaded and ticking: chunks outside the spawn area are otherwise
        // unloaded by the periodic save (seen as lost tile-entity ticks in long scenarios)
        net.minecraftforge.common.ForgeChunkManager.Ticket ticket = net.minecraftforge.common.ForgeChunkManager.requestTicket(
                PorterTestkit.instance, world, net.minecraftforge.common.ForgeChunkManager.Type.NORMAL);
        touched.addAll(chunks);
        for (Long c : chunks) {
            int cx = (int) (c.longValue() >> 32);
            int cz = (int) c.longValue();
            world.theChunkProviderServer.loadChunk(cx, cz);
            if (ticket != null) {
                net.minecraftforge.common.ForgeChunkManager.forceChunk(ticket, new net.minecraft.world.ChunkCoordIntPair(cx, cz));
            }
        }
        if (realPlayer != null) {
            holdPlayer(realPlayer, plan);
        }
    }

    /** Client mode: keep the creative player flying next to the scenario (the world is a void). */
    static void holdPlayer(EntityPlayerMP realPlayer, JsonObject plan) {
        {
            int[] p = {0, 64, 0};
            for (JsonElement e : plan.getAsJsonArray("ops")) {
                if (e.getAsJsonObject().has("at")) {
                    p = Executor.pos(e.getAsJsonObject(), "at");
                    break;
                }
            }
            realPlayer.capabilities.isFlying = true;
            realPlayer.sendPlayerAbilities();
            realPlayer.setPositionAndUpdate(p[0] + 0.5, p[1] + 0.5, p[2] - 2.5);
        }
    }

    @Override
    public void reset() {
        // blocks first (through setBlock, so the mods under test see the removals), entities after (removal spills items)
        for (Long c : touched) {
            int cx = (int) (c.longValue() >> 32);
            int cz = (int) c.longValue();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!world.theChunkProviderServer.chunkExists(cx + dx, cz + dz)) {
                        continue;
                    }
                    net.minecraft.world.chunk.Chunk chunk = world.getChunkFromChunkCoords(cx + dx, cz + dz);
                    for (net.minecraft.world.chunk.storage.ExtendedBlockStorage st : chunk.getBlockStorageArray()) {
                        if (st == null || st.isEmpty()) {
                            continue;
                        }
                        for (int y = st.getYLocation(); y < st.getYLocation() + 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    int wx = ((cx + dx) << 4) + x;
                                    int wz = ((cz + dz) << 4) + z;
                                    if (world.getBlock(wx, y, wz) != net.minecraft.init.Blocks.air) {
                                        world.setBlock(wx, y, wz, net.minecraft.init.Blocks.air, 0, 3);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Object o : new ArrayList<Object>(world.loadedEntityList)) {
            if (!(o instanceof EntityPlayer)) {
                ((net.minecraft.entity.Entity) o).setDead();
            }
        }
        if (player != null) {
            for (int i = 0; i < player.inventory.mainInventory.length; i++) {
                player.inventory.mainInventory[i] = null;
            }
            for (int i = 0; i < player.inventory.armorInventory.length; i++) {
                player.inventory.armorInventory[i] = null;
            }
            player.inventory.currentItem = 0;
        }
    }

    private FakePlayer player() {
        if (player == null) {
            // Forge's FakePlayer turns openGui into a no-op: this one builds the server container like FML does, so that
            // a click that opens a GUI shows up in the probe player:container (the open-window packet is not sent)
            player = new FakePlayer(world, new com.mojang.authlib.GameProfile(java.util.UUID.fromString("41C82C87-7AFB-4024-BA57-13D2C99CAE77"), "[Minecraft]")) {
                @Override
                public void openGui(Object mod, int modGuiId, net.minecraft.world.World w, int x, int y, int z) {
                    cpw.mods.fml.common.ModContainer mc = cpw.mods.fml.common.FMLCommonHandler.instance().findContainerFor(mod);
                    net.minecraft.inventory.Container c = cpw.mods.fml.common.network.NetworkRegistry.INSTANCE.getRemoteGuiContainer(mc, this, modGuiId, w, x, y, z);
                    if (c != null) {
                        getNextWindowId();
                        closeContainer();
                        openContainer = c;
                        c.windowId = currentWindowId;
                        try {
                            c.addCraftingToCrafters(this);
                        } catch (RuntimeException noConnection) {
                            // the scenario player has no network channel
                        }
                    }
                }
            };
            if (player.playerNetServerHandler == null) {
                // a disconnected connection: Forge code that answers a click with a packet (a cancelled or denied
                // PlayerInteractEvent sends a block change) must not fail for the scenario player
                new net.minecraft.network.NetHandlerPlayServer(net.minecraft.server.MinecraftServer.getServer(),
                        new net.minecraft.network.NetworkManager(false), player);
            }
        }
        return player;
    }

    static int side(String s, int def) {
        if (s == null) {
            return def;
        }
        if (s.equals("down")) {
            return 0;
        }
        if (s.equals("up")) {
            return 1;
        }
        if (s.equals("north")) {
            return 2;
        }
        if (s.equals("south")) {
            return 3;
        }
        if (s.equals("west")) {
            return 4;
        }
        if (s.equals("east")) {
            return 5;
        }
        throw new IllegalArgumentException("bad side " + s);
    }

    static Block block(String id) {
        Block b = (Block) Block.blockRegistry.getObject(id);
        if (b == null || !id.equals(Block.blockRegistry.getNameForObject(b))) {
            throw new IllegalArgumentException("unknown block " + id);
        }
        return b;
    }

    @Override
    public void place(JsonObject op) throws Exception {
        int[] p = Executor.pos(op, "at");
        JsonObject b = op.getAsJsonObject("block");
        Block block = block(b.get("id").getAsString());
        int meta = Executor.integer(b, "meta", 0);
        if (b.has("state")) {
            throw new IllegalArgumentException("block states do not exist in 1.7.10; map " + b.get("id") + " with a meta in the id map");
        }
        String facing = Executor.str(op, "facing", null);
        boolean facingMeta = facing != null && !b.has("meta") && usesFacingMeta(block);
        if (facingMeta) {
            meta = side(facing, 2);
        }
        world.setBlock(p[0], p[1], p[2], block, meta, 3);
        if (world.getBlockMetadata(p[0], p[1], p[2]) != meta) {
            // e.g. BlockFurnace.onBlockAdded re-orients itself towards open space
            world.setBlockMetadataWithNotify(p[0], p[1], p[2], meta, 3);
        }
        TileEntity te = world.getTileEntity(p[0], p[1], p[2]);
        if (facing != null && te != null && !facingMeta) {
            applyFacing(te, (short) side(facing, 2));
        }
        if (b.has("nbt") && te != null) {
            NBTTagCompound tag = new NBTTagCompound();
            te.writeToNBT(tag);
            NBTTagCompound extra = (NBTTagCompound) toTag(b.get("nbt"));
            for (Object k : extra.func_150296_c()) {
                tag.setTag((String) k, extra.getTag((String) k).copy());
            }
            te.readFromNBT(tag);
            te.markDirty();
        }
    }

    /** Vanilla blocks whose meta is the facing (furnace, chest, dispenser...). */
    private static boolean usesFacingMeta(Block b) {
        return b instanceof net.minecraft.block.BlockFurnace || b instanceof net.minecraft.block.BlockChest
                || b instanceof net.minecraft.block.BlockDispenser || b instanceof net.minecraft.block.BlockLadder;
    }

    /** Tech-mod style machines keep their facing in the tile entity: setFacing(short) (IWrenchable), best effort. */
    private static void applyFacing(TileEntity te, short facing) throws Exception {
        for (java.lang.reflect.Method m : te.getClass().getMethods()) {
            if (m.getName().equals("setFacing") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == short.class) {
                m.invoke(te, Short.valueOf(facing));
                return;
            }
        }
    }

    private TileEntity te(int[] p) {
        TileEntity te = world.getTileEntity(p[0], p[1], p[2]);
        if (te == null) {
            throw new IllegalStateException("no tile entity at " + p[0] + "," + p[1] + "," + p[2]);
        }
        return te;
    }

    @Override
    public int fill(JsonObject op) {
        TileEntity te = te(Executor.pos(op, "at"));
        String id = op.getAsJsonObject("fluid").get("id").getAsString();
        Fluid f = FluidRegistry.getFluid(id);
        if (f == null) {
            throw new IllegalArgumentException("unknown fluid " + id + " (1.7.10 fluid names have no namespace)");
        }
        if (!(te instanceof IFluidHandler)) {
            throw new IllegalStateException("tile entity " + te.getClass().getName() + " is not an IFluidHandler");
        }
        ForgeDirection dir = ForgeDirection.getOrientation(side(Executor.str(op, "side", null), 6));
        return ((IFluidHandler) te).fill(dir, new FluidStack(f, op.get("amount").getAsInt()), true);
    }

    static ItemStack stack(JsonObject item, int count) {
        String id = item.get("id").getAsString();
        Item it = (Item) Item.itemRegistry.getObject(id);
        if (it == null || !id.equals(Item.itemRegistry.getNameForObject(it))) {
            throw new IllegalArgumentException("unknown item " + id);
        }
        ItemStack s = new ItemStack(it, count, Executor.integer(item, "meta", 0));
        if (item.has("nbt")) {
            s.setTagCompound((NBTTagCompound) toTag(item.get("nbt")));
        }
        return s;
    }

    @Override
    public String invoke(JsonObject op) throws Exception {
        TileEntity te = te(Executor.pos(op, "at"));
        String name = op.get("method").getAsString();
        com.google.gson.JsonArray args = op.has("args") ? op.getAsJsonArray("args") : new com.google.gson.JsonArray();
        for (java.lang.reflect.Method m : te.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.size()) {
                continue;
            }
            Object[] values = new Object[args.size()];
            boolean ok = true;
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < values.length && ok; i++) {
                com.google.gson.JsonElement e = args.get(i);
                Class<?> t = types[i];
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().equals("$player") && t.isAssignableFrom(player().getClass())) {
                    values[i] = player();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && (t == boolean.class || t == Boolean.class)) {
                    values[i] = e.getAsBoolean();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                    if (t == int.class) {
                        values[i] = e.getAsInt();
                    } else if (t == long.class) {
                        values[i] = e.getAsLong();
                    } else if (t == double.class) {
                        values[i] = e.getAsDouble();
                    } else if (t == float.class) {
                        values[i] = e.getAsFloat();
                    } else if (t == short.class) {
                        values[i] = e.getAsShort();
                    } else if (t == byte.class) {
                        values[i] = e.getAsByte();
                    } else {
                        ok = false;
                    }
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && t == String.class) {
                    values[i] = e.getAsString();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && t.isEnum()) {
                    // an enum parameter (a direction, a mode): the constant named by the string, case-insensitive
                    Object constant = null;
                    for (Object c : t.getEnumConstants()) {
                        if (((Enum<?>) c).name().equalsIgnoreCase(e.getAsString())) {
                            constant = c;
                        }
                    }
                    if (constant == null) {
                        ok = false;
                    } else {
                        values[i] = constant;
                    }
                } else if (e.isJsonObject()) {
                    // {"$tile": [x, y, z]} = the block entity there; {"$new": "pkg.Class", "args": [...]} = a new object
                    values[i] = objectArg(e.getAsJsonObject(), t);
                    ok = values[i] != null;
                } else {
                    ok = false;
                }
            }
            if (ok) {
                Object r = m.invoke(te, values);
                return "invoke " + name + " -> " + r;
            }
        }
        throw new IllegalStateException("no public method " + name + " with " + args.size() + " matching arguments on " + te.getClass().getName());
    }

    /** Object argument of invoke_tile: {@code {"$tile": [x,y,z]}} or {@code {"$new": "pkg.Class", "args": [...]}} (null = no match). */
    private Object objectArg(JsonObject o, Class<?> want) throws Exception {
        if (o.has("$tile")) {
            com.google.gson.JsonArray a = o.getAsJsonArray("$tile");
            TileEntity t = te(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            return want.isInstance(t) ? t : null;
        }
        if (!o.has("$new")) {
            return null;
        }
        Class<?> c = Class.forName(o.get("$new").getAsString());
        com.google.gson.JsonArray a = o.has("args") ? o.getAsJsonArray("args") : new com.google.gson.JsonArray();
        for (java.lang.reflect.Constructor<?> k : c.getConstructors()) {
            Class<?>[] pt = k.getParameterTypes();
            if (pt.length != a.size() || !want.isAssignableFrom(c)) {
                continue;
            }
            Object[] v = new Object[pt.length];
            boolean ok = true;
            for (int i = 0; i < pt.length && ok; i++) {
                com.google.gson.JsonElement e = a.get(i);
                if (e.isJsonObject()) {
                    v[i] = objectArg(e.getAsJsonObject(), pt[i]);
                    ok = v[i] != null;
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && pt[i] == String.class) {
                    v[i] = e.getAsString();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && pt[i] == boolean.class) {
                    v[i] = e.getAsBoolean();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && pt[i] == int.class) {
                    v[i] = e.getAsInt();
                } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && pt[i] == double.class) {
                    v[i] = e.getAsDouble();
                } else {
                    ok = false;
                }
            }
            if (ok) {
                return k.newInstance(v);
            }
        }
        return null;
    }

    @Override
    public double energy(JsonObject op) throws Exception {
        TileEntity te = te(Executor.pos(op, "at"));
        double amount = op.get("amount").getAsDouble();
        net.minecraftforge.common.util.ForgeDirection dir = net.minecraftforge.common.util.ForgeDirection.UNKNOWN;
        String sideName = Executor.str(op, "side", null);
        if (sideName != null) {
            dir = net.minecraftforge.common.util.ForgeDirection.getOrientation(side(sideName, 1));
        }
        // EU sink (reflection: the test mod does not depend on the EU API mod): injectEnergy(dir, amount, voltage) returns the
        // amount NOT accepted; the voltage defaults to the sink's tier (8 * 4^tier)
        for (java.lang.reflect.Method m : te.getClass().getMethods()) {
            if (m.getName().equals("injectEnergy") && m.getParameterTypes().length == 3 && m.getParameterTypes()[1] == double.class) {
                double voltage = op.has("voltage") ? op.get("voltage").getAsDouble() : 0;
                if (voltage <= 0) {
                    try {
                        voltage = 8 * Math.pow(4, ((Number) te.getClass().getMethod("getSinkTier").invoke(te)).intValue());
                    } catch (NoSuchMethodException e) {
                        voltage = 32;
                    }
                }
                double rest = ((Number) m.invoke(te, dir, amount, voltage)).doubleValue();
                return amount - rest;
            }
        }
        // RF (CoFH IEnergyReceiver): receiveEnergy(dir, int, simulate) returns the amount accepted
        for (java.lang.reflect.Method m : te.getClass().getMethods()) {
            if (m.getName().equals("receiveEnergy") && m.getParameterTypes().length == 3) {
                return ((Number) m.invoke(te, dir, (int) amount, Boolean.FALSE)).doubleValue();
            }
        }
        throw new IllegalStateException("tile entity " + te.getClass().getName() + " has no energy input (injectEnergy / receiveEnergy)");
    }

    @Override
    public int insert(JsonObject op) {
        TileEntity te = te(Executor.pos(op, "at"));
        if (!(te instanceof IInventory)) {
            throw new IllegalStateException("tile entity " + te.getClass().getName() + " is not an IInventory");
        }
        IInventory inv = (IInventory) te;
        JsonObject item = op.getAsJsonObject("item");
        int count = Executor.integer(op, "count", Executor.integer(item, "count", 1));
        ItemStack s = stack(item, count);
        int slot = Executor.integer(op, "slot", -1);
        String sideName = Executor.str(op, "side", null);
        List<Integer> slots = new ArrayList<Integer>();
        if (slot >= 0) {
            slots.add(Integer.valueOf(slot));
        } else if (sideName != null && inv instanceof ISidedInventory) {
            for (int i : ((ISidedInventory) inv).getAccessibleSlotsFromSide(side(sideName, 1))) {
                slots.add(Integer.valueOf(i));
            }
        } else {
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                slots.add(Integer.valueOf(i));
            }
        }
        int left = count;
        for (Integer i : slots) {
            if (left <= 0) {
                break;
            }
            if (slot < 0 && !inv.isItemValidForSlot(i.intValue(), s)) {
                continue;
            }
            if (sideName != null && inv instanceof ISidedInventory
                    && !((ISidedInventory) inv).canInsertItem(i.intValue(), s, side(sideName, 1))) {
                continue;
            }
            ItemStack cur = inv.getStackInSlot(i.intValue());
            int max = Math.min(s.getMaxStackSize(), inv.getInventoryStackLimit());
            if (cur == null) {
                int n = Math.min(left, max);
                ItemStack put = s.copy();
                put.stackSize = n;
                inv.setInventorySlotContents(i.intValue(), put);
                left -= n;
            } else if (cur.isItemEqual(s) && ItemStack.areItemStackTagsEqual(cur, s)) {
                int n = Math.min(left, max - cur.stackSize);
                if (n > 0) {
                    cur.stackSize += n;
                    inv.setInventorySlotContents(i.intValue(), cur);
                    left -= n;
                }
            }
        }
        inv.markDirty();
        return count - left;
    }

    @Override
    public String interact(JsonObject op) {
        int[] p = Executor.pos(op, "at");
        FakePlayer pl = player();
        pl.setPosition(p[0] + 0.5, p[1] + 1.5, p[2] + 0.5);
        ItemStack held = null;
        if (op.has("holding")) {
            JsonObject h = op.getAsJsonObject("holding");
            held = stack(h, Executor.integer(h, "count", 1));
        }
        pl.inventory.currentItem = 0;
        stashHand(pl); // items a previous click put into the hand stay in the inventory, like a player switching slots
        pl.inventory.mainInventory[0] = held;
        pl.setSneaking(op.has("sneaking") && op.get("sneaking").getAsBoolean());
        int side = side(Executor.str(op, "side", null), 1);
        float hx = 0.5f + 0.5f * net.minecraft.util.Facing.offsetsXForSide[side];
        float hy = 0.5f + 0.5f * net.minecraft.util.Facing.offsetsYForSide[side];
        float hz = 0.5f + 0.5f * net.minecraft.util.Facing.offsetsZForSide[side];
        boolean r;
        if (op.has("use")) {
            // use: finish = right-click in the air with the held item, then complete the use as if the player held the
            // button for its whole duration (food and drinks apply their effects in ItemStack#onFoodEaten)
            r = pl.theItemInWorldManager.tryUseItem(pl, world, held);
            ItemStack inUse = pl.inventory.getCurrentItem() != null ? pl.inventory.getCurrentItem() : held; // getItemInUse is client-only
            if ("finish".equals(op.get("use").getAsString()) && inUse != null) {
                inUse.onFoodEaten(world, pl);
            }
        } else {
            r = pl.theItemInWorldManager.activateBlockOrUseItem(pl, world, held, p[0], p[1], p[2], side, hx, hy, hz);
        }
        pl.setSneaking(false);
        stashHand(pl);
        System.out.println("[porter-testkit] interact at " + p[0] + "," + p[1] + "," + p[2] + " -> " + r + ", container " + pl.openContainer.getClass().getName());
        lastContainer = pl.openContainer != pl.inventoryContainer ? pl.openContainer.getClass().getName() : null;
        if (pl.openContainer != pl.inventoryContainer) {
            pl.closeContainer(); // no packet: fake players have no connection
        }
        return "interact " + r;
    }

    @Override
    public String breakBlock(JsonObject op) {
        int[] p = Executor.pos(op, "at");
        boolean drops = !op.has("drops") || op.get("drops").getAsBoolean();
        String by = Executor.str(op, "by", "player");
        boolean ok;
        if (by.equals("world")) {
            ok = !world.isAirBlock(p[0], p[1], p[2]);
            world.func_147480_a(p[0], p[1], p[2], drops); // World.breakBlock(x, y, z, dropItems)
        } else {
            FakePlayer pl = player();
            pl.setPosition(p[0] + 0.5, p[1] + 1.5, p[2] + 0.5);
            ItemStack held = null;
            if (op.has("holding")) {
                JsonObject h = op.getAsJsonObject("holding");
                held = stack(h, Executor.integer(h, "count", 1));
            }
            pl.inventory.currentItem = 0;
            stashHand(pl);
            pl.inventory.mainInventory[0] = held;
            ok = pl.theItemInWorldManager.tryHarvestBlock(p[0], p[1], p[2]);
            stashHand(pl);
        }
        System.out.println("[porter-testkit] break_block " + by + " at " + p[0] + "," + p[1] + "," + p[2] + " -> " + ok);
        return "break " + by + " " + ok;
    }

    @Override
    public String explode(JsonObject op) {
        int[] p = Executor.pos(op, "at");
        float power = op.has("power") ? op.get("power").getAsFloat() : 4.0f;
        boolean fire = op.has("fire") && op.get("fire").getAsBoolean();
        boolean destroy = !op.has("destroy") || op.get("destroy").getAsBoolean();
        world.newExplosion(null, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, power, fire, destroy);
        return "explode power " + power;
    }

    /** class of the container the last interact opened for the scenario player (null = none): probe player:container */
    private static String lastContainer;

    /** Moves the hand stack (slot 0) into another inventory slot (or drops it), leaving the hand empty. */
    private static void stashHand(FakePlayer pl) {
        ItemStack left = pl.inventory.mainInventory[0];
        pl.inventory.mainInventory[0] = null;
        if (left == null || left.stackSize <= 0) {
            return;
        }
        for (int i = 1; i < pl.inventory.mainInventory.length && left.stackSize > 0; i++) {
            ItemStack cur = pl.inventory.mainInventory[i];
            if (cur == null) {
                pl.inventory.mainInventory[i] = left;
                return;
            }
            if (cur.isItemEqual(left) && ItemStack.areItemStackTagsEqual(cur, left) && cur.stackSize < cur.getMaxStackSize()) {
                int n = Math.min(left.stackSize, cur.getMaxStackSize() - cur.stackSize);
                cur.stackSize += n;
                left.stackSize -= n;
            }
        }
        if (left.stackSize > 0) {
            pl.dropPlayerItemWithRandomChoice(left, false);
        }
    }

    @Override
    public JsonObject gui(JsonObject op, int attempt) throws Exception {
        if (realPlayer == null) {
            throw new UnsupportedOperationException(op.get("op").getAsString() + " needs side=client");
        }
        if (attempt == 0 && op.get("op").getAsString().equals("open_gui")) {
            // the real player clicks with an empty hand, or holds the item it is going to use
            realPlayer.inventory.currentItem = 0;
            realPlayer.inventory.mainInventory[0] = op.has("item") ? stack(op.getAsJsonObject("item"), 1) : null;
            realPlayer.inventoryContainer.detectAndSendChanges();
        }
        return ClientDriver.INSTANCE.submit(op, attempt);
    }

    @Override
    public JsonElement probe(Probe p) throws Exception {
        if (p.probe.equals("client_field")) {
            // client_field:[x,y,z]/name[/name...]: like field:, but on the CLIENT world's block entity (client runs only): what the
            // GUI of the open screen sees. A segment is a field name, else a no-arg method name.
            Object v = ClientDriver.clientTile(p.pos);
            for (String seg : p.path) {
                if (v == null) {
                    break;
                }
                try {
                    v = Executor.field(v, seg);
                } catch (NoSuchFieldException e) {
                    v = Executor.call(v, seg);
                }
            }
            return Executor.plain(v);
        }
        if (p.probe.equals("real_player")) {
            // client runs: the player of the integrated server (the one the client controls), looked up by name every time
            // because a dimension change replaces the entity. real_player:pos -> [x, y, z, dimension] (block coordinates)
            java.util.List<?> pls = net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList;
            if (pls.isEmpty()) {
                return JsonNull.INSTANCE;
            }
            EntityPlayer rp = (EntityPlayer) pls.get(0);
            if ("pos".equals(p.target)) {
                JsonArray pos = new JsonArray();
                pos.add(new JsonPrimitive((int) Math.floor(rp.posX)));
                pos.add(new JsonPrimitive((int) Math.floor(rp.posY)));
                pos.add(new JsonPrimitive((int) Math.floor(rp.posZ)));
                pos.add(new JsonPrimitive(rp.dimension));
                return Executor.path(pos, p.path);
            }
            if ("container".equals(p.target)) {
                return new JsonPrimitive(rp.openContainer == null ? "null" : rp.openContainer.getClass().getName());
            }
            throw new IllegalArgumentException("real_player:" + p.target + " (known: pos, container)");
        }
        if (p.probe.equals("player")) {
            EntityPlayer pl = player();
            if ("pos".equals(p.target)) {
                // player:pos -> [x, y, z, dimension] (block coordinates of the scenario player)
                JsonArray pos = new JsonArray();
                pos.add(new JsonPrimitive((int) Math.floor(pl.posX)));
                pos.add(new JsonPrimitive((int) Math.floor(pl.posY)));
                pos.add(new JsonPrimitive((int) Math.floor(pl.posZ)));
                pos.add(new JsonPrimitive(pl.dimension));
                return Executor.path(pos, p.path);
            }
            if ("container".equals(p.target)) {
                return lastContainer == null ? JsonNull.INSTANCE : new JsonPrimitive(lastContainer);
            }
            if ("held".equals(p.target)) {
                return Executor.path(stackJson(pl.inventory.getCurrentItem()), p.path);
            }
            if ("effects".equals(p.target)) {
                // player:effects -> [{id, name, duration, amplifier}] of the active potion effects of the scenario player
                JsonArray fx = new JsonArray();
                for (Object o : pl.getActivePotionEffects()) {
                    net.minecraft.potion.PotionEffect e = (net.minecraft.potion.PotionEffect) o;
                    JsonObject j = new JsonObject();
                    j.addProperty("id", e.getPotionID());
                    j.addProperty("name", e.getEffectName());
                    j.addProperty("duration", e.getDuration());
                    j.addProperty("amplifier", e.getAmplifier());
                    fx.add(j);
                }
                return Executor.path(fx, p.path);
            }
            JsonArray a = new JsonArray();
            for (int i = 0; i < pl.inventory.getSizeInventory(); i++) {
                ItemStack s = pl.inventory.getStackInSlot(i);
                if (s != null) {
                    JsonObject o = stackJson(s).getAsJsonObject();
                    o.addProperty("slot", i);
                    a.add(o);
                }
            }
            return Executor.path(a, p.path);
        }
        if (p.probe.equals("recipes")) {
            // recipes:<registry name> -> number of crafting recipes (shaped, shapeless, ore) whose output is that item or block
            net.minecraft.item.Item it = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry.getObject(p.target);
            if (it == null) {
                net.minecraft.block.Block b = (net.minecraft.block.Block) Block.blockRegistry.getObject(p.target);
                it = b == null ? null : net.minecraft.item.Item.getItemFromBlock(b);
            }
            // recipes:<registry name>/craftable -> only recipes whose every ingredient resolves (an ore recipe with an empty
            // OreDictionary entry, or a null ingredient, can never be crafted and is not counted)
            boolean onlyCraftable = p.path.length > 0 && "craftable".equals(p.path[0]);
            int n = 0;
            for (Object r : net.minecraft.item.crafting.CraftingManager.getInstance().getRecipeList()) {
                ItemStack out = ((net.minecraft.item.crafting.IRecipe) r).getRecipeOutput();
                if (it != null && out != null && out.getItem() == it && (!onlyCraftable || ingredientsResolve(r))) {
                    n++;
                }
            }
            return new JsonPrimitive(n);
        }
        if (p.probe.equals("registry")) {
            // registry:<blocks|items|fluids>[/<prefix>] -> sorted registry names (with meta-less ids), for writing id maps
            java.util.TreeSet<String> names = new java.util.TreeSet<String>();
            if ("blocks".equals(p.target)) {
                for (Object k : Block.blockRegistry.getKeys()) {
                    names.add(String.valueOf(k));
                }
            } else if ("items".equals(p.target)) {
                for (Object k : Item.itemRegistry.getKeys()) {
                    names.add(String.valueOf(k));
                }
            } else if ("entities".equals(p.target)) {
                for (Object k : net.minecraft.entity.EntityList.stringToClassMapping.keySet()) {
                    names.add(String.valueOf(k));
                }
            } else if ("fluids".equals(p.target)) {
                names.addAll(FluidRegistry.getRegisteredFluids().keySet());
            } else {
                throw new IllegalArgumentException("registry:<blocks|items|fluids>");
            }
            String prefix = p.path.length > 0 ? p.path[0] : "";
            JsonArray a = new JsonArray();
            for (String n : names) {
                if (n.startsWith(prefix)) {
                    a.add(new JsonPrimitive(n));
                }
            }
            return a;
        }
        p.requirePos();
        int x = p.pos[0];
        int y = p.pos[1];
        int z = p.pos[2];
        if (p.probe.equals("block")) {
            return new JsonPrimitive(Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)));
        }
        if (p.probe.equals("light")) {
            // stored block light (0..15) at a position
            return new JsonPrimitive(world.getSavedLightValue(net.minecraft.world.EnumSkyBlock.Block, x, y, z));
        }
        if (p.probe.equals("block_state")) {
            JsonObject o = new JsonObject();
            o.addProperty("meta", world.getBlockMetadata(x, y, z));
            return Executor.path(o, p.path);
        }
        if (p.probe.equals("drops")) {
            @SuppressWarnings("unchecked")
            List<EntityItem> items = world.getEntitiesWithinAABB(EntityItem.class,
                    AxisAlignedBB.getBoundingBox(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4));
            List<JsonObject> list = new ArrayList<JsonObject>();
            for (EntityItem ei : items) {
                list.add(stackJson(ei.getEntityItem()).getAsJsonObject());
            }
            Collections.sort(list, new Comparator<JsonObject>() {
                @Override
                public int compare(JsonObject a, JsonObject b) {
                    return a.toString().compareTo(b.toString());
                }
            });
            JsonArray a = new JsonArray();
            for (JsonObject o : list) {
                a.add(o);
            }
            return Executor.path(a, p.path);
        }
        if (p.probe.equals("spawn_check")) {
            // Forge's natural-spawn check for a fresh entity at the block's centre: allow / deny / default
            // (1.7.10 entity names, e.g. spawn_check:[0,64,0]/Zombie; map 1.19.2 ids with the scenario keyMap)
            if (p.path.length == 0) {
                throw new IllegalArgumentException("spawn_check probe needs /<entity name>");
            }
            net.minecraft.entity.Entity e = net.minecraft.entity.EntityList.createEntityByName(p.path[0], world);
            if (!(e instanceof net.minecraft.entity.EntityLiving)) {
                return JsonNull.INSTANCE;
            }
            e.setLocationAndAngles(x + 0.5, y, z + 0.5, 0F, 0F);
            cpw.mods.fml.common.eventhandler.Event.Result r = net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(
                    (net.minecraft.entity.EntityLiving) e, world, x + 0.5F, y, z + 0.5F);
            return new JsonPrimitive(r.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (p.probe.equals("entity_new")) {
            // entity_new:[x,y,z]/<EntityList name> -> class name of a fresh entity spawned there (null = the name is not registered):
            // checks the entity registration and that its World constructor works
            if (p.path.length == 0) {
                throw new IllegalArgumentException("entity_new probe needs /<entity name>");
            }
            net.minecraft.entity.Entity e = net.minecraft.entity.EntityList.createEntityByName(p.path[0], world);
            if (e == null) {
                return JsonNull.INSTANCE;
            }
            e.setPosition(x + 0.5, y, z + 0.5);
            world.spawnEntityInWorld(e);
            return new JsonPrimitive(e.getClass().getName());
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if (te == null) {
            return JsonNull.INSTANCE;
        }
        if (p.probe.equals("tile")) {
            // tile:[x,y,z] -> class name of the block entity (null = none): "the block created its tile entity"
            return new JsonPrimitive(te.getClass().getName());
        }
        if (p.probe.equals("nbt")) {
            NBTTagCompound tag = new NBTTagCompound();
            te.writeToNBT(tag);
            tag.removeTag("x");
            tag.removeTag("y");
            tag.removeTag("z");
            tag.removeTag("id");
            return Executor.path(json(tag), p.path);
        }
        if (p.probe.equals("item") || p.probe.equals("inv")) {
            if (!(te instanceof IInventory)) {
                return JsonNull.INSTANCE;
            }
            IInventory inv = (IInventory) te;
            if (p.probe.equals("item") && p.index >= 0) {
                return Executor.path(stackJson(p.index < inv.getSizeInventory() ? inv.getStackInSlot(p.index) : null), p.path);
            }
            JsonArray a = new JsonArray();
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                a.add(stackJson(inv.getStackInSlot(i)));
            }
            return Executor.path(a, p.path);
        }
        if (p.probe.equals("fluid")) {
            if (!(te instanceof IFluidHandler)) {
                return JsonNull.INSTANCE;
            }
            FluidTankInfo[] tanks = ((IFluidHandler) te).getTankInfo(ForgeDirection.getOrientation(side(p.side, 6)));
            JsonArray a = new JsonArray();
            if (tanks != null) {
                for (FluidTankInfo t : tanks) {
                    JsonObject o = new JsonObject();
                    FluidStack fs = t.fluid;
                    o.add("fluid", fs == null || fs.amount <= 0 ? JsonNull.INSTANCE : new JsonPrimitive(FluidRegistry.getFluidName(fs)));
                    o.addProperty("amount", fs == null ? 0 : fs.amount);
                    o.addProperty("capacity", t.capacity);
                    a.add(o);
                }
            }
            if (p.index >= 0) {
                return Executor.path(p.index < a.size() ? a.get(p.index) : JsonNull.INSTANCE, p.path);
            }
            return Executor.path(a, p.path);
        }
        if (p.probe.equals("energy")) {
            // no common energy API in 1.7.10 Forge; RF-style getEnergyStored(ForgeDirection) if present
            for (java.lang.reflect.Method m : te.getClass().getMethods()) {
                if (m.getName().equals("getEnergyStored") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == ForgeDirection.class) {
                    return Executor.plain(m.invoke(te, ForgeDirection.getOrientation(side(p.side, 6))));
                }
            }
            return JsonNull.INSTANCE;
        }
        if (p.probe.equals("field") || p.probe.equals("call")) {
            if (p.path.length == 0) {
                throw new IllegalArgumentException(p.probe + " probe needs /name");
            }
            Object v = te;
            for (String seg : p.path) {
                if (v == null) {
                    break;
                }
                v = p.probe.equals("call") ? Executor.call(v, seg) : Executor.field(v, seg);
            }
            return Executor.plain(v);
        }
        throw new IllegalArgumentException("unknown probe " + p.probe);
    }

    /** False when an ore recipe has an ingredient with an empty OreDictionary entry (the recipe can never match). */
    static boolean ingredientsResolve(Object recipe) {
        Object[] in = null;
        if (recipe instanceof net.minecraftforge.oredict.ShapedOreRecipe) {
            in = ((net.minecraftforge.oredict.ShapedOreRecipe) recipe).getInput();
        } else if (recipe instanceof net.minecraftforge.oredict.ShapelessOreRecipe) {
            in = ((net.minecraftforge.oredict.ShapelessOreRecipe) recipe).getInput().toArray();
        }
        if (in == null) {
            return true;
        }
        boolean shapeless = recipe instanceof net.minecraftforge.oredict.ShapelessOreRecipe;
        for (Object o : in) {
            if (o == null) {
                if (shapeless) {
                    return false;
                }
            } else if (o instanceof java.util.List && ((java.util.List<?>) o).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static JsonElement stackJson(ItemStack s) {
        if (s == null || s.stackSize <= 0 || s.getItem() == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", Item.itemRegistry.getNameForObject(s.getItem()));
        o.addProperty("count", s.stackSize);
        if (s.getItemDamage() != 0) {
            o.addProperty("damage", s.getItemDamage());
        }
        if (s.getTagCompound() != null && !s.getTagCompound().hasNoTags()) {
            o.add("nbt", json(s.getTagCompound()));
        }
        return o;
    }

    static JsonElement json(NBTBase t) {
        if (t == null) {
            return JsonNull.INSTANCE;
        }
        if (t instanceof NBTTagCompound) {
            NBTTagCompound c = (NBTTagCompound) t;
            JsonObject o = new JsonObject();
            TreeSet<String> keys = new TreeSet<String>();
            for (Object k : c.func_150296_c()) {
                keys.add((String) k);
            }
            for (String k : keys) {
                o.add(k, json(c.getTag(k)));
            }
            return o;
        }
        if (t instanceof NBTTagList) {
            List<?> raw = ReflectionHelper.getPrivateValue(NBTTagList.class, (NBTTagList) t, "field_74747_a", "tagList");
            JsonArray a = new JsonArray();
            for (Object e : raw) {
                a.add(json((NBTBase) e));
            }
            return a;
        }
        if (t instanceof NBTTagByteArray) {
            return Executor.plain(((NBTTagByteArray) t).func_150292_c());
        }
        if (t instanceof NBTTagIntArray) {
            return Executor.plain(((NBTTagIntArray) t).func_150302_c());
        }
        if (t instanceof NBTTagString) {
            return new JsonPrimitive(((NBTTagString) t).func_150285_a_());
        }
        if (t instanceof NBTTagFloat || t instanceof NBTTagDouble) {
            return Executor.plain(Double.valueOf(((NBTBase.NBTPrimitive) t).func_150286_g()));
        }
        if (t instanceof NBTBase.NBTPrimitive) {
            return Executor.plain(Long.valueOf(((NBTBase.NBTPrimitive) t).func_150291_c()));
        }
        return new JsonPrimitive(t.toString());
    }

    static NBTBase toTag(JsonElement e) {
        if (e.isJsonObject()) {
            NBTTagCompound c = new NBTTagCompound();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                c.setTag(en.getKey(), toTag(en.getValue()));
            }
            return c;
        }
        if (e.isJsonArray()) {
            NBTTagList l = new NBTTagList();
            for (JsonElement x : e.getAsJsonArray()) {
                l.appendTag(toTag(x));
            }
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return new NBTTagByte((byte) (p.getAsBoolean() ? 1 : 0));
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE && !p.getAsString().contains(".")) {
                return new NBTTagInt((int) d);
            }
            return new NBTTagDouble(d);
        }
        Object typed = Executor.typedNumber(p.getAsString());
        if (typed instanceof Byte) {
            return new NBTTagByte(((Byte) typed).byteValue());
        }
        if (typed instanceof Short) {
            return new NBTTagShort(((Short) typed).shortValue());
        }
        if (typed instanceof Long) {
            return new NBTTagLong(((Long) typed).longValue());
        }
        if (typed instanceof Float) {
            return new NBTTagFloat(((Float) typed).floatValue());
        }
        if (typed instanceof Double) {
            return new NBTTagDouble(((Double) typed).doubleValue());
        }
        return new NBTTagString(p.getAsString());
    }
}
