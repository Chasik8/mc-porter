package dev.porter.port.upfront;

import dev.porter.port.upfront.model.Kind;
import dev.porter.port.upfront.model.MatchRow;
import dev.porter.port.upfront.model.MatchTable;
import dev.porter.port.upfront.model.Plan;
import dev.porter.port.upfront.model.Target;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the flattening table ({@code registry.tsv}, {@link Flattening}) into a match table: a REGISTRY row
 * ({@code Items.SUGAR_CANE}) gets the 1.7.10 expression of its registry row as target ({@code owner} empty, {@code name}
 * the expression, used verbatim by the rule emitter). Rows without a counterpart ({@code ABSENT}, {@code TODO}) and rows
 * that need a damage value ({@code meta} set: the expression would lose it) keep no target, so they stay unresolved.
 */
public final class RegistryJoin {
    private RegistryJoin() {
    }

    /** Simple names an expression of the table may use, with the class that must be imported for them (1.7.10). */
    static final Map<String, String> IMPORTS = Map.of(
            "Blocks", "net.minecraft.init.Blocks",
            "Items", "net.minecraft.init.Items",
            "Item", "net.minecraft.item.Item",
            "Enchantment", "net.minecraft.enchantment.Enchantment",
            "Potion", "net.minecraft.potion.Potion",
            "FluidRegistry", "net.minecraftforge.fluids.FluidRegistry");

    private static final Pattern WORD = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\b");

    /** Imports an expression needs, sorted. */
    public static List<String> importsOf(String expr) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(expr.replaceAll("\"[^\"]*\"", ""));
        while (m.find()) {
            String imp = IMPORTS.get(m.group(1));
            if (imp != null && !out.contains(imp)) {
                out.add(imp);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Result of a join: the table and how many REGISTRY rows got a target. */
    public record Joined(MatchTable table, int registryRows, int withTarget, int needsMeta, int absent) {
    }

    public static Joined apply(MatchTable table, Path registryTsv) throws IOException {
        Map<String, String[]> by = new HashMap<>();
        for (String l : Files.readAllLines(registryTsv, StandardCharsets.UTF_8)) {
            String[] p = l.split("\t", -1);
            if (p.length == 8 && !p[0].equals("registry")) {
                by.put(p[0] + "#" + p[2], p);
            }
        }
        List<MatchRow> rows = new ArrayList<>();
        int regRows = 0;
        int with = 0;
        int meta = 0;
        int absent = 0;
        for (MatchRow r : table.rows()) {
            if (r.plan() != Plan.REGISTRY || r.kind() != Kind.FIELD || r.target() != null) {
                rows.add(r);
                continue;
            }
            regRows++;
            String owner = r.key().owner();
            String[] p = by.get(owner.substring(owner.lastIndexOf('.') + 1) + "#" + r.key().name());
            if (p == null || p[3].equals("ABSENT") || p[3].equals("TODO")) {
                absent++;
                rows.add(r);
            } else if (!p[5].isEmpty()) {
                meta++;
                rows.add(r);
            } else {
                with++;
                rows.add(new MatchRow(r.key(), r.uses(), r.area(), r.resolvedBy(), r.plan(), "registry:flattening", Double.parseDouble(p[6]),
                        new Target("", p[4], "", true), r.shim(), r.ruleId(), r.features(), r.alternatives(), r.note()));
            }
        }
        MatchTable sorted = new MatchTable(MatchTable.SCHEMA_VERSION, new MatchTable(MatchTable.SCHEMA_VERSION, rows).sortedRows());
        return new Joined(sorted, regRows, with, meta, absent);
    }
}
