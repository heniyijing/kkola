package com.milai.redteam;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hard gate in front of every scenario run.
 *
 * This exists so a lab run can never drive a package it was not deliberately
 * pointed at. Two lists:
 *
 *   ALLOWED   packages this build is permitted to drive (your own surfaces)
 *   FORBIDDEN packages that are refused outright -- no override flag
 *
 * If you need to drive another package, add it to ALLOWED deliberately and
 * rebuild. That is intentional friction: it keeps "point the tool at someone
 * else's app" from being a runtime toggle.
 */
public final class SafetyGate {

    /**
     * Packages this build may drive.
     *
     * com.taobao.taobao 是「去赚金币 / 去赚能量」快捷按钮的目标——
     * 仅在你自己的账号上跑，见 MainActivity 的快捷场景。
     * 不在这里的包一律拒绝执行——这是刻意的摩擦，避免顺手把工具指到别处。
     */
    private static final Set<String> ALLOWED = new LinkedHashSet<>(Arrays.asList(
            "com.taobao.taobao"
    ));

    /**
     * Refused outright. No override exists, by design.
     * 其余第三方平台一律不在本工具的作业范围内。
     */
    private static final Set<String> FORBIDDEN = new LinkedHashSet<>(Arrays.asList(
            "com.taobao.idlefish",
            "com.eg.android.AlipayGphone",
            "com.tencent.mm"
    ));

    private SafetyGate() {}

    public static final class Refused extends SecurityException {
        public Refused(String message) {
            super(message);
        }
    }

    /** @throws Refused if the package may not be driven by this build. */
    public static void requireAllowed(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            throw new Refused("目标包名为空");
        }
        String p = pkg.trim();
        if (FORBIDDEN.contains(p)) {
            throw new Refused("包 " + p + " 在禁用清单内，拒绝启动场景。"
                    + "本工具只用于自有一方应用的授权测试。");
        }
        if (!ALLOWED.contains(p)) {
            throw new Refused("包 " + p + " 不在允许清单内。允许清单：" + ALLOWED
                    + "。要驱动它，请显式加入 SafetyGate.ALLOWED 并重新构建。");
        }
    }

    public static boolean isAllowed(String pkg) {
        return pkg != null && ALLOWED.contains(pkg.trim()) && !FORBIDDEN.contains(pkg.trim());
    }

    public static Set<String> allowed() {
        return Collections.unmodifiableSet(ALLOWED);
    }

    public static Set<String> forbidden() {
        return Collections.unmodifiableSet(FORBIDDEN);
    }
}
