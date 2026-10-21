// porter:adapter (generic) - 1.19.2 net.minecraft.commands.Commands (literal builders, permission levels).
package dev.porter.shim.mc.cmd;

public final class Commands {
    public static final int LEVEL_ALL = 0, LEVEL_MODERATORS = 1, LEVEL_GAMEMASTERS = 2, LEVEL_ADMINS = 3, LEVEL_OWNERS = 4;

    private Commands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) { return LiteralArgumentBuilder.literal(name); }
}
