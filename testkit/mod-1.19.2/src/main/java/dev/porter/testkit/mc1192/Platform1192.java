package dev.porter.testkit.mc1192;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.porter.testkit.mod.Executor;
import dev.porter.testkit.mod.Probe;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Forge 1.19.2 world access for the shared {@link Executor}. */
final class Platform1192 implements Executor.Platform {
    private final MinecraftServer server;
    private final ServerLevel level;
    private final net.minecraft.server.level.ServerPlayer realPlayer;
    private FakePlayer player;
    /** Chunks of every plan prepared so far (batch runs clear them between scenarios). */
    private final TreeSet<Long> touched = new TreeSet<>();

    Platform1192(MinecraftServer server) {
        this(server, null);
    }

    /** Client mode: the real (integrated-server) player executes GUI ops through the client. */
    Platform1192(MinecraftServer server, net.minecraft.server.level.ServerPlayer realPlayer) {
        this.server = server;
        this.level = server.overworld();
        this.realPlayer = realPlayer;
    }

    /** Client mode: keep the creative player flying next to the scenario. */
    static void holdPlayer(net.minecraft.server.level.ServerPlayer p, JsonObject plan) {
        int[] at = {0, 64, 0};
        for (JsonElement e : plan.getAsJsonArray("ops")) {
            if (e.getAsJsonObject().has("at")) {
                at = Executor.pos(e.getAsJsonObject(), "at");
                break;
            }
        }
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        p.teleportTo(p.getLevel(), at[0] + 0.5, at[1] + 0.5, at[2] - 2.5, 0f, 30f);
    }

    @Override
    public String version() {
        return "forge-1.19.2";
    }

    /** Force-loads every chunk the plan touches. */
    void prepare(JsonObject plan) {
        TreeSet<Long> chunks = new TreeSet<>();
        for (JsonElement e : plan.getAsJsonArray("ops")) {
            JsonObject op = e.getAsJsonObject();
            if (op.has("at")) {
                int[] p = Executor.pos(op, "at");
                chunks.add(((long) (p[0] >> 4) << 32) | ((p[2] >> 4) & 0xffffffffL));
            }
        }
        touched.addAll(chunks);
        for (long c : chunks) {
            int cx = (int) (c >> 32);
            int cz = (int) c;
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
        }
    }

    @Override
    public void reset() {
        // blocks first (through setBlock, so the mods under test see the removals), entities after (removal spills items)
        List<BlockPos> blocks = new ArrayList<>();
        for (long c : touched) {
            int cx = (int) (c >> 32);
            int cz = (int) c;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                    if (chunk == null) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        if (sections[i] == null || sections[i].hasOnlyAir()) {
                            continue;
                        }
                        int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
                        for (int y = 0; y < 16; y++) {
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    if (!sections[i].getBlockState(x, y, z).isAir()) {
                                        blocks.add(new BlockPos(((cx + dx) << 4) + x, y0 + y, ((cz + dz) << 4) + z));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (BlockPos p : blocks) {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        List<net.minecraft.world.entity.Entity> entities = new ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (!(e instanceof net.minecraft.world.entity.player.Player)) {
                entities.add(e);
            }
        }
        for (net.minecraft.world.entity.Entity e : entities) {
            e.discard();
        }
        if (player != null) {
            player.getInventory().clearContent();
        }
    }

    private FakePlayer player() {
        if (player == null) {
            player = FakePlayerFactory.getMinecraft(level);
        }
        return player;
    }

    private static BlockPos bp(int[] p) {
        return new BlockPos(p[0], p[1], p[2]);
    }

    private static Direction dir(String side, Direction def) {
        return side == null ? def : Direction.byName(side);
    }

    @Override
    public void place(JsonObject op) {
        BlockPos pos = bp(Executor.pos(op, "at"));
        JsonObject b = op.getAsJsonObject("block");
        String id = b.get("id").getAsString();
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block == null || (ForgeRegistries.BLOCKS.getKey(block) != null && !ForgeRegistries.BLOCKS.getKey(block).toString().equals(id))) {
            throw new IllegalArgumentException("unknown block " + id);
        }
        BlockState state = block.defaultBlockState();
        if (b.has("state")) {
            for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("state").entrySet()) {
                state = with(state, e.getKey(), e.getValue().getAsString());
            }
        }
        String facing = Executor.str(op, "facing", null);
        if (facing != null) {
            Property<?> p = state.getBlock().getStateDefinition().getProperty("facing");
            if (p == null) {
                p = state.getBlock().getStateDefinition().getProperty("horizontal_facing");
            }
            if (p != null) {
                state = with(state, p.getName(), facing);
            }
        }
        level.setBlock(pos, state, 3);
        if (facing != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                applyFacing(be, facing);
            }
        }
        if (b.has("nbt") && level.getBlockEntity(pos) != null) {
            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag tag = be.saveWithoutMetadata();
            tag.merge((CompoundTag) toTag(b.get("nbt")));
            be.load(tag);
            be.setChanged();
        }
    }

