package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestLivingEntitySensor.class)
public abstract class NearestLivingEntitySensorMixin {
    /**
     * Replaces the sort for copper golems only; the resulting memories are identical to vanilla.
     * See {@link GolemOptimizations#senseNearbyEntities}.
     */
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void copperGolemMod$fastSense(ServerLevel level, LivingEntity body, CallbackInfo ci) {
        if (body instanceof CopperGolem golem) {
            CopperGolemModConfig config = CopperGolemMod.config();
            if (config.enabled && config.optimizations.fastNearbyEntitySort) {
                GolemOptimizations.senseNearbyEntities(level, golem);
                ci.cancel();
            }
        }
    }
}
