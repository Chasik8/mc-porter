package dev.porter.testkit.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;

/**
 * Version-independent client-side GUI state machine for GUI scenarios (Java 8, Gson only).
 *
 * <p>The plan runs on the integrated server thread; GUI ops ({@code open_gui}, {@code gui_input}, {@code close_gui})
 * are handed over with {@link #submit} and executed on the client thread like a player would: a real right-click on
 * the block (or a use of the held item), real mouse/key events on the open screen. After the screen has settled, the
 * next frame is recorded: text draw calls are captured by swapping in a recording font for one frame, then the
 * framebuffer is saved and the screen structure (size, position, slots) is dumped. The result goes back to the server
 * thread, which polls {@link #submit} every tick.
 *
 * <p>Version-specific parts are the abstract hooks; all coordinates in dumps are scaled GUI pixels relative to the
 * screen's top-left corner unless named {@code abs*}.
 */
public abstract class GuiDriver {
    private enum State { IDLE, WAIT_HAND, ACT, WAIT_SCREEN, SETTLE, ARMED, RECORDING }

    private static final int SETTLE_TICKS = 10;
    private static final int SCREEN_TIMEOUT = 100;
    private static final int ABSENT_WAIT = 40;

    private volatile JsonObject request;
    private volatile JsonObject result;
    private State state = State.IDLE;
    private int ticks;
    private Object screenBefore;
    private JsonObject op;
    /** check of the open_gui that opened the current screen: gui_input captures inherit its fields (size, ...). */
    private JsonObject sessionCheck = new JsonObject();

    // ------------------------------------------------------------------ server thread

    /** Submits a GUI op (attempt 0) or polls its result (later attempts); null while the op is still running. */
    public JsonObject submit(JsonObject op, int attempt) {
        if (attempt == 0) {
            result = null;
            request = op;
            return null;
        }
        JsonObject r = result;
        if (r != null) {
            result = null;
        }
        return r;
    }

    // ------------------------------------------------------------------ client thread

