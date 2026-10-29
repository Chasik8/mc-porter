package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * {@code match-tables.yaml}: every number, synonym and owner the matcher decides with. The matcher code contains no
 * symbol names; a wrong decision is fixed here (a general fact), never with a per-symbol branch.
 */
public final class MatchTables {
    /** One area: the first whose regex finds the owner FQN wins. */
    public record Area(String name, Pattern regex) {
    }

    public final Map<String, Double> weights = new LinkedHashMap<>();
    public double accept = 0.62;
    public double margin = 0.08;
    public double lineageMin = 0.30;
    public double accessorPenalty = 0.05;
    public final Map<String, List<String>> synonyms = new LinkedHashMap<>();
    public final List<String> constantPrefixes = new ArrayList<>();
    public final Map<String, String> conceptOwners = new LinkedHashMap<>();
    public final Set<String> registryOwners = new LinkedHashSet<>();
    public final List<Area> areas = new ArrayList<>();

    public static MatchTables load(Path yaml) {
        try {
            return parse(Json.yaml().readTree(Files.readString(yaml)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MatchTables parse(JsonNode root) {
        MatchTables t = new MatchTables();
        if (root.has("weights")) {
            root.get("weights").properties().forEach(e -> t.weights.put(e.getKey(), e.getValue().asDouble()));
        }
        for (String k : new String[] {"name", "arity", "ret", "params"}) {
            t.weights.putIfAbsent(k, 0.0);
        }
        if (root.has("accept")) {
            t.accept = root.get("accept").asDouble();
        }
        if (root.has("margin")) {
            t.margin = root.get("margin").asDouble();
        }
        if (root.has("lineageMin")) {
            t.lineageMin = root.get("lineageMin").asDouble();
        }
        if (root.has("accessorPenalty")) {
            t.accessorPenalty = root.get("accessorPenalty").asDouble();
        }
        if (root.has("synonyms")) {
            root.get("synonyms").properties().forEach(e -> {
                List<String> l = new ArrayList<>();
                e.getValue().forEach(v -> l.add(v.asString()));
                t.synonyms.put(e.getKey(), l);
            });
        }
        if (root.has("constantPrefixes")) {
            root.get("constantPrefixes").forEach(v -> t.constantPrefixes.add(v.asString()));
        }
        if (root.has("conceptOwners")) {
            root.get("conceptOwners").properties().forEach(e -> t.conceptOwners.put(e.getKey(), e.getValue().asString()));
        }
        if (root.has("registryOwners")) {
            root.get("registryOwners").forEach(v -> t.registryOwners.add(v.asString()));
        }
        if (root.has("areas")) {
            for (JsonNode a : root.get("areas")) {
                t.areas.add(new Area(a.get("area").asString(), Pattern.compile(a.get("regex").asString())));
            }
        }
        return t;
    }

    /** First matching area of an owner FQN, or {@code "other"}. */
    public String areaOf(String fqn) {
        for (Area a : areas) {
            if (a.regex().matcher(fqn).find()) {
                return a.name();
            }
        }
        return "other";
    }
}
