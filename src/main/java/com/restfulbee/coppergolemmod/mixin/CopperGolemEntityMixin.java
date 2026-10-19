package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.ModMetrics;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-golem tick bookkeeping plus the opt-in stationary sorter. {@code travel} and {@code tickCramming} are
 * overridden here instead of injected into {@code LivingEntity}, so no other entity type pays for these checks.
 */
@Mixin(CopperGolemEntity.class)
public abstract class CopperGolemEntityMixin extends GolemEntity {
    @Unique
    private long copperGolemMod$lastBlockPos = Long.MIN_VALUE;
    @Unique
    private int copperGolemMod$ticksInSameBlock;
    @Unique
    private boolean copperGolemMod$stationary;
    @Unique
    private long copperGolemMod$tickStartNanos;

    protected CopperGolemEntityMixin(EntityType<? extends GolemEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void copperGolemMod$onTickHead(CallbackInfo ci) {
        if (this.getEntityWorld().isClient()) {
            return;
        }
        ModMetrics metrics = CopperGolemMod.metrics();
        metrics.golemTick();
        if (metrics.shouldTimeGolemTicks()) {
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
    public void travel(Vec3d movementInput) {
        if (copperGolemMod$stationary
            && CopperGolemMod.config().stationarySorter.skipTravelWhenStationary
            && movementInput.lengthSquared() < 1.0E-7D
            && !this.jumping
            && this.getVelocity().y <= 0.0D) {
            CopperGolemMod.metrics().stationaryTravelSkipped();
            return;
        }
        super.travel(movementInput);
    }

    @Override
    protected void tickCramming() {
        if (copperGolemMod$stationary
            && CopperGolemMod.config().stationarySorter.skipCrammingWhenStationary
            && !copperGolemMod$hasMovementIntent()) {
            CopperGolemMod.metrics().stationaryCrammingSkipped();
            return;
        }
        super.tickCramming();
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

        long blockPos = this.getBlockPos().asLong();
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
            && Math.floorMod(this.age + this.getId(), settings.fullPhysicsTickInterval) != 0
            && this.isOnGround()
            && !this.isTouchingWater()
            && !this.isInLava()
            && !this.hasVehicle()
            && !this.hasPassengers()
            && !this.isLeashed()
            && this.getVelocity().horizontalLengthSquared() <= settings.maxHorizontalVelocitySquared;
    }

    @Unique
    private boolean copperGolemMod$hasMovementIntent() {
        return this.forwardSpeed != 0.0F || this.sidewaysSpeed != 0.0F || this.upwardSpeed != 0.0F || this.jumping;
    }
}
