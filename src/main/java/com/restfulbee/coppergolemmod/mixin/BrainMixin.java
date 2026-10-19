package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.golem.CopperGolem;
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
    private void copperGolemMod$throttleIdleSensors(ServerLevel level, E body, CallbackInfo ci) {
        if (body instanceof CopperGolem golem && GolemOptimizations.shouldSkipIdleSensors(golem)) {
            ci.cancel();
        }
    }
}
