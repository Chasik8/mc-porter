// porter:adapter (generic) - Forge 1.19.2 ForgeConfigSpec on a 1.7.10 Configuration file. The builder records
// values with their path (push/pop = categories), comment, default and range; load(file) reads them (writing
// defaults for missing keys). Values read before load return their default, like an unloaded 1.19.2 spec.
package dev.porter.shim.mc;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.commons.lang3.tuple.Pair;

public class ForgeConfigSpec {
    private final List<ConfigValue<?>> values;
    private boolean loaded;
    private Configuration config;

    ForgeConfigSpec(List<ConfigValue<?>> values) { this.values = values; }

    public boolean isLoaded() { return loaded; }

    public List<ConfigValue<?>> getValues() { return Collections.unmodifiableList(values); }

    /** reads every value from the file, writes defaults for missing keys */
    public synchronized void load(File file) {
        config = new Configuration(file);
        config.load();
        for (ConfigValue<?> v : values) v.read(config);
        if (config.hasChanged()) config.save();
        loaded = true;
    }

    public synchronized void save() {
        if (config == null) return;
        for (ConfigValue<?> v : values) v.write(config);
        config.save();
    }

    public static class Builder {
        private final Deque<String> path = new ArrayDeque<>();
        private final List<ConfigValue<?>> values = new ArrayList<>();
        private String[] comment = new String[0];

        public Builder comment(String... c) { this.comment = c; return this; }

        public Builder comment(String c) { this.comment = new String[] {c}; return this; }

        public Builder translation(String key) { return this; }

        public Builder worldRestart() { return this; }

        public Builder push(String name) {
            for (String p : name.split("\\.")) path.addLast(p);
            comment = new String[0];
            return this;
        }

        public Builder push(List<String> names) {
            for (String p : names) path.addLast(p);
            return this;
        }

        public Builder pop() { return pop(1); }

        public Builder pop(int n) {
            for (int i = 0; i < n; i++) path.removeLast();
            return this;
        }

        private <V extends ConfigValue<?>> V add(V v) {
            values.add(v);
            comment = new String[0];
            return v;
        }

        private List<String> path(String key) {
            List<String> p = new ArrayList<>(path);
            p.addAll(Arrays.asList(key.split("\\.")));
            return p;
        }

        public <T> ConfigValue<T> define(String key, T def) { return add(new ConfigValue<>(path(key), comment, def)); }

        public <T> ConfigValue<T> define(String key, T def, Predicate<Object> validator) { return define(key, def); }

        public <T> ConfigValue<T> define(String key, Supplier<T> def, Predicate<Object> validator) { return define(key, def.get()); }

        public BooleanValue define(String key, boolean def) { return add(new BooleanValue(path(key), comment, def)); }

        public IntValue defineInRange(String key, int def, int min, int max) { return add(new IntValue(path(key), comment, def, min, max)); }

        public LongValue defineInRange(String key, long def, long min, long max) { return add(new LongValue(path(key), comment, def, min, max)); }

        public DoubleValue defineInRange(String key, double def, double min, double max) { return add(new DoubleValue(path(key), comment, def, min, max)); }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String key, V def) { return add(new EnumValue<>(path(key), comment, def)); }

        public <T> ConfigValue<List<? extends T>> defineList(String key, List<? extends T> def, Predicate<Object> elementValidator) {
            return add(new ListValue<T>(path(key), comment, def, elementValidator));
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(List<String> p, Supplier<List<? extends T>> def, Predicate<Object> elementValidator) {
            return add(new ListValue<T>(new ArrayList<>(p), comment, def.get(), elementValidator));
        }

        public <T> ConfigValue<List<? extends T>> defineList(String key, Supplier<List<? extends T>> def, Predicate<Object> elementValidator) {
            return defineList(key, def.get(), elementValidator);
        }

        public <T> Pair<T, ForgeConfigSpec> configure(Function<Builder, T> consumer) {
            T o = consumer.apply(this);
            return Pair.of(o, build());
        }

        public ForgeConfigSpec build() { return new ForgeConfigSpec(new ArrayList<>(values)); }
    }

    public static class ConfigValue<T> implements Supplier<T> {
        final List<String> path;
        final String comment;
        final T def;
        T value;

        ConfigValue(List<String> path, String[] comment, T def) {
            this.path = path;
            this.comment = String.join("\n", comment);
            this.def = def;
            this.value = def;
        }

