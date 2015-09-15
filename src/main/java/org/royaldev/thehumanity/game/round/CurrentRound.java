package org.royaldev.thehumanity.game.round;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kitteh.irc.client.library.IRCFormat;
import org.royaldev.thehumanity.cards.play.Play;
import org.royaldev.thehumanity.cards.types.BlackCard;
import org.royaldev.thehumanity.cards.types.WhiteCard;
import org.royaldev.thehumanity.game.Game;
import org.royaldev.thehumanity.game.HouseRule;
import org.royaldev.thehumanity.player.Player;
import org.royaldev.thehumanity.util.Snapshottable;
import org.royaldev.thehumanity.util.json.JSONSerializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Represents a single round of the game.
 */
public class CurrentRound implements Round, JSONSerializable, Snapshottable<RoundSnapshot> {

    private final Game game;
    private final int number;
    private final BlackCard blackCard;
    private final Player czar;
    private final Set<Player> skippedPlayers = Collections.synchronizedSet(new HashSet<>());
    private final List<Play> plays = Collections.synchronizedList(new ArrayList<>());
    private final Multiset<Play> votes = HashMultiset.create();
    private final Set<Player> voters = new HashSet<>();
    private ScheduledFuture reminderTask;
    private RoundStage currentStage = RoundStage.IDLE;
    private Play winningPlay;
    private long startTime, endTime;
    private RoundEndCause endCause = RoundEndCause.NOT_ENDED;

    /**
     * Creates a new round for the given game.
     *
     * @param game      Game the round belongs to
     * @param number    Round number
     * @param blackCard The black card used for this round
     * @param czar      The czar, or null if playing a mode with no czar
     */
    public CurrentRound(@NotNull final Game game, final int number, @NotNull final BlackCard blackCard, @Nullable final Player czar) {
        Preconditions.checkNotNull(game, "game was null");
        Preconditions.checkNotNull(blackCard, "blackCard was null");
        this.game = game;
        this.number = number;
        this.blackCard = blackCard;
        this.czar = czar;
    }

