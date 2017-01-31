package tc.oc.pgm.flag.state;

import java.util.Collections;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Location;
import tc.oc.pgm.match.ParticipantState;
import tc.oc.pgm.match.Party;
import tc.oc.pgm.flag.Flag;
import tc.oc.pgm.flag.Post;
import tc.oc.pgm.score.ScoreMatchModule;
import tc.oc.pgm.teams.TeamMatchModule;

import javax.annotation.Nullable;

/**
 * State of a flag after being returned to a {@link Post}, or at the start of
 * the match when at its initial post.
 */
public class Returned extends Uncarried implements Runnable {

    public Returned(Flag flag, Post home, @Nullable Location location) {
        super(flag, home, location);
    }

    @Override
    public boolean isRecoverable() {
        return false;
    }

    @Override
    public Iterable<Location> getProximityLocations(ParticipantState player) {
        if(!flag.hasTouched(player.getParty())) {
            return Collections.singleton(getLocation());
        } else {
            return super.getProximityLocations(player);
        }
    }

    @Override
    public void tickRunning() {
        super.tickRunning();

        ScoreMatchModule smm = this.flag.getMatch().getMatchModule(ScoreMatchModule.class);
        if(smm != null && this.post.getOwner() != null && this.post.getPointsPerSecond() > 0) {
            smm.incrementScore(this.flag.getMatch().needMatchModule(TeamMatchModule.class).team(this.post.getOwner()), this.post.getPointsPerSecond() / 20D);
        }
    }

    @Override
    public ChatColor getStatusColor(Party viewer) {
        if(this.flag.getDefinition().hasMultipleCarriers()) {
            return ChatColor.WHITE;
        } else {
            return super.getStatusColor(viewer);
        }
    }

    @Override
    public String getStatusSymbol(Party viewer) {
        return Flag.RETURNED_SYMBOL;
    }
}
