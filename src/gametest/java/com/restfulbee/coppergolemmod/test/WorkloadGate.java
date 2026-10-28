package com.restfulbee.coppergolemmod.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How the workload benchmark judges MSPT, kept separate from the game so it can be tested directly.
 *
 * <p>The CI runner is shared with other jobs. When one of them runs at the same time, some game ticks take much longer
 * than the rest. A tick's median barely moves, but the mean jumps, so phases are compared by their median MSPT, and a
 * phase whose mean is well above its median is treated as disturbed: its on/off pair is run again. A disturbed run can
 * still pass, but can't fail on MSPT alone, because its numbers don't measure the mod.
 */
final class WorkloadGate {
    /** Undisturbed phases measured 1.00 to 1.04 times their median; disturbed ones 1.4 to 1.8. */
    static final double DISTURBED_MEAN_OVER_MEDIAN = 1.15D;

    enum Verdict { GAIN, NO_GAIN, INCONCLUSIVE }

    /** One phase's MSPT, and which on/off pair it belongs to. */
    record Phase(int pair, double msptMean, double msptMedian) {
    }

    private WorkloadGate() {
    }

    static boolean disturbed(double msptMean, double msptMedian) {
        return msptMedian > 0.0D && msptMean > msptMedian * DISTURBED_MEAN_OVER_MEDIAN;
    }

    /** The pairs with at least one disturbed phase. */
    static Set<Integer> disturbedPairs(List<Phase> phases) {
        Set<Integer> pairs = new TreeSet<>();
        for (Phase phase : phases) {
            if (disturbed(phase.msptMean(), phase.msptMedian())) {
                pairs.add(phase.pair());
            }
        }
        return pairs;
    }

    /** How much lower the mod-on median must be to count as a gain: 2% of mod off, at least 0.5 ms. */
    static double requiredGain(double offMedian) {
        return Math.max(0.5D, offMedian * 0.02D);
    }

    static Verdict mspt(double onMedian, double offMedian, boolean stillDisturbed) {
        if (onMedian <= offMedian - requiredGain(offMedian)) {
            return Verdict.GAIN;
        }
        return stillDisturbed ? Verdict.INCONCLUSIVE : Verdict.NO_GAIN;
    }

    /** The 1-minute load average of the machine (the host's, even inside a container), or -1 if unreadable. */
    static double loadAverage() {
        try {
            String content = Files.readString(Path.of("/proc/loadavg"));
            return Double.parseDouble(content.substring(0, content.indexOf(' ')));
        } catch (Exception exception) {
            return -1.0D;
        }
    }

    /** The CPU temperature in degrees Celsius, or -1 if the machine doesn't expose it. */
    static double cpuTemperature() {
        try {
            String content = Files.readString(Path.of("/sys/class/thermal/thermal_zone0/temp")).trim();
            return Integer.parseInt(content) / 1000.0D;
        } catch (Exception exception) {
            return -1.0D;
        }
    }
}
