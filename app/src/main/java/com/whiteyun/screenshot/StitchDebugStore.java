package com.whiteyun.screenshot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class StitchDebugStore {
    private static final String ROOT_DIR = "stitch-debug";
    private static final String MANIFEST_NAME = "manifest.json";
    private static final int MAX_SESSIONS = 5;
    // ponytail: release records paths/plan only; flip this for local diagnostics when full pixel copies are worth the I/O.
    private static final boolean WRITE_PIXEL_COPIES = false;

    private StitchDebugStore() {
    }

    static File write(
            Context context,
            String mode,
            List<Bitmap> frames,
            LongScreenshotStitcher.StitchPlan plan,
            File preview,
            List<String> sourceFiles) throws IOException {
        File root = new File(context.getFilesDir(), ROOT_DIR);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Cannot create stitch debug root");
        }
        File dir = new File(root, "stitch_" + timestamp());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create stitch debug dir");
        }

        try {
            boolean writePixelCopies = WRITE_PIXEL_COPIES && frames != null;
            JSONObject manifest = new JSONObject();
            manifest.put("schema", "whiteyun-stitch-debug-v1");
            manifest.put("mode", mode == null ? "" : mode);
            manifest.put("createdAt", now());
            manifest.put("directory", dir.getAbsolutePath());
            manifest.put("pixelCopiesWritten", writePixelCopies);
            manifest.put("previewFile", writePixelCopies
                    ? copyPreview(preview, dir).getAbsolutePath()
                    : preview.getAbsolutePath());
            manifest.put("sourceFiles", stringArray(sourceFiles));
            manifest.put("frames", writePixelCopies
                    ? writeFrames(frames, dir)
                    : frameReferences(frames, sourceFiles));
            manifest.put("plan", planJson(plan));
            writeJson(new File(dir, MANIFEST_NAME), manifest);
            pruneOldSessions(root);
        } catch (Exception exception) {
            throw new IOException("Cannot write stitch debug manifest", exception);
        }
        return dir;
    }

    static StitchDebugSummary latestSummary(Context context) {
        File latest = latestSessionDir(new File(context.getFilesDir(), ROOT_DIR));
        if (latest == null) {
            return null;
        }
        return new StitchDebugSummary(latest.getAbsolutePath(), new File(latest, MANIFEST_NAME).getAbsolutePath());
    }

    private static JSONArray writeFrames(List<Bitmap> frames, File dir) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < frames.size(); i++) {
            Bitmap bitmap = frames.get(i);
            File file = new File(dir, String.format(Locale.US, "frame_%03d.png", i + 1));
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("PNG write failed");
                }
            }
            JSONObject row = new JSONObject();
            row.put("index", i);
            row.put("file", file.getAbsolutePath());
            row.put("width", bitmap.getWidth());
            row.put("height", bitmap.getHeight());
            row.put("bytes", file.length());
            rows.put(row);
        }
        return rows;
    }

    private static JSONArray frameReferences(List<Bitmap> frames, List<String> sourceFiles) throws Exception {
        JSONArray rows = new JSONArray();
        int count = Math.max(
                frames == null ? 0 : frames.size(),
                sourceFiles == null ? 0 : sourceFiles.size());
        for (int i = 0; i < count; i++) {
            Bitmap bitmap = frames != null && i < frames.size() ? frames.get(i) : null;
            String path = sourceFiles != null && i < sourceFiles.size() ? sourceFiles.get(i) : "";
            File file = path == null || path.isEmpty() ? null : new File(path);
            int width = bitmap == null ? 0 : bitmap.getWidth();
            int height = bitmap == null ? 0 : bitmap.getHeight();
            if (bitmap == null && file != null && file.isFile()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                width = Math.max(0, bounds.outWidth);
                height = Math.max(0, bounds.outHeight);
            }
            JSONObject row = new JSONObject();
            row.put("index", i);
            row.put("file", path == null ? "" : path);
            row.put("copied", false);
            row.put("width", width);
            row.put("height", height);
            row.put("bytes", file != null && file.isFile() ? file.length() : 0);
            rows.put(row);
        }
        return rows;
    }

    private static File copyPreview(File preview, File dir) throws IOException {
        File output = new File(dir, "preview.png");
        try (FileInputStream input = new FileInputStream(preview);
             FileOutputStream fileOutput = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileOutput.write(buffer, 0, read);
            }
        }
        return output;
    }

    private static JSONObject planJson(LongScreenshotStitcher.StitchPlan plan) throws Exception {
        JSONObject object = new JSONObject();
        JSONArray seams = new JSONArray();
        if (plan != null) {
            for (int i = 0; i < plan.overlaps.length; i++) {
                JSONObject seam = new JSONObject();
                seam.put("index", i);
                seam.put("overlap", plan.overlaps[i]);
                seam.put("score", valueAt(plan.scores, i));
                seam.put("seamScore", valueAt(plan.seamScores, i));
                seam.put("consensusScore", valueAt(plan.consensusScores, i));
                seam.put("maxOverlap", valueAt(plan.maxOverlaps, i));
                seam.put("manualRequired", boolAt(plan.manualRequired, i));
                seam.put("noMovement", boolAt(plan.noMovement, i));
                seam.put("message", stringAt(plan.seamMessages, i));
                seam.put("scrollDelta", valueAt(plan.scrollDeltas, i));
                seam.put("expectedOverlap", valueAtOr(plan.expectedOverlaps, i, -1));
                seam.put("contentRect", rectString(rectAt(plan.contentRects, i)));
                seam.put("matchRect", rectString(rectAt(plan.matchRects, i)));
                seams.put(seam);
            }
        }
        object.put("seams", seams);
        return object;
    }

    private static JSONArray stringArray(List<String> values) {
        JSONArray rows = new JSONArray();
        if (values == null) {
            return rows;
        }
        for (String value : values) {
            rows.put(value == null ? "" : value);
        }
        return rows;
    }

    private static int valueAt(int[] values, int index) {
        return values == null || index < 0 || index >= values.length ? 0 : values[index];
    }

    private static int valueAtOr(int[] values, int index, int fallback) {
        return values == null || index < 0 || index >= values.length ? fallback : values[index];
    }

    private static boolean boolAt(boolean[] values, int index) {
        return values != null && index >= 0 && index < values.length && values[index];
    }

    private static String stringAt(String[] values, int index) {
        return values == null || index < 0 || index >= values.length || values[index] == null ? "" : values[index];
    }

    private static Rect rectAt(Rect[] rects, int index) {
        return rects == null || index < 0 || index >= rects.length ? null : rects[index];
    }

    private static String rectString(Rect rect) {
        return rect == null ? "" : rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom;
    }

    private static void writeJson(File file, JSONObject object) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            writer.write(object.toString(2));
            writer.write('\n');
        }
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
        // ponytail: developer stitch evidence is intentionally bounded; export before running large regression batches.
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

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date());
    }

    static final class StitchDebugSummary {
        final String dirPath;
        final String manifestPath;

        StitchDebugSummary(String dirPath, String manifestPath) {
            this.dirPath = dirPath;
            this.manifestPath = manifestPath;
        }
    }
}
