package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.sensor.NearestLivingEntitiesSensor;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestLivingEntitiesSensor.class)
public abstract class NearestLivingEntitiesSensorMixin {
    /**
     * Replaces the sort for copper golems only; the resulting memories are identical to vanilla.
     * See {@link GolemOptimizations#senseNearbyEntities}.
     */
    @Inject(method = "sense", at = @At("HEAD"), cancellable = true)
    private void copperGolemMod$fastSense(ServerWorld world, LivingEntity entity, CallbackInfo ci) {
        if (entity instanceof CopperGolemEntity golem) {
            CopperGolemModConfig config = CopperGolemMod.config();
            if (config.enabled && config.optimizations.fastNearbyEntitySort) {
                GolemOptimizations.senseNearbyEntities(world, golem);
                ci.cancel();
            }
        }
    }
}
