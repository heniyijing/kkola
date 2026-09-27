package com.milai.redteam;

import java.util.Random;

/**
 * Per-generation behaviour profile, ported from adversary-lab/lab_driver.py so
 * the on-device engine and the desktop engine produce the same shapes.
 *
 * injectionClass records which injector is physically required. The runner
 * refuses to execute a generation whose injector is not available, instead of
 * silently degrading it into a weaker one (this was a real trap: `input swipe`
 * interpolates uniformly, so Gen2/Gen3 through the shell path would look
 * identical to Gen1 and produce a falsely reassuring scorecard).
 */
public final class Generation {

    public static final String SHELL_INPUT = "shell_input";
    public static final String IM_INJECT = "input_manager_inject";
    public static final String PHYSICAL = "physical_or_farm";
    public static final String PROTOCOL = "protocol_only";

    public final String name;
    public final double jitterPx;
    public final long settleMinMs;
    public final long settleMaxMs;
    public final boolean fixedSwipeDurations;
    public final long browseWaitMs;
    public final boolean resampleOrder;
    public final int multiInstance;
    public final boolean protocolOnly;
    public final String injectionClass;

    private Generation(String name, double jitterPx, long settleMinMs, long settleMaxMs,
                       boolean fixedSwipeDurations, long browseWaitMs, boolean resampleOrder,
                       int multiInstance, boolean protocolOnly, String injectionClass) {
        this.name = name;
        this.jitterPx = jitterPx;
        this.settleMinMs = settleMinMs;
        this.settleMaxMs = settleMaxMs;
        this.fixedSwipeDurations = fixedSwipeDurations;
        this.browseWaitMs = browseWaitMs;
        this.resampleOrder = resampleOrder;
        this.multiInstance = multiInstance;
        this.protocolOnly = protocolOnly;
        this.injectionClass = injectionClass;
    }

    public static Generation of(String name) {
        switch (name) {
            case "gen1":
                // 呼应 AutomationService: sleep(800 + nextInt(1201))，固定 650/1000，浏览 sleep(1000)
                return new Generation(name, 0.0, 800, 2000, true, 1000, false, 1, false, SHELL_INPUT);
            case "gen2":
                return new Generation(name, 2.5, 220, 9000, false, 4000, true, 1, false, IM_INJECT);
            case "gen3":
                return new Generation(name, 2.5, 220, 9000, false, 5000, true, 1, false, IM_INJECT);
            case "gen4":
                return new Generation(name, 2.5, 300, 9000, false, 5000, true, 6, false, PHYSICAL);
            case "gen5":
                return new Generation(name, 0.0, 220, 9000, false, 4000, false, 1, true, PROTOCOL);
            default:
                throw new IllegalArgumentException("未知代数: " + name
                        + "，可选: " + LabConfig.generations());
        }
    }

    /** gen1 是有界均匀；gen2+ 是长尾。 */
    public long settleMs(Random rng) {
        if (settleMinMs == 800 && settleMaxMs == 2000) {
            return 800 + rng.nextInt(1201);
        }
        // lognormal-ish long tail, clamped
        double v = Math.exp(Math.log(1100) + rng.nextGaussian() * 0.75);
        return (long) Math.max(settleMinMs, Math.min(settleMaxMs, v));
    }

    public long browseWait(Random rng) {
        if (browseWaitMs == 1000) {
            return 1000; // gen1 的浏览空等是固定的
        }
        double v = Math.exp(Math.log(browseWaitMs) + rng.nextGaussian() * 0.9);
        return (long) Math.max(300, Math.min(60000, v));
    }

    public double jitter(Random rng) {
        return jitterPx == 0.0 ? 0.0 : rng.nextGaussian() * jitterPx;
    }

    /** 固定滑动时长（gen1）；其余按 Fitts 近似随机。 */
    public long swipeDurationMs(Random rng, double distancePx, long gen1Fixed) {
        if (fixedSwipeDurations) {
            return gen1Fixed;
        }
        double base = 180 + 120 * (Math.log(distancePx / 40.0 + 1) / Math.log(2));
        return (long) Math.max(90, Math.min(1200, base + rng.nextGaussian() * 35));
    }
}
