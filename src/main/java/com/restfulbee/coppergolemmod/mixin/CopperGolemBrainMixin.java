package com.restfulbee.coppergolemmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.passive.CopperGolemBrain;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CopperGolemBrain.class)
public abstract class CopperGolemBrainMixin {
    /**
     * Swaps the copper golem's "look at nearby player" idle task for an equivalent one that skips its
     * per-tick scan of every nearby entity when no player is close enough to be found.
     * See {@link GolemOptimizations#createLookAtPlayerTask}.
     */
    @WrapOperation(
        method = "addIdleActivities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/ai/brain/task/LookAtMobWithIntervalTask;follow(Lnet/minecraft/entity/EntityType;FLnet/minecraft/util/math/intprovider/UniformIntProvider;)Lnet/minecraft/entity/ai/brain/task/Task;"
        )
    )
    private static Task<LivingEntity> copperGolemMod$fastLookAtPlayer(EntityType<?> type, float maxDistance, UniformIntProvider interval, Operation<Task<LivingEntity>> original) {
        if (type != EntityType.PLAYER) {
            return original.call(type, maxDistance, interval);
        }
        return GolemOptimizations.createLookAtPlayerTask(maxDistance, interval);
    }
}
