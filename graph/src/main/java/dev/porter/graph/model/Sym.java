package dev.porter.graph.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A symbol (class, method or field) of one Minecraft version, keyed by its obfuscated identity in the client jar,
 * with names in every namespace of that version. Mutable only while the version is being loaded.
 */
public final class Sym {
    public enum Kind {
        CLASS("class"), METHOD("method"), FIELD("field");

        public final String json;

        Kind(String json) {
            this.json = json;
        }

        public static Kind of(String s) {
            for (Kind k : values()) {
                if (k.json.equals(s)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("unknown symbol kind " + s);
        }
    }

    /** A method parameter name by JVM local index. */
    public record Param(int index, String name, String source) {
    }

    public final Kind kind;
    public final int access;
    /** Class: obf internal name. Member: obf simple name. */
    public final String obfName;
    /** Member only: obf descriptor. */
    public final String obfDesc;
    /** Member only: declaring class. */
    public final Sym owner;
    /** Names per {@link VersionData#namespaces()} (class: internal name with '/', member: simple name). */
    public final String[] names;
    /** Canonical descriptor (members only). */
    public String desc;
    public List<Param> params = List.of();
    /** Class only. */
    public String superObf;
    public List<String> interfacesObf = List.of();
    public final List<Sym> members = new ArrayList<>();
    /** Position in {@link VersionData#symbols()}. */
    public int index = -1;

    public Sym(Kind kind, int access, String obfName, String obfDesc, Sym owner, int namespaceCount) {
        this.kind = kind;
        this.access = access;
        this.obfName = obfName;
        this.obfDesc = obfDesc;
        this.owner = owner;
        this.names = new String[namespaceCount];
    }

    public boolean isClass() {
        return kind == Kind.CLASS;
    }

    /** Canonical name: class FQN in binary form (dots), member simple name. */
    public String canonicalName(VersionData v) {
        String n = names[v.canonicalIndex()];
        return isClass() ? n.replace('/', '.') : n;
    }

    /** Canonical internal name of a class (slashes). */
    public String internalName(VersionData v) {
        return names[v.canonicalIndex()];
    }

    public SymbolRef ref(VersionData v) {
        if (isClass()) {
            return new SymbolRef(v.id(), kind, null, canonicalName(v), null);
        }
        return new SymbolRef(v.id(), kind, owner.canonicalName(v), names[v.canonicalIndex()], desc);
    }

    @Override
    public String toString() {
        return isClass() ? "class " + obfName : kind.json + " " + owner.obfName + "." + obfName + obfDesc;
    }
}
