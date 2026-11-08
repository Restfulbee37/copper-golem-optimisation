package com.restfulbee.coppergolemmod.test;

import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import com.restfulbee.coppergolemmod.ConfigScreen;
import com.restfulbee.coppergolemmod.ConfigSettings;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.GolemOptimizations;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemAi;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * In-game correctness tests: the mod must not change what copper golems do, only how cheaply they do it.
 * Each behavior is checked under the relevant configs:
 * {@code mod_off} (vanilla), {@code mod_on} (defaults), {@code stationary_sorter} and {@code look_disabled}.
 * The {@code /cgp config} commands are checked here too, in {@code config_commands}, which uses the real config file.
 */
public class CorrectnessTests {
    private static final String ARENA = "copper_golem_mod_test:arena";
    private static final String MOD_OFF = "copper_golem_mod_test:mod_off";
    private static final String MOD_ON = "copper_golem_mod_test:mod_on";
    private static final String STATIONARY = "copper_golem_mod_test:stationary_sorter";
    private static final String LOOK_DISABLED = "copper_golem_mod_test:look_disabled";
    private static final String CONFIG_COMMANDS = "copper_golem_mod_test:config_commands";
    private static final String CONSENT = "stationarySorter.iUnderstandThisChangesCopperGolemBehavior";
    /**
     * Golems search for chests up to 32 blocks away. Tests in the same batch run side by side only a few blocks
     * apart, so the arena is large (76x76) and every test is built around its middle: neighbouring tests' golems
     * and chests are then always out of reach. (Works on every Minecraft version, unlike GameTest padding.)
     */
    private static final int ARENA_SIZE = 76;
    private static final BlockPos ORIGIN = new BlockPos(32, 0, 32);

    /** A position in the middle of the arena (relative to {@link #ORIGIN}). */
    private static BlockPos at(int x, int y, int z) {
        return ORIGIN.offset(x, y, z);
    }

    private static Vec3 at(double x, double y, double z) {
        return new Vec3(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z);
    }

