package dev.porter.graph.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Contract {@code symbolRef}: a symbol in one version named in that version's canonical namespace. */
public record SymbolRef(String version, Sym.Kind kind, String owner, String name, String desc) {
    public static final Comparator<SymbolRef> ORDER = Comparator.comparing(SymbolRef::version)
            .thenComparing(r -> r.owner() == null ? "" : r.owner())
            .thenComparing(r -> r.kind().ordinal())
            .thenComparing(SymbolRef::name)
            .thenComparing(r -> r.desc() == null ? "" : r.desc());

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("kind", kind.json);
        if (owner != null) {
            m.put("owner", owner);
        }
        m.put("name", name);
        if (desc != null) {
            m.put("desc", desc);
        }
        return m;
    }

    /** Human-readable form: {@code a.b.C}, {@code a.b.C#name(desc)} or {@code a.b.C#field}. */
    public String display() {
        if (kind == Sym.Kind.CLASS) {
            return name;
        }
        return owner + "#" + name + (kind == Sym.Kind.METHOD && desc != null ? desc : "");
    }
}
