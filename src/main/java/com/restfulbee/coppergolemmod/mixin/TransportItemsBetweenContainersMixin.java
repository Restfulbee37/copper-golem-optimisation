package com.restfulbee.coppergolemmod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Predicate;

@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
    @Shadow
    @Final
    private Predicate<TransportItemTarget> shouldQueueForTarget;

    @Shadow
    private boolean isWantedBlock(PathfinderMob mob, BlockState block) {
        throw new AssertionError();
    }

    /**
     * Counts completed pickups and drop-offs so {@code /cgp status} can report sorting throughput. Vanilla only
     * calls these when there is actually something to take or somewhere to put it. Observation only.
     */
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

    /**
     * Answers "is anyone using this chest?" for copper golems without listing every entity near it; the answer is the
     * same. See {@link GolemOptimizations#isChestInUse}.
     */
    @WrapMethod(method = "isAnotherMobInteractingWithTarget")
    private boolean copperGolemMod$fastChestInUseCheck(TransportItemTarget target, Level level, Operation<Boolean> original) {
        if (shouldQueueForTarget instanceof GolemOptimizations.ChestInUseCheck && GolemOptimizations.isFastChestInUseCheckOn()) {
            return GolemOptimizations.isChestInUse(target, level);
        }
        return original.call(target, level);
    }

    /**
     * Rules out chests a copper golem can't pick before vanilla builds their targets; the chest chosen is the same.
     * See {@link GolemOptimizations#chestSearchShortcut}.
     */
    @WrapMethod(method = "isTargetValidToPick")
    private TransportItemTarget copperGolemMod$fastChestSearch(PathfinderMob body, Level level, BlockEntity blockEntity,
                                                               Set<GlobalPos> visitedPositions, Set<GlobalPos> unreachablePositions,
                                                               AABB targetBlockSearchArea, Operation<TransportItemTarget> original) {
        if (body instanceof CopperGolem && GolemOptimizations.isFastChestSearchOn()) {
            boolean wantedBlock = isWantedBlock(body, blockEntity.getBlockState());
            switch (GolemOptimizations.chestSearchShortcut(level, blockEntity, wantedBlock, visitedPositions, unreachablePositions, targetBlockSearchArea)) {
                case RULED_OUT -> {
                    return null;
                }
                case TARGET_IF_BUILDABLE -> {
                    return TransportItemTarget.tryCreatePossibleTarget(blockEntity, level);
                }
                case ASK_VANILLA -> {
                }
            }
        }
        return original.call(body, level, blockEntity, visitedPositions, unreachablePositions, targetBlockSearchArea);
    }
}
