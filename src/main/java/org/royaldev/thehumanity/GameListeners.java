package org.royaldev.thehumanity;

import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.QuitEvent;

public class GameListeners extends ListenerAdapter<PircBotX> {

    private final TheHumanity humanity;

    public GameListeners(final TheHumanity instance) {
        this.humanity = instance;
    }

    @Override
    public void onPart(final PartEvent<PircBotX> event) throws Exception {
        final User u = event.getUser();
        final Game g = this.humanity.getGameFor(u);
        if (g == null || !g.getChannel().getName().equalsIgnoreCase(event.getChannel().getName())) return;
        g.removePlayer(g.getPlayer(event.getUser()));
    }

    @Override
    public void onQuit(final QuitEvent<PircBotX> event) throws Exception {
        final User u = event.getUser();
        final Game g = this.humanity.getGameFor(u);
        if (g == null) return;
        g.removePlayer(g.getPlayer(event.getUser()));
    }
}
