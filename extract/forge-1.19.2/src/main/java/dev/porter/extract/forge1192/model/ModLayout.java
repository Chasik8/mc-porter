package dev.porter.extract.forge1192.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the Java sources and resources of a mod live. Recognised layouts (first match wins):
 * a Gradle project ({@code src/main/java} + {@code src/main/resources}), a decompile ({@code java/} + {@code res/}
 * or {@code resources/}), or a bare directory that is itself both.
 */
public record ModLayout(Path root, List<Path> javaRoots, List<Path> resourceRoots) {

    public static ModLayout detect(Path root) {
        root = root.toAbsolutePath().normalize();
        List<Path> java = new ArrayList<>();
        List<Path> res = new ArrayList<>();
        if (Files.isDirectory(root.resolve("src/main/java"))) {
            java.add(root.resolve("src/main/java"));
            addIfDir(res, root.resolve("src/main/resources"));
            addIfDir(res, root.resolve("src/generated/resources"));
        } else if (Files.isDirectory(root.resolve("java"))) {
            java.add(root.resolve("java"));
            addIfDir(res, root.resolve("res"));
            addIfDir(res, root.resolve("resources"));
        } else {
            java.add(root);
            res.add(root);
        }
        return new ModLayout(root, List.copyOf(java), List.copyOf(res));
    }

    private static void addIfDir(List<Path> list, Path p) {
        if (Files.isDirectory(p)) {
            list.add(p);
        }
    }

    /** Path relative to the mod root with forward slashes (provenance.file). */
    public String relative(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            return root.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString().replace('\\', '/');
    }

    /** All files below the resource roots whose relative path (from the resource root) matches the glob-ish filter. */
    public List<Path> resourceFiles(java.util.function.Predicate<String> relPathFilter) {
        List<Path> out = new ArrayList<>();
        for (Path r : resourceRoots) {
            try (Stream<Path> s = Files.walk(r)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> relPathFilter.test(r.relativize(p).toString().replace('\\', '/')))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Path of a resource file relative to its resource root. */
    public String resourcePath(Path file) {
        for (Path r : resourceRoots) {
            if (file.startsWith(r)) {
                return r.relativize(file).toString().replace('\\', '/');
            }
        }
        return file.getFileName().toString();
    }
}
