package ic2cprofile.extract;

import dev.porter.extract.forge1192.ExtractContext;
import dev.porter.extract.forge1192.interp.V;
import dev.porter.extract.forge1192.interp.V.Obj;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * IC2C texture packages of a block ({@code assets/<ns>/textures/block/<path>/texture_package.json}), from
 * <ul>
 *   <li>texture providers held by the block (ToggleProvider(mod, path)... whose getTexture calls
 *       IC2Textures#getMappedEntriesBlock(this.mod, this.path)), and</li>
 *   <li>literal {@code IC2Textures.getMappedEntriesBlockIC2("misc/barrel")} calls in the block's class hierarchy.</li>
 * </ul>
 * Only packages that exist as files are reported; {@code porter:texturePackage} is set when exactly one remains, or
 * to the package of the main (model) provider among several. A single-sprite provider sets the block {@code textures}
 * instead, an item that names its package entry the item {@code textures}.
 */
final class Ic2cTextures {
    private Ic2cTextures() {
    }

    static Set<String> packages(ExtractContext ctx, Obj block) {
        Set<String> out = new TreeSet<>();
        for (Object v : block.fields.values()) {
            provider(ctx, v, out);
            if (v instanceof V.Seq s) {
                s.items.forEach(x -> provider(ctx, x, out));
            }
        }
        for (CtType<?> t = block.type; t != null; t = ctx.interp.superType(t)) {
            for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
                String n = inv.getExecutable().getSimpleName();
                List<CtExpression<?>> a = inv.getArguments();
                if (n.equals("getMappedEntriesBlockIC2") && a.size() == 1 && a.get(0) instanceof CtLiteral<?> l
                        && l.getValue() instanceof String p) {
                    add(ctx, "ic2", p, out);
                } else if (n.equals("getMappedEntriesBlock") && a.size() == 2 && a.get(0) instanceof CtLiteral<?> l0
                        && l0.getValue() instanceof String ns && a.get(1) instanceof CtLiteral<?> l1
                        && l1.getValue() instanceof String p) {
                    add(ctx, ns, p, out);
                }
            }
        }
        return out;
    }

    /**
     * The package of the block's main texture provider, when it holds several (overlays, formed/unformed): the
     * provider field of the class that renders the block model ({@code <base class>#provider}, a field hidden by a
     * subclass field of the same name, first; then {@code provider}). null if none of those is a package provider.
     */
    static String primary(ExtractContext ctx, Obj block) {
        List<Map.Entry<String, Object>> ranked = new ArrayList<>(block.fields.entrySet());
        ranked.sort(java.util.Comparator.comparingInt(e -> rank(e.getKey())));
        for (Map.Entry<String, Object> e : ranked) {
            if (rank(e.getKey()) > 1) {
                break;
            }
            Set<String> one = new TreeSet<>();
            provider(ctx, e.getValue(), one);
            if (one.size() == 1) {
                return one.iterator().next();
            }
        }
        return null;
    }

    /** 0 = a hidden base-class "provider" field, 1 = "provider", 2 = any other field. */
    private static int rank(String key) {
        return key.endsWith("#provider") ? 0 : key.equals("provider") ? 1 : 2;
    }

    private static void provider(ExtractContext ctx, Object v, Set<String> out) {
        if (!(v instanceof Obj o)) {
            return;
        }
        CtMethod<?> m = ctx.interp.sourceMethod(o.type, "getTexture", 2);
        String[] pkg = m == null ? null : blockPackage(o, m);
        if (pkg == null || spriteKey(o, m) != null) {
            return; // no provider, or one sprite of a shared package (SingleProvider): see sprite()
        }
        add(ctx, pkg[0], pkg[1], out);
    }

    /**
     * {@code [namespace, path]} of the package a provider's getTexture reads: {@code getMappedEntriesBlock(mod, path)}
     * or {@code getMappedEntriesBlockIC2(path)}, arguments as literals or fields of the provider.
     */
    private static String[] blockPackage(Obj o, CtMethod<?> getTexture) {
        for (CtInvocation<?> inv : getTexture.getElements(new TypeFilter<>(CtInvocation.class))) {
            String n = inv.getExecutable().getSimpleName();
            List<CtExpression<?>> a = inv.getArguments();
            if (n.equals("getMappedEntriesBlock") && a.size() == 2) {
                String ns = string(o, a.get(0));
                String path = string(o, a.get(1));
                return ns == null || path == null ? null : new String[] {ns, path};
            }
            if (n.equals("getMappedEntriesBlockIC2") && a.size() == 1) {
                String path = string(o, a.get(0));
                return path == null ? null : new String[] {"ic2", path};
            }
        }
        return null;
    }

    /**
     * Texture of a block drawn with one sprite of a shared package: a provider held by the block whose getTexture is
     * {@code getMappedEntriesBlock(mod, path).get(this.<field>)} (IC2C SingleProvider), resolved through the package
     * file ({@code "valve" -> "ic2:block/misc/valve"}). null if none.
     */
    static String sprite(ExtractContext ctx, Obj block) {
        List<Map.Entry<String, Object>> ranked = new ArrayList<>(block.fields.entrySet());
        ranked.sort(java.util.Comparator.comparingInt(e -> rank(e.getKey()))); // main provider before overlays
        for (Map.Entry<String, Object> e : ranked) {
            if (e.getValue() instanceof Obj o) {
                CtMethod<?> m = ctx.interp.sourceMethod(o.type, "getTexture", 2);
                String[] pkg = m == null ? null : blockPackage(o, m);
                String key = pkg == null ? null : spriteKey(o, m);
                if (key != null) {
                    String t = entry(ctx, pkg[0], pkg[1].isEmpty() ? "block" : "block/" + pkg[1], key);
                    if (t != null) {
                        return t;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Sprite of an item that names its texture package and entry (IC2C IC2SimpleItem: getTexture() =
     * {@code getMappedEntriesItem(ns, this.textureFolder).get(this.textureName)}), e.g. {@code ic2:item/food/grist}.
     */
    static String itemSprite(ExtractContext ctx, Obj item, String itemNamespace) {
        CtMethod<?> m = ctx.interp.sourceMethod(item.type, "getTexture", 0);
        if (m == null) {
            return null;
        }
        for (CtInvocation<?> get : m.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!get.getExecutable().getSimpleName().equals("get") || get.getArguments().size() != 1
                    || !(get.getTarget() instanceof CtInvocation<?> src)) {
                continue;
            }
            String n = src.getExecutable().getSimpleName();
            List<CtExpression<?>> a = src.getArguments();
            String folder;
            String ns = itemNamespace;
            if (n.equals("getMappedEntriesItemIC2") && a.size() == 1) {
                folder = string(item, a.get(0));
                ns = "ic2";
            } else if (n.equals("getMappedEntriesItem") && a.size() == 2) {
                folder = string(item, a.get(1));
                String explicit = string(item, a.get(0));
                ns = explicit != null ? explicit : itemNamespace;
            } else {
                continue;
            }
            String key = string(item, get.getArguments().get(0));
            if (folder != null && key != null && !key.isEmpty()) {
                return entry(ctx, ns, folder.isEmpty() ? "item" : "item/" + folder, key);
            }
        }
        return null;
    }

    /** The field a provider's getTexture looks its sprite up with ({@code .get(this.name)}), as its value. */
    private static String spriteKey(Obj o, CtMethod<?> getTexture) {
        for (CtInvocation<?> get : getTexture.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (get.getExecutable().getSimpleName().equals("get") && get.getArguments().size() == 1
                    && get.getTarget() instanceof CtInvocation<?> src
                    && src.getExecutable().getSimpleName().startsWith("getMappedEntriesBlock")
                    && get.getArguments().get(0) instanceof CtFieldRead<?> fr
                    && o.fields.get(fr.getVariable().getSimpleName()) instanceof String key) {
                return key;
            }
        }
        return null;
    }

    private static String string(Obj o, CtExpression<?> e) {
        if (e instanceof CtLiteral<?> l && l.getValue() instanceof String s) {
            return s;
        }
        if (e instanceof CtFieldRead<?> fr && o.fields.get(fr.getVariable().getSimpleName()) instanceof String s) {
            return s;
        }
        return null;
    }

    /** Value of {@code key} in {@code assets/<ns>/textures/<pkg>/texture_package.json}, if it is a texture ref. */
    private static String entry(ExtractContext ctx, String ns, String pkg, String key) {
        String rel = "assets/" + ns + "/textures/" + pkg + "/texture_package.json";
        for (Path r : ctx.layout.resourceRoots()) {
            Path f = r.resolve(rel);
            if (Files.isRegularFile(f)) {
                try {
                    JsonNode v = Json.json().readTree(Files.readString(f, StandardCharsets.UTF_8)).get(key);
                    return v != null && v.isString() && v.asString().contains(":") ? v.asString() : null;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void add(ExtractContext ctx, String ns, String path, Set<String> out) {
        String rel = "assets/" + ns + "/textures/" + (path.isEmpty() ? "block" : "block/" + path) + "/texture_package.json";
        for (Path r : ctx.layout.resourceRoots()) {
            if (Files.isRegularFile(r.resolve(rel))) {
                out.add(ns + ":" + (path.isEmpty() ? "block" : "block/" + path));
                return;
            }
        }
    }
}
