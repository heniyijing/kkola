package com.milai.redteam;

/**
 * Injection through the privileged shell (`input tap` / `input swipe`),
 * executed via a Shizuku UserService.
 *
 * This is the capability level of the tool this lab was derived from, and the
 * reason Gen2/Gen3 cannot be reproduced here: `input swipe` interpolates
 * uniformly and carries no pressure or tremor channel. `canPhysiological()`
 * returns false, and `swipe(..., true)` throws rather than pretending -- a
 * silent downgrade would make Gen3 indistinguishable from Gen1 in the results.
 */
public final class ShellInjector implements InputInjector {

    public ShellInjector() {
    }

    @Override
    public String injectionClass() {
        return Generation.SHELL_INPUT;
    }

    @Override
    public String describe() {
        return "Shizuku shell input（Gen1 级）";
    }

    @Override
    public boolean canPhysiological() {
        return false;
    }

    @Override
    public boolean launch(String pkg) {
        String out = exec("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        return out != null && !out.contains("No activities found");
    }

    @Override
    public void tap(float x, float y, long downTimeMs, long upTimeMs) {
        long dur = upTimeMs - downTimeMs;
        if (dur <= 0) {
            exec("input tap " + r(x) + " " + r(y));
        } else {
            // input tap has no duration parameter; a same-point swipe with a
            // duration is the closest shell equivalent.
            exec("input swipe " + r(x) + " " + r(y) + " " + r(x) + " " + r(y) + " " + dur);
        }
    }

    @Override
    public void swipe(float x0, float y0, float x1, float y1, long durationMs, boolean physiological) {
        if (physiological) {
            throw new IllegalStateException(
                    "ShellInjector 无法生成生理级轨迹（input swipe 内部为均匀插值）。"
                            + "要跑 Gen2/Gen3，请使用 InputManagerInjector。");
        }
        exec("input swipe " + r(x0) + " " + r(y0) + " " + r(x1) + " " + r(y1) + " " + durationMs);
    }

    private static int r(float v) {
        return Math.round(v);
    }

    static String exec(String cmd) {
        try {
            return ShizukuShell.exec(cmd);
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
