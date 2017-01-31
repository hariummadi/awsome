package tc.oc.pgm.blockdrops;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDespawnInVoidEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.bukkit.util.RayBlockIntersection;
import org.bukkit.util.Vector;
import tc.oc.commons.bukkit.util.BlockUtils;
import tc.oc.commons.bukkit.util.NMSHacks;
import tc.oc.commons.core.util.Pair;
import tc.oc.pgm.kits.KitPlayerFacet;
import tc.oc.pgm.match.Match;
import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.match.MatchScope;
import tc.oc.commons.bukkit.event.BlockPunchEvent;
import tc.oc.commons.bukkit.event.BlockTrampleEvent;
import tc.oc.pgm.events.BlockTransformEvent;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.events.ParticipantBlockTransformEvent;
import tc.oc.pgm.match.MatchModule;
import tc.oc.commons.bukkit.util.Materials;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

@ListenerScope(MatchScope.RUNNING)
public class BlockDropsMatchModule extends MatchModule implements Listener {
    private static final double BASE_FALL_SPEED = 3d;

    private final BlockDropsRuleSet ruleSet;

    // Tracks FallingBlocks created by explosions that have been randomly chosen
    // to not form a block when they land. We need to track them from the time
    // they are created because we don't want this to affect FallingBlocks created
    // in the normal vanilla way.
    //
    // This WILL leak a few entities now and then, because there are ways they can
    // die that do not fire an event e.g. the tick age limit, but this should be
    // rare and they will only leak until the end of the match.
    private final Set<FallingBlock> fallingBlocksThatWillNotLand = new HashSet<>();

    public BlockDropsMatchModule(Match match, BlockDropsRuleSet ruleSet) {
        super(match);
        this.ruleSet = ruleSet;
    }

    public BlockDropsRuleSet getRuleSet() {
        return ruleSet;
    }

