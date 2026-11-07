package com.restfulbee.coppergolemmod.mixin;

import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The range vanilla searches for viewers in, for {@code GolemOptimizations.isChestInUse}. */
@Mixin(ContainerOpenersCounter.class)
public interface ContainerOpenersCounterAccessor {
    @Accessor("maxInteractionRange")
    double copperGolemMod$maxInteractionRange();
}
