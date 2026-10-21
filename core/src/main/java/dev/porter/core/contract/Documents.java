package dev.porter.core.contract;

import dev.porter.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads contract documents from disk as JSON trees.
 *
 * <ul>
 *   <li>{@code *.json}: one document.</li>
 *   <li>{@code *.jsonl}: one document per non-blank line.</li>
 *   <li>{@code *.yaml}/{@code *.yml}: one or more YAML documents separated by {@code ---}.</li>
 * </ul>
 */
public final class Documents {
    private Documents() {
    }

    /** Parsed documents of a file plus parse errors (if any, documents may be incomplete). */
    public record Parsed(List<JsonNode> documents, List<ValidationIssue> issues) {
    }

    public static boolean isYaml(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    public static boolean isJsonLines(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl");
    }

    public static boolean isSupported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".json") || isJsonLines(file) || isYaml(file);
    }

    public static Parsed read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<JsonNode> docs = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();
        if (isJsonLines(file)) {
            String[] lines = text.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                try {
                    docs.add(Json.json().readTree(lines[i]));
                } catch (JacksonException e) {
                    issues.add(new ValidationIssue(docs.size(), "line " + (i + 1), e.getOriginalMessage(), ValidationIssue.PARSE));
                }
            }
            return new Parsed(docs, issues);
        }
        ObjectMapper mapper = isYaml(file) ? Json.yaml() : Json.json();
        try (MappingIterator<JsonNode> it = mapper.readerFor(JsonNode.class).readValues(text)) {
            while (it.hasNextValue()) {
                JsonNode node = it.nextValue();
                if (node != null && !node.isMissingNode() && !node.isNull()) {
                    docs.add(node);
                }
            }
        } catch (JacksonException e) {
            String where = e.getLocation() == null ? "" : "line " + e.getLocation().getLineNr();
            issues.add(new ValidationIssue(docs.size(), where, e.getOriginalMessage(), ValidationIssue.PARSE));
        }
        if (docs.isEmpty() && issues.isEmpty()) {
            issues.add(new ValidationIssue(0, "", "file contains no document", ValidationIssue.PARSE));
        }
        return new Parsed(docs, issues);
    }
}
