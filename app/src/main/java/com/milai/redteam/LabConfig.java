package com.milai.redteam;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.view.WindowManager;

/**
 * Runtime target description.
 *
 * Anchors are authored against a 1080x2400 baseline (the values you fill in),
 * then scaled to the device's real resolution at run time. That way the same
 * anchor table works on a different handset without re-measuring everything.
 */
public final class LabConfig {

    /** Baseline the anchors below are authored against. */
    public static final int BASE_W = 1080;
    public static final int BASE_H = 2400;

    public final String packageName;

    /** Logical screen size on the running device. */
    public final int screenW;
    public final int screenH;

    // --- anchors, in BASELINE coordinates. Replace with 米来 real values. ---
    /** 首页 -> 任务列表 的入口 */
    private final float[] aHome = {540, 2200};
    /** 任务列表第一行的可点区域 */
    private final float[] aTaskRow0 = {540, 760};
    /** 行间距（点第 2、3、4 行用） */
    private final float aTaskRowStep = 90;
    /** 任务详情页的领奖/完成按钮 */
    private final float[] aTaskDetailClaim = {540, 1500};
    /** 返回 */
    private final float[] aBack = {60, 120};
    /** 闲逛流滑动：起点 -> 终点 */
    private final float[] aScrollFrom = {540, 1600};
    private final float[] aScrollTo = {540, 700};

    public LabConfig(Context ctx, String packageName) {
        this.packageName = packageName;
        Point size = new Point();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealSize(size);
            }
        } catch (Throwable ignored) {
        }
        if (size.x <= 0 || size.y <= 0) {
            // fall back to the configuration, still better than a hard-coded guess
            Configuration c = ctx.getResources().getConfiguration();
            size.x = (int) (c.screenWidthDp * c.density);
            size.y = (int) (c.screenHeightDp * c.density);
        }
        this.screenW = size.x;
        this.screenH = size.y;
    }

    /** Scale a baseline coordinate pair to this device. */
    public float[] scaled(float[] base) {
        return new float[]{
                base[0] * screenW / (float) BASE_W,
                base[1] * screenH / (float) BASE_H
        };
    }

    public float[] home() {
        return scaled(aHome);
    }

    public float[] taskRow(int index) {
        return scaled(new float[]{aTaskRow0[0], aTaskRow0[1] + index * aTaskRowStep});
    }

    public float[] claim() {
        return scaled(aTaskDetailClaim);
    }

    public float[] back() {
        return scaled(aBack);
    }

    public float[] scrollFrom() {
        return scaled(aScrollFrom);
    }

    public float[] scrollTo() {
        return scaled(aScrollTo);
    }

    public long swipeDistancePx() {
        float[] f = scrollFrom();
        float[] t = scrollTo();
        return (long) Math.hypot(t[0] - f[0], t[1] - f[1]);
    }

    public String describe() {
        return packageName + " @ " + screenW + "x" + screenH;
    }
}
