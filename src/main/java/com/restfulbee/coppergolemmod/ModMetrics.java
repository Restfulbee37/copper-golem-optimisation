package com.restfulbee.coppergolemmod;

import java.util.Locale;

/**
 * Counters for {@code /cgp status}. Every hook that updates these runs on the server thread, so plain
 * fields are enough; the command that reads them runs on the server thread too.
 */
public final class ModMetrics {
    private long windowStartNanos = System.nanoTime();
    private long serverTicks;
    private long golemTicks;
    private long stacksTaken;
    private long stacksPlaced;
    private long lookScansSkipped;
    private long lookScansRun;
    private long lookDisabled;
    private long sensorSortsOptimized;
    private long idleSensorTicksSkipped;
    private long stationaryTravelSkipped;
    private long stationaryCrammingSkipped;

    private boolean profiling;
    private long profiledGolemTicks;
    private long profiledGolemNanos;
    private long profiledServerTicks;
    private long lastLogNanos = System.nanoTime();

    // Lifetime totals for benchmarks. Never reset, so /cgp reset can't disturb a running benchmark.
    private boolean benchmarkTiming;
    private long lifetimeGolemTicks;
    private long lifetimeTimedGolemTicks;
    private long lifetimeTimedGolemNanos;
    private long lifetimeStacksTaken;
    private long lifetimeStacksPlaced;

    public void reset() {
        windowStartNanos = System.nanoTime();
        serverTicks = 0;
        golemTicks = 0;
        stacksTaken = 0;
        stacksPlaced = 0;
        lookScansSkipped = 0;
        lookScansRun = 0;
        lookDisabled = 0;
        sensorSortsOptimized = 0;
        idleSensorTicksSkipped = 0;
        stationaryTravelSkipped = 0;
        stationaryCrammingSkipped = 0;
        profiledGolemTicks = 0;
        profiledGolemNanos = 0;
        profiledServerTicks = 0;
    }

    public void serverTick() {
        serverTicks++;
        if (profiling) {
            profiledServerTicks++;
        }
    }

    public void golemTick() {
        golemTicks++;
        lifetimeGolemTicks++;
    }

    public void stackTaken() {
        stacksTaken++;
        lifetimeStacksTaken++;
    }

    public void stackPlaced() {
        stacksPlaced++;
        lifetimeStacksPlaced++;
    }

    public void lookScanSkipped() {
        lookScansSkipped++;
    }

    public void lookScanRun() {
        lookScansRun++;
    }

    public void lookDisabled() {
        lookDisabled++;
    }

    public void sensorSortOptimized() {
        sensorSortsOptimized++;
    }

    public void idleSensorTickSkipped() {
        idleSensorTicksSkipped++;
    }

    public void stationaryTravelSkipped() {
        stationaryTravelSkipped++;
    }

    public void stationaryCrammingSkipped() {
        stationaryCrammingSkipped++;
    }

    public boolean isProfiling() {
        return profiling;
    }

    /** Golem ticks are timed while {@code /cgp profile} is on or a benchmark is running. */
    public boolean shouldTimeGolemTicks() {
        return profiling || benchmarkTiming;
    }

    public void setBenchmarkTiming(boolean benchmarkTiming) {
        this.benchmarkTiming = benchmarkTiming;
    }

    public Snapshot snapshot() {
        return new Snapshot(lifetimeGolemTicks, lifetimeTimedGolemTicks, lifetimeTimedGolemNanos, lifetimeStacksTaken, lifetimeStacksPlaced);
    }

    public void setProfiling(boolean profiling) {
        this.profiling = profiling;
        profiledGolemTicks = 0;
        profiledGolemNanos = 0;
        profiledServerTicks = 0;
    }

    public void recordGolemTickNanos(long nanos) {
        lifetimeTimedGolemTicks++;
        lifetimeTimedGolemNanos += nanos;
        if (profiling) {
            profiledGolemTicks++;
            profiledGolemNanos += nanos;
        }
    }

    /** Returns true when a periodic log line is due, and restarts the log interval. */
    public boolean shouldLog(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            return false;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < intervalSeconds * 1_000_000_000L) {
            return false;
        }
        lastLogNanos = now;
        return true;
    }

    public String formatStatus(CopperGolemModConfig config) {
        double seconds = Math.max(1.0E-3D, (System.nanoTime() - windowStartNanos) / 1.0E9D);
        double minutes = seconds / 60.0D;
        double golemsPerTick = serverTicks == 0 ? 0.0D : (double) golemTicks / serverTicks;

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "CopperGolemMod status (last %.0fs, %d server ticks)%n", seconds, serverTicks));
        out.append("  enabled=").append(config.enabled)
            .append(", fastPlayerLookCheck=").append(config.optimizations.fastPlayerLookCheck)
            .append(", fastNearbyEntitySort=").append(config.optimizations.fastNearbyEntitySort)
            .append(", idleThrottle=").append(config.idleThrottle.enabled ? "every " + config.idleThrottle.sensorTickInterval + " ticks" : "off")
            .append(", stationarySorter=").append(config.isStationarySorterActive() ? "ON" : "off")
            .append(", golemsLookAtPlayers=").append(config.isPlayerLookDisabled() ? "off" : "on")
            .append('\n');
        out.append(String.format(Locale.ROOT, "  golems ticked per server tick: %.1f%n", golemsPerTick));
        out.append(String.format(Locale.ROOT, "  sorting: %d stacks taken (%.1f/min), %d stacks placed (%.1f/min)%n",
            stacksTaken, stacksTaken / minutes, stacksPlaced, stacksPlaced / minutes));
        out.append(String.format(Locale.ROOT, "  player-look scans: %d skipped, %d run, %d disabled by config%n", lookScansSkipped, lookScansRun, lookDisabled));
        out.append(String.format(Locale.ROOT, "  nearby-entity sorts optimized: %d%n", sensorSortsOptimized));
        out.append(String.format(Locale.ROOT, "  idle sensor ticks skipped: %d%n", idleSensorTicksSkipped));
        out.append(String.format(Locale.ROOT, "  stationary: %d travel skipped, %d cramming skipped%n", stationaryTravelSkipped, stationaryCrammingSkipped));
        if (profiling) {
            double avgMicros = profiledGolemTicks == 0 ? 0.0D : profiledGolemNanos / 1000.0D / profiledGolemTicks;
            double msPerServerTick = profiledServerTicks == 0 ? 0.0D : profiledGolemNanos / 1.0E6D / profiledServerTicks;
            out.append(String.format(Locale.ROOT, "  profile: %.2f us per golem tick, ~%.2f ms of golem ticking per server tick (since profile on or reset)%n",
                avgMicros, msPerServerTick));
        } else {
            out.append("  profile: off (use /cgp profile on)\n");
        }
        out.append("  Use /cgp bench ab to compare the mod on vs off, or Spark for a full profile.");
        return out.toString();
    }

    /** Lifetime counter values at one moment; subtract two snapshots to get the totals in between. */
    public record Snapshot(long golemTicks, long timedGolemTicks, long timedGolemNanos, long stacksTaken, long stacksPlaced) {
        public Snapshot minus(Snapshot earlier) {
            return new Snapshot(
                golemTicks - earlier.golemTicks,
                timedGolemTicks - earlier.timedGolemTicks,
                timedGolemNanos - earlier.timedGolemNanos,
                stacksTaken - earlier.stacksTaken,
                stacksPlaced - earlier.stacksPlaced
            );
        }
    }
}
