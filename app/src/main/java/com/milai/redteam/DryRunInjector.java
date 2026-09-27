package com.milai.redteam;

import java.io.File;

/**
 * Executes a plan without touching the device.
 *
 * Two uses:
 *   1. Validate anchors and step ordering before risking real input.
 *   2. Produce traces on a device with no Shizuku, for scorer development.
 *
 * The emitted event stream is synthetic but internally consistent with the
 * generation's profile, so it is NOT evidence about a real client's input
 * channel. Only use these traces for pipeline testing.
 */
public final class DryRunInjector implements InputInjector {

    private final Generation gen;
    private final java.util.Random rng;

    public DryRunInjector(Generation gen, int seed) {
        this.gen = gen;
        this.rng = new java.util.Random(seed);
    }

    @Override
    public String injectionClass() {
        return "dry_run";
    }

    @Override
    public String describe() {
        return "dry-run（不注入，仅出 trace）";
    }

    @Override
    public boolean canPhysiological() {
        return true; // it fabricates the trajectory rather than injecting it
    }

    @Override
    public boolean launch(String pkg) {
        AppState.log("dry-run: 跳过启动 " + pkg);
        return true;
    }

    @Override
    public void tap(float x, float y, long downTimeMs, long upTimeMs) {
        // nothing is injected; SceneRunner records the trace
    }

    @Override
    public void swipe(float x0, float y0, float x1, float y1, long durationMs, boolean physiological) {
        // nothing is injected; SceneRunner records the trace
    }

    /** File used by the UI to tell the operator where traces land. */
    public static File traceDir(android.content.Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "traces");
        if (!dir.exists() && !dir.mkdirs()) {
            AppState.log("无法创建 trace 目录: " + dir);
        }
        return dir;
    }
}
