package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.ModMetrics;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-golem tick bookkeeping plus the opt-in stationary sorter. {@code travel} and {@code pushEntities} are
 * overridden here instead of injected into {@code LivingEntity}, so no other entity type pays for these checks.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin extends AbstractGolem {
    @Unique
    private long copperGolemMod$lastBlockPos = Long.MIN_VALUE;
    @Unique
    private int copperGolemMod$ticksInSameBlock;
    @Unique
    private boolean copperGolemMod$stationary;
    @Unique
    private long copperGolemMod$tickStartNanos;

    protected CopperGolemMixin(EntityType<? extends AbstractGolem> type, Level level) {
        super(type, level);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void copperGolemMod$onTickHead(CallbackInfo ci) {
        if (this.level().isClientSide()) {
            return;
        }
        ModMetrics metrics = CopperGolemMod.metrics();
        metrics.golemTick();
        if (metrics.isProfiling()) {
            copperGolemMod$tickStartNanos = System.nanoTime();
        }
        copperGolemMod$updateStationaryState(CopperGolemMod.config());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void copperGolemMod$onTickReturn(CallbackInfo ci) {
        if (copperGolemMod$tickStartNanos != 0L) {
            CopperGolemMod.metrics().recordGolemTickNanos(System.nanoTime() - copperGolemMod$tickStartNanos);
            copperGolemMod$tickStartNanos = 0L;
        }
    }

    @Override
    public void travel(Vec3 input) {
        if (copperGolemMod$stationary
            && CopperGolemMod.config().stationarySorter.skipTravelWhenStationary
            && input.lengthSqr() < 1.0E-7D
            && !this.jumping
            && this.getDeltaMovement().y <= 0.0D) {
            CopperGolemMod.metrics().stationaryTravelSkipped();
            return;
        }
        super.travel(input);
    }

    @Override
    protected void pushEntities() {
        if (copperGolemMod$stationary
            && CopperGolemMod.config().stationarySorter.skipCrammingWhenStationary
            && !copperGolemMod$hasMovementIntent()) {
            CopperGolemMod.metrics().stationaryCrammingSkipped();
            return;
        }
        super.pushEntities();
    }

    /**
     * Decides once per tick whether this golem may skip physics. The golem must have stayed in the same block
     * for a while, be standing on the ground, and be barely moving. Every {@code fullPhysicsTickInterval}
     * ticks full physics always runs, so pushes, pistons, or a removed floor are still picked up.
     */
    @Unique
    private void copperGolemMod$updateStationaryState(CopperGolemModConfig config) {
        if (!config.isStationarySorterActive()) {
            copperGolemMod$stationary = false;
            copperGolemMod$ticksInSameBlock = 0;
            copperGolemMod$lastBlockPos = Long.MIN_VALUE;
            return;
        }

        long blockPos = this.blockPosition().asLong();
        if (blockPos == copperGolemMod$lastBlockPos) {
            if (copperGolemMod$ticksInSameBlock < Integer.MAX_VALUE) {
                copperGolemMod$ticksInSameBlock++;
            }
        } else {
            copperGolemMod$lastBlockPos = blockPos;
            copperGolemMod$ticksInSameBlock = 0;
        }

        CopperGolemModConfig.StationarySorter settings = config.stationarySorter;
        copperGolemMod$stationary = copperGolemMod$ticksInSameBlock >= settings.minStationaryTicks
            && Math.floorMod(this.tickCount + this.getId(), settings.fullPhysicsTickInterval) != 0
            && this.onGround()
            && !this.isInWater()
            && !this.isInLava()
            && !this.isPassenger()
            && !this.isVehicle()
            && !this.isLeashed()
            && this.getDeltaMovement().horizontalDistanceSqr() <= settings.maxHorizontalVelocitySquared;
    }

    @Unique
    private boolean copperGolemMod$hasMovementIntent() {
        return this.zza != 0.0F || this.xxa != 0.0F || this.yya != 0.0F || this.jumping;
    }
}
