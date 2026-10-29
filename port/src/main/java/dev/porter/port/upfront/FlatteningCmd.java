package dev.porter.port.upfront;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code porter flattening}: derives the registry-constant table (1.19.2 {@code Items.X}/{@code Blocks.X}/... to a 1.7.10
 * expression) from the user's own client jar and MCP jar. The result is cached next to the output: the inputs' sha256
 * are stored in {@code <out>.inputs} and an unchanged run is skipped unless {@code --force}.
 */
@Command(name = "flattening", description = {"Registry-constant table: 1.19.2 Items/Blocks/SoundEvents/... constants to 1.7.10 expressions.",
    "Reads the flattening strings of the client jar and the registrations of the 1.7.10 MCP jar; writes registry.tsv."})
public final class FlatteningCmd implements Callable<Integer>, PorterSubcommand {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--client-jar", required = true, paramLabel = "<client.jar>", description = "The 1.19.2 client jar (obfuscated).")
    Path clientJar;

    @Option(names = "--tiny", required = true, paramLabel = "<1.19.2.tiny>", description = "Tiny v2 mappings with obf and mojmap namespaces.")
    Path tiny;

    @Option(names = "--target-jar", required = true, paramLabel = "<mcp.jar>", description = "The 1.7.10 MCP (+Forge) jar.")
    Path targetJar;

    @Option(names = "--out", required = true, paramLabel = "<registry.tsv>")
    Path out;

    @Option(names = "--sounds", paramLabel = "<sounds.json>", description = "The 1.7.10 sounds.json (assets index object 'minecraft/sounds.json'): "
            + "SoundEvents rows get the 1.7.10 sound name (a string) when the file has a name of the same shape.")
    Path sounds;

    @Option(names = "--force", description = "Rebuild even if the cached output matches the inputs.")
    boolean force;

    @Option(names = "--compare", paramLabel = "<table.tsv>", description = "Hand-rule table (columns kind owner name ... gt_kind gt ... method): "
            + "report how many registry-constant rows with gt_kind=direct are reproduced.")
    Path compare;

    @Override
    public String path() {
        return "flattening";
    }

    @Override
    public Integer call() {
        RunReport report = new RunReport("flattening", spec.commandLine().getParseResult().originalArgs());
        Path inputs = out.resolveSibling(out.getFileName() + ".inputs");
        String sha = sounds == null ? Flattening.sha(clientJar, tiny, targetJar) : Flattening.sha(clientJar, tiny, targetJar, sounds);
        List<Flattening.Row> rows;
        try {
            if (!force && Files.exists(out) && Files.exists(inputs) && Files.readString(inputs).equals(sha)) {
                System.out.println("registry table up to date: " + out);
                report.put("cached", true).write(RunReport.STATUS_OK, ExitCodes.OK);
                if (compare != null) {
                    compare(readBack(out));
                }
                return ExitCodes.OK;
            }
            StringBuilder stats = new StringBuilder();
            rows = Flattening.derive(clientJar, tiny, targetJar, stats);
            if (sounds != null) {
                int[] found = new int[1];
                rows = SoundNames.apply(rows, SoundNames.load(sounds), found);
                stats.append("sound names found ").append(found[0]).append('\n');
            }
            Flattening.write(rows, out);
            Files.writeString(inputs, sha);
            System.out.print(stats);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Flattening.summary(rows).forEach((k, v) -> System.out.println(k + "\t" + v));
        long withTarget = rows.stream().filter(r -> !r.kind().equals("ABSENT") && !r.kind().equals("TODO")).count();
        System.out.println("rows " + rows.size() + ", with a target " + withTarget);
        if (compare != null) {
            compare(rows);
        }
        report.put("rows", rows.size()).put("withTarget", withTarget).write(RunReport.STATUS_OK, ExitCodes.OK);
        return ExitCodes.OK;
    }

    private static List<Flattening.Row> readBack(Path f) throws IOException {
        List<Flattening.Row> rows = new ArrayList<>();
        for (String l : Files.readAllLines(f)) {
            String[] p = l.split("\t", -1);
            if (p.length == 8 && !p[0].equals("registry")) {
                rows.add(new Flattening.Row(p[0], p[1], p[2], p[3], p[4], p[5], Double.parseDouble(p[6]), p[7]));
            }
        }
        return rows;
    }

    private void compare(List<Flattening.Row> rows) {
        Map<String, Flattening.Row> by = new HashMap<>();
        rows.forEach(r -> by.put(r.registry() + "#" + r.field(), r));
        int total = 0;
        int hit = 0;
        int all = 0;
        int targeted = 0;
        int unres = 0;
        int unresTargeted = 0;
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        try {
            List<String> lines = Files.readAllLines(compare);
            List<String> head = List.of(lines.get(0).split("\t", -1));
            int iOwner = head.indexOf("owner");
            int iName = head.indexOf("name");
            int iKind = head.indexOf("gt_kind");
            int iGt = head.indexOf("gt");
            int iMethod = head.indexOf("method");
            int iResolved = head.indexOf("resolved");
            for (String l : lines.subList(1, lines.size())) {
                String[] p = l.split("\t", -1);
                if (p.length <= iMethod || !p[iMethod].equals("registry-constant")) {
                    continue;
                }
                String owner = p[iOwner].substring(p[iOwner].lastIndexOf('.') + 1);
                Flattening.Row r = by.get(owner + "#" + p[iName]);
                all++;
                boolean unresolved = iResolved >= 0 && p[iResolved].equals("0");
                unres += unresolved ? 1 : 0;
                if (r != null && !r.kind().equals("ABSENT") && !r.kind().equals("TODO")) {
                    targeted++;
                    unresTargeted += unresolved ? 1 : 0;
                } else {
                    reasons.merge(owner + ":" + (r == null ? "no row" : r.kind() + " " + r.source().replaceAll("[0-9]+$", "")), 1, Integer::sum);
                }
                if (!p[iKind].equals("direct")) {
                    continue;
                }
                total++;
                String field = r == null ? "" : r.expr().replaceAll(".*[.(]", "").replace(")", "");
                boolean ok = r != null && !field.isEmpty() && List.of(p[iGt].split("[|]")).contains(field);
                if (ok) {
                    hit++;
                } else {
                    System.out.println("MISS " + owner + "." + p[iName] + " gt=" + p[iGt] + " got=" + (r == null ? "-" : r.kind() + " " + r.expr()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("hand rules reproduced: " + hit + " / " + total);
        System.out.println("registry-constant rows of the table with a target: " + targeted + " / " + all);
        System.out.println("  of those unresolved by rules (resolved=0): " + unresTargeted + " / " + unres);
        reasons.forEach((k, v) -> System.out.println("  without target: " + k + " " + v));
    }
}
