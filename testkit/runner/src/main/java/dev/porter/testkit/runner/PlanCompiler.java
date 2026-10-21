package dev.porter.testkit.runner;

import dev.porter.core.json.Json;
import dev.porter.testkit.runner.expr.Expr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compiles a version-independent scenario into the flat, version-specific plan the test mods execute
 * (testkit/README.md, "plan format"): IR ids resolved through the {@link IdMap}, canonical sample keys mapped to the
 * version's keys (inverse keyMap), {@code build_multiblock} expanded from the IR multiblock node, and every key an
 * {@code assert} reads directly sampled right before it.
 */
public final class PlanCompiler {
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");

    private final Scenario scenario;
    private final String version;
    private final IdMap ids;
    private final IrIndex ir;

    public PlanCompiler(Scenario scenario, String version, IdMap ids, IrIndex ir) {
        this.scenario = scenario;
        this.version = version;
        this.ids = ids;
        this.ir = ir;
    }

    public ObjectNode compile() {
        ObjectNode plan = Json.json().createObjectNode();
        plan.put("format", "porter-testkit-plan/1");
        plan.put("scenario", scenario.id());
        plan.put("version", version);
        plan.put("side", scenario.side());
        plan.put("world", scenario.world());
        long max = scenario.maxTicks();
        if (max > 0) {
            plan.put("maxTicks", max);
        }
        ArrayNode ops = plan.putArray("ops");
        List<JsonNode> steps = scenario.steps();
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String type = Scenario.type(step);
            JsonNode a = step.path(type);
            switch (type) {
                case "place_block" -> {
                    ObjectNode op = op(ops, "place", i);
                    op.set("at", a.path("at").deepCopy());
                    ObjectNode block = ids.block(a.path("block").asString());
                    if (a.has("state")) {
                        block.set("state", a.path("state").deepCopy());
                    }
                    op.set("block", block);
                    if (a.has("facing")) {
                        op.put("facing", a.path("facing").asString());
                    }
                }
                case "build_multiblock" -> buildMultiblock(ops, i, a);
                case "fill_fluid" -> {
                    ObjectNode op = op(ops, "fill", i);
                    op.set("at", a.path("at").deepCopy());
                    op.set("fluid", fluid(a.path("fluid").asString()));
                    op.put("amount", a.path("amount").asInt());
                    if (a.has("side")) {
                        op.put("side", a.path("side").asString());
                    }
                }
                case "insert_item" -> {
                    ObjectNode op = op(ops, "insert", i);
                    op.set("at", a.path("at").deepCopy());
                    op.set("item", ids.item(a.path("item").asString()));
                    if (a.has("count")) {
                        op.put("count", a.path("count").asInt());
                    }
                    if (a.has("slot")) {
                        op.put("slot", a.path("slot").asInt());
                    }
                    if (a.has("side")) {
                        op.put("side", a.path("side").asString());
                    }
                }
                case "interact" -> {
                    ObjectNode op = op(ops, "interact", i);
                    op.set("at", a.path("at").deepCopy());
                    if (a.has("side")) {
                        op.put("side", a.path("side").asString());
                    }
                    if (a.has("holding")) {
                        op.set("holding", ids.item(a.path("holding").asString()));
                    }
                    if (a.has("sneaking")) {
                        op.put("sneaking", a.path("sneaking").asBoolean());
                    }
                }
                case "open_gui" -> {
                    ObjectNode op = op(ops, "open_gui", i);
                    if (a.has("at")) {
                        op.set("at", a.path("at").deepCopy());
                    }
                    if (a.has("item")) {
                        op.set("item", ids.item(a.path("item").asString()));
                    }
                    if (a.has("menu")) {
                        op.put("menu", a.path("menu").asString());
                    }
                    boolean shot = a.path("screenshot").asBoolean(false);
                    op.put("screenshot", shot);
                    op.put("screenshotName", scenario.id() + "-" + version + "-step" + i + ".png");
                    op.put("capture", a.path("capture").asString(scenario.id() + "-step" + i));
                    if (a.has("keepOpen")) {
                        op.put("keepOpen", a.path("keepOpen").asBoolean());
                    }
                    if (a.has("check")) {
                        op.set("check", a.path("check").deepCopy());
                    }
                }
                case "gui_input" -> {
                    ObjectNode op = op(ops, "gui_input", i);
                    for (String f : List.of("click", "button", "key", "call", "capture", "check")) {
                        if (a.has(f)) {
                            op.set(f, a.path(f).deepCopy());
                        }
                    }
                }
                case "close_gui" -> op(ops, "close_gui", i);
                case "tick" -> op(ops, "tick", i).put("n", step.path("tick").asLong());
                case "sample" -> {
                    ObjectNode op = op(ops, "sample", i);
                    if (a.has("every")) {
                        op.put("every", a.path("every").asLong());
                    }
                    ArrayNode keys = op.putArray("keys");
                    for (JsonNode k : a.path("what")) {
                        keys.add(scenario.toVersionKey(k.asString(), version));
                    }
                }
                case "assert" -> {
                    Expr.Node expr = Expr.parse(a.path("expr").asString());
                    List<String> direct = new ArrayList<>();
                    for (String k : Expr.directKeys(expr)) {
                        if (k.contains(":")) {
                            direct.add(scenario.toVersionKey(k, version));
                        }
                    }
                    if (!direct.isEmpty()) {
                        ObjectNode op = op(ops, "sample", i);
                        op.put("implicit", true);
                        ArrayNode keys = op.putArray("keys");
                        direct.forEach(keys::add);
                    }
                }
                default -> throw new IllegalArgumentException("unknown step " + type);
            }
        }
        return plan;
    }

    private static ObjectNode op(ArrayNode ops, String kind, int step) {
        ObjectNode op = ops.addObject();
        op.put("op", kind);
        op.put("step", step);
        return op;
    }

    private ObjectNode fluid(String irId) {
        ObjectNode f = ids.fluid(irId);
        if (version.equals("forge-1.7.10") && f.path("id").asString().equals(irId) && irId.startsWith("minecraft:")) {
            // 1.7.10 fluid registry names have no namespace (water, lava)
            f.put("id", irId.substring("minecraft:".length()));
        }
        return f;
    }

    // ------------------------------------------------------------------ multiblocks

    private void buildMultiblock(ArrayNode ops, int step, JsonNode a) {
        String id = a.path("id").asString();
        JsonNode mb = ir.multiblock(id);
        if (mb == null) {
            throw new IllegalArgumentException("build_multiblock: no IR multiblock node " + id + " (pass --ir)");
        }
        int sx = mb.path("size").get(0).asInt();
        int sy = mb.path("size").get(1).asInt();
        int sz = mb.path("size").get(2).asInt();
        String natural = mb.path("controller").path("facing").asString("north");
        String target = a.has("facing") ? a.path("facing").asString() : natural;
        int turns = 0;
        if (HORIZONTAL.contains(natural) && HORIZONTAL.contains(target)) {
            turns = Math.floorMod(HORIZONTAL.indexOf(target) - HORIZONTAL.indexOf(natural), 4);
        } else if (!natural.equals(target)) {
            throw new IllegalArgumentException("build_multiblock: cannot rotate " + id + " from " + natural + " to " + target);
        }
        int ax = a.path("at").get(0).asInt();
        int ay = a.path("at").get(1).asInt();
        int az = a.path("at").get(2).asInt();
        JsonNode ctrl = mb.path("controller");
        int[] cpos = ctrl.has("pos") ? new int[] {ctrl.path("pos").get(0).asInt(), ctrl.path("pos").get(1).asInt(), ctrl.path("pos").get(2).asInt()} : null;
        ObjectNode controllerOp = null;
        for (int y = 0; y < sy; y++) {
            JsonNode layer = mb.path("layers").get(y);
            for (int z = 0; z < sz; z++) {
                String row = layer.get(z).asString();
                for (int x = 0; x < sx; x++) {
                    String ch = String.valueOf(row.charAt(x));
                    JsonNode ref = mb.path("palette").get(ch);
                    if (ref == null || (ref.isObject() && ref.path("any").asBoolean(false))) {
                        continue;
                    }
                    String blockId = ref.isString() ? ref.asString() : ref.path("block").asString();
                    int[] r = rotate(x, z, sx, sz, turns);
                    ObjectNode op = Json.json().createObjectNode();
                    op.put("op", "place");
                    op.put("step", step);
                    ArrayNode at = op.putArray("at");
                    at.add(ax + r[0]);
                    at.add(ay + y);
                    at.add(az + r[1]);
                    ObjectNode block = ids.block(blockId);
                    if (ref.isObject() && ref.has("state")) {
                        ObjectNode st = (ObjectNode) ref.path("state").deepCopy();
                        for (Map.Entry<String, JsonNode> e : ref.path("state").properties()) {
                            if (e.getKey().equals("facing") && HORIZONTAL.contains(e.getValue().asString())) {
                                st.put("facing", turn(e.getValue().asString(), turns));
                            }
                        }
                        block.set("state", st);
                    }
                    op.set("block", block);
                    boolean isController = cpos != null && cpos[0] == x && cpos[1] == y && cpos[2] == z;
                    if (isController) {
                        if (ctrl.has("facing")) {
                            op.put("facing", turn(natural, turns));
                        }
                        controllerOp = op;
                    } else {
                        ops.add(op);
                    }
                }
            }
        }
        if (controllerOp != null) {
            ops.add(controllerOp); // controller last: the structure is complete when it first checks itself
        }
    }

    /** Rotates (x, z) inside an sx*sz footprint by quarter turns clockwise (seen from above). */
    static int[] rotate(int x, int z, int sx, int sz, int turns) {
        int cx = x;
        int cz = z;
        int w = sx;
        int d = sz;
        for (int i = 0; i < turns; i++) {
            int nx = d - 1 - cz;
            int nz = cx;
            cx = nx;
            cz = nz;
            int t = w;
            w = d;
            d = t;
        }
        return new int[] {cx, cz};
    }

    static String turn(String facing, int turns) {
        int i = HORIZONTAL.indexOf(facing);
        return i < 0 ? facing : HORIZONTAL.get((i + turns) % 4);
    }
}
