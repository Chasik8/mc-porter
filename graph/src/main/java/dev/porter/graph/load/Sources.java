package dev.porter.graph.load;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** The input manifest ({@code dev/porter/graph/sources.json}): jars and mappings per Minecraft version. */
public final class Sources {
    /** One downloadable artifact (file or archive). */
    public record Artifact(String type, String name, String version, String dir, String url, String sha1, String entry,
                           List<String> local) {
        public String fileName() {
            return url.substring(url.lastIndexOf('/') + 1);
        }
    }

    /** One Minecraft version: client jar + mapping artifacts, canonical namespace for symbol refs. */
    public record Version(String id, String canonical, Artifact jar, List<Artifact> mappings) {
        public boolean has(String type) {
            return mappings.stream().anyMatch(m -> m.type().equals(type));
        }
    }

    private final List<Version> versions;

    private Sources(List<Version> versions) {
        this.versions = List.copyOf(versions);
    }

    public static Sources bundled() {
        try (InputStream in = Sources.class.getResourceAsStream("/dev/porter/graph/sources.json")) {
            if (in == null) {
                throw new IllegalStateException("sources.json missing from the graph jar");
            }
            return parse(Json.json().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Sources parse(JsonNode root) {
        List<Version> out = new ArrayList<>();
        for (JsonNode v : root.get("versions")) {
            List<Artifact> maps = new ArrayList<>();
            for (JsonNode m : v.get("mappings")) {
                maps.add(artifact(m));
            }
            out.add(new Version(v.get("id").asString(), v.get("canonical").asString(), artifact(v.get("jar")), maps));
        }
        return new Sources(out);
    }

    private static Artifact artifact(JsonNode n) {
        List<String> local = new ArrayList<>();
        if (n.has("local")) {
            n.get("local").forEach(x -> local.add(x.asString()));
        }
        return new Artifact(text(n, "type"), text(n, "name"), text(n, "version"), text(n, "dir"), text(n, "url"),
                text(n, "sha1"), text(n, "entry"), local);
    }

    private static String text(JsonNode n, String key) {
        return n.has(key) ? n.get(key).asString() : null;
    }

    /** All versions, newest first (the order of the manifest). */
    public List<Version> versions() {
        return versions;
    }

    public Optional<Version> version(String id) {
        return versions.stream().filter(v -> v.id().equals(id)).findFirst();
    }
}
