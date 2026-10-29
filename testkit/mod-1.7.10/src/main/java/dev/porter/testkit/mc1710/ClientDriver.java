package dev.porter.testkit.mc1710;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import dev.porter.testkit.mod.Executor;
import dev.porter.testkit.mod.GuiDriver;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Client side of GUI scenarios (1.7.10): creates a void creative world from the main menu, executes the GUI ops of
 * the plan like a player ({@link GuiDriver}): right-click on the block / use of the held item, mouse clicks and keys
 * on the screen, then records one frame (text draws, framebuffer, slots) and quits the client when the plan is done.
 */
final class ClientDriver extends GuiDriver {
    static final ClientDriver INSTANCE = new ClientDriver();

    private static int menuTicks;
    private static int doneTicks;
    private static boolean launched;
    private static int joinTicks;

    private RecordingFont recorder;
    private FontRenderer savedMc;
    private FontRenderer savedScreen;

    private ClientDriver() {
    }

    static void install() {
        ClientDriver.Handler h = new ClientDriver.Handler();
        FMLCommonHandler.instance().bus().register(h);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(h);
    }

    /**
     * Never touch the user's real mouse: vanilla {@code ungrabMouseCursor} warps the OS cursor to the window centre every time
     * a GUI opens (FML's {@code -Dfml.noGrab} guards only the grab half), and the grab itself would capture it.
     */
    static final class NoGrabMouse extends net.minecraft.util.MouseHelper {
        @Override
        public void grabMouseCursor() {
            deltaX = 0;
            deltaY = 0;
        }

        @Override
        public void ungrabMouseCursor() {
        }
    }

    public static final class Handler {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (!(mc.mouseHelper instanceof NoGrabMouse)) {
                mc.mouseHelper = new NoGrabMouse();
            }
            if (!launched) {
                if (mc.currentScreen instanceof GuiMainMenu && ++menuTicks > 20) {
                    launched = true;
                    String folder = "porter-" + PorterTestkit.plan.get("scenario").getAsString();
                    deleteRecursively(new File(mc.mcDataDir, "saves/" + folder));
                    WorldSettings ws = new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
                    ws.func_82750_a("2;0;1;");
                    mc.launchIntegratedServer(folder, folder, ws);
                }
                return;
            }
            if (mc.thePlayer != null) {
                INSTANCE.clientTick();
            } else if (PorterTestkit.clientExecutor == null && ++joinTicks > 1200) {
                // known 1.7.10 integrated-server login stall: quit without a result, the runner relaunches
                System.out.println("[porter-testkit] the player did not join within 60 s - quitting for a relaunch");
                mc.shutdown();
            }
            Executor ex = PorterTestkit.clientExecutor;
            if (ex != null && ex.isDone() && ++doneTicks > 5) {
                mc.shutdown();
            }
        }

        /** Texts are recorded only while the screen itself draws (not the HUD behind it). */
        @SubscribeEvent
        public void onDrawPre(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre event) {
            if (INSTANCE.recorder != null) {
                INSTANCE.recorder.active = true;
            }
        }

