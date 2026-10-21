package dev.porter.generate.forge1710;

import dev.porter.core.ir.IrNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the block-state properties of one IR block are stored on 1.7.10: in the 4 metadata bits (mixed radix, at most
 * 16 combinations) and, when they do not fit, in fields of a TileEntity ("spill").
 *
 * <p>Meta selection: properties are taken in priority order (direction, boolean, integer, enum; declaration order
 * within a type) while the product of value counts stays ≤ 16; every other property spills. Each property has an
 * index range {@code 0..values-1} in its IR value order.
 */
final class MetaLayout {
    /** One property. {@code multiplier} is its radix weight in meta (meta props) or 0 (spilled). */
    record Prop(String name, String type, List<String> values, int defaultIndex, boolean inMeta, int multiplier) {
        String field() {
            return "state" + dev.porter.generate.Names.pascal(name);
        }
    }

    final List<Prop> props;
    final int metaStates;
    final int totalStates;

    private MetaLayout(List<Prop> props, int metaStates, int totalStates) {
        this.props = props;
        this.metaStates = metaStates;
        this.totalStates = totalStates;
    }

    static final List<String> BOOL = List.of("false", "true");
    static final List<String> DIRS = List.of("down", "up", "north", "south", "west", "east");

    static List<String> values(IrNode.Block.StateProperty p) {
        if (p.values() != null && !p.values().isEmpty()) {
            return p.values();
        }
        return switch (p.type()) {
            case "boolean" -> BOOL;
            case "direction" -> DIRS;
            default -> List.of();
        };
    }

    static MetaLayout of(IrNode.Block b) {
        List<IrNode.Block.StateProperty> in = b.stateProperties() == null ? List.of() : b.stateProperties();
        List<IrNode.Block.StateProperty> order = new ArrayList<>(in);
        order.sort(Comparator.comparingInt(p -> switch (p.type()) {
            case "direction" -> 0;
            case "boolean" -> 1;
            case "integer" -> 2;
            default -> 3;
        }));
        Map<String, Boolean> inMeta = new LinkedHashMap<>();
        int meta = 1;
        long total = 1;
        for (IrNode.Block.StateProperty p : order) {
            int n = Math.max(1, values(p).size());
            total *= n;
            if (meta * n <= 16) {
                meta *= n;
                inMeta.put(p.name(), true);
            } else {
                inMeta.put(p.name(), false);
            }
        }
        List<Prop> props = new ArrayList<>();
        int mult = 1;
        for (IrNode.Block.StateProperty p : in) {
            List<String> v = values(p);
            int def = p.defaultValue() == null ? 0 : Math.max(0, v.indexOf(p.defaultValue()));
            boolean m = inMeta.get(p.name());
            props.add(new Prop(p.name(), p.type(), v, def, m, m ? mult : 0));
            if (m) {
                mult *= Math.max(1, v.size());
            }
        }
        return new MetaLayout(props, meta, (int) Math.min(Integer.MAX_VALUE, total));
    }

    boolean spills() {
        return props.stream().anyMatch(p -> !p.inMeta());
    }

    List<Prop> metaProps() {
        return props.stream().filter(Prop::inMeta).toList();
    }

    List<Prop> spilled() {
        return props.stream().filter(p -> !p.inMeta()).toList();
    }

    Prop prop(String name) {
        return props.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** Meta of the default state. */
    int defaultMeta() {
        int m = 0;
        for (Prop p : metaProps()) {
            m += p.defaultIndex() * p.multiplier();
        }
        return m;
    }

    /** Meta for a partial state (unnamed meta props take their default), or -1 if a value is unknown/spilled. */
    int metaFor(Map<String, String> state) {
        int m = 0;
        for (Prop p : metaProps()) {
            String v = state.get(p.name());
            int i = v == null ? p.defaultIndex() : p.values().indexOf(v);
            if (i < 0) {
                return -1;
            }
            m += i * p.multiplier();
        }
        for (String k : state.keySet()) {
            Prop p = prop(k);
            if (p == null || !p.inMeta()) {
                return -1;
            }
        }
        return m;
    }

    /** Value index of a meta property in a meta value. */
    int index(Prop p, int meta) {
        return meta / p.multiplier() % p.values().size();
    }

    /** Manifest entry describing the layout. */
    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metaStates", metaStates);
        m.put("totalStates", totalStates);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Prop p : props) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", p.name());
            e.put("values", p.values());
            e.put("storage", p.inMeta() ? "meta" : "tile");
            if (p.inMeta()) {
                e.put("multiplier", p.multiplier());
            } else {
                e.put("field", p.field());
            }
            ps.add(e);
        }
        m.put("properties", ps);
        return m;
    }
}