        public List<String> getPath() { return new ArrayList<>(path); }

        @Override
        public T get() { return value; }

        public T getDefault() { return def; }

        public void set(T v) { this.value = v; }

        public void save() {}

        String category() { return path.size() > 1 ? String.join(".", path.subList(0, path.size() - 1)).toLowerCase(java.util.Locale.ROOT) : Configuration.CATEGORY_GENERAL; }

        String key() { return path.get(path.size() - 1); }

        @SuppressWarnings("unchecked")
        void read(Configuration c) {
            Property p = c.get(category(), key(), String.valueOf(def), comment);
            if (def instanceof String) value = (T) p.getString();
        }

        void write(Configuration c) { c.get(category(), key(), String.valueOf(def), comment).set(String.valueOf(value)); }
    }

    public static class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(List<String> p, String[] c, boolean d) { super(p, c, d); }

        @Override
        void read(Configuration c) { value = c.get(category(), key(), def, comment).getBoolean(def); }

        @Override
        void write(Configuration c) { c.get(category(), key(), def, comment).set(value); }
    }

    public static class IntValue extends ConfigValue<Integer> {
        final int min, max;

        IntValue(List<String> p, String[] c, int d, int min, int max) {
            super(p, c, d);
            this.min = min;
            this.max = max;
        }

        @Override
        void read(Configuration c) {
            int v = c.get(category(), key(), def, comment, min, max).getInt(def);
            value = Math.max(min, Math.min(max, v));
        }

        @Override
        void write(Configuration c) { c.get(category(), key(), def, comment, min, max).set(value); }
    }

    public static class LongValue extends ConfigValue<Long> {
        final long min, max;

        LongValue(List<String> p, String[] c, long d, long min, long max) {
            super(p, c, d);
            this.min = min;
            this.max = max;
        }

        @Override
        void read(Configuration c) {
            String s = c.get(category(), key(), String.valueOf(def), comment + " [range: " + min + " ~ " + max + "]").getString();
            try {
                value = Math.max(min, Math.min(max, Long.parseLong(s.trim())));
            } catch (NumberFormatException e) {
                value = def;
            }
        }
    }

    public static class DoubleValue extends ConfigValue<Double> {
        final double min, max;

        DoubleValue(List<String> p, String[] c, double d, double min, double max) {
            super(p, c, d);
            this.min = min;
            this.max = max;
        }

        @Override
        void read(Configuration c) {
            double v = c.get(category(), key(), def, comment, min, max).getDouble(def);
            value = Math.max(min, Math.min(max, v));
        }

        @Override
        void write(Configuration c) { c.get(category(), key(), def, comment, min, max).set(value); }
    }

    public static class EnumValue<V extends Enum<V>> extends ConfigValue<V> {
        EnumValue(List<String> p, String[] c, V d) { super(p, c, d); }

        @Override
        void read(Configuration c) {
            String s = c.get(category(), key(), def.name(), comment).getString();
            try {
                value = Enum.valueOf(def.getDeclaringClass(), s.trim());
            } catch (IllegalArgumentException e) {
                value = def;
            }
        }

        @Override
        void write(Configuration c) { c.get(category(), key(), def.name(), comment).set(value.name()); }
    }

    /** list values are string lists in the 1.7.10 file; elements failing the validator are dropped */
    public static class ListValue<T> extends ConfigValue<List<? extends T>> {
        final Predicate<Object> validator;

        ListValue(List<String> p, String[] c, List<? extends T> d, Predicate<Object> validator) {
            super(p, c, d);
            this.validator = validator;
        }

        private String[] defaults() {
            String[] d = new String[def.size()];
            for (int i = 0; i < d.length; i++) d[i] = String.valueOf(def.get(i));
            return d;
        }

        @Override
        @SuppressWarnings("unchecked")
        void read(Configuration c) {
            String[] raw = c.get(category(), key(), defaults(), comment).getStringList();
            List<T> l = new ArrayList<>();
            for (String s : raw) if (validator == null || validator.test(s)) l.add((T) s);
            value = l;
        }

        @Override
        void write(Configuration c) {
            String[] v = new String[value.size()];
            for (int i = 0; i < v.length; i++) v[i] = String.valueOf(value.get(i));
            c.get(category(), key(), defaults(), comment).set(v);
        }
    }
}
