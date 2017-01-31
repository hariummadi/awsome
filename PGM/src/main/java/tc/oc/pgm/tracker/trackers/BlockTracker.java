package tc.oc.pgm.tracker.trackers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.inject.Inject;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import tc.oc.commons.core.logging.Loggers;
import tc.oc.pgm.events.BlockTransformEvent;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.match.MatchScope;
import tc.oc.pgm.match.ParticipantState;
import tc.oc.pgm.tracker.BlockResolver;
import tc.oc.pgm.tracker.damage.BlockInfo;
import tc.oc.pgm.tracker.damage.OwnerInfo;
import tc.oc.pgm.tracker.damage.PhysicalInfo;
import tc.oc.pgm.tracker.damage.TrackerInfo;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Tracks the ownership of {@link Block}s and resolves damage caused by them
 */
@ListenerScope(MatchScope.RUNNING)
public class BlockTracker implements BlockResolver, Listener {

    private final Logger logger;
    private final Map<Block, TrackerInfo> blocks = new HashMap<>();
    private final Map<Block, Material> materials = new HashMap<>();

    @Inject BlockTracker(Loggers loggers) {
        this.logger = loggers.get(getClass());
    }

    @Override
    public PhysicalInfo resolveBlock(Block block) {
        TrackerInfo info = blocks.get(block);
        if(info instanceof PhysicalInfo) {
            return (PhysicalInfo) info;
        } else if(info instanceof OwnerInfo) {
            return new BlockInfo(block.getState(), ((OwnerInfo) info).getOwner());
        } else {
            return new BlockInfo(block.getState());
        }
    }

    @Override
    public @Nullable TrackerInfo resolveInfo(Block block) {
        return blocks.get(block);
    }

    @Override
    public @Nullable ParticipantState getOwner(Block block) {
        OwnerInfo info = resolveInfo(block, OwnerInfo.class);
        return info == null ? null : info.getOwner();
    }

    public void trackBlockState(Block block, @Nullable Material material, @Nullable TrackerInfo info) {
        checkNotNull(block);
        if(info != null) {
            blocks.put(block, info);
            if(material != null) {
                materials.put(block, material);
            } else {
                materials.remove(block);
            }
            logger.fine("Track block=" + block + " material=" + material + " info=" + info);
        } else {
            clearBlock(block);
        }
    }

    public void trackBlockState(BlockState state, @Nullable TrackerInfo info) {
        checkNotNull(state);
        trackBlockState(state.getBlock(), state.getMaterial(), info);
    }

    public void clearBlock(Block block) {
        checkNotNull(block);
        blocks.remove(block);
        materials.remove(block);
        logger.fine("Clear block=" + block);
    }

    boolean isPlaced(BlockState state) {
        // If block was registered with a specific material, check that the new state
        // has the same material, otherwise assume the block is still placed.
        Material material = materials.get(state.getBlock());
        return material == null || material == state.getMaterial();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(BlockTransformEvent event) {
        if(event.getCause() instanceof BlockPistonEvent) return;

        Block block = event.getOldState().getBlock();
        TrackerInfo info = blocks.get(block);
        if(info != null && !isPlaced(event.getNewState())) {
            clearBlock(block);
        }
    }

    private void handleMove(Collection<Block> blocks, BlockFace direction) {
        Map<Block, TrackerInfo> keepInfo = new HashMap<>();
        Map<Block, Material> keepMaterials = new HashMap<>();
        List<Block> remove = new ArrayList<>();

        for(Block block : blocks) {
            TrackerInfo info = this.blocks.get(block);
            if(info != null) {
                remove.add(block);
                keepInfo.put(block.getRelative(direction), info);

                Material material = materials.get(block);
                if(material != null) {
                    keepMaterials.put(block, material);
                }
            }
        }

        for(Block block : remove) {
            TrackerInfo info = keepInfo.remove(block);
            if(info != null) {
                this.blocks.put(block, info);

                Material material = keepMaterials.get(block);
                if(material != null) {
                    this.materials.put(block, material);
                }
            } else {
                this.blocks.remove(block);
                this.materials.remove(block);
            }
        }

        this.blocks.putAll(keepInfo);
        this.materials.putAll(keepMaterials);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handleMove(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handleMove(event.getBlocks(), event.getDirection());
    }
}
