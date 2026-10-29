// porter:adapter (generic) - the CarbonConfig (Apache-2.0, carbonconfiglib.config) API subset ported code uses. The
// real library is Java 17 bytecode, 1.7.10 runs Java 8: entries keep their default values (no config file).
package dev.porter.shim.mc;

public class ConfigEntry<T> {
    protected final String key;
    protected T value;
    protected final T defaultValue;
    protected final String[] comment;

    protected ConfigEntry(String key, T value, String... comment) {
        this.key = key;
        this.value = value;
        this.defaultValue = value;
        this.comment = comment;
    }

    public T get() { return value; }

    public T getValue() { return value; }

    public T getDefault() { return defaultValue; }

    public void set(T v) { this.value = v; }

    public String getKey() { return key; }

    public ConfigEntry<T> setServerSynced() { return this; }

    public ConfigEntry<T> setRequiredReload() { return this; }

    public ConfigEntry<T> setHidden() { return this; }

    public static class IntValue extends ConfigEntry<Integer> {
        public IntValue(String key, int v, String... c) { super(key, v, c); }

        public int getInt() { return value; }

        public IntValue setMin(int min) { return this; }

        public IntValue setMax(int max) { return this; }

        public IntValue setRange(int min, int max) { return this; }
    }

    public static class DoubleValue extends ConfigEntry<Double> {
        public DoubleValue(String key, double v, String... c) { super(key, v, c); }

        public double getDouble() { return value; }

        public DoubleValue setMin(double min) { return this; }

        public DoubleValue setMax(double max) { return this; }

        public DoubleValue setRange(double min, double max) { return this; }
    }

    public static class FloatValue extends ConfigEntry<Float> {
        public FloatValue(String key, float v, String... c) { super(key, v, c); }

        public float getFloat() { return value; }
    }

    public static class LongValue extends ConfigEntry<Long> {
        public LongValue(String key, long v, String... c) { super(key, v, c); }

        public long getLong() { return value; }
    }

    public static class BoolValue extends ConfigEntry<Boolean> {
        public BoolValue(String key, boolean v, String... c) { super(key, v, c); }
    }

    public static class StringValue extends ConfigEntry<String> {
        public StringValue(String key, String v, String... c) { super(key, v, c); }
    }
}
