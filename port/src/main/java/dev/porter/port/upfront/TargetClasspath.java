package dev.porter.port.upfront;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The default 1.7.10 class path for {@code bodies check} and {@code upfront --check-bodies}: the MCP jar of the cache,
 * the libraries listed in the Forge 1.7.10 launcher version json (only the entries that have an {@code artifact.path}
 * and exist on disk), and the adapter source directories of the porter checkout.
 */
public final class TargetClasspath {
    private TargetClasspath() {
    }

    /** The Minecraft directory: {@code $PORTER_MINECRAFT}, else {@code %APPDATA%/.minecraft}, else {@code ~/.minecraft}. */
    public static Path defaultMinecraftDir() {
        String env = System.getenv("PORTER_MINECRAFT");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".minecraft");
        }
        return Path.of(System.getProperty("user.home")).resolve(".minecraft");
    }

    /**
     * @param mcJar the 1.7.10 MCP jar
     * @param mcDir the Minecraft directory ({@code versions/} and {@code libraries/} below it)
     * @param versionId the version folder and json name (default {@code Forge 1.7.10})
     * @param adapterDirs source directories compiled-against (adapter pack); missing ones are skipped
     */
    public static List<Path> build(Path mcJar, Path mcDir, String versionId, List<Path> adapterDirs) throws IOException {
        List<Path> cp = new ArrayList<>();
        cp.add(mcJar);
        for (Path d : adapterDirs) {
            if (Files.isDirectory(d)) {
                cp.add(d);
            }
        }
        Path json = mcDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IllegalArgumentException("Forge version json not found: " + json + " (use --cp or --mc-dir / $PORTER_MINECRAFT)");
        }
        Map<?, ?> root = Json.yaml().readValue(Files.readString(json, StandardCharsets.UTF_8), Map.class);
        if (root.get("libraries") instanceof List<?> libs) {
            for (Object o : libs) {
                if (o instanceof Map<?, ?> lib && lib.get("artifact") instanceof Map<?, ?> art && art.get("path") != null) {
                    Path jar = mcDir.resolve("libraries").resolve(art.get("path").toString());
                    if (Files.isRegularFile(jar)) {
                        cp.add(jar);
                    }
                }
            }
        }
        return cp;
    }

    /** Adapter source directories of a porter checkout rooted at {@code repo}. */
    public static List<Path> adapterDirs(Path repo) {
        return List.of(repo.resolve("rewrite/shim/src/adapters/java"), repo.resolve("rewrite/shim/src/main/java"));
    }
}
