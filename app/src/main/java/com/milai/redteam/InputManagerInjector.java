package com.milai.redteam;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Injection at the InputManager level -- what makes Gen2/Gen3 possible.
 *
 * Four hidden-API touch points, all reflective:
 *   1. ServiceManager.getService("input")        -> IBinder
 *   2. IInputManager.Stub.asInterface(binder)     -> proxy
 *   3. IInputManager.injectInputEvent(...)        -> boolean
 *   4. MotionEvent.obtain(13 long-typed params)   -> MotionEvent
 *
 * =========================== RISK NOTICE ===========================
 * Least verifiable file in the project: written without a compiler and
 * without your device. Budget your first debugging session here.
 *
 * Two common failure modes:
 *
 *  a) Hidden-API blocklist. Since Android 9 hidden APIs are restricted for
 *     non-system apps, and targetSdk >= 30 narrows it further. If step 3 is
 *     blocked, add org.lsposed.hiddenapibypass:hiddenapibypass:4.3 and call
 *     in Application.onCreate():
 *        HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/input/");
 *        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/InputManager");
 *
 *  b) MotionEvent.obtain overload drift across API levels. If step 4 fails,
 *     log describeObtainOverloads() and match the arity-13 overload by hand.
 *
 * Failure policy: NO silent fallback to the shell path. A fallback would turn
 * Gen3 into Gen1 and yield a falsely reassuring scorecard.
 * ====================================================================
 */
public final class InputManagerInjector implements InputInjector {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    private static final int MODE_ASYNC = 0;

    private final Object proxy;
    private final Method injectMethod;
    private final java.util.Random rng = new java.util.Random(20260227L);

