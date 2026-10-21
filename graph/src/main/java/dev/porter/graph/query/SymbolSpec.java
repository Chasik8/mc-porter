package dev.porter.graph.query;

import dev.porter.graph.model.Sym;
import dev.porter.graph.model.SymbolRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Type;

/**
 * A user-written symbol: {@code a.b.C} (class), {@code a.b.C#name} (all members of that name),
 * {@code a.b.C#name(Lx/Y;I)V} (JVM descriptor) or {@code a.b.C#name(BlockPos, int)} (parameter types by simple or
 * full name). The owner may use '.' or '/', may be a simple class name if unique, and names may be given in any
 * namespace of the version (mojmap, srg, mcp, obf).
 */
public record SymbolSpec(String owner, String member, String desc, List<String> paramTypes) {
    public static SymbolSpec parse(String s) {
        s = s.strip();
        int hash = s.indexOf('#');
        if (hash < 0) {
            return new SymbolSpec(s.replace('/', '.'), null, null, null);
        }
        String owner = s.substring(0, hash).replace('/', '.');
        String rest = s.substring(hash + 1);
        int paren = rest.indexOf('(');
        if (paren < 0) {
            return new SymbolSpec(owner, rest, null, null);
        }
        String name = rest.substring(0, paren);
        String sig = rest.substring(paren);
        int close = sig.indexOf(')');
        String params = sig.substring(1, close);
        if (close < sig.length() - 1) {
            return new SymbolSpec(owner, name, sig, null); // full JVM descriptor
        }
        List<String> types = new ArrayList<>();
        for (String t : params.split(",")) {
            if (!t.isBlank()) {
                types.add(t.strip());
            }
        }
        return new SymbolSpec(owner, name, null, types);
    }

    public List<SymbolRef> resolve(List<SymbolGraph.SymbolInfo> all) {
        List<SymbolGraph.SymbolInfo> owners = new ArrayList<>();
        for (SymbolGraph.SymbolInfo s : all) {
            if (s.ref().kind() == Sym.Kind.CLASS && matchesClass(s.names())) {
                owners.add(s);
            }
        }
        List<SymbolRef> out = new ArrayList<>();
        if (member == null) {
            owners.forEach(o -> out.add(o.ref()));
            return out;
        }
        for (SymbolGraph.SymbolInfo o : owners) {
            String ownerName = o.ref().name();
            for (SymbolGraph.SymbolInfo s : all) {
                SymbolRef r = s.ref();
                if (r.kind() == Sym.Kind.CLASS || !ownerName.equals(r.owner()) || !s.names().containsValue(member)) {
                    continue;
                }
                if (desc != null && !desc.equals(r.desc())) {
                    continue;
                }
                if (paramTypes != null && (r.kind() != Sym.Kind.METHOD || !paramsMatch(r.desc()))) {
                    continue;
                }
                out.add(r);
            }
        }
        return out;
    }

    private boolean matchesClass(Map<String, String> names) {
        for (String n : names.values()) {
            if (n.equals(owner)) {
                return true;
            }
        }
        if (!owner.contains(".")) {
            for (String n : names.values()) {
                String simple = n.substring(n.lastIndexOf('.') + 1);
                if (simple.equals(owner) || simple.substring(simple.lastIndexOf('$') + 1).equals(owner)) {
                    return n.contains(".");
                }
            }
        }
        return false;
    }

    private boolean paramsMatch(String d) {
        Type[] args = Type.getArgumentTypes(d);
        if (args.length != paramTypes.size()) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            String full = args[i].getClassName();
            String want = paramTypes.get(i).replace('/', '.');
            String simple = full.substring(full.lastIndexOf('.') + 1);
            if (!want.equals(full) && !want.equals(simple) && !want.equals(simple.substring(simple.lastIndexOf('$') + 1))) {
                return false;
            }
        }
        return true;
    }
}
