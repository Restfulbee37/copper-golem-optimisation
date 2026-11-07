package com.restfulbee.coppergolemmod.test;

import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Optional;

/** Small helpers shared by the GameTests. Positions are relative to the test structure unless named absolute. */
final class TestSupport {
    private TestSupport() {
    }

    /** Fills the y = 0 layer of an arena with stone so mobs have something to stand on. */
    static void floor(GameTestHelper helper, int sizeX, int sizeZ) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    static Container container(GameTestHelper helper, BlockPos relativePos) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(helper.absolutePos(relativePos));
        if (blockEntity instanceof Container container) {
            return container;
        }
        throw new IllegalStateException("No container at " + relativePos);
    }

    /** Places a block that holds items (chest, copper chest) and puts the given stacks into its first slots. */
    static Container placeContainer(GameTestHelper helper, BlockPos relativePos, Block block, ItemStack... stacks) {
        helper.setBlock(relativePos, block);
        Container container = container(helper, relativePos);
        for (int slot = 0; slot < stacks.length; slot++) {
            container.setItem(slot, stacks[slot].copy());
        }
        return container;
    }

    /**
     * Places one half of a double chest (or a single chest) facing north without updating its neighbours, so
     * mismatched halves stay as placed. For a {@code LEFT} half, the other half goes one block east.
     */
    static void placeChestHalf(GameTestHelper helper, BlockPos relativePos, Block block, ChestType type) {
        BlockState state = block.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, type);
        helper.getLevel().setBlock(helper.absolutePos(relativePos), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** A cat sitting on the chest at this (relative) position, which blocks the chest for golems. It has no AI, so it stays. */
    static Cat sittingCat(GameTestHelper helper, BlockPos chestPos) {
        Cat cat = helper.spawn(EntityTypes.CAT, new Vec3(chestPos.getX() + 0.5D, chestPos.getY() + 0.875D, chestPos.getZ() + 0.5D));
        cat.setNoAi(true);
        cat.setInSittingPose(true);
        return cat;
    }

    /** Locks a chest (or any container block) with a lock no item matches. */
    static void lock(GameTestHelper helper, BlockPos relativePos) {
        try {
            Field lockKey = BaseContainerBlockEntity.class.getDeclaredField("lockKey");
            lockKey.setAccessible(true);
            lockKey.set(helper.getLevel().getBlockEntity(helper.absolutePos(relativePos)),
                new LockCode(ItemPredicate.Builder.item().withCount(MinMaxBounds.Ints.exactly(1000)).build()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Can't lock the container at " + relativePos, exception);
        }
    }

    static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static int countAll(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    /** The entity a golem is currently looking at, if its look target is an entity. */
    static Optional<Entity> lookTargetEntity(CopperGolem golem) {
        Optional<PositionTracker> target = golem.getBrain().getMemory(MemoryModuleType.LOOK_TARGET);
        return target.filter(EntityTracker.class::isInstance).map(tracker -> ((EntityTracker) tracker).getEntity());
    }

    /**
     * Whether the stationary sorter currently treats this golem as stationary. Reads the field the mod's mixin adds
     * to CopperGolem, so tests can prove the optimization actually engaged.
     */
    static boolean isStationary(CopperGolem golem) {
        try {
            Field field = CopperGolem.class.getDeclaredField("copperGolemMod$stationary");
            field.setAccessible(true);
            return field.getBoolean(golem);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CopperGolemMod's stationary state field is missing; was the mixin renamed?", exception);
        }
    }
}
