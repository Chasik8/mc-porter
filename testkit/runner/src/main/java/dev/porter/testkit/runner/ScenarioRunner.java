package dev.porter.testkit.runner;

import dev.porter.core.contract.Contract;
import dev.porter.core.contract.ContractIo;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.databind.node.ObjectNode;

/** Runs one scenario on one platform version and writes the trace: plan -> launch -> raw result -> trace. */
public final class ScenarioRunner {
    private final McLauncher launcher;
    private final IrIndex ir;
    private final Path screensDir;
    private final Path repoRoot;

    public ScenarioRunner(McLauncher launcher, IrIndex ir, Path screensDir, Path repoRoot) {
        this.launcher = launcher;
        this.ir = ir;
        this.screensDir = screensDir;
        this.repoRoot = repoRoot;
    }

    /** One side of a run: version, subject jar (may be null for vanilla scenarios), extra mods, id map. */
    public record Side(String version, Path subject, List<Path> extraMods, IdMap ids) {
    }

    public record Result(ObjectNode trace, Path traceFile, McLauncher.Outcome outcome) {
    }

    private static List<Path> mods(Side side) {
        List<Path> mods = new java.util.ArrayList<>();
        if (side.subject() != null) {
            mods.add(side.subject());
        }
        mods.addAll(side.extraMods());
        return mods;
    }

    /**
     * Server scenarios of one side in ONE Minecraft launch ({@link McLauncher#runBatch}); client scenarios, a lone
     * scenario or {@code batch == false} use {@link #run}. Results are in the order of {@code scenarios}.
     */
    public List<Result> runAll(List<Scenario> scenarios, Side side, List<Path> traceFiles, boolean batch) {
        Result[] res = new Result[scenarios.size()];
        List<Integer> server = new java.util.ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            if (batch && scenarios.get(i).side().equals("server")) {
                server.add(i);
            }
        }
        if (server.size() > 1) {
            List<Path> mods = mods(side);
            List<ObjectNode> plans = new java.util.ArrayList<>();
            for (int i : server) {
                plans.add(new PlanCompiler(scenarios.get(i), side.version(), side.ids(), ir).compile());
            }
            List<McLauncher.Outcome> outs = launcher.runBatch(plans, side.version(), mods);
            for (int k = 0; k < server.size(); k++) {
                int i = server.get(k);
                res[i] = finish(scenarios.get(i), side, mods, outs.get(k), traceFiles.get(i));
            }
        }
        for (int i = 0; i < res.length; i++) {
            if (res[i] == null) {
                res[i] = run(scenarios.get(i), side, traceFiles.get(i));
            }
        }
        return java.util.Arrays.asList(res);
    }

    public Result run(Scenario sc, Side side, Path traceFile) {
        ObjectNode plan = new PlanCompiler(sc, side.version(), side.ids(), ir).compile();
        List<Path> mods = mods(side);
        return finish(sc, side, mods, launcher.run(plan, side.version(), mods), traceFile);
    }

    private Result finish(Scenario sc, Side side, List<Path> mods, McLauncher.Outcome out, Path traceFile) {
        TraceBuilder tb = new TraceBuilder(sc, side.version(), side.ids(), ir).screens(screensDir, repoRoot);
        if (side.subject() != null) {
            tb.subject(subject(side.subject()));
        }
        ObjectNode trace = out.raw() == null
                ? tb.failedRun(out.failure() != null && out.failure().startsWith("timeout") ? "timeout" : "error", out.failure())
                : tb.build(out.raw());
        if (traceFile != null) {
            ContractIo.writeJson(traceFile, trace, Contract.TRACE);
            if (out.raw() != null && sc.side().equals("client")) {
                dev.porter.testkit.runner.gui.GuiCaptures.export(out.raw(), out.runDir(),
                        dev.porter.testkit.runner.gui.GuiCaptures.dirFor(traceFile), mods);
            }
        }
        return new Result(trace, traceFile, out);
    }

    /** trace.subject: mod id (mods.toml / mcmod.info), jar file name and sha256. */
    public static ObjectNode subject(Path jar) {
        ObjectNode s = Json.json().createObjectNode();
        String modId = modId(jar);
        if (modId != null) {
            s.put("modId", modId);
        }
        s.put("jar", jar.getFileName().toString());
        s.put("sha256", sha256(jar));
        return s;
    }

    static String modId(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry toml = z.getEntry("META-INF/mods.toml");
            if (toml != null) {
                Matcher m = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(read(z, toml));
                if (m.find()) {
                    return m.group(1);
                }
            }
            ZipEntry info = z.getEntry("mcmod.info");
            if (info != null) {
                Matcher m = Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]+)\"").matcher(read(z, info));
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    /** Platform version a mod jar was built for: mods.toml -> forge-1.19.2 family, mcmod.info -> forge-1.7.10. */
    public static String guessVersion(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            if (z.getEntry("META-INF/mods.toml") != null) {
                return "forge-1.19.2";
            }
            if (z.getEntry("mcmod.info") != null) {
                return "forge-1.7.10";
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    private static String read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sha256(Path f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(f)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
