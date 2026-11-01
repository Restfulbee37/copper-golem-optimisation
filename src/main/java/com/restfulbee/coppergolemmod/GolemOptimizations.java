package com.restfulbee.coppergolemmod;

import com.restfulbee.coppergolemmod.mixin.ChestBlockEntityAccessor;
import com.restfulbee.coppergolemmod.mixin.ContainerOpenersCounterAccessor;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTargetSometimes;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The actual optimizations. Everything here runs on the server thread as part of the normal golem tick.
 */
public final class GolemOptimizations {
    private static final EntityTypeTest<Entity, LivingEntity> LIVING_ENTITIES = EntityTypeTest.forClass(LivingEntity.class);

    private GolemOptimizations() {
    }

    /**
     * Drop-in replacement for {@code SetEntityLookTargetSometimes.create(EntityType.PLAYER, maxDistance, interval)}
     * as used by {@code CopperGolemAi}.
     *
     * <p>Vanilla runs this every tick for every golem without a look target, and each run walks the golem's
     * whole NEAREST_VISIBLE_LIVING_ENTITIES list (every living entity within 16 blocks) looking for a player. In a dense farm that
     * is golems x golems type checks per tick, and it almost never finds anything. A match requires a player
     * within {@code maxDistance}, so when no player in the world is that close the scan cannot succeed and is
     * skipped. Otherwise this runs the exact vanilla logic, including the random look interval.
     *
     * <p>If {@code behavior.golemsLookAtPlayers} is false, the behavior never starts at all (opt-in, not vanilla).
     */
    @SuppressWarnings("deprecation") // SetEntityLookTargetSometimes is deprecated upstream but still what vanilla uses here.
    public static BehaviorControl<LivingEntity> createLookAtPlayerTask(float maxDistance, UniformInt interval) {
        float maxDistanceSquared = maxDistance * maxDistance;
        Predicate<LivingEntity> isPlayer = entity -> entity.is(EntityType.PLAYER);
        SetEntityLookTargetSometimes.Ticker lookInterval = new SetEntityLookTargetSometimes.Ticker(interval);
        return BehaviorBuilder.create(
            i -> i.group(i.absent(MemoryModuleType.LOOK_TARGET), i.present(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES))
                .apply(i, (lookTarget, nearestEntities) -> (level, body, timestamp) -> {
                    CopperGolemModConfig config = CopperGolemMod.config();
                    if (config.isPlayerLookDisabled()) {
                        CopperGolemMod.metrics().lookDisabled();
                        return false;
                    }
                    if (config.enabled && config.optimizations.fastPlayerLookCheck && !isAnyPlayerWithin(level, body, maxDistanceSquared)) {
                        CopperGolemMod.metrics().lookScanSkipped();
                        return false;
                    }
                    CopperGolemMod.metrics().lookScanRun();

                    Optional<LivingEntity> target = i.<NearestVisibleLivingEntities>get(nearestEntities)
                        .findClosest(isPlayer.and(other -> other.distanceToSqr(body) <= maxDistanceSquared));
                    if (target.isEmpty()) {
                        return false;
                    } else if (!lookInterval.tickDownAndCheck(level.getRandom())) {
                        return false;
                    } else {
                        lookTarget.set(new EntityTracker(target.get(), true));
                        return true;
                    }
                })
        );
    }

