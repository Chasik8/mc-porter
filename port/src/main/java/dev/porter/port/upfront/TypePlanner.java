package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import dev.porter.port.upfront.model.Plan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The per-type plan: for every 1.19.2 type the mod references, before any port run, what it becomes.
 * <ol>
 *   <li>the {@code types:} section of concepts.yaml (explicit);</li>
 *   <li>a hand type rule: MAP if it names a vanilla/Forge/JDK class, MIRROR if it names a shim class;</li>
 *   <li>a concept that names a shim: MIRROR with that shim;</li>
 *   <li>the lineage maps the class (and the target exists): MAP;</li>
 *   <li>a nested class follows its outer class ({@code Outer$Inner} becomes {@code <outer target>$Inner});</li>
 *   <li>otherwise MIRROR with a new generated shim class {@code dev.porter.shim.gen.<package without
 *       net.minecraft>.<Simple>} (generated later by the shim skeleton step).</li>
 * </ol>
 * Types are source names with {@code $} for nested classes. Every input type gets a plan.
 */
public final class TypePlanner {

    /** {@code concept} = the type is also a signature concept; {@code method} = which step decided. */
    public record TypePlan(String type, Plan plan, String target, String method, boolean concept) {
    }

    public static final String DEFAULT_SHIM_PREFIX = "dev.porter.shim";
    public static final String GEN_PACKAGE = "dev.porter.shim.gen";

    private final Concepts concepts;
    private final Map<String, String> lineage;
    private final Map<String, String> handRules;
    private final List<String> shimPrefixes;

    /**
     * @param lineage    source class to its 1.7.10 class, only entries whose chain reaches 1.7.10 and exist there
     * @param handRules  source class to the class a hand type rule replaces it with
     * @param shimPrefixes a replacement starting with one of these is a shim (MIRROR), anything else is a class of the
     *                     target platform (MAP); empty = {@link #DEFAULT_SHIM_PREFIX}
     */
    public TypePlanner(Concepts concepts, Map<String, String> lineage, Map<String, String> handRules,
            Collection<String> shimPrefixes) {
        this.concepts = concepts;
        this.lineage = lineage;
        this.handRules = handRules;
        this.shimPrefixes = shimPrefixes == null || shimPrefixes.isEmpty() ? List.of(DEFAULT_SHIM_PREFIX) : List.copyOf(shimPrefixes);
    }

    /** the plan of every type, sorted by name (deterministic) */
    public Map<String, TypePlan> plan(Collection<String> types) {
        Map<String, TypePlan> out = new TreeMap<>();
        for (String t : types) {
            out.put(t, plan(t));
        }
        return out;
    }

    public TypePlan plan(String type) {
        boolean concept = concepts.find(type).isPresent();
        Concepts.TypeEntry explicit = concepts.types().get(type);
        if (explicit != null) {
            return new TypePlan(type, explicit.plan() == Plan.MAP ? Plan.MAP : Plan.MIRROR,
                    explicit.shim() != null ? explicit.shim() : generated(type), "types-yaml", concept);
        }
        String rule = handRules.get(type);
        if (rule != null) {
            return new TypePlan(type, isShim(rule) ? Plan.MIRROR : Plan.MAP, rule, "rule", concept);
        }
        var c = concepts.find(type);
        if (c.isPresent() && c.get().shim() != null) {
            return new TypePlan(type, Plan.MIRROR, c.get().shim(), "concept", true);
        }
        String lin = lineage.get(type);
        if (lin != null) {
            return new TypePlan(type, Plan.MAP, lin, "lineage", concept);
        }
        int dollar = type.lastIndexOf('$');
        if (dollar > 0) {
            TypePlan outer = plan(type.substring(0, dollar));
            return new TypePlan(type, outer.plan(), outer.target() + type.substring(dollar), "outer", concept);
        }
        return new TypePlan(type, Plan.MIRROR, generated(type), "new-mirror", concept);
    }

    private boolean isShim(String fqn) {
        for (String p : shimPrefixes) {
            if (fqn.equals(p) || fqn.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The generated mirror class of a type: {@code net.minecraft.world.level.block.Foo} becomes
     * {@code dev.porter.shim.gen.world.level.block.Foo}, {@code net.minecraftforge.x.Foo} becomes
     * {@code dev.porter.shim.gen.forge.x.Foo}, other packages are kept ({@code com.mojang.math.Foo} becomes
     * {@code dev.porter.shim.gen.com.mojang.math.Foo}). A nested class keeps its {@code $} suffix.
     */
    public static String generated(String type) {
        String outer = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
        String nested = type.substring(outer.length());
        String pkg = outer.contains(".") ? outer.substring(0, outer.lastIndexOf('.')) : "";
        String simple = outer.substring(outer.lastIndexOf('.') + 1);
        if (pkg.equals("net.minecraft")) {
            pkg = "";
        } else if (pkg.startsWith("net.minecraft.")) {
            pkg = pkg.substring("net.minecraft.".length());
        } else if (pkg.equals("net.minecraftforge")) {
            pkg = "forge";
        } else if (pkg.startsWith("net.minecraftforge.")) {
            pkg = "forge." + pkg.substring("net.minecraftforge.".length());
        }
        return GEN_PACKAGE + (pkg.isEmpty() ? "" : "." + pkg) + "." + simple + nested;
    }

    // ------------------------------------------------------------------------------------------------ hand rules

    /**
     * Type rules of rule files (YAML documents separated by {@code ---}): {@code match: {type: X}} with a string
     * {@code replace}. Files are read from the given files or directories (recursively, {@code *.yaml}). The first
     * rule for a type wins.
     */
    public static Map<String, String> handTypeRules(List<Path> roots) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path root : roots) {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(p -> p.toString().endsWith(".yaml")).sorted().forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(root)) {
                files.add(root);
            }
            for (Path f : files) {
                readTypeRules(f, out);
            }
        }
        return out;
    }

    private static void readTypeRules(Path file, Map<String, String> out) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String doc : text.split("(?m)^---\\s*$")) {
            if (doc.isBlank()) {
                continue;
            }
            JsonNode n;
            try {
                n = Json.yaml().readTree(doc);
            } catch (RuntimeException e) {
                continue; // not a rule document
            }
            JsonNode match = n.path("match");
            JsonNode replace = n.path("replace");
            if (match.isObject() && match.size() == 1 && match.has("type") && replace.isString()) {
                out.putIfAbsent(match.get("type").asString(), replace.asString());
            }
        }
    }
}
