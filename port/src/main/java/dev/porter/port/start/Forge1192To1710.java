package dev.porter.port.start;

import dev.porter.port.run.Proc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The one-time setup of the Forge 1.19.2 -> Forge 1.7.10 pair, fully automatic and pinned by checksums: the symbol
 * graph, the Forge 1.19.2 client install, the mojmap-named compile jar, the supertype table, the mapping data the
 * extractor reads and the 1.7.10 project template. Mirrors what {@code ports/torchmaster/README.md} used to list by hand
 * ({@code testkit/mod-1.19.2/setup-client.sh} is the script form of the compile-jar part).
 */
public final class Forge1192To1710 {
    static final String FORGE = "1.19.2-43.5.2";
    static final String FORGE_INSTALLER_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + FORGE + "/forge-" + FORGE + "-installer.jar";
    static final String FORGE_INSTALLER_SHA256 = "cee6837e368a579e10b6c9677a29e789b5a7e424ea6143996e1c8794d0b01cf1";
    static final String MC_JSON_URL = "https://piston-meta.mojang.com/v1/packages/ed548106acf3ac7e8205a6ee8fd2710facfa164f/1.19.2.json";
    static final String MC_JSON_SHA1 = "ed548106acf3ac7e8205a6ee8fd2710facfa164f";
    /** GTNH ExampleMod1.7.10 (the RetroFuturaGradle project template), pinned commit. */
    static final String TEMPLATE_URL = "https://github.com/GTNewHorizons/ExampleMod1.7.10/archive/6453dec5f5f534e604887d3505ddf7125889c6f9.zip";
    static final String TEMPLATE_SHA256 = "52d785ad073f0182c6688d3a891a0e84d818ac19fe1ff08312a2958b8debeefb";

    private final Home home;
    private final PrintStream out;

    public Forge1192To1710(Home home) {
        this.home = home;
        this.out = home.out();
    }

    public static boolean supports(String from, String to) {
        return "forge-1.19.2".equals(from) && "forge-1.7.10".equals(to);
    }

    private Path cache(String p) {
        return home.cache().resolve(p);
    }

