package dev.porter.port.passes;

import dev.porter.port.Text;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * porter's generic adapter pack: Java 8 classes under {@code dev.porter.shim.mc} (and {@code .client}) that
 * implement the parts of the Forge 1.19.2 API a ported mod still calls, on top of 1.7.10 (sources:
 * {@code rewrite/shim/src/adapters/java}). The rules in {@code rules/packs/adapters/1.19.2__1.7.10/adapters-*.rule.yaml}
 * rewrite references to the 1.19.2 types into these adapters; the install stage copies the pack, relocated to the
 * port's shim package, into the target project.
 */
public final class Adapters {
    private static final String BASE = "/porter/adapters/";
    private static final String SHIM = "dev.porter.shim";

    private Adapters() {
    }

    /** Relative paths ({@code dev/porter/shim/mc/X.java}) of the bundled pack. */
    public static List<String> index() {
        try (InputStream in = Adapters.class.getResourceAsStream(BASE + "index.txt")) {
            if (in == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank()) {
                    out.add(l.strip());
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies the pack into {@code out}, relocated from {@code dev.porter.shim} to {@code shimPackage}; returns the count. */
    public static int write(Path out, String shimPackage) {
        int n = 0;
        String pkg = shimPackage == null || shimPackage.isEmpty() ? SHIM : shimPackage;
        for (String rel : index()) {
            String text;
            try (InputStream in = Adapters.class.getResourceAsStream(BASE + rel)) {
                if (in == null) {
                    throw new IllegalStateException("adapter source missing from the porter jar: " + rel);
                }
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String target = pkg.replace('.', '/') + rel.substring(SHIM.length());
            Text.write(out.resolve(target), text.replace(SHIM + ".", pkg + ".").replace("package " + SHIM + ";", "package " + pkg + ";"));
            n++;
        }
        return n;
    }
}