    /**
     * Makes a reminder for the czar. The reminder will ping the czar after 45 seconds, and continually every 22.5
     * seconds after the initial period. This will return null if there is no czar. The task is canceled by a listener,
     * which is fired when the czar speaks.
     *
     * @return ScheduledFuture or null
     */
    @Nullable
    private ScheduledFuture makeReminderTask() {
        final Player czar = this.getCzar();
        if (czar == null) {
            return null;
        }
        return this.getGame().getHumanity().getThreadPool().scheduleAtFixedRate(
            () -> this.getGame().getChannel().sendMessage(czar.getUser().getNick() + ": Wake up! You're the czar!"),
            45000L,
            22500L,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Processes tasks that house rules need to complete.
     */
    private void processHouseRules() {
        if (this.currentStage != RoundStage.WAITING_FOR_PLAYERS) return;
        if (this.game.hasHouseRule(HouseRule.RANDO_CARDRISSIAN)) {
            final List<WhiteCard> randoPlay = new ArrayList<>();
            for (int i = 0; i < this.blackCard.getBlanks(); i++) {
                randoPlay.add(this.game.getDeck().getRandomWhiteCard(null));
            }
            this.addPlay(new Play(this.game.getRandoCardrissian(), randoPlay));
        }
        if (this.game.hasHouseRule(HouseRule.PACKING_HEAT)) {
            if (this.getBlackCard().getBlanks() > 1) {
                this.game.getPlayers().stream().filter(p -> !p.equals(this.getCzar())).forEach(p -> p.getHand().addCard(this.game.getDeck().getRandomWhiteCard(null)));
            }
        }
    }

    /**
     * Processes various tasks for each new stage the round enters.
     */
    private void processStage() {
        if (!this.getGame().hasEnoughPlayers()) return;
        switch (this.currentStage) {
            case WAITING_FOR_PLAYERS:
                this.startTime = System.currentTimeMillis();
                this.processHouseRules();
                this.game.showCards();
                break;
            case WAITING_FOR_CZAR:
                Collections.shuffle(this.plays);
                if (this.game.hasHouseRule(HouseRule.GOD_IS_DEAD)) {
                    this.displayPlays();
                    this.getGame().sendMessage("Send " + IRCFormat.BOLD + this.getGame().getHumanity().getPrefix() + "pick" + IRCFormat.RESET + " followed by the number you think should win.");
                }
                final Player czar = this.getCzar();
                if (czar == null) break;
                this.displayPlays();
                this.getGame().sendMessage(IRCFormat.BOLD + czar.getUser().getNick() + IRCFormat.RESET + " is picking a winner.");
                czar.getUser().sendMessage("Send " + IRCFormat.BOLD + this.getGame().getHumanity().getPrefix() + "pick" + IRCFormat.RESET + " followed by the number you think should win.");
                this.reminderTask = this.makeReminderTask();
                break;
            case ENDED:
                this.endTime = System.currentTimeMillis();
                this.getGame().advanceStage();
                break;
        }
    }

    /**
     * Adds a play to this round.
     *
     * @param play Play to add
     */
    public void addPlay(@NotNull final Play play) {
        Preconditions.checkNotNull(play, "play was null");
        synchronized (this.plays) {
            this.plays.add(play);
        }
        play.getWhiteCards().stream().forEach(play.getPlayer().getHand()::removeCard);
        if (this.hasAllPlaysMade()) this.advanceStage();
    }

    /**
     * Adds a vote for a choice in the God is Dead house rule mode.
     *
     * @param player Player voting
     * @param index  Number of the play that is being voted for
     * @return true if vote was successful, false if not
     */
    public boolean addVote(@NotNull final Player player, int index) {
        Preconditions.checkNotNull(player, "player was null");
        if (this.hasVoted(player)) return false;
        index--;
        if (index < 0 || index >= this.getPlays().size()) return false;
        this.voters.add(player);
        final Play p = this.getPlays().get(index);
        this.votes.add(p);
        if (this.votes.size() >= this.getGame().getPlayers().size()) {
            final Play winner = this.getMostVoted();
            final int winningIndex = this.plays.indexOf(winner);
            this.chooseWinningPlay(winningIndex + 1);
        }
        return true;
    }

    /**
     * Progresses the stage to the next following stage.
     */
    public void advanceStage() {
        switch (this.currentStage) {
            case IDLE:
                this.currentStage = RoundStage.WAITING_FOR_PLAYERS;
                break;
            case WAITING_FOR_PLAYERS:
                this.currentStage = RoundStage.WAITING_FOR_CZAR;
                break;
            case WAITING_FOR_CZAR:
                this.currentStage = RoundStage.ENDED;
                break;
        }
        this.processStage();
    }

    /**
     * Cancels the reminder for the czar, if one has been started and it is not canceled or finished. This is always
     * safe to call.
     */
    public void cancelReminderTask() {
        if (this.reminderTask == null || this.reminderTask.isCancelled() || this.reminderTask.isDone()) {
            return;
        }
        this.reminderTask.cancel(true);
    }

    /**
     * Chooses the winning play for this round. This ends the round.
     *
     * @param index Index of the winning play (starting at 1)
     */
    public void chooseWinningPlay(int index) {
        this.cancelReminderTask();
        index--;
        if (index < 0 || index >= this.getPlays().size()) return;
        final Play p = this.winningPlay = this.getPlays().get(index);
        p.getPlayer().addWin(this.getBlackCard());
        this.getGame().sendMessage(IRCFormat.RESET + "Play " + IRCFormat.BOLD + (index + 1) + IRCFormat.RESET + " by " + IRCFormat.BOLD + p.getPlayer().getUser().getNick() + IRCFormat.RESET + " wins!");
        this.setEndCause(RoundEndCause.CZAR_CHOSE_WINNER);
        this.advanceStage();
    }

    /**
     * Displays all the plays made this round (without player names).
     */
    public void displayPlays() {
        for (int i = 0; i < this.getPlays().size(); i++) {
            final Play p = this.getPlays().get(i);
            this.getGame().sendMessage((i + 1) + ". " + this.getBlackCard().fillInBlanks(p));
            this.getCzar().getUser().sendMessage((i + 1) + ". " + this.getBlackCard().fillInBlanks(p));
        }
    }

    /**
     * Gets the plays that have been made by players still taking part in the game. Modifying this list will not change
     * it in the round.
     *
     * @return Cloned list of active-player plays
     */
    @NotNull
    public List<Play> getActivePlayerPlays() { // TODO: Rename method?
        return this.plays.stream().filter(p -> this.getGame().getPlayers().contains(p.getPlayer())).collect(Collectors.toList());
    }

    /**
     * Gets the black card for this round.
     *
     * @return Black card
     */
    @Override
    @NotNull
    public BlackCard getBlackCard() {
        return this.blackCard;
    }

    /**
     * Gets the czar for this round. This may change if the czar leaves.
     *
     * @return Czar
     */
    @Override
    @Nullable
    public Player getCzar() {
        return this.czar;
    }

    /**
     * Gets the play that has the highest amount of votes in the God is Dead house rule mode. Ties are handled by
     * returning the play that was first in the iteration.
     *
     * @return Play that had the highest amount of votes
     */
    @Override
    public Play getMostVoted() {
        return Multisets.copyHighestCountFirst(this.votes).iterator().next();
    }

    /**
     * Gets the number of this round.
     *
     * @return Number
     */
    @Override
    public int getNumber() {
        return this.number;
    }

    /**
     * Gets all of the plays that have been made this round. This is a copy of the list, not the real list. Modifying it
     * will not change it in the round.
     *
     * @return Cloned list of plays
     */
    @Override
    @NotNull
    public List<Play> getPlays() {
        synchronized (this.plays) {
            return new ArrayList<>(this.plays);
        }
    }

    /**
     * Gets all the skipped players for this round. Returns the actual list. Modifying the list returned will modify the
     * round.
     *
     * @return List of skipped players
     */
    @Override
    @NotNull
    public Set<Player> getSkippedPlayers() {
        synchronized (this.skippedPlayers) {
            return this.skippedPlayers;
        }
    }

    /**
     * Gets the current stage this round is in.
     *
     * @return RoundStage
     */
    @NotNull
    public RoundStage getCurrentStage() {
        return this.currentStage;
    }

    @NotNull
    public RoundEndCause getEndCause() {
        return this.endCause;
    }

    public void setEndCause(@NotNull final RoundEndCause endCause) {
        Preconditions.checkNotNull(endCause, "endCause was null");
        this.endCause = endCause;
    }

    /**
     * Gets the game that this round is associated with.
     *
     * @return Game
     */
    @NotNull
    public Game getGame() {
        return this.game;
    }

    /**
     * Checks if this round has all necessary plays made to advance the stage (meaning that every player has played).
     * Skipped players are ignored.
     *
     * @return true if all plays have been made, false if otherwise
     */
    public boolean hasAllPlaysMade() {
        final List<Player> usersTakingPart = new ArrayList<>(this.getGame().getPlayers());
        usersTakingPart.removeAll(this.getSkippedPlayers());
        return this.getActivePlayerPlays().size() >= usersTakingPart.size() - (this.getGame().hasHouseRule(HouseRule.GOD_IS_DEAD) ? 0 : 1);
    }

    /**
     * Checks if the given player has played in this round.
     *
     * @param p Player to check
     * @return true if played,false if otherwise
     */
    public boolean hasPlayed(@NotNull final Player p) {
        Preconditions.checkNotNull(p, "p was null");
        return this.plays.stream().anyMatch(play -> play.getPlayer().equals(p));
    }

    public boolean hasVoted(@NotNull final Player player) {
        Preconditions.checkNotNull(player, "player was null");
        return this.voters.contains(player);
    }

    /**
     * Checks if the given player is skipped.
     *
     * @param p Player to check
     * @return true if skipped, false if otherwise
     */
    public boolean isSkipped(@NotNull final Player p) {
        Preconditions.checkNotNull(p, "p was null");
        return this.getSkippedPlayers().contains(p);
    }

    /**
     * Returns all the played cards back to the hands of the players.
     */
    public void returnCards() {
        this.getPlays().stream().forEach(p -> p.getWhiteCards().stream().forEach(p.getPlayer().getHand()::addCard));
    }

    public void setEndTime(final long endTime) {
        this.endTime = endTime;
    }

    public void setStartTime(final long startTime) {
        this.startTime = startTime;
    }

    /**
     * Skips a player. A skipped player will not need to submit a play in order for the stage to advance.
     *
     * @param p Player to skip
     */
    public boolean skip(@NotNull final Player p) {
        Preconditions.checkNotNull(p, "p was null");
        if (this.isSkipped(p)) return false;
        // If the total amount of players less the skipped players is less than the amount needed to play, don't skip.
        // However, if this person is the czar, it's fine.
        if (!p.equals(this.getCzar()) && this.getGame().getPlayers().size() - this.skippedPlayers.size() < 3) {
            return false;
        }
        synchronized (this.skippedPlayers) {
            this.skippedPlayers.add(p);
        }
        switch (this.getCurrentStage()) {
            case WAITING_FOR_PLAYERS:
                if (this.hasAllPlaysMade()) this.advanceStage();
                break;
            case WAITING_FOR_CZAR:
                if (p.equals(this.getCzar())) {
                    this.getGame().sendMessage(IRCFormat.BOLD + "The czar has been skipped!" + IRCFormat.RESET + " Returning your cards and starting a new round.");
                    this.returnCards();
                    this.setEndCause(RoundEndCause.CZAR_SKIPPED);
                    this.advanceStage();
                }
                break;
        }
        return true;
    }

    @NotNull
    @Override
    public RoundSnapshot takeSnapshot() {
        return new RoundSnapshot(
            this.getNumber(),
            this.startTime,
            this.endTime,
            this.getBlackCard().getText(),
            this.getCzar() == null ? null : this.getCzar().getUser().getNick(),
            this.winningPlay == null ? null : this.winningPlay.getPlayer().getUser().getNick(),
            this.endCause.name(),
            this.getPlays().stream().map(Play::takeSnapshot).collect(Collectors.toList()),
            this.getGame().getPlayers().stream().map(p -> p.getUser().getNick()).collect(Collectors.toSet()),
            this.getSkippedPlayers().stream().map(p -> p.getUser().getNick()).collect(Collectors.toSet()),
            this.getGame().getHistoricPlayers().stream().collect(Collectors.toMap(p -> p.getUser().getNick(), p -> this.winningPlay == null ? 0 : p.equals(this.winningPlay.getPlayer()) ? 1 : 0)),
            this.votes.stream().collect(Collectors.toMap(play -> play.getPlayer().getUser().getNick(), this.votes::count))
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
            .add("number", this.number)
            .add("game", this.game)
            .add("blackCard", this.blackCard)
            .add("czar", this.czar)
            .add("currentStage", this.currentStage)
            .toString();
    }

}
