package org.royaldev.thehumanity.cards;

import org.pircbotx.Colors;
import org.royaldev.thehumanity.cards.types.WhiteCard;
import org.royaldev.thehumanity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * A play. A play contains all the {@link org.royaldev.thehumanity.cards.types.WhiteCard WhiteCards} that a player used
 * to answer a {@link org.royaldev.thehumanity.cards.types.BlackCard BlackCard}.
 */
public class Play {

    private final Player player;
    private final List<WhiteCard> whiteCards = new ArrayList<>();

    /**
     * Constructs a new play for the given player, using the given cards.
     *
     * @param player     Player that played the cards
     * @param whiteCards Cards the player played
     */
    public Play(final Player player, final List<WhiteCard> whiteCards) {
        this.player = player;
        this.whiteCards.addAll(whiteCards);
    }

    /**
     * Gets the player that made this play.
     *
     * @return Player
     */
    public Player getPlayer() {
        return this.player;
    }

    /**
     * Gets the white cards used in this play.
     *
     * @return White cards
     */
    public List<WhiteCard> getWhiteCards() {
        return this.whiteCards;
    }

    /**
     * Returns a comma-delimited list of the white cards in this play, using their
     * {@link org.royaldev.thehumanity.cards.types.WhiteCard@getText getText()} method.
     * <p/>
     * <strong>Note</strong> that IRC colors are included in this representation.
     *
     * @return Comma-delimited String
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (final WhiteCard wc : this.getWhiteCards()) {
            sb.append(Colors.BOLD).append(wc.getText()).append(Colors.NORMAL).append(", ");
        }
        return sb.substring(0, sb.length() - 2);
    }
}
