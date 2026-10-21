package dev.porter.assets.model;

import java.util.Map;
import java.util.TreeMap;

/** Results of the 1.8 → 1.7.10 render conversion, shared by the model and texture-package converters. */
public final class RenderResult {
    /**
     * A block resolved to per-face textures.
     *
     * @param textures face (down, up, north, south, west, east, particle) → texture ref as in the converted tree
     * @param variants extra per-state face maps (state name → faces), may be empty
     */
    public record CubeBlock(String id, String shape, String modelRef, String file, double confidence,
            Map<String, String> textures, Map<String, Map<String, String>> variants, String note) {
    }

    /** Something that cannot be expressed as per-side icons. */
    public record Manual(String subject, String source, String reason, String obj) {
    }

    public final Map<String, CubeBlock> blocks = new TreeMap<>();
    public final Map<String, Manual> manual = new TreeMap<>();

    public static final String[] FACES = {"down", "up", "north", "south", "west", "east"};
}
