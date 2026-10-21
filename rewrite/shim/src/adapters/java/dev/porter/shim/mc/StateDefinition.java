// porter:adapter (generic) - 1.19.2 StateDefinition (all states precomputed, canonical instances) + 1.7.10 meta codec.
package dev.porter.shim.mc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;

public final class StateDefinition<O, S> {
    final Block owner;
    final List<Property<?>> properties;
    final BlockState[] states;
    final int[] radix; // product of the value counts of the properties after i
    /** properties encoded into the 1.7.10 metadata (chosen so that the product fits 16) */
    final List<Property<?>> metaProps = new ArrayList<>();

    StateDefinition(Block owner, List<Property<?>> props) {
        this.owner = owner;
        this.properties = Collections.unmodifiableList(new ArrayList<>(props));
        int n = 1;
        radix = new int[props.size()];
        for (int i = props.size() - 1; i >= 0; i--) {
            radix[i] = n;
            n *= props.get(i).count();
        }
        states = new BlockState[n];
        for (int idx = 0; idx < n; idx++) states[idx] = new BlockState(this, idx);
        int prod = 1;
        // facings first, then booleans, then the rest (same priority as porter generate's meta layout)
        List<Property<?>> order = new ArrayList<>();
        for (Property<?> p : props) if (p instanceof DirectionProperty) order.add(p);
        for (Property<?> p : props) if (p instanceof BooleanProperty) order.add(p);
        for (Property<?> p : props) if (!order.contains(p)) order.add(p);
        for (Property<?> p : order) {
            if (prod * p.count() <= 16) {
                metaProps.add(p);
                prod *= p.count();
            }
        }
    }

    public Block getOwner() { return owner; }

    public Collection<Property<?>> getProperties() { return properties; }

    public Property<?> getProperty(String name) {
        for (Property<?> p : properties) if (p.getName().equals(name)) return p;
        return null;
    }

    public List<BlockState> getPossibleStates() {
        List<BlockState> l = new ArrayList<>();
        Collections.addAll(l, states);
        return l;
    }

    public BlockState any() { return states[0]; }

    int index(Property<?> p) { return properties.indexOf(p); }

    int valueIndex(int stateIdx, int propIdx) { return (stateIdx / radix[propIdx]) % properties.get(propIdx).count(); }

    BlockState with(int stateIdx, int propIdx, int valueIdx) {
        int cur = valueIndex(stateIdx, propIdx);
        return states[stateIdx + (valueIdx - cur) * radix[propIdx]];
    }

    /** 1.7.10 metadata of a state (only the meta properties; see {@link #fullyEncoded()}). */
    public int toMeta(BlockState s) {
        int meta = 0;
        for (Property<?> p : metaProps) meta = meta * p.count() + valueIndex(s.index, index(p));
        return meta;
    }

    /** state from metadata; properties outside the meta keep the values of {@code base}. */
    public BlockState fromMeta(int meta, BlockState base) {
        BlockState s = base == null || base.def != this ? states[0] : base;
        for (int i = metaProps.size() - 1; i >= 0; i--) {
            Property<?> p = metaProps.get(i);
            int c = p.count();
            s = with(s.index, index(p), meta % c);
            meta /= c;
        }
        return s;
    }

    public boolean fullyEncoded() { return metaProps.size() == properties.size(); }

    public Map<String, Object> describe(BlockState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Property<?> p : properties) m.put(p.getName(), s.getValue((Property) p));
        return m;
    }

    public static final class Builder<O, S> {
        private final Block owner;
        private final List<Property<?>> props = new ArrayList<>();

        public Builder(Block owner) { this.owner = owner; }

        public Builder<O, S> add(Property<?>... ps) {
            for (Property<?> p : ps) if (!props.contains(p)) props.add(p);
            return this;
        }

        public StateDefinition<O, S> create() { return new StateDefinition<>(owner, props); }
    }
}
