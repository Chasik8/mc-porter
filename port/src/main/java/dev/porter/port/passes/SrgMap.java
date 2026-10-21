package dev.porter.port.passes;

import dev.porter.port.Text;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SRG member id -> (declaring class, mojmap name) of one Minecraft version, read at run time from a tiny v2 file
 * with {@code mojmap} and {@code srg} namespaces (the symbol graph's {@code mappings/<version>.tiny}). The first
 * class that declares an id wins (overrides share the id of the method they override). Mapping data is never part
 * of porter: it is derived from the user's own Mojang/MCP downloads.
 */
public final class SrgMap {
    private static final Pattern SRG_ID = Pattern.compile("[mf]_\\d+_");
    private final Map<String, String[]> map;

    private SrgMap(Map<String, String[]> map) {
        this.map = map;
    }

    public static SrgMap empty() {
        return new SrgMap(Map.of());
    }

    public static SrgMap fromTiny(Path tiny) {
        List<String> lines = List.of(Text.read(tiny).split("\n", -1));
        String[] hdr = lines.get(0).split("\t", -1);
        if (hdr.length < 4 || !hdr[0].equals("tiny")) {
            throw new IllegalArgumentException("not a tiny v2 file: " + tiny);
        }
        List<String> ns = List.of(hdr).subList(3, hdr.length);
        int mi = ns.indexOf("mojmap");
        int si = ns.indexOf("srg");
        if (mi < 0 || si < 0) {
            throw new IllegalArgumentException("tiny file needs mojmap and srg namespaces: " + tiny + " has " + ns);
        }
        Map<String, String[]> m = new LinkedHashMap<>();
        String cls = null;
        for (String l : lines.subList(1, lines.size())) {
            String[] p = l.split("\t", -1);
            if (l.startsWith("c\t")) {
                cls = p[1 + mi].replace('/', '.');
            } else if ((l.startsWith("\tm\t") || l.startsWith("\tf\t")) && cls != null) {
                String srg = p[3 + si];
                if (SRG_ID.matcher(srg).matches()) {
                    m.putIfAbsent(srg, new String[] {cls, p[3 + mi]});
                }
            }
        }
        return new SrgMap(m);
    }

    /** {owner, name} or null. */
    public String[] get(String srg) {
        return map.get(srg);
    }

    public int size() {
        return map.size();
    }
}
