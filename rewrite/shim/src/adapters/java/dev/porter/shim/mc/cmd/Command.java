// porter:adapter (generic) - brigadier Command.
package dev.porter.shim.mc.cmd;

@FunctionalInterface
public interface Command<S> {
    int SINGLE_SUCCESS = 1;

    int run(CommandContext<S> context);
}
