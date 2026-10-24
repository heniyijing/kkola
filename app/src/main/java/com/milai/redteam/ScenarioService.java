package com.milai.redteam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.List;

/**
 * Foreground service that drives one scenario suite.
 *
 * Runs off the main thread; the notification carries a stop action so a run can
 * always be halted without touching the UI.
 */
public final class ScenarioService extends Service {

    public static final String ACTION_START = "com.milai.redteam.START";
    public static final String ACTION_STOP = "com.milai.redteam.STOP";

    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_GEN = "gen";
    public static final String EXTRA_SESSIONS = "sessions";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_DRY_RUN = "dry_run";
    /** 快捷场景：coin=去赚金币 / energy=去赚能量（见 ScenePlanner.SCENE_*） */
    public static final String EXTRA_SCENE = "scene";

    private static final String CHANNEL_ID = "redteam";
    private static final int NOTIFICATION_ID = 4711;

    private volatile SceneRunner runner;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopRun();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        String genName = intent.getStringExtra(EXTRA_GEN);
        int sessions = intent.getIntExtra(EXTRA_SESSIONS, 1);
        String account = intent.getStringExtra(EXTRA_ACCOUNT);
        boolean dryRun = intent.getBooleanExtra(EXTRA_DRY_RUN, false);
        String scene = intent.getStringExtra(EXTRA_SCENE);

        startForeground(NOTIFICATION_ID, buildNotification("准备中…"));

        // Safety gate first: nothing touches a device before this passes.
        try {
            SafetyGate.requireAllowed(pkg);
        } catch (SafetyGate.Refused e) {
            AppState.log("拒绝启动: " + e.getMessage());
            AppState.status = "已拒绝（目标不在允许清单）";
            stopSelf();
            return START_NOT_STICKY;
        }

        if (runner != null) {
            AppState.log("已有场景在运行，忽略新的启动请求");
            return START_NOT_STICKY;
        }

        final Generation gen;
        try {
            gen = Generation.of(genName);
        } catch (Throwable e) {
            AppState.log("代数无效: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        worker = new Thread(() -> runSuite(pkg, gen, sessions, account, dryRun, scene),
                "redteam-runner");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runSuite(String pkg, Generation gen, int sessions, String account,
                          boolean dryRun, String scene) {
        AppState.running = true;
        AppState.log("目标 " + pkg + "，代数 " + gen.name
                + "，注入类别 " + gen.injectionClass
                + (dryRun ? "（dry-run 不注入）" : ""));

        InputInjector injector = null;
        try {
            if (dryRun) {
                injector = new DryRunInjector(gen, 20260227);
            } else if (Generation.SHELL_INPUT.equals(gen.injectionClass)) {
                if (!ShizukuShell.hasPermission()) {
                    throw new IllegalStateException("Shizuku 未授权，无法注入。请先授权后重试。");
                }
                injector = new ShellInjector();
            } else if (Generation.IM_INJECT.equals(gen.injectionClass)) {
                if (!ShizukuShell.hasPermission()) {
                    throw new IllegalStateException("Shizuku 未授权，无法注入。请先授权后重试。");
                }
                injector = new InputManagerInjector();
            } else {
                throw new IllegalStateException(
                        "代数 " + gen.name + " 需要注入类别 " + gen.injectionClass
                                + "，本机当前不具备（需要物理设备/农场或协议端）。"
                                + "用 dry-run 只出 trace，或换一个代数。");
            }
        } catch (Throwable e) {
            AppState.log("注入器初始化失败: " + e.getMessage());
            AppState.log("提示: " + hintFor(gen));
            AppState.running = false;
            AppState.status = "注入器不可用";
            stopSelf();
            return;
        }

        try {
            AppState.log(injector.describe());
            if (!injector.launch(pkg)) {
                AppState.log("警告: 目标应用可能未启动，检查包名是否正确");
            }
            SystemClock.sleep(1500);

            LabConfig cfg = new LabConfig(this, pkg);
            AppState.log("设备分辨率 " + cfg.screenW + "x" + cfg.screenH
                    + "（锚点按 1080x2400 基准自动缩放）");

            for (int i = 0; i < sessions; i++) {
                if (Thread.currentThread().isInterrupted()) break;

                List<SceneStep> steps;
                if (ScenePlanner.SCENE_COIN.equals(scene)
                        || ScenePlanner.SCENE_ENERGY.equals(scene)) {
                    steps = ScenePlanner.buildTaobaoScene(gen, cfg, 20260227 + i * 7919, scene);
                } else {
                    steps = ScenePlanner.build(gen, cfg, 20260227 + i * 7919);
                }
                SceneRunner r = new SceneRunner(this, cfg, gen, injector, steps,
                        account == null || account.isEmpty() ? "lab-account" : account,
                        true);
                runner = r;

                AppState.status = String.format("%s 第 %d/%d 轮（%d 步）",
                        gen.name, i + 1, sessions, steps.size());
                AppState.log("=== " + AppState.status + " ===");

                int executed = r.run(i);
                AppState.log("本轮执行 " + executed + "/" + steps.size() + " 步");
                runner = null;

                if (i < sessions - 1) {
                    SystemClock.sleep(2500);
                }
            }
            AppState.status = "完成";
        } catch (Throwable e) {
            AppState.log("运行失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            AppState.status = "失败";
        } finally {
            AppState.running = false;
            runner = null;
            stopSelf();
        }
    }

    private static String hintFor(Generation gen) {
        switch (gen.injectionClass) {
            case Generation.IM_INJECT:
                return "Gen2/Gen3 需要 InputManager 级注入；若反射被 hidden API 策略拦截，"
                        + "见 InputManagerInjector 顶部注释。";
            case Generation.PHYSICAL:
                return "Gen4 需要真实设备农场或多开环境，单机只能覆盖图谱侧。";
            case Generation.PROTOCOL:
                return "Gen5 不走 UI，需要协议端直接打接口，本 App 只记录标记。";
            default:
                return "检查 Shizuku 是否运行并已授权本应用。";
        }
    }

    private void stopRun() {
        SceneRunner r = runner;
        if (r != null) {
            r.requestStop();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
        AppState.running = false;
        AppState.status = "已停止";
        AppState.log("用户请求停止");
    }

    @Override
    public void onDestroy() {
        stopRun();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notify

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "红队靶场",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScenarioService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("红队靶场运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .setOngoing(true)
                .build();
    }
}
