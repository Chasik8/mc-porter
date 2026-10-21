package dev.porter.core.cli;

/**
 * Service-provider interface through which modules contribute subcommands to the single {@code porter} CLI.
 *
 * <p>To implement e.g. {@code porter graph build} in the graph module:
 * <ol>
 *   <li>write a class annotated with picocli {@code @Command(name = "build", ...)} that implements
 *       {@code Callable<Integer>} and this interface ({@link #path()} returns {@code "graph build"},
 *       {@link #command()} returns {@code this});</li>
 *   <li>register it in {@code src/main/resources/META-INF/services/dev.porter.core.cli.PorterSubcommand};</li>
 *   <li>write a JSON report with {@link RunReport} and return an {@link ExitCodes} value.</li>
 * </ol>
 * Do not use {@code mixinStandardHelpOptions}: it claims {@code --version}, which commands such as
 * {@code extract --version 1.19.2} need. Declare {@code @Option(names = {"-h", "--help"}, usageHelp = true)} instead.
 * The CLI replaces the built-in "not implemented" stub with the provided command. Paths must be one of
 * {@link KnownCommands#PATHS} (new top-level commands need a maintainer decision).
 */
public interface PorterSubcommand {
    /** Space-separated command path, e.g. {@code "extract"} or {@code "test diff"}. */
    String path();

    /** The picocli command object (annotated with {@code @Command}); usually {@code this}. */
    default Object command() {
        return this;
    }
}
