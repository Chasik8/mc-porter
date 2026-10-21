package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StageLineTest {
    @Test
    void oneLinePerStageWithScalarMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classes", 28);
        m.put("lines", 1913);
        m.put("jar", "D:/very/long/path/to/some.jar");
        m.put("castfix", List.of(3, 0));
        m.put("runs", Map.of("registry", 0));
        String l = PortRunner.stageLine("closure", true, 75, m);
        assertEquals(1, l.lines().count());
        assertTrue(l.startsWith("closure"), l);
        assertTrue(l.contains("ok"), l);
        assertTrue(l.contains("1m15s"), l);
        assertTrue(l.contains("classes 28, lines 1913, castfix [3,0]"), l);
        assertFalse(l.contains("some.jar"), l);
    }

    @Test
    void failedStageSaysSo() {
        assertTrue(PortRunner.stageLine("build", false, 3, Map.of("exitCode", 1)).contains("FAILED"));
        assertEquals("0s", PortRunner.duration(0));
        assertEquals("4m51s", PortRunner.duration(291));
    }
}
