package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import net.minecraft.entity.ai.brain.task.MoveItemsTask;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.inventory.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts completed pickups and drop-offs so {@code /cgp status} can report sorting throughput. Vanilla only
 * calls these when there is actually something to take or somewhere to put it. Observation only.
 */
@Mixin(MoveItemsTask.class)
public abstract class MoveItemsTaskMixin {
    @Inject(method = "takeStack", at = @At("HEAD"))
    private void copperGolemMod$countTake(PathAwareEntity entity, Inventory inventory, CallbackInfo ci) {
        if (entity instanceof CopperGolemEntity) {
            CopperGolemMod.metrics().stackTaken();
        }
    }

    @Inject(method = "placeStack", at = @At("HEAD"))
    private void copperGolemMod$countPlace(PathAwareEntity entity, Inventory inventory, CallbackInfo ci) {
        if (entity instanceof CopperGolemEntity) {
            CopperGolemMod.metrics().stackPlaced();
        }
    }
}
