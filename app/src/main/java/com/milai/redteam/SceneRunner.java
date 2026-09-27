package com.milai.redteam;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.util.List;
import java.util.Random;

/**
 * Runs one planned session against the target and records a trace of exactly
 * what was injected.
 *
 * Ordering per step: inject, then record, then advance the clock. Recording the
 * samples the injector actually produced (rather than recomputing them) is what
 * keeps the trace honest -- the scorer grades the exported file, so a trace that
 * drifts from reality would produce a confident, wrong answer.
 *
 * Injection timestamps (SystemClock.uptimeMillis) are written into the trace
 * instead of a fabricated timeline, so timing statistics reflect the run.
 */
public final class SceneRunner {

    private final Context ctx;
    private final LabConfig cfg;
    private final Generation gen;
    private final InputInjector injector;
    private final List<SceneStep> steps;
    private final String accountId;
    private final boolean recordTraces;

    private final Random rng = new Random(20260227L);
    private volatile boolean stopRequested = false;

    public SceneRunner(Context ctx, LabConfig cfg, Generation gen, InputInjector injector,
                       List<SceneStep> steps, String accountId, boolean recordTraces) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.gen = gen;
        this.injector = injector;
        this.steps = steps;
        this.accountId = accountId;
        this.recordTraces = recordTraces;
    }

    public void requestStop() {
        stopRequested = true;
    }

    public int stepCount() {
        return steps.size();
    }

    /** @return number of steps executed (0 if aborted early). */
    public int run(int sessionIndex) {
        TraceWriter trace = null;
        long t0 = SystemClock.uptimeMillis();
        long wall = System.currentTimeMillis();
        File traceFile = null;

        if (recordTraces) {
            File dir = DryRunInjector.traceDir(ctx);
            traceFile = new File(dir, "sessions.jsonl");
            try {
                trace = new TraceWriter(traceFile,
                        "milai-" + gen.name + "-" + String.format("%04d", sessionIndex),
                        gen.name,
                        "device-run " + android.os.Build.MODEL + " android-" + android.os.Build.VERSION.SDK_INT,
                        accountId, t0, wall);
            } catch (Throwable e) {
                AppState.log("无法打开 trace 文件: " + e.getMessage());
            }
        }

        int executed = 0;
        try {
            for (SceneStep s : steps) {
                if (stopRequested) {
                    AppState.log("收到停止请求，第 " + executed + " 步中止");
                    break;
                }
                execute(s, trace);
                executed++;
            }
        } catch (Throwable e) {
            AppState.log("场景执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            long end = SystemClock.uptimeMillis();
            if (trace != null) {
                trace.commit(end);
                trace.close();
            }
        }

        if (traceFile != null && executed > 0) {
            AppState.log("trace 已写出: " + traceFile.getAbsolutePath());
        }
        return executed;
    }

    private void execute(SceneStep s, TraceWriter trace) {
        switch (s.kind) {
            case SCREEN:
                if (trace != null) trace.screen(SystemClock.uptimeMillis(), s.screen, s.rewardPath);
                // 等页面稳定：gen1 是有界均匀，其余是长尾
                pause(gen.settleMs(rng));
                break;

            case TAP: {
                long down = SystemClock.uptimeMillis();
                long dur = tapDurationMs();
                long up = down + dur;
                injector.tap(s.x, s.y, down, up);
                if (trace != null) {
                    recordTapSamples(trace, s.x, s.y, down, up);
                }
                break;
            }

            case CLAIM: {
                long down = SystemClock.uptimeMillis();
                long dur = tapDurationMs();
                long up = down + dur;
                if (trace != null && s.screen != null) {
                    trace.screen(down, s.screen, true);
                }
                injector.tap(s.x, s.y, down, up);
                if (trace != null) {
                    recordTapSamples(trace, s.x, s.y, down, up);
                }
                break;
            }

            case SWIPE: {
                boolean phys = allowsPhysiological();
                doSwipe(s.x, s.y, s.x, s.y, s.durationMs, phys, trace);
                break;
            }

            case WAIT:
            case BROWSE: {
                long now = SystemClock.uptimeMillis();
                if (trace != null) {
                    trace.idle(now, s.waitMs, s.reason == null ? s.kind.name().toLowerCase() : s.reason);
                }
                pause(s.waitMs);
                break;
            }

            default:
                break;
        }
    }

    /**
     * Press duration for a tap.
     *
     * The shell path gets 0 on purpose: `adb shell input tap` is reported by the
     * framework as a same-timestamp DOWN/UP with no MOVE, which is itself part of
     * what makes Gen1's input look synthetic. Recorded at 0 it models reality;
     * padding it to a plausible-looking duration would understate that signal.
     */
    private long tapDurationMs() {
        if (Generation.SHELL_INPUT.equals(injector.injectionClass())) {
            return 0;
        }
        return Math.max(45, Math.round(90 + rng.nextGaussian() * 25));
    }

    private boolean allowsPhysiological() {
        // Only the three-generation profile asks for a real trajectory.
        return injector.canPhysiological() && !gen.fixedSwipeDurations;
    }

    private void recordTapSamples(TraceWriter trace, float x, float y, long down, long up) {
        if (injector instanceof InputManagerInjector) {
            StrokeSampler.Pt[] p = ((InputManagerInjector) injector).tapSample(x, y, down, up);
            trace.touch(down, "DOWN", p[0].x, p[0].y, p[0].pressure, p[0].size);
            trace.touch(up, "UP", p[1].x, p[1].y, p[1].pressure, p[1].size);
        } else if (injector instanceof DryRunInjector) {
            // fabricate a profile-consistent pair so the pipeline can be tested
            double j = gen.jitterPx;
            float jx = (float) (rng.nextGaussian() * j);
            float jy = (float) (rng.nextGaussian() * j);
            float pr = gen.fixedSwipeDurations ? 1.0f : StrokeSampler.clamp((float) (0.78 + rng.nextGaussian() * 0.08), 0.35f, 1.0f);
            trace.touch(down, "DOWN", x, y, pr, gen.fixedSwipeDurations ? 0.0f : 0.22f);
            trace.touch(up, "UP", x + jx, y + jy, pr, gen.fixedSwipeDurations ? 0.0f : 0.18f);
        } else {
            // shell path: `input tap` -> identical DOWN/UP coordinates, no MOVE
            trace.touch(down, "DOWN", x, y, 1.0f, 0.0f);
            trace.touch(up, "UP", x, y, 1.0f, 0.0f);
        }
    }

    /**
     * Perform a stroke and record the samples that were actually injected.
     * The injector is the single source of truth for the coordinates.
     */
    private void doSwipe(float x0, float y0, float x1, float y1, long durationMs,
                         boolean phys, TraceWriter trace) {
        long t0 = SystemClock.uptimeMillis();
        List<StrokeSampler.Pt> pts;
        if (injector instanceof InputManagerInjector) {
            pts = ((InputManagerInjector) injector).swipeSample(x0, y0, x1, y1, durationMs, phys);
        } else if (injector instanceof DryRunInjector) {
            pts = phys ? StrokeSampler.physiological(x0, y0, x1, y1, durationMs, rng)
                       : StrokeSampler.linear(x0, y0, x1, y1, durationMs);
            // DryRun still needs to emit the same synthetic shapes for pipeline tests
            ((DryRunInjector) injector).swipe(x0, y0, x1, y1, durationMs, phys);
        } else {
            if (phys) {
                throw new IllegalStateException(
                        "当前注入器无法生成生理级轨迹，拒绝把 Gen2/Gen3 退化成 Gen1");
            }
            pts = StrokeSampler.linear(x0, y0, x1, y1, durationMs);
            injector.swipe(x0, y0, x1, y1, durationMs, false);
        }

        if (trace != null) {
            for (int i = 0; i < pts.size(); i++) {
                StrokeSampler.Pt p = pts.get(i);
                trace.touch(t0 + p.dt, StrokeSampler.actionOf(i, pts.size()),
                        p.x, p.y, p.pressure, p.size);
            }
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(Math.max(1, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
