package com.restfulbee.coppergolemmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkTest {
    private static final int GOLEMS = 800;

    @TempDir
    Path outputDir;

    @Test
    void abPhasesAlternateInAbbaOrderInWholeBlocks() {
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 10 * 60 * 20, 30 * 20, 40);
        List<Boolean> order = Stream.iterate(0, i -> i + 1).limit(8).map(benchmark::enabledForPhase).toList();
        assertEquals(List.of(true, false, false, true, true, false, false, true), order);
        assertTrue(benchmark.describe().startsWith("20 phases of 30 s"), benchmark.describe());

        // 3 minutes of 30 s phases = 6 phases, rounded to a whole ABBA block of 4 (or 8).
        Benchmark odd = new Benchmark(Benchmark.Kind.AB, 3 * 60 * 20, 30 * 20, 40);
        assertTrue(odd.describe().startsWith("8 phases") || odd.describe().startsWith("4 phases"), odd.describe());
    }

    @Test
    void currentModeNeverOverridesTheConfig() {
        Benchmark benchmark = new Benchmark(Benchmark.Kind.CURRENT, 5 * 60 * 20, 30 * 20, 40);
        for (int phase = 0; phase < 10; phase++) {
            assertNull(benchmark.enabledForPhase(phase));
        }
    }

    @Test
    void abReportShowsTheDifferenceAndExcludesWarmup() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 10 * 60 * 20, 30 * 20, 40);
        simulate(benchmark, metrics, 40.0D, 60.0D, 5, 5, Integer.MAX_VALUE);

        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);

        // The 1-second spikes injected at the start of every phase fall inside the warm-up and must not show up.
        assertTrue(report.contains("MSPT mean: on 40.0 ms | off 60.0 ms | -33.3%"), report);
        assertTrue(report.contains("MSPT p95: on 40.0 ms | off 60.0 ms"), report);
        assertTrue(report.contains("Per golem tick: on 20.0 us | off 36.0 us | -44.4%"), report);
        assertTrue(report.contains("Stacks placed / 1000 ticks: on 200.0 | off 200.0 | +0.0%"), report);
        assertTrue(report.contains("Golems: 800"), report);
        assertTrue(report.contains("OK: MSPT is 33.3% lower with the mod on"), report);
        assertTrue(report.contains("OK: sorting speed per game tick is not lower with the mod on."), report);

        JsonObject json = readOnlyResultFile();
        assertEquals("ab", json.get("kind").getAsString());
        assertEquals(20, json.get("phasesMeasured").getAsInt());
        JsonArray phases = json.getAsJsonArray("phases");
        assertEquals("mod on", phases.get(0).getAsJsonObject().get("side").getAsString());
        assertEquals("mod off", phases.get(1).getAsJsonObject().get("side").getAsString());
        assertEquals("mod off", phases.get(2).getAsJsonObject().get("side").getAsString());
        assertEquals("mod on", phases.get(3).getAsJsonObject().get("side").getAsString());
        assertEquals(500, phases.get(0).getAsJsonObject().get("measuredTicks").getAsInt());
        assertTrue(json.getAsJsonObject("environment").has("config"));
    }

    @Test
    void warnsWhenSortingIsSlowerWithTheModOn() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 4 * 60 * 20, 30 * 20, 40);
        simulate(benchmark, metrics, 40.0D, 60.0D, 10, 5, Integer.MAX_VALUE);

        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);
        assertTrue(report.contains("WARNING: golems placed 50% fewer stacks per game tick with the mod on"), report);
    }

    @Test
    void warnsWhenTheModMakesTicksSlower() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 4 * 60 * 20, 30 * 20, 40);
        simulate(benchmark, metrics, 60.0D, 40.0D, 5, 5, Integer.MAX_VALUE);

        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);
        assertTrue(report.contains("WARNING: MSPT is higher with the mod on"), report);
    }

    @Test
    void stoppingEarlyKeepsTheMeasuredPhases() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 10 * 60 * 20, 30 * 20, 40);
        // 1.5 phases: phase 1 complete, phase 2 half measured.
        simulate(benchmark, metrics, 40.0D, 60.0D, 5, 5, 900);
        assertFalse(benchmark.isFinished());
        benchmark.stop(metrics);
        assertTrue(benchmark.isFinished());

        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);
        assertEquals(2, readOnlyResultFile().get("phasesMeasured").getAsInt());
        assertTrue(report.contains("MSPT mean: on 40.0 ms | off 60.0 ms"), report);
    }

    @Test
    void stoppingDuringTheFirstWarmupReportsNoData() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 10 * 60 * 20, 30 * 20, 40);
        simulate(benchmark, metrics, 40.0D, 60.0D, 5, 5, 50);
        benchmark.stop(metrics);

        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);
        assertTrue(report.contains("NO DATA"), report);
    }

    @Test
    void reportsWhenNoGolemsAreTicking() throws IOException {
        ModMetrics metrics = new ModMetrics();
        Benchmark benchmark = new Benchmark(Benchmark.Kind.AB, 2 * 60 * 20, 30 * 20, 40);
        while (!benchmark.isFinished()) {
            benchmark.onServerTick(5_000_000L, metrics);
        }
        String report = benchmark.finishAndReport(new CopperGolemModConfig(), outputDir);
        assertTrue(report.contains("NO GOLEMS"), report);
    }

    /**
     * Feeds the benchmark server ticks the way the mod does at runtime. Golem cost, tick length and how often a
     * stack is placed depend on whether the current phase has the mod on. The first 10 ticks of each phase cost
     * one second, to check that warm-up ticks are excluded.
     */
    private static void simulate(Benchmark benchmark, ModMetrics metrics, double onMspt, double offMspt,
                                 int onPlaceEveryTicks, int offPlaceEveryTicks, int maxTicks) {
        int ticksIntoPhase = 0;
        for (int tick = 0; tick < maxTicks && !benchmark.isFinished(); tick++) {
            Boolean enabled = benchmark.currentEnabledOverride();
            boolean on = enabled == null || enabled;
            for (int golem = 0; golem < GOLEMS; golem++) {
                metrics.golemTick();
                metrics.recordGolemTickNanos(on ? 20_000L : 36_000L);
            }
            if (tick % (on ? onPlaceEveryTicks : offPlaceEveryTicks) == 0) {
                metrics.stackPlaced();
            }
            long tickNanos = ticksIntoPhase < 10 ? 1_000_000_000L : (long) ((on ? onMspt : offMspt) * 1.0E6D);
            ticksIntoPhase = benchmark.onServerTick(tickNanos, metrics) ? 0 : ticksIntoPhase + 1;
        }
    }

    private JsonObject readOnlyResultFile() throws IOException {
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> results = files.toList();
            assertEquals(1, results.size(), results.toString());
            return JsonParser.parseString(Files.readString(results.getFirst())).getAsJsonObject();
        }
    }
}
