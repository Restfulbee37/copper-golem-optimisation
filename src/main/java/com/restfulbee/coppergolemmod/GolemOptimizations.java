package com.restfulbee.coppergolemmod;

import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTargetSometimes;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The actual optimizations. Everything here runs on the server thread as part of the normal golem tick.
 */
public final class GolemOptimizations {
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
}
