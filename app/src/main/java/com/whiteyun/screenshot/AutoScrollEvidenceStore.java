package com.whiteyun.screenshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

final class AutoScrollEvidenceStore {
    private static final String ROOT_DIR = "auto-scroll-evidence";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final int MAX_SESSIONS = 3;
    private static final int MAX_EVENTS = 512;
    private static final int MAX_WINDOWS = 32;
    private static final int FLUSH_EVERY_MUTATIONS = 8;
    // ponytail: event evidence stays on by default; raw PNG capture is a local diagnostic switch because it dominates auto-run I/O.
    private static final boolean SAVE_RAW_FRAMES = false;

    private AutoScrollEvidenceStore() {
    }

    static Session start(
            Context context,
            int displayWidth,
            int displayHeight,
            int densityDpi,
            int maxFrames) {
        File root = new File(context.getFilesDir(), ROOT_DIR);
        if (!root.isDirectory() && !root.mkdirs()) {
            return Session.disabled();
        }
        String sessionId = "auto_" + fileTimestamp();
        File dir = new File(root, sessionId);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return Session.disabled();
        }
        Session session = new Session(dir, sessionId);
        session.begin(displayWidth, displayHeight, densityDpi, maxFrames);
        pruneOldSessions(root);
        return session;
    }

    static EvidenceSummary latestSummary(Context context) {
        File root = new File(context.getFilesDir(), ROOT_DIR);
        File latest = latestSessionDir(root);
        if (latest == null) {
            return null;
        }
        return new EvidenceSummary(latest.getAbsolutePath(), new File(latest, MANIFEST_NAME).getAbsolutePath());
    }

    private static File latestSessionDir(File root) {
        File[] dirs = root.isDirectory() ? root.listFiles(File::isDirectory) : null;
        if (dirs == null || dirs.length == 0) {
            return null;
        }
        Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
        return dirs[0];
    }

    private static void pruneOldSessions(File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= MAX_SESSIONS) {
            return;
        }
        Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());
        // ponytail: keep only the latest local evidence sessions to avoid unbounded private storage growth; add export/retention UI if beta users need longer archives.
        for (int i = MAX_SESSIONS; i < dirs.length; i++) {
            deleteRecursively(dirs[i]);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String fileTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (DigestInputStream input = new DigestInputStream(new java.io.FileInputStream(file), digest)) {
                while (input.read(buffer) != -1) {
                    // DigestInputStream updates the digest as bytes are read.
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    static final class Session {
        private final File dir;
        private final File manifestFile;
        private final String sessionId;
        private final boolean enabled;
        private final JSONObject manifest = new JSONObject();
        private final JSONArray events = new JSONArray();
        private final JSONArray frames = new JSONArray();
        private final JSONArray windows = new JSONArray();
        private boolean closed;
        private int unflushedMutations;

        private Session(File dir, String sessionId) {
            this.dir = dir;
            this.manifestFile = new File(dir, MANIFEST_NAME);
            this.sessionId = sessionId;
            this.enabled = true;
        }

        private Session() {
            this.dir = null;
            this.manifestFile = null;
            this.sessionId = "";
            this.enabled = false;
        }

        static Session disabled() {
            return new Session();
        }

        synchronized boolean isEnabled() {
            return enabled;
        }

        synchronized String manifestPath() {
            return enabled ? manifestFile.getAbsolutePath() : "";
        }

        synchronized void begin(int displayWidth, int displayHeight, int densityDpi, int maxFrames) {
            if (!enabled) {
                return;
            }
            try {
                manifest.put("schema", "whiteyun-auto-scroll-evidence-v1");
                manifest.put("sessionId", sessionId);
                manifest.put("startedAt", now());
                manifest.put("manifestPath", manifestFile.getAbsolutePath());
                manifest.put("device", deviceJson());
                manifest.put("display", displayJson(displayWidth, displayHeight, densityDpi));
                manifest.put("maxFrames", maxFrames);
                manifest.put("rawFramesEnabled", SAVE_RAW_FRAMES);
                manifest.put("frames", frames);
                manifest.put("events", events);
                manifest.put("windows", windows);
                recordEvent("session_start", "auto long screenshot evidence started", 0, 0);
                flush();
            } catch (Exception exception) {
                close("manifest_init_failed: " + exception.getMessage(), false);
            }
        }

        synchronized void recordEvent(String type, String detail, int acceptedFrames, int scrollFailures) {
            if (!enabled || closed) {
                return;
            }
            try {
                JSONObject event = new JSONObject();
                event.put("time", now());
                event.put("type", type);
                event.put("detail", detail == null ? "" : detail);
                event.put("acceptedFrames", acceptedFrames);
                event.put("scrollFailures", scrollFailures);
                events.put(event);
                trim(events, MAX_EVENTS);
                flushIfNeeded();
            } catch (Exception ignored) {
            }
        }

        synchronized void recordWindow(String reason, WindowSnapshot snapshot) {
            if (!enabled || closed) {
                return;
            }
            try {
                JSONObject row = snapshot == null ? WindowSnapshot.unavailable("null snapshot").toJson() : snapshot.toJson();
                row.put("time", now());
                row.put("reason", reason);
                windows.put(row);
                trim(windows, MAX_WINDOWS);
                flushIfNeeded();
            } catch (Exception ignored) {
            }
        }

        synchronized void saveRawFrame(Bitmap bitmap, int attempt) {
            if (!enabled || closed || bitmap == null) {
                return;
            }
            if (!SAVE_RAW_FRAMES) {
                return;
            }
            File file = new File(dir, String.format(Locale.US, "frame_%03d_raw.png", attempt));
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("PNG write failed");
                }
            } catch (IOException exception) {
                recordEvent("raw_frame_write_failed", exception.getMessage(), 0, 0);
                return;
            }

            try {
                JSONObject frame = new JSONObject();
                frame.put("time", now());
                frame.put("attempt", attempt);
                frame.put("file", file.getAbsolutePath());
                frame.put("width", bitmap.getWidth());
                frame.put("height", bitmap.getHeight());
                frame.put("bytes", file.length());
                frame.put("sha256", sha256(file));
                frames.put(frame);
                flushIfNeeded();
            } catch (Exception ignored) {
            }
        }

        synchronized void recordFrameResult(
                int attempt,
                boolean accepted,
                boolean duplicate,
                long croppedFingerprint,
                int croppedWidth,
                int croppedHeight,
                int acceptedFrames,
                int scrollFailures) {
            if (!enabled || closed) {
                return;
            }
            try {
                JSONObject event = new JSONObject();
                event.put("time", now());
                event.put("type", "frame_result");
                event.put("attempt", attempt);
                event.put("accepted", accepted);
                event.put("duplicate", duplicate);
                event.put("croppedFingerprint", Long.toHexString(croppedFingerprint));
                event.put("croppedWidth", croppedWidth);
                event.put("croppedHeight", croppedHeight);
                event.put("acceptedFrames", acceptedFrames);
                event.put("scrollFailures", scrollFailures);
                events.put(event);
                trim(events, MAX_EVENTS);
                flushIfNeeded();
            } catch (Exception ignored) {
            }
        }

        synchronized void close(String reason, boolean success) {
            if (!enabled || closed) {
                return;
            }
            closed = true;
            try {
                manifest.put("endedAt", now());
                manifest.put("success", success);
                manifest.put("endReason", reason == null ? "" : reason);
                flush();
            } catch (Exception ignored) {
            }
        }

        private void flush() throws IOException, JSONException {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(manifestFile),
                    StandardCharsets.UTF_8)) {
                writer.write(manifest.toString(2));
                writer.write('\n');
            }
            unflushedMutations = 0;
        }

        private void flushIfNeeded() throws IOException, JSONException {
            unflushedMutations++;
            // ponytail: evidence is diagnostic, so batch metadata writes; export/live streaming is the
            // upgrade if every single event must survive an abrupt power loss.
            if (unflushedMutations >= FLUSH_EVERY_MUTATIONS) {
                flush();
            }
        }

        private static void trim(JSONArray values, int maxItems) {
            while (values.length() > maxItems) {
                values.remove(0);
            }
        }

        private JSONObject deviceJson() throws JSONException {
            JSONObject device = new JSONObject();
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("device", Build.DEVICE);
            return device;
        }

        private JSONObject displayJson(int width, int height, int densityDpi) throws JSONException {
            JSONObject display = new JSONObject();
            display.put("width", width);
            display.put("height", height);
            display.put("densityDpi", densityDpi);
            return display;
        }
    }

    static final class WindowSnapshot {
        final boolean available;
        final String error;
        final String packageName;
        final String className;
        final String rootBounds;
        final int nodeCount;
        final int scrollableNodeCount;
        final String firstScrollableClass;
        final String firstScrollableBounds;
        final boolean traversalTruncated;
        final String latestEventType;
        final String latestEventPackage;
        final String latestEventClass;

        WindowSnapshot(
                boolean available,
                String error,
                String packageName,
                String className,
                String rootBounds,
                int nodeCount,
                int scrollableNodeCount,
                String firstScrollableClass,
                String firstScrollableBounds,
                boolean traversalTruncated,
                String latestEventType,
                String latestEventPackage,
                String latestEventClass) {
            this.available = available;
            this.error = error;
            this.packageName = packageName;
            this.className = className;
            this.rootBounds = rootBounds;
            this.nodeCount = nodeCount;
            this.scrollableNodeCount = scrollableNodeCount;
            this.firstScrollableClass = firstScrollableClass;
            this.firstScrollableBounds = firstScrollableBounds;
            this.traversalTruncated = traversalTruncated;
            this.latestEventType = latestEventType;
            this.latestEventPackage = latestEventPackage;
            this.latestEventClass = latestEventClass;
        }

        static WindowSnapshot unavailable(String error) {
            return new WindowSnapshot(
                    false,
                    error == null ? "" : error,
                    "",
                    "",
                    "",
                    0,
                    0,
                    "",
                    "",
                    false,
                    "",
                    "",
                    "");
        }

        JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("available", available);
            object.put("error", error);
            object.put("packageName", packageName);
            object.put("className", className);
            object.put("rootBounds", rootBounds);
            object.put("nodeCount", nodeCount);
            object.put("scrollableNodeCount", scrollableNodeCount);
            object.put("firstScrollableClass", firstScrollableClass);
            object.put("firstScrollableBounds", firstScrollableBounds);
            object.put("traversalTruncated", traversalTruncated);
            object.put("latestEventType", latestEventType);
            object.put("latestEventPackage", latestEventPackage);
            object.put("latestEventClass", latestEventClass);
            return object;
        }
    }

    static final class WindowStats {
        int nodeCount;
        int scrollableNodeCount;
        boolean traversalTruncated;
        String firstScrollableClass = "";
        String firstScrollableBounds = "";
    }

    static final class LatestEvent {
        final String type;
        final String packageName;
        final String className;

        LatestEvent(String type, String packageName, String className) {
            this.type = type;
            this.packageName = packageName;
            this.className = className;
        }
    }

    static final class EvidenceSummary {
        final String dirPath;
        final String manifestPath;

        EvidenceSummary(String dirPath, String manifestPath) {
            this.dirPath = dirPath;
            this.manifestPath = manifestPath;
        }
    }

    static String rectToString(Rect rect) {
        return rect == null ? "" : rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom;
    }
}
