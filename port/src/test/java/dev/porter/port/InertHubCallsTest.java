package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.port.passes.InertHubCalls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InertHubCallsTest {
    @Test
    void reportsCallsIntoInertStubMembers(@TempDir Path tmp) throws Exception {
        Path stub = tmp.resolve("a/hub/Painter.java");
        Files.createDirectories(stub.getParent());
        Files.writeString(stub, "package a.hub;\npublic class Painter {\n"
                + "    public Painter() {\n        // porter:hub-stub inert\n    }\n"
                + "    public static void drawIcon(int x) {\n        // porter:hub-stub inert\n    }\n"
                + "    public int size() {\n        return 0; // porter:hub-stub inert\n    }\n"
                + "    public int real() {\n        return 7;\n    }\n}\n");
        Path user = tmp.resolve("a/gui/Screen.java");
        Files.createDirectories(user.getParent());
        Files.writeString(user, "package a.gui;\nimport a.hub.Painter;\nclass Screen {\n"
                + "    void f(Painter p) {\n        // Painter.drawIcon(0);\n        Painter.drawIcon(1);\n"
                + "        int n = p.size() + p.real();\n    }\n}\n");
        List<InertHubCalls.Hit> hits = InertHubCalls.scan(tmp);
        assertEquals(2, hits.size());
        assertEquals("drawIcon", hits.get(0).member());
        assertEquals(6, hits.get(0).line());
        assertEquals("static", hits.get(0).kind());
        assertTrue(hits.get(0).client());
        assertEquals("size", hits.get(1).member());
        assertEquals("instance", hits.get(1).kind());
        assertEquals(List.of("a.hub.Painter#drawIcon [client] x1", "a.hub.Painter#size [client] x1"), InertHubCalls.summary(hits));
    }

    @Test
    void noStubsNoHits(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("X.java"), "class X { void f() { Y.g(); } }\n");
        assertTrue(InertHubCalls.scan(tmp).isEmpty());
    }
}
