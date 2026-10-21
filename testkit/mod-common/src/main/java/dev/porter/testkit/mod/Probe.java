package dev.porter.testkit.mod;

/**
 * Parsed sample key {@code <probe>[:<target>][/<path>]} (shared by the 1.7.10 and 1.19.2 test mods, Java 8).
 *
 * <p>Targets: {@code [x,y,z]} optionally followed by {@code #n} (slot / tank index) and {@code @side}
 * (down, up, north, south, west, east); or a bare word ({@code player:inv}). Path: everything after the first
 * {@code /} behind the target, segments separated by {@code /}.
 */
public final class Probe {
    public final String key;
    public final String probe;
    public final String target;
    public final int[] pos;
    public final int index;
    public final String side;
    public final String[] path;

    private Probe(String key, String probe, String target, int[] pos, int index, String side, String[] path) {
        this.key = key;
        this.probe = probe;
        this.target = target;
        this.pos = pos;
        this.index = index;
        this.side = side;
        this.path = path;
    }

    public static Probe parse(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            return new Probe(key, key, null, null, -1, null, new String[0]);
        }
        String probe = key.substring(0, colon);
        String rest = key.substring(colon + 1);
        int[] pos = null;
        String target;
        String after;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("missing ] in " + key);
            }
            String[] p = rest.substring(1, close).split(",");
            if (p.length != 3) {
                throw new IllegalArgumentException("position needs 3 coordinates in " + key);
            }
            pos = new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
            target = rest.substring(0, close + 1);
            after = rest.substring(close + 1);
        } else {
            int end = 0;
            while (end < rest.length() && "/#@".indexOf(rest.charAt(end)) < 0) {
                end++;
            }
            target = rest.substring(0, end);
            after = rest.substring(end);
        }
        int index = -1;
        String side = null;
        String path = "";
        int slash = after.indexOf('/');
        String mods = slash < 0 ? after : after.substring(0, slash);
        if (slash >= 0) {
            path = after.substring(slash + 1);
        }
        int i = 0;
        while (i < mods.length()) {
            char c = mods.charAt(i);
            int j = i + 1;
            while (j < mods.length() && mods.charAt(j) != '#' && mods.charAt(j) != '@') {
                j++;
            }
            String v = mods.substring(i + 1, j);
            if (c == '#') {
                index = Integer.parseInt(v);
            } else if (c == '@') {
                side = v;
            } else {
                throw new IllegalArgumentException("unexpected '" + c + "' in " + key);
            }
            i = j;
        }
        return new Probe(key, probe, target, pos, index, side, path.isEmpty() ? new String[0] : path.split("/"));
    }

    public String requirePos() {
        if (pos == null) {
            throw new IllegalArgumentException(probe + " probe needs a [x,y,z] target: " + key);
        }
        return target;
    }
}