    private Map<String, String> env() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("PORTER_CACHE", home.cache().toString());
        e.put("GRADLE_USER_HOME", home.gradle().toString());
        e.put("CLASSPATH", System.getProperty("java.class.path"));
        return e;
    }

    private void run(String log, List<String> cmd, Path cwd, Map<String, String> env, String failure) {
        Proc.Result r = Proc.run(cmd, cwd, env, home.logs().resolve(log + ".log"), null);
        if (!r.ok()) {
            throw new Fail(failure + "\n   Details: " + r.log());
        }
    }

    /** All one-time steps in order. */
    public void ensure() {
        gradleHome();
        home.step("graph-1", "Symbol graph of Minecraft 1.19.2 ... 1.7.10 (downloads the Minecraft jars and mappings, matches them)", "10 min",
                this::graph);
        home.step("mojang-1.19.2", "Minecraft 1.19.2 version data", "1 min", this::mojang);
        home.step("forge-client-" + FORGE, "Forge " + FORGE + " (installer, libraries)", "5 min", this::forgeClient);
        home.step("compile-jar-" + FORGE, "Forge 1.19.2 classes under their readable (Mojang) names", "3 min", this::compileJar);
        home.step("mc-data-" + FORGE, "Mapping data for the extractor", "1 min", this::mcData);
        home.step("typefacts-" + FORGE, "Supertype table of Minecraft and Forge", "1 min", this::typeFacts);
    }

    // ------------------------------------------------------------------------------------------- gradle home

    private void gradleHome() {
        Path props = home.gradle().resolve("gradle.properties");
        if (Files.exists(props)) {
            return;
        }
        try {
            Files.createDirectories(props.getParent());
            Files.writeString(props, String.join("\n",
                    "# written by porter run: builds run in the background, one at a time, without a daemon",
                    "org.gradle.daemon=false",
                    "org.gradle.priority=low",
                    "org.gradle.parallel=false",
                    "org.gradle.workers.max=2",
                    "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8",
                    "org.gradle.java.installations.auto-download=true", ""));
        } catch (IOException e) {
            throw new Fail("cannot write " + props, e);
        }
    }

    // ------------------------------------------------------------------------------------------------ steps

    private void graph() {
        List<String> cmd = Proc.porter(List.of(), List.of("-Xmx3g", "-Xss16m"), home.logs().resolve("reports"),
                home.logs().resolve("graph.args"), List.of("graph", "build", "--versions", "1.19.2,1.18.2,1.16.5,1.12.2,1.7.10"));
        run("graph", cmd, home.root(), env(), "Could not build the symbol graph (a download may have failed; run again to retry).");
    }

    private Path graphDl(String dir) {
        return cache("graph/dl/" + dir);
    }

    private void mojang() {
        Path d = cache("mojang-1.19.2");
        Net.fetch(MC_JSON_URL, d.resolve("1.19.2.json"), "SHA-1", MC_JSON_SHA1, out);
        copy(graphDl("mc-1.19.2-client/client.jar"), d.resolve("client.jar"));
    }

    private void forgeClient() {
        Path inst = Net.fetch(FORGE_INSTALLER_URL, cache("forge-1.19.2-installer/forge-" + FORGE + "-installer.jar"), "SHA-256",
                FORGE_INSTALLER_SHA256, out);
        Path dir = cache("forge-1.19.2-client");
        Path v = dir.resolve("versions/1.19.2");
        try {
            Files.createDirectories(v);
            Files.writeString(dir.resolve("launcher_profiles.json"), "{\"profiles\":{}}\n");
        } catch (IOException e) {
            throw new Fail("cannot prepare " + dir, e);
        }
        copy(cache("mojang-1.19.2/1.19.2.json"), v.resolve("1.19.2.json"));
        copy(cache("mojang-1.19.2/client.jar"), v.resolve("1.19.2.jar"));
        Path fetch = tool("FetchClientLibs.java");
        run("forge-client-libs", List.of(Proc.javaExe(), fetch.toString(), v.resolve("1.19.2.json").toString(), dir.resolve("libraries").toString()),
                dir, env(), "Could not download the Minecraft libraries.");
        run("forge-client-install", List.of(Proc.javaExe(), "-Xmx1g", "-jar", inst.toString(), "--installClient", dir.toString()), dir, env(),
                "The Forge installer failed (needs internet access).");
    }

    private void compileJar() {
        Path libs = cache("forge-1.19.2-client/libraries");
        Path srg = libs.resolve("net/minecraft/client/1.19.2-20220805.130853/client-1.19.2-20220805.130853-srg.jar");
        Path patched = libs.resolve("net/minecraftforge/forge/" + FORGE + "/forge-" + FORGE + "-client.jar");
        Path art = libs.resolve("net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar");
        Path asm = libs.resolve("org/ow2/asm/asm/9.7.1/asm-9.7.1.jar");
        for (Path p : List.of(srg, patched, art, asm)) {
            if (!Files.isRegularFile(p)) {
                throw new Fail("The Forge install is incomplete, missing " + p + ". Delete " + home.root().resolve("stamps")
                        + " and " + cache("forge-1.19.2-client") + " and run again.");
            }
        }
        Path joined = cache("mcpconfig-1.19.2/x/config/joined.tsrg");
        extractEntry(graphDl("mcp_config-1.19.2/mcp_config-1.19.2-20220805.130853.zip"), "config/joined.tsrg", joined);
        Path clientTxt = graphDl("mc-1.19.2-client-mappings/client.txt");
        Path dir = cache("forge-1.19.2-compile");
        Path merged = dir.resolve("mc-forge-1.19.2-client-srg.jar");
        Path tsrg = dir.resolve("moj2srg-client.tsrg");
        Path result = dir.resolve("mc-forge-1.19.2-client-mojmap.jar");
        merge(srg, patched, merged);
        run("compile-jar-names", List.of(Proc.javaExe(), "-Xmx1g", "-cp", asm.toString(), tool("MakeMojToSrg.java").toString(),
                clientTxt.toString(), joined.toString(), tsrg.toString(), merged.toString()), dir, env(), "Could not build the name mapping.");
        run("compile-jar-rename", List.of(Proc.javaExe(), "-Xmx1200m", "-jar", art.toString(), "--input", merged.toString(), "--output",
                result.toString(), "--names", tsrg.toString(), "--reverse", "--threads", "2", "--log",
                dir.resolve("art-reverse-client.log").toString()), dir, env(), "Could not rename the Forge classes.");
        try {
            Files.deleteIfExists(merged);
        } catch (IOException e) {
            // leftover only
        }
    }

    private void mcData() {
        Path d = cache("mappings-" + "1.19.2");
        copy(graphDl("mc-1.19.2-client/client.jar"), d.resolve("client_jar/client.jar"));
        copy(graphDl("mc-1.19.2-client-mappings/client.txt"), d.resolve("client_mappings/client.txt"));
        copy(cache("mcpconfig-1.19.2/x/config/joined.tsrg"), d.resolve("mcp_config/config/joined.tsrg"));
        copy(cache("forge-1.19.2-client/libraries/net/minecraftforge/forge/" + FORGE + "/forge-" + FORGE + "-universal.jar"),
                d.resolve("forge/forge-" + FORGE + "-universal.jar"));
    }

    private void typeFacts() {
        int n = TypeTable.write(cache("typefacts-1.19.2/forge-" + FORGE + ".tsv"),
                List.of(cache("forge-1.19.2-compile/mc-forge-1.19.2-client-mojmap.jar"),
                        cache("forge-1.19.2-client/libraries/net/minecraftforge/forge/" + FORGE + "/forge-" + FORGE + "-universal.jar")),
                List.of("net.minecraft.", "net.minecraftforge.", "com.mojang."));
        out.println("   " + n + " classes");
    }

    // ------------------------------------------------------------------------------------- 1.7.10 project

    /**
     * The target project: the pinned GTNH template with the port's {@code project/} directory copied over it.
     * Does nothing when the project already exists.
     */
    public void project(Path portDir, Path project) {
        Path marker = project.resolve(".porter-project");
        if (Files.exists(marker)) {
            return;
        }
        out.println("== The 1.7.10 project (GTNH template + the port's files)");
        Path zip = Net.fetch(TEMPLATE_URL, home.downloads().resolve("ExampleMod1.7.10-6453dec.zip"), "SHA-256", TEMPLATE_SHA256, out);
        Path tmp = project.resolveSibling("template-unpack");
        Inputs.deleteTree(tmp);
        Inputs.deleteTree(project);
        Archives.unzip(zip, tmp);
        copyTree(Archives.stripTop(tmp), project);
        Inputs.deleteTree(tmp);
        Inputs.deleteTree(project.resolve("src")); // the template's example mod must not end up in the port's jar
        if (Files.isDirectory(portDir.resolve("project"))) {
            copyTree(portDir.resolve("project"), project);
        }
        Archives.makeExecutable(project.resolve("gradlew"));
        try {
            Files.writeString(marker, "template " + TEMPLATE_SHA256 + "\n");
        } catch (IOException e) {
            throw new Fail("cannot write " + marker, e);
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Path tool(String name) {
        Path p = cache("tools/" + name);
        try (InputStream in = Forge1192To1710.class.getResourceAsStream("tools/" + name)) {
            if (in == null) {
                throw new Fail("porter is missing its bundled tool " + name);
            }
            Files.createDirectories(p.getParent());
            Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new Fail("cannot write " + p, e);
        }
        return p;
    }

    static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new Fail("cannot copy " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new Fail("cannot copy " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    private static void extractEntry(Path zip, String entry, Path to) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) {
                throw new Fail(zip.getFileName() + " has no " + entry);
            }
            Files.createDirectories(to.getParent());
            try (InputStream in = z.getInputStream(e)) {
                Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new Fail("cannot read " + zip + ": " + e.getMessage(), e);
        }
    }

    /** The classes of {@code base} overlaid with the classes of {@code patch} (patch wins), without META-INF. */
    private static void merge(Path base, Path patch, Path out) {
        Set<String> seen = new HashSet<>();
        List<Path> order = new ArrayList<>(List.of(patch, base));
        try {
            Files.createDirectories(out.getParent());
        } catch (IOException e) {
            throw new Fail("cannot create " + out.getParent(), e);
        }
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zo = new ZipOutputStream(os)) {
            for (Path jar : order) {
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    for (var en = z.entries(); en.hasMoreElements();) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || e.getName().startsWith("META-INF/") || !seen.add(e.getName())) {
                            continue;
                        }
                        zo.putNextEntry(new ZipEntry(e.getName()));
                        try (InputStream in = z.getInputStream(e)) {
                            in.transferTo(zo);
                        }
                        zo.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            throw new Fail("cannot merge jars into " + out + ": " + e.getMessage(), e);
        }
    }
}
