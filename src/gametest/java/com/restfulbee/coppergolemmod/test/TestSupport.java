package com.restfulbee.coppergolemmod.test;

import net.minecraft.util.math.BlockPos;
import net.minecraft.test.TestContext;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.LookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.util.Optional;

/** Small helpers shared by the GameTests. Positions are relative to the test structure unless named absolute. */
final class TestSupport {
    private TestSupport() {
    }

    /** Fills the y = 0 layer of an arena with stone so mobs have something to stand on. */
    static void floor(TestContext helper, int sizeX, int sizeZ) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                helper.setBlockState(x, 0, z, Blocks.STONE);
            }
        }
    }

    static Inventory container(TestContext helper, BlockPos relativePos) {
        BlockEntity blockEntity = helper.getWorld().getBlockEntity(helper.getAbsolutePos(relativePos));
        if (blockEntity instanceof Inventory container) {
            return container;
        }
        throw new IllegalStateException("No container at " + relativePos);
    }

    /** Places a block that holds items (chest, copper chest) and puts the given stacks into its first slots. */
    static Inventory placeContainer(TestContext helper, BlockPos relativePos, Block block, ItemStack... stacks) {
        helper.setBlockState(relativePos, block);
        Inventory container = container(helper, relativePos);
        for (int slot = 0; slot < stacks.length; slot++) {
            container.setStack(slot, stacks[slot].copy());
        }
        return container;
    }

    static int count(Inventory container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static int countAll(Inventory container) {
        int total = 0;
        for (int slot = 0; slot < container.size(); slot++) {
            total += container.getStack(slot).getCount();
        }
        return total;
    }

    /** The entity a golem is currently looking at, if its look target is an entity. */
    static Optional<Entity> lookTargetEntity(CopperGolemEntity golem) {
        Optional<LookTarget> target = golem.getBrain().getOptionalRegisteredMemory(MemoryModuleType.LOOK_TARGET);
        return target.filter(EntityLookTarget.class::isInstance).map(tracker -> ((EntityLookTarget) tracker).getEntity());
    }

    /**
     * Whether the stationary sorter currently treats this golem as stationary. Reads the field the mod's mixin adds
     * to CopperGolemEntity, so tests can prove the optimization actually engaged.
     */
    static boolean isStationary(CopperGolemEntity golem) {
        try {
            Field field = CopperGolemEntity.class.getDeclaredField("copperGolemMod$stationary");
            field.setAccessible(true);
            return field.getBoolean(golem);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CopperGolemMod's stationary state field is missing; was the mixin renamed?", exception);
        }
    }
}
