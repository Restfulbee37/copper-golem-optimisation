package com.restfulbee.coppergolemmod.mixin;

import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a chest's viewer counter, for {@code GolemOptimizations.isChestInUse}. */
@Mixin(ChestBlockEntity.class)
public interface ChestBlockEntityAccessor {
    @Accessor("openersCounter")
    ContainerOpenersCounter copperGolemMod$openersCounter();
}
