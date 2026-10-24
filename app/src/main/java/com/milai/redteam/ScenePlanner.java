package com.milai.redteam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds the ordered interaction plan for one capability generation.
 * Mirrors adversary-lab/lab_driver.py so both engines produce the same shapes.
 *
 * gen1  shell-equivalent: fixed-endpoint swipes, bounded-uniform settles, a
 *       bare fixed browse wait.
 * gen2  endpoint jitter, injected strokes, long-tailed settles, resampled
 *       task order.
 * gen3  minimum-jerk Bezier + tremor + pressure dynamics (needs the
 *       InputManager injector).
 * gen4  the same body repeated across several instances, sequenced (device
 *       farm / multi-open shape). Sequencing -- not real parallelism -- is what
 *       a single handset can produce, and it still exercises the graph rules.
 * gen5  no UI interaction at all; one protocol claim marker.
 */
public final class ScenePlanner {

    public static final int ROWS = 4;

    /** 快捷场景：去赚金币 */
    public static final String SCENE_COIN = "coin";
    /** 快捷场景：去赚能量 */
    public static final String SCENE_ENERGY = "energy";

    private ScenePlanner() {}

    /**
     * 淘宝双11活动页快捷场景：首页 -> 领淘金币入口 -> 活动页 -> 去赚金币/去赚能量。
     *
     * @param scene 取 {@link #SCENE_COIN} 或 {@link #SCENE_ENERGY}
     */
    public static List<SceneStep> buildTaobaoScene(Generation gen, LabConfig cfg, int seed,
                                                   String scene) {
        Random rng = new Random(seed);
        List<SceneStep> steps = new ArrayList<>();

        steps.add(SceneStep.screen("launch", false).withInstance(0));
        steps.add(SceneStep.wait(1500, "launch_settle").withInstance(0));
        steps.add(SceneStep.screen("home", false).withInstance(0));

        // 首页功能宫格 -> 领淘金币 入口
        float[] entry = cfg.goldEntry();
        steps.add(SceneStep.tap(jit(gen, rng, entry[0]), jit(gen, rng, entry[1])).withInstance(0));
        steps.add(SceneStep.wait(gen.settleMs(rng), "home_to_activity").withInstance(0));
        steps.add(SceneStep.screen("activity_page", true).withInstance(0));

        // 活动页 -> 去赚金币 / 去赚能量
        float[] btn = SCENE_ENERGY.equals(scene) ? cfg.energyButton() : cfg.coinButton();
        steps.add(SceneStep.tap(jit(gen, rng, btn[0]), jit(gen, rng, btn[1])).withInstance(0));
        steps.add(SceneStep.wait(gen.settleMs(rng), "enter_task").withInstance(0));
        steps.add(SceneStep.screen(scene + "_task", true).withInstance(0));

        // 进入任务页后先停留浏览一段时间（后续可按目标页继续补锚点）
        steps.add(SceneStep.browse(gen.browseWait(rng), scene + "_browse").withInstance(0));

        steps.add(SceneStep.screen("home", false).withInstance(0));
        steps.add(SceneStep.wait(gen.settleMs(rng), "session_end").withInstance(0));
        return Collections.unmodifiableList(steps);
    }

    public static List<SceneStep> build(Generation gen, LabConfig cfg, int seed) {
        Random rng = new Random(seed);
        List<SceneStep> steps = new ArrayList<>();

        if (gen.protocolOnly) {
            // Gen5: 完全不碰 UI。记录一个标记，供服务端埋点侧对齐。
            steps.add(SceneStep.claim(0, 0, "reward_claim")
                    .withInstance(0));
            return steps;
        }

        int instances = Math.max(1, gen.multiInstance);
        for (int inst = 0; inst < instances; inst++) {
            buildInstance(gen, cfg, rng, steps, inst);
        }
        return Collections.unmodifiableList(steps);
    }

    private static void buildInstance(Generation gen, LabConfig cfg, Random rng,
                                      List<SceneStep> out, int inst) {
        float[] scrollFrom = cfg.scrollFrom();
        float[] scrollTo = cfg.scrollTo();
        long scrollDist = cfg.swipeDistancePx();

        out.add(SceneStep.screen("launch", false).withInstance(inst));
        out.add(SceneStep.wait(1200, "launch_settle").withInstance(inst));
        out.add(SceneStep.screen("home", false).withInstance(inst));

        float[] home = cfg.home();
        out.add(SceneStep.tap(jit(gen, rng, home[0]), jit(gen, rng, home[1])).withInstance(inst));
        out.add(SceneStep.wait(gen.settleMs(rng), "home_to_list").withInstance(inst));
        out.add(SceneStep.screen("task_list", true).withInstance(inst));

        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) rows.add(i);
        if (gen.resampleOrder) Collections.shuffle(rows, rng);

        for (int n = 0; n < rows.size(); n++) {
            int row = rows.get(n);

            out.add(SceneStep.swipe(scrollFrom[0], scrollFrom[1], scrollTo[0], scrollTo[1],
                    gen.swipeDurationMs(rng, scrollDist, 650)).withInstance(inst));
            out.add(SceneStep.wait(gen.settleMs(rng), "after_scroll").withInstance(inst));

            float[] r = cfg.taskRow(row);
            out.add(SceneStep.tap(jit(gen, rng, r[0]), jit(gen, rng, r[1])).withInstance(inst));
            out.add(SceneStep.wait(gen.settleMs(rng), "task_page_load").withInstance(inst));
            out.add(SceneStep.screen("task_detail", true).withInstance(inst));

            out.add(SceneStep.swipe(scrollFrom[0], scrollFrom[1], scrollTo[0], scrollTo[1],
                    gen.swipeDurationMs(rng, scrollDist, 1000)).withInstance(inst));
            out.add(SceneStep.wait(gen.settleMs(rng), "task_scroll").withInstance(inst));

            float[] claim = cfg.claim();
            out.add(SceneStep.claim(jit(gen, rng, claim[0]), jit(gen, rng, claim[1]),
                    "reward_claim").withInstance(inst));
            out.add(SceneStep.wait(gen.settleMs(rng), "task_submit").withInstance(inst));
            out.add(SceneStep.screen("task_list", true).withInstance(inst));

            // 非奖励路径：这就是「无收益行为占比」的分子来源。
            if (n < 2 && rng.nextDouble() < 0.8) {
                out.add(SceneStep.screen("browse_feed", false).withInstance(inst));
                out.add(SceneStep.browse(gen.browseWait(rng), "browsing_wait").withInstance(inst));
            }
        }

        out.add(SceneStep.screen("home", false).withInstance(inst));
        out.add(SceneStep.wait(gen.settleMs(rng), "session_end").withInstance(inst));
    }

    private static float jit(Generation gen, Random rng, float v) {
        return (float) (v + gen.jitter(rng));
    }
}
