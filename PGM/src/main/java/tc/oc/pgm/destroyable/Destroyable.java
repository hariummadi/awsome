package tc.oc.pgm.destroyable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.ChatColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Firework;
import org.bukkit.material.MaterialData;
import org.bukkit.util.BlockVector;
import tc.oc.api.docs.virtual.MatchDoc;
import tc.oc.commons.bukkit.chat.NameStyle;
import tc.oc.commons.bukkit.util.BlockUtils;
import tc.oc.commons.bukkit.util.NMSHacks;
import tc.oc.commons.core.chat.Components;
import tc.oc.commons.core.util.DefaultMapAdapter;
import tc.oc.pgm.Config;
import tc.oc.pgm.PGM;
import tc.oc.pgm.blockdrops.BlockDrops;
import tc.oc.pgm.blockdrops.BlockDropsMatchModule;
import tc.oc.pgm.blockdrops.BlockDropsRuleSet;
import tc.oc.pgm.events.FeatureChangeEvent;
import tc.oc.pgm.fireworks.FireworkUtil;
import tc.oc.pgm.goals.IncrementalGoal;
import tc.oc.pgm.goals.ModeChangeGoal;
import tc.oc.pgm.goals.TouchableGoal;
import tc.oc.pgm.goals.events.GoalCompleteEvent;
import tc.oc.pgm.goals.events.GoalStatusChangeEvent;
import tc.oc.pgm.match.Competitor;
import tc.oc.pgm.match.Match;
import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.match.MatchPlayerState;
import tc.oc.pgm.match.ParticipantState;
import tc.oc.pgm.match.Parties;
import tc.oc.pgm.match.Party;
import tc.oc.pgm.regions.FiniteBlockRegion;
import tc.oc.pgm.teams.Team;
import tc.oc.pgm.utils.MaterialPattern;
import tc.oc.pgm.utils.Strings;

