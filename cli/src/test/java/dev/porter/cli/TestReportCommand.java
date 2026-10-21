package dev.porter.cli;

import dev.porter.core.cli.ExitCodes;
import dev.porter.core.cli.PorterSubcommand;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** Test-only provider proving that ServiceLoader-registered commands replace stubs. */
@Command(name = "report", description = "test provider")
public final class TestReportCommand implements PorterSubcommand, Callable<Integer> {
    @Override
    public String path() {
        return "report";
    }

    @Override
    public Integer call() {
        return ExitCodes.OK;
    }
}
