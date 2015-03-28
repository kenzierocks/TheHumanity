package org.royaldev.thehumanity.cards;

import com.google.common.base.MoreObjects;
import org.royaldev.thehumanity.cards.types.BlackCard;
import org.royaldev.thehumanity.cards.types.WhiteCard;
import org.royaldev.thehumanity.player.Hand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deck class. A deck is a collection of {@link CardPack CardPacks}. When playing, the Deck pays no heed to which pack a
 * card belongs to when dealing.
 * <p/>
 * Each game should have one deck, which contains both white and black cards. Decks are repopulating,
 * meaning that if cards of either type run out, that type of card will be replenished from the card pack sources. This
 * happens automatically for white cards when drawing randomly. Black cards must be manually repopulated.
 */
public class Deck {

    private final List<CardPack> cardPacks = Collections.synchronizedList(new ArrayList<>());
    private final List<WhiteCard> whiteCards = Collections.synchronizedList(new ArrayList<>());
    private final List<BlackCard> blackCards = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new Deck with the given card packs as sources.
     *
     * @param cardPacks Card packs to add to this deck
     */
    public Deck(final Collection<CardPack> cardPacks) {
        synchronized (this.cardPacks) {
            this.cardPacks.addAll(cardPacks);
        }
        this.repopulateBlackCards();
        this.repopulateWhiteCards();
    }

    /**
     * Adds a CardPack to this Deck. All cards in the pack will be added to this Deck.
     *
     * @param cp CardPack to add
     * @return true if the pack was added, false if otherwise
     */
    public boolean addCardPack(final CardPack cp) {
        if (!this.cardPacks.add(cp)) return false;
        cp.getWhiteCards().forEach(this.whiteCards::add);
        cp.getBlackCards().forEach(this.blackCards::add);
        return true;
    }

    /**
     * Gets the total amount of black cards contained in this deck.
     *
     * @return Total amount of black cards
     */
    public int getBlackCardCount() {
        return this.cardPacks.stream().mapToInt(cp -> cp.getBlackCards().size()).sum();
    }

    /**
     * Gets the card packs that this Deck was created with.
     *
     * @return List of CardPacks
     */
    public List<CardPack> getCardPacks() {
        synchronized (this.cardPacks) {
            return new ArrayList<>(this.cardPacks);
        }
    }

    /**
     * Gets a random black card. If there are none left in this Deck, null will be returned. A manual repopulation can
     * be done using {@link #repopulateBlackCards()}, but the default game behavior is to end when black cards are
     * exhausted.
     *
     * @return A random black card or null
     */
    public BlackCard getRandomBlackCard() {
        synchronized (this.blackCards) {
            if (this.blackCards.size() < 1) return null;
            Collections.shuffle(this.blackCards);
            return this.blackCards.remove(0);
        }
    }

    /**
     * Gets a random white card. If there are none left in this Deck, the pile will be repopulated. An optional
     * parameter may be included to specify Hands containing cards that are not to be included when the pile is
     * repopulated. If it is null, the hand will be repopulated normally.
     *
     * @param repopulateExcludes Hands with cards not to include or null
     * @return A random white card
     */
    public WhiteCard getRandomWhiteCard(final Collection<Hand> repopulateExcludes) {
        synchronized (this.whiteCards) {
            if (this.whiteCards.size() < 1) this.repopulateWhiteCards(repopulateExcludes);
            Collections.shuffle(this.whiteCards);
            return this.whiteCards.remove(0);
        }
    }

    /**
     * Gets the amount of unused black cards contained in this deck.
     *
     * @return Total amount of unused black cards
     */
    public int getUnusedBlackCardCount() {
        return this.blackCards.size();
    }

    /**
     * Gets the amount of unused white cards contained in this deck.
     *
     * @return Total amount of unused white cards
     */
    public int getUnusedWhiteCardCount() {
        return this.whiteCards.size();
    }

    /**
     * Gets the total amount of white cards contained in this deck.
     *
     * @return Total amount of white cards
     */
    public int getWhiteCardCount() {
        return this.cardPacks.stream().mapToInt(cp -> cp.getWhiteCards().size()).sum();
    }

    /**
     * Removes a CardPack from this Deck, removing all the cards it has, as well.
     *
     * @param cp CardPack to remove
     * @return true if pack was removed, false if otherwise
     */
    public boolean removeCardPack(final CardPack cp) {
        return !(!this.cardPacks.contains(cp) || !this.cardPacks.remove(cp)) && this.whiteCards.removeAll(this.whiteCards.stream().filter(wc -> wc.getCardPack().equals(cp)).collect(Collectors.toCollection(ArrayList::new)));
    }

    /**
     * Adds all the black cards from the card packs back into the draw pile.
     */
    public void repopulateBlackCards() {
        synchronized (this.cardPacks) {
            synchronized (this.blackCards) {
                this.cardPacks.forEach(cp -> this.blackCards.addAll(cp.getBlackCards()));
            }
        }
    }

    /**
     * Adds all the white cards from the card packs back into the draw pile, excluding any in the given collection of
     * Hands.
     *
     * @param exclude Hands of Cards to exclude
     */
    public void repopulateWhiteCards(final Collection<Hand> exclude) {
        synchronized (this.cardPacks) {
            synchronized (this.whiteCards) {
                for (final CardPack cp : this.cardPacks) {
                    thisCard:
                    for (final WhiteCard wc : cp.getWhiteCards()) {
                        if (exclude != null && !exclude.isEmpty()) {
                            for (final Hand h : exclude) {
                                if (h.getCards().contains(wc)) continue thisCard;
                            }
                        }
                        this.whiteCards.add(wc);
                    }
                }
            }
        }
    }

    /**
     * Adds all the white cards from the card packs back into the draw pile.
     */
    public void repopulateWhiteCards() {
        this.repopulateWhiteCards(null);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("cardPacks", this.cardPacks)
            .toString();
    }

}
