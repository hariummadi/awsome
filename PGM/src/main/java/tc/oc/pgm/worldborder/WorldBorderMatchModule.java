package tc.oc.pgm.worldborder;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import org.bukkit.EntityLocation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.time.Duration;
import tc.oc.commons.bukkit.event.CoarsePlayerMoveEvent;
import tc.oc.commons.bukkit.util.WorldBorderUtils;
import tc.oc.commons.core.util.DefaultMapAdapter;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.goals.events.GoalEvent;
import tc.oc.pgm.match.Match;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.match.MatchScope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@ListenerScope(MatchScope.LOADED)
public class WorldBorderMatchModule extends MatchModule implements Listener {

    private final List<WorldBorder> borders;
    private final Map<WorldBorder, Boolean> results = new DefaultMapAdapter<>(false);
    private @Nullable WorldBorder appliedBorder;
    private @Nullable Duration appliedAt;

    public WorldBorderMatchModule(Match match, List<WorldBorder> borders) {
        super(match);
        checkNotNull(borders);
        checkArgument(!borders.isEmpty());
        this.borders = borders;
    }

    @Override
    public void load() {
        super.load();

        WorldBorder initial = null;
        for(WorldBorder border : borders) {
            if(!border.isConditional()) initial = border;
        }

        if(initial != null) {
            logger.fine("Initializing with " + initial);
            apply(initial);
        } else {
            reset();
        }
    }

    @Override
    public void enable() {
        super.enable();

        getMatch().getScheduler(MatchScope.RUNNING).createRepeatingTask(Duration.ZERO, Duration.ofSeconds(1), () -> {
            if(!update()) {
                refresh();
            }
        });
    }

    @Override
    public void disable() {
        freeze();

        super.disable();
    }

    private void apply(WorldBorder border) {
        logger.fine("Applying " + border);

        border.apply(getMatch().getWorld().getWorldBorder(), appliedBorder != null);
        appliedBorder = border;
        appliedAt = getMatch().runningTime();
    }

    private void reset() {
        logger.fine("Clearing border");

        appliedBorder = null;
        appliedAt = null;
        getMatch().getWorld().getWorldBorder().reset();
    }

    /**
     * Query the filters of all borders and apply them as needed.
     *
     * A border is applied when its filter goes from false to true, or when it becomes
     * active because of another border further down the list going from true to false.
     *
     * If multiple borders become active simultaneously, they are applied in order. This
     * allows a border to serve as the starting point for another moving border.
     */
    private boolean update() {
        WorldBorder lastMatched = null;
        boolean applied = false;

        for(WorldBorder border : borders) {
            boolean newResult = border.filter.query(match).isAllowed();
            boolean oldResult = results.put(border, newResult);
            if(newResult) lastMatched = border;

            if(!oldResult && newResult) {
                // On the filter's rising edge, apply the border
                applied = true;
                apply(border);
            } else if(oldResult && !newResult) {
                if(lastMatched == null) {
                    // On the filter's falling edge, apply the last border in the list with a passing filter
                    reset();
                } else {
                    // If no borders have passing filters, clear the border
                    apply(lastMatched);
                }
            }
        }

        return applied;
    }

    /**
     * If the current border is moving, refresh its size/duration on all clients (to keep them in sync)
     */
    private void refresh() {
        if(appliedBorder != null) {
            appliedBorder.refresh(getMatch().getWorld().getWorldBorder(), getMatch().runningTime().minus(appliedAt));
        }
    }

    /**
     * If the current border is moving, stop it in-place
     */
    private void freeze() {
        if(appliedBorder != null && appliedBorder.isMoving()) {
            logger.fine("Freezing border " + appliedBorder);
            getMatch().getWorld().getWorldBorder().setSize(getMatch().getWorld().getWorldBorder().getSize(), 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGoalComplete(GoalEvent event) {
        update();
    }

    /**
     * Prevent teleporting outside the border
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        if(event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            if(WorldBorderUtils.isInsideBorder(event.getFrom()) &&
               !WorldBorderUtils.isInsideBorder(event.getTo())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(final CoarsePlayerMoveEvent event) {
        MatchPlayer player = getMatch().getPlayer(event.getPlayer());
        if(player != null && player.isObserving()) {
            EntityLocation location = event.getTo();
            if(WorldBorderUtils.clampToBorder(location)) {
                event.setTo(location);
            }
        }
    }
}
