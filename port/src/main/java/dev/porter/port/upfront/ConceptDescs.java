package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The read side of {@code concepts.yaml} that the matcher needs: per source type the descriptor fragment it becomes as a
 * parameter or return type ({@code param.desc}, {@code ret.desc}), and the type plans ({@code plan}, {@code shim}).
 * Expression templates ({@code expand}, {@code wrap}) belong to the rule emitter.
 */
public final class ConceptDescs {
    public record TypePlan(String plan, String shim) {
    }

    /** Internal source type name to descriptor fragment ({@code ""} = parameter dropped). */
    private final Map<String, String> param = new HashMap<>();
    private final Map<String, String> ret = new HashMap<>();
    /** Dotted type name to plan. */
    private final Map<String, TypePlan> plans = new HashMap<>();

    public static ConceptDescs empty() {
        return new ConceptDescs();
    }

    public static ConceptDescs load(Path yaml) {
        try {
            return parse(Json.yaml().readTree(Files.readString(yaml)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ConceptDescs parse(JsonNode root) {
        ConceptDescs c = new ConceptDescs();
        if (root.has("concepts")) {
            for (JsonNode n : root.get("concepts")) {
                String type = n.get("type").asString().replace('.', '/');
                if (n.has("param") && n.get("param").has("desc")) {
                    c.param.put(type, n.get("param").get("desc").asString());
                }
                if (n.has("ret") && n.get("ret").has("desc")) {
                    c.ret.put(type, n.get("ret").get("desc").asString());
                }
                c.plans.put(type.replace('/', '.'), new TypePlan(str(n, "plan"), str(n, "shim")));
            }
        }
        if (root.has("types")) {
            root.get("types").properties().forEach(e -> c.plans.put(e.getKey(), new TypePlan(str(e.getValue(), "plan"), str(e.getValue(), "shim"))));
        }
        return c;
    }

    private static String str(JsonNode n, String k) {
        return n.has(k) && !n.get(k).isNull() ? n.get(k).asString() : null;
    }

    public TypePlan planOf(String dottedType) {
        return plans.get(dottedType);
    }

    /**
     * Applies the concepts and the class map to a source descriptor: the 1.7.10 descriptor the member has when it differs
     * from the source only by concepts. Arrays of concept types are not expanded (only the class map applies).
     */
    public String conceptDesc(String desc, Map<String, String> classMap, Map<String, String> typeRuleClasses, Map<String, String> conceptOwners) {
        List<String> pa = Descs.params(desc);
        String r = Descs.ret(desc);
        if (pa == null) {
            return one(r, ret, classMap, typeRuleClasses, conceptOwners);
        }
        StringBuilder sb = new StringBuilder("(");
        for (String p : pa) {
            sb.append(one(p, param, classMap, typeRuleClasses, conceptOwners));
        }
        return sb.append(')').append(one(r, ret, classMap, typeRuleClasses, conceptOwners)).toString();
    }

    private static String one(String t, Map<String, String> table, Map<String, String> cmap, Map<String, String> rules, Map<String, String> owners) {
        if (t.startsWith("L") && t.endsWith(";")) {
            String hit = table.get(t.substring(1, t.length() - 1));
            if (hit != null) {
                return hit;
            }
        }
        return Descs.mapType(t, cmap, owners, rules);
    }
}
