package dev.porter.port;

import dev.porter.port.config.PortConfig;
import dev.porter.port.run.Download;
import dev.porter.port.run.Proc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The step lists of the {@code setup} and {@code derive} stages. A step is one of
 * <pre>
 *   porter:   [args...]                       a porter command (child JVM, queued)
 *   command:  [exe, args...]  cwd, env, queue  an external command (queued unless queue: false)
 *   download: {url, sha256, to}                a pinned download (skipped when the file is there with that sha256)
 *   unzip:    {zip, to, prefix, classes, stripFirst}  extract (part of) a zip/jar/tar.gz
 *   copy:     {from, to}                       a file, a directory overlaid onto another, or a glob's matches into a directory
 *   delete:   [paths]                          files or directories removed (e.g. a stale project)
 *   composite: {base, overlay, to}            an image: the base with the overlay's non-transparent pixels (an edit
 *                                             of a texture the user's own jar provides, shipped as the changed pixels only)
 * </pre>
 * plus {@code name} and {@code creates: [paths]}: when every path of {@code creates} exists the step is skipped
 * (one-time setup: downloads, decompiles, toolchains). Relative paths resolve against the config directory,
 * {@code cwd} defaults to it; {@code ./gradlew} runs the directory's {@code gradlew.bat} on Windows.
 */
final class Steps {
    private final PortConfig cfg;
    private final Path work;
    private final Map<String, String> childEnv;
    private final java.util.function.Consumer<String> detail;

    Steps(PortConfig cfg, Path work, Map<String, String> childEnv, java.util.function.Consumer<String> detail) {
        this.cfg = cfg;
        this.work = work;
        this.childEnv = childEnv;
        this.detail = detail;
    }

    /** Runs the steps of a stage section; returns per-step status for the stage summary. */
    boolean run(String stage, JsonNode section, Map<String, Object> summary) {
        Map<String, Object> res = new LinkedHashMap<>();
        int i = 0;
        boolean ok = true;
        for (JsonNode s : section.path("steps")) {
            i++;
            String name = s.path("name").asString(stage + "-" + i);
            List<Path> creates = cfg.paths(s.path("creates"));
            if (!creates.isEmpty() && creates.stream().allMatch(Files::exists)) {
                res.put(name, "cached");
                continue;
            }
            long t0 = System.nanoTime();
            try {
                String r = one(stage, name, s);
                res.put(name, r + " " + Math.round((System.nanoTime() - t0) / 1e9) + "s");
                if (r.startsWith("failed")) {
                    ok = false;
                    break;
                }
            } catch (RuntimeException e) {
                res.put(name, "failed: " + e.getMessage());
                ok = false;
                break;
            }
            for (Path c : creates) {
                if (!Files.exists(c)) {
                    res.put(name, "failed: did not create " + c);
                    ok = false;
                }
            }
            if (!ok) {
                break;
            }
        }
        summary.put("steps", res);
        return ok;
    }