public class Destroyable extends TouchableGoal<DestroyableFactory> implements IncrementalGoal<DestroyableFactory>,
                                                                              ModeChangeGoal<DestroyableFactory> {

    // Block replacement rules in this ruleset will be used to calculate destroyable health
    protected final BlockDropsRuleSet blockDropsRuleSet;

    protected final FiniteBlockRegion blockRegion;
    protected final Set<MaterialPattern> materialPatterns = new HashSet<>();
    protected final Set<MaterialData> materials = new HashSet<>();

    // The percentage of blocks that must be broken for the entire Destroyable to be destroyed.
    protected double destructionRequired;

    protected final Duration SPARK_COOLDOWN = Duration.ofMillis(75);
    protected Instant lastSparkTime;

    /**
     * The maximum possible health that this Destroyable can have, which is the sum of the max health
     * of each block. The max health of a block is the maximum number of breaks between any destroyable
     * material and any non-destroyable material. Note that blocks are not necessarily at max health when
     * the match starts. This value can change as the result of mode changes.
     */
    protected int maxHealth;

    // The current health of the Destroyable
    protected int health;

    /**
     * Map of block -> material -> health i.e. the health level that each material represents
     * for each block in the destroyable. For example, (1,2,3) -> Gold Block -> 3 means that
     * when there is a gold block at (1,2,3), it will need to be broken three times to change
     * into a block that is not a destroyable material.
     *
     * This map will have en entry for every destroyable material for every block. If there are
     * no custom block replacement rules affecting this destroyable, this will be null;
     */
    protected Map<BlockVector, Map<MaterialData, Integer>> blockMaterialHealth;

    protected final List<DestroyableHealthChange> events = Lists.newArrayList();
    protected ImmutableList<DestroyableContribution> contributions;

    protected Iterable<Location> proximityLocations;

    public Destroyable(DestroyableFactory definition, Match match) {
        super(definition, match);

        for(MaterialPattern pattern : definition.getMaterials()) {
            addMaterials(pattern);
        }

        this.destructionRequired = definition.getDestructionRequired();

        final FiniteBlockRegion.Factory regionFactory = new FiniteBlockRegion.Factory(match.getMapInfo().proto);
        this.blockRegion = regionFactory.fromWorld(definition.getRegion(), match.getWorld(), this.materialPatterns);
        if(this.blockRegion.blockVolume() == 0) {
            match.getServer().getLogger().warning("No destroyable blocks found in destroyable " + this.getName());
        }

        this.blockDropsRuleSet = match.needMatchModule(BlockDropsMatchModule.class)
                                      .getRuleSet()
                                      .subsetAffecting(this.materials)
                                      .subsetAffecting(this.blockRegion);

        this.recalculateHealth();
    }

    // Remove @Nullable
    @Override
    public @Nonnull Team getOwner() {
        return super.getOwner();
    }

    @Override
    public boolean getDeferTouches() {
        return true;
    }

    @Override
    public BaseComponent getTouchMessage(@Nullable ParticipantState toucher, boolean self) {
        if(toucher == null) {
            return new TranslatableComponent("match.touch.destroyable.owner",
                                             Components.blank(),
                                             getComponentName(),
                                             getOwner().getComponentName());
        } else if(self) {
            return new TranslatableComponent("match.touch.destroyable.owner.you",
                                             Components.blank(),
                                             getComponentName(),
                                             getOwner().getComponentName());
        } else {
            return new TranslatableComponent("match.touch.destroyable.owner.toucher",
                                             toucher.getStyledName(NameStyle.COLOR),
                                             getComponentName(),
                                             getOwner().getComponentName());
        }
    }

    @Override
    public Iterable<Location> getProximityLocations(ParticipantState player) {
        if(proximityLocations == null) {
            proximityLocations = Collections.singleton(getBlockRegion().getBounds().center().toLocation(getOwner().getMatch().getWorld()));
        }
        return proximityLocations;
    }

    void addMaterials(MaterialPattern pattern) {
        materialPatterns.add(pattern);
        if(pattern.dataMatters()) {
            materials.add(pattern.getMaterialData());
        } else {
            // Hacky, but there is no other simple way to deal with block replacement
            materials.addAll(NMSHacks.getBlockStates(pattern.getMaterial()));
        }
    }

    /**
     * Calculate maximum/current health
     */
    protected void recalculateHealth() {
        // We only need blockMaterialHealth if there are destroyable blocks that are
        // replaced by other destroyable blocks when broken.
        if(this.isAffectedByBlockReplacementRules()) {
            this.blockMaterialHealth = new HashMap<>();
            this.buildMaterialHealthMap();
        } else {
            this.blockMaterialHealth = null;
            this.maxHealth = (int) this.blockRegion.blockVolume();
            this.health = 0;
            for(Block block : this.blockRegion.getBlocks(match.getWorld())) {
                if(this.hasMaterial(block.getState().getData())) {
                    this.health++;
                }
            }
        }
    }

    protected boolean isAffectedByBlockReplacementRules() {
        if (this.blockDropsRuleSet.getRules().isEmpty()) {
            return false;
        }

        for(Block block : this.blockRegion.getBlocks(match.getWorld())) {
            for(MaterialData material : this.materials) {
                BlockDrops drops = this.blockDropsRuleSet.getDrops(block.getState(), material);
                if(drops != null && drops.replacement != null && this.hasMaterial(drops.replacement)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Used internally to break out of the below recursive algorithm when a cycle is detected
     */
    protected final static class Indestructible extends Exception { }

    protected void buildMaterialHealthMap() {
        this.maxHealth = 0;
        this.health = 0;
        Set<MaterialData> visited = new HashSet<>();
        try {
            for(Block block : blockRegion.getBlocks(match.getWorld())) {
                Map<MaterialData, Integer> materialHealthMap = new HashMap<>();
                int blockMaxHealth = 0;

                for(MaterialData material : this.materials) {
                    visited.clear();
                    int blockHealth = this.buildBlockMaterialHealthMap(block, material, materialHealthMap, visited);
                    if(blockHealth > blockMaxHealth) {
                        blockMaxHealth = blockHealth;
                    }
                }

                this.blockMaterialHealth.put(block.getLocation().toVector().toBlockVector(), materialHealthMap);
                this.maxHealth += blockMaxHealth;
                this.health += this.getBlockHealth(block.getState());
            }
        }
        catch(Indestructible ex) {
            this.health = this.maxHealth = Integer.MAX_VALUE;
            PGM.get().getLogger().warning("Destroyable " + this.getName() + " is indestructible due to block replacement cycle");
        }
    }

    protected int buildBlockMaterialHealthMap(Block block,
                                              MaterialData material,
                                              Map<MaterialData, Integer> materialHealthMap,
                                              Set<MaterialData> visited) throws Indestructible {

        if(!this.hasMaterial(material)) {
            return 0;
        }

        Integer healthBoxed = materialHealthMap.get(material);
        if(healthBoxed != null) {
            return healthBoxed;
        }

        if(visited.contains(material)) {
            throw new Indestructible();
        }
        visited.add(material);

        int health = 1;
        if(this.blockDropsRuleSet != null) {
            BlockDrops drops = this.blockDropsRuleSet.getDrops(block.getState(), material);
            if(drops != null && drops.replacement != null) {
                health += this.buildBlockMaterialHealthMap(block, drops.replacement, materialHealthMap, visited);
            }
        }

        materialHealthMap.put(material, health);
        return health;
    }

    /**
     * Return the number of breaks required to change the given block to a non-objective material
     */
    public int getBlockHealth(BlockState blockState) {
        if(!this.getBlockRegion().contains(blockState)) {
            return 0;
        }

        if(this.blockMaterialHealth == null) {
            return this.hasMaterial(blockState.getData()) ? 1 : 0;
        } else {
            Map<MaterialData, Integer> materialHealthMap = this.blockMaterialHealth.get(blockState.getLocation().toVector().toBlockVector());
            if(materialHealthMap == null) {
                return 0;
            }
            Integer health = materialHealthMap.get(blockState.getData());
            return health == null ? 0 : health;
        }
    }

    public int getBlockHealthChange(BlockState oldState, BlockState newState) {
        return this.getBlockHealth(newState) - this.getBlockHealth(oldState);
    }

    /**
     * Update the state of this Destroyable to reflect the given block being changed by the given player.
     * @param oldState State of the block before the change
     * @param newState State of the block after the change
     * @param player Player responsible for the change
     * @return An object containing information about the change, including the health delta,
     *         or null if this Destroyable was not affected by the block change
     */
    public DestroyableHealthChange handleBlockChange(BlockState oldState, BlockState newState, @Nullable ParticipantState player) {
        if(this.isDestroyed() || !this.getBlockRegion().contains(oldState)) return null;

        int deltaHealth = this.getBlockHealthChange(oldState, newState);
        if(deltaHealth == 0) return null;

        this.addHealth(deltaHealth);

        DestroyableHealthChange changeInfo = new DestroyableHealthChange(oldState, newState, player, deltaHealth);
        this.events.add(changeInfo);

        if(deltaHealth < 0) {
            touch(player);

            if(this.definition.hasSparks()) {
                Location blockLocation = BlockUtils.center(oldState);
                Instant now = Instant.now();

                // Probability of a spark is time_since_last_spark / cooldown_time
                float chance = this.lastSparkTime == null ? 1.0f : ((float) Duration.between(lastSparkTime, now).toMillis()) / (float) SPARK_COOLDOWN.toMillis();
                if(this.match.getRandom().nextFloat() < chance) {
                    this.lastSparkTime = now;

                    // Spawn a firework where the block was
                    Firework firework = FireworkUtil.spawnFirework(blockLocation,
                                                                   FireworkEffect.builder()
                                                                       .with(FireworkEffect.Type.BURST)
                                                                       .withFlicker()
                                                                       .withColor(this.getOwner().getFullColor())
                                                                       .build(),
                                                                   0);
                    NMSHacks.skipFireworksLaunch(firework);

                    // Players more than 64m away will not see or hear the fireworks, so just play the sound for them
                    for(MatchPlayer listener : this.getOwner().getMatch().getPlayers()) {
                        if(listener.getBukkit().getLocation().distance(blockLocation) > 64) {
                            listener.getBukkit().playSound(listener.getBukkit().getLocation(), Sound.ENTITY_FIREWORK_BLAST, 0.75f, 1f);
                            listener.getBukkit().playSound(listener.getBukkit().getLocation(), Sound.ENTITY_FIREWORK_TWINKLE, 0.75f, 1f);
                        }
                    }
                }
            }
        }

        match.callEvent(new DestroyableHealthChangeEvent(this.getMatch(), this, changeInfo));
        match.callEvent(new GoalStatusChangeEvent(this));

        if(this.isDestroyed()) {
            match.callEvent(new DestroyableDestroyedEvent(this.match, this));
            match.callEvent(new GoalCompleteEvent(this,
                                                  true,
                                                  c -> false,
                                                  c -> !c.equals(getOwner()),
                                                  this.getContributions()));
        }

        return changeInfo;
    }

    @Override
    protected void playTouchEffects(ParticipantState toucher) {
        // We make our own touch sounds
    }

    /**
     * Test if the given block change is allowed by this Destroyable
     * @param oldState State of the block before the change
     * @param newState State of the block after the change
     * @param player Player responsible for the change
     * @return A player-readable message explaining why the block change is not allowed, or null if it is allowed
     */
    public String testBlockChange(BlockState oldState, BlockState newState, @Nullable ParticipantState player) {
        if(this.isDestroyed() || !this.getBlockRegion().contains(oldState)) return null;

        int deltaHealth = this.getBlockHealthChange(oldState, newState);
        if(deltaHealth == 0) return null;

        if(deltaHealth < 0) {
            // Damage
            if(player != null && player.getParty() == this.getOwner()) {
                return "match.destroyable.damageOwn";
            }
        } else if(deltaHealth > 0) {
            // Repair
            if(player != null && player.getParty() != this.getOwner()) {
                return "match.destroyable.repairOther";
            } else if(!this.definition.isRepairable()) {
                return "match.destroyable.repairDisabled";
            }
        }

        return null;
    }

    public FiniteBlockRegion getBlockRegion() {
        return this.blockRegion;
    }

    public boolean hasMaterial(MaterialData data) {
        for(MaterialPattern material : materialPatterns) {
            if(material.matches(data)) return true;
        }
        return false;
    }

    public void addHealth(int delta) {
        this.health = Math.max(0, Math.min(this.maxHealth, this.health + delta));
    }

    public int getMaxHealth() {
        return this.maxHealth;
    }

    public int getHealth() {
        return this.health;
    }

    public float getHealthPercent() {
        return (float) this.health / this.maxHealth;
    }

    public int getBreaks() {
        return this.maxHealth - this.health;
    }

    @Override
    public boolean isAffectedByModeChanges() {
        return this.definition.hasModeChanges();
    }

    public double getDestructionRequired() {
        return this.destructionRequired;
    }

    public String renderDestructionRequired() {
        return Math.round(this.destructionRequired * 100) + "%";
    }

    private int getBreaksRequired(double destructionRequired) {
        return (int) Math.round(this.maxHealth * destructionRequired);
    }

    public int getBreaksRequired() {
        return this.getBreaksRequired(this.destructionRequired);
    }

    public void setDestructionRequired(double destructionRequired) {
        if(this.destructionRequired != destructionRequired) {
            if(this.getBreaks() >= this.getBreaksRequired(destructionRequired)) {
                throw new IllegalArgumentException("Destroyable is already destroyed that much");
            } else if(destructionRequired > 1) {
                throw new IllegalArgumentException("Cannot require more than 100% destruction");
            }

            this.destructionRequired = destructionRequired;
            this.getOwner().getMatch().getPluginManager().callEvent(new FeatureChangeEvent(this.getMatch(), this));
        }
    }

    public void setBreaksRequired(int breaks) {
        this.setDestructionRequired((double) breaks / this.getMaxHealth());
    }

    @Override
    public double getCompletion() {
        return (double) this.getBreaks() / this.getBreaksRequired();
    }

    @Override
    public String renderCompletion() {
        return Strings.progressPercentage(this.getCompletion());
    }

    @Override
    public String renderPreciseCompletion() {
        return this.getBreaks() + "/" + this.getBreaksRequired();
    }

    @Override
    public String renderSidebarStatusText(@Nullable Competitor competitor, Party viewer) {
        if(this.getShowProgress() || Parties.isObservingType(viewer)) {
            String text = this.renderCompletion();
            if(Config.Scoreboard.preciseProgress()) {
                String precise = this.renderPreciseCompletion();
                if(precise != null) {
                    text += " " + ChatColor.GRAY + precise;
                }
            }
            return text;
        } else {
            return super.renderSidebarStatusText(competitor, viewer);
        }
    }

    @Override
    public boolean getShowProgress() {
        return this.definition.getShowProgress();
    }

    public boolean isDestroyed() {
        return this.getBreaks() >= this.getBreaksRequired();
    }

    @Override
    public boolean canComplete(Competitor team) {
        return team != this.getOwner();
    }

    @Override
    public boolean isCompleted() {
        return this.isDestroyed();
    }

    @Override
    public boolean isCompleted(Competitor team) {
        return this.isDestroyed() && this.canComplete(team);
    }

    public @Nonnull List<DestroyableHealthChange> getEvents() {
        return ImmutableList.copyOf(this.events);
    }

    public @Nonnull ImmutableList<DestroyableContribution> getContributions() {
        if(this.contributions != null) {
            return this.contributions;
        }

        Map<MatchPlayerState, Integer> playerDamage = new DefaultMapAdapter<>(new HashMap<MatchPlayerState, Integer>(), 0);

        int totalDamage = 0;
        for(DestroyableHealthChange change : this.events) {
            if(change.getHealthChange() < 0) {
                MatchPlayerState player = change.getPlayerCause();
                if(player != null) {
                    playerDamage.put(player, playerDamage.get(player) - change.getHealthChange());
                }
                totalDamage -= change.getHealthChange();
            }
        }

        ImmutableList.Builder<DestroyableContribution> builder = ImmutableList.builder();
        for(Map.Entry<MatchPlayerState, Integer> entry : playerDamage.entrySet()) {
            builder.add(new DestroyableContribution(
                entry.getKey(),
                (double) entry.getValue() / totalDamage, entry.getValue()
            ));
        }

        ImmutableList<DestroyableContribution> contributions = builder.build();
        if(this.isDestroyed()) {
            // Only cache if completely destroyed
            this.contributions = contributions;
        }
        return contributions;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void replaceBlocks(MaterialData newMaterial) {
        // Calling this method causes all non-destroyed blocks to be replaced, and the material
        // list to be replaced with one containing only the new block. If called on a multi-stage
        // destroyable, i.e. one which is affected by block replacement rules, it effectively ceases
        // to be multi-stage. Even if there are block replacement rules for the new block, the
        // replacements will not be in the material list, and so those blocks will be considered
        // destroyed the first time they are mined. This can have some strange effects on the health
        // of the destroyable: individual block health can only decrease, while the total health
        // percentage can only increase.
        double oldCompletion = getCompletion();

        for (Block block : this.getBlockRegion().getBlocks(match.getWorld())) {
            BlockState oldState = block.getState();
            int oldHealth = this.getBlockHealth(oldState);

            if (oldHealth > 0) {
                block.setTypeIdAndData(newMaterial.getItemTypeId(), newMaterial.getData(), true);
            }
        }

        // Update the materials list on switch
        this.materialPatterns.clear();
        this.materials.clear();
        addMaterials(new MaterialPattern(newMaterial));

        // If there is a block health map, get rid of it, since there is now only one material in the list
        this.blockMaterialHealth = null;

        this.recalculateHealth();

        if(oldCompletion != getCompletion()) {
            match.callEvent(new DestroyableHealthChangeEvent(match, this, null));
        }
    }

    @Override
    public boolean isObjectiveMaterial(Block block) {
        return this.hasMaterial(block.getState().getData());
    }

    @Override
    public String getModeChangeMessage() {
        return "match.objectiveMode.name.destroyable";
    }

    @Override
    public MatchDoc.Destroyable getDocument() {
        return new Document();
    }

    class Document extends TouchableGoal.Document implements MatchDoc.Destroyable {
        @Override
        public int total_blocks() {
            return getMaxHealth();
        }

        @Override
        public int breaks_required() {
            return getBreaksRequired();
        }

        @Override
        public int breaks() {
            return getBreaks();
        }

        @Override
        public double completion() {
            return getCompletion();
        }
    }
}
