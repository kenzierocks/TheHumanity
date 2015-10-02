package org.royaldev.thehumanity;

import com.google.common.base.Splitter;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.capabilities.CapabilitiesSupportedListEvent;
import org.kitteh.irc.client.library.event.channel.ChannelInviteEvent;
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent;
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent;
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent;
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent;
import org.kitteh.irc.client.library.event.client.ClientConnectedEvent;
import org.kitteh.irc.client.library.event.client.ClientConnectionClosedEvent;
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent;
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent;
import org.kitteh.irc.lib.net.engio.mbassy.listener.Handler;
import org.royaldev.thehumanity.commands.CallInfo;
import org.royaldev.thehumanity.commands.CallInfo.UsageType;
import org.royaldev.thehumanity.commands.IRCCommand;
import org.royaldev.thehumanity.ping.WhoX;
import org.royaldev.thehumanity.util.ConversionHelper;

import java.util.List;

/**
 * The basic listeners of the bot that allow it to function.
 */
final class BaseListeners {

    private final TheHumanity humanity;

    BaseListeners(final TheHumanity instance) {
        this.humanity = instance;
    }

    @Handler
    public void accountNotify(final CapabilitiesSupportedListEvent e) {
        if (e.getSupportedCapabilities().stream().filter(cap -> cap.getCapabilityName().equalsIgnoreCase("account-notify")).findFirst().isPresent()) {
            e.getClient().sendRawLine("CAP REQ :account-notify");
        }
    }

    @Handler
    public void changeNickAccountMapping(final UserNickChangeEvent e) {
        final User oldUser = e.getUser();
        final User newUser = e.getNewUser();
        final WhoX whoX = this.humanity.getWhoX();
        // Get the account of the user before nick change
        final String oldAccount = whoX.getAccount(oldUser.getMessagingName());
        // Remove account mapping for the old nick
        whoX.remove(oldUser.getMessagingName(), oldAccount);
        // Set the new nick to the account
        whoX.addAccount(newUser.getMessagingName(), oldAccount);
    }

    @Handler
    public void connected(final ClientConnectedEvent e) {
        this.humanity.getLogger().info("Connected to " + e.getServerInfo().getNetworkName() + " (" + e.getServer().getName() + ").");
    }

    @Handler
    public void disconnected(final ClientConnectionClosedEvent e) {
        this.humanity.getLogger().info("Disconnected. " + (e.isReconnecting() ? "R" : "Not r") + "econnecting.");
    }

    @Handler
    public void joining(final ChannelJoinEvent e) {
        if (!e.getActor().getNick().equals(e.getClient().getNick()) || e.getClient().getMessageDelay() == 1) return;
        e.getClient().setMessageDelay(1);
        this.humanity.getLogger().info("Set message delay to 1ms.");
    }

    @Handler
    public void onChannelMessage(final ChannelMessageEvent e) {
        final String message = e.getMessage();
        if (message.isEmpty() || message.charAt(0) != this.humanity.getPrefix()) return;
        final List<String> split = Splitter.on(' ').omitEmptyStrings().splitToList(message.trim());
        final String commandString = split.get(0).substring(1, split.get(0).length());
        final IRCCommand command = this.humanity.getCommandHandler().get(commandString);
        if (command == null) return;
        final IRCCommand.CommandType commandType = command.getCommandType();
        if (commandType != IRCCommand.CommandType.MESSAGE && commandType != IRCCommand.CommandType.BOTH) return;
        this.humanity.getLogger().info(e.getChannel().getName() + "/" + e.getActor().getNick() + ": " + e.getMessage());
        try {
            final List<String> args = split.subList(1, split.size());
            command.onCommand(e, new CallInfo(commandString, UsageType.MESSAGE), args.toArray(new String[args.size()]));
        } catch (final Throwable t) {
            t.printStackTrace();
            final StringBuilder sb = new StringBuilder("Unhandled command exception! ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            e.getActor().sendMessage(sb.toString());
            this.humanity.getLogger().warning(sb.toString());
        }
    }

    @Handler
    public void onConnect(final ClientConnectedEvent event) throws Exception {
        event.getClient().sendRawLine("/mode " + event.getClient().getNick() + " +B");
    }

    @Handler
    public void onInvite(final ChannelInviteEvent e) {
        e.getClient().addChannel(e.getChannel().getName());
        this.humanity.getLogger().info("Invited to " + e.getChannel().getName() + " by " + e.getActor().getName() + ".");
    }

    @Handler
    public void onJoin(final ChannelJoinEvent e) {
        if (e.getChannel().getNicknames().size() < 1) e.getChannel().part("Alone.");
        if (!e.getActor().getNick().equals(this.humanity.getBot().getNick())) return;
        this.humanity.getLogger().info("Joined " + e.getChannel().getName() + ".");
    }

    @Handler
    public void onKick(final ChannelKickEvent e) {
        this.humanity.getLogger().info("Kicked from " + e.getChannel().getName() + ".");
    }

    @Handler
    public void onPart(final ChannelPartEvent e) {
        if (e.getChannel().getNicknames().size() <= 2) e.getChannel().part("Alone.");
        if (!e.getActor().getNick().equals(this.humanity.getBot().getNick())) return;
        this.humanity.getLogger().info("Parted from " + e.getChannel().getName() + ".");
    }

    @Handler
    public void onPrivateMessage(final PrivateMessageEvent e) {
        final String message = e.getMessage();
        if (message.isEmpty()) return;
        final List<String> split = Splitter.on(' ').omitEmptyStrings().splitToList(message.trim());
        final String commandString = split.get(0);
        IRCCommand command = this.humanity.getCommandHandler().get(commandString);
        if (command == null) {
            command = this.humanity.getCommandHandler().get(commandString.substring(1));
        }
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
            final List<String> args = split.subList(1, split.size());
            command.onCommand(e, new CallInfo(commandString, CallInfo.UsageType.PRIVATE), args.toArray(new String[args.size()]));
        } catch (final Throwable t) {
            t.printStackTrace();
            final StringBuilder sb = new StringBuilder("Unhandled command exception! ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            e.getActor().sendMessage(sb.toString());
            this.humanity.getLogger().warning(sb.toString());
        }
    }

    @Handler
    public void whoX(final ChannelJoinEvent e) {
        if (!e.getActor().getNick().equals(e.getClient().getNick())) return;
        this.humanity.getWhoX().sendWhoX(e.getChannel());
    }

}
