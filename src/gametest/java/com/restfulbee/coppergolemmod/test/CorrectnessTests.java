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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

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

        Container inputChest = TestSupport.placeContainer(helper, input, Blocks.COPPER_CHEST,
            new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.OAK_PLANKS, 32));
        Container cobblestoneOut = TestSupport.placeContainer(helper, cobblestoneChest, Blocks.CHEST, new ItemStack(Items.COBBLESTONE, 1));
        Container planksOut = TestSupport.placeContainer(helper, planksChest, Blocks.CHEST, new ItemStack(Items.OAK_PLANKS, 1));
        List<CopperGolem> golems = golemSpawns.stream().map(pos -> helper.spawn(EntityType.COPPER_GOLEM, pos)).toList();

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
        CopperGolem golem = helper.spawn(EntityType.COPPER_GOLEM, at(3.5, 1, 6.5));
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
        CopperGolem golem = helper.spawn(EntityType.COPPER_GOLEM, at(6.5, 1, 6.5));

        List<Vec3> spots = new ArrayList<>(List.of(
            // Equal distances, so the stable ordering of ties is checked.
            at(8.5, 1, 6.5), at(4.5, 1, 6.5), at(6.5, 1, 8.5), at(6.5, 1, 4.5),
            at(8.5, 1, 8.5), at(4.5, 1, 4.5), at(8.5, 1, 4.5), at(4.5, 1, 8.5)
        ));
        Random random = new Random(1234);
        for (int i = 0; i < 24; i++) {
            spots.add(at(1 + random.nextInt(10) + 0.5, 1, 1 + random.nextInt(10) + 0.5));
        }
        spots.forEach(spot -> helper.spawn(EntityType.ARMOR_STAND, spot));

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
        CopperGolem golem = helper.spawn(EntityType.COPPER_GOLEM, at(6.5, 1, 6.5));
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
        CopperGolem golem = helper.spawn(EntityType.COPPER_GOLEM, at(6.5, 2, 6.5));

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
