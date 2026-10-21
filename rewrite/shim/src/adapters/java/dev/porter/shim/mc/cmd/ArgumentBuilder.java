// porter:adapter (generic) - brigadier ArgumentBuilder (literal command trees only; no argument types).
package dev.porter.shim.mc.cmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public abstract class ArgumentBuilder<S, T extends ArgumentBuilder<S, T>> {
    final List<ArgumentBuilder<S, ?>> children = new ArrayList<>();
    Command<S> command;
    Predicate<S> requirement = s -> true;

    protected abstract T getThis();

    public T then(ArgumentBuilder<S, ?> child) {
        children.add(child);
        return getThis();
    }

    public T executes(Command<S> c) {
        this.command = c;
        return getThis();
    }

    public T requires(Predicate<S> r) {
        this.requirement = r;
        return getThis();
    }

    public Command<S> getCommand() { return command; }

    public Predicate<S> getRequirement() { return requirement; }

    public List<ArgumentBuilder<S, ?>> getArguments() { return Collections.unmodifiableList(children); }
}
