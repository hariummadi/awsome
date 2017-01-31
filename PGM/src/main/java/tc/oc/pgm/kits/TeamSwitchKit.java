package tc.oc.pgm.kits;

import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.teams.TeamFactory;
import tc.oc.pgm.teams.TeamMatchModule;

public class TeamSwitchKit extends DelayedKit {
    private final TeamFactory team;

    public TeamSwitchKit(TeamFactory team) {
        this.team = team;
    }

    @Override
    public void applyDelayed(MatchPlayer player, boolean force) {
        TeamMatchModule tmm = player.getMatch().needMatchModule(TeamMatchModule.class);
        tmm.forceJoin(player, tmm.team(team));
    }

}
