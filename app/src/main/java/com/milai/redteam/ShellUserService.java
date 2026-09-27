package com.milai.redteam;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.SystemServiceHelper;

/**
 * Privileged shell worker. Runs in a separate process under the shell UID via
 * Shizuku's UserService mechanism, and executes one command per transaction.
 *
 * The transaction code must match {@link ShizukuShell#TRANSACTION_EXEC}.
 */
public final class ShellUserService extends android.app.Service {

    private final IBinder binder = new Binder();

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return binder;
    }

    private static final class Binder extends android.os.Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != ShizukuShell.TRANSACTION_EXEC) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface("com.milai.redteam.ShellUserService");
            String command = data.readString();
            int exit = -1;
            String output;
            try {
                Process p = new ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                exit = p.waitFor();
                output = sb.toString();
            } catch (Throwable e) {
                output = "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            reply.writeNoException();
            reply.writeInt(exit);
            reply.writeString(output);
            return true;
        }
    }

    /** Kept for callers that need a system service handle from the shell side. */
    @SuppressWarnings("unused")
    static IBinder inputService() {
        return SystemServiceHelper.getSystemService("input");
    }
}
