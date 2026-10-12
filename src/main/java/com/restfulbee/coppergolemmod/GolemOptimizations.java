package com.restfulbee.coppergolemmod;

import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.LivingTargetCache;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookAtMobWithIntervalTask;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.ai.brain.task.TaskTriggerer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.intprovider.UniformIntProvider;

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
     * Drop-in replacement for {@code LookAtMobWithIntervalTask.follow(EntityType.PLAYER, maxDistance, interval)}
     * as used by {@code CopperGolemBrain}.
     *
     * <p>Vanilla runs this every tick for every golem without a look target, and each run walks the golem's
     * whole VISIBLE_MOBS list (every living entity within 16 blocks) looking for a player. In a dense farm that
     * is golems x golems type checks per tick, and it almost never finds anything. A match requires a player
     * within {@code maxDistance}, so when no player in the world is that close the scan cannot succeed and is
     * skipped. Otherwise this runs the exact vanilla logic, including the random look interval.
     */
    @SuppressWarnings("deprecation") // LookAtMobWithIntervalTask is deprecated upstream but still what vanilla uses here.
    public static Task<LivingEntity> createLookAtPlayerTask(float maxDistance, UniformIntProvider interval) {
        float maxDistanceSquared = maxDistance * maxDistance;
        Predicate<LivingEntity> isPlayer = entity -> EntityType.PLAYER.equals(entity.getType());
        LookAtMobWithIntervalTask.Interval lookInterval = new LookAtMobWithIntervalTask.Interval(interval);
        return TaskTriggerer.task(
            context -> context.group(context.queryMemoryAbsent(MemoryModuleType.LOOK_TARGET), context.queryMemoryValue(MemoryModuleType.VISIBLE_MOBS))
                .apply(context, (lookTarget, visibleMobs) -> (world, entity, time) -> {
                    CopperGolemModConfig config = CopperGolemMod.config();
                    if (config.enabled && config.optimizations.fastPlayerLookCheck && !isAnyPlayerWithin(world, entity, maxDistanceSquared)) {
                        CopperGolemMod.metrics().lookScanSkipped();
                        return false;
                    }
                    CopperGolemMod.metrics().lookScanRun();

                    Optional<LivingEntity> target = context.<LivingTargetCache>getValue(visibleMobs)
                        .findFirst(isPlayer.and(other -> other.squaredDistanceTo(entity) <= maxDistanceSquared));
                    if (target.isEmpty()) {
                        return false;
                    } else if (!lookInterval.shouldRun(world.random)) {
                        return false;
                    } else {
                        lookTarget.remember(new EntityLookTarget(target.get(), true));
                        return true;
                    }
                })
        );
    }

    private static boolean isAnyPlayerWithin(ServerWorld world, LivingEntity entity, double maxDistanceSquared) {
        List<ServerPlayerEntity> players = world.getPlayers();
        for (int i = 0, size = players.size(); i < size; i++) {
            if (players.get(i).squaredDistanceTo(entity) <= maxDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same result as {@code NearestLivingEntitiesSensor.sense} for a copper golem, but cheaper.
     *
     * <p>Vanilla sorts with {@code Comparator.comparingDouble(entity::squaredDistanceTo)}, which recomputes two
     * distances for every comparison (roughly 2 * n * log2(n) distance calculations). Here each distance is
     * computed once and the indices are sorted with a stable merge sort. Vanilla's {@code List.sort} is also
     * stable and compares with {@code Double.compare}, so ties keep the same order and the list is identical.
     */
    public static void senseNearbyEntities(ServerWorld world, CopperGolemEntity golem) {
        double range = golem.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
        Box box = golem.getBoundingBox().expand(range, range, range);
        List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, box, other -> other != golem && other.isAlive());

        int size = entities.size();
        if (size > 1) {
            LivingEntity[] unsorted = entities.toArray(new LivingEntity[0]);
            double[] distances = new double[size];
            int[] order = new int[size];
            for (int i = 0; i < size; i++) {
                distances[i] = golem.squaredDistanceTo(unsorted[i]);
                order[i] = i;
            }
            IntArrays.mergeSort(order, (a, b) -> Double.compare(distances[a], distances[b]));
            for (int i = 0; i < size; i++) {
                entities.set(i, unsorted[order[i]]);
            }
        }

        Brain<?> brain = golem.getBrain();
        brain.remember(MemoryModuleType.MOBS, entities);
        brain.remember(MemoryModuleType.VISIBLE_MOBS, new LivingTargetCache(world, golem, entities));
        CopperGolemMod.metrics().sensorSortOptimized();
    }

    /**
     * True when this tick's sensor update should be skipped because the golem is idle. A golem is idle while
     * it has the transport-items cooldown memory, which vanilla sets after a search finds nothing to move
     * (or after 10 visits). During that cooldown the item-moving task cannot start, so the sensors only
     * serve looking at players and closing doors. The cooldown timer itself is ticked by a brain task, not
     * by a sensor, so it still counts down at normal speed and sorting resumes on the same tick as vanilla.
     */
    public static boolean shouldSkipIdleSensors(CopperGolemEntity golem) {
        CopperGolemModConfig config = CopperGolemMod.config();
        int interval = config.idleThrottle.sensorTickInterval;
        if (!config.enabled || !config.idleThrottle.enabled || interval <= 1) {
            return false;
        }
        if (Math.floorMod(golem.age + golem.getId(), interval) == 0) {
            return false;
        }
        // React immediately to being hurt; HURT_BY is a sensor.
        if (golem.hurtTime > 0 || golem.isPanicking()) {
            return false;
        }
        if (!golem.getBrain().hasMemoryModule(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS)) {
            return false;
        }
        CopperGolemMod.metrics().idleSensorTickSkipped();
        return true;
    }
}
