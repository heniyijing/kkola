package com.milai.redteam;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry for injected strokes.
 *
 * The runner records a trace of what was injected, so both the injector and the
 * recorder must agree on every coordinate. They agree by construction: both
 * call these methods. If the formulas were duplicated, the trace could drift
 * from reality and the scorer would grade fiction.
 */
public final class StrokeSampler {

    /** One sample within a stroke. dt is relative to the stroke start. */
    public static final class Pt {
        public final long dt;
        public final float x;
        public final float y;
        public final float pressure;
        public final float size;

        Pt(long dt, float x, float y, float pressure, float size) {
            this.dt = dt;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.size = size;
        }
    }

    private StrokeSampler() {}

    /** START | MOVE | END */
    public static String actionOf(int index, int count) {
        if (index == 0) return "START";
        if (index == count - 1) return "END";
        return "MOVE";
    }

    /**
     * Straight, uniformly interpolated stroke. This is what the shell-level
     * `input swipe` produces regardless of its endpoints or duration -- which is
     * precisely why it trips a spatial-linearity + time-uniformity rule.
     */
    public static List<Pt> linear(float x0, float y0, float x1, float y1, long durationMs) {
        int steps = 12;
        List<Pt> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            float s = i / (float) steps;
            out.add(new Pt(Math.round(durationMs * s),
                    x0 + (x1 - x0) * s,
                    y0 + (y1 - y0) * s,
                    1.0f, 0.0f));
        }
        return out;
    }

    /**
     * Physiological stroke: minimum-jerk speed profile along a cubic Bezier,
     * with an 8-12 Hz tremor and pressure/size dynamics. This is the shape that
     * makes trajectory-based detection fail.
     */
    public static List<Pt> physiological(float x0, float y0, float x1, float y1,
                                         long durationMs, java.util.Random rng) {
        double dist = Math.hypot(x1 - x0, y1 - y0);
        double bend = (rng.nextDouble() * 0.2 - 0.1) * dist;
        double nx = -(y1 - y0), ny = (x1 - x0);
        double nlen = Math.hypot(nx, ny);
        if (nlen == 0) nlen = 1;
        double c1x = x0 + (x1 - x0) * 0.33 + nx / nlen * bend;
        double c1y = y0 + (y1 - y0) * 0.33 + ny / nlen * bend;
        double c2x = x0 + (x1 - x0) * 0.66 + nx / nlen * bend * 0.6;
        double c2y = y0 + (y1 - y0) * 0.66 + ny / nlen * bend * 0.6;

        double tremorHz = 8.0 + rng.nextDouble() * 4.0;
        double tremorAmp = 0.6 + rng.nextDouble() * 1.6;
        double phase = rng.nextDouble() * Math.PI * 2;

        int sampleMs = 12;
        int n = Math.max(3, (int) (durationMs / sampleMs));
        List<Pt> out = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double s = i / (double) n;
            double sm = 10 * Math.pow(s, 3) - 15 * Math.pow(s, 4) + 6 * Math.pow(s, 5);
            double u = 1 - sm;
            double px = u * u * u * x0 + 3 * u * u * sm * c1x + 3 * u * sm * sm * c2x + sm * sm * sm * x1;
            double py = u * u * u * y0 + 3 * u * u * sm * c1y + 3 * u * sm * sm * c2y + sm * sm * sm * y1;
            double ph = 2 * Math.PI * tremorHz * (i * sampleMs / 1000.0) + phase;
            px += Math.sin(ph) * tremorAmp + rng.nextGaussian() * 0.35;
            py += Math.cos(ph * 1.07) * tremorAmp + rng.nextGaussian() * 0.35;

            float pressure = clamp((float) (0.82 - 0.5 * Math.abs(0.5 - s) + rng.nextGaussian() * 0.05), 0.2f, 1.0f);
            float size = clamp((float) (0.18 + 0.12 * Math.sin(Math.PI * s) + rng.nextGaussian() * 0.02), 0.05f, 0.6f);
            out.add(new Pt(Math.round(durationMs * s), (float) px, (float) py, pressure, size));
        }
        return out;
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
