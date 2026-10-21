package dev.porter.loop.run;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles a source tree in-process with the JDK's javac (no Gradle). Used for tests and for projects without a
 * Gradle build; output is javac's plain text format, the same one Gradle prints.
 */
public final class JavacCompileRunner implements CompileRunner {
    private final List<Path> sourceRoots;
    private final List<Path> classpath;
    private final Path outDir;
    private final List<String> options;

    /**
     * @param sourceRoots directories with .java files
     * @param classpath   jars/directories
     * @param outDir      class output directory
     * @param options     extra javac options (e.g. --release 8)
     */
    public JavacCompileRunner(List<Path> sourceRoots, List<Path> classpath, Path outDir, List<String> options) {
        this.sourceRoots = List.copyOf(sourceRoots);
        this.classpath = List.copyOf(classpath);
        this.outDir = outDir;
        this.options = List.copyOf(options);
    }

    @Override
    public Result compile() throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system Java compiler (run porter on a JDK)");
        }
        List<Path> files = new ArrayList<>();
        for (Path root : sourceRoots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> w = Files.walk(root)) {
                    w.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }
        }
        Files.createDirectories(outDir);
        List<String> opts = new ArrayList<>(List.of("-d", outDir.toString(), "-proc:none", "-Xmaxerrs", "100000", "-encoding", "UTF-8",
                "-Xlint:none", "-nowarn"));
        if (!classpath.isEmpty()) {
            opts.add("-classpath");
            opts.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        opts.addAll(options);
        StringWriter out = new StringWriter();
        boolean ok;
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            ok = files.isEmpty() || javac.getTask(new PrintWriter(out), fm, null, opts, null, units).call();
        }
        return new Result(ok ? 0 : 1, out.toString(), "javac " + String.join(" ", opts) + " <" + files.size() + " files>");
    }
}
