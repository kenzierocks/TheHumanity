package org.royaldev.thehumanity.commands.impl;

import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.ActorEvent;
import org.royaldev.thehumanity.Game;
import org.royaldev.thehumanity.TheHumanity;
import org.royaldev.thehumanity.commands.CallInfo;
import org.royaldev.thehumanity.commands.Command;
import org.royaldev.thehumanity.commands.InGameCommand;

@Command(
    name = "cardcounts",
    description = "Displays the current amount of cards in the deck.",
    aliases = {"cardcount", "cc"},
    usage = "<command> (public)"
)
public class CardCountsCommand extends InGameCommand {

    public CardCountsCommand(final TheHumanity humanity) {
        super(humanity);
    }

    @Override
    public void onInGameCommand(final ActorEvent<User> event, final CallInfo ci, final Game g, final String[] args) {
        final User u = event.getActor();
        if (args.length > 0 && "public".equalsIgnoreCase(args[0])) {
            g.showCardCounts();
        } else {
            this.notice(u, g.getCardCounts());
        }
    }
}
