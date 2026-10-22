// porter:adapter (generic) - CarbonConfig ConfigSection subset (see ConfigEntry).
package ic2cextras.ported.shim.lib;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigSection {
    private final String name;
    private final Map<String, ConfigEntry<?>> entries = new LinkedHashMap<>();
    private final Map<String, ConfigSection> children = new LinkedHashMap<>();

    public ConfigSection(String name) { this.name = name; }

    public String getName() { return name; }

    public ConfigSection addSubSection(String name) { return children.computeIfAbsent(name, ConfigSection::new); }

    public ConfigSection getSubSection(String name) { return children.get(name); }

    private <E extends ConfigEntry<?>> E add(E e) {
        entries.put(e.getKey(), e);
        return e;
    }

    public ConfigEntry.IntValue addInt(String key, int v, String... comment) { return add(new ConfigEntry.IntValue(key, v, comment)); }

    public ConfigEntry.DoubleValue addDouble(String key, double v, String... comment) { return add(new ConfigEntry.DoubleValue(key, v, comment)); }

    public ConfigEntry.FloatValue addFloat(String key, float v, String... comment) { return add(new ConfigEntry.FloatValue(key, v, comment)); }

    public ConfigEntry.LongValue addLong(String key, long v, String... comment) { return add(new ConfigEntry.LongValue(key, v, comment)); }

    public ConfigEntry.BoolValue addBool(String key, boolean v, String... comment) { return add(new ConfigEntry.BoolValue(key, v, comment)); }

    public ConfigEntry.StringValue addString(String key, String v, String... comment) { return add(new ConfigEntry.StringValue(key, v, comment)); }
}
