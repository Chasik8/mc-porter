package dev.porter.graph.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All symbols of one Minecraft version (from its client jar) with their names in every loaded namespace. */
public final class VersionData {
    /** Provenance of one input (contract {@code versions[].sources[]}). */
    public record Source(String name, String version, String url, String sha256) {
    }

    private final String id;
    private final List<String> namespaces;
    private final int canonicalIndex;
    private final List<Source> sources;
    private final List<Sym> symbols = new ArrayList<>();
    private final Map<String, Sym> classesByObf = new HashMap<>();
    private final Map<String, Sym> classesByCanonical = new HashMap<>();

    public VersionData(String id, List<String> namespaces, String canonical, List<Source> sources) {
        this.id = id;
        this.namespaces = List.copyOf(namespaces);
        this.canonicalIndex = namespaces.indexOf(canonical);
        if (canonicalIndex < 0) {
            throw new IllegalArgumentException("canonical namespace " + canonical + " not in " + namespaces + " for " + id);
        }
        this.sources = List.copyOf(sources);
    }

    public String id() {
        return id;
    }

    public List<String> namespaces() {
        return namespaces;
    }

    public int ns(String namespace) {
        return namespaces.indexOf(namespace);
    }

    public String canonical() {
        return namespaces.get(canonicalIndex);
    }

    public int canonicalIndex() {
        return canonicalIndex;
    }

    public List<Source> sources() {
        return sources;
    }

    public void addClass(Sym c) {
        c.index = symbols.size();
        symbols.add(c);
        classesByObf.put(c.obfName, c);
    }

    public void addMember(Sym m) {
        m.index = symbols.size();
        symbols.add(m);
        m.owner.members.add(m);
    }

    /** Call after loading: builds the canonical-name index. */
    public void seal() {
        for (Sym s : symbols) {
            if (s.isClass()) {
                classesByCanonical.put(s.internalName(this), s);
            }
        }
    }

    public List<Sym> symbols() {
        return Collections.unmodifiableList(symbols);
    }

    public Sym classByObf(String obf) {
        return classesByObf.get(obf);
    }

    public Sym classByCanonical(String internalName) {
        return classesByCanonical.get(internalName);
    }

    public Iterable<Sym> classes() {
        return () -> symbols.stream().filter(Sym::isClass).iterator();
    }
}
