package com.milai.redteam;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

import rikka.shizuku.Shizuku;

/** Operator console: pick target + generation, start a run, watch the log. */
public final class MainActivity extends Activity {

    private static final int SHIZUKU_REQUEST = 0x51;

    private Spinner genSpinner;
    private EditText pkgInput;
    private EditText accountInput;
    private EditText sessionsInput;
    private CheckBox dryRunCheck;
    private TextView statusView;
    private TextView logView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLogVersion = -1;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                AppState.log("Shizuku 授权结果: " + (ok ? "已授权" : "被拒绝"));
                refresh();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable e) {
            AppState.log("Shizuku 不可用: " + e.getMessage());
        }

        AppState.log("允许清单: " + SafetyGate.allowed());
        AppState.log("禁用清单: " + SafetyGate.forbidden());
        checkShizuku();
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("红队靶场 —— 检测对抗模拟");
        title.setTextSize(20);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只对 SafetyGate 允许清单内的自有一方应用运行。"
                + "Gen1 走 shell 注入；Gen2/Gen3 需 InputManager 注入；"
                + "Gen4/Gen5 单机只能出计划与 trace。");
        sub.setTextSize(12);
        sub.setPadding(0, pad / 2, 0, pad);
        root.addView(sub);

        root.addView(label("目标包名"));
        pkgInput = new EditText(this);
        pkgInput.setHint("com.example.yourapp");
        root.addView(pkgInput);

        root.addView(label("账号标识（写进 trace 的 account_id）"));
        accountInput = new EditText(this);
        accountInput.setHint("lab-account-01");
        root.addView(accountInput);

        root.addView(label("代数"));
        genSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, LabConfig.generations());
        genSpinner.setAdapter(ad);
        root.addView(genSpinner);

        root.addView(label("轮数"));
        sessionsInput = new EditText(this);
        sessionsInput.setText("1");
        sessionsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(sessionsInput);

        dryRunCheck = new CheckBox(this);
        dryRunCheck.setText("dry-run（不注入，只生成 trace）");
        dryRunCheck.setChecked(true);
        root.addView(dryRunCheck);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, pad / 2, 0, pad / 2);

        Button authBtn = new Button(this);
        authBtn.setText("授权 Shizuku");
        authBtn.setOnClickListener(v -> requestShizuku());
        buttons.addView(authBtn);

        Button startBtn = new Button(this);
        startBtn.setText("开始");
        startBtn.setOnClickListener(v -> startRun());
        buttons.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, ScenarioService.class)
                    .setAction(ScenarioService.ACTION_STOP);
            startService(i);
        });
        buttons.addView(stopBtn);
        root.addView(buttons);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(statusView);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setGravity(Gravity.START);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    // ---------------------------------------------------------------- logic

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                AppState.log("Shizuku 未运行。请先启动 Shizuku（无线调试或 root）。");
                return;
            }
            if (Shizuku.isPreV11()) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AppState.log("Shizuku 已授权");
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            }
        } catch (Throwable e) {
            AppState.log("请求授权失败: " + e.getMessage());
        }
    }

    private void checkShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                AppState.log("Shizuku 状态: 未运行（Gen1/Gen2/Gen3 需要它）");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AppState.log("Shizuku 状态: 已授权");
            } else {
                AppState.log("Shizuku 状态: 运行中但未授权");
            }
        } catch (Throwable e) {
            AppState.log("Shizuku 状态: 不可用");
        }
    }

    private void startRun() {
        String pkg = pkgInput.getText().toString().trim();
        String gen = (String) genSpinner.getSelectedItem();
        String account = accountInput.getText().toString().trim();
        boolean dry = dryRunCheck.isChecked();

        try {
            SafetyGate.requireAllowed(pkg);
        } catch (SafetyGate.Refused e) {
            AppState.log("拒绝: " + e.getMessage());
            AppState.status = "已拒绝";
            return;
        }

        int sessions;
        try {
            sessions = Math.max(1, Integer.parseInt(sessionsInput.getText().toString().trim()));
        } catch (Throwable e) {
            sessions = 1;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x52);
        }

        Intent i = new Intent(this, ScenarioService.class)
                .setAction(ScenarioService.ACTION_START)
                .putExtra(ScenarioService.EXTRA_PACKAGE, pkg)
                .putExtra(ScenarioService.EXTRA_GEN, gen)
                .putExtra(ScenarioService.EXTRA_SESSIONS, sessions)
                .putExtra(ScenarioService.EXTRA_ACCOUNT, account)
                .putExtra(ScenarioService.EXTRA_DRY_RUN, dry);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        AppState.log("已下发任务: " + pkg + " / " + gen + " / " + sessions + " 轮"
                + (dry ? " / dry-run" : ""));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    private void refresh() {
        statusView.setText(AppState.displayStatus());
        long v = AppState.logsVersion();
        if (v != lastLogVersion) {
            lastLogVersion = v;
            logView.setText(AppState.logs());
        }
    }

    /** Convenience for the operator: show what the gate would decide. */
    @SuppressWarnings("unused")
    private void explainGate() {
        List<String> allowed = new java.util.ArrayList<>(SafetyGate.allowed());
        AppState.log("允许: " + allowed);
    }
}
