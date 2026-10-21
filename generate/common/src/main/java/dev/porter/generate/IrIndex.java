package dev.porter.generate;

import dev.porter.core.ir.IrDocument;
import dev.porter.core.ir.IrIo;
import dev.porter.core.ir.IrNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * All IR nodes of a run, indexed by kind and id. Files are read in sorted path order; within a kind nodes are
 * iterated in id order, so generators see the same sequence whatever the file layout.
 */
public final class IrIndex {
    private final Map<Class<?>, SortedMap<String, IrNode>> byKind = new LinkedHashMap<>();
    private final List<IrNode.Unknown> unknown = new ArrayList<>();
    private final List<String> duplicates = new ArrayList<>();
    private final TreeSet<String> sourceVersions = new TreeSet<>();
    private final List<String> files = new ArrayList<>();

    /** Reads every {@code *.ir.json} / {@code *.ir.yaml} below the given files or directories (validated). */
    public static IrIndex load(List<Path> inputs) {
        IrIndex idx = new IrIndex();
        List<Path> all = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> walk = Files.walk(in)) {
                    walk.filter(Files::isRegularFile).filter(IrIndex::isIrFile).sorted().forEach(all::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                all.add(in);
            }
        }
        for (Path p : all) {
            idx.files.add(p.toString().replace('\\', '/'));
            idx.add(IrIo.read(p));
        }
        return idx;
    }

    static boolean isIrFile(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".ir.json") || n.endsWith(".ir.yaml") || n.endsWith(".ir.yml");
    }

    public static IrIndex of(List<IrDocument> docs) {
        IrIndex idx = new IrIndex();
        docs.forEach(idx::add);
        return idx;
    }

    public void add(IrDocument doc) {
        if (doc.source() != null && doc.source().version() != null) {
            sourceVersions.add(doc.source().version());
        }
        for (IrNode n : doc.nodes()) {
            if (n instanceof IrNode.Unknown u) {
                unknown.add(u);
                continue;
            }
            SortedMap<String, IrNode> m = byKind.computeIfAbsent(n.getClass(), k -> new TreeMap<>());
            if (m.containsKey(n.id())) {
                duplicates.add(n.kind() + " " + n.id());
                continue;
            }
            m.put(n.id(), n);
        }
    }

    /**
     * Fills {@code textures}, {@code model} and the {@code assets:*} extensions of blocks from a {@code porter assets}
     * render result ({@code meta/blocks.textures.ir.json}); existing values win.
     *
     * @return ids of the blocks that received data
     */
    public List<String> mergeBlockTextures(IrDocument rendered) {
        List<String> filled = new ArrayList<>();
        SortedMap<String, IrNode> blocks = byKind.get(IrNode.Block.class);
        if (blocks == null) {
            return filled;
        }
        for (IrNode n : rendered.nodes()) {
            if (!(n instanceof IrNode.Block t) || !(blocks.get(t.id()) instanceof IrNode.Block b)) {
                continue;
            }
            Map<String, JsonNode> ext = new TreeMap<>(t.extensions() == null ? Map.of() : t.extensions());
            if (b.extensions() != null) {
                ext.putAll(b.extensions());
            }
            if (ext.equals(b.extensions() == null ? Map.of() : b.extensions()) && b.textures() != null) {
                continue;
            }
            blocks.put(b.id(), new IrNode.Block(b.id(), b.translationKey(), b.logicClass(), b.material(), b.hardness(),
                    b.resistance(), b.lightLevel(), b.soundType(), b.requiresTool(), b.harvest(), b.creativeTab(),
                    b.stateProperties(), b.textures() != null ? b.textures() : t.textures(),
                    b.model() != null ? b.model() : t.model(), b.blockEntity(), b.item(), b.lootTable(), b.provenance(),
                    b.confidence(), ext));
            filled.add(b.id());
        }
        return filled;
    }

    @SuppressWarnings("unchecked")
    public <T extends IrNode> Collection<T> all(Class<T> type) {
        SortedMap<String, IrNode> m = byKind.get(type);
        return m == null ? List.of() : (Collection<T>) m.values();
    }

    public <T extends IrNode> Optional<T> get(Class<T> type, String id) {
        if (id == null) {
            return Optional.empty();
        }
        SortedMap<String, IrNode> m = byKind.get(type);
        return m == null ? Optional.empty() : Optional.ofNullable(type.cast(m.get(id)));
    }

    public Optional<IrNode.Mod> mod() {
        return all(IrNode.Mod.class).stream().findFirst();
    }

    public List<IrNode.Unknown> unknown() {
        return unknown;
    }

    public List<String> duplicates() {
        return duplicates;
    }

    public TreeSet<String> sourceVersions() {
        return sourceVersions;
    }

    public List<String> files() {
        return files;
    }

    /** Namespaces of all non-mod node ids (the mod's own content), sorted. */
    public TreeSet<String> contentNamespaces() {
        TreeSet<String> s = new TreeSet<>();
        byKind.forEach((k, m) -> {
            if (k != IrNode.Mod.class && k != IrNode.Lang.class && k != IrNode.Tag.class && k != IrNode.Recipe.class) {
                m.keySet().forEach(id -> s.add(Names.namespace(id)));
            }
        });
        return s;
    }

    /** Number of nodes per kind (for reports). */
    public Map<String, Integer> counts() {
        Map<String, Integer> c = new TreeMap<>();
        byKind.forEach((k, m) -> m.values().stream().findFirst().ifPresent(n -> c.put(n.kind(), m.size())));
        if (!unknown.isEmpty()) {
            c.put("unknown", unknown.size());
        }
        return c;
    }
}