    private static boolean isAnyPlayerWithin(ServerLevel level, LivingEntity entity, double maxDistanceSquared) {
        List<ServerPlayer> players = level.players();
        for (int i = 0, size = players.size(); i < size; i++) {
            if (players.get(i).distanceToSqr(entity) <= maxDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same result as {@code NearestLivingEntitySensor.doTick} for a copper golem, but cheaper.
     *
     * <p>Vanilla sorts with {@code Comparator.comparingDouble(body::distanceToSqr)}, which recomputes two
     * distances for every comparison (roughly 2 * n * log2(n) distance calculations). Here each distance is
     * computed once and the indices are sorted with a stable merge sort. Vanilla's {@code List.sort} is also
     * stable and compares with {@code Double.compare}, so ties keep the same order and the list is identical.
     */
    public static void senseNearbyEntities(ServerLevel level, CopperGolem golem) {
        double range = golem.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB box = golem.getBoundingBox().inflate(range, range, range);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box, other -> other != golem && other.isAlive());

        int size = entities.size();
        if (size > 1) {
            LivingEntity[] unsorted = entities.toArray(new LivingEntity[0]);
            double[] distances = new double[size];
            int[] order = new int[size];
            for (int i = 0; i < size; i++) {
                distances[i] = golem.distanceToSqr(unsorted[i]);
                order[i] = i;
            }
            IntArrays.mergeSort(order, (a, b) -> Double.compare(distances[a], distances[b]));
            for (int i = 0; i < size; i++) {
                entities.set(i, unsorted[order[i]]);
            }
        }

        Brain<?> brain = golem.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, entities);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, new NearestVisibleLivingEntities(level, golem, entities));
        CopperGolemMod.metrics().sensorSortOptimized();
    }

