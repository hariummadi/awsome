package tc.oc.pgm.tracker.damage;

import javax.annotation.Nullable;

import org.bukkit.Location;
import tc.oc.commons.core.inspect.Inspectable;
import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.match.ParticipantState;
import tc.oc.pgm.time.TickTime;

public class FallState extends Inspectable.Impl implements FallInfo {

    // A player must leave the ground within this many ticks of being attacked for
    // the fall to be caused by knockback from that attack
    public static final long MAX_KNOCKBACK_TICKS = 20;

    // A player's fall is cancelled if they are on the ground continuously for more than this many ticks
    public static final long MAX_ON_GROUND_TICKS = 10;

    // A player's fall is cancelled if they touch the ground more than this many times
    public static final long MAX_GROUND_TOUCHES = 2;

    // A player's fall is cancelled if they are in water for more than this many ticks
    public static final long MAX_SWIMMING_TICKS = 20;

    // A player's fall is cancelled if they are climbing something for more than this many ticks
    public static final long MAX_CLIMBING_TICKS = 10;

    @Inspect final public MatchPlayer victim;
    @Inspect final public Location origin;

    // The kind of attack that initiated the fall
    @Inspect final public From from;
    @Inspect final public TrackerInfo cause;

    @Inspect final public TickTime startTime;

    // Where they land.. this is set when the fall ends
    @Inspect public To to;

    // If the player is on the ground when attacked, this is initially set false and later set true when they leave
    // the ground within the allowed time window. If the player is already in the air when attacked, this is set true.
    // This is used to distinguish the initial knockback/spleef from ground touches that occur during the fall.
    @Inspect public boolean isStarted;

    // Set true when the fall is over and no further processing should be done
    @Inspect public boolean isEnded;

    // Time the player last transitioned from off-ground to on-ground
    @Inspect public long onGroundTick;

    // The player's most recent swimming state and the time it was last set true
    @Inspect public boolean isSwimming;
    @Inspect public long swimmingTick;

    // The player's most recent climbing state and the time it was last set true
    @Inspect public boolean isClimbing;
    @Inspect public long climbingTick;

    // The player's most recent in-lava state and the time it was last set true
    @Inspect public boolean isInLava;
    @Inspect public long inLavaTick;

    // The number of times the player has touched the ground during since isFalling was set true
    @Inspect public int groundTouchCount;

    public FallState(MatchPlayer victim, From from, TrackerInfo cause) {
        this.victim = victim;
        this.from = from;
        this.cause = cause;
        this.startTime = victim.getMatch().getClock().now();
        this.origin = victim.getBukkit().getLocation();
    }

    @Override
    public @Nullable ParticipantState getAttacker() {
        if(cause instanceof OwnerInfo) {
            return ((OwnerInfo) cause).getOwner();
        } else if(cause instanceof DamageInfo) {
            return ((DamageInfo) cause).getAttacker();
        } else {
            return null;
        }
    }

    @Override
    public Location getOrigin() {
        return origin;
    }

    @Override
    public From getFrom() {
        return from;
    }

    @Override
    public To getTo() {
        return to;
    }

    @Override
    public TrackerInfo getCause() {
        return cause;
    }

    /**
     * Check if the victim of this fall is current supported by any solid blocks, water, or ladders
     */
    public boolean isSupported() {
        return this.isClimbing || this.isSwimming || victim.getBukkit().isOnGround();
    }

    /**
     * Check if the victim has failed to become unsupported quickly enough after the fall began
     */
    public boolean isExpired(TickTime now) {
        return this.isSupported() && now.tick - startTime.tick > MAX_KNOCKBACK_TICKS;
    }

    /**
     * Check if this fall has ended safely, which is true if the victim is not in lava and any of the following are true:
     *
     *  - victim has been on the ground for MAX_ON_GROUND_TICKS
     *  - victim has touched the ground MAX_GROUND_TOUCHES times
     *  - victim has been in water for MAX_SWIMMING_TICKS
     *  - victim has been on a ladder for MAX_CLIMBING_TICKS
     */
    public boolean isEndedSafely(TickTime now) {
        return !this.isInLava
               && ((victim.getBukkit().isOnGround() && (now.tick - onGroundTick > MAX_ON_GROUND_TICKS
                                                        || groundTouchCount > MAX_GROUND_TOUCHES))
                   || (isSwimming && now.tick - swimmingTick > MAX_SWIMMING_TICKS)
                   || (isClimbing && now.tick - climbingTick > MAX_CLIMBING_TICKS));
    }
}
