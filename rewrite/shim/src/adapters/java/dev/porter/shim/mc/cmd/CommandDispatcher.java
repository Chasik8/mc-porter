// porter:adapter (generic) - brigadier CommandDispatcher: collects the registered literal roots
// (ModLifecycle turns each into a 1.7.10 command, PortedCommand).
package dev.porter.shim.mc.cmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandDispatcher<S> {
    private final List<LiteralArgumentBuilder<S>> roots = new ArrayList<>();

    public LiteralArgumentBuilder<S> register(LiteralArgumentBuilder<S> command) {
        roots.add(command);
        return command;
    }

    public List<LiteralArgumentBuilder<S>> getRoots() { return Collections.unmodifiableList(roots); }
}
