package dev.porter.assets.model;

import dev.porter.assets.AssetTree;
import dev.porter.assets.Context;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Wavefront OBJ (+ MTL) export of a JSON model's elements, for blocks that need a hand-written 1.7.10 renderer
 * (ISBRH / TESR / AdvancedModelLoader). Coordinates in blocks (model units / 16), element rotation applied (no
 * rescale), UVs from the face {@code uv} or the vanilla defaults, face {@code rotation} applied to the UV corners.
 * Written to {@code meta/obj/<ns>/<path>.obj|.mtl}; materials point at the converted textures.
 */
public final class ObjExporter {
    private ObjExporter() {
    }

    /** Returns the meta path of the OBJ, or null if nothing could be exported. */
    static String export(String id, ModelConverter.Resolved model, AssetTree tree, Context ctx) {
        String ns = id.substring(0, id.indexOf(':'));
        String path = id.substring(id.indexOf(':') + 1);
        String base = "obj/" + ns + "/" + path;
        String name = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder obj = new StringBuilder("# porter assets: OBJ export of " + model.ref() + " for block " + id + "\n");
        obj.append("mtllib ").append(name).append(".mtl\n");
        Map<String, String> materials = new LinkedHashMap<>();
        int v = 1;
        int vt = 1;
        int faces = 0;
        int index = 0;
        String depthToRoot = "../".repeat(3 + (int) path.chars().filter(ch -> ch == '/').count());
        for (JsonNode e : model.elements()) {
            obj.append("o element_").append(index++).append('\n');
            double[] f = vec(e.get("from"));
            double[] t = vec(e.get("to"));
            JsonNode rot = e.get("rotation");
            for (String face : RenderResult.FACES) {
                JsonNode fn = e.path("faces").get(face);
                if (fn == null) {
                    continue;
                }
                String tex = ModelConverter.resolveVar(fn.path("texture").asString(""), model.textures());
                String mat = tex == null ? "missing" : tex.replace(':', '_').replace('/', '_');
                materials.putIfAbsent(mat, tex);
                double[][] corners = corners(face, f, t);
                double[] uv = fn.has("uv") ? vec4(fn.get("uv")) : defaultUv(face, f, t);
                double[][] uvc = {{uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
                int shift = (fn.path("rotation").asInt(0) / 90) & 3;
                for (double[] c : corners) {
                    double[] p = rot == null ? c : rotate(c, rot);
                    obj.append(String.format(Locale.ROOT, "v %.6f %.6f %.6f%n", p[0] / 16, p[1] / 16, p[2] / 16));
                }
                for (int i = 0; i < 4; i++) {
                    double[] q = uvc[(i + shift) & 3];
                    obj.append(String.format(Locale.ROOT, "vt %.6f %.6f%n", q[0] / 16, 1 - q[1] / 16));
                }
                obj.append("usemtl ").append(mat).append('\n');
                obj.append(String.format(Locale.ROOT, "f %d/%d %d/%d %d/%d %d/%d%n", v, vt, v + 1, vt + 1, v + 2, vt + 2, v + 3, vt + 3));
                v += 4;
                vt += 4;
                faces++;
            }
        }
        if (faces == 0) {
            return null;
        }
        StringBuilder mtl = new StringBuilder("# porter assets: materials for " + id + "\n");
        materials.forEach((mat, tex) -> {
            mtl.append("newmtl ").append(mat).append("\nKd 1.000000 1.000000 1.000000\n");
            if (tex != null) {
                int c = tex.indexOf(':');
                mtl.append("map_Kd ").append(depthToRoot).append("assets/").append(tex, 0, c).append("/textures/")
                        .append(tex.substring(c + 1)).append(".png\n");
            }
        });
        ctx.putMeta(base + ".obj", obj.toString().replace("\r\n", "\n"));
        ctx.putMeta(base + ".mtl", mtl.toString());
        return "meta/" + base + ".obj";
    }

    private static double[] vec(JsonNode a) {
        return new double[] {a.get(0).asDouble(), a.get(1).asDouble(), a.get(2).asDouble()};
    }

    private static double[] vec4(JsonNode a) {
        return new double[] {a.get(0).asDouble(), a.get(1).asDouble(), a.get(2).asDouble(), a.get(3).asDouble()};
    }

    /** Corners in the order top-left, bottom-left, bottom-right, top-right as seen from outside (CCW). */
    static double[][] corners(String face, double[] f, double[] t) {
        double x0 = f[0], y0 = f[1], z0 = f[2], x1 = t[0], y1 = t[1], z1 = t[2];
        return switch (face) {
            case "down" -> new double[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
            case "up" -> new double[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case "north" -> new double[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case "south" -> new double[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case "west" -> new double[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            case "east" -> new double[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
            default -> throw new IllegalArgumentException(face);
        };
    }

    /** Vanilla default UVs (FaceBakery) for a face of the box from..to. */
    static double[] defaultUv(String face, double[] f, double[] t) {
        return switch (face) {
            case "down" -> new double[] {f[0], 16 - t[2], t[0], 16 - f[2]};
            case "up" -> new double[] {f[0], f[2], t[0], t[2]};
            case "north" -> new double[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new double[] {f[0], 16 - t[1], t[0], 16 - f[1]};
            case "west" -> new double[] {f[2], 16 - t[1], t[2], 16 - f[1]};
            case "east" -> new double[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
            default -> throw new IllegalArgumentException(face);
        };
    }

    static double[] rotate(double[] p, JsonNode rot) {
        double[] o = vec(rot.get("origin"));
        double a = Math.toRadians(rot.path("angle").asDouble(0));
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double[] r = switch (rot.path("axis").asString("y")) {
            case "x" -> new double[] {x, y * cos - z * sin, y * sin + z * cos};
            case "z" -> new double[] {x * cos - y * sin, x * sin + y * cos, z};
            default -> new double[] {x * cos + z * sin, y, -x * sin + z * cos};
        };
        return new double[] {r[0] + o[0], r[1] + o[1], r[2] + o[2]};
    }
}
