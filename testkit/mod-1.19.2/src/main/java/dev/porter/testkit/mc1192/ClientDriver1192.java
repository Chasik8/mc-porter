package dev.porter.testkit.mc1192;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector4f;
import dev.porter.testkit.mod.Executor;
import dev.porter.testkit.mod.GuiDriver;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of GUI scenarios (1.19.2): moves the window off-screen, creates a superflat creative world from the
 * title screen, executes the GUI ops like a player ({@link GuiDriver}: right-click on the block, use of the held
 * item, mouse/key events on the screen), records one frame (text draws through a recording font, framebuffer,
 * slots) and quits when the plan is done. Loaded only on the client.
 */
final class ClientDriver1192 extends GuiDriver {
    static final ClientDriver1192 INSTANCE = new ClientDriver1192();

    private int menuTicks;
    private int doneTicks;
    private boolean launched;
    private boolean moved;
    private int joinTicks;

    private RecordingFont recorder;
    private Font savedMc;
    private Font savedScreen;

    private ClientDriver1192() {
    }

    static GuiDriver install() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        if ("offscreen".equals(System.getProperty("porter.window"))) {
            RenderSystem.recordRenderCall(INSTANCE::moveWindow);
        }
        return INSTANCE;
    }

    private void moveWindow() {
        if (!moved) {
            moved = true;
            GLFW.glfwSetWindowPos(Minecraft.getInstance().getWindow().getWindow(), -12000, -12000);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if ("offscreen".equals(System.getProperty("porter.window"))) {
            moveWindow();
        }
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse(); // never capture the user's mouse
        }
        if (!launched) {
            if (mc.screen instanceof TitleScreen && ++menuTicks > 20) {
                launched = true;
                createWorld(mc, "porter-" + PorterTestkit.plan.get("scenario").getAsString());
            }
            return;
        }
        if (mc.player != null) {
            clientTick();
        } else if (PorterTestkit.clientExecutor == null && ++joinTicks > 2400) {
            System.out.println("[porter-testkit] the player did not join within 120 s - quitting for a relaunch");
            mc.stop();
        }
        Executor ex = PorterTestkit.clientExecutor;
        if (ex != null && ex.isDone() && ++doneTicks > 5) {
            mc.stop();
        }
    }

    /** Texts are recorded only while the screen itself draws (not the HUD behind it). */
    @SubscribeEvent
    public void onDrawPre(net.minecraftforge.client.event.ScreenEvent.Render.Pre event) {
        if (recorder != null) {
            recorder.active = true;
        }
    }

    @SubscribeEvent
    public void onDrawPost(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        if (recorder != null) {
            recorder.active = false;
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            renderStart();
        } else {
            renderEnd();
        }
    }

    private static void createWorld(Minecraft mc, String folder) {
        deleteRecursively(new File(mc.gameDirectory, "saves/" + folder));
        RegistryAccess.Frozen ra = RegistryAccess.BUILTIN.get();
        WorldGenSettings wgs = ra.registryOrThrow(Registry.WORLD_PRESET_REGISTRY).getHolderOrThrow(WorldPresets.FLAT).value()
                .createWorldGenSettings(0L, false, false);
        LevelSettings ls = new LevelSettings(folder, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), DataPackConfig.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(folder, ls, ra, wgs);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    protected Object currentScreen() {
        return mc().screen;
    }

    @Override
    protected void closeScreen() {
        Minecraft mc = mc();
        if (mc.screen instanceof AbstractContainerScreen && mc.player != null) {
            mc.player.closeContainer();
        } else {
            mc.setScreen(null);
        }
    }

    @Override
    protected boolean holdingItem() {
        Minecraft mc = mc();
        mc.player.getInventory().selected = 0;
        return !mc.player.getMainHandItem().isEmpty();
    }

    @Override
    protected void useItem() {
        Minecraft mc = mc();
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }

    @Override
    protected void clickBlock(int[] p) {
        Minecraft mc = mc();
        mc.player.getInventory().selected = 0;
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        Vec3 hit = new Vec3(p[0] + 0.5, p[1] + 1.0, p[2] + 0.5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
    }

    @Override
    protected void click(Object screen, int x, int y, int button) {
        Screen s = (Screen) screen;
        s.mouseClicked(x + 0.5, y + 0.5, button);
        s.mouseReleased(x + 0.5, y + 0.5, button);
    }

    @Override
    protected void key(Object screen, String key) {
        InputConstants.Key k = InputConstants.getKey("key.keyboard." + key.toLowerCase(java.util.Locale.ROOT));
        if (k == InputConstants.UNKNOWN) {
            throw new IllegalArgumentException("unknown key " + key + " (GLFW key names: right, home, backspace, escape, ...)");
        }
        ((Screen) screen).keyPressed(k.getValue(), 0, 0);
    }

    @Override
    protected int[] origin(Object screen, JsonObject check) {
        int[] r = region(screen, check);
        return new int[] {r[0], r[1]};
    }

    private int[] region(Object screen, JsonObject check) {
        if (screen instanceof AbstractContainerScreen) {
            AbstractContainerScreen<?> g = (AbstractContainerScreen<?>) screen;
            return new int[] {g.getGuiLeft(), g.getGuiTop(), g.getXSize(), g.getYSize()};
        }
        Minecraft mc = mc();
        return centred(check, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    @Override
    protected void parkMouse() throws Exception {
        // the game's mouse position (window pixels); the OS cursor is not moved
        set(net.minecraft.client.MouseHandler.class, mc().mouseHandler, "f_91507_", 0.0);
        set(net.minecraft.client.MouseHandler.class, mc().mouseHandler, "f_91508_", 0.0);
    }

    @Override
    protected void beginTextRecording(Object screen) throws Exception {
        Minecraft mc = mc();
        if (recorder == null) {
            recorder = new RecordingFont(mc.font);
        }
        recorder.records.clear();
        savedMc = mc.font;
        set(Minecraft.class, mc, "f_91062_", recorder);
        if (screen instanceof Screen) {
            savedScreen = ObfuscationReflectionHelper.getPrivateValue(Screen.class, (Screen) screen, "f_96547_");
            set(Screen.class, screen, "f_96547_", recorder);
        }
    }

    @Override
    protected JsonArray endTextRecording(Object screen, JsonObject check) throws Exception {
        Minecraft mc = mc();
        if (savedMc != null) {
            set(Minecraft.class, mc, "f_91062_", savedMc);
            savedMc = null;
        }
        if (screen instanceof Screen && savedScreen != null) {
            set(Screen.class, screen, "f_96547_", savedScreen);
            savedScreen = null;
        }
        JsonArray out = new JsonArray();
        if (recorder == null) {
            return out;
        }
        int[] reg = screen == null ? new int[] {0, 0} : region(screen, check);
        for (JsonObject t : recorder.records) {
            t.addProperty("x", t.get("x").getAsInt() - reg[0]);
            t.addProperty("y", t.get("y").getAsInt() - reg[1]);
            out.add(t);
        }
        recorder.records.clear();
        return out;
    }

    private static void set(Class<?> owner, Object target, String srg, Object value) throws Exception {
        Field f = ObfuscationReflectionHelper.findField(owner, srg);
        f.set(target, value);
    }

    @Override
    protected JsonObject describe(Object screen, JsonObject check) {
        Minecraft mc = mc();
        int[] reg = region(screen, check);
        JsonObject o = new JsonObject();
        o.addProperty("screenClass", screen.getClass().getName());
        o.addProperty("guiLeft", reg[0]);
        o.addProperty("guiTop", reg[1]);
        JsonObject size = new JsonObject();
        size.addProperty("width", reg[2]);
        size.addProperty("height", reg[3]);
        o.add("size", size);
        o.addProperty("scale", (int) Math.round(mc.getWindow().getGuiScale()));
        JsonObject display = new JsonObject();
        display.addProperty("width", mc.getWindow().getWidth());
        display.addProperty("height", mc.getWindow().getHeight());
        o.add("display", display);
        JsonArray slots = new JsonArray();
        if (screen instanceof AbstractContainerScreen) {
            List<Slot> list = ((AbstractContainerScreen<?>) screen).getMenu().slots;
            for (int i = 0; i < list.size(); i++) {
                Slot s = list.get(i);
                JsonObject so = new JsonObject();
                so.addProperty("index", i);
                so.addProperty("x", s.x);
                so.addProperty("y", s.y);
                if (s.container instanceof Inventory) {
                    so.addProperty("player", true);
                }
                slots.add(so);
            }
        }
        o.add("slots", slots);
        return o;
    }

    @Override
    protected void saveFramebuffer(File png) throws Exception {
        try (NativeImage img = Screenshot.takeScreenshot(mc().getMainRenderTarget())) {
            img.writeToFile(png);
        }
    }

    /** Font that records every string drawn (position through the pose and the RenderSystem model-view matrix). */
    static final class RecordingFont extends Font {
        final List<JsonObject> records = new ArrayList<>();
        boolean active;

        @SuppressWarnings("unchecked")
        RecordingFont(Font original) {
            super((Function<ResourceLocation, FontSet>) ObfuscationReflectionHelper.getPrivateValue(Font.class, original, "f_92713_"), false);
        }

        @Override
        public int drawInBatch(String text, float x, float y, int color, boolean shadow, Matrix4f m, MultiBufferSource buf,
                boolean seeThrough, int bg, int light, boolean bidi) {
            record(text, x, y, width(text), color, shadow, m);
            return super.drawInBatch(text, x, y, color, shadow, m, buf, seeThrough, bg, light, bidi);
        }

        @Override
        public int drawInBatch(FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f m,
                MultiBufferSource buf, boolean seeThrough, int bg, int light) {
            StringBuilder sb = new StringBuilder();
            text.accept((idx, style, cp) -> {
                sb.appendCodePoint(cp);
                return true;
            });
            record(sb.toString(), x, y, width(text), color, shadow, m);
            return super.drawInBatch(text, x, y, color, shadow, m, buf, seeThrough, bg, light);
        }

        private void record(String text, float x, float y, int width, int color, boolean shadow, Matrix4f m) {
            if (!active || text == null || text.isEmpty()) {
                return;
            }
            Vector4f a = new Vector4f(x, y, 0, 1);
            a.transform(m);
            a.transform(RenderSystem.getModelViewMatrix());
            Vector4f b = new Vector4f(x + width, y, 0, 1);
            b.transform(m);
            b.transform(RenderSystem.getModelViewMatrix());
            records.add(GuiDriver.text(text, a.x(), a.y(), Math.round(b.x() - a.x()), color, shadow));
        }
    }

    static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        if (f.exists() && !f.delete()) {
            System.err.println("[porter-testkit] cannot delete " + f);
        }
    }
}
