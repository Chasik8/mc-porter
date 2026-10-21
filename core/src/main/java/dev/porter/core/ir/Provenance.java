package dev.porter.core.ir;

/**
 * Where an IR fact came from.
 *
 * @param file      path relative to the mod source root, or {@code runtime:<what>} for runtime exports
 * @param line      1-based line, 0 or null = unknown
 * @param version   platform version, e.g. {@code forge-1.19.2}
 * @param via       source | bytecode | resource | runtime-export | manual (null = source)
 * @param extractor extractor id, e.g. {@code block/deferred-register}
 */
public record Provenance(String file, Integer line, String version, String via, String extractor) {
    public static Provenance of(String file, int line, String version) {
        return new Provenance(file, line, version, null, null);
    }
}
