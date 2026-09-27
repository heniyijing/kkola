package com.milai.redteam;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

import rikka.shizuku.Shizuku;

/**
 * Synchronous client for the privileged shell UserService.
 *
 * Shizuku runs a separate process as the shell UID; we hand it a command string
 * and read back (exitCode, output). This mirrors the standard Shizuku
 * UserService pattern.
 *
 * RISK NOTE (未验证): the transaction codes and the UserServiceArgs version
 * must line up with the Shizuku version on the device and with
 * ShellUserService's implementation. If the shell path fails on your device,
 * check these two first -- everything else here is ordinary binder plumbing.
 */
public final class ShizukuShell {

    /** Transaction code for "exec a shell command"; must match ShellUserService. */
    public static final int TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final Object LOCK = new Object();
    private static volatile IBinder binder;
    private static volatile boolean binding;

    private static final Shizuku.UserServiceArgs ARGS =
            new Shizuku.UserServiceArgs(new ComponentName(
                    "com.milai.redteam", ShellUserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("shell")
                    .debuggable(false)
                    .version(1);

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                binder = service;
                binding = false;
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                binder = null;
                binding = false;
                LOCK.notifyAll();
            }
        }
    };

    private ShizukuShell() {}

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return binderAlive()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void requestPermission(int requestCode) {
        try {
            if (!binderAlive()) return;
            if (Shizuku.isPreV11()) {
                Shizuku.requestPermission(requestCode);
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void prepare() {
        if (!hasPermission()) return;
        IBinder cur = binder;
        if (cur != null && cur.pingBinder()) return;
        synchronized (LOCK) {
            cur = binder;
            if (cur != null && cur.pingBinder()) return;
            if (binding) return;
            binding = true;
            try {
                Shizuku.bindUserService(ARGS, CONNECTION);
            } catch (Throwable e) {
                binding = false;
                LOCK.notifyAll();
            }
        }
    }

    private static IBinder await(long timeoutMs) {
        prepare();
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (true) {
                IBinder cur = binder;
                if (cur != null && cur.pingBinder()) return cur;
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) return null;
                try {
                    LOCK.wait(Math.min(remain, 250));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** Execute one shell command. Returns stdout (or an "ERROR: ..." string). */
    public static String exec(String command) {
        if (!hasPermission()) {
            return "ERROR: Shizuku 未授权或未运行";
        }
        IBinder b = await(6000);
        if (b == null) {
            return "ERROR: Shizuku UserService 连接超时";
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(command);
            if (!b.transact(TRANSACTION_EXEC, data, reply, 0)) {
                return "ERROR: transact 失败";
            }
            reply.readException();
            int code = reply.readInt();
            String out = reply.readString();
            return out == null ? "" : out;
        } catch (Throwable e) {
            synchronized (LOCK) {
                binder = null;
                binding = false;
            }
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
