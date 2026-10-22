package dev.porter.port.patch;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import dev.porter.core.cli.RunReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code porter patch make} / {@code porter patch apply}: unified diffs between trees (see {@link PatchSet}). */
public final class PatchCommands {
    private PatchCommands() {
    }

    abstract static class Base implements PorterSubcommand, Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        boolean help;

        @Option(names = "--base", required = true, paramLabel = "<dir>", description = "Root of the '---' paths (the unchanged files).")
        Path base;

        @Option(names = "--target", paramLabel = "<dir>", description = "Root of the '+++' paths (default: the base root).")
        Path target;

        RunReport report() {
            return new RunReport(path(), spec.commandLine().getParseResult().originalArgs());
        }

        Path target() {
            return target == null ? base : target;
        }
    }

    @Command(name = "make", description = {"Write a unified diff that turns files of the base tree into files of the target tree.",
            "Without --file/--files: every file that differs between the two trees at the same relative path (--under limits it)."})
    public static final class Make extends Base {
        @Option(names = "--file", paramLabel = "<base>[=<target>]", description = "One file pair (repeatable); a path alone means the same path in both trees.")
        List<String> files = new ArrayList<>();

        @Option(names = "--files", paramLabel = "<list>", description = "File with one <base>[=<target>] pair per line.")
        Path list;

        @Option(names = "--under", paramLabel = "<subpath>", description = "Tree mode: only files below this relative path (repeatable).")
        List<String> under = new ArrayList<>();

        @Option(names = "--context", description = "Context lines (default 3).")
        int context = 3;

        @Option(names = "--blind", description = "Blind patches: no context and no removed lines (the base is pinned by sha256), so the patch "
                + "carries no text of the base - for bases that must not be redistributed.")
        boolean blind;

        @Option(names = "--split", description = "Write one <target path>.patch per file below --out (a directory) instead of one file.")
        boolean split;

        @Option(names = "--out", required = true, paramLabel = "<file>", description = "Patch file to write.")
        Path out;

        @Override
        public String path() {
            return "patch make";
        }

        @Override
        public Integer call() throws IOException {
            List<String> specs = new ArrayList<>(files);
            if (list != null) {
                for (String l : Files.readAllLines(list)) {
                    if (!l.isBlank() && !l.startsWith("#")) {
                        specs.add(l.strip());
                    }
                }
            }
            List<PatchSet.Pair> pairs = specs.isEmpty() ? PatchSet.sameNames(base, target(), under) : PatchSet.pairs(specs, Map.of());
            if (split) {
                int n = 0;
                for (PatchSet.Pair pr : pairs) {
                    String t = PatchSet.make(base, target(), List.of(pr), context, blind);
                    if (!t.isEmpty()) {
                        Path f = out.resolve((pr.target() != null ? pr.target() : pr.base()) + ".patch");
                        Files.createDirectories(f.getParent());
                        Files.write(f, t.getBytes(StandardCharsets.UTF_8));
                        n++;
                    }
                }
                spec.commandLine().getOut().println("porter patch make: " + n + " file(s) differ -> " + out);
                report().put("files", n).write(RunReport.STATUS_OK, ExitCodes.OK);
                return ExitCodes.OK;
            }
            String text = PatchSet.make(base, target(), pairs, context, blind);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            long n = UnifiedDiff.parse(text).size();
            spec.commandLine().getOut().println("porter patch make: " + n + " file(s) differ -> " + out);
            report().put("files", n).write(RunReport.STATUS_OK, ExitCodes.OK);
            return ExitCodes.OK;
        }
    }

    @Command(name = "apply", description = {"Apply unified diffs exactly (no fuzz): '---' paths read from --base, '+++' paths written to --target.",
            "All patches are checked before anything is written."})
    public static final class Apply extends Base {
        @Option(names = "--patches", required = true, paramLabel = "<file|dir>", description = "A patch file or a directory of *.patch files.")
        Path patches;

        @Override
        public String path() {
            return "patch apply";
        }

        @Override
        public Integer call() {
            try {
                PatchSet.Applied a = PatchSet.apply(patches, base, target());
                spec.commandLine().getOut().println("porter patch apply: " + a.written().size() + " written, " + a.deleted().size() + " deleted");
                report().put("written", a.written().size()).put("deleted", a.deleted().size()).write(RunReport.STATUS_OK, ExitCodes.OK);
                return ExitCodes.OK;
            } catch (IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
                spec.commandLine().getErr().println("porter patch apply: " + e.getMessage());
                report().put("error", e.getMessage()).write(RunReport.STATUS_FAILED, ExitCodes.FAILED);
                return ExitCodes.FAILED;
            }
        }
    }
}