    /** Call at the end of every client tick. */
    public final void clientTick() {
        JsonObject req = request;
        if (req != null && state == State.IDLE) {
            request = null;
            op = req;
            ticks = 0;
            String kind = op.get("op").getAsString();
            System.out.println("[porter-testkit] gui op " + kind + " " + captureName() + " start");
            if (kind.equals("close_gui")) {
                closeScreen();
                finish(new JsonObject());
                return;
            }
            if (kind.equals("open_gui")) {
                sessionCheck = op.has("check") ? op.getAsJsonObject("check") : new JsonObject();
                screenBefore = currentScreen();
                if (screenBefore != null) {
                    closeScreen();
                    screenBefore = null;
                }
                state = op.has("item") ? State.WAIT_HAND : State.ACT;
            } else {
                state = State.ACT;
            }
        }
        ticks++;
        try {
            switch (state) {
                case WAIT_HAND:
                    if (holdingItem()) {
                        state = State.ACT;
                    } else if (ticks > 60) {
                        fail("the item never arrived in the player's hand");
                    }
                    break;
                case ACT:
                    act();
                    break;
                case WAIT_SCREEN: {
                    Object s = currentScreen();
                    boolean absent = "absent".equals(mode());
                    if (s != null) {
                        state = State.SETTLE;
                        ticks = 0;
                    } else if (absent && ticks > ABSENT_WAIT) {
                        JsonObject d = new JsonObject();
                        d.addProperty("capture", captureName());
                        d.add("screenClass", JsonNull.INSTANCE);
                        d.add("check", check());
                        finish(d);
                    } else if (ticks > SCREEN_TIMEOUT) {
                        fail("no screen opened within " + SCREEN_TIMEOUT + " ticks");
                    }
                    break;
                }
                case SETTLE:
                    if (currentScreen() == null) {
                        fail("the screen closed by itself");
                    } else if (ticks >= SETTLE_TICKS) {
                        if (op.has("capture") || op.get("op").getAsString().equals("open_gui")) {
                            state = State.ARMED;
                        } else {
                            finish(new JsonObject());
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            t.printStackTrace();
            fail(String.valueOf(t));
        }
    }

    private void act() throws Exception {
        String kind = op.get("op").getAsString();
        if (kind.equals("open_gui")) {
            if (op.has("item")) {
                useItem();
            } else {
                int[] p = Executor.pos(op, "at");
                clickBlock(p);
            }
            state = State.WAIT_SCREEN;
            ticks = 0;
            return;
        }
        Object s = currentScreen();
        if (s == null) {
            fail("gui_input: no screen is open");
            return;
        }
        if (op.has("click")) {
            JsonArray c = op.getAsJsonArray("click");
            int[] origin = origin(s, check());
            click(s, origin[0] + c.get(0).getAsInt(), origin[1] + c.get(1).getAsInt(), Executor.integer(op, "button", 0));
        }
        if (op.has("key")) {
            key(s, op.get("key").getAsString());
        }
        if (op.has("call")) {
            JsonObject call = op.getAsJsonObject("call");
            Object[] args = new Object[0];
            if (call.has("args")) {
                JsonArray a = call.getAsJsonArray("args");
                args = new Object[a.size()];
                for (int i = 0; i < a.size(); i++) {
                    JsonElement e = a.get(i);
                    if (e.isJsonNull()) {
                        args[i] = null;
                    } else if (e.getAsJsonPrimitive().isNumber()) {
                        args[i] = Integer.valueOf(e.getAsInt());
                    } else if (e.getAsJsonPrimitive().isBoolean()) {
                        args[i] = Boolean.valueOf(e.getAsBoolean());
                    } else {
                        args[i] = e.getAsString();
                    }
                }
            }
            callMethod(s, call.get("method").getAsString(), args);
        }
        state = State.SETTLE;
        ticks = 0;
    }

    /** Call at the start of every rendered frame. */
    public final void renderStart() {
        if (state == State.ARMED) {
            try {
                parkMouse();
                beginTextRecording(currentScreen());
                state = State.RECORDING;
                System.out.println("[porter-testkit] capture " + captureName() + " begin");
            } catch (Throwable t) {
                t.printStackTrace();
                fail("cannot start text recording: " + t);
            }
        }
    }

    /** Call at the end of every rendered frame (the screen is in the framebuffer). */
    public final void renderEnd() {
        if (state != State.RECORDING) {
            return;
        }
        Object s = currentScreen();
        JsonArray texts;
        try {
            texts = endTextRecording(s, check());
        } catch (Throwable t) {
            t.printStackTrace();
            texts = new JsonArray();
        }
        try {
            if (s == null) {
                fail("the screen closed while capturing");
                return;
            }
            String name = captureName();
            JsonObject d = describe(s, check());
            d.addProperty("capture", name);
            d.add("texts", texts);
            d.add("check", check());
            String dir = System.getProperty("porter.screens");
            File out = new File(dir != null ? dir : "screens", name + ".png");
            out.getParentFile().mkdirs();
            saveFramebuffer(out);
            d.addProperty("screenshotFile", out.getAbsolutePath());
            System.out.println("[porter-testkit] capture " + name + " end");
            boolean keep = op.get("op").getAsString().equals("gui_input") || (op.has("keepOpen") && op.get("keepOpen").getAsBoolean());
            if (!keep) {
                closeScreen();
            }
            finish(d);
        } catch (Throwable t) {
            t.printStackTrace();
            fail("capture failed: " + t);
        }
    }

    private String captureName() {
        return Executor.str(op, "capture", "step" + Executor.integer(op, "step", 0));
    }

    /** The op's check merged over the session's (open_gui) check. */
    private JsonObject check() {
        JsonObject c = new JsonObject();
        boolean input = op != null && !op.get("op").getAsString().equals("open_gui");
        if (input) {
            for (java.util.Map.Entry<String, JsonElement> e : sessionCheck.entrySet()) {
                if (!e.getKey().equals("mode") && !e.getKey().equals("dynamicFrom")) {
                    c.add(e.getKey(), e.getValue());
                }
            }
        }
        if (op != null && op.has("check")) {
            for (java.util.Map.Entry<String, JsonElement> e : op.getAsJsonObject("check").entrySet()) {
                c.add(e.getKey(), e.getValue());
            }
        }
        if (input && !c.has("mode") && sessionCheck.has("mode")) {
            c.add("mode", sessionCheck.get("mode"));
        }
        return c;
    }

    private String mode() {
        return Executor.str(check(), "mode", "diff");
    }

    private void fail(String message) {
        JsonObject d = new JsonObject();
        d.addProperty("capture", op == null ? "?" : captureName());
        d.addProperty("error", message);
        System.out.println("[porter-testkit] gui op failed: " + message);
        finish(d);
    }

    private void finish(JsonObject d) {
        state = State.IDLE;
        op = null;
        result = d;
    }

    /** Top-left corner of the screen's GUI area in scaled pixels (container: guiLeft/guiTop; else centred check.size). */
    protected abstract int[] origin(Object screen, JsonObject check);

    /** Centred region of size {@code check.size} for screens without a known GUI rectangle. */
    protected static int[] centred(JsonObject check, int scaledWidth, int scaledHeight) {
        if (check != null && check.has("size")) {
            JsonArray s = check.getAsJsonArray("size");
            return new int[] {(scaledWidth - s.get(0).getAsInt()) / 2, (scaledHeight - s.get(1).getAsInt()) / 2,
                s.get(0).getAsInt(), s.get(1).getAsInt()};
        }
        return new int[] {0, 0, scaledWidth, scaledHeight};
    }

    /** Version hooks (client thread). */
    protected abstract Object currentScreen();

    protected abstract void closeScreen();

    protected abstract boolean holdingItem();

    protected abstract void useItem() throws Exception;

    protected abstract void clickBlock(int[] pos) throws Exception;

    protected abstract void click(Object screen, int x, int y, int button) throws Exception;

    protected abstract void key(Object screen, String key) throws Exception;

    protected abstract void beginTextRecording(Object screen) throws Exception;

    /** Moves the game's idea of the mouse to the window's corner (no hover effects or tooltips in captures). */
    protected abstract void parkMouse() throws Exception;

    /** Recorded text draws: {text, x, y, width, color, shadow} with x/y relative to the GUI origin. */
    protected abstract JsonArray endTextRecording(Object screen, JsonObject check) throws Exception;

    /**
     * Screen structure: screenClass, guiLeft, guiTop (scaled px), size {width, height}, scale (framebuffer px per
     * scaled px), display {width, height} (framebuffer px), slots [{index, x, y, player?}] (container screens).
     */
    protected abstract JsonObject describe(Object screen, JsonObject check) throws Exception;

    protected abstract void saveFramebuffer(File png) throws Exception;

    /** Invokes a public method of the screen by name with String/Integer/Boolean arguments (test hook). */
    protected static Object callMethod(Object target, String name, Object[] args) throws Exception {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length && ok; i++) {
                Object a = args[i];
                ok = a == null ? !pt[i].isPrimitive()
                        : (pt[i] == int.class && a instanceof Integer) || (pt[i] == boolean.class && a instanceof Boolean)
                        || pt[i].isInstance(a);
            }
            if (ok) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name + " with " + args.length + " args on " + target.getClass().getName());
    }

    /** Text record helper shared by the font hooks. */
    public static JsonObject text(String s, double x, double y, int width, int color, boolean shadow) {
        JsonObject t = new JsonObject();
        t.addProperty("text", s);
        t.addProperty("x", Integer.valueOf((int) Math.round(x)));
        t.addProperty("y", Integer.valueOf((int) Math.round(y)));
        t.addProperty("width", Integer.valueOf(width));
        t.addProperty("color", String.format("%06x", Integer.valueOf(color & 0xffffff)));
        if (shadow) {
            t.addProperty("shadow", Boolean.TRUE);
        }
        return t;
    }
}
