package dev.porter.core.cli;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable report every subcommand writes (section 3: "all subcommands write a JSON report to reports/").
 *
 * <p>Location: {@code <reports-dir>/runs/<command-path-with-dashes>.json}, overwritten by the next run of the same
 * command. {@code <reports-dir>} is {@code --reports-dir}, else the {@code porter.reports.dir} system property, else
 * the {@code PORTER_REPORTS_DIR} environment variable, else {@code reports} (relative to the working directory).
 *
 * <p>Not a frozen contract: shape is {command, status, exitCode, startedAt, finishedAt, args, details}; modules
 * put their own metrics under {@code details}.
 */
public final class RunReport {
    public static final String STATUS_OK = "ok";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_NOT_IMPLEMENTED = "not_implemented";

    private final String command;
    private final List<String> args;
    private final Instant startedAt = Instant.now();
    private final Map<String, Object> details = new LinkedHashMap<>();

    public RunReport(String commandPath, List<String> args) {
        this.command = commandPath;
        this.args = List.copyOf(args);
    }

    /** Adds a detail entry (any Jackson-serialisable value). */
    public RunReport put(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public static Path reportsDir() {
        String dir = System.getProperty("porter.reports.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("PORTER_REPORTS_DIR");
        }
        return Path.of(dir == null || dir.isBlank() ? "reports" : dir);
    }

    /** Writes the report and returns its path. */
    public Path write(String status, int exitCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("status", status);
        out.put("exitCode", exitCode);
        out.put("startedAt", startedAt.toString());
        out.put("finishedAt", Instant.now().toString());
        out.put("args", args);
        out.put("details", details);
        Path file = reportsDir().resolve("runs").resolve(command.replace(' ', '-') + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.json().writeValueAsString(out).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
