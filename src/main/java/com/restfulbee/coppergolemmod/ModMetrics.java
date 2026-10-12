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
    private long sensorSortsOptimized;
    private long idleSensorTicksSkipped;
    private long stationaryTravelSkipped;
    private long stationaryCrammingSkipped;

    private boolean profiling;
    private long profiledGolemTicks;
    private long profiledGolemNanos;
    private long profiledServerTicks;
    private long lastLogNanos = System.nanoTime();

    public void reset() {
        windowStartNanos = System.nanoTime();
        serverTicks = 0;
        golemTicks = 0;
        stacksTaken = 0;
        stacksPlaced = 0;
        lookScansSkipped = 0;
        lookScansRun = 0;
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
    }

    public void stackTaken() {
        stacksTaken++;
    }

    public void stackPlaced() {
        stacksPlaced++;
    }

    public void lookScanSkipped() {
        lookScansSkipped++;
    }

    public void lookScanRun() {
        lookScansRun++;
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

    public void setProfiling(boolean profiling) {
        this.profiling = profiling;
        profiledGolemTicks = 0;
        profiledGolemNanos = 0;
        profiledServerTicks = 0;
    }

    public void recordGolemTickNanos(long nanos) {
        profiledGolemTicks++;
        profiledGolemNanos += nanos;
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
            .append('\n');
        out.append(String.format(Locale.ROOT, "  golems ticked per server tick: %.1f%n", golemsPerTick));
        out.append(String.format(Locale.ROOT, "  sorting: %d stacks taken (%.1f/min), %d stacks placed (%.1f/min)%n",
            stacksTaken, stacksTaken / minutes, stacksPlaced, stacksPlaced / minutes));
        out.append(String.format(Locale.ROOT, "  player-look scans: %d skipped, %d run%n", lookScansSkipped, lookScansRun));
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
        out.append("  Use Spark for overall TPS/MSPT.");
        return out.toString();
    }
}
