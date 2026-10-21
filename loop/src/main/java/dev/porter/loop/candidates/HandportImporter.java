package dev.porter.loop.candidates;

import dev.porter.core.candidate.Candidate;
import dev.porter.core.candidate.CandidateIo;
import dev.porter.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Imports a hand-port {@code porting-log/api.jsonl} (records {@code {from, to, before, after, symbol, kind, count,
 * author}}, not a porter contract) as contract candidates. The hand port records no compiler error, so {@code error}
 * says so and {@code file}/{@code line} point at the log record; {@code normalized} is computed by {@link Normalizer}.
 * Output is ARR-derived when the log is: write it under {@code private/} only.
 */
public final class HandportImporter {
    private HandportImporter() {
    }

    public static List<Candidate> convert(Path apiJsonl, String fileLabel) {
        List<String> lines;
        try {
            lines = Files.readAllLines(apiJsonl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.isEmpty()) {
                continue;
            }
            JsonNode n = Json.json().readTree(l);
            String before = text(n, "before");
            String after = text(n, "after");
            String symbol = text(n, "symbol");
            String kind = text(n, "kind");
            String author = text(n, "author");
            if (author == null || !author.matches("^(agent|human|rule):[A-Za-z0-9_.-]+$")) {
                author = "human:handport";
            }
            int count = n.has("count") && n.get("count").canConvertToInt() ? Math.max(1, n.get("count").asInt()) : 1;
            String from = text(n, "from");
            String to = text(n, "to");
            if (from != null && !from.matches("^(forge|neoforge|fabric|vanilla)-[0-9]+\\.[0-9]+(\\.[0-9]+)?$")) {
                from = null;
            }
            if (to != null && !to.matches("^(forge|neoforge|fabric|vanilla)-[0-9]+\\.[0-9]+(\\.[0-9]+)?$")) {
                to = null;
            }
            String id = "c-hp-" + CandidateAggregator.sha(l).substring(0, 12);
            out.add(new Candidate(1, id, from, to, "hand port: no javac error recorded" + (kind == null ? "" : " (" + kind + ")"),
                    null, fileLabel, i + 1, before == null ? "" : before, after == null ? "" : after, symbol,
                    Normalizer.normalize(symbol, before, after), author, count, null));
        }
        return out;
    }

    /** Converts and writes (replacing {@code out}). */
    public static List<Candidate> importTo(Path apiJsonl, Path out, String fileLabel) {
        List<Candidate> cs = convert(apiJsonl, fileLabel);
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.deleteIfExists(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Candidate c : cs) {
            CandidateIo.append(out, c);
        }
        return cs;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }
}
