package org.royaldev.thehumanity;

import org.apache.commons.lang3.ArrayUtils;
import org.kitteh.irc.client.library.EventHandler;
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent;
import org.kitteh.irc.client.library.event.client.ClientConnectedEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.royaldev.thehumanity.commands.CallInfo;
import org.royaldev.thehumanity.commands.IRCCommand;
import org.royaldev.thehumanity.util.ConversionHelper;

/**
 * The basic listeners of the bot that allow it to function.
 */
final class BaseListeners {

    private final TheHumanity humanity;

    BaseListeners(final TheHumanity instance) {
        this.humanity = instance;
    }

    @SuppressWarnings("ConstantConditions")
    @EventHandler
    public void onChannelMessage(final ChannelMessageEvent e) {
        final String message = e.getMessage();
        if (message.isEmpty() || message.charAt(0) != this.humanity.getPrefix()) return;
        final String[] split = message.trim().split(" ");
        final String commandString = split[0].substring(1, split[0].length());
        final IRCCommand command = this.humanity.getCommandHandler().get(commandString);
        if (command == null) return;
        final IRCCommand.CommandType commandType = command.getCommandType();
        if (commandType != IRCCommand.CommandType.MESSAGE && commandType != IRCCommand.CommandType.BOTH) return;
        this.humanity.getLogger().info(e.getChannel().getName() + "/" + e.getActor().getNick() + ": " + e.getMessage());
        try {
            command.onCommand(e, new CallInfo(commandString, CallInfo.UsageType.MESSAGE), ArrayUtils.subarray(split, 1, split.length));
        } catch (final Throwable t) {
            t.printStackTrace();
            final StringBuilder sb = new StringBuilder("Unhandled command exception! ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            e.getActor().sendNotice(sb.toString());
            this.humanity.getLogger().warning(sb.toString());
        }
    }

    @EventHandler
    public void onConnect(final ClientConnectedEvent event) throws Exception {
        event.getClient().sendRawLine("/mode " + event.getClient().getNick() + " +B");
    }

    @EventHandler
    public void onInvite(final ChannelInviteEvent e) {
        e.getClient().addChannel(e.getChannel().getName());
        this.humanity.getLogger().info("Invited to " + e.getChannel() + " by " + e.getActor().getName() + ".");
    }

    @EventHandler
    public void onJoin(final ChannelJoinEvent e) {
        if (e.getChannel().getUsers().size() < 1) e.getChannel().part("Alone.");
        if (!e.getActor().getNick().equals(this.humanity.getBot().getNick())) return;
        this.humanity.getLogger().info("Joined " + e.getChannel().getName() + ".");
    }

    @EventHandler
    public void onKick(final ChannelKickEvent e) {
        this.humanity.getLogger().info("Kicked from " + e.getChannel().getName() + ".");
    }

    @EventHandler
    public void onPart(final ChannelPartEvent e) {
        if (e.getChannel().getUsers().size() <= 2) e.getChannel().part("Alone.");
        if (!e.getActor().getNick().equals(this.humanity.getBot().getNick())) return;
        this.humanity.getLogger().info("Parted from " + e.getChannel().getName() + ".");
    }

    @EventHandler
    public void onPrivateMessage(final PrivateMessageEvent e) {
        final String message = e.getMessage();
        if (message.isEmpty()) return;
        final String[] split = message.trim().split(" ");
        final String commandString = split[0];
        final IRCCommand command = this.humanity.getCommandHandler().get(commandString);
        if (command == null) {
            ConversionHelper.respond(e, "No such command.");
            return;
        }
        final IRCCommand.CommandType commandType = command.getCommandType();
        if (commandType != IRCCommand.CommandType.PRIVATE && commandType != IRCCommand.CommandType.BOTH) {
            ConversionHelper.respond(e, "No such command.");
            return;
        }
        this.humanity.getLogger().info(e.getActor().getNick() + ": " + e.getMessage());
        try {
            command.onCommand(e, new CallInfo(commandString, CallInfo.UsageType.PRIVATE), ArrayUtils.subarray(split, 1, split.length));
        } catch (final Throwable t) {
            t.printStackTrace();
            final StringBuilder sb = new StringBuilder("Unhandled command exception! ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            e.getActor().sendNotice(sb.toString());
            this.humanity.getLogger().warning(sb.toString());
        }
    }

}
