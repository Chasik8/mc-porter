package dev.porter.testkit.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.json.Json;
import dev.porter.testkit.runner.expr.Expr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ExprTest {
    static final class Ctx implements Expr.Context {
        final Map<String, List<Expr.TimedValue>> s = new LinkedHashMap<>();
        long now;

        Ctx put(long tick, String key, String json) {
            s.computeIfAbsent(key, k -> new ArrayList<>()).add(new Expr.TimedValue(tick, Json.json().readTree(json)));
            now = Math.max(now, tick);
            return this;
        }

        @Override
        public JsonNode latest(String key) {
            JsonNode last = null;
            for (Expr.TimedValue tv : s.getOrDefault(key, List.of())) {
                if (tv.tick() <= now) {
                    last = tv.value();
                }
            }
            return last;
        }

        @Override
        public List<Expr.TimedValue> series(String key) {
            return s.getOrDefault(key, List.of());
        }

        @Override
        public String resolve(String k) {
            if (s.containsKey(k)) {
                return k;
            }
            List<String> m = s.keySet().stream().filter(x -> x.startsWith(k + ":")).toList();
            if (m.size() == 1) {
                return m.get(0);
            }
            return k;
        }

        @Override
        public long now() {
            return now;
        }
    }

    boolean t(String expr, Ctx c) {
        return Expr.test(Expr.parse(expr), c);
    }

    @Test
    void keysWithAccessorsAndComparisons() {
        Ctx c = new Ctx().put(220, "item:[0,64,0]#2", "{\"item\":\"minecraft:iron_ingot\",\"count\":1}")
                .put(220, "nbt:[0,-60,0]/CookTime", "0");
        assertTrue(t("item:[0,64,0]#2.count == 1", c));
        assertTrue(t("item:[0,64,0]#2.item == 'minecraft:iron_ingot'", c));
        assertTrue(t("nbt:[0,-60,0]/CookTime <= 0 && !(nbt:[0,-60,0]/CookTime > 0)", c));
        assertFalse(t("item:[0,64,0]#2.count != 1 || item:[0,64,0]#2.count >= 2", c));
        assertEquals(List.of("item:[0,64,0]#2", "nbt:[0,-60,0]/CookTime"),
                Expr.directKeys(Expr.parse("item:[0,64,0]#2.count == 1 && nbt:[0,-60,0]/CookTime == 0")));
    }

    @Test
    void arithmeticNullsAndBareProbeNames() {
        Ctx c = new Ctx().put(10, "energy_out:[1,65,4]", "512").put(10, "item:[0,0,0]#0", "null");
        assertTrue(t("energy_out > 0", c));
        assertTrue(t("energy_out * 4 == 2048", c));
        assertTrue(t("energy_out - 12 == 500", c));
        assertTrue(t("-energy_out < 0", c));
        assertTrue(t("item:[0,0,0]#0 == null", c));
        assertFalse(t("item:[0,0,0]#0.count > 0", c));
        assertTrue(t("tick == 10", c));
    }

    @Test
    void seriesFunctions() {
        Ctx c = new Ctx();
        for (int i = 0; i <= 10; i++) {
            c.put(i * 10L, "field:[0,0,0]/heat", Integer.toString(i * 10));
        }
        assertTrue(t("avg(field:[0,0,0]/heat, 0, 100) == 50", c));
        assertTrue(t("min(field:[0,0,0]/heat) == 0 && max(field:[0,0,0]/heat) == 100", c));
        assertTrue(t("delta(field:[0,0,0]/heat, 20, 60) == 40", c));
        assertTrue(t("at(field:[0,0,0]/heat, 30) == 30", c));
        assertTrue(t("count(field:[0,0,0]/heat) == 11", c));
        assertTrue(t("abs(0 - 5) == 5 && round(2.6) == 3", c));
        assertEquals(List.of(), Expr.directKeys(Expr.parse("avg(field:[0,0,0]/heat, 0, 100) == 50")));
    }

    @Test
    void errors() {
        Ctx c = new Ctx().put(0, "a:[0,0,0]", "1");
        assertThrows(Expr.ExprException.class, () -> t("a:[0,0,0] +", c));
        assertThrows(Expr.ExprException.class, () -> t("a:[0,0,0]", c));
        assertThrows(Expr.ExprException.class, () -> t("nope(1)", c));
        assertThrows(Expr.ExprException.class, () -> t("'x' > 1", c));
    }
}
