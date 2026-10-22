package dev.porter.port;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import dev.porter.port.run.Download;
import dev.porter.port.run.Proc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter decompile}: the user's own copy of a mod jar -> {@code <out>/java} (CFR) + {@code <out>/res} (every
 * non-class entry). The decompiler is pinned (CFR 0.152 from Maven Central, sha256-checked) and so should the Java
 * runtime that runs it: CFR resolves JDK types from its runtime, so the output depends on the Java version (a Java 8
 * runtime gives the same text on every 8u build). Same jar + same CFR + same Java major = byte-identical sources,
 * which is what lets port profiles ship patches against them instead of the sources themselves.
 */
@Command(name = "decompile", description = {"Decompile a jar with a pinned CFR (downloaded, sha256-checked) into <out>/java and copy its resources to <out>/res.",
        "Pin the runtime with --java (CFR output depends on the JDK class library; Java 8 is the reference)."})
public final class DecompileCommand implements PorterSubcommand, Callable<Integer> {
    static final String CFR_URL = "https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar";
    static final String CFR_SHA256 = "f686e8f3ded377d7bc87d216a90e9e9512df4156e75b06c655a16648ae8765b2";

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--jar", required = true, paramLabel = "<jar>", description = "The jar to decompile.")
    Path jar;

    @Option(names = "--sha256", paramLabel = "<hex>", description = "Expected sha256 of the jar (refuses other files).")
    String sha256;

    @Option(names = "--out", required = true, paramLabel = "<dir>", description = "Output: <dir>/java and <dir>/res (replaced).")
    Path out;

    @Option(names = "--java", paramLabel = "<java>", description = "Java executable that runs CFR (default: $JAVA8_HOME/bin/java, else this JVM's java).")
    Path javaPath;

    @Option(names = "--cfr", paramLabel = "<jar>", description = "CFR jar (default: CFR 0.152 downloaded into --tools).")
    Path cfr;

    @Option(names = "--tools", paramLabel = "<dir>", description = "Download directory of the decompiler (default $PORTER_CACHE/tools or ~/.cache/porter/tools).")
    Path tools;

    @Override
    public String path() {
        return "decompile";
    }

    static Path cacheDir() {
        String c = System.getenv("PORTER_CACHE");
        return c != null && !c.isBlank() ? Path.of(c) : Path.of(System.getProperty("user.home"), ".cache", "porter");
    }

    @Override
    public Integer call() throws Exception {
        RunReport report = new RunReport("decompile", spec.commandLine().getParseResult().originalArgs());
        try {
            Download.verify(jar, sha256, "decompile --jar");
            String jarSha = Download.sha256(jar);
            Path stamp = out.resolve(".porter-decompile");
            String key = "jar " + jarSha + "\ncfr " + (cfr == null ? CFR_SHA256 : Download.sha256(cfr)) + "\n";
            if (Files.exists(stamp) && Files.readString(stamp).equals(key)) {
                spec.commandLine().getOut().println("porter decompile: up to date " + out);
                report.put("cached", true).write(RunReport.STATUS_OK, ExitCodes.OK);
                return ExitCodes.OK;
            }
            Path cfrJar = cfr != null ? cfr : Download.fetch(CFR_URL, CFR_SHA256, (tools != null ? tools : cacheDir().resolve("tools")).resolve("cfr-0.152.jar"));
            Path javaExe = javaPath;
            if (javaExe == null) {
                String j8 = System.getenv("JAVA8_HOME");
                javaExe = j8 != null && !j8.isBlank() ? Path.of(j8, "bin", Proc.isWindows() ? "java.exe" : "java") : Path.of(Proc.javaExe());
            }
            deleteTree(out);
            Files.createDirectories(out);
            List<String> cmd = new ArrayList<>(List.of(javaExe.toString(), "-Xmx1500m", "-jar", cfrJar.toString(), jar.toAbsolutePath().toString(),
                    "--outputdir", out.resolve("java").toAbsolutePath().toString()));
            Proc.Result r = Proc.run(cmd, out, Map.of(), out.resolve("cfr.log"), null);
            if (!r.ok()) {
                throw new IllegalStateException("CFR exited " + r.exitCode() + ", see " + r.log());
            }
            Files.deleteIfExists(out.resolve("java/summary.txt")); // absolute paths: not reproducible
            int res = Download.unzip(jar, "", out.resolve("res"), true, false);
            long javaFiles = Text.javaFiles(out.resolve("java")).size();
            Files.writeString(stamp, key);
            spec.commandLine().getOut().println("porter decompile: " + javaFiles + " java files, " + res + " resources -> " + out);
            report.put("java", javaFiles).put("resources", res).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("porter decompile: " + e.getMessage());
            report.put("error", e.toString()).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
            return ExitCodes.FAILED;
        }
    }

    static void deleteTree(Path p) throws java.io.IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