    public static boolean causesDrops(final Event event) {
        return event instanceof BlockBreakEvent || event instanceof EntityExplodeEvent;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void initializeDrops(BlockTransformEvent event) {
        if(!causesDrops(event.getCause())) {
            return;
        }

        BlockDrops drops = this.ruleSet.getDrops(event, event.getOldState(), ParticipantBlockTransformEvent.getPlayerState(event));
        if(drops != null) {
            event.setDrops(drops);
        }
    }

    private void dropItems(BlockDrops drops, @Nullable MatchPlayer player, Location location, double yield) {
        if(player == null || player.getBukkit().getGameMode() != GameMode.CREATIVE) {
            Random random = getMatch().getRandom();
            for (Pair<Double, ItemStack> entry : drops.items) {
                if (random.nextFloat() < yield * entry.first) {
                    location.getWorld().dropItemNaturally(BlockUtils.center(location), entry.second);
                }
            }
        }
    }

    private void dropExperience(BlockDrops drops, Location location) {
        if(drops.experience != 0) {
            ExperienceOrb expOrb = (ExperienceOrb) location.getWorld().spawnEntity(BlockUtils.center(location), EntityType.EXPERIENCE_ORB);
            if(expOrb != null) {
                expOrb.setExperience(drops.experience);
            }
        }
    }

    private void giveKit(BlockDrops drops, MatchPlayer player) {
        if(player != null && player.isParticipating() && player.isSpawned() && drops.kit != null) {
            player.facet(KitPlayerFacet.class).applyKit(drops.kit, false);
        }
    }

    private void dropObjects(BlockDrops drops, @Nullable MatchPlayer player, Location location, double yield, boolean explosion) {
        giveKit(drops, player);
        if(explosion) {
            match.getScheduler(MatchScope.RUNNING).createTask(() -> dropItems(drops, player, location, yield));
        } else {
            dropItems(drops, player, location, yield);
            dropExperience(drops, location);
        }
    }

    private void replaceBlock(BlockDrops drops, Block block, MatchPlayer player) {
        if(drops.replacement != null) {
            EntityChangeBlockEvent event = new EntityChangeBlockEvent(player.getBukkit(), block, drops.replacement);
            getMatch().callEvent(event);

            if(!event.isCancelled()) {
                BlockState state = block.getState();
                state.setType(drops.replacement.getItemType());
                state.setData(drops.replacement);
                state.update(true, true);
            }
        }
    }

    /**
     * This is not an event handler. It is called explicitly by BlockTransformListener
     * after all event handlers have been called.
     */
    @SuppressWarnings("deprecation")
    public void doBlockDrops(final BlockTransformEvent event) {
        if(!causesDrops(event.getCause())) {
            return;
        }

        final BlockDrops drops = event.getDrops();
        if(drops != null) {
            event.setCancelled(true);
            final BlockState oldState = event.getOldState();
            final BlockState newState = event.getNewState();
            final Block block = event.getOldState().getBlock();
            final int newTypeId = newState.getTypeId();
            final byte newData = newState.getRawData();

            block.setTypeIdAndData(newTypeId, newData, true);

            boolean explosion = false;
            MatchPlayer player = ParticipantBlockTransformEvent.getParticipant(event);

            if(event.getCause() instanceof EntityExplodeEvent) {
                EntityExplodeEvent explodeEvent = (EntityExplodeEvent) event.getCause();
                explosion = true;

                if(drops.fallChance != null &&
                   oldState.getType().isBlock() &&
                   oldState.getType() != Material.AIR &&
                   this.getMatch().getRandom().nextFloat() < drops.fallChance) {

                    FallingBlock fallingBlock = event.getOldState().spawnFallingBlock();
                    fallingBlock.setDropItem(false);

                    if(drops.landChance != null && this.getMatch().getRandom().nextFloat() >= drops.landChance) {
                        this.fallingBlocksThatWillNotLand.add(fallingBlock);
                    }

                    Vector v = fallingBlock.getLocation().subtract(explodeEvent.getLocation()).toVector();
                    double distance = v.length();
                    v.normalize().multiply(BASE_FALL_SPEED * drops.fallSpeed / Math.max(1d, distance));

                    // A very simple deflection model. Check for a solid
                    // neighbor block and "bounce" the velocity off of it.
                    Block west = block.getRelative(BlockFace.WEST);
                    Block east = block.getRelative(BlockFace.EAST);
                    Block down = block.getRelative(BlockFace.DOWN);
                    Block up = block.getRelative(BlockFace.UP);
                    Block north = block.getRelative(BlockFace.NORTH);
                    Block south = block.getRelative(BlockFace.SOUTH);

                    if((v.getX() < 0 && west != null && Materials.isColliding(west.getType())) ||
                        v.getX() > 0 && east != null && Materials.isColliding(east.getType())) {
                        v.setX(-v.getX());
                    }

                    if((v.getY() < 0 && down != null && Materials.isColliding(down.getType())) ||
                        v.getY() > 0 && up != null && Materials.isColliding(up.getType())) {
                        v.setY(-v.getY());
                    }

                    if((v.getZ() < 0 && north != null && Materials.isColliding(north.getType())) ||
                        v.getZ() > 0 && south != null && Materials.isColliding(south.getType())) {
                        v.setZ(-v.getZ());
                    }

                    fallingBlock.setVelocity(v);
                }
            }

            dropObjects(drops, player, newState.getLocation(), 1d, explosion);

        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallingBlockLand(BlockTransformEvent event) {
        if(event.getCause() instanceof EntityChangeBlockEvent) {
            Entity entity = ((EntityChangeBlockEvent) event.getCause()).getEntity();
            if(entity instanceof FallingBlock && this.fallingBlocksThatWillNotLand.remove(entity)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFallInVoid(EntityDespawnInVoidEvent event) {
        if(event.getEntity() instanceof FallingBlock) {
            this.fallingBlocksThatWillNotLand.remove(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPunch(BlockPunchEvent event) {
        final MatchPlayer player = getMatch().getPlayer(event.getPlayer());
        if(player == null) return;

        RayBlockIntersection hit = event.getIntersection();

        BlockDrops drops = getRuleSet().getDrops(event, hit.getBlock().getState(), player.getParticipantState());
        if(drops == null) return;

        MaterialData oldMaterial = hit.getBlock().getState().getData();
        replaceBlock(drops, hit.getBlock(), player);

        // Play a fake punching effect if the block is punchable. Use raw particles instead of
        // playBlockBreakEffect so the position is precise rather than in the block center.
        Object packet = NMSHacks.blockCrackParticlesPacket(oldMaterial, false, hit.getPosition(), new Vector(), 0, 5);
        for(MatchPlayer viewer : getMatch().getPlayers()) {
            if(viewer.getBukkit().getEyeLocation().toVector().distanceSquared(hit.getPosition()) < 16 * 16) {
                NMSHacks.sendPacket(viewer.getBukkit(), packet);
            }
        }
        NMSHacks.playBlockPlaceSound(hit.getBlock().getWorld(), hit.getPosition(), oldMaterial.getItemType(), 1);

        dropObjects(drops, player, hit.getPosition().toLocation(hit.getBlock().getWorld()), 1d, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockTrample(BlockTrampleEvent event) {
        final MatchPlayer player = getMatch().getPlayer(event.getPlayer());
        if(player == null) return;

        BlockDrops drops = getRuleSet().getDrops(event, event.getBlock().getState(), player.getParticipantState());
        if(drops == null) return;

        replaceBlock(drops, event.getBlock(), player);
        dropObjects(drops, player, player.getBukkit().getLocation(), 1d, false);
    }
}
