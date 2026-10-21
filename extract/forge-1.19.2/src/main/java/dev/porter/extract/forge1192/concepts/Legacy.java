package dev.porter.extract.forge1192.concepts;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * {@code extensions["porter:legacyName"]}: the 1.7.10 unlocalized name the generators and the lang converter use.
 * The source version has no such name, so it is derived by one fixed convention (NS_CAMEL:
 * {@code examplemod:steam_turbine} → {@code examplemod.steamTurbine}), marked by {@code porter:legacyNameSource}.
 */
public final class Legacy {
    public static final String KEY = "porter:legacyName";
    public static final String SOURCE = "porter:legacyNameSource";

    private Legacy() {
    }

    public static String name(String id) {
        int c = id.indexOf(':');
        String ns = id.substring(0, c);
        StringBuilder sb = new StringBuilder(ns).append('.');
        boolean up = false;
        for (char ch : id.substring(c + 1).toCharArray()) {
            if (ch == '_' || ch == '/' || ch == '-' || ch == '.') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    public static void put(Map<String, JsonNode> ext, String id) {
        ext.put(KEY, Vals.F.stringNode(name(id)));
        ext.put(SOURCE, Vals.F.stringNode("derived:ns-camel"));
    }
}
