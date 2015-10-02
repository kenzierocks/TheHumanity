package org.royaldev.thehumanity.game;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kitteh.irc.client.library.IRCFormat;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;
import org.royaldev.thehumanity.TheHumanity;
import org.royaldev.thehumanity.cards.Deck;
import org.royaldev.thehumanity.cards.packs.CardPack;
import org.royaldev.thehumanity.cards.types.BlackCard;
import org.royaldev.thehumanity.cards.types.WhiteCard;
import org.royaldev.thehumanity.exceptions.MissingCzarException;
import org.royaldev.thehumanity.game.round.CurrentRound;
import org.royaldev.thehumanity.game.round.Round;
import org.royaldev.thehumanity.game.round.Round.RoundEndCause;
import org.royaldev.thehumanity.game.round.Round.RoundStage;
import org.royaldev.thehumanity.game.round.RoundSnapshot;
import org.royaldev.thehumanity.player.Hand;
import org.royaldev.thehumanity.player.Player;
import org.royaldev.thehumanity.util.DescendingValueComparator;
import org.royaldev.thehumanity.util.FakeUser;
import org.royaldev.thehumanity.util.Snapshottable;
import org.royaldev.thehumanity.util.json.JSONSerializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Game implements JSONSerializable, Snapshottable<GameSnapshot> {

    private final TheHumanity humanity;
    /**
     * A list of the current players in this game.
     */
    private final List<Player> players = Collections.synchronizedList(new ArrayList<>());
    /**
     * A list of all the players that have ever played in this game.
     */
    private final List<Player> historicPlayers = Collections.synchronizedList(new ArrayList<>());
    private final Deck deck;
    private final List<HouseRule> houseRules = Lists.newArrayList();
    private final List<RoundSnapshot> previousRounds = Lists.newArrayList();
    private final Player randoCardrissian = new Player(new FakeUser("Rando Cardrissian"));
    private Channel channel;
    private CurrentRound currentRound = null;
    private Player host = null;
    private ScheduledFuture countdownTask;
    private GameStatus gameStatus = GameStatus.IDLE;
    private boolean hostWasVoiced = false;
    private long startTime, endTime;
    private GameEndCause endCause = GameEndCause.NOT_ENDED;

    public Game(@NotNull final TheHumanity humanity, @NotNull final Channel channel, @NotNull final List<CardPack> cardPacks) {
        Preconditions.checkNotNull(humanity, "humanity was null");
        Preconditions.checkNotNull(channel, "channel was null");
        Preconditions.checkNotNull(cardPacks, "cardPacks was null");
        this.humanity = humanity;
        this.channel = channel;
        this.deck = new Deck(cardPacks);
        this.addHouseRule(HouseRule.REBOOTING_THE_UNIVERSE);
    }

    /**
     * Processes adding a house rule. Useful for setting up various aspects of the game to work with the rule.
     *
     * @param hr Rule being added
     */
    private void processAddingHouseRule(@NotNull final HouseRule hr) {
        Preconditions.checkNotNull(hr, "hr was null");
        switch (hr) {
            case RANDO_CARDRISSIAN:
                this.historicPlayers.add(this.randoCardrissian);
                break;
        }
    }

    /**
     * Adds a CardPack to this Game.
     *
     * @param cp CardPack to add.
     * @return true if pack was added, false if otherwise
     */
    public boolean addCardPack(@NotNull final CardPack cp) {
        Preconditions.checkNotNull(cp, "cp was null");
        return this.deck.addCardPack(cp);
    }

    /**
     * Adds a house rule to the game.
     *
     * @param rule Rule to add
     * @return true if rule was added, false if otherwise
     */
    public boolean addHouseRule(@NotNull final HouseRule rule) {
        Preconditions.checkNotNull(rule, "rule was null");
        if (this.hasHouseRule(rule)) return false;
        this.processAddingHouseRule(rule);
        return this.houseRules.add(rule);
    }

    /**
     * Adds a player to the game. If there are not enough white cards to deal this player in, the game will end. If this
     * Player has previously played, all cards and points will be restored to it.
     *
     * @param player Player to add to the game
     */
    public void addPlayer(@NotNull final Player player) {
        Preconditions.checkNotNull(player, "player was null");
        if (this.hasPlayer(player)) return;
        if (!this.setOldUserData(player)) {
            synchronized (this.players) {
                this.players.add(player);
            }
            synchronized (this.historicPlayers) {
                this.historicPlayers.add(player);
            }
        }
        this.update();
        final int totalCards = this.getDeck().getCardPacks().stream().mapToInt(cp -> cp.getWhiteCards().size()).sum();
        if (this.players.size() * 10 >= totalCards) {
            this.sendMessage(IRCFormat.BOLD + "Not enough white cards to play!");
            this.stop(GameEndCause.NOT_ENOUGH_WHITE_CARDS);
            return;
        }
        this.deal(player);
        if (this.gameStatus != GameStatus.JOINING) this.showCards(player);
        this.sendMessage(IRCFormat.BOLD + player.getUser().getNick() + IRCFormat.RESET + " has joined the game!");
    }

    /**
     * Advances the game stage. This should be called after every Round terminates.
     */
    public void advanceStage() {
        switch (this.gameStatus) {
            case IDLE:
                this.gameStatus = GameStatus.JOINING;
                break;
            case JOINING:
                this.gameStatus = GameStatus.PLAYING;
                break;
        }
        this.processStatus(this.gameStatus);
    }

    /**
     * Reformats a message to ensure no User in IRC is pinged by this message.
     *
     * @param message Message to reformat
     * @return Reformatted message
     */
    @NotNull
    public String antiPing(@NotNull String message) {
        Preconditions.checkNotNull(message, "message was null");
        for (final String nickname : this.channel.getNicknames()) {
            if (nickname.length() <= 1) continue;
            message = message.replace(nickname, nickname.substring(0, 1) + "\u200b" + nickname.substring(1));
        }
        return message;
    }

    /**
     * Creates a Player from a User.
     *
     * @param u User to create Player from
     * @return Player
     */
    @Nullable
    public Player createPlayer(@NotNull final User u) {
        Preconditions.checkNotNull(u, "u was null");
        if (this.hasPlayer(u.getNick())) return this.getPlayer(u.getNick());
        final Player p = new Player(u);
        this.addPlayer(p);
        return p;
    }

    /**
     * Deals cards to the given Player until it has ten in its hand.
     *
     * @param player Player to deal to
     */
    public void deal(@NotNull final Player player) {
        Preconditions.checkNotNull(player, "player was null");
        final Hand<WhiteCard> hand = player.getHand();
        while (hand.getSize() < 10) hand.addCard(this.getDeck().getRandomWhiteCard(null));
    }

    /**
     * Deals to each Player in the game until they all have ten cards.
     */
    public void deal() {
        synchronized (this.players) {
            this.players.stream().forEach(this::deal);
        }
    }

    /**
     * Creates a String with the current card counts.
     *
     * @return String
     */
    @NotNull
    public String getCardCounts() {
        return IRCFormat.BOLD + "Card counts: "
            + IRCFormat.RESET + this.getDeck().getUnusedBlackCardCount() + " unused/" + this.getDeck().getBlackCardCount() + " black cards, "
            + this.getDeck().getUnusedWhiteCardCount() + " unused/" + this.getDeck().getWhiteCardCount() + " white cards";
    }

    /**
     * Gets the Channel that this game is taking place in.
     *
     * @return Channel
     */
    @NotNull
    public Channel getChannel() {
        return this.channel;
    }

    /**
     * Sets the Channel that this Game is in.
     *
     * @param channel Channel
     */
    private void setChannel(@NotNull final Channel channel) {
        Preconditions.checkNotNull(channel, "channel was null");
        this.channel = channel;
    }

    /**
     * Gets the current Round.
     *
     * @return Round
     */
    @Nullable
    public CurrentRound getCurrentRound() {
        return this.currentRound;
    }

    /**
     * Gets the Deck being used for this game.
     *
     * @return Deck
     */
    @NotNull
    public Deck getDeck() {
        return this.deck;
    }

    @NotNull
    public GameEndCause getEndCause() {
        return this.endCause;
    }

    public void setEndCause(@NotNull final GameEndCause endCause) {
        Preconditions.checkNotNull(endCause, "endCause was null");
        this.endCause = endCause;
    }

    public long getEndTime() {
        return this.endTime;
    }

    /**
     * Gets the current status of this game.
     *
     * @return GameStatus
     */
    @NotNull
    public GameStatus getGameStatus() {
        return this.gameStatus;
    }

    /**
     * Sets the current status of this game.
     *
     * @param s Status to set
     */
    public void setGameStatus(final GameStatus s) {
        this.gameStatus = s;
    }

    /**
     * Returns the internal list of all players that have ever played in this game.
     *
     * @return List
     */
    @NotNull
    public List<Player> getHistoricPlayers() {
        return this.historicPlayers;
    }

    /**
     * Gets the host of this game.
     *
     * @return Player
     */
    @NotNull
    public Player getHost() {
        return this.host;
    }

    /**
     * Sets the host of this game. This player will be given voice.
     *
     * @param host Player to set as host
     */
    public void setHost(@NotNull final Player host) {
        Preconditions.checkNotNull(host, "host was null");
        this.host = host;
        this.hostWasVoiced = this.humanity.hasChannelMode(this.channel, this.getHost().getUser(), 'v');
        if (!this.hostWasVoiced) {
            this.getChannel().newModeCommand().addModeChange(true, 'v', this.getHost().getUser()).execute();
        }
        this.showHost();
    }

    /**
     * Gets a list of {@link HouseRule HouseRules} being used for this game.
     *
     * @return House rules
     */
    @NotNull
    public List<HouseRule> getHouseRules() {
        return Collections.unmodifiableList(this.houseRules);
    }

    /**
     * Gets the instance of the bot that this game is running under.
     *
     * @return TheHumanity
     */
    @NotNull
    public TheHumanity getHumanity() {
        return this.humanity;
    }

    /**
     * Gets a Player for the given User. If there is no corresponding Player, null is returned.
     *
     * @param u User to get Player for
     * @return Corresponding Player
     */
    @Nullable
    public Player getPlayer(final User u) {
        if (u == null) return null;
        return this.getPlayers().stream()
            .filter(p -> this.humanity.usersMatch(u, p.getUser()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Gets a player in this game from the list of players.
     *
     * @param p Player to get
     * @return Player or null
     */
    @Nullable
    public Player getPlayer(final Player p) {
        if (p == null) return null;
        final int index = this.getPlayers().indexOf(p);
        if (index < 0) return null;
        return this.getPlayers().get(index);
    }

    /**
     * Gets a Player in this game based on its name.
     *
     * @param name Name of the Player to retrieve
     * @return Player or null
     */
    @Nullable
    public Player getPlayer(final String name) {
        if (name == null) return null;
        return this.getPlayer(
            this.channel.getUsers().stream()
                .filter(user -> user.getNick().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null)
        );
    }

    /**
     * Gets all players in this game.
     *
     * @return List&lt;Player&gt;
     */
    @NotNull
    public List<Player> getPlayers() {
        synchronized (this.players) {
            return new ArrayList<>(this.players);
        }
    }

    /**
     * Gets an unmodifiable list of previous rounds as snapshots.
     *
     * @return Unmodifiable list of previous round snapshots
     */
    public List<RoundSnapshot> getPreviousRounds() {
        return Collections.unmodifiableList(this.previousRounds);
    }

    /**
     * Gets the Player that represents Rando Cardrissian. If Rando Cardrissian is not enabled, this will return null.
     *
     * @return Player or null
     */
    @Nullable
    public Player getRandoCardrissian() {
        if (!this.hasHouseRule(HouseRule.RANDO_CARDRISSIAN)) return null;
        return this.randoCardrissian;
    }

    public long getStartTime() {
        return this.startTime;
    }

    /**
     * Checks to see if the game has enough players to continue. If it does not, the game will end.
     *
     * @return true if enough players, false if otherwise
     */
    public boolean hasEnoughPlayers() {
        if (this.getPlayers().size() < 3) {
            this.sendMessage(IRCFormat.BOLD + "Not enough players to continue!");
            this.stop(GameEndCause.NOT_ENOUGH_PLAYERS);
            return false;
        }
        return true;
    }

    /**
     * Convenience method to check if this game has a house rule in effect.
     *
     * @param rule Rule to check
     * @return true if the rule is being used, false if otherwise
     */
    public boolean hasHouseRule(final HouseRule rule) {
        return this.getHouseRules().contains(rule);
    }

    /**
     * Checks to see if the given player name is in this game.
     *
     * @param name Name to check
     * @return true if name is in the game, false if otherwise
     */
    public boolean hasPlayer(final String name) {
        return this.getPlayer(name) != null;
    }

    /**
     * Checks to see if the given Player is in this game.
     *
     * @param p Player to check
     * @return true if player is in the game, false if otherwise
     */
    public boolean hasPlayer(final Player p) {
        return this.players.contains(p);
    }

    /**
     * Devoices the current host and sets a new host (voicing him).
     */
    public void nextHost() {
        if (this.host != null && !this.hostWasVoiced) {
            this.getChannel().newModeCommand().addModeChange(false, 'v', this.host.getUser()).execute();
            this.host = null;
        }
        synchronized (this.players) {
            if (this.players.size() < 1) return; // should never happen without the game stopping
            this.setHost(this.players.get(0));
        }
    }

    /**
     * Processes steps to take with the given GameStatus.
     *
     * @param newStatus New status of the game.
     */
    public void processStatus(final GameStatus newStatus) {
        if (newStatus == GameStatus.IDLE) return;
        else if (newStatus == GameStatus.JOINING) {
            this.countdownTask = this.humanity.getThreadPool().scheduleAtFixedRate(new GameCountdown(), 0L, 15L, TimeUnit.SECONDS);
        }
        switch (newStatus) {
            case JOINING:
                this.startTime = System.currentTimeMillis();
                final StringBuilder sb = new StringBuilder();
                sb.append(IRCFormat.BOLD).append("Card packs for this game:").append(IRCFormat.RESET).append(" ");
                this.getDeck().getCardPacks().stream().forEach(cp -> sb.append(cp.getName()).append(", "));
                this.sendMessage(IRCFormat.BOLD + "A new game is starting!");
                this.sendMessage(sb.toString().substring(0, sb.length() - 2));
                this.showCardCounts();
                this.sendMessage("Use " + IRCFormat.BOLD + this.humanity.getPrefix() + "join" + IRCFormat.RESET + " to join.");
                break;
            case PLAYING:
                if (!this.hasEnoughPlayers()) return;
                final CurrentRound currentRound = this.getCurrentRound();
                final boolean hadRound = currentRound != null;
                if (hadRound) {
                    this.previousRounds.add(currentRound.takeSnapshot());
                    // this.showScores();
                    // this.showCardCounts();
                }
                int index = !hadRound ? 0 : this.getPlayers().indexOf(currentRound.getCzar()) + 1;
                if (index >= this.getPlayers().size()) index = 0;
                BlackCard blackCard = null;
                do {
                    if (blackCard != null) {
                        this.sendMessage("Black card " + IRCFormat.BOLD + blackCard.getText() + IRCFormat.RESET + " was skipped because it is invalid.");
                    }
                    blackCard = this.getDeck().getRandomBlackCard();
                    if (blackCard == null) {
                        this.sendMessage(" ");
                        this.sendMessage(IRCFormat.BOLD + "There are no more black cards!");
                        this.stop(GameEndCause.RAN_OUT_OF_BLACK_CARDS);
                        return;
                    }
                } while (blackCard.getBlanks() > 10 || blackCard.getBlanks() < 1);
                if (hadRound) currentRound.cancelReminderTask();
                this.currentRound = new CurrentRound(this, !hadRound ? 1 : currentRound.getNumber() + 1, blackCard, this.hasHouseRule(HouseRule.GOD_IS_DEAD) ? null : this.getPlayers().get(index));
                this.deal();
                // LESS SPAM MODE.
                // this.sendMessage(" ");
                // this.sendMessage(IRCFormat.BOLD + "Round " + this.getCurrentRound().getNumber() + IRCFormat.RESET + "!");
                if (!this.hasHouseRule(HouseRule.GOD_IS_DEAD)) {
                    final Player czar = this.getCurrentRound().getCzar();
                    if (czar == null) {
                        throw new MissingCzarException();
                    }
                    this.sendMessage(IRCFormat.BOLD + czar.getUser().getNick() + IRCFormat.RESET + " is the card czar.");
                }
                this.sendMessage(IRCFormat.BOLD + this.getCurrentRound().getBlackCard().getText());
                this.getCurrentRound().advanceStage();
                break;
        }
    }

    /**
     * Removes a CardPack from this Game. If sweep is true, any cards in players' hands will be removed if they belonged
     * to the removed pack. If cards are removed, new cards will be dealt, and the affected players will have their
     * hands shown to them.
     *
     * @param cp    CardPack to remove
     * @param sweep Remove cards in hands?
     * @return true if the pack was removed, false if otherwise
     */
    public boolean removeCardPack(final CardPack cp, final boolean sweep) {
        if (!this.deck.removeCardPack(cp)) return false;
        if (!sweep) return true;
        this.historicPlayers.forEach(p -> {
            if (!p.getHand().removeCards(cp.getWhiteCards())) return;
            this.deal(p);
            p.getUser().sendMessage("Your hand has changed as a result of card pack changes. Here's your new hand!");
            this.showCards(p);
        });
        return true;
    }

    /**
     * Removes a house rule from the game.
     *
     * @param rule Rule to remove
     * @return true if rule was removed, false if otherwise
     */
    public boolean removeHouseRule(final HouseRule rule) {
        return this.hasHouseRule(rule) && this.houseRules.remove(rule);
    }

    /**
     * Removes a Player from this game. If there are not enough Players to continue, the game will end.
     *
     * @param p Player to remove
     */
    public void removePlayer(final Player p) {
        Preconditions.checkNotNull(p, "p was null");
        synchronized (this.players) {
            if (!this.players.remove(p)) return;
        }
        this.sendMessage(IRCFormat.BOLD + p.getUser().getNick() + IRCFormat.RESET + " has left the game.");
        if (this.host.equals(p)) this.nextHost();
        this.update();
        if (this.getCurrentRound() != null) {
            if (!this.hasEnoughPlayers()) return;
            if (p.equals(this.getCurrentRound().getCzar())) {
                this.sendMessage(IRCFormat.BOLD + "The czar has left!" + IRCFormat.RESET + " Returning your cards and starting a new round.");
                this.getCurrentRound().returnCards();
                this.getCurrentRound().setEndCause(RoundEndCause.CZAR_LEFT);
                this.advanceStage();
                return;
            }
            if (this.getCurrentRound().hasAllPlaysMade() && this.getCurrentRound().getCurrentStage() == RoundStage.WAITING_FOR_PLAYERS) {
                this.getCurrentRound().advanceStage();
            }
        }
    }

    /**
     * Removes a Player from this game given his name.
     *
     * @param name Name of Player to remove
     */
    public void removePlayer(@NotNull final String name) {
        Preconditions.checkNotNull(name, "name was null");
        this.removePlayer(this.getPlayer(name));
    }

    /**
     * Sends a message parsed through {@link #antiPing(String)} to the channel.
     *
     * @param message Message to send
     */
    public void sendMessage(final String message) {
        this.channel.sendMessage(this.antiPing(message));
    }

    /**
     * Adds old data about a player to a rejoining player.
     *
     * @param newPlayer Player that is rejoining
     * @return true if data was applied, false if otherwise
     */
    public boolean setOldUserData(final Player newPlayer) {
        final Player oldPlayer;
        synchronized (this.historicPlayers) {
            if (this.historicPlayers.contains(newPlayer)) {
                oldPlayer = this.historicPlayers.get(this.historicPlayers.indexOf(newPlayer));
            } else return false;
        }
        final Hand<WhiteCard> hand = newPlayer.getHand();
        hand.clearHand();
        hand.addCards(oldPlayer.getHand().getCards());
        newPlayer.clearWins();
        oldPlayer.getWins().forEach(newPlayer::addWin);
        synchronized (this.players) {
            this.players.add(oldPlayer);
        }
        return true;
    }

    /**
     * Displays the current card counts.
     */
    public void showCardCounts() {
        this.sendMessage(this.getCardCounts());
    }

    /**
     * Shows a Player his cards.
     *
     * @param p Player to show cards to
     */
    public void showCards(@NotNull final Player p) {
        Preconditions.checkNotNull(p, "p was null");
        if (!getCurrentRound().getCzar().equals(p)) {
            p.getUser().sendMessage(IRCFormat.BOLD + this.getCurrentRound().getBlackCard().getText());
        }
        final StringBuilder sb = new StringBuilder();
        final Hand<WhiteCard> hand = p.getHand();
        for (int i = 0; i < hand.getSize(); i++) {
            final WhiteCard wc = hand.getCard(i);
            sb.append(i + 1).append(". ").append(IRCFormat.BOLD).append(wc.getText()).append(IRCFormat.RESET).append(" ");
        }
        p.getUser().sendMessage(sb.toString());
    }

    /**
     * Shows each Player his hand.
     */
    public void showCards() {
        final Round currentRound = this.getCurrentRound();
        if (currentRound == null) return;
        this.players.stream().filter(p -> !p.equals(currentRound.getCzar())).forEach(this::showCards);
    }

    /**
     * Sends a message to the game channel, declaring who the host is.
     */
    public void showHost() {
        this.sendMessage("The host is " + IRCFormat.BOLD + this.host.getUser().getNick() + IRCFormat.RESET + ".");
    }

    /**
     * Shows the ordered scores in the game channel.
     */
    public void showScores() {
        final Map<Player, Integer> scores = new HashMap<>();
        synchronized (this.historicPlayers) {
            this.historicPlayers.stream().forEach(p -> scores.put(p, p.getScore()));
        }
        final Map<Player, Integer> sortedScores = new TreeMap<>(new DescendingValueComparator<>(scores));
        sortedScores.putAll(scores);
        final StringBuilder sb = new StringBuilder();
        sb.append(IRCFormat.BOLD).append("Scores:").append(IRCFormat.RESET).append(" ");
        for (final Map.Entry<Player, Integer> entry : sortedScores.entrySet()) {
            sb.append(entry.getKey().getUser().getNick()).append(": ").append(entry.getValue() == null ? 0 : entry.getValue()).append(", ");
        }
        this.sendMessage(sb.toString().substring(0, sb.length() - 2));
    }

    /**
     * Skips the countdown to start this Game and immediately starts the Game.
     *
     * @return true if countdown was skipped, false if otherwise
     */
    public boolean skipCountdown() {
        if (this.countdownTask == null || this.countdownTask.isCancelled() || this.countdownTask.isDone()) return false;
        if (this.getPlayers().size() < 3) return false;
        this.advanceStage();
        this.countdownTask.cancel(false);
        return true;
    }

    /**
     * Starts the game.
     */
    public void start() {
        if (this.gameStatus != GameStatus.IDLE) return;
        this.advanceStage();
    }

    /**
     * Stops the game.
     */
    public void stop(@NotNull final GameEndCause endCause) {
        this.setEndCause(endCause);
        if (this.getCurrentRound() != null) {
            this.getCurrentRound().setEndTime(System.currentTimeMillis());
            this.getCurrentRound().setEndCause(RoundEndCause.GAME_ENDED);
            this.previousRounds.add(this.getCurrentRound().takeSnapshot());
        }
        this.humanity.getGames().remove(this.channel);
        if (this.host != null && !this.hostWasVoiced) {
            this.getChannel().newModeCommand().addModeChange(false, 'v', this.host.getUser()).execute();
        }
        if (this.countdownTask != null) this.countdownTask.cancel(false);
        if (this.getCurrentRound() != null) {
            this.getCurrentRound().cancelReminderTask();
        }
        if (this.gameStatus != GameStatus.IDLE) {
            this.gameStatus = GameStatus.IDLE;
            this.sendMessage(IRCFormat.BOLD + "The game has ended.");
            if (this.gameStatus != GameStatus.JOINING) this.showScores();
        }
        this.endTime = System.currentTimeMillis();
        this.gameStatus = GameStatus.ENDED;
        this.humanity.getHistory().saveGameSnapshot(this.takeSnapshot());
    }

    @NotNull
    @Override
    public GameSnapshot takeSnapshot() {
        return new GameSnapshot(
            this.getChannel().getName(),
            this.endCause.name(),
            this.startTime,
            this.endTime,
            this.getPlayers().stream().map(p -> p.getUser().getNick()).collect(Collectors.toList()),
            this.getHistoricPlayers().stream().map(p -> p.getUser().getNick()).collect(Collectors.toList()),
            this.getHouseRules().stream().map(HouseRule::getFriendlyName).collect(Collectors.toList()),
            this.getDeck().getCardPacks().stream().map(CardPack::getName).collect(Collectors.toList()),
            this.getPreviousRounds(),
            this.getHistoricPlayers().stream().collect(Collectors.toMap(p -> p.getUser().getNick(), Player::getScore)),
            this.getHost().getUser().getNick(),
            this.getCurrentRound() == null ? 0 : this.getCurrentRound().getNumber()
        );
    }

    @NotNull
    @Override
    public String toJSON() {
        return this.takeSnapshot().toJSON();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("historicPlayers", this.historicPlayers)
            .add("players", this.players)
            .add("deck", this.deck)
            .add("houseRules", this.houseRules)
            .add("channel", this.channel)
            .add("currentRound", this.currentRound)
            .add("host", this.host)
            .add("gameStatus", this.gameStatus)
            .toString();
    }

    /**
     * Updates the Channel and Users.
     */
    public void update() {
        this.updateChannel();
        this.updateUsers();
    }

    /**
     * Updates the stored Channel snapshot.
     */
    public void updateChannel() {
        this.setChannel(
            this.humanity.getBot().getChannels().stream()
                .filter(c -> c.getName().equalsIgnoreCase(this.getChannel().getName()))
                .findFirst()
                .orElseThrow(IllegalStateException::new)
        );
    }

    /**
     * Updates the Users stored in all Players.
     */
    public void updateUsers() {
        this.getHistoricPlayers().stream()
            .forEach(p -> {
                    final User newUser = this.channel.getUsers().stream()
                        .filter(u -> u.getNick().equalsIgnoreCase(p.getUser().getNick()))
                        .findFirst()
                        .orElse(null);
                    if (newUser == null) return;
                    p.setUser(newUser);
                }
            );

    }

    public enum GameStatus {
        /**
         * The game is not started. Players are not joining. Nothing is happening.
         */
        IDLE,
        /**
         * The game is started. Players are joining.
         */
        JOINING,
        /**
         * The game is now in play. Cards are being played.
         */
        PLAYING,
        /**
         * The game has terminated. Players are not joining. Nothing is happening.
         */
        ENDED
    }

    public enum GameEndCause {
        NOT_ENDED,
        NOT_ENOUGH_PLAYERS,
        RAN_OUT_OF_BLACK_CARDS,
        NOT_ENOUGH_WHITE_CARDS,
        STOPPED_BY_COMMAND,
        JAVA_SHUTDOWN
    }

    private class GameCountdown implements Runnable {

        private int runCount = 3; // 3 default

        @Override
        public void run() {
            final int seconds = this.runCount * 15;
            if (seconds > 0) {
                Game.this.sendMessage(IRCFormat.BOLD.toString() + (this.runCount * 15) + IRCFormat.RESET + " seconds remain to join the game!");
            }
            if (this.runCount-- < 1) {
                if (Game.this.getPlayers().size() >= 3) {
                    Game.this.advanceStage();
                } else {
                    Game.this.sendMessage(IRCFormat.BOLD + "Not enough players." + IRCFormat.RESET + " At least three people are required for the game to begin.");
                    Game.this.stop(GameEndCause.NOT_ENOUGH_PLAYERS);
                }
                Game.this.countdownTask.cancel(false);
            }
        }
    }

}
