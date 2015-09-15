package org.royaldev.thehumanity.commands;

import com.google.common.base.Preconditions;
import org.jetbrains.annotations.NotNull;
import org.kitteh.irc.client.library.element.User;

/**
 * A normal {@link IRCCommand} with a {@link #notice} method.
 */
public abstract class NoticeableCommand extends IRCCommand {

    /**
     * Sends a notice to the given User.
     *
     * @param u       User to send notice to
     * @param message Message to send
     */
    public void notice(@NotNull final User u, @NotNull final String message) {
        Preconditions.checkNotNull(u, "u was null");
        Preconditions.checkNotNull(message, "message was null");
        u.sendMessage(message);
    }

}
