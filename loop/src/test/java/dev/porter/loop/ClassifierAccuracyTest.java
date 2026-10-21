package dev.porter.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.porter.core.json.Json;
import dev.porter.loop.javac.ClassifiedError;
import dev.porter.loop.javac.ErrorClassifier;
import dev.porter.loop.javac.JavacOutputParser;
import dev.porter.loop.run.CompileRunner;
import dev.porter.loop.run.JavacCompileRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Acceptance criterion 1E: >= 90 % classifier accuracy on 30 typical javac errors (real javac output). */
class ClassifierAccuracyTest {
    @TempDir
    Path tmp;

    @Test
    void thirtyTypicalErrors() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/classifier/cases.yaml")) {
            root = Json.yaml().readTree(in);
        }
        List<String> wrong = new ArrayList<>();
        Map<String, Object> rows = new LinkedHashMap<>();
        int total = 0;
        int right = 0;
        for (JsonNode c : root.get("cases")) {
            total++;
            String id = c.get("id").asString();
            Path dir = tmp.resolve(id);
            Path src = dir.resolve("src");
            for (var f : c.get("files").properties()) {
                Path p = src.resolve(f.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getValue().asString(), StandardCharsets.UTF_8);
            }
            CompileRunner.Result res = new JavacCompileRunner(List.of(src), List.of(), dir.resolve("out"), List.of()).compile();
            assertFalse(res.exitCode() == 0, id + " must not compile");
            List<ClassifiedError> errs = new ErrorClassifier(dir, null).classifyAll(JavacOutputParser.parse(res.output()));
            assertFalse(errs.isEmpty(), id + ": no error parsed from\n" + res.output());
            ClassifiedError first = errs.get(0);
            String expected = c.get("expected").asString();
            boolean ok = expected.equals(first.errorClass().id());
            if (ok) {
                right++;
            } else {
                wrong.add(id + ": expected " + expected + ", got " + first.errorClass().id() + " for '" + first.diagnostic().message() + "'");
            }
            if (c.has("symbol")) {
                assertEquals(c.get("symbol").asString(), first.symbol(), id + " symbol");
            }
            assertTrue(first.file().endsWith(".java"), id + " file " + first.file());
            rows.put(id, Map.of("expected", expected, "actual", first.errorClass().id(), "code", first.code(),
                    "message", first.diagnostic().message()));
        }
        double accuracy = (double) right / total;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cases", total);
        report.put("correct", right);
        report.put("accuracy", accuracy);
        report.put("wrong", wrong);
        report.put("rows", rows);
        Path out = Path.of("build/reports/loop/classifier-accuracy.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, Json.json().writeValueAsString(report), StandardCharsets.UTF_8);
        assertEquals(30, total);
        assertTrue(accuracy >= 0.9, "accuracy " + accuracy + "; wrong: " + wrong);
        System.out.println("classifier accuracy " + right + "/" + total + " " + wrong);
    }
}