    /**
     * True when this tick's sensor update should be skipped because the golem is idle. A golem is idle while
     * it has the transport-items cooldown memory, which vanilla sets after a search finds nothing to move
     * (or after 10 visits). During that cooldown the item-moving task cannot start, so the sensors only
     * serve looking at players and closing doors. The cooldown timer itself is ticked by a brain task, not
     * by a sensor, so it still counts down at normal speed and sorting resumes on the same tick as vanilla.
     */
    public static boolean shouldSkipIdleSensors(CopperGolem golem) {
        CopperGolemModConfig config = CopperGolemMod.config();
        int interval = config.idleThrottle.sensorTickInterval;
        if (!config.enabled || !config.idleThrottle.enabled || interval <= 1) {
            return false;
        }
        if (Math.floorMod(golem.tickCount + golem.getId(), interval) == 0) {
            return false;
        }
        // React immediately to being hurt; HURT_BY is a sensor.
        if (golem.hurtTime > 0 || golem.isPanicking()) {
            return false;
        }
        if (!golem.getBrain().hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS)) {
            return false;
        }
        CopperGolemMod.metrics().idleSensorTickSkipped();
        return true;
    }

    /**
     * The copper golem's "should I queue for this chest?" predicate, unchanged. Wrapping it lets the item-moving behavior
     * tell copper golems apart from any other mob using it, and answer the question with {@link #isChestInUse}.
     */
    public record ChestInUseCheck(Predicate<TransportItemTarget> vanilla) implements Predicate<TransportItemTarget> {
        @Override
        public boolean test(TransportItemTarget target) {
            return vanilla.test(target);
        }
    }

    public static boolean isFastChestInUseCheckOn() {
        CopperGolemModConfig config = CopperGolemMod.config();
        return config.enabled && config.optimizations.fastChestInUseCheck;
    }

    /**
     * Same result as {@code TransportItemsBetweenContainers.isAnotherMobInteractingWithTarget} for a copper golem: true if
     * anyone has this chest, or the other half of this double chest, open. Golems ask this every tick while they are close
     * to their chest or queuing for it.
     *
     * <p>Vanilla first builds a target for the other half, which runs its blocked checks (including a scan for sitting
     * cats), then lists every entity near each half and checks whether the list is empty. Here the golem's own half is
     * checked first, the other half's target is only built once that half has a viewer (vanilla only checks the other
     * half when its target can be built), and each search stops at the first viewer.
     */
    public static boolean isChestInUse(TransportItemTarget target, Level level) {
        CopperGolemMod.metrics().chestInUseCheckOptimized();
        if (hasViewer(target.blockEntity())) {
            return true;
        }
        if (target.state().getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) == ChestType.SINGLE) {
            return false;
        }
        BlockEntity otherHalf = level.getBlockEntity(ChestBlock.getConnectedBlockPos(target.pos(), target.state()));
        return otherHalf != null && hasViewer(otherHalf) && TransportItemTarget.tryCreatePossibleTarget(otherHalf, level) != null;
    }

    /** Vanilla's copper golem predicate (is this a chest anyone has open?), stopping at the first viewer. */
    private static boolean hasViewer(BlockEntity blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return false;
        }
        if (chest.getClass() != ChestBlockEntity.class && chest.getClass() != TrappedChestBlockEntity.class) {
            // A modded chest may find its viewers differently, so ask it.
            return !chest.getEntitiesWithContainerOpen().isEmpty();
        }
        // ContainerOpenersCounter.getEntitiesWithContainerOpen with the same box and test, but stopping at the first match.
        // Every ContainerUser is a LivingEntity (ContainerUser.getLivingEntity requires it), so other entities are skipped.
        ContainerOpenersCounter counter = ((ChestBlockEntityAccessor) chest).copperGolemMod$openersCounter();
        BlockPos pos = chest.getBlockPos();
        AABB searchBox = new AABB(pos).inflate(((ContainerOpenersCounterAccessor) counter).copperGolemMod$maxInteractionRange() + 4.0D);
        return chest.getLevel().hasEntities(LIVING_ENTITIES, searchBox, entity -> entity instanceof ContainerUser user
            && !user.getLivingEntity().isSpectator() && user.hasContainerOpen(counter, pos));
    }

    public static boolean isFastChestSearchOn() {
        CopperGolemModConfig config = CopperGolemMod.config();
        return config.enabled && config.optimizations.fastChestSearch;
    }

    /** What {@link #chestSearchShortcut} found out about a chest without building its target. */
    public enum ChestSearchShortcut {
        /** Vanilla returns null for this chest whatever its target would be. */
        RULED_OUT,
        /** Vanilla returns this chest's target, or null if it can't be built (e.g. a cat is sitting on the chest). */
        TARGET_IF_BUILDABLE,
        /** The other half of this double chest was visited; whether that counts depends on its target, so ask vanilla. */
        ASK_VANILLA
    }

    /**
     * For {@code TransportItemsBetweenContainers.isTargetValidToPick}, which a searching golem calls for every chest in
     * range that is closer than the best one found so far.
     *
     * <p>Vanilla first builds the chest's target, which runs blocked checks on both halves (including a scan for sitting
     * cats), and only then checks that the chest is the right kind, not visited and not locked. For a double chest it
     * also builds the other half's target, to check whether that half was visited. None of this has side effects, so
     * running the cheap checks first gives the same answer: only chests that can actually be picked get their target
     * built, and the other half's only when it was visited.
     */
    public static ChestSearchShortcut chestSearchShortcut(Level level, BlockEntity blockEntity, boolean wantedBlock,
                                                          Set<GlobalPos> visited, Set<GlobalPos> unreachable, AABB searchArea) {
        BlockPos pos = blockEntity.getBlockPos();
        if (!searchArea.contains(pos.getX(), pos.getY(), pos.getZ())) {
            return ChestSearchShortcut.RULED_OUT;
        }
        if (!wantedBlock || blockEntity instanceof BaseContainerBlockEntity container && container.isLocked()
            || isVisited(level, pos, visited, unreachable)) {
            CopperGolemMod.metrics().chestRuledOutEarly();
            return ChestSearchShortcut.RULED_OUT;
        }
        BlockState state = blockEntity.getBlockState();
        if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE
            && isVisited(level, ChestBlock.getConnectedBlockPos(pos, state), visited, unreachable)) {
            return ChestSearchShortcut.ASK_VANILLA;
        }
        return ChestSearchShortcut.TARGET_IF_BUILDABLE;
    }

    private static boolean isVisited(Level level, BlockPos pos, Set<GlobalPos> visited, Set<GlobalPos> unreachable) {
        GlobalPos globalPos = new GlobalPos(level.dimension(), pos);
        return visited.contains(globalPos) || unreachable.contains(globalPos);
    }
}
