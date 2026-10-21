package dev.porter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.cli.Compact;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactTest {
    @Test
    void shortOutputIsUnchanged() {
        List<String> l = List.of("a", "b", "c");
        assertEquals(l, Compact.summarize(l, 0, "log"));
        assertEquals(l, Compact.summarize(l, 1, "log"));
    }

    @Test
    void longSuccessKeepsHeadTailAndLogPath() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            l.add("line " + i);
        }
        List<String> s = Compact.summarize(l, 0, "/tmp/x.log");
        assertTrue(s.size() <= Compact.MAX_LINES, s.toString());
        assertEquals("line 0", s.get(0));
        assertTrue(s.contains("line 499"));
        assertTrue(s.get(s.size() - 1).contains("/tmp/x.log"));
    }

    @Test
    void failureIsGroupedByCause() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            l.add("C:/work/Foo" + i + ".java:" + (10 + i) + ": error: cannot find symbol");
            l.add("  symbol:   method getWorld" + (i % 2) + "()");
            l.add("  location: class Foo" + i);
        }
        for (int i = 0; i < 30; i++) {
            l.add("/src/Bar.java:" + i + ": error: package net.minecraft.block does not exist");
        }
        List<String> s = Compact.summarize(l, 1, "/tmp/x.log");
        assertTrue(s.size() <= Compact.MAX_LINES, s.size() + " " + s);
        String all = String.join("\n", s);
        assertTrue(all.contains("200x"), all);
        assertTrue(all.contains("30x"), all);
        assertTrue(all.contains("2 distinct cause(s)"), all);
        assertTrue(all.contains("/tmp/x.log"), all);
    }

    @Test
    void failureWithoutErrorWordsShowsTheTail() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            l.add("step " + i);
        }
        List<String> s = Compact.summarize(l, 2, "log");
        assertTrue(s.size() <= Compact.MAX_LINES);
        assertTrue(s.contains("step 99"));
    }
}
