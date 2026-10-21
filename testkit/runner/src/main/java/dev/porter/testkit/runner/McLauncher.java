package dev.porter.testkit.runner;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Launches a Minecraft dedicated server (logic scenarios) or client (GUI scenarios) with the test mod and the mods
 * under test, waits with a wall-clock timeout, kills the whole process tree on a hang and retries.
 *
 * <p>Every run gets a fresh directory {@code <work>/<scenario>-<version>/attempt-N} (world, config, logs, plan,
 * raw result). Supported versions: forge-1.19.2 (server), forge-1.7.10 (server and client).
 */
public final class McLauncher {
    /** Outcome of one launch (after retries). */
    public record Outcome(JsonNode raw, String failure, Path runDir, int attempts) {
    }

    private final Env env;
    private final Path work;
    private final int timeoutSeconds;
    private final int retries;
    private final java.io.PrintWriter log;

    public McLauncher(Env env, Path work, int timeoutSeconds, int retries, java.io.PrintWriter log) {
        this.env = env;
        this.work = work;
        this.timeoutSeconds = timeoutSeconds;
        this.retries = retries;
        this.log = log;
    }

    public Outcome run(ObjectNode plan, String version, List<Path> mods) {
        String scenario = plan.path("scenario").asString();
        boolean client = plan.path("side").asString("server").equals("client");
        String failure = null;
        Path dir = null;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            dir = work.resolve(scenario + "-" + version).resolve("attempt-" + attempt);
            try {
                deleteRecursively(dir);
                Files.createDirectories(dir.resolve("mods"));
                Path testmod = Path.of(env.require(version + ".testmod"));
                if (!Files.isRegularFile(testmod)) {
                    throw new IllegalArgumentException("test mod jar " + testmod + " not found (build testkit/mod-" + version.substring(6) + ")");
                }
                Files.copy(testmod, dir.resolve("mods").resolve(testmod.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                for (Path m : mods) {
                    Files.copy(m, dir.resolve("mods").resolve(m.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
                Path planFile = dir.resolve("plan.json");
                Files.writeString(planFile, Json.json().writeValueAsString(plan), StandardCharsets.UTF_8);
                Path raw = dir.resolve("raw.json");
                List<String> cmd = client ? clientCommand(version, dir, planFile, raw) : serverCommand(version, dir, planFile, raw);
                log.println("[testkit] " + version + " " + (client ? "client" : "server") + " attempt " + attempt + " in " + dir);
                log.flush();
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                        .redirectErrorStream(true).redirectOutput(dir.resolve("console.log").toFile());
                pb.redirectInput(ProcessBuilder.Redirect.from(emptyStdin(dir).toFile()));
                pb.environment().remove("CLASSPATH"); // the game must not inherit the caller's classpath (porter's own jars)
                Process p = pb.start();
                boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    threadDump(version, p, dir);
                    kill(p);
                    failure = "timeout: no exit within " + timeoutSeconds + " s (attempt " + attempt + ")";
                } else if (Files.isRegularFile(raw)) {
                    JsonNode r = Json.json().readTree(Files.readString(raw, StandardCharsets.UTF_8));
                    if (!r.path("status").asString().equals("timeout") || attempt > retries) {
                        return new Outcome(r, null, dir, attempt);
                    }
                    failure = "wall-clock timeout inside the game (attempt " + attempt + ")";
                } else {
                    failure = "process exited with " + p.exitValue() + " without a result" + crashCause(dir) + " (see " + dir.resolve("console.log") + ")";
                }
            } catch (IOException e) {
                failure = "launch failed: " + e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(null, "interrupted", dir, attempt);
            }
            log.println("[testkit] " + failure);
            log.flush();
        }
        return new Outcome(null, failure, dir, retries + 1);
    }

    private int batchSeq;

    /**
     * Several server scenarios of one version and mod set in ONE launch: the test mod runs the plans one after another
     * and resets the world between them (testkit/README.md, "batches"). Returns one outcome per plan, in order.
     * A launch that dies or hangs part way is repeated for the scenarios that have no result yet.
     */
    public List<Outcome> runBatch(List<ObjectNode> plans, String version, List<Path> mods) {
        int n = plans.size();
        Outcome[] res = new Outcome[n];
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pending.add(i);
        }
        String failure = null;
        Path dir = null;
        int seq = ++batchSeq;
        for (int attempt = 1; attempt <= retries + 1 && !pending.isEmpty(); attempt++) {
            dir = work.resolve("batch-" + version + "-" + seq).resolve("attempt-" + attempt);
            try {
                deleteRecursively(dir);
                Files.createDirectories(dir.resolve("mods"));
                Path testmod = Path.of(env.require(version + ".testmod"));
                if (!Files.isRegularFile(testmod)) {
                    throw new IllegalArgumentException("test mod jar " + testmod + " not found (build testkit/mod-" + version.substring(6) + ")");
                }
                Files.copy(testmod, dir.resolve("mods").resolve(testmod.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                for (Path m : mods) {
                    Files.copy(m, dir.resolve("mods").resolve(m.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
                ObjectNode batch = Json.json().createObjectNode();
                batch.put("format", "porter-testkit-batch/1");
                var arr = batch.putArray("plans");
                for (int i : pending) {
                    ObjectNode p = plans.get(i).deepCopy();
                    p.put("out", dir.resolve("raw-" + i + ".json").toAbsolutePath().toString());
                    arr.add(p);
                }
                Path planFile = dir.resolve("plan.json");
                Files.writeString(planFile, Json.json().writeValueAsString(batch), StandardCharsets.UTF_8);
                List<String> cmd = serverCommand(version, dir, planFile, dir.resolve("raw.json"));
                log.println("[testkit] " + version + " server (batch of " + pending.size() + ") attempt " + attempt + " in " + dir);
                log.flush();
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile())
                        .redirectErrorStream(true).redirectOutput(dir.resolve("console.log").toFile());
                pb.redirectInput(ProcessBuilder.Redirect.from(emptyStdin(dir).toFile()));
                pb.environment().remove("CLASSPATH"); // the game must not inherit the caller's classpath (porter's own jars)
                Process p = pb.start();
                long limit = (long) timeoutSeconds * pending.size();
                boolean finished = p.waitFor(limit, TimeUnit.SECONDS);
                if (!finished) {
                    threadDump(version, p, dir);
                    kill(p);
                    failure = "timeout: no exit within " + limit + " s (attempt " + attempt + ")";
                } else if (!Files.isRegularFile(dir.resolve("raw-" + pending.get(0) + ".json"))) {
                    failure = "process exited with " + p.exitValue() + " without a result" + crashCause(dir) + " (see " + dir.resolve("console.log") + ")";
                } else {
                    failure = "process exited with " + p.exitValue() + " before every scenario had a result (see " + dir.resolve("console.log") + ")";
                }
                List<Integer> still = new ArrayList<>();
                for (int i : pending) {
                    Path raw = dir.resolve("raw-" + i + ".json");
                    if (Files.isRegularFile(raw)) {
                        JsonNode r = Json.json().readTree(Files.readString(raw, StandardCharsets.UTF_8));
                        if (r.path("status").asString().equals("timeout") && attempt <= retries) {
                            still.add(i);
                        } else {
                            res[i] = new Outcome(r, null, dir, attempt);
                        }
                    } else {
                        still.add(i);
                    }
                }
                if (still.isEmpty()) {
                    failure = null;
                } else if (still.size() > 1 && still.size() < pending.size()) {
                    // the first scenario without a result is the likely culprit: run it last on the next attempt
                    still.add(still.remove(0));
                }
                pending = still;
            } catch (IOException e) {
                failure = "launch failed: " + e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "interrupted";
                break;
            }
            if (!pending.isEmpty()) {
                log.println("[testkit] " + failure);
                log.flush();
            }
        }
        List<Outcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(res[i] != null ? res[i] : new Outcome(null, failure == null ? "no result" : failure, dir, retries + 1));
        }
        return out;
    }

    private static Path emptyStdin(Path dir) throws IOException {
        Path f = dir.resolve("stdin.txt");
        Files.writeString(f, "", StandardCharsets.UTF_8);
        return f;
    }

    /** ": <exception> at <first frame>" from a crash report the game wrote, or "". */
    static String crashCause(Path dir) {
        Path cr = dir.resolve("crash-reports");
        if (!Files.isDirectory(cr)) {
            return "";
        }
        try (Stream<Path> s = Files.list(cr)) {
            for (Path f : s.sorted().toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("Description: ") && i + 2 < lines.size()) {
                        String ex = lines.get(i + 2).trim();
                        String at = i + 3 < lines.size() ? lines.get(i + 3).trim() : "";
                        return ": game crashed (" + lines.get(i).substring(13) + "): " + ex + (at.startsWith("at ") ? " " + at : "");
                    }
                }
            }
        } catch (IOException e) {
            // no cause
        }
        return "";
    }

    /** jstack of a hung game (diagnostics for the known 1.7.10 client hang), written to threads.txt. */
    private void threadDump(String version, Process p, Path dir) {
        try {
            Path java = Path.of(env.require(version + ".java"));
            Path jstack = java.resolveSibling(java.getFileName().toString().replace("java", "jstack"));
            if (Files.isRegularFile(jstack)) {
                Process j = new ProcessBuilder(jstack.toString(), String.valueOf(p.pid())).redirectErrorStream(true)
                        .redirectOutput(dir.resolve("threads.txt").toFile()).start();
                j.waitFor(30, TimeUnit.SECONDS);
                j.destroyForcibly();
            }
        } catch (IOException | RuntimeException e) {
            // diagnostics only
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> jvm(String version, Path planFile, Path raw) {
        List<String> c = new ArrayList<>();
        c.add(env.require(version + ".java"));
        c.add("-Xmx" + env.forVersion(version, "heap", "1500m"));
        c.add("-Dporter.plan=" + planFile.toAbsolutePath());
        c.add("-Dporter.out=" + raw.toAbsolutePath());
        c.add("-Dporter.wallSeconds=" + Math.max(30, timeoutSeconds - 30));
        c.add("-Dporter.screens=" + raw.toAbsolutePath().getParent().resolve("screens"));
        c.add("-Dfml.queryResult=confirm");
        return c;
    }

    // ------------------------------------------------------------------ dedicated servers

    List<String> serverCommand(String version, Path dir, Path planFile, Path raw) throws IOException {
        Path server = Path.of(env.require(version + ".server"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        int port = 25600 + (int) (ProcessHandle.current().pid() % 300);
        List<String> c = jvm(version, planFile, raw);
        switch (version) {
            case "forge-1.7.10" -> {
                Files.writeString(dir.resolve("server.properties"), String.join("\n",
                        "level-type=FLAT", "generator-settings=2;0;1;", "online-mode=false", "server-port=" + port,
                        "generate-structures=false", "spawn-animals=false", "spawn-monsters=false", "spawn-npcs=false",
                        "difficulty=0", "snooper-enabled=false", "level-name=world", "max-build-height=256", "") , StandardCharsets.UTF_8);
                c.add("-jar");
                c.add(single(server, "forge-1.7.10-", "-universal.jar").toString());
                c.add("nogui");
            }
            case "forge-1.19.2" -> {
                Files.writeString(dir.resolve("server.properties"), String.join("\n",
                        "level-type=minecraft\\:flat",
                        "generator-settings={\"layers\"\\:[{\"block\"\\:\"minecraft\\:air\",\"height\"\\:1}],\"biome\"\\:\"minecraft\\:the_void\",\"structure_overrides\"\\:[]}",
                        "online-mode=false", "server-port=" + port, "generate-structures=false", "spawn-animals=false",
                        "spawn-monsters=false", "spawn-npcs=false", "difficulty=peaceful", "max-tick-time=-1",
                        "sync-chunk-writes=false", "level-name=world", "") , StandardCharsets.UTF_8);
                boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                Path forgeLibs = server.resolve("libraries/net/minecraftforge/forge");
                Path argsFile;
                try (Stream<Path> s = Files.walk(forgeLibs, 2)) {
                    argsFile = s.filter(f -> f.getFileName().toString().equals(windows ? "win_args.txt" : "unix_args.txt"))
                            .findFirst().orElseThrow(() -> new IOException("no " + (windows ? "win" : "unix") + "_args.txt under " + forgeLibs));
                }
                String lib = server.resolve("libraries").toAbsolutePath().toString().replace('\\', '/');
                String args = Files.readString(argsFile, StandardCharsets.UTF_8)
                        .replace("libraries/", lib + "/")
                        .replace("=libraries\n", "=" + lib + "\n").replace("=libraries\r\n", "=" + lib + "\r\n")
                        .replace("=libraries ", "=" + lib + " ");
                Path abs = dir.resolve("forge-args.txt");
                Files.writeString(abs, args, StandardCharsets.UTF_8);
                c.add("@" + abs.toAbsolutePath());
                c.add("nogui");
            }
            default -> throw new IllegalArgumentException("no server launcher for " + version);
        }
        return c;
    }

    // ------------------------------------------------------------------ clients

    List<String> clientCommand(String version, Path dir, Path planFile, Path raw) throws IOException {
        if (version.equals("forge-1.19.2")) {
            return clientCommand1192(version, dir, planFile, raw);
        }
        if (!version.equals("forge-1.7.10")) {
            throw new IllegalArgumentException("client runs are implemented for forge-1.7.10 and forge-1.19.2 (not " + version + ")");
        }
        Path versionJson = Path.of(env.require(version + ".client.versionJson"));
        Path libs = Path.of(env.require(version + ".client.libraries"));
        Path clientJar = Path.of(env.require(version + ".client.jar"));
        Path natives = Path.of(env.require(version + ".client.natives"));
        Path assets = Path.of(env.require(version + ".client.assets"));
        JsonNode vj = Json.json().readTree(Files.readString(versionJson, StandardCharsets.UTF_8));
        List<String> cp = new ArrayList<>();
        for (JsonNode l : vj.path("libraries")) {
            if (l.has("natives")) {
                continue;
            }
            String[] n = l.path("name").asString().split(":");
            Path jar = libs.resolve(n[0].replace('.', '/')).resolve(n[1]).resolve(n[2]).resolve(n[1] + "-" + n[2] + ".jar");
            if (!Files.isRegularFile(jar)) {
                throw new IOException("client library missing: " + jar);
            }
            cp.add(jar.toString());
        }
        cp.add(clientJar.toString());
        Files.writeString(dir.resolve("options.txt"), String.join("\n",
                "pauseOnLostFocus:false", "renderDistance:2", "guiScale:" + env.forVersion(version, "client.guiScale", "1"), "fancyGraphics:false", "lang:en_US",
                "soundCategory_master:0.0", "soundCategory_music:0.0", "snooperEnabled:false", "") , StandardCharsets.UTF_8);
        List<String> c = jvm(version, planFile, raw);
        c.add("-Djava.library.path=" + natives);
        c.add("-Dporter.window=" + env.forVersion(version, "client.window", "offscreen"));
        c.add("-Dfml.noGrab=true"); // never capture the user's mouse (FML switch)
        c.add("-cp");
        c.add(String.join(java.io.File.pathSeparator, cp));
        c.add(vj.path("mainClass").asString("net.minecraft.launchwrapper.Launch"));
        c.addAll(List.of("--username", "porter", "--version", "1.7.10", "--gameDir", dir.toAbsolutePath().toString(),
                "--assetsDir", assets.toString(), "--assetIndex", env.forVersion(version, "client.assetIndex", "1.7.10"),
                "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userProperties", "{}",
                "--userType", "legacy", "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker",
                "--width", "854", "--height", "480"));
        return c;
    }

    /**
     * Forge 1.19.2 client from a launcher-style install (testkit/mod-1.19.2/setup-client.sh): libraries of the Forge
     * version json and the vanilla json it inherits from (Forge first, same group:artifact[:classifier] once), the
     * Forge JVM/game arguments with the launcher placeholders filled, an off-screen window and GUI scale 1.
     */
    List<String> clientCommand1192(String version, Path dir, Path planFile, Path raw) throws IOException {
        Path cdir = Path.of(env.require(version + ".client.dir"));
        String vid = env.forVersion(version, "client.versionId", "1.19.2-forge-43.5.2");
        Path libs = cdir.resolve("libraries");
        JsonNode fj = Json.json().readTree(Files.readString(cdir.resolve("versions").resolve(vid).resolve(vid + ".json"), StandardCharsets.UTF_8));
        String parent = fj.path("inheritsFrom").asString("1.19.2");
        JsonNode vj = Json.json().readTree(Files.readString(cdir.resolve("versions").resolve(parent).resolve(parent + ".json"), StandardCharsets.UTF_8));
        java.util.LinkedHashMap<String, String> cp = new java.util.LinkedHashMap<>();
        for (JsonNode j : List.of(fj, vj)) {
            for (JsonNode l : j.path("libraries")) {
                if (!rulesAllow(l.path("rules"))) {
                    continue;
                }
                String[] n = l.path("name").asString().split(":");
                String key = n[0] + ":" + n[1] + (n.length > 3 ? ":" + n[3] : "");
                String rel = l.path("downloads").path("artifact").path("path").asString(null);
                if (rel == null) {
                    rel = n[0].replace('.', '/') + "/" + n[1] + "/" + n[2] + "/" + n[1] + "-" + n[2] + (n.length > 3 ? "-" + n[3] : "") + ".jar";
                }
                Path jar = libs.resolve(rel);
                if (Files.isRegularFile(jar)) {
                    cp.putIfAbsent(key, jar.toString());
                }
            }
        }
        Files.writeString(dir.resolve("options.txt"), String.join("\n",
                "pauseOnLostFocus:false", "renderDistance:2", "simulationDistance:5", "guiScale:" + env.forVersion(version, "client.guiScale", "1"),
                "graphicsMode:0", "lang:en_us", "soundCategory_master:0.0", "soundCategory_music:0.0", "tutorialStep:none",
                "skipMultiplayerWarning:true", "joinedFirstServer:true", "fullscreen:false", "") , StandardCharsets.UTF_8);
        Map<String, String> vars = Map.of("${library_directory}", libs.toString(), "${classpath_separator}", java.io.File.pathSeparator,
                "${version_name}", vid);
        List<String> c = jvm(version, planFile, raw);
        c.add("-Dporter.window=" + env.forVersion(version, "client.window", "offscreen"));
        for (JsonNode a : fj.path("arguments").path("jvm")) {
            if (a.isString()) {
                String v = a.asString();
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    v = v.replace(e.getKey(), e.getValue());
                }
                c.add(v);
            }
        }
        c.add("-cp");
        c.add(String.join(java.io.File.pathSeparator, cp.values()));
        c.add(fj.path("mainClass").asString());
        Path assets = Path.of(env.forVersion(version, "client.assets", cdir.resolve("assets").toString()));
        Files.createDirectories(assets);
        c.addAll(List.of("--username", "porter", "--version", vid, "--gameDir", dir.toAbsolutePath().toString(),
                "--assetsDir", assets.toString(), "--assetIndex", env.forVersion(version, "client.assetIndex", "1.19"),
                "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userType", "legacy",
                "--versionType", "release", "--width", "854", "--height", "480"));
        for (JsonNode a : fj.path("arguments").path("game")) {
            if (a.isString()) {
                c.add(a.asString());
            }
        }
        return c;
    }

    /** Launcher library rules: allowed unless the last matching rule disallows the current OS. */
    static boolean rulesAllow(JsonNode rules) {
        if (!rules.isArray() || rules.isEmpty()) {
            return true;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String name = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        boolean allow = false;
        for (JsonNode r : rules) {
            String on = r.path("os").path("name").asString(null);
            if (on == null || on.equals(name)) {
                allow = r.path("action").asString().equals("allow");
            }
        }
        return allow;
    }

    private static Path single(Path dir, String prefix, String suffix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().startsWith(prefix) && f.getFileName().toString().endsWith(suffix))
                    .findFirst().orElseThrow(() -> new IOException("no " + prefix + "*" + suffix + " in " + dir));
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }
}