    /** Machines that keep their facing in the block entity (tech-mod style setFacing(Direction)), best effort. */
    private static void applyFacing(BlockEntity be, String facing) {
        for (java.lang.reflect.Method m : be.getClass().getMethods()) {
            if (m.getName().equals("setFacing") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Direction.class) {
                try {
                    m.invoke(be, Direction.byName(facing));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("setFacing failed: " + e);
                }
                return;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, String name, String value) {
        Property p = state.getBlock().getStateDefinition().getProperty(name);
        if (p == null) {
            throw new IllegalArgumentException("block " + ForgeRegistries.BLOCKS.getKey(state.getBlock()) + " has no property " + name);
        }
        Optional v = p.getValue(value);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("bad value " + value + " for property " + name);
        }
        return state.setValue(p, (Comparable) v.get());
    }

    private BlockEntity be(int[] p) {
        BlockEntity be = level.getBlockEntity(bp(p));
        if (be == null) {
            throw new IllegalStateException("no block entity at " + bp(p).toShortString());
        }
        return be;
    }

    @Override
    public int fill(JsonObject op) {
        BlockEntity be = be(Executor.pos(op, "at"));
        String id = op.getAsJsonObject("fluid").get("id").getAsString();
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(id));
        if (fluid == null || !ForgeRegistries.FLUIDS.getKey(fluid).toString().equals(id)) {
            throw new IllegalArgumentException("unknown fluid " + id);
        }
        Direction side = dir(Executor.str(op, "side", null), null);
        IFluidHandler h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve()
                .orElseThrow(() -> new IllegalStateException("no fluid handler at " + be.getBlockPos().toShortString()));
        return h.fill(new FluidStack(fluid, op.get("amount").getAsInt()), IFluidHandler.FluidAction.EXECUTE);
    }

    ItemStack stack(JsonObject item, int count) {
        String id = item.get("id").getAsString();
        Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (it == null || !ForgeRegistries.ITEMS.getKey(it).toString().equals(id)) {
            throw new IllegalArgumentException("unknown item " + id);
        }
        ItemStack s = new ItemStack(it, count);
        if (item.has("nbt")) {
            s.setTag((CompoundTag) toTag(item.get("nbt")));
        }
        if (item.has("meta") && item.get("meta").getAsInt() != 0) {
            s.setDamageValue(item.get("meta").getAsInt());
        }
        return s;
    }

