package com.milai.redteam;

/** One planned interaction. Coordinates are already device-scaled. */
public final class SceneStep {

    public enum Kind { SCREEN, TAP, SWIPE, CLAIM, WAIT, BROWSE }

    public final Kind kind;
    public final String screen;
    public final Boolean rewardPath;
    public final float x;
    public final float y;
    public final long durationMs;
    public final long waitMs;
    public final String reason;
    public final int instance;

    private SceneStep(Kind kind, String screen, Boolean rewardPath, float x, float y,
                      long durationMs, long waitMs, String reason, int instance) {
        this.kind = kind;
        this.screen = screen;
        this.rewardPath = rewardPath;
        this.x = x;
        this.y = y;
        this.durationMs = durationMs;
        this.waitMs = waitMs;
        this.reason = reason;
        this.instance = instance;
    }

    public static SceneStep screen(String name, boolean rewardPath) {
        return new SceneStep(Kind.SCREEN, name, rewardPath, 0, 0, 0, 0, null, 0);
    }

    public static SceneStep tap(float x, float y) {
        return new SceneStep(Kind.TAP, null, null, x, y, 0, 0, null, 0);
    }

    public static SceneStep claim(float x, float y, String rewardScreen) {
        return new SceneStep(Kind.CLAIM, rewardScreen, true, x, y, 0, 0, "claim", 0);
    }

    public static SceneStep swipe(float x0, float y0, float x1, float y1, long durationMs) {
        return new SceneStep(Kind.SWIPE, null, null, x0, y0, durationMs, 0, null, 0);
    }

    public static SceneStep wait(long ms, String reason) {
        return new SceneStep(Kind.WAIT, null, null, 0, 0, 0, ms, reason, 0);
    }

    public static SceneStep browse(long ms, String reason) {
        return new SceneStep(Kind.BROWSE, null, false, 0, 0, 0, ms, reason, 0);
    }

    public SceneStep withInstance(int inst) {
        return new SceneStep(kind, screen, rewardPath, x, y, durationMs, waitMs, reason, inst);
    }
}
