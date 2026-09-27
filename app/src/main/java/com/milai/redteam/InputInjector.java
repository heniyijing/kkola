package com.milai.redteam;

/**
 * One way of putting input into the device.
 *
 * Implementations declare {@link #injectionClass()}, which the runner matches
 * against the generation's required class. A mismatch aborts the run instead of
 * degrading silently: `input swipe` interpolates uniformly, so routing Gen2/Gen3
 * through the shell path would make them look like Gen1 and produce a falsely
 * reassuring result.
 */
public interface InputInjector {

    /** {@link Generation#SHELL_INPUT}, {@link Generation#IM_INJECT} or "dry_run". */
    String injectionClass();

    /** Human-readable label for the status line. */
    String describe();

    /** True if this injector can emit real trajectories (Bezier + tremor + pressure). */
    boolean canPhysiological();

    /** Launch the target app. Returns false if it could not be started. */
    boolean launch(String pkg);

    /**
     * One tap. Times are absolute uptime millis so the trace and the injected
     * events agree.
     */
    void tap(float x, float y, long downTimeMs, long upTimeMs);

    /**
     * A swipe/stroke. When {@code physiological} is true the injector must emit
     * a genuine trajectory; injectors that cannot must throw rather than
     * quietly emitting a straight uniform line.
     */
    void swipe(float x0, float y0, float x1, float y1, long durationMs, boolean physiological);
}
