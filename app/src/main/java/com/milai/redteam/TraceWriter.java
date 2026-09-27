package com.milai.redteam;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes one session per line in the trace format the desktop scorer reads
 * (see detect-test/trace_format.py in the lab repo).
 *
 * Events are recorded with a virtual timeline so the export does not depend on
 * how long injection actually took on the device. That keeps the duration
 * statistics (H4/H5, no-reward ratio) faithful to the plan rather than to
 * scheduler jitter.
 */
public final class TraceWriter implements AutoCloseable {

    private final BufferedWriter out;
    private final String traceId;
    private final String gen;
    private final String source;
    private final String accountId;
    private final long startAtWallMs;
    private final long t0Uptime;

    private final JSONArray events = new JSONArray();
    private final List<long[]> rewardIntervals = new ArrayList<>();

    private long rewardOpenAt = -1;
    private String currentScreen = "launch";

    public TraceWriter(File outFile, String traceId, String gen, String source,
                       String accountId, long t0Uptime, long startAtWallMs) throws Exception {
        this.out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outFile, true), StandardCharsets.UTF_8));
        this.traceId = traceId;
        this.gen = gen;
        this.source = source;
        this.accountId = accountId;
        this.t0Uptime = t0Uptime;
        this.startAtWallMs = startAtWallMs;
    }

    private long rel(long uptimeMs) {
        return Math.max(0, uptimeMs - t0Uptime);
    }

    public void screen(long uptimeMs, String name, boolean rewardPath) {
        currentScreen = name;
        JSONObject e = new JSONObject();
        try {
            e.put("t", rel(uptimeMs));
            e.put("type", "screen");
            e.put("name", name);
            e.put("reward_path", rewardPath);
            events.put(e);

            // maintain reward-path intervals
            if (rewardPath && rewardOpenAt < 0) {
                rewardOpenAt = rel(uptimeMs);
            } else if (!rewardPath && rewardOpenAt >= 0) {
                rewardIntervals.add(new long[]{rewardOpenAt, rel(uptimeMs)});
                rewardOpenAt = -1;
            }
        } catch (Exception ignored) {
        }
    }

    public void idle(long uptimeMs, long ms, String reason) {
        JSONObject e = new JSONObject();
        try {
            e.put("t", rel(uptimeMs));
            e.put("type", "idle");
            e.put("ms", ms);
            e.put("reason", reason);
            events.put(e);
        } catch (Exception ignored) {
        }
    }

    public void touch(long uptimeMs, String action, float x, float y,
                      float pressure, float size) {
        JSONObject e = new JSONObject();
        try {
            e.put("t", rel(uptimeMs));
            e.put("type", "touch");
            e.put("action", action);
            e.put("x", round(x));
            e.put("y", round(y));
            e.put("pressure", round(pressure));
            e.put("size", round(size));
            events.put(e);
        } catch (Exception ignored) {
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Close the reward interval if a session ends mid-reward-path. */
    public void close(long endUptimeMs) {
        if (rewardOpenAt >= 0) {
            rewardIntervals.add(new long[]{rewardOpenAt, rel(endUptimeMs)});
            rewardOpenAt = -1;
        }
    }

    /** Serialize and append one line. */
    public void commit(long endUptimeMs) {
        close(endUptimeMs);
        JSONObject root = new JSONObject();
        try {
            root.put("trace_id", traceId);
            root.put("gen", gen);
            root.put("label", "adversary");
            root.put("source", source);

            JSONObject device = new JSONObject();
            device.put("model", android.os.Build.MODEL);
            device.put("pressure_constant", "gen1".equals(gen));
            root.put("device", device);

            JSONObject session = new JSONObject();
            session.put("account_id", accountId);
            session.put("start_at", startAtWallMs);
            JSONArray iv = new JSONArray();
            for (long[] r : rewardIntervals) {
                JSONArray p = new JSONArray();
                p.put(r[0]);
                p.put(r[1]);
                iv.put(p);
            }
            session.put("reward_path_intervals", iv);
            root.put("session", session);

            root.put("events", events);
            out.write(root.toString());
            out.write('\n');
            out.flush();
        } catch (Exception e) {
            AppState.log("trace 写出失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (Exception ignored) {
        }
    }
}
