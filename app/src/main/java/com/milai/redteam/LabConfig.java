package com.milai.redteam;

import android.content.Context;
import android.graphics.Point;
import android.util.DisplayMetrics;
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
    /** 淘宝首页功能宫格 -> 领淘金币 入口（截图 1） */
    private final float[] aGoldEntry = {617, 516};
    /** 双11活动页 -> 去赚金币 按钮（截图 2） */
    private final float[] aCoinButton = {82, 1012};
    /** 双11活动页 -> 去赚能量 按钮（截图 2） */
    private final float[] aEnergyButton = {923, 1012};
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
            // fall back to the display metrics, still better than a hard-coded guess
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            size.x = dm.widthPixels;
            size.y = dm.heightPixels;
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

    /** 淘宝首页「领淘金币」入口 */
    public float[] goldEntry() {
        return scaled(aGoldEntry);
    }

    /** 双11活动页「去赚金币」按钮 */
    public float[] coinButton() {
        return scaled(aCoinButton);
    }

    /** 双11活动页「去赚能量」按钮 */
    public float[] energyButton() {
        return scaled(aEnergyButton);
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

    /** 可选代数（与 Generation.of 支持的名称一致）。 */
    public static String[] generations() {
        return new String[]{"gen1", "gen2", "gen3", "gen4", "gen5"};
    }
}