    // ---- Sorting: every item ends up in its matching chest, none lost or duplicated ----

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 3600)
    public void sortsItemsOpenModOff(GameTestHelper helper) {
        sortsItems(helper, false);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 3600)
    public void sortsItemsOpenModOn(GameTestHelper helper) {
        sortsItems(helper, false);
    }

    @GameTest(environment = STATIONARY, structure = ARENA, maxTicks = 3600)
    public void sortsItemsOpenStationarySorter(GameTestHelper helper) {
        sortsItems(helper, false);
    }

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 3600)
    public void sortsItemsTrappedModOff(GameTestHelper helper) {
        sortsItems(helper, true);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 3600)
    public void sortsItemsTrappedModOn(GameTestHelper helper) {
        sortsItems(helper, true);
    }

    @GameTest(environment = STATIONARY, structure = ARENA, maxTicks = 3600)
    public void sortsItemsTrappedStationarySorter(GameTestHelper helper) {
        sortsItems(helper, true);
    }

    /**
     * One copper chest with 32 cobblestone and 32 oak planks, and two output chests already holding one of each.
     * Open: a single golem has to walk between the chests. Trapped: two golems in a 1x1 glass cell with the chests
     * as its walls, like a real sorter cell, so they never need to walk (what the stationary sorter targets).
     */
    private static void sortsItems(GameTestHelper helper, boolean trapped) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        BlockPos input;
        BlockPos cobblestoneChest;
        BlockPos planksChest;
        List<Vec3> golemSpawns = new ArrayList<>();
        if (trapped) {
            BlockPos cell = at(6, 1, 6);
            input = cell.north();
            cobblestoneChest = cell.east();
            planksChest = cell.west();
            helper.setBlock(cell.south(), Blocks.GLASS);
            helper.setBlock(cell.above(), Blocks.GLASS);
            golemSpawns.add(at(6.5, 1, 6.5));
            golemSpawns.add(at(6.5, 1, 6.5));
        } else {
            input = at(2, 1, 2);
            cobblestoneChest = at(9, 1, 9);
            planksChest = at(9, 1, 3);
            golemSpawns.add(at(5.5, 1, 5.5));
        }

        Container inputChest = TestSupport.placeContainer(helper, input, Blocks.COPPER_CHEST.weathering().unaffected(),
            new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.OAK_PLANKS, 32));
        Container cobblestoneOut = TestSupport.placeContainer(helper, cobblestoneChest, Blocks.CHEST, new ItemStack(Items.COBBLESTONE, 1));
        Container planksOut = TestSupport.placeContainer(helper, planksChest, Blocks.CHEST, new ItemStack(Items.OAK_PLANKS, 1));
        List<CopperGolem> golems = golemSpawns.stream().map(pos -> helper.spawn(EntityTypes.COPPER_GOLEM, pos)).toList();

        helper.succeedWhen(() -> {
            helper.assertTrue(inputChest.isEmpty(), "the input chest still has items");
            for (CopperGolem golem : golems) {
                helper.assertTrue(golem.getMainHandItem().isEmpty(), "a golem is still carrying " + golem.getMainHandItem());
            }
            helper.assertValueEqual(TestSupport.count(cobblestoneOut, Items.COBBLESTONE), 33, "cobblestone in the cobblestone chest");
            helper.assertValueEqual(TestSupport.countAll(cobblestoneOut), 33, "items in the cobblestone chest");
            helper.assertValueEqual(TestSupport.count(planksOut, Items.OAK_PLANKS), 33, "oak planks in the planks chest");
            helper.assertValueEqual(TestSupport.countAll(planksOut), 33, "items in the planks chest");
        });
    }

    // ---- Looking at players ----

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 400)
    public void looksAtNearbyPlayerModOff(GameTestHelper helper) {
        looksAtPlayer(helper, true);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 400)
    public void looksAtNearbyPlayerModOn(GameTestHelper helper) {
        looksAtPlayer(helper, true);
    }

    @GameTest(environment = LOOK_DISABLED, structure = ARENA, maxTicks = 400)
    public void neverLooksAtPlayerWhenDisabled(GameTestHelper helper) {
        looksAtPlayer(helper, false);
    }

    /**
     * A golem with nothing to sort and a player 3 blocks away. Vanilla looks at the player within a few seconds
     * (sensor refresh up to 20 ticks, then a random 40-80 tick interval), so 300 ticks is plenty.
     */
    @SuppressWarnings("removal") // Mojang marks the mock-player helper for removal but has no replacement yet.
    private static void looksAtPlayer(GameTestHelper helper, boolean expectLook) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(3.5, 1, 6.5));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 playerPos = helper.absoluteVec(at(6.5, 1, 6.5));
        player.snapTo(playerPos.x, playerPos.y, playerPos.z, 90.0F, 0.0F);

        long[] lookedAtTick = {-1};
        helper.onEachTick(() -> {
            if (lookedAtTick[0] < 0 && TestSupport.lookTargetEntity(golem).filter(entity -> entity == player).isPresent()) {
                lookedAtTick[0] = helper.getTick();
            }
        });
        helper.runAtTickTime(300, () -> {
            // Remove the player before any assertion, so a failure can't leave it standing in later tests.
            helper.getLevel().getServer().getPlayerList().remove(player);
            if (expectLook) {
                helper.assertTrue(lookedAtTick[0] >= 0, "the golem never looked at a player 3 blocks away");
            } else {
                helper.assertTrue(lookedAtTick[0] < 0, "the golem looked at the player (tick " + lookedAtTick[0] + ") although golemsLookAtPlayers is false");
            }
            helper.succeed();
        });
    }

    // ---- Nearby-entity sensor: our sort must give exactly vanilla's result ----

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 20)
    public void nearbyEntityListMatchesVanilla(GameTestHelper helper) throws ReflectiveOperationException {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(6.5, 1, 6.5));

        List<Vec3> spots = new ArrayList<>(List.of(
            // Equal distances, so the stable ordering of ties is checked.
            at(8.5, 1, 6.5), at(4.5, 1, 6.5), at(6.5, 1, 8.5), at(6.5, 1, 4.5),
            at(8.5, 1, 8.5), at(4.5, 1, 4.5), at(8.5, 1, 4.5), at(4.5, 1, 8.5)
        ));
        Random random = new Random(1234);
        for (int i = 0; i < 24; i++) {
            spots.add(at(1 + random.nextInt(10) + 0.5, 1, 1 + random.nextInt(10) + 0.5));
        }
        spots.forEach(spot -> helper.spawn(EntityTypes.ARMOR_STAND, spot));

        Method doTick = NearestLivingEntitySensor.class.getDeclaredMethod("doTick", ServerLevel.class, LivingEntity.class);
        doTick.setAccessible(true);
        NearestLivingEntitySensor<LivingEntity> sensor = new NearestLivingEntitySensor<>();
        CopperGolemModConfig testConfig = CopperGolemMod.baseConfig();
        try {
            CopperGolemMod.setConfigOverride(ModConfigEnvironment.config(false, false, true, true));
            doTick.invoke(sensor, helper.getLevel(), golem);
            List<LivingEntity> vanilla = List.copyOf(golem.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES).orElseThrow());

            CopperGolemMod.setConfigOverride(testConfig);
            doTick.invoke(sensor, helper.getLevel(), golem);
            List<LivingEntity> optimized = List.copyOf(golem.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES).orElseThrow());

            helper.assertTrue(vanilla.size() >= spots.size(), "vanilla sensor found only " + vanilla.size() + " of " + spots.size() + " entities");
            helper.assertValueEqual(optimized.size(), vanilla.size(), "number of nearby entities");
            for (int i = 0; i < vanilla.size(); i++) {
                helper.assertTrue(optimized.get(i) == vanilla.get(i), "entity #" + i + " differs from vanilla's order");
            }
        } finally {
            CopperGolemMod.setConfigOverride(testConfig);
        }
        helper.succeed();
    }

    // ---- Chests: golems wait while a chest is in use, and pick the same chest as vanilla ----

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 1200)
    public void waitsWhileChestIsInUseModOff(GameTestHelper helper) {
        waitsWhileChestIsInUse(helper);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 1200)
    public void waitsWhileChestIsInUseModOn(GameTestHelper helper) {
        waitsWhileChestIsInUse(helper);
    }

    /**
     * A golem carrying cobblestone heads for the only chest that takes it while a player has that chest open. It must
     * walk over and wait near the chest without putting anything in, and deliver everything once the player closes it.
     * Vanilla starts waiting once the golem is within 3 blocks of the chest's outline on each axis, which can be up to
     * about 5 blocks from the chest's centre when the golem stops diagonally, so it starts 8 blocks away.
     */
    @SuppressWarnings("removal") // Mojang marks the mock-player helper for removal but has no replacement yet.
    private static void waitsWhileChestIsInUse(GameTestHelper helper) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        BlockPos chestPos = at(9, 1, 6);
        Container chest = TestSupport.placeContainer(helper, chestPos, Blocks.CHEST, new ItemStack(Items.COBBLESTONE, 1));
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(1.5, 1, 6.5));
        golem.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 8));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 playerPos = helper.absoluteVec(at(10.5, 1, 6.5));
        player.snapTo(playerPos.x, playerPos.y, playerPos.z, 90.0F, 0.0F);
        BlockPos absoluteChest = helper.absolutePos(chestPos);
        player.openMenu(helper.getLevel().getBlockState(absoluteChest).getMenuProvider(helper.getLevel(), absoluteChest));

        double[] closest = {Double.MAX_VALUE};
        int[] itemsWhileOpen = {1};
        helper.onEachTick(() -> {
            if (helper.getTick() < 300) {
                closest[0] = Math.min(closest[0], golem.position().distanceTo(Vec3.atBottomCenterOf(absoluteChest)));
                itemsWhileOpen[0] = Math.max(itemsWhileOpen[0], TestSupport.countAll(chest));
            }
        });
        helper.runAtTickTime(300, player::closeContainer);
        helper.runAtTickTime(900, () -> {
            // Remove the player before any assertion, so a failure can't leave it standing in later tests.
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.assertTrue(closest[0] < 5.5D, "the golem came no closer than " + closest[0] + " blocks to the chest while it was open");
            helper.assertValueEqual(itemsWhileOpen[0], 1, "items in the chest while the player had it open");
            helper.assertValueEqual(TestSupport.count(chest, Items.COBBLESTONE), 9, "cobblestone in the chest after it was closed");
            helper.assertTrue(golem.getMainHandItem().isEmpty(), "the golem is still carrying " + golem.getMainHandItem());
            helper.succeed();
        });
    }

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 1200)
    public void skipsBlockedAndFullChestsModOff(GameTestHelper helper) {
        skipsBlockedAndFullChests(helper);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 1200)
    public void skipsBlockedAndFullChestsModOn(GameTestHelper helper) {
        skipsBlockedAndFullChests(helper);
    }

    /**
     * A golem carrying cobblestone, and three chests that could take it, nearest first: one with a cat sitting on it,
     * one full of dirt, and one with cobblestone. Vanilla never picks the blocked chest, tries the full one, remembers
     * it as visited and delivers to the last one.
     */
    private static void skipsBlockedAndFullChests(GameTestHelper helper) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        BlockPos catChestPos = at(5, 1, 8);
        Container catChest = TestSupport.placeContainer(helper, catChestPos, Blocks.CHEST, new ItemStack(Items.COBBLESTONE, 1));
        TestSupport.sittingCat(helper, catChestPos);
        BlockPos fullChestPos = at(8, 1, 4);
        helper.setBlock(fullChestPos, Blocks.CHEST);
        Container fullChest = TestSupport.container(helper, fullChestPos);
        for (int slot = 0; slot < fullChest.getContainerSize(); slot++) {
            fullChest.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        Container goodChest = TestSupport.placeContainer(helper, at(12, 1, 6), Blocks.CHEST, new ItemStack(Items.COBBLESTONE, 1));
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(2.5, 1, 6.5));
        golem.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 8));

        helper.succeedWhen(() -> {
            helper.assertTrue(golem.getMainHandItem().isEmpty(), "the golem is still carrying " + golem.getMainHandItem());
            helper.assertValueEqual(TestSupport.count(goodChest, Items.COBBLESTONE), 9, "cobblestone in the chest that takes it");
            helper.assertValueEqual(TestSupport.countAll(catChest), 1, "items in the chest with a cat on it");
            helper.assertValueEqual(TestSupport.countAll(fullChest), 64 * fullChest.getContainerSize(), "items in the full chest");
        });
    }

    /**
     * Asks "is anyone using this chest?" (TransportItemsBetweenContainers.isAnotherMobInteractingWithTarget) for single,
     * double, trapped and copper chests, with the mod off and on, while players and golems use them from different
     * distances and a cat blocks one half. The answers must be identical.
     */
    @SuppressWarnings("removal") // Mojang marks the mock-player helper for removal but has no replacement yet.
    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 20)
    public void chestInUseCheckMatchesVanilla(GameTestHelper helper) throws ReflectiveOperationException {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        ServerLevel level = helper.getLevel();
        BlockPos single = at(3, 1, 3);
        BlockPos left = at(7, 1, 3);
        BlockPos right = at(8, 1, 3);
        helper.setBlock(single, Blocks.CHEST);
        TestSupport.placeChestHalf(helper, left, Blocks.CHEST, ChestType.LEFT);
        TestSupport.placeChestHalf(helper, right, Blocks.CHEST, ChestType.RIGHT);
        helper.setBlock(at(3, 1, 9), Blocks.TRAPPED_CHEST);
        helper.setBlock(at(7, 1, 9), Blocks.COPPER_CHEST.weathering().unaffected());
        List<BlockPos> chests = List.of(single, left, right, at(3, 1, 9), at(7, 1, 9));

        Method inUse = TransportItemsBetweenContainers.class.getDeclaredMethod("isAnotherMobInteractingWithTarget", TransportItemTarget.class, Level.class);
        inUse.setAccessible(true);
        TransportItemsBetweenContainers behavior = copperGolemTransportBehavior();
        CopperGolemModConfig testConfig = CopperGolemMod.baseConfig();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            // The answer for each chest in `chests`, after checking that vanilla and the mod agree.
            java.util.function.Function<String, List<Boolean>> ask = scenario -> {
                List<Boolean> answers = new ArrayList<>();
                for (BlockPos chest : chests) {
                    BlockEntity blockEntity = level.getBlockEntity(helper.absolutePos(chest));
                    TransportItemTarget target = new TransportItemTarget(blockEntity.getBlockPos(), (Container) blockEntity, blockEntity, blockEntity.getBlockState());
                    try {
                        CopperGolemMod.setConfigOverride(ModConfigEnvironment.config(false, false, true, true));
                        boolean vanilla = (Boolean) inUse.invoke(behavior, target, level);
                        CopperGolemMod.setConfigOverride(testConfig);
                        boolean optimized = (Boolean) inUse.invoke(behavior, target, level);
                        helper.assertValueEqual(optimized, vanilla, scenario + ": chest at " + chest + " in use");
                        answers.add(vanilla);
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
                return answers;
            };
            helper.assertValueEqual(ask.apply("nobody"), List.of(false, false, false, false, false), "in use with nobody around");

            placePlayer(helper, player, at(3.5, 1, 12.0));
            openChest(helper, player, single);
            helper.assertValueEqual(ask.apply("player 8 blocks away"), List.of(true, false, false, false, false), "in use with a player 8 blocks away");
            placePlayer(helper, player, at(3.5, 1, 13.5));
            helper.assertValueEqual(ask.apply("player 9.5 blocks away"), List.of(false, false, false, false, false), "in use with a player out of range");
            player.closeContainer();

            placePlayer(helper, player, at(7.5, 1, 1.5));
            openChest(helper, player, left);
            helper.assertValueEqual(ask.apply("player at the double chest"), List.of(false, true, true, false, false), "in use with the double chest open");
            player.closeContainer();

            // A golem using the right half from where only the right half's search reaches it.
            CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(13.0, 1, 3.5));
            golem.setOpenedChestPos(helper.absolutePos(right));
            helper.assertValueEqual(ask.apply("golem at the right half"), List.of(false, true, true, false, false), "in use by a golem at the right half");
            // Vanilla doesn't check a blocked other half.
            TestSupport.sittingCat(helper, right);
            helper.assertValueEqual(ask.apply("cat on the right half"), List.of(false, false, true, false, false), "in use with a cat on the right half");
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
            CopperGolemMod.setConfigOverride(testConfig);
        }
        helper.succeed();
    }

    /**
     * Runs a copper golem's chest check (TransportItemsBetweenContainers.isTargetValidToPick) on chests of every kind,
     * blocked, locked, out of reach and half-broken, empty-handed and carrying items, with different chests visited or
     * unreachable, with the mod off and on. The chest picked (or not) must be identical.
     */
    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 20)
    public void chestSearchMatchesVanilla(GameTestHelper helper) throws ReflectiveOperationException {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        ServerLevel level = helper.getLevel();
        List<BlockPos> chests = new ArrayList<>();
        BlockPos copper = at(2, 1, 2);
        helper.setBlock(copper, Blocks.COPPER_CHEST.weathering().unaffected());
        BlockPos copperLeft = at(2, 1, 4);
        BlockPos copperRight = at(3, 1, 4);
        TestSupport.placeChestHalf(helper, copperLeft, Blocks.COPPER_CHEST.weathering().unaffected(), ChestType.LEFT);
        TestSupport.placeChestHalf(helper, copperRight, Blocks.COPPER_CHEST.weathering().unaffected(), ChestType.RIGHT);
        BlockPos single = at(10, 1, 2);
        helper.setBlock(single, Blocks.CHEST);
        BlockPos left = at(2, 1, 10);
        BlockPos right = at(3, 1, 10);
        TestSupport.placeChestHalf(helper, left, Blocks.CHEST, ChestType.LEFT);
        TestSupport.placeChestHalf(helper, right, Blocks.CHEST, ChestType.RIGHT);
        BlockPos trapped = at(10, 1, 10);
        helper.setBlock(trapped, Blocks.TRAPPED_CHEST);
        BlockPos locked = at(6, 1, 12);
        helper.setBlock(locked, Blocks.CHEST);
        TestSupport.lock(helper, locked);
        BlockPos coveredByStone = at(12, 1, 6);
        helper.setBlock(coveredByStone, Blocks.CHEST);
        helper.setBlock(coveredByStone.above(), Blocks.STONE);
        BlockPos withCat = at(0, 1, 6);
        helper.setBlock(withCat, Blocks.CHEST);
        TestSupport.sittingCat(helper, withCat);
        BlockPos catLeft = at(6, 1, 0);
        BlockPos catRight = at(7, 1, 0);
        TestSupport.placeChestHalf(helper, catLeft, Blocks.CHEST, ChestType.LEFT);
        TestSupport.placeChestHalf(helper, catRight, Blocks.CHEST, ChestType.RIGHT);
        TestSupport.sittingCat(helper, catRight);
        // A half that claims to be a double chest next to a single chest, as a broken world might have.
        BlockPos brokenLeft = at(12, 1, 12);
        BlockPos besideBroken = at(13, 1, 12);
        TestSupport.placeChestHalf(helper, besideBroken, Blocks.CHEST, ChestType.SINGLE);
        TestSupport.placeChestHalf(helper, brokenLeft, Blocks.CHEST, ChestType.LEFT);
        BlockPos tooHigh = at(4, 11, 4);
        helper.setBlock(tooHigh, Blocks.CHEST);
        chests.addAll(List.of(copper, copperLeft, copperRight, single, left, right, trapped, locked, coveredByStone,
            withCat, catLeft, catRight, brokenLeft, besideBroken, tooHigh));

        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(6.5, 1, 6.5));
        Method validToPick = TransportItemsBetweenContainers.class.getDeclaredMethod("isTargetValidToPick",
            PathfinderMob.class, Level.class, BlockEntity.class, Set.class, Set.class, AABB.class);
        validToPick.setAccessible(true);
        TransportItemsBetweenContainers behavior = copperGolemTransportBehavior();
        AABB searchArea = new AABB(golem.blockPosition()).inflate(32, 8, 32);

        List<List<BlockPos>> visitedSets = List.of(List.of(), List.of(single), List.of(left), List.of(right), List.of(copperRight),
            List.of(catLeft), List.of(catRight), List.of(besideBroken), List.of(brokenLeft, trapped));
        CopperGolemModConfig testConfig = CopperGolemMod.baseConfig();
        int picked = 0;
        int notPicked = 0;
        try {
            for (ItemStack hand : List.of(ItemStack.EMPTY, new ItemStack(Items.COBBLESTONE, 4))) {
                golem.setItemSlot(EquipmentSlot.MAINHAND, hand.copy());
                for (List<BlockPos> visitedSet : visitedSets) {
                    for (boolean asUnreachable : List.of(false, true)) {
                        Set<GlobalPos> marked = new HashSet<>();
                        visitedSet.forEach(pos -> marked.add(new GlobalPos(level.dimension(), helper.absolutePos(pos))));
                        Set<GlobalPos> visited = asUnreachable ? Set.of() : marked;
                        Set<GlobalPos> unreachable = asUnreachable ? marked : Set.of();
                        for (BlockPos chest : chests) {
                            BlockEntity blockEntity = level.getBlockEntity(helper.absolutePos(chest));
                            CopperGolemMod.setConfigOverride(ModConfigEnvironment.config(false, false, true, true));
                            TransportItemTarget vanilla = (TransportItemTarget) validToPick.invoke(behavior, golem, level, blockEntity, visited, unreachable, searchArea);
                            CopperGolemMod.setConfigOverride(testConfig);
                            TransportItemTarget optimized = (TransportItemTarget) validToPick.invoke(behavior, golem, level, blockEntity, visited, unreachable, searchArea);
                            String what = "chest at " + chest + " with " + (hand.isEmpty() ? "an empty hand" : "items in hand")
                                + (asUnreachable ? ", unreachable " : ", visited ") + visitedSet;
                            helper.assertValueEqual(optimized == null, vanilla == null, what + " picked");
                            if (vanilla != null) {
                                picked++;
                                helper.assertTrue(optimized.blockEntity() == vanilla.blockEntity() && optimized.pos().equals(vanilla.pos())
                                    && optimized.state() == vanilla.state() && optimized.container().getClass() == vanilla.container().getClass()
                                    && optimized.container().getContainerSize() == vanilla.container().getContainerSize(), what + ": target differs from vanilla's");
                            } else {
                                notPicked++;
                            }
                        }
                    }
                }
            }
        } finally {
            CopperGolemMod.setConfigOverride(testConfig);
        }
        helper.assertTrue(picked > 50 && notPicked > 50, "too few cases to compare: " + picked + " picked, " + notPicked + " not");
        helper.succeed();
    }

    /** A copper golem's item-moving behavior, built from the same pieces vanilla uses (with the mod's mixins applied). */
    @SuppressWarnings("unchecked")
    private static TransportItemsBetweenContainers copperGolemTransportBehavior() throws ReflectiveOperationException {
        Method shouldQueue = CopperGolemAi.class.getDeclaredMethod("shouldQueueForTarget");
        shouldQueue.setAccessible(true);
        Predicate<TransportItemTarget> queuePredicate = (Predicate<TransportItemTarget>) shouldQueue.invoke(null);
        if (!(queuePredicate instanceof GolemOptimizations.ChestInUseCheck)) {
            throw new IllegalStateException("CopperGolemAi.shouldQueueForTarget wasn't wrapped by the mod");
        }
        Field source = CopperGolemAi.class.getDeclaredField("TRANSPORT_ITEM_SOURCE_BLOCK");
        Field destination = CopperGolemAi.class.getDeclaredField("TRANSPORT_ITEM_DESTINATION_BLOCK");
        source.setAccessible(true);
        destination.setAccessible(true);
        return new TransportItemsBetweenContainers(1.0F, (Predicate<BlockState>) source.get(null), (Predicate<BlockState>) destination.get(null),
            32, 8, Map.of(), body -> {
        }, queuePredicate);
    }

    private static void placePlayer(GameTestHelper helper, ServerPlayer player, Vec3 relativePos) {
        Vec3 pos = helper.absoluteVec(relativePos);
        player.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
    }

    private static void openChest(GameTestHelper helper, ServerPlayer player, BlockPos relativePos) {
        BlockPos pos = helper.absolutePos(relativePos);
        player.openMenu(helper.getLevel().getBlockState(pos).getMenuProvider(helper.getLevel(), pos));
    }

    // ---- Idle throttle: sensors are throttled, but the cooldown itself is not slowed ----

    @GameTest(environment = MOD_OFF, structure = ARENA, maxTicks = 200)
    public void idleCooldownLengthModOff(GameTestHelper helper) {
        idleCooldownLength(helper, false);
    }

    @GameTest(environment = MOD_ON, structure = ARENA, maxTicks = 200)
    public void idleCooldownLengthModOn(GameTestHelper helper) {
        idleCooldownLength(helper, true);
    }

    /** Puts a golem into a 100-tick "nothing to sort" cooldown and checks it ends after ~100 ticks either way. */
    private static void idleCooldownLength(GameTestHelper helper, boolean expectThrottle) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(6.5, 1, 6.5));
        golem.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 100);

        long[] endedAt = {-1};
        int[] lastValue = {100};
        boolean[] throttled = {false};
        helper.onEachTick(() -> {
            if (endedAt[0] >= 0) {
                return;
            }
            Optional<Integer> value = golem.getBrain().getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
            // Ends when the memory disappears, or is immediately set again by a new search (value goes back up).
            if (value.isEmpty() || value.get() > lastValue[0]) {
                endedAt[0] = helper.getTick();
                return;
            }
            lastValue[0] = value.get();
            throttled[0] |= GolemOptimizations.shouldSkipIdleSensors(golem);
        });
        helper.runAtTickTime(150, () -> {
            helper.assertTrue(endedAt[0] >= 95 && endedAt[0] <= 110, "a 100-tick cooldown ended at tick " + endedAt[0]);
            helper.assertValueEqual(throttled[0], expectThrottle, "idle sensor throttle engaged");
            helper.succeed();
        });
    }

    // ---- Stationary sorter: physics is skipped, but never at the cost of a golem floating ----

    @GameTest(environment = STATIONARY, structure = ARENA, maxTicks = 200)
    public void stationaryGolemFallsWhenFloorRemoved(GameTestHelper helper) {
        TestSupport.floor(helper, ARENA_SIZE, ARENA_SIZE);
        BlockPos pillar = at(6, 1, 6);
        helper.setBlock(pillar, Blocks.STONE);
        for (int y = 2; y <= 3; y++) {
            helper.setBlock(at(5, y, 6), Blocks.GLASS);
            helper.setBlock(at(7, y, 6), Blocks.GLASS);
            helper.setBlock(at(6, y, 5), Blocks.GLASS);
            helper.setBlock(at(6, y, 7), Blocks.GLASS);
        }
        CopperGolem golem = helper.spawn(EntityTypes.COPPER_GOLEM, at(6.5, 2, 6.5));

        boolean[] wasStationary = {false};
        double[] heightBeforeDrop = {0.0D};
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick >= 60 && tick < 80) {
                wasStationary[0] |= TestSupport.isStationary(golem);
            }
        });
        helper.runAtTickTime(80, () -> {
            helper.assertTrue(wasStationary[0], "the stationary sorter never engaged for a golem standing still for 60 ticks");
            heightBeforeDrop[0] = golem.getY();
            helper.setBlock(pillar, Blocks.AIR);
        });
        helper.runAtTickTime(130, () -> {
            helper.assertTrue(golem.getY() < heightBeforeDrop[0] - 0.9D,
                "the golem is still at y=" + golem.getY() + " 50 ticks after its floor was removed (was " + heightBeforeDrop[0] + ")");
            helper.succeed();
        });
    }

    // ---- /cgp config: changes apply at once and are saved, within limits, and never skip the consent step ----
    // Each test does all its work in its first tick, so tests sharing the config file never interleave.

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void configSetAppliesAtOnceAndIsSaved(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        op.restoringConfig(() -> {
            op.run("cgp config reset idleThrottle.sensorTickInterval");
            helper.assertValueEqual(op.run("cgp config set idleThrottle.sensorTickInterval 6"), 1, "result of config set");
            helper.assertTrue(op.replies().contains("idleThrottle.sensorTickInterval set to 6 (was 4)"), "unexpected reply: " + op.replies());
            helper.assertValueEqual(CopperGolemMod.config().idleThrottle.sensorTickInterval, 6, "sensorTickInterval in use");
            helper.assertValueEqual(savedValue("idleThrottle.sensorTickInterval"), "6", "sensorTickInterval in the config file");

            op.run("cgp config get idleThrottle.sensorTickInterval");
            helper.assertTrue(op.replies().startsWith("idleThrottle.sensorTickInterval = 6 (default 4, allowed 1 to 20)"), "unexpected reply: " + op.replies());
            helper.assertValueEqual(op.run("cgp config list"), ConfigSettings.ALL.size(), "settings listed");

            op.run("cgp config reset idleThrottle.sensorTickInterval");
            helper.assertValueEqual(CopperGolemMod.config().idleThrottle.sensorTickInterval, 4, "sensorTickInterval after reset");
            helper.assertValueEqual(savedValue("idleThrottle.sensorTickInterval"), "4", "sensorTickInterval in the file after reset");
        });
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void configTabCompletes(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        List<String> settings = op.suggestions("cgp config set ");
        helper.assertValueEqual(settings.size(), ConfigSettings.ALL.size(), "settings suggested for /cgp config set");
        helper.assertValueEqual(op.suggestions("cgp config set idleThrottle.s"), List.of("idleThrottle.sensorTickInterval"), "completion of idleThrottle.s");
        helper.assertValueEqual(op.suggestions("cgp config set enabled "), List.of("false", "true"), "values suggested for a switch");
        List<String> numbers = op.suggestions("cgp config set idleThrottle.sensorTickInterval ");
        helper.assertTrue(numbers.containsAll(List.of("4", "1", "20")), "values suggested for sensorTickInterval: " + numbers);
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void configRejectsInvalidValues(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        String fileBefore = CommandRunner.readConfigFile();
        for (String command : List.of(
            "cgp config set idleThrottle.sensorTickInterval 21",
            "cgp config set idleThrottle.sensorTickInterval 0",
            "cgp config set idleThrottle.sensorTickInterval four",
            "cgp config set stationarySorter.maxHorizontalVelocitySquared -0.5",
            "cgp config set enabled maybe",
            "cgp config set noSuchSetting 1"
        )) {
            helper.assertTrue(op.rejects(command), "/" + command + " was accepted");
        }
        helper.assertValueEqual(CommandRunner.readConfigFile(), fileBefore, "config file after rejected commands");
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void stationarySorterNeedsTwoStepConsent(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        op.restoringConfig(() -> {
            op.run("cgp config reset stationarySorter.enabled");
            op.run("cgp config reset " + CONSENT);

            helper.assertValueEqual(op.run("cgp config set stationarySorter.enabled true"), 0, "enabling without consent");
            helper.assertTrue(op.replies().contains("/cgp accept-consequences first"), "unexpected reply: " + op.replies());
            helper.assertValueEqual(op.run("cgp config set " + CONSENT + " true"), 0, "setting the consent flag directly");
            helper.assertTrue(!CopperGolemMod.config().stationarySorter.enabled
                && !CopperGolemMod.config().stationarySorter.iUnderstandThisChangesCopperGolemBehavior, "a refused command changed the config");

            op.run("cgp accept-consequences");
            helper.assertTrue(op.replies().contains("again within 60 seconds to confirm"), "unexpected reply: " + op.replies());
            helper.assertTrue(!CopperGolemMod.config().stationarySorter.iUnderstandThisChangesCopperGolemBehavior, "accepted after one run");
            op.run("cgp accept-consequences");
            helper.assertTrue(CopperGolemMod.config().stationarySorter.iUnderstandThisChangesCopperGolemBehavior, "not accepted after confirming");
            helper.assertValueEqual(savedValue(CONSENT), "true", "consent in the config file");

            helper.assertValueEqual(op.run("cgp config set stationarySorter.enabled true"), 1, "enabling after consent");
            helper.assertTrue(CopperGolemMod.config().isStationarySorterActive(), "the stationary sorter isn't active");
            op.run("cgp config set " + CONSENT + " false");
            helper.assertTrue(!CopperGolemMod.config().isStationarySorterActive(), "withdrawing consent left the stationary sorter on");
        });
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void configChangesAreRefusedWhileBenchmarking(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        op.restoringConfig(() -> {
            String fileBefore = CommandRunner.readConfigFile();
            op.run("cgp bench current 1");
            for (String command : List.of("cgp config set idleThrottle.sensorTickInterval 6",
                "cgp config reset idleThrottle.sensorTickInterval", "cgp config apply idleThrottle.sensorTickInterval=6",
                "cgp accept-consequences")) {
                helper.assertValueEqual(op.run(command), 0, "result of /" + command + " during a benchmark");
                helper.assertTrue(op.replies().contains("benchmark is running"), "unexpected reply to /" + command + ": " + op.replies());
            }
            helper.assertValueEqual(CommandRunner.readConfigFile(), fileBefore, "config file during the benchmark");
            op.run("cgp bench stop");
        });
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void configApplyChangesSeveralSettingsAtOnce(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        op.restoringConfig(() -> {
            op.run("cgp config reset idleThrottle.sensorTickInterval");
            op.run("cgp config reset behavior.golemsLookAtPlayers");
            op.run("cgp config reset " + CONSENT);

            helper.assertValueEqual(op.run("cgp config apply idleThrottle.sensorTickInterval=6 behavior.golemsLookAtPlayers=false"), 2, "settings changed");
            helper.assertTrue(op.replies().contains("changed idleThrottle.sensorTickInterval 4 -> 6, behavior.golemsLookAtPlayers true -> false"),
                "unexpected reply: " + op.replies());
            helper.assertValueEqual(CopperGolemMod.config().idleThrottle.sensorTickInterval, 6, "sensorTickInterval in use");
            helper.assertValueEqual(savedValue("behavior.golemsLookAtPlayers"), "false", "golemsLookAtPlayers in the config file");

            // All or nothing: one bad value means nothing changes.
            String fileBefore = CommandRunner.readConfigFile();
            for (String command : List.of(
                "cgp config apply idleThrottle.sensorTickInterval=8 metrics.logIntervalSeconds=99999",
                "cgp config apply idleThrottle.sensorTickInterval=8 noSuchSetting=1",
                "cgp config apply idleThrottle.sensorTickInterval=8 enabled",
                "cgp config apply idleThrottle.sensorTickInterval=8 stationarySorter.enabled=true",
                "cgp config apply " + CONSENT + "=true"
            )) {
                helper.assertValueEqual(op.run(command), 0, "result of /" + command);
                helper.assertTrue(op.replies().startsWith("No settings were changed"), "unexpected reply to /" + command + ": " + op.replies());
            }
            helper.assertValueEqual(CommandRunner.readConfigFile(), fileBefore, "config file after refused apply commands");
        });
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void settingsScreenSaveRunsTheConfigCommand(GameTestHelper helper) {
        CommandRunner op = new CommandRunner(helper);
        op.restoringConfig(() -> {
            op.run("cgp config reset idleThrottle.sensorTickInterval");
            op.run("cgp config reset behavior.golemsLookAtPlayers");
            op.run("cgp config reset " + CONSENT);

            Dialog screen = ConfigScreen.build(CopperGolemMod.config());
            // The screen must survive vanilla's own validation, as it does when it's sent to a player.
            var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            Dialog.DIRECT_CODEC.parse(ops, Dialog.DIRECT_CODEC.encodeStart(ops, screen).getOrThrow()).getOrThrow();
            List<String> keys = screen.common().inputs().stream().map(Input::key).toList();
            helper.assertValueEqual(keys.size(), ConfigSettings.ALL.size() - 2, "settings on the screen before consent");
            helper.assertTrue(!keys.contains(ConfigScreen.inputKey(ConfigSettings.STATIONARY_SORTER))
                && !keys.contains(ConfigScreen.inputKey(ConfigSettings.STATIONARY_SORTER_CONSENT)), "the screen offers the stationary sorter before consent");

            // Saving the screen untouched must change nothing: every value survives the trip through the screen.
            CompoundTag values = untouchedScreenValues(screen);
            String fileBefore = CommandRunner.readConfigFile();
            op.saveSettingsScreen(values);
            helper.assertTrue(op.replies().contains("No settings changed"), "unexpected reply: " + op.replies());
            helper.assertValueEqual(CommandRunner.readConfigFile(), fileBefore, "config file after saving an untouched screen");

            values.putFloat(ConfigScreen.inputKey(ConfigSettings.SENSOR_TICK_INTERVAL), 6.0F);
            values.putBoolean(ConfigScreen.inputKey(ConfigSettings.GOLEMS_LOOK_AT_PLAYERS), false);
            op.saveSettingsScreen(values);
            helper.assertTrue(op.replies().contains("changed idleThrottle.sensorTickInterval 4 -> 6, behavior.golemsLookAtPlayers true -> false"),
                "unexpected reply: " + op.replies());
            helper.assertValueEqual(CopperGolemMod.config().idleThrottle.sensorTickInterval, 6, "sensorTickInterval in use");
            helper.assertValueEqual(savedValue("idleThrottle.sensorTickInterval"), "6", "sensorTickInterval in the config file");

            op.run("cgp accept-consequences");
            op.run("cgp accept-consequences");
            helper.assertTrue(ConfigScreen.build(CopperGolemMod.config()).common().inputs().stream()
                .anyMatch(input -> input.key().equals(ConfigScreen.inputKey(ConfigSettings.STATIONARY_SORTER))),
                "the screen doesn't offer the stationary sorter after consent");
        });
        helper.succeed();
    }

    /** What a player's game sends back when Save is pressed without changing anything. */
    private static CompoundTag untouchedScreenValues(Dialog screen) {
        CompoundTag values = new CompoundTag();
        for (Input input : screen.common().inputs()) {
            Tag value = switch (input.control()) {
                case BooleanInput toggle -> ByteTag.valueOf(toggle.initial());
                case NumberRangeInput slider -> FloatTag.valueOf(slider.rangeInfo().computeScaledValue(slider.rangeInfo().initialSliderValue()));
                case TextInput text -> StringTag.valueOf(text.initial());
                default -> throw new IllegalStateException("Unexpected control " + input.control());
            };
            values.put(input.key(), value);
        }
        return values;
    }

    // ---- Workload benchmark gate: medians, disturbed phases and when MSPT may fail (numbers from real CI runs) ----

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void workloadGateSpotsDisturbedPhases(GameTestHelper helper) {
        helper.assertTrue(!WorkloadGate.disturbed(129.50D, 128.49D) && !WorkloadGate.disturbed(193.02D, 186.29D), "clean phases counted as disturbed");
        helper.assertTrue(WorkloadGate.disturbed(415.58D, 234.85D) && WorkloadGate.disturbed(201.12D, 140.92D), "disturbed phases not spotted");
        helper.assertTrue(!WorkloadGate.disturbed(5.0D, 0.0D), "a phase without ticks counted as disturbed");
        helper.assertValueEqual(WorkloadGate.disturbedPairs(List.of(
            new WorkloadGate.Phase(1, 126.47D, 125.75D), new WorkloadGate.Phase(1, 167.62D, 166.18D),
            new WorkloadGate.Phase(2, 265.45D, 182.23D), new WorkloadGate.Phase(2, 201.12D, 140.92D))), java.util.Set.of(2), "disturbed pairs");
        helper.succeed();
    }

    @GameTest(environment = CONFIG_COMMANDS, structure = ARENA, maxTicks = 20)
    public void workloadGateOnlyFailsMsptOnCleanRuns(GameTestHelper helper) {
        helper.assertValueEqual(WorkloadGate.requiredGain(10.0D), 0.5D, "minimum required gain");
        helper.assertValueEqual(WorkloadGate.mspt(128.8D, 176.0D, false), WorkloadGate.Verdict.GAIN, "clear gain");
        helper.assertValueEqual(WorkloadGate.mspt(128.8D, 176.0D, true), WorkloadGate.Verdict.GAIN, "clear gain in a disturbed run");
        helper.assertValueEqual(WorkloadGate.mspt(174.0D, 176.0D, false), WorkloadGate.Verdict.NO_GAIN, "no gain in a clean run");
        helper.assertValueEqual(WorkloadGate.mspt(174.0D, 176.0D, true), WorkloadGate.Verdict.INCONCLUSIVE, "no gain in a disturbed run");
        helper.assertValueEqual(WorkloadGate.mspt(200.0D, 176.0D, true), WorkloadGate.Verdict.WORSE, "much slower in a disturbed run");
        helper.assertValueEqual(WorkloadGate.mspt(200.0D, 176.0D, false), WorkloadGate.Verdict.WORSE, "much slower in a clean run");
        helper.assertValueEqual(WorkloadGate.mspt(190.0D, 176.0D, true), WorkloadGate.Verdict.INCONCLUSIVE, "a little slower in a disturbed run");
        helper.succeed();
    }

    /** A setting's value as written in the config file. */
    private static String savedValue(String key) {
        JsonElement value = JsonParser.parseString(CommandRunner.readConfigFile());
        for (String part : key.split("\\.")) {
            value = value.getAsJsonObject().get(part);
        }
        return value.getAsString();
    }
}
