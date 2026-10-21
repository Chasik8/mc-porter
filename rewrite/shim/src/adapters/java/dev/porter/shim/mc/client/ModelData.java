// porter:adapter (generic) - Forge 1.19.2 client ModelData (block entity -> model side data), a typed map on 1.7.10.
package dev.porter.shim.mc.client;

import java.util.HashMap;
import java.util.Map;

public final class ModelData {
    public static final ModelData EMPTY = new ModelData(new HashMap<>());
    private final Map<ModelProperty<?>, Object> values;

    private ModelData(Map<ModelProperty<?>, Object> values) { this.values = values; }

    public static Builder builder() { return new Builder(); }

    @SuppressWarnings("unchecked")
    public <T> T get(ModelProperty<T> p) { return (T) values.get(p); }

    public boolean has(ModelProperty<?> p) { return values.containsKey(p); }

    public Builder derive() {
        Builder b = new Builder();
        b.values.putAll(values);
        return b;
    }

    public static final class Builder {
        final Map<ModelProperty<?>, Object> values = new HashMap<>();

        public <T> Builder with(ModelProperty<T> p, T value) {
            values.put(p, value);
            return this;
        }

        public ModelData build() { return new ModelData(new HashMap<>(values)); }
    }

    public static class ModelProperty<T> {
        public ModelProperty() {}
    }
}
