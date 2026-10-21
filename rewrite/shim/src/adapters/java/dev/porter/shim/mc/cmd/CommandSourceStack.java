// porter:adapter (generic) - 1.19.2 CommandSourceStack over a 1.7.10 ICommandSender.
package dev.porter.shim.mc.cmd;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

public class CommandSourceStack {
    private final ICommandSender sender;
    private final String commandName;

    public CommandSourceStack(ICommandSender sender, String commandName) {
        this.sender = sender;
        this.commandName = commandName;
    }

    public ICommandSender getSender() { return sender; }

    public MinecraftServer getServer() { return MinecraftServer.getServer(); }

    public World getLevel() { return sender.getEntityWorld(); }

    public Entity getEntity() { return sender instanceof Entity ? (Entity) sender : null; }

    public EntityPlayerMP getPlayerOrException() {
        if (sender instanceof EntityPlayerMP) return (EntityPlayerMP) sender;
        throw new net.minecraft.command.PlayerNotFoundException();
    }

    public String getTextName() { return sender.getCommandSenderName(); }

    public boolean hasPermission(int level) { return sender.canCommandSenderUseCommand(level, commandName); }

    public void sendSuccess(IChatComponent message, boolean broadcastToOps) { sender.addChatMessage(message); }

    public void sendFailure(IChatComponent message) {
        sender.addChatMessage(message.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }

    public void sendSystemMessage(IChatComponent message) { sender.addChatMessage(message); }
}