    private String one(String stage, String name, JsonNode s) {
        Path log = work.resolve("logs/" + stage + "-" + name + ".log");
        if (s.has("porter")) {
            List<String> args = PortConfig.strings(s.get("porter"));
            List<String> queue = s.path("queue").asBoolean(true) ? cfg.queue() : List.of();
            List<String> cmd = Proc.porter(queue, PortConfig.strings(s.path("jvmArgs")).isEmpty() ? cfg.jvmArgs()
                    : PortConfig.strings(s.path("jvmArgs")), work.resolve("reports"), work.resolve("args/" + stage + "-" + name + ".args"), args);
            detail.accept("   porter " + String.join(" ", args.subList(0, Math.min(2, args.size()))) + " ... (log: " + log + ")");
            Proc.Result r = Proc.run(cmd, cfg.dir(), childEnv, log, null);
            return r.ok() ? "ok" : "failed (exit " + r.exitCode() + ", " + log + ")";
        }
        if (s.has("command")) {
            Path cwd = s.has("cwd") ? cfg.path(s.get("cwd").asString()) : cfg.dir();
            List<String> cmd = new ArrayList<>(s.path("queue").asBoolean(true) ? cfg.queue() : List.of());
            cmd.addAll(PortRunner.windowsWrapper(PortConfig.strings(s.get("command")), cwd));
            Map<String, String> env = new LinkedHashMap<>(childEnv);
            for (Map.Entry<String, JsonNode> e : s.path("env").properties()) {
                env.put(e.getKey(), e.getValue().asString());
            }
            detail.accept("   " + String.join(" ", PortConfig.strings(s.get("command"))) + " (log: " + log + ")");
            Proc.Result r = Proc.run(cmd, cwd, env, log, null);
            return r.ok() ? "ok" : "failed (exit " + r.exitCode() + ", " + log + ")";
        }
        if (s.has("download")) {
            JsonNode d = s.get("download");
            Download.fetch(d.path("url").asString(), d.path("sha256").asString(null), cfg.path(d.path("to").asString()));
            return "ok";
        }
        if (s.has("unzip")) {
            JsonNode z = s.get("unzip");
            Path to = cfg.path(z.path("to").asString());
            int n = Download.unzip(cfg.path(z.path("zip").asString()), z.path("prefix").asString(""), to,
                    !z.path("classes").asBoolean(true), z.path("stripFirst").asBoolean(false));
            return "ok " + n + " files";
        }
        if (s.has("copy")) {
            JsonNode c = s.get("copy");
            String from = c.path("from").asString();
            if (from.contains("*") || from.contains("?")) { // a glob: every match into the directory 'to'
                List<Path> hits = cfg.glob(from);
                if (hits.isEmpty()) {
                    throw new IllegalStateException("copy: nothing matches " + from);
                }
                for (Path h : hits) {
                    copy(h, cfg.path(c.path("to").asString()).resolve(h.getFileName().toString()));
                }
                return "ok " + hits.size() + " files";
            }
            return "ok " + copy(cfg.path(from), cfg.path(c.path("to").asString())) + " files";
        }
        if (s.has("composite")) {
            JsonNode c = s.get("composite");
            return "ok " + composite(cfg.path(c.path("base").asString()), cfg.path(c.path("overlay").asString()), cfg.path(c.path("to").asString()))
                    + " pixels";
        }
        if (s.has("delete")) {
            for (Path p : cfg.paths(s.get("delete"))) {
                deleteTree(p);
            }
            return "ok";
        }
        throw new IllegalArgumentException("step " + name + ": no porter/command/download/unzip/copy/composite/delete");
    }

    static int copy(Path from, Path to) {
        try {
            if (Files.isRegularFile(from)) {
                Files.createDirectories(to.toAbsolutePath().getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                return 1;
            }
            int n = 0;
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    Path dst = to.resolve(from.relativize(p).toString());
                    Files.createDirectories(dst.getParent());
                    Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException("copy " + from + " -> " + to + ": " + e.getMessage(), e);
        }
    }

    /** Pixels of {@code overlay} with alpha > 0 replace those of {@code base} (same size); the result is a PNG. */
    static int composite(Path base, Path overlay, Path to) {
        try {
            java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(base.toFile());
            java.awt.image.BufferedImage o = javax.imageio.ImageIO.read(overlay.toFile());
            if (b == null || o == null || b.getWidth() != o.getWidth() || b.getHeight() != o.getHeight()) {
                throw new IllegalStateException("composite: " + base + " and " + overlay + " must be images of the same size");
            }
            java.awt.image.BufferedImage r = new java.awt.image.BufferedImage(b.getWidth(), b.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int n = 0;
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) {
                    int op = o.getRGB(x, y);
                    if ((op >>> 24) != 0) {
                        r.setRGB(x, y, op);
                        n++;
                    } else {
                        r.setRGB(x, y, b.getRGB(x, y));
                    }
                }
            }
            Files.createDirectories(to.toAbsolutePath().getParent());
            javax.imageio.ImageIO.write(r, "png", to.toFile());
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
