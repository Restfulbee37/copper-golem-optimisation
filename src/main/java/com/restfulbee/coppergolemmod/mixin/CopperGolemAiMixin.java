package com.restfulbee.coppergolemmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.animal.golem.CopperGolemAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CopperGolemAi.class)
public abstract class CopperGolemAiMixin {
    /**
     * Swaps the copper golem's "look at nearby player" idle behavior for an equivalent one that skips its
     * per-tick scan of every nearby entity when no player is close enough to be found.
     * See {@link GolemOptimizations#createLookAtPlayerTask}.
     */
    @WrapOperation(
        method = "initIdleActivity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/SetEntityLookTargetSometimes;create(Lnet/minecraft/world/entity/EntityType;FLnet/minecraft/util/valueproviders/UniformInt;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"
        )
    )
    private static BehaviorControl<LivingEntity> copperGolemMod$fastLookAtPlayer(EntityType<?> type, float maxDistance, UniformInt interval, Operation<BehaviorControl<LivingEntity>> original) {
        if (type != EntityType.PLAYER) {
            return original.call(type, maxDistance, interval);
        }
        return GolemOptimizations.createLookAtPlayerTask(maxDistance, interval);
    }
}
