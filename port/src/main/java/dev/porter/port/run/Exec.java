package dev.porter.port.run;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the command stored in a file (one argument per line) with inherited I/O and exits with its code. Used behind
 * a job-queue wrapper: the wrapper sees only {@code java ... Exec <file>}, so long command lines and {@code @argfile}
 * arguments never pass through the wrapper's shell (Cygwin/MSYS expands {@code @file} arguments of native callers).
 */
public final class Exec {
    private Exec() {
    }

    public static void main(String[] args) throws Exception {
        List<String> cmd = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).toList();
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        System.exit(p.waitFor());
    }
}
