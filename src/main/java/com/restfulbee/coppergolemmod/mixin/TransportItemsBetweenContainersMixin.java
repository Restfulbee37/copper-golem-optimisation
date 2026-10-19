package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts completed pickups and drop-offs so {@code /cgp status} can report sorting throughput. Vanilla only
 * calls these when there is actually something to take or somewhere to put it. Observation only.
 */
@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
    @Inject(method = "pickUpItems", at = @At("HEAD"))
    private void copperGolemMod$countTake(PathfinderMob body, Container container, CallbackInfo ci) {
        if (body instanceof CopperGolem) {
            CopperGolemMod.metrics().stackTaken();
        }
    }

    @Inject(method = "putDownItem", at = @At("HEAD"))
    private void copperGolemMod$countPlace(PathfinderMob body, Container container, CallbackInfo ci) {
        if (body instanceof CopperGolem) {
            CopperGolemMod.metrics().stackPlaced();
        }
    }
}
