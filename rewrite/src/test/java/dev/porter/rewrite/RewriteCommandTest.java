package dev.porter.rewrite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class RewriteCommandTest {
    @Test
    void secondRunIsByteIdentical(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("p"));
        Files.writeString(src.resolve("p/A.java"), RewriterTest.DECOMPILED);
        Files.writeString(src.resolve("p/Other.java"), "package p;\nclass Other { }\n");
        System.setProperty("porter.reports.dir", tmp.resolve("reports").toString());
        for (String o : List.of("rw1", "rw2")) {
            int code = new CommandLine(new RewriteCommand()).execute("--src", src.toString(), "--from", "1.19.2", "--to", "1.7.10",
                    "--out", tmp.resolve(o).toString(), "--rules", TestSupport.repo().resolve("rules/composite").toString(),
                    "--graph", TestSupport.resource("graphs/srg-names-1.19.2.graph.json").toString(), "--include", "**/A.java");
            assertEquals(0, code);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(tmp.resolve("rw1"))) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        assertTrue(files.stream().anyMatch(p -> p.endsWith("p/A.java")), files.toString());
        assertTrue(files.stream().noneMatch(p -> p.endsWith("p/Other.java")), "only --include files are written");
        assertTrue(files.stream().anyMatch(p -> p.endsWith("dev/porter/shim/BlockPos.java")), "shim copied: " + files);
        assertTrue(files.stream().anyMatch(p -> p.endsWith("dev/porter/shim/Worlds.java")), "world helpers copied: " + files);
        for (Path f : files) {
            Path twin = tmp.resolve("rw2").resolve(tmp.resolve("rw1").relativize(f));
            assertArrayEquals(Files.readAllBytes(f), Files.readAllBytes(twin), "differs: " + f);
        }
        String report = Files.readString(tmp.resolve("rw1/porter-rewrite.json"));
        assertTrue(report.contains("\"todosByReason\""), report);
    }

    @Test
    void checkRules(@TempDir Path tmp) {
        System.setProperty("porter.reports.dir", tmp.resolve("reports").toString());
        int code = new CommandLine(new RewriteCommand()).execute("--from", "1.19.2", "--to", "1.7.10", "--check-rules",
                "--rules", TestSupport.repo().resolve("rules/composite/1.19.2__1.7.10/math.rule.yaml").toString());
        assertEquals(0, code);
    }
}
