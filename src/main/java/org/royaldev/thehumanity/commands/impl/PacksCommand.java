package org.royaldev.thehumanity.commands.impl;

import org.pircbotx.hooks.types.GenericMessageEvent;
import org.royaldev.thehumanity.TheHumanity;
import org.royaldev.thehumanity.cards.CardPack;
import org.royaldev.thehumanity.commands.CallInfo;
import org.royaldev.thehumanity.commands.Command;
import org.royaldev.thehumanity.commands.IRCCommand;

import java.text.DecimalFormat;
import java.util.Random;
import java.util.stream.Collectors;

@Command(
    name = "packs",
    description = "Shows the loaded card packs."
)
public class PacksCommand extends IRCCommand {

    private static final DecimalFormat df = new DecimalFormat("0.##%");
    private static final Random r = new Random();
    private final TheHumanity humanity;

    public PacksCommand(final TheHumanity instance) {
        this.humanity = instance;
    }

    @Override
    public void onCommand(final GenericMessageEvent event, final CallInfo ci, final String[] args) {
        final StringBuilder sb = new StringBuilder();
        final String allPackNames = this.humanity.getLoadedCardPacks().stream().map(CardPack::getName).sorted().collect(Collectors.joining());
        sb.append("# All card packs\n");
        sb.append("- ").append(
            this.humanity.getLoadedCardPacks().stream().map(CardPack::getName).collect(Collectors.joining("\n- "))
        );
        sb.append("\n\n# Individual card packs\n");
        for (final CardPack cp : this.humanity.getLoadedCardPacks()) {
            final int blackCards = cp.getBlackCards().size();
            final int whiteCards = cp.getWhiteCards().size();
            final int totalCards = blackCards + whiteCards;
            sb.append("## ").append(cp.getName()).append("\n");
            sb.append("**Total cards:** ").append(totalCards).append("  \n");
            sb.append("**Black cards:** ").append(blackCards).append(" (").append(PacksCommand.df.format((double) blackCards / (double) totalCards)).append(")  \n");
            sb.append("**White cards:** ").append(whiteCards).append(" (").append(PacksCommand.df.format((double) whiteCards / (double) totalCards)).append(")  \n");
            sb.append("**Random black card:** ").append("```").append(cp.getBlackCards().get(PacksCommand.r.nextInt(blackCards))).append("```  \n");
            sb.append("**Random white card:** ").append("```").append(cp.getWhiteCards().get(PacksCommand.r.nextInt(whiteCards))).append("```  \n");
        }
        event.respond(this.humanity.gist("packs", allPackNames, "packs.md", sb.toString()));
    }
}
