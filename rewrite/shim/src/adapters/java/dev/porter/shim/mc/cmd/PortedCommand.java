// porter:adapter (generic) - a 1.7.10 command that runs a literal brigadier tree: "/root sub sub" walks the
// literal children, checks every requirement on the way and runs the command of the last node (usage otherwise).
package dev.porter.shim.mc.cmd;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;

public class PortedCommand extends CommandBase {
    private final LiteralArgumentBuilder<CommandSourceStack> root;

    public PortedCommand(LiteralArgumentBuilder<CommandSourceStack> root) { this.root = root; }

    public static List<PortedCommand> of(CommandDispatcher<CommandSourceStack> d) {
        List<PortedCommand> l = new ArrayList<>();
        for (LiteralArgumentBuilder<CommandSourceStack> r : d.getRoots()) l.add(new PortedCommand(r));
        return l;
    }

    @Override
    public String getCommandName() { return root.getLiteral(); }

    @SuppressWarnings("unchecked")
    private static String literal(ArgumentBuilder<CommandSourceStack, ?> c) {
        return c instanceof LiteralArgumentBuilder ? ((LiteralArgumentBuilder<CommandSourceStack>) c).getLiteral() : null;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        List<String> subs = new ArrayList<>();
        for (ArgumentBuilder<CommandSourceStack, ?> c : root.getArguments()) if (literal(c) != null) subs.add(literal(c));
        return "/" + root.getLiteral() + (subs.isEmpty() ? "" : " <" + String.join("|", subs) + ">");
    }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return root.getRequirement().test(new CommandSourceStack(sender, getCommandName()));
    }

    private ArgumentBuilder<CommandSourceStack, ?> child(ArgumentBuilder<CommandSourceStack, ?> node, String name) {
        for (ArgumentBuilder<CommandSourceStack, ?> c : node.getArguments()) if (name.equalsIgnoreCase(literal(c))) return c;
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CommandSourceStack src = new CommandSourceStack(sender, getCommandName());
        ArgumentBuilder<CommandSourceStack, ?> node = root;
        StringBuilder path = new StringBuilder(root.getLiteral());
        for (String a : args) {
            ArgumentBuilder<CommandSourceStack, ?> next = child(node, a);
            if (next == null) throw new CommandException(getCommandUsage(sender));
            if (!next.getRequirement().test(src)) throw new CommandException("commands.generic.permission");
            node = next;
            path.append(' ').append(a);
        }
        if (node.getCommand() == null) throw new CommandException(getCommandUsage(sender));
        node.getCommand().run(new CommandContext<>(src, path.toString()));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        ArgumentBuilder<CommandSourceStack, ?> node = root;
        for (int i = 0; i < args.length - 1 && node != null; i++) node = child(node, args[i]);
        if (node == null) return null;
        List<String> names = new ArrayList<>();
        for (ArgumentBuilder<CommandSourceStack, ?> c : node.getArguments()) if (literal(c) != null) names.add(literal(c));
        return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
    }
}
