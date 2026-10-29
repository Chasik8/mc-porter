package dev.porter.port.upfront.model;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * api-inventory.json ({@code contracts/upfront-inventory.schema.json}): the platform symbols a mod references.
 * {@link #write} sorts symbols by key and the origin summary by name, so two runs produce identical bytes.
 */
public record ApiInventory(int schemaVersion, String from, String scope, Sources sources, List<ApiSymbol> symbols,
                           Summary summary) {

    public static final int SCHEMA_VERSION = 1;

    public record Sources(String jar, String srcRoot, List<String> classes) {
    }

    public record Summary(int types, int members, Map<String, Integer> byOrigin) {
    }

    public static ApiInventory read(Path file) throws IOException {
        return Json.json().readValue(Files.readString(file, StandardCharsets.UTF_8), ApiInventory.class);
    }

    public void write(Path file) throws IOException {
        List<ApiSymbol> sorted = symbols.stream()
                .sorted(Comparator.comparing((ApiSymbol s) -> s.key().toString())).toList();
        Map<String, Integer> origins = summary.byOrigin() == null ? null : new TreeMap<>(summary.byOrigin());
        ApiInventory out = new ApiInventory(schemaVersion, from, scope, sources, sorted,
                new Summary(summary.types(), summary.members(), origins));
        Files.writeString(file, Json.json().writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
    }
}
