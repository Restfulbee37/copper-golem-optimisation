package com.restfulbee.coppergolemmod.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.restfulbee.coppergolemmod.CopperGolemMod;
import com.restfulbee.coppergolemmod.CopperGolemModConfig;
import com.restfulbee.coppergolemmod.ModMetrics;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Standard workload benchmark (#6): a copy of a real copper golem sorter with the villager floor above it
 * (see {@link FarmFixture}) sorts the same fixed set of items with the mod on and off, in ABBA order.
 *
 * <p>Each phase starts from an identical state: every chest is reset (inputs empty, outputs holding one "marker"
 * item each, as in the real farm), all golems are respawned at their original positions, and after a warm-up each
 * input chest of a cell in use is filled with the items its golems can sort (the marker items of the output chests
 * next to that cell), 8 stacks each by default. The phase ends when everything is sorted, nothing has moved for a
 * minute of game time (some items may have no reachable chest, as in real farms), or it times out.
 *
 * <p>Sorting speed is measured as T90, the game ticks until 90% of the items are sorted. The time to the very last
 * item depends on a few stragglers (one golem retrying a chest after a cooldown) and varies by ~1000 ticks between
 * identical runs, so it is reported but not used to decide pass/fail.
 *
 * <p>The test fails if any item is lost or duplicated, if T90 is more than 5% longer or the mod leaves noticeably more
 * items unsorted (more than one golem trip), or if the mod gives no measurable MSPT improvement. Results are written
 * to {@code <run dir>/copper_golem_mod/workload/} as JSON.
 */
public class WorkloadBenchmark {
    private static final Logger LOGGER = LoggerFactory.getLogger("CopperGolemMod workload");

    @GameTest(environment = "copper_golem_mod_test:workload", structure = "copper_golem_mod_test:farm", maxTicks = 72_000)
    public void farmWorkload(TestContext helper) {
        int stacksPerInput = Integer.getInteger("copper_golem_mod_test.workload.stacksPerInput", 8);
        Run run = new Run(helper, FarmFixture.load(), stacksPerInput);
        helper.forEachRemainingTick(run::tick);
    }

    private enum Stage { SETTLE_VILLAGERS, WARMUP, SORTING }

    private enum Outcome { SORTED, STUCK, TIMED_OUT }

    /** One workload run, driven once per game tick. */
    private static final class Run {
        static final int VILLAGER_SETTLE_TICKS = 600;
        static final int GOLEM_WARMUP_TICKS = 200;
        static final int PHASE_TIMEOUT_TICKS = 15_000;
        static final int STUCK_AFTER_TICKS = 1_200;
        static final int CHECK_EVERY_TICKS = 20;
        /** ABBA: drift during the run (villager activity, JIT warm-up) affects both sides equally. */
        static final boolean[] PHASE_MOD_ON = {true, false, false, true};
        static final int BLOCK_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS | Block.SKIP_BLOCK_ADDED_CALLBACK;

        final TestContext helper;
        final ServerWorld level;
        final FarmFixture farm;
        final int stacksPerInput;
        final LocalDateTime startedAt = LocalDateTime.now();

        final List<Inventory> allContainers = new ArrayList<>();
        final Map<BlockPos, ItemStack> markers = new LinkedHashMap<>();
        final Map<BlockPos, List<ItemStack>> feed = new LinkedHashMap<>();
        final List<String> skippedStates = new ArrayList<>();
        final Set<String> unknownItems = new TreeSet<>();
        long fedItems;
        long markerItems;

        Stage stage = Stage.SETTLE_VILLAGERS;
        int stageTicks;
        int phase;
        List<CopperGolemEntity> golems = List.of();
        final LongArrayList tickNanos = new LongArrayList();
        ModMetrics.Snapshot metricsAtStart;
        long remainingAtLastProgress;
        int lastProgressTick;
        long initialRemaining;
        final List<long[]> progress = new ArrayList<>();
        final List<PhaseResult> results = new ArrayList<>();

        Run(TestContext helper, FarmFixture farm, int stacksPerInput) {
            this.helper = helper;
            this.level = helper.getWorld();
            this.farm = farm;
            this.stacksPerInput = stacksPerInput;
        }

        void tick() {
            if (helper.getTick() == 0) {
                buildFarm();
                return;
            }
            stageTicks++;
            switch (stage) {
                case SETTLE_VILLAGERS -> {
                    if (stageTicks >= VILLAGER_SETTLE_TICKS) {
                        startPhase();
                    }
                }
                case WARMUP -> {
                    if (stageTicks >= GOLEM_WARMUP_TICKS) {
                        startSorting();
                    }
                }
                case SORTING -> {
                    // During tick N this is the length of tick N-1, the first of which already had full inputs.
                    tickNanos.add(CopperGolemModTestMod.lastTickNanos());
                    if (stageTicks % CHECK_EVERY_TICKS == 0) {
                        checkProgress();
                    }
                }
            }
        }

        // ---- Setup ----

        void buildFarm() {
            Map<String, BlockState> states = new HashMap<>();
            for (String state : farm.palette) {
                try {
                    states.put(state, BlockArgumentParser.block(level.createCommandRegistryWrapper(RegistryKeys.BLOCK), state, false).blockState());
                } catch (CommandSyntaxException exception) {
                    skippedStates.add(state);
                }
            }
            for (int[] block : farm.blocks) {
                BlockState state = states.get(farm.palette.get(block[3]));
                if (state != null) {
                    level.setBlockState(helper.getAbsolutePos(new BlockPos(block[0], block[1], block[2])), state, BLOCK_FLAGS);
                }
            }
            if (!skippedStates.isEmpty()) {
                LOGGER.warn("Workload farm: {} block states don't exist in this Minecraft version and were skipped: {}", skippedStates.size(), skippedStates);
            }

            for (int[] block : farm.blocks) {
                if (level.getBlockEntity(helper.getAbsolutePos(new BlockPos(block[0], block[1], block[2]))) instanceof Inventory container) {
                    allContainers.add(container);
                }
            }
            for (FarmFixture.ContainerSpec spec : farm.containers) {
                if (!spec.input() && spec.marker() != null) {
                    item(spec.marker()).ifPresent(item -> markers.put(spec.pos(), new ItemStack(item, 1)));
                }
            }
            planFeed();

            for (FarmFixture.VillagerSpec spec : farm.villagers) {
                VillagerEntity villager = helper.spawnEntity(EntityType.VILLAGER, spec.pos());
                var registries = level.getRegistryManager();
                villager.setVillagerData(villager.getVillagerData()
                    .withProfession(registries, RegistryKey.of(RegistryKeys.VILLAGER_PROFESSION, Identifier.of(spec.profession())))
                    .withType(registries, RegistryKey.of(RegistryKeys.VILLAGER_TYPE, Identifier.of(spec.type())))
                    .withLevel(spec.level()));
                if (spec.baby()) {
                    villager.setBreedingAge(-24_000);
                }
            }
            LOGGER.info("Workload farm built: {} blocks, {} containers, {} villagers; feeding {} items per phase into {} input chests",
                farm.blocks.size() - skippedStates.size(), allContainers.size(), farm.villagers.size(), fedItems, feed.size());
            stage = Stage.SETTLE_VILLAGERS;
            stageTicks = 0;
        }

        /**
         * Golems in a trapped cell can only use chests right next to them, so each input chest is filled the way the
         * real farm's feed fills it: with the item types the output chests around its own cell already hold. Cells
         * whose neighbouring chests are all empty aren't in use in the real farm (otherwise their chests would hold
         * items), so they get nothing; their golems stand idle, which also costs tick time on the real server.
         */
        void planFeed() {
            Set<BlockPos> golemCells = new TreeSet<>();
            farm.golems.forEach(golem -> golemCells.add(BlockPos.ofFloored(golem)));
            for (FarmFixture.ContainerSpec input : farm.containers) {
                if (!input.input()) {
                    continue;
                }
                Set<Item> markedItems = new java.util.LinkedHashSet<>();
                for (BlockPos cell : golemCells) {
                    if (!near(input.pos(), cell)) {
                        continue;
                    }
                    for (Map.Entry<BlockPos, ItemStack> marker : markers.entrySet()) {
                        if (near(marker.getKey(), cell)) {
                            markedItems.add(marker.getValue().getItem());
                        }
                    }
                }
                if (markedItems.isEmpty()) {
                    continue;
                }
                List<Item> items = markedItems.stream()
                    .sorted((a, b) -> Registries.ITEM.getId(a).compareTo(Registries.ITEM.getId(b)))
                    .toList();
                List<ItemStack> stacks = new ArrayList<>();
                for (int i = 0; i < stacksPerInput; i++) {
                    Item item = items.get(i % items.size());
                    ItemStack stack = new ItemStack(item, Math.min(64, item.getMaxCount()));
                    stacks.add(stack);
                    fedItems += stack.getCount();
                }
                feed.put(input.pos(), stacks);
            }
            markerItems = markers.size();
        }

        /**
         * Whether a golem standing in {@code cell} can use a container at {@code pos}: vanilla golems interact with
         * containers within about one block horizontally, at their own level or one above.
         */
        static boolean near(BlockPos pos, BlockPos cell) {
            int dy = pos.getY() - cell.getY();
            return Math.abs(pos.getX() - cell.getX()) <= 1 && Math.abs(pos.getZ() - cell.getZ()) <= 1 && dy >= 0 && dy <= 1;
        }

        void startPhase() {
            boolean modOn = PHASE_MOD_ON[phase];
            CopperGolemMod.setConfigOverride(phaseConfig(modOn));
            CopperGolemMod.metrics().setBenchmarkTiming(true);

            Box bounds = helper.getTestBox();
            level.getEntitiesByClass(CopperGolemEntity.class, bounds, entity -> true).forEach(golem -> golem.discard());
            level.getEntitiesByClass(ItemEntity.class, bounds.expand(4), entity -> true).forEach(item -> item.discard());
            for (Inventory container : allContainers) {
                container.clear();
            }
            markers.forEach((pos, marker) -> container(pos).setStack(0, marker.copy()));
            golems = farm.golems.stream().map(pos -> helper.spawnEntity(EntityType.COPPER_GOLEM, pos)).toList();

            LOGGER.info("Workload phase {}/{} (mod {}): {} golems spawned, warming up", phase + 1, PHASE_MOD_ON.length, modOn ? "on" : "off", golems.size());
            stage = Stage.WARMUP;
            stageTicks = 0;
        }

        void startSorting() {
            feed.forEach((pos, stacks) -> {
                Inventory container = container(pos);
                for (int slot = 0; slot < stacks.size() && slot < container.size(); slot++) {
                    container.setStack(slot, stacks.get(slot).copy());
                }
            });
            tickNanos.clear();
            metricsAtStart = CopperGolemMod.metrics().snapshot();
            remainingAtLastProgress = itemsToSort();
            initialRemaining = remainingAtLastProgress;
            progress.clear();
            progress.add(new long[]{0, initialRemaining});
            lastProgressTick = 0;
            stage = Stage.SORTING;
            stageTicks = 0;
        }

        // ---- Measuring ----

        void checkProgress() {
            long remaining = itemsToSort();
            progress.add(new long[]{stageTicks, remaining});
            if (remaining < remainingAtLastProgress) {
                remainingAtLastProgress = remaining;
                lastProgressTick = stageTicks;
            }
            if (remaining == 0 && level.getEntitiesByClass(ItemEntity.class, helper.getTestBox().expand(4), entity -> true).isEmpty()) {
                finishPhase(Outcome.SORTED, remaining);
            } else if (stageTicks - lastProgressTick >= STUCK_AFTER_TICKS) {
                finishPhase(Outcome.STUCK, remaining);
            } else if (stageTicks >= PHASE_TIMEOUT_TICKS) {
                finishPhase(Outcome.TIMED_OUT, remaining);
            }
        }

        /** Items still in the fed input chests or carried by golems. */
        long itemsToSort() {
            long total = 0;
            for (BlockPos pos : feed.keySet()) {
                total += TestSupport.countAll(container(pos));
            }
            for (CopperGolemEntity golem : golems) {
                total += golem.getMainHandStack().getCount();
            }
            return total;
        }

        /** Every item in the farm: containers, golem hands, villager inventories and dropped items. */
        long itemsInFarm() {
            long total = 0;
            for (Inventory container : allContainers) {
                total += TestSupport.countAll(container);
            }
            for (CopperGolemEntity golem : golems) {
                total += golem.getMainHandStack().getCount();
            }
            for (VillagerEntity villager : level.getEntitiesByClass(VillagerEntity.class, helper.getTestBox().expand(4), entity -> true)) {
                total += TestSupport.countAll(villager.getInventory());
            }
            for (ItemEntity item : level.getEntitiesByClass(ItemEntity.class, helper.getTestBox().expand(4), entity -> true)) {
                total += item.getStack().getCount();
            }
            return total;
        }

        void finishPhase(Outcome outcome, long remaining) {
            ModMetrics.Snapshot delta = CopperGolemMod.metrics().snapshot().minus(metricsAtStart);
            long[] nanos = tickNanos.toLongArray();
            long expected = fedItems + markerItems;
            long actual = itemsInFarm();
            int ticksToSort = outcome == Outcome.SORTED ? stageTicks : lastProgressTick;
            PhaseResult result = PhaseResult.of(phase + 1, PHASE_MOD_ON[phase], outcome, stageTicks, ticksToSort,
                ticksUntilSorted(0.5D), ticksUntilSorted(0.9D), ticksUntilSorted(0.99D), sortedOverTime(), leftovers(),
                remaining, expected, actual, nanos, delta);
            results.add(result);
            LOGGER.info("Workload phase {}/{} ({}): {}, T50 {} / T90 {} / T99 {} / last item {} ticks, MSPT mean {} ms, golem {} ms/tick, {} items left, items {}",
                result.phase, PHASE_MOD_ON.length, result.side, outcome, result.ticksTo50Percent, result.ticksTo90Percent, result.ticksTo99Percent,
                ticksToSort, result.msptMean, result.golemMsPerTick, remaining,
                result.itemsConserved ? "conserved" : "NOT CONSERVED (" + actual + " of " + expected + ")");

            phase++;
            if (phase < PHASE_MOD_ON.length) {
                startPhase();
            } else {
                finishRun();
            }
        }

        void finishRun() {
            CopperGolemMod.setConfigOverride(null);
            CopperGolemMod.metrics().setBenchmarkTiming(false);
            golems.forEach(golem -> golem.discard());

            Summary on = Summary.of("mod on", results.stream().filter(r -> r.modOn).toList());
            Summary off = Summary.of("mod off", results.stream().filter(r -> !r.modOn).toList());
            List<String> failures = new ArrayList<>();
            List<String> notes = new ArrayList<>();

            if (results.stream().anyMatch(r -> !r.itemsConserved)) {
                failures.add("Items were lost or duplicated in at least one phase (see itemsExpected / itemsFound).");
            }
            // One golem trip (16 items) per side of slack: which golem ends up holding the last odd stack is random.
            if (on.itemsLeftUnsorted > off.itemsLeftUnsorted + 16L * on.phases) {
                failures.add(String.format(Locale.ROOT, "More items were left unsorted with the mod on (%d vs %d).", on.itemsLeftUnsorted, off.itemsLeftUnsorted));
            } else {
                notes.add(String.format(Locale.ROOT, "Items left unsorted: %d with the mod on, %d off.", on.itemsLeftUnsorted, off.itemsLeftUnsorted));
            }
            double change = percentChange(off.meanTicksTo90Percent, on.meanTicksTo90Percent);
            if (change > 5.0D) {
                failures.add(String.format(Locale.ROOT, "Sorting took %.1f%% longer with the mod on (T90 %.0f vs %.0f ticks).", change, on.meanTicksTo90Percent, off.meanTicksTo90Percent));
            } else {
                notes.add(String.format(Locale.ROOT, "Sorting time with the mod on: %+.1f%% (T90 %.0f vs %.0f ticks; T50 %.0f vs %.0f; last item %.0f vs %.0f).",
                    change, on.meanTicksTo90Percent, off.meanTicksTo90Percent, on.meanTicksTo50Percent, off.meanTicksTo50Percent, on.meanTicksToSort, off.meanTicksToSort));
            }
            double requiredGain = Math.max(0.5D, off.msptMean * 0.02D);
            if (on.msptMean > off.msptMean - requiredGain) {
                failures.add(String.format(Locale.ROOT, "No measurable MSPT gain with the mod on (%.2f vs %.2f ms; needs at least %.2f ms lower).", on.msptMean, off.msptMean, requiredGain));
            } else {
                notes.add(String.format(Locale.ROOT, "MSPT with the mod on: %.2f vs %.2f ms (%+.1f%%).", on.msptMean, off.msptMean, percentChange(off.msptMean, on.msptMean)));
            }
            notes.add(String.format(Locale.ROOT, "Golem time per tick with the mod on: %.2f vs %.2f ms (%+.1f%%).",
                on.golemMsPerTick, off.golemMsPerTick, percentChange(off.golemMsPerTick, on.golemMsPerTick)));

            String savedTo = save(on, off, failures, notes);
            StringBuilder report = new StringBuilder("Workload benchmark ").append(failures.isEmpty() ? "PASSED" : "FAILED").append('\n');
            notes.forEach(note -> report.append("  ").append(note).append('\n'));
            failures.forEach(failure -> report.append("  FAIL: ").append(failure).append('\n'));
            report.append("  Results: ").append(savedTo);
            LOGGER.info(report.toString());

            if (failures.isEmpty()) {
                helper.complete();
            } else {
                helper.throwGameTestException(String.join(" ", failures));
            }
        }

        /** Ticks until the given fraction of the fed items was sorted; the phase length if it never got there. */
        int ticksUntilSorted(double fraction) {
            for (long[] point : progress) {
                if (initialRemaining - point[1] >= fraction * initialRemaining) {
                    return (int) point[0];
                }
            }
            return stageTicks;
        }

        /** Items still to sort every 100 ticks, for plotting a sorting curve. */
        List<long[]> sortedOverTime() {
            List<long[]> points = new ArrayList<>();
            for (long[] point : progress) {
                if (point[0] % 100 == 0) {
                    points.add(point);
                }
            }
            return points;
        }

        /**
         * Where unsorted items ended up, to make a failing run easy to diagnose. Positions are relative to the farm's
         * corner, like farm.json (computed directly: vanilla's relativePos flips unrotated tests by 180 degrees).
         */
        List<String> leftovers() {
            List<String> found = new ArrayList<>();
            for (BlockPos pos : feed.keySet()) {
                Inventory container = container(pos);
                for (int slot = 0; slot < container.size(); slot++) {
                    ItemStack stack = container.getStack(slot);
                    if (!stack.isEmpty()) {
                        found.add(String.format(Locale.ROOT, "%dx %s in input chest at %s", stack.getCount(), Registries.ITEM.getId(stack.getItem()), pos.toShortString()));
                    }
                }
            }
            for (CopperGolemEntity golem : golems) {
                ItemStack held = golem.getMainHandStack();
                if (!held.isEmpty()) {
                    found.add(String.format(Locale.ROOT, "%dx %s held by a golem at %s", held.getCount(), Registries.ITEM.getId(held.getItem()),
                        golem.getBlockPos().subtract(helper.getAbsolutePos(BlockPos.ORIGIN)).toShortString()));
                }
            }
            return found;
        }

        // ---- Helpers ----

        CopperGolemModConfig phaseConfig(boolean modOn) {
            // "On" mirrors the stress-test server: default optimizations plus the stationary sorter.
            return ModConfigEnvironment.config(modOn, true, true, true);
        }

        Inventory container(BlockPos relativePos) {
            return TestSupport.container(helper, relativePos);
        }

        Optional<Item> item(String id) {
            Optional<Item> item = Registries.ITEM.getOptionalValue(Identifier.of(id));
            if (item.isEmpty()) {
                unknownItems.add(id);
            }
            return item;
        }

        String save(Summary on, Summary off, List<String> failures, List<String> notes) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("result", failures.isEmpty() ? "passed" : "failed");
            report.put("startedAt", startedAt.toString());
            report.put("finishedAt", LocalDateTime.now().toString());
            report.put("environment", Map.of(
                "modVersion", version(CopperGolemMod.MOD_ID),
                "minecraftVersion", version("minecraft"),
                "javaVersion", System.getProperty("java.version"),
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024),
                "environmentType", FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER ? "server" : "client"
            ));
            report.put("farm", Map.of(
                "source", farm.meta,
                "golems", farm.golems.size(),
                "villagers", farm.villagers.size(),
                "inputChestsFed", feed.size(),
                "stacksPerInput", stacksPerInput,
                "itemsFedPerPhase", fedItems,
                "skippedBlockStates", skippedStates,
                "unknownMarkerItems", unknownItems
            ));
            report.put("modOnConfig", "defaults + stationary sorter (like the stress-test server)");
            report.put("summary", List.of(on, off));
            report.put("notes", notes);
            report.put("failures", failures);
            report.put("phases", results);

            Path dir = FabricLoader.getInstance().getGameDir().resolve(CopperGolemMod.MOD_ID).resolve("workload");
            Path file = dir.resolve("workload-" + startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".json");
            try {
                Files.createDirectories(dir);
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    gson.toJson(report, writer);
                }
                return file.toAbsolutePath().toString();
            } catch (IOException exception) {
                LOGGER.error("Could not save workload results to {}", file, exception);
                return "(not saved: " + exception.getMessage() + ")";
            }
        }

        static String version(String modId) {
            return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        }
    }

    private static double percentChange(double from, double to) {
        return from == 0.0D ? 0.0D : (to - from) / from * 100.0D;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private record PhaseResult(
        int phase, String side, boolean modOn, String outcome, int phaseTicks,
        int ticksTo50Percent, int ticksTo90Percent, int ticksTo99Percent, int ticksToSort, long itemsLeftUnsorted,
        long itemsExpected, long itemsFound, boolean itemsConserved,
        double msptMean, double msptMedian, double msptP95, double golemMsPerTick, double microsPerGolemTick, long stacksPlaced,
        List<String> leftovers, List<long[]> itemsLeftEvery100Ticks
    ) {
        static PhaseResult of(int phase, boolean modOn, Outcome outcome, int phaseTicks, int ticksToSort,
                              int t50, int t90, int t99, List<long[]> curve, List<String> leftovers,
                              long remaining, long expected, long found, long[] tickNanos, ModMetrics.Snapshot delta) {
            long[] sorted = tickNanos.clone();
            Arrays.sort(sorted);
            int n = Math.max(1, sorted.length);
            double mean = Arrays.stream(sorted).average().orElse(0.0D) / 1.0E6D;
            return new PhaseResult(
                phase, modOn ? "mod on" : "mod off", modOn, outcome.name().toLowerCase(Locale.ROOT), phaseTicks,
                t50, t90, t99, ticksToSort, remaining,
                expected, found, expected == found,
                round(mean),
                round(sorted.length == 0 ? 0.0D : sorted[sorted.length / 2] / 1.0E6D),
                round(sorted.length == 0 ? 0.0D : sorted[Math.min(sorted.length - 1, (int) Math.ceil(0.95D * sorted.length) - 1)] / 1.0E6D),
                round(delta.timedGolemNanos() / 1.0E6D / n),
                round(delta.timedGolemTicks() == 0 ? 0.0D : delta.timedGolemNanos() / 1000.0D / delta.timedGolemTicks()),
                delta.stacksPlaced(),
                leftovers,
                curve
            );
        }
    }

    private record Summary(String side, int phases, boolean allSorted, double meanTicksTo50Percent, double meanTicksTo90Percent,
                           double meanTicksToSort, long itemsLeftUnsorted,
                           double msptMean, double msptP95, double golemMsPerTick, double microsPerGolemTick) {
        static Summary of(String side, List<PhaseResult> phases) {
            return new Summary(
                side,
                phases.size(),
                phases.stream().allMatch(p -> p.outcome.equals("sorted")),
                round(phases.stream().mapToInt(PhaseResult::ticksTo50Percent).average().orElse(0)),
                round(phases.stream().mapToInt(PhaseResult::ticksTo90Percent).average().orElse(0)),
                round(phases.stream().mapToInt(PhaseResult::ticksToSort).average().orElse(0)),
                phases.stream().mapToLong(PhaseResult::itemsLeftUnsorted).sum(),
                round(phases.stream().mapToDouble(PhaseResult::msptMean).average().orElse(0)),
                round(phases.stream().mapToDouble(PhaseResult::msptP95).average().orElse(0)),
                round(phases.stream().mapToDouble(PhaseResult::golemMsPerTick).average().orElse(0)),
                round(phases.stream().mapToDouble(PhaseResult::microsPerGolemTick).average().orElse(0))
            );
        }
    }
}
