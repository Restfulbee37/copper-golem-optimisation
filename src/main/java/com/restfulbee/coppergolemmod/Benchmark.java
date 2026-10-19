package com.restfulbee.coppergolemmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A benchmark run started by {@code /cgp bench}. Driven from the end of every server tick on the server thread.
 *
 * <p>The run is split into phases of a fixed number of game ticks. In {@link Kind#AB} mode the mod is switched on
 * and off between phases in an ABBA order (on, off, off, on, on, off, ...), so slow drift in the world (villager
 * schedules, item input, mob counts) affects both sides equally. Only {@code enabled} is changed, in memory; the
 * config file is never touched. The first ticks of every phase are a warm-up and are not measured, because some
 * optimizations (the stationary sorter) need a few ticks to engage after being switched on.
 *
 * <p>Everything is reported per game tick rather than per real minute, so results don't depend on TPS.
 */
public final class Benchmark {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int DEFAULT_WARMUP_TICKS = 100;

    public enum Kind {
        /** Alternate the mod on and off. */
        AB,
        /** Measure the current configuration without changing it. */
        CURRENT
    }

    private enum Side {
        ON("mod on"), OFF("mod off"), CURRENT("current config");

        private final String label;

        Side(String label) {
            this.label = label;
        }
    }

    private final Kind kind;
    private final int phaseTicks;
    private final int warmupTicks;
    private final int phaseCount;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final List<PhaseResult> phases = new ArrayList<>();
    private final Map<Side, LongArrayList> tickNanosBySide = new LinkedHashMap<>();

    private int phaseIndex;
    private int ticksIntoPhase;
    private ModMetrics.Snapshot measureStart;
    private long measureStartWallNanos;
    private final LongArrayList phaseTickNanos = new LongArrayList();

    public Benchmark(Kind kind, int totalTicks, int phaseTicks, int minStationaryTicks) {
        this.kind = kind;
        this.phaseTicks = phaseTicks;
        // Long enough for the stationary sorter to re-engage after the mod is switched back on.
        this.warmupTicks = Math.min(Math.max(DEFAULT_WARMUP_TICKS, minStationaryTicks + 20), phaseTicks / 2);
        int phases = Math.max(1, Math.round((float) totalTicks / phaseTicks));
        // ABBA order only cancels drift with a whole number of ABBA blocks.
        this.phaseCount = kind == Kind.AB ? Math.max(4, Math.round(phases / 4.0F) * 4) : phases;
    }

    /** Whether the mod should be enabled during the given phase, or null to leave the config alone. */
    @Nullable
    public Boolean enabledForPhase(int phase) {
        if (kind != Kind.AB) {
            return null;
        }
        return ((phase + 1) / 2) % 2 == 0;
    }

    @Nullable
    public Boolean currentEnabledOverride() {
        return enabledForPhase(phaseIndex);
    }

    /**
     * Records one finished server tick. Returns true when the phase changed (the caller re-applies
     * {@link #currentEnabledOverride()}), and {@link #isFinished()} becomes true after the last phase.
     */
    public boolean onServerTick(long tickNanos, ModMetrics metrics) {
        ticksIntoPhase++;
        if (ticksIntoPhase <= warmupTicks) {
            if (ticksIntoPhase == warmupTicks) {
                measureStart = metrics.snapshot();
                measureStartWallNanos = System.nanoTime();
            }
            return false;
        }

        phaseTickNanos.add(tickNanos);
        if (ticksIntoPhase < phaseTicks) {
            return false;
        }

        finishPhase(metrics);
        phaseIndex++;
        ticksIntoPhase = 0;
        return true;
    }

    /** Ends the current phase early (for {@code /cgp bench stop}); a phase with no measured ticks is dropped. */
    public void stop(ModMetrics metrics) {
        if (ticksIntoPhase > warmupTicks && !phaseTickNanos.isEmpty()) {
            finishPhase(metrics);
        }
        phaseIndex = phaseCount;
    }

    public boolean isFinished() {
        return phaseIndex >= phaseCount;
    }

    public String progress() {
        int totalTicks = phaseCount * phaseTicks;
        int doneTicks = Math.min(totalTicks, phaseIndex * phaseTicks + ticksIntoPhase);
        Side side = sideFor(Math.min(phaseIndex, phaseCount - 1));
        return String.format(Locale.ROOT, "CopperGolemMod benchmark (%s): phase %d/%d (%s), %d%% done, about %d s of game time left.",
            kind == Kind.AB ? "A/B" : "current config", Math.min(phaseIndex + 1, phaseCount), phaseCount, side.label,
            100 * doneTicks / totalTicks, (totalTicks - doneTicks) / 20);
    }

    public String describe() {
        return String.format(Locale.ROOT, "%d phases of %d s game time (%d s warm-up each, not measured), about %d min of game time in total",
            phaseCount, phaseTicks / 20, warmupTicks / 20, Math.round(phaseCount * phaseTicks / 1200.0F));
    }

    private Side sideFor(int phase) {
        Boolean enabled = enabledForPhase(phase);
        return enabled == null ? Side.CURRENT : enabled ? Side.ON : Side.OFF;
    }

    private void finishPhase(ModMetrics metrics) {
        ModMetrics.Snapshot delta = metrics.snapshot().minus(measureStart);
        double wallSeconds = (System.nanoTime() - measureStartWallNanos) / 1.0E9D;
        long[] nanos = phaseTickNanos.toLongArray();
        Side side = sideFor(phaseIndex);
        phases.add(PhaseResult.of(phaseIndex + 1, side.label, nanos, wallSeconds, delta));
        tickNanosBySide.computeIfAbsent(side, ignored -> new LongArrayList()).addAll(phaseTickNanos);
        phaseTickNanos.clear();
    }

    /** Default results folder: {@code <server>/copper_golem_mod/benchmarks}. */
    public static Path defaultOutputDir() {
        return FabricLoader.getInstance().getGameDir().resolve(CopperGolemMod.MOD_ID).resolve("benchmarks");
    }

    /** Builds the result, writes it to {@code outputDir} and returns the chat/log report. */
    public String finishAndReport(CopperGolemModConfig config, Path outputDir) {
        List<SideSummary> sides = new ArrayList<>();
        for (Side side : Side.values()) {
            List<PhaseResult> sidePhases = phases.stream().filter(phase -> phase.side.equals(side.label)).toList();
            if (!sidePhases.isEmpty()) {
                sides.add(SideSummary.of(side.label, sidePhases, tickNanosBySide.get(side).toLongArray()));
            }
        }

        List<String> verdict = verdict(sides);
        Result result = new Result(
            kind.name().toLowerCase(Locale.ROOT),
            startedAt.toString(),
            LocalDateTime.now().toString(),
            phaseTicks / 20,
            warmupTicks,
            phases.size(),
            Environment.capture(config),
            sides,
            verdict,
            phases
        );
        String savedTo = save(result, outputDir);
        return formatReport(result, savedTo);
    }

    private static List<String> verdict(List<SideSummary> sides) {
        List<String> notes = new ArrayList<>();
        SideSummary on = find(sides, Side.ON.label);
        SideSummary off = find(sides, Side.OFF.label);
        SideSummary any = sides.isEmpty() ? null : sides.getFirst();

        if (any == null) {
            notes.add("NO DATA: the benchmark was stopped before any ticks were measured.");
            return notes;
        }
        if (sides.stream().allMatch(side -> side.golemsPerTick < 1.0D)) {
            notes.add("NO GOLEMS: no copper golems were ticking, so there was nothing to measure.");
            return notes;
        }
        if (on == null || off == null) {
            if (on != null || off != null) {
                notes.add("INCOMPLETE: only one side was measured; run for at least 4 phases to compare.");
            }
            return notes;
        }

        double msptNoise = Math.max(0.5D, 2.0D * Math.hypot(on.msptMeanSpread, off.msptMeanSpread));
        if (on.msptMean < off.msptMean - msptNoise) {
            notes.add(String.format(Locale.ROOT, "OK: MSPT is %.1f%% lower with the mod on (beyond phase-to-phase noise of +/-%.1f ms).",
                percentChange(off.msptMean, on.msptMean) * -1, msptNoise));
        } else if (on.msptMean > off.msptMean + msptNoise) {
            notes.add("WARNING: MSPT is higher with the mod on. The optimizations may be broken on this version.");
        } else {
            notes.add("INCONCLUSIVE: the MSPT difference is within phase-to-phase noise. Run longer or with more golems.");
        }

        long sortedStacks = on.stacksPlaced + off.stacksPlaced;
        if (sortedStacks < 50) {
            notes.add("NOT ENOUGH SORTING: fewer than 50 stacks were placed. Feed the farm items during the benchmark to check sorting speed.");
        } else {
            double placedNoise = 2.0D * Math.hypot(on.placedPer1000TicksSpread, off.placedPer1000TicksSpread);
            if (on.placedPer1000Ticks < off.placedPer1000Ticks * 0.9D && on.placedPer1000Ticks < off.placedPer1000Ticks - placedNoise) {
                notes.add(String.format(Locale.ROOT, "WARNING: golems placed %.0f%% fewer stacks per game tick with the mod on. Sorting may be slowed.",
                    -percentChange(off.placedPer1000Ticks, on.placedPer1000Ticks)));
            } else {
                notes.add("OK: sorting speed per game tick is not lower with the mod on.");
            }
        }
        return notes;
    }

    private static String formatReport(Result result, @Nullable String savedTo) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "CopperGolemMod benchmark (%s) finished: %d phases of %d s.%n",
            result.kind.equals("ab") ? "A/B" : "current config", result.phasesMeasured, result.phaseSeconds));
        SideSummary on = find(result.summary, Side.ON.label);
        SideSummary off = find(result.summary, Side.OFF.label);
        if (on != null && off != null) {
            out.append(row("MSPT mean", "%.1f ms", on.msptMean, off.msptMean, true));
            out.append(row("MSPT p95", "%.1f ms", on.msptP95, off.msptP95, true));
            out.append(row("TPS", "%.1f", on.tps, off.tps, false));
            out.append(row("Golem time per tick", "%.2f ms", on.golemMsPerTick, off.golemMsPerTick, true));
            out.append(row("Per golem tick", "%.1f us", on.microsPerGolemTick, off.microsPerGolemTick, true));
            out.append(row("Stacks placed / 1000 ticks", "%.1f", on.placedPer1000Ticks, off.placedPer1000Ticks, true));
            out.append(String.format(Locale.ROOT, "  Golems: %.0f | Phase spread (MSPT): on +/-%.1f ms, off +/-%.1f ms%n",
                on.golemsPerTick, on.msptMeanSpread, off.msptMeanSpread));
        } else {
            for (SideSummary side : result.summary) {
                out.append(String.format(Locale.ROOT,
                    "  %s: MSPT mean %.1f ms (p95 %.1f, +/-%.1f across phases), TPS %.1f, golem time %.2f ms/tick (%.1f us per golem), %.1f stacks placed / 1000 ticks, %.0f golems%n",
                    side.side, side.msptMean, side.msptP95, side.msptMeanSpread, side.tps, side.golemMsPerTick,
                    side.microsPerGolemTick, side.placedPer1000Ticks, side.golemsPerTick));
            }
        }
        for (String note : result.verdict) {
            out.append("  ").append(note).append('\n');
        }
        out.append(savedTo != null ? "  Saved to " + savedTo : "  Could not save the results file; see the server log.");
        return out.toString();
    }

    private static String row(String name, String format, double on, double off, boolean showChange) {
        String line = "  " + name + ": on " + String.format(Locale.ROOT, format, on) + " | off " + String.format(Locale.ROOT, format, off);
        if (showChange && off != 0.0D) {
            line += String.format(Locale.ROOT, " | %+.1f%%", percentChange(off, on));
        }
        return line + '\n';
    }

    private static double percentChange(double from, double to) {
        return from == 0.0D ? 0.0D : (to - from) / from * 100.0D;
    }

    @Nullable
    private static SideSummary find(List<SideSummary> sides, String label) {
        return sides.stream().filter(side -> side.side.equals(label)).findFirst().orElse(null);
    }

    @Nullable
    private String save(Result result, Path dir) {
        Path file = dir.resolve(startedAt.format(FILE_TIME) + "_" + result.kind + ".json");
        try {
            Files.createDirectories(dir);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(result, writer);
            }
            Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath();
            Path absolute = file.toAbsolutePath();
            return absolute.startsWith(gameDir) ? gameDir.relativize(absolute).toString() : absolute.toString();
        } catch (IOException exception) {
            CopperGolemMod.LOGGER.error("Failed to save CopperGolemMod benchmark results to {}", file, exception);
            return null;
        }
    }

    private static double mean(long[] values) {
        if (values.length == 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (long value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /** Nearest-rank percentile of nanosecond values, in milliseconds. */
    private static double percentileMs(long[] values, double percentile) {
        if (values.length == 0) {
            return 0.0D;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1.0E6D;
    }

    private static double sampleStdDev(double[] values) {
        if (values.length < 2) {
            return 0.0D;
        }
        double mean = Arrays.stream(values).average().orElse(0.0D);
        double sumSquares = 0.0D;
        for (double value : values) {
            sumSquares += (value - mean) * (value - mean);
        }
        return Math.sqrt(sumSquares / (values.length - 1));
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    // ---- Serialized result types (field names are the JSON keys) ----

    private record Result(
        String kind,
        String startedAt,
        String finishedAt,
        int phaseSeconds,
        int warmupTicksPerPhase,
        int phasesMeasured,
        Environment environment,
        List<SideSummary> summary,
        List<String> verdict,
        List<PhaseResult> phases
    ) {
    }

    private record Environment(
        String modVersion,
        String minecraftVersion,
        String javaVersion,
        List<String> otherMods,
        CopperGolemModConfig config
    ) {
        static Environment capture(CopperGolemModConfig config) {
            FabricLoader loader = FabricLoader.getInstance();
            List<String> otherMods = loader.getAllMods().stream()
                .map(ModContainer::getMetadata)
                .filter(meta -> !meta.getType().equals("builtin"))
                .filter(meta -> !meta.getId().equals(CopperGolemMod.MOD_ID))
                .filter(meta -> !meta.getId().startsWith("fabric-") || meta.getId().equals("fabric-api"))
                .filter(meta -> loader.getModContainer(meta.getId()).flatMap(ModContainer::getContainingMod).isEmpty())
                .map(meta -> meta.getId() + " " + meta.getVersion().getFriendlyString())
                .sorted()
                .toList();
            return new Environment(
                version(CopperGolemMod.MOD_ID),
                version("minecraft"),
                System.getProperty("java.version"),
                otherMods,
                config
            );
        }

        private static String version(String modId) {
            return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        }
    }

    private record PhaseResult(
        int phase,
        String side,
        int measuredTicks,
        double wallSeconds,
        double tps,
        double msptMean,
        double msptMedian,
        double msptP95,
        double golemsPerTick,
        double golemMsPerTick,
        double microsPerGolemTick,
        long stacksTaken,
        long stacksPlaced,
        double placedPer1000Ticks
    ) {
        static PhaseResult of(int phase, String side, long[] tickNanos, double wallSeconds, ModMetrics.Snapshot delta) {
            int ticks = tickNanos.length;
            return new PhaseResult(
                phase,
                side,
                ticks,
                round(wallSeconds, 2),
                round(wallSeconds > 0.0D ? ticks / wallSeconds : 0.0D, 2),
                round(mean(tickNanos) / 1.0E6D, 3),
                round(percentileMs(tickNanos, 0.5D), 3),
                round(percentileMs(tickNanos, 0.95D), 3),
                round((double) delta.golemTicks() / ticks, 1),
                round(delta.timedGolemNanos() / 1.0E6D / ticks, 3),
                round(delta.timedGolemTicks() == 0 ? 0.0D : delta.timedGolemNanos() / 1000.0D / delta.timedGolemTicks(), 2),
                delta.stacksTaken(),
                delta.stacksPlaced(),
                round(delta.stacksPlaced() * 1000.0D / ticks, 2)
            );
        }
    }

    private record SideSummary(
        String side,
        int phases,
        int measuredTicks,
        double tps,
        double msptMean,
        double msptMedian,
        double msptP95,
        double msptMeanSpread,
        double golemsPerTick,
        double golemMsPerTick,
        double microsPerGolemTick,
        long stacksTaken,
        long stacksPlaced,
        double placedPer1000Ticks,
        double placedPer1000TicksSpread
    ) {
        static SideSummary of(String side, List<PhaseResult> phases, long[] tickNanos) {
            int ticks = tickNanos.length;
            double wall = phases.stream().mapToDouble(PhaseResult::wallSeconds).sum();
            double golemTicks = phases.stream().mapToDouble(phase -> phase.golemsPerTick * phase.measuredTicks).sum();
            double golemMs = phases.stream().mapToDouble(phase -> phase.golemMsPerTick * phase.measuredTicks).sum();
            long taken = phases.stream().mapToLong(PhaseResult::stacksTaken).sum();
            long placed = phases.stream().mapToLong(PhaseResult::stacksPlaced).sum();
            return new SideSummary(
                side,
                phases.size(),
                ticks,
                round(wall > 0.0D ? ticks / wall : 0.0D, 2),
                round(mean(tickNanos) / 1.0E6D, 3),
                round(percentileMs(tickNanos, 0.5D), 3),
                round(percentileMs(tickNanos, 0.95D), 3),
                round(sampleStdDev(phases.stream().mapToDouble(PhaseResult::msptMean).toArray()), 3),
                round(golemTicks / ticks, 1),
                round(golemMs / ticks, 3),
                round(golemTicks == 0.0D ? 0.0D : golemMs * 1000.0D / golemTicks, 2),
                taken,
                placed,
                round(placed * 1000.0D / ticks, 2),
                round(sampleStdDev(phases.stream().mapToDouble(PhaseResult::placedPer1000Ticks).toArray()), 2)
            );
        }
    }
}
