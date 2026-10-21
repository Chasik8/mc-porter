package dev.porter.assets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * In-memory view of an {@code assets/} directory: {@code <namespace>/<path>} → bytes, always sorted (determinism).
 * Conversion steps rewrite this tree; nothing touches the disk until {@link #writeTo(Path)}.
 */
public final class AssetTree {
    private final NavigableMap<String, byte[]> files = new TreeMap<>();

    /** Reads every regular file below {@code assetsDir} (the directory that contains the namespaces). */
    public static AssetTree read(Path assetsDir) {
        AssetTree tree = new AssetTree();
        try (Stream<Path> walk = Files.walk(assetsDir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                tree.put(assetsDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tree;
    }

    /** Writes the tree below {@code assetsDir}; LF/UTF-8 is the producers' job. */
    public void writeTo(Path assetsDir) {
        try {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path target = assetsDir.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] get(String path) {
        return files.get(path);
    }

    public String text(String path) {
        byte[] b = files.get(path);
        if (b == null) {
            return null;
        }
        String s = new String(b, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    public boolean has(String path) {
        return files.containsKey(path);
    }

    public void put(String path, byte[] data) {
        files.put(path, data);
    }

    public void putText(String path, String text) {
        files.put(path, text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
    }

    public byte[] remove(String path) {
        return files.remove(path);
    }

    /** Moves a file; returns false if the source does not exist. Refuses to overwrite a different file. */
    public boolean move(String from, String to) {
        byte[] b = files.get(from);
        if (b == null) {
            return false;
        }
        byte[] existing = files.get(to);
        if (existing != null && !java.util.Arrays.equals(existing, b)) {
            throw new IllegalStateException("move " + from + " -> " + to + ": target exists with different content");
        }
        files.remove(from);
        files.put(to, b);
        return true;
    }

    /** Sorted snapshot of all paths. */
    public List<String> paths() {
        return new ArrayList<>(files.keySet());
    }

    /** Sorted snapshot of paths starting with {@code prefix}. */
    public List<String> paths(String prefix) {
        return new ArrayList<>(files.subMap(prefix, true, prefix + Character.MAX_VALUE, true).keySet());
    }

    /** Namespaces (first path segment), sorted. */
    public List<String> namespaces() {
        return files.keySet().stream().map(p -> p.substring(0, p.indexOf('/') < 0 ? p.length() : p.indexOf('/')))
                .distinct().toList();
    }

    public int size() {
        return files.size();
    }

    /** Namespace of a tree path. */
    public static String namespace(String path) {
        int i = path.indexOf('/');
        return i < 0 ? path : path.substring(0, i);
    }

    /** Path inside the namespace. */
    public static String local(String path) {
        int i = path.indexOf('/');
        return i < 0 ? "" : path.substring(i + 1);
    }
}