        @SubscribeEvent
        public void onDrawPost(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post event) {
            if (INSTANCE.recorder != null) {
                INSTANCE.recorder.active = false;
            }
        }

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                INSTANCE.renderStart();
            } else {
                INSTANCE.renderEnd();
            }
        }
    }

    /** The block entity of the client world at a position (probe client_field), null when absent. */
    static Object clientTile(int[] p) {
        Minecraft mc = mc();
        return mc.theWorld == null || p == null ? null : mc.theWorld.getTileEntity(p[0], p[1], p[2]);
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private static ScaledResolution res() {
        Minecraft mc = mc();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    @Override
    protected Object currentScreen() {
        return mc().currentScreen;
    }

    @Override
    protected void closeScreen() {
        Minecraft mc = mc();
        if (mc.currentScreen instanceof GuiContainer && mc.thePlayer != null) {
            mc.thePlayer.closeScreen();
        } else {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected boolean holdingItem() {
        Minecraft mc = mc();
        mc.thePlayer.inventory.currentItem = 0;
        return mc.thePlayer.getHeldItem() != null;
    }

    @Override
    protected void useItem() {
        Minecraft mc = mc();
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
    }

    @Override
    protected void clickBlock(int[] p) {
        Minecraft mc = mc();
        mc.thePlayer.inventory.currentItem = 0;
        // top face, like a player looking down at the block
        Vec3 hit = Vec3.createVectorHelper(p[0] + 0.5, p[1] + 1.0, p[2] + 0.5);
        mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), p[0], p[1], p[2], 1, hit);
    }

    @Override
    protected void click(Object screen, int x, int y, int button) throws Exception {
        GuiScreen s = (GuiScreen) screen;
        ReflectionHelper.findMethod(GuiScreen.class, s, new String[] {"func_73864_a", "mouseClicked"}, int.class, int.class, int.class)
                .invoke(s, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(button));
        ReflectionHelper.findMethod(GuiScreen.class, s, new String[] {"func_146286_b", "mouseMovedOrUp"}, int.class, int.class, int.class)
                .invoke(s, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(button));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> buttonList(GuiScreen gs) {
        return ReflectionHelper.<List<Object>, GuiScreen>getPrivateValue(GuiScreen.class, gs, "field_146292_n", "buttonList");
    }

    private static Object field(Object o, String name) {
        try {
            return o.getClass().getField(name).get(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object method(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static int num(Object v, int dflt) {
        return v instanceof Number ? ((Number) v).intValue() : dflt;
    }

    /**
     * Pressable controls: the 1.7.10 {@code GuiScreen.buttonList} (kind "button", id = GuiButton.id) first, then the
     * 1.19.2-style widgets of a ported screen (public list {@code renderables}; kind "widget"; id from the screen's public
     * {@code getWidgets()} id -> widget map, when it has one).
     */
    @Override
    protected JsonArray buttons(Object screen) {
        JsonArray out = new JsonArray();
        if (!(screen instanceof GuiScreen)) {
            return out;
        }
        GuiScreen gs = (GuiScreen) screen;
        for (Object o : buttonList(gs)) {
            GuiButton b = (GuiButton) o;
            JsonObject j = new JsonObject();
            j.addProperty("index", Integer.valueOf(out.size()));
            j.addProperty("kind", "button");
            j.addProperty("id", Integer.valueOf(b.id));
            j.addProperty("class", b.getClass().getName());
            j.addProperty("label", b.displayString);
            j.addProperty("x", Integer.valueOf(b.xPosition));
            j.addProperty("y", Integer.valueOf(b.yPosition));
            j.addProperty("width", Integer.valueOf(b.width));
            j.addProperty("height", Integer.valueOf(b.height));
            j.addProperty("active", Boolean.valueOf(b.enabled));
            j.addProperty("visible", Boolean.valueOf(b.visible));
            out.add(j);
        }
        java.util.IdentityHashMap<Object, Integer> ids = new java.util.IdentityHashMap<Object, Integer>();
        Object map = method(screen, "getWidgets");
        if (map instanceof java.util.Map) {
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) map).entrySet()) {
                if (e.getKey() instanceof Number) {
                    ids.put(e.getValue(), Integer.valueOf(((Number) e.getKey()).intValue()));
                }
            }
        }
        Object list = field(screen, "renderables");
        if (list instanceof List) {
            for (Object w : (List<?>) list) {
                if (w == null || field(w, "x") == null || method(w, "getWidth") == null) {
                    continue;
                }
                JsonObject j = new JsonObject();
                j.addProperty("index", Integer.valueOf(out.size()));
                j.addProperty("kind", "widget");
                if (ids.containsKey(w)) {
                    j.addProperty("id", ids.get(w));
                }
                j.addProperty("class", w.getClass().getName());
                Object msg = method(w, "getMessage");
                if (msg instanceof net.minecraft.util.IChatComponent) {
                    j.addProperty("label", ((net.minecraft.util.IChatComponent) msg).getUnformattedText());
                }
                j.addProperty("x", Integer.valueOf(num(field(w, "x"), 0)));
                j.addProperty("y", Integer.valueOf(num(field(w, "y"), 0)));
                j.addProperty("width", Integer.valueOf(num(method(w, "getWidth"), 0)));
                j.addProperty("height", Integer.valueOf(num(method(w, "getHeight"), 0)));
                j.addProperty("active", Boolean.valueOf(!Boolean.FALSE.equals(field(w, "active"))));
                j.addProperty("visible", Boolean.valueOf(!Boolean.FALSE.equals(field(w, "visible"))));
                out.add(j);
            }
        }
        return out;
    }

    @Override
    protected String press(Object screen, JsonObject sel) throws Exception {
        JsonArray list = buttons(screen);
        int at = selectButton(list, sel);
        if (at < 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                JsonObject b = list.get(i).getAsJsonObject();
                sb.append(" [").append(b.get("index").getAsInt()).append(' ').append(b.get("kind").getAsString());
                if (b.has("id")) {
                    sb.append(" id=").append(b.get("id").getAsInt());
                }
                if (b.has("label")) {
                    sb.append(" \"").append(b.get("label").getAsString()).append('"');
                }
                sb.append(']');
            }
            throw new IllegalStateException("no control matches " + sel + " on " + screen.getClass().getName() + "; it has "
                    + list.size() + ":" + sb);
        }
        JsonObject b = list.get(at).getAsJsonObject();
        if (!b.get("visible").getAsBoolean() || !b.get("active").getAsBoolean()) {
            throw new IllegalStateException("control " + b + " is " + (b.get("visible").getAsBoolean() ? "disabled" : "hidden"));
        }
        boolean isButton = b.get("kind").getAsString().equals("button");
        String via = Executor.str(sel, "via", isButton ? "call" : "click");
        if (via.equals("call")) {
            if (!isButton) {
                throw new IllegalStateException("via: call needs a GuiButton (buttonList); use via: click for widgets");
            }
            GuiScreen gs = (GuiScreen) screen;
            ReflectionHelper.findMethod(GuiScreen.class, gs, new String[] {"func_146284_a", "actionPerformed"}, GuiButton.class)
                    .invoke(gs, buttonList(gs).get(at));
        } else {
            int cx = b.get("x").getAsInt() + b.get("width").getAsInt() / 2;
            int cy = b.get("y").getAsInt() + b.get("height").getAsInt() / 2;
            click(screen, cx, cy, 0);
        }
        return "press " + via + " " + b;
    }

    @Override
    protected void key(Object screen, String key) throws Exception {
        int code = Keyboard.getKeyIndex(key.toUpperCase(java.util.Locale.ROOT));
        if (code == Keyboard.KEY_NONE) {
            throw new IllegalArgumentException("unknown key " + key + " (LWJGL 2 key names: RIGHT, HOME, BACK, ESCAPE, ...)");
        }
        char c = key.length() == 1 ? key.charAt(0) : '\0';
        GuiScreen s = (GuiScreen) screen;
        ReflectionHelper.findMethod(GuiScreen.class, s, new String[] {"func_73869_a", "keyTyped"}, char.class, int.class)
                .invoke(s, Character.valueOf(c), Integer.valueOf(code));
    }

    @Override
    protected int[] origin(Object screen, JsonObject check) {
        int[] r = region(screen, check);
        return new int[] {r[0], r[1]};
    }

    /** GUI rectangle in scaled px: container guiLeft/guiTop/xSize/ySize, else check.size centred, else the screen. */
    private int[] region(Object screen, JsonObject check) {
        if (screen instanceof GuiContainer) {
            GuiContainer g = (GuiContainer) screen;
            return new int[] {intField(g, "field_147003_i", "guiLeft"), intField(g, "field_147009_r", "guiTop"),
                intField(g, "field_146999_f", "xSize"), intField(g, "field_147000_g", "ySize")};
        }
        ScaledResolution r = res();
        return centred(check, r.getScaledWidth(), r.getScaledHeight());
    }

    private static int intField(GuiContainer g, String srg, String mcp) {
        return ReflectionHelper.<Integer, GuiContainer>getPrivateValue(GuiContainer.class, g, srg, mcp).intValue();
    }

    @Override
    protected void parkMouse() throws Exception {
        // LWJGL 2 keeps the last position in static fields; (0,0) = bottom-left corner. The OS cursor is not moved.
        for (String f : new String[] {"x", "y", "absolute_x", "absolute_y"}) {
            java.lang.reflect.Field fld = org.lwjgl.input.Mouse.class.getDeclaredField(f);
            fld.setAccessible(true);
            fld.setInt(null, 0);
        }
    }

    @Override
    protected void beginTextRecording(Object screen) {
        Minecraft mc = mc();
        if (recorder == null) {
            recorder = new RecordingFont(mc);
        }
        recorder.records.clear();
        recorder.setUnicodeFlag(mc.fontRenderer.getUnicodeFlag());
        recorder.setBidiFlag(mc.fontRenderer.getBidiFlag());
        savedMc = mc.fontRenderer;
        mc.fontRenderer = recorder;
        if (screen instanceof GuiScreen) {
            savedScreen = ReflectionHelper.getPrivateValue(GuiScreen.class, (GuiScreen) screen, "field_146289_q", "fontRendererObj");
            ReflectionHelper.setPrivateValue(GuiScreen.class, (GuiScreen) screen, recorder, "field_146289_q", "fontRendererObj");
        }
    }

    @Override
    protected JsonArray endTextRecording(Object screen, JsonObject check) {
        Minecraft mc = mc();
        if (savedMc != null) {
            mc.fontRenderer = savedMc;
            savedMc = null;
        }
        if (screen instanceof GuiScreen && savedScreen != null) {
            ReflectionHelper.setPrivateValue(GuiScreen.class, (GuiScreen) screen, savedScreen, "field_146289_q", "fontRendererObj");
            savedScreen = null;
        }
        JsonArray out = new JsonArray();
        if (recorder == null) {
            return out;
        }
        int[] reg = screen == null ? new int[] {0, 0} : region(screen, check);
        for (JsonObject t : recorder.records) {
            t.addProperty("x", Integer.valueOf(t.get("x").getAsInt() - reg[0]));
            t.addProperty("y", Integer.valueOf(t.get("y").getAsInt() - reg[1]));
            out.add(t);
        }
        recorder.records.clear();
        return out;
    }

    @Override
    protected JsonObject describe(Object screen, JsonObject check) {
        Minecraft mc = mc();
        ScaledResolution r = res();
        int[] reg = region(screen, check);
        JsonObject o = new JsonObject();
        o.addProperty("screenClass", screen.getClass().getName());
        o.addProperty("guiLeft", Integer.valueOf(reg[0]));
        o.addProperty("guiTop", Integer.valueOf(reg[1]));
        JsonObject size = new JsonObject();
        size.addProperty("width", Integer.valueOf(reg[2]));
        size.addProperty("height", Integer.valueOf(reg[3]));
        o.add("size", size);
        o.addProperty("scale", Integer.valueOf(r.getScaleFactor()));
        JsonObject display = new JsonObject();
        display.addProperty("width", Integer.valueOf(mc.displayWidth));
        display.addProperty("height", Integer.valueOf(mc.displayHeight));
        o.add("display", display);
        JsonArray slots = new JsonArray();
        if (screen instanceof GuiContainer) {
            List<?> list = ((GuiContainer) screen).inventorySlots.inventorySlots;
            for (int i = 0; i < list.size(); i++) {
                Slot s = (Slot) list.get(i);
                JsonObject so = new JsonObject();
                so.addProperty("index", Integer.valueOf(i));
                so.addProperty("x", Integer.valueOf(s.xDisplayPosition));
                so.addProperty("y", Integer.valueOf(s.yDisplayPosition));
                if (s.inventory instanceof InventoryPlayer) {
                    so.addProperty("player", Boolean.TRUE);
                }
                slots.add(so);
            }
        }
        o.add("slots", slots);
        return o;
    }

    @Override
    protected void saveFramebuffer(File png) {
        Minecraft mc = mc();
        // 1.7.10 writes to <dir>/screenshots/<name>
        File dir = new File(png.getParentFile(), "tmp-shot");
        new File(dir, "screenshots").mkdirs(); // ScreenShotHelper only mkdir()s the last level
        ScreenShotHelper.saveScreenshot(dir, png.getName(), mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        File shot = new File(new File(dir, "screenshots"), png.getName());
        if (png.exists()) {
            png.delete();
        }
        if (!shot.renameTo(png)) {
            throw new IllegalStateException("screenshot not written: " + shot);
        }
    }

    /** FontRenderer that records every string drawn (absolute scaled position from the GL modelview matrix). */
    static final class RecordingFont extends FontRenderer {
        final List<JsonObject> records = new ArrayList<JsonObject>();
        boolean active;
        private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

        RecordingFont(Minecraft mc) {
            super(mc.gameSettings, new ResourceLocation("textures/font/ascii.png"), mc.getTextureManager(), false);
            onResourceManagerReload(mc.getResourceManager());
        }

        @Override
        public int drawString(String text, int x, int y, int color, boolean shadow) {
            record(text, x, y, color, shadow);
            return super.drawString(text, x, y, color, shadow);
        }

        @Override
        public void drawSplitString(String text, int x, int y, int wrap, int color) {
            int line = 0;
            for (Object l : listFormattedStringToWidth(text, wrap)) {
                record((String) l, x, y + line * FONT_HEIGHT, color, false);
                line++;
            }
            super.drawSplitString(text, x, y, wrap, color);
        }

        private void record(String text, int x, int y, int color, boolean shadow) {
            if (!active || text == null || text.isEmpty()) {
                return;
            }
            matrix.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
            float sx = matrix.get(0);
            float sy = matrix.get(5);
            float tx = matrix.get(12);
            float ty = matrix.get(13);
            records.add(GuiDriver.text(text, sx * x + tx, sy * y + ty, Math.round(getStringWidth(text) * sx), color, shadow));
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