    public InputManagerInjector() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "input");
        if (binder == null) {
            throw new IllegalStateException("ServiceManager.getService(\"input\") 返回 null");
        }

        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", android.os.IBinder.class);
        asInterface.setAccessible(true);
        this.proxy = asInterface.invoke(null, binder);

        this.injectMethod = findInjectMethod(proxy.getClass());
        this.injectMethod.setAccessible(true);
    }

    private static Method findInjectMethod(Class<?> proxyClass) throws NoSuchMethodException {
        for (Method m : proxyClass.getMethods()) {
            if (!m.getName().equals("injectInputEvent")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length >= 2 && "android.view.InputEvent".equals(p[0].getName())) {
                return m;
            }
        }
        throw new NoSuchMethodException("在 " + proxyClass.getName()
                + " 上找不到 injectInputEvent(InputEvent, int)");
    }

    @Override
    public String injectionClass() {
        return Generation.IM_INJECT;
    }

    @Override
    public String describe() {
        return "InputManager 逐点注入（Gen2/Gen3 可用）";
    }

    @Override
    public boolean canPhysiological() {
        return true;
    }

    @Override
    public boolean launch(String pkg) {
        // 启动不需要事件注入，走 shell 即可
        String out = ShellInjector.exec("monkey -p " + pkg
                + " -c android.intent.category.LAUNCHER 1");
        return out != null && !out.contains("No activities found");
    }

    public StrokeSampler.Pt[] tapSample(float x, float y, long downTimeMs, long upTimeMs) {
        float jx = (float) (rng.nextGaussian() * 2.5);
        float jy = (float) (rng.nextGaussian() * 2.5);
        float p0 = StrokeSampler.clamp((float) (0.78 + rng.nextGaussian() * 0.08), 0.35f, 1.0f);
        float p1 = StrokeSampler.clamp(p0 - Math.abs((float) (rng.nextGaussian() * 0.12)), 0.10f, 1.0f);
        float s0 = Math.max(0.02f, (float) (0.22 + rng.nextGaussian() * 0.04));
        float s1 = Math.max(0.01f, s0 * (0.5f + rng.nextFloat() * 0.35f));
        return new StrokeSampler.Pt[]{
                new StrokeSampler.Pt(0, x, y, p0, s0),
                new StrokeSampler.Pt(upTimeMs - downTimeMs, x + jx, y + jy, p1, s1)
        };
    }

    public List<StrokeSampler.Pt> swipeSample(float x0, float y0, float x1, float y1,
                                              long durationMs, boolean physiological) {
        if (physiological) {
            return StrokeSampler.physiological(x0, y0, x1, y1, durationMs, rng);
        }
        return StrokeSampler.linear(x0, y0, x1, y1, durationMs);
    }

    @Override
    public void tap(float x, float y, long downTimeMs, long upTimeMs) {
        StrokeSampler.Pt[] p = tapSample(x, y, downTimeMs, upTimeMs);
        inject(downTimeMs, downTimeMs, android.view.MotionEvent.ACTION_DOWN,
                p[0].x, p[0].y, p[0].pressure, p[0].size);
        inject(downTimeMs, upTimeMs, android.view.MotionEvent.ACTION_UP,
                p[1].x, p[1].y, p[1].pressure, p[1].size);
    }

    @Override
    public void swipe(float x0, float y0, float x1, float y1, long durationMs, boolean physiological) {
        long t0 = android.os.SystemClock.uptimeMillis();
        List<StrokeSampler.Pt> pts = swipeSample(x0, y0, x1, y1, durationMs, physiological);
        for (int i = 0; i < pts.size(); i++) {
            StrokeSampler.Pt s = pts.get(i);
            int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                    : (i == pts.size() - 1 ? android.view.MotionEvent.ACTION_UP
                    : android.view.MotionEvent.ACTION_MOVE);
            inject(t0, t0 + s.dt, action, s.x, s.y, s.pressure, s.size);
        }
    }

    private void inject(long downTime, long eventTime, int action,
                        float x, float y, float pressure, float size) {
        try {
            android.view.MotionEvent ev = obtain(downTime, eventTime, action, x, y, pressure, size);
            if (ev == null) {
                throw new IllegalStateException("MotionEvent.obtain 反射返回 null");
            }
            injectMethod.invoke(proxy, ev, MODE_ASYNC, (Object) null);
            ev.recycle();
        } catch (Throwable t) {
            throw new RuntimeException("injectInputEvent 失败: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    private static android.view.MotionEvent obtain(long downTime, long eventTime, int action,
                                                   float x, float y, float pressure, float size)
            throws Exception {
        Method target = null;
        for (Method m : android.view.MotionEvent.class.getDeclaredMethods()) {
            if (!m.getName().equals("obtain")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 13) continue;
            if (p[0] != long.class || p[1] != long.class || p[2] != int.class) continue;
            if (p[3] != float.class || p[4] != float.class) continue;
            if (p[5] != float.class || p[6] != float.class) continue;
            if (p[9] != int.class || p[10] != int.class) continue;
            target = m;
            break;
        }
        if (target == null) {
            throw new NoSuchMethodException(
                    "找不到 MotionEvent.obtain 的 13 参数重载；请打印 describeObtainOverloads() 手工匹配");
        }
        target.setAccessible(true);
        Object ev = target.invoke(null,
                downTime, eventTime, action,
                x, y, pressure, size,
                1.0f, 1.0f,   // xPrecision, yPrecision
                0, 0,         // deviceId, edgeFlags
                1, 0);        // pointerCount, metaState
        return (android.view.MotionEvent) ev;
    }

    /** Diagnostic: list the obtain overloads so a mismatch can be matched by hand. */
    public static List<String> describeObtainOverloads() {
        List<String> out = new java.util.ArrayList<>();
        for (Method m : android.view.MotionEvent.class.getDeclaredMethods()) {
            if (!m.getName().equals("obtain")) continue;
            StringBuilder sb = new StringBuilder("obtain(");
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(p[i].getSimpleName());
            }
            sb.append(")");
            out.add(sb.toString());
        }
        return out;
    }
}
