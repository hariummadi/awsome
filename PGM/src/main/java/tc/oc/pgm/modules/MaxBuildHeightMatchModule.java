package tc.oc.pgm.modules;

import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import tc.oc.commons.core.chat.Component;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.match.Match;
import tc.oc.pgm.events.BlockTransformEvent;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.match.MatchScope;

@ListenerScope(MatchScope.RUNNING)
public class MaxBuildHeightMatchModule extends MatchModule implements Listener {
    protected final int buildHeight;

    public MaxBuildHeightMatchModule(Match match, int buildHeight) {
        super(match);
        this.buildHeight = buildHeight;
    }

    @EventHandler(ignoreCancelled = true)
    public void checkBuildHeight(BlockTransformEvent event) {
        if(event.getNewState().getType() != Material.AIR) {
            if(event.getNewState().getY() >= this.buildHeight) {
                event.setCancelled(true, new TranslatableComponent("match.maxBuildHeightWarning",
                                                                   new Component(String.valueOf(buildHeight), net.md_5.bungee.api.ChatColor.AQUA)));
            }
        }
    }
}
