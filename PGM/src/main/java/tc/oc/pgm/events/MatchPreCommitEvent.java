package tc.oc.pgm.events;

import org.bukkit.event.HandlerList;
import tc.oc.pgm.match.Match;

/**
 * Fired immediately before players become committed to playing the match.
 * This is at the beginning of team huddle, if it is enabled, otherwise match start.
 */
public class MatchPreCommitEvent extends MatchEvent {

    public MatchPreCommitEvent(Match match) {
        super(match);
    }

    private static HandlerList handlers = new HandlerList();
    @Override public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