    @Override
    public int insert(JsonObject op) {
        BlockEntity be = be(Executor.pos(op, "at"));
        JsonObject item = op.getAsJsonObject("item");
        int count = Executor.integer(op, "count", Executor.integer(item, "count", 1));
        ItemStack s = stack(item, count);
        int slot = Executor.integer(op, "slot", -1);
        String side = Executor.str(op, "side", null);
        if (slot >= 0 && side == null && be instanceof Container c) {
            ItemStack cur = c.getItem(slot);
            if (cur.isEmpty()) {
                c.setItem(slot, s);
            } else if (ItemStack.isSameItemSameTags(cur, s)) {
                cur.grow(count);
                c.setItem(slot, cur);
            } else {
                throw new IllegalStateException("slot " + slot + " holds " + cur);
            }
            c.setChanged();
            return count;
        }
        if (slot >= 0 && side == null) {
            // modded inventories without Container: direct slot access like setInventorySlotContents in 1.7.10
            java.lang.reflect.Method get = method(be, "getStackInSlot", int.class);
            java.lang.reflect.Method set = method(be, "setStackInSlot", int.class, ItemStack.class);
            if (get != null && set != null) {
                try {
                    ItemStack cur = (ItemStack) get.invoke(be, slot);
                    if (cur.isEmpty()) {
                        set.invoke(be, slot, s);
                    } else if (ItemStack.isSameItemSameTags(cur, s)) {
                        cur.grow(count);
                        set.invoke(be, slot, cur);
                    } else {
                        throw new IllegalStateException("slot " + slot + " holds " + cur);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                be.setChanged();
                return count;
            }
        }
        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir(side, null)).resolve()
                .orElseThrow(() -> new IllegalStateException("no item handler at " + be.getBlockPos().toShortString()));
        ItemStack rest = s;
        if (slot >= 0) {
            rest = h.insertItem(slot, rest, false);
        } else {
            for (int i = 0; i < h.getSlots() && !rest.isEmpty(); i++) {
                rest = h.insertItem(i, rest, false);
            }
        }
        be.setChanged();
        return count - rest.getCount();
    }

    /** Moves the main-hand stack into another inventory slot (or drops it), leaving the hand empty. */
    private static void stashHand(FakePlayer pl) {
        ItemStack left = pl.getMainHandItem();
        if (!left.isEmpty()) {
            pl.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            int sel = pl.getInventory().selected;
            for (int i = 0; i < pl.getInventory().items.size() && !left.isEmpty(); i++) {
                if (i == sel) {
                    continue;
                }
                ItemStack cur = pl.getInventory().items.get(i);
                if (cur.isEmpty()) {
                    pl.getInventory().items.set(i, left);
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameTags(cur, left) && cur.getCount() < cur.getMaxStackSize()) {
                    int n = Math.min(left.getCount(), cur.getMaxStackSize() - cur.getCount());
                    cur.grow(n);
                    left.shrink(n);
                }
            }
            if (!left.isEmpty()) {
                pl.drop(left, false);
            }
        }
    }

    private static java.lang.reflect.Method method(Object o, String name, Class<?>... params) {
        try {
            return o.getClass().getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public String interact(JsonObject op) {
        int[] p = Executor.pos(op, "at");
        BlockPos pos = bp(p);
        FakePlayer pl = player();
        pl.setPos(p[0] + 0.5, p[1] + 1.5, p[2] + 0.5);
        stashHand(pl); // items a previous click put into the hand stay in the inventory, like a player switching slots
        if (op.has("holding")) {
            JsonObject h = op.getAsJsonObject("holding");
            pl.setItemInHand(InteractionHand.MAIN_HAND, stack(h, Executor.integer(h, "count", 1)));
        } else {
            pl.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        pl.setShiftKeyDown(op.has("sneaking") && op.get("sneaking").getAsBoolean());
        Direction side = dir(Executor.str(op, "side", null), Direction.UP);
        Vec3 hit = Vec3.atCenterOf(pos).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        InteractionResult r = pl.gameMode.useItemOn(pl, level, pl.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, side, pos, false));
        pl.setShiftKeyDown(false);
        stashHand(pl);
        pl.doCloseContainer();
        return "interact " + r;
    }

    @Override
    public JsonObject gui(JsonObject op, int attempt) {
        if (realPlayer == null) {
            throw new UnsupportedOperationException(op.get("op").getAsString() + " needs side=client");
        }
        if (attempt == 0 && op.get("op").getAsString().equals("open_gui")) {
            // the real player clicks with an empty hand, or holds the item it is going to use
            realPlayer.getInventory().selected = 0;
            realPlayer.getInventory().items.set(0, op.has("item") ? stack(op.getAsJsonObject("item"), 1) : ItemStack.EMPTY);
            realPlayer.inventoryMenu.broadcastChanges();
        }
        return PorterTestkit.guiDriver().submit(op, attempt);
    }

    private java.lang.reflect.Field lightScheduled;
    private java.lang.reflect.Field lightTasks;

    /**
     * The 1.19.2 light engine runs on a worker thread and is only polled while the server waits between ticks; the
     * tight tick loop never waits, so block light would never update. After every tick this schedules the pending
     * light work and waits (bounded, 5 s) until the engine is idle, like a normally paced server between two ticks.
     */
    void settleLight() {
        net.minecraft.server.level.ThreadedLevelLightEngine le = level.getChunkSource().getLightEngine();
        try {
            if (lightScheduled == null) {
                lightScheduled = field(net.minecraft.server.level.ThreadedLevelLightEngine.class, "scheduled", "f_9302_");
                lightTasks = field(net.minecraft.server.level.ThreadedLevelLightEngine.class, "lightTasks", "f_9298_");
            }
            long end = System.nanoTime() + 5_000_000_000L; // bounded, but generous: a loaded machine made 500 ms too short once
            while (System.nanoTime() < end) {
                le.tryScheduleUpdate();
                boolean busy = ((java.util.concurrent.atomic.AtomicBoolean) lightScheduled.get(le)).get()
                        || !((java.util.Collection<?>) lightTasks.get(le)).isEmpty();
                if (!busy) {
                    return;
                }
                Thread.sleep(0, 200_000);
            }
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException("cannot settle the light engine: " + e, e);
        }
    }

    /**
     * Waits until the light engine has been idle for a quiet period (bounded). {@link #settleLight} can see the engine
     * idle while updates are still on their way (they reach {@code lightTasks} through an asynchronous queue); after a
     * reset that removed hundreds of light sources the backlog would otherwise run into the next scenario.
     */
    @Override
    public void beforeSample(JsonArray keys) {
        // the light engine runs on its own thread; a tight tick loop can outrun it, so light samples wait for it
        for (JsonElement k : keys) {
            if (k.getAsString().startsWith("light:")) {
                drainLight();
                return;
            }
        }
    }

    void drainLight() {
        net.minecraft.server.level.ThreadedLevelLightEngine le = level.getChunkSource().getLightEngine();
        try {
            if (lightScheduled == null) {
                lightScheduled = field(net.minecraft.server.level.ThreadedLevelLightEngine.class, "scheduled", "f_9302_");
                lightTasks = field(net.minecraft.server.level.ThreadedLevelLightEngine.class, "lightTasks", "f_9298_");
            }
            long end = System.nanoTime() + 30_000_000_000L;
            long quietSince = -1;
            while (System.nanoTime() < end) {
                le.tryScheduleUpdate();
                boolean busy = ((java.util.concurrent.atomic.AtomicBoolean) lightScheduled.get(le)).get()
                        || !((java.util.Collection<?>) lightTasks.get(le)).isEmpty();
                long now = System.nanoTime();
                if (busy) {
                    quietSince = -1;
                } else if (quietSince < 0) {
                    quietSince = now;
                } else if (now - quietSince > 150_000_000L) {
                    return;
                }
                while (server.pollTask()) {
                    // main-thread tasks the light engine may wait for
                }
                Thread.sleep(2);
            }
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException("cannot drain the light engine: " + e, e);
        }
    }

    private static java.lang.reflect.Field field(Class<?> c, String moj, String srg) throws NoSuchFieldException {
        java.lang.reflect.Field f;
        try {
            f = c.getDeclaredField(srg);
        } catch (NoSuchFieldException e) {
            f = c.getDeclaredField(moj);
        }
        f.setAccessible(true);
        return f;
    }

    @Override
    public JsonElement probe(Probe p) throws Exception {
        switch (p.probe) {
            case "registry": {
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                switch (String.valueOf(p.target)) {
                    case "blocks" -> ForgeRegistries.BLOCKS.getKeys().forEach(k -> names.add(k.toString()));
                    case "items" -> ForgeRegistries.ITEMS.getKeys().forEach(k -> names.add(k.toString()));
                    case "fluids" -> ForgeRegistries.FLUIDS.getKeys().forEach(k -> names.add(k.toString()));
                    default -> throw new IllegalArgumentException("registry:<blocks|items|fluids>");
                }
                String prefix = p.path.length > 0 ? p.path[0] : "";
                JsonArray a = new JsonArray();
                names.stream().filter(n -> n.startsWith(prefix)).forEach(a::add);
                return a;
            }
            case "block": {
                BlockState s = level.getBlockState(bp(posOf(p)));
                return new JsonPrimitive(ForgeRegistries.BLOCKS.getKey(s.getBlock()).toString());
            }
            case "light": {
                // block light (0..15) at a position (the light engine's current value)
                return new JsonPrimitive(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, bp(posOf(p))));
            }
            case "spawn_check": {
                // Forge's natural-spawn check (MobSpawnType.NATURAL, no spawner) for a fresh entity at the block's
                // centre: allow / deny / default. Path = entity id, e.g. spawn_check:[0,64,0]/minecraft:zombie
                if (p.path.length == 0) {
                    throw new IllegalArgumentException("spawn_check probe needs /<entity id>");
                }
                int[] q = posOf(p);
                net.minecraft.world.entity.EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(p.path[0]));
                net.minecraft.world.entity.Entity e = t == null ? null : t.create(level);
                if (!(e instanceof net.minecraft.world.entity.Mob mob)) {
                    return JsonNull.INSTANCE;
                }
                mob.moveTo(q[0] + 0.5, q[1], q[2] + 0.5, 0F, 0F);
                net.minecraftforge.eventbus.api.Event.Result r = net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(
                        mob, level, q[0] + 0.5, q[1], q[2] + 0.5, null, net.minecraft.world.entity.MobSpawnType.NATURAL);
                return new JsonPrimitive(r.name().toLowerCase(java.util.Locale.ROOT));
            }
            case "block_state": {
                BlockState s = level.getBlockState(bp(posOf(p)));
                JsonObject o = new JsonObject();
                List<Property<?>> props = new ArrayList<>(s.getProperties());
                props.sort(Comparator.comparing(Property::getName));
                for (Property<?> prop : props) {
                    o.addProperty(prop.getName(), name(s, prop));
                }
                return Executor.path(o, p.path);
            }
            case "nbt": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                return Executor.path(json(be.saveWithoutMetadata()), p.path);
            }
            case "item": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                if (p.side == null && be instanceof Container c) {
                    return p.index < 0 ? inv(c) : Executor.path(stackJson(c.getItem(p.index)), p.path);
                }
                java.lang.reflect.Method get = method(be, "getStackInSlot", int.class);
                if (p.side == null && p.index >= 0 && get != null) {
                    return Executor.path(stackJson((ItemStack) get.invoke(be, p.index)), p.path);
                }
                IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir(p.side, null)).resolve().orElse(null);
                if (h == null) {
                    return JsonNull.INSTANCE;
                }
                return Executor.path(stackJson(h.getStackInSlot(p.index)), p.path);
            }
            case "inv": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                if (p.side == null && be instanceof Container c) {
                    return inv(c);
                }
                IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, dir(p.side, null)).resolve().orElse(null);
                if (h == null) {
                    return JsonNull.INSTANCE;
                }
                JsonArray a = new JsonArray();
                for (int i = 0; i < h.getSlots(); i++) {
                    a.add(stackJson(h.getStackInSlot(i)));
                }
                return a;
            }
            case "fluid": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                IFluidHandler h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, dir(p.side, null)).resolve().orElse(null);
                if (h == null) {
                    return JsonNull.INSTANCE;
                }
                JsonArray a = new JsonArray();
                for (int i = 0; i < h.getTanks(); i++) {
                    JsonObject t = new JsonObject();
                    FluidStack fs = h.getFluidInTank(i);
                    t.add("fluid", fs.isEmpty() ? JsonNull.INSTANCE : new JsonPrimitive(ForgeRegistries.FLUIDS.getKey(fs.getFluid()).toString()));
                    t.addProperty("amount", fs.getAmount());
                    t.addProperty("capacity", h.getTankCapacity(i));
                    a.add(t);
                }
                if (p.index >= 0) {
                    return Executor.path(p.index < a.size() ? a.get(p.index) : JsonNull.INSTANCE, p.path);
                }
                return Executor.path(a, p.path);
            }
            case "energy": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                IEnergyStorage e = be.getCapability(ForgeCapabilities.ENERGY, dir(p.side, null)).resolve().orElse(null);
                return e == null ? JsonNull.INSTANCE : new JsonPrimitive(e.getEnergyStored());
            }
            case "field":
            case "call": {
                BlockEntity be = level.getBlockEntity(bp(posOf(p)));
                if (be == null) {
                    return JsonNull.INSTANCE;
                }
                if (p.path.length == 0) {
                    throw new IllegalArgumentException(p.probe + " probe needs /name");
                }
                Object v = p.probe.equals("call") ? Executor.call(be, p.path[0]) : Executor.field(be, p.path[0]);
                for (int i = 1; i < p.path.length && v != null; i++) {
                    v = p.probe.equals("call") ? Executor.call(v, p.path[i]) : Executor.field(v, p.path[i]);
                }
                return Executor.plain(v);
            }
            case "player": {
                FakePlayer pl = player();
                if ("held".equals(p.target)) {
                    return Executor.path(stackJson(pl.getMainHandItem()), p.path);
                }
                JsonArray a = new JsonArray();
                for (int i = 0; i < pl.getInventory().getContainerSize(); i++) {
                    ItemStack s = pl.getInventory().getItem(i);
                    if (!s.isEmpty()) {
                        JsonObject o = stackJson(s).getAsJsonObject();
                        o.addProperty("slot", i);
                        a.add(o);
                    }
                }
                return Executor.path(a, p.path);
            }
            case "drops": {
                int[] q = posOf(p);
                List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(bp(q)).inflate(3));
                List<JsonObject> list = new ArrayList<>();
                for (ItemEntity ie : items) {
                    list.add(stackJson(ie.getItem()).getAsJsonObject());
                }
                list.sort(Comparator.comparing(o -> o.toString()));
                JsonArray a = new JsonArray();
                list.forEach(a::add);
                return Executor.path(a, p.path);
            }
            default:
                throw new IllegalArgumentException("unknown probe " + p.probe);
        }
    }

    private static int[] posOf(Probe p) {
        p.requirePos();
        return p.pos;
    }

    private static <T extends Comparable<T>> String name(BlockState s, Property<T> prop) {
        return prop.getName(s.getValue(prop));
    }

    private static JsonArray inv(Container c) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < c.getContainerSize(); i++) {
            a.add(stackJson(c.getItem(i)));
        }
        return a;
    }

    static JsonElement stackJson(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        if (s.isDamageableItem() && s.getDamageValue() != 0) {
            o.addProperty("damage", s.getDamageValue());
        }
        if (s.getTag() != null && !s.getTag().isEmpty()) {
            o.add("nbt", json(s.getTag()));
        }
        return o;
    }

    static JsonElement json(Tag t) {
        if (t instanceof CompoundTag c) {
            JsonObject o = new JsonObject();
            for (String k : new TreeSet<>(c.getAllKeys())) {
                o.add(k, json(c.get(k)));
            }
            return o;
        }
        if (t instanceof ListTag l) {
            JsonArray a = new JsonArray();
            for (Tag e : l) {
                a.add(json(e));
            }
            return a;
        }
        if (t instanceof ByteArrayTag b) {
            return Executor.plain(b.getAsByteArray());
        }
        if (t instanceof IntArrayTag b) {
            return Executor.plain(b.getAsIntArray());
        }
        if (t instanceof LongArrayTag b) {
            return Executor.plain(b.getAsLongArray());
        }
        if (t instanceof NumericTag n) {
            return Executor.plain(n.getAsNumber());
        }
        if (t instanceof StringTag s) {
            return new JsonPrimitive(s.getAsString());
        }
        return t == null ? JsonNull.INSTANCE : new JsonPrimitive(t.toString());
    }

    static Tag toTag(JsonElement e) {
        if (e.isJsonObject()) {
            CompoundTag c = new CompoundTag();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                c.put(en.getKey(), toTag(en.getValue()));
            }
            return c;
        }
        if (e.isJsonArray()) {
            ListTag l = new ListTag();
            for (JsonElement x : e.getAsJsonArray()) {
                l.add(toTag(x));
            }
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return ByteTag.valueOf(p.getAsBoolean());
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE && !p.getAsString().contains(".")) {
                return IntTag.valueOf((int) d);
            }
            return DoubleTag.valueOf(d);
        }
        Object typed = Executor.typedNumber(p.getAsString());
        if (typed instanceof Byte b) {
            return ByteTag.valueOf(b);
        }
        if (typed instanceof Short s) {
            return ShortTag.valueOf(s);
        }
        if (typed instanceof Long l) {
            return LongTag.valueOf(l);
        }
        if (typed instanceof Float f) {
            return FloatTag.valueOf(f);
        }
        if (typed instanceof Double d) {
            return DoubleTag.valueOf(d);
        }
        return StringTag.valueOf(p.getAsString());
    }
}
