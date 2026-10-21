// porter:adapter (generic) - brigadier CommandContext (source + the literal path that was run).
package dev.porter.shim.mc.cmd;

public class CommandContext<S> {
    private final S source;
    private final String input;

    public CommandContext(S source, String input) {
        this.source = source;
        this.input = input;
    }

    public S getSource() { return source; }

    public String getInput() { return input; }
}
