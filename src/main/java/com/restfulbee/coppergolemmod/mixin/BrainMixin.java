package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Brain.class)
public abstract class BrainMixin<E extends LivingEntity> {
    /**
     * Runs sensors less often for copper golems in their "nothing to sort" cooldown.
     * See {@link GolemOptimizations#shouldSkipIdleSensors}.
     */
    @Inject(method = "tickSensors", at = @At("HEAD"), cancellable = true)
    private void copperGolemMod$throttleIdleSensors(ServerWorld world, E entity, CallbackInfo ci) {
        if (entity instanceof CopperGolemEntity golem && GolemOptimizations.shouldSkipIdleSensors(golem)) {
            ci.cancel();
        }
    }
}
