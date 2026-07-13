package com.whiteyun.screenshot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.util.Log;

import ar.com.hjg.pngj.FilterType;
import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngWriter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StreamingLongScreenshotStitcher {
    private static final String TAG = "WhiteYunStreamingStitch";
    private static final int PNG_COMPRESSION_LEVEL = 3;
    private static final int FRAME_PNG_COMPRESSION_LEVEL = 1;
    private static final int EDGE_INDICATOR_MIN_SCRUB_PX = 6;
    private static final int EDGE_INDICATOR_MAX_SCRUB_PX = 36;
    private static final int EDGE_INDICATOR_CONTRAST = 24;
    private static final int CROP_BLOCK_ROWS = 256;
    private static final int PATH_RECENT_LIMIT = 5;
    private static final int PATH_MIN_SUPPORT = 3;
    private static final int PATH_STABILITY_TOLERANCE = 96;
    private static final int SESSION_PATH_STABILITY_TOLERANCE = 48;
    private static final int SESSION_PATH_MIN_SUPPORT = 3;

    private StreamingLongScreenshotStitcher() {
    }

    interface ProgressCallback {
        void onProgress(int completed, int total);
    }

    static Result stitch(
            List<File> frameFiles,
            int[] scrollDeltas,
            File output,
            ProgressCallback analyzeProgress,
            ProgressCallback writeProgress) throws IOException {
        if (frameFiles == null || frameFiles.isEmpty()) {
            throw new IllegalArgumentException("没有可拼接的采样帧");
        }
        FrameBounds bounds = readBounds(frameFiles);
        SegmentPlan segments = analyze(frameFiles, scrollDeltas, bounds, analyzeProgress);
        File part = new File(output.getAbsolutePath() + ".part");
        part.delete();
        try {
            writePng(frameFiles, segments, bounds.width, part, writeProgress);
            verify(part, bounds.width, segments.totalHeight);
            publish(part, output);
        } catch (IOException | RuntimeException | Error exception) {
            part.delete();
            throw exception;
        }
        return new Result(output, segments.plan, bounds.width, segments.totalHeight);
    }

    static void crop(File source, Rect crop, File output) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("待裁剪长图不存在");
        }
        File part = new File(output.getAbsolutePath() + ".part");
        part.delete();
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(source.getAbsolutePath(), false);
            if (crop == null
                    || crop.left < 0
                    || crop.top < 0
                    || crop.right > decoder.getWidth()
                    || crop.bottom > decoder.getHeight()
                    || crop.isEmpty()) {
                throw new IllegalArgumentException("长图裁剪区域无效");
            }
            writeCroppedPng(decoder, crop, part);
            verify(part, crop.width(), crop.height());
            publish(part, output);
        } catch (IOException | RuntimeException | Error exception) {
            part.delete();
            throw exception;
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    static void writeFramePng(Bitmap bitmap, File output) throws IOException {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("待写入帧无效");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create frame dir");
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ImageInfo imageInfo = new ImageInfo(width, height, 8, true);
        PngWriter writer = new PngWriter(output, imageInfo, true);
        writer.setCompLevel(FRAME_PNG_COMPRESSION_LEVEL);
        writer.setFilterType(FilterType.FILTER_ADAPTIVE_FAST);
        ImageLineInt line = new ImageLineInt(imageInfo);
        int blockRows = Math.min(CROP_BLOCK_ROWS, height);
        int[] argb = new int[width * blockRows];
        int[] rgba = line.getScanline();
        try {
            for (int top = 0; top < height; top += blockRows) {
                int rows = Math.min(blockRows, height - top);
                bitmap.getPixels(argb, 0, width, 0, top, width, rows);
                for (int row = 0; row < rows; row++) {
                    int source = row * width;
                    for (int x = 0, sample = 0; x < width; x++, source++, sample += 4) {
                        int color = argb[source];
                        rgba[sample] = (color >>> 16) & 0xff;
                        rgba[sample + 1] = (color >>> 8) & 0xff;
                        rgba[sample + 2] = color & 0xff;
                        rgba[sample + 3] = (color >>> 24) & 0xff;
                    }
                    writer.writeRow(line);
                }
            }
            writer.end();
        } catch (RuntimeException | Error exception) {
            writer.close();
            output.delete();
            throw exception;
        }
    }

    private static SegmentPlan analyze(
            List<File> frameFiles,
            int[] scrollDeltas,
            FrameBounds bounds,
            ProgressCallback progress) throws IOException {
        int count = frameFiles.size();
        int[] sourceTops = new int[count];
        int[] sourceBottoms = bounds.heights.clone();
        int[] overlaps = new int[count];
        boolean[] manualRequired = new boolean[count];
        int[] scores = new int[count];
        int[] maxOverlaps = new int[count];
        Rect[] contentRects = new Rect[count];
        int[] seamScores = new int[count];
        int[] consensusScores = new int[count];
        boolean[] noMovement = new boolean[count];
        String[] seamMessages = new String[count];
        int[] usedScrollDeltas = new int[count];
        int[] expectedOverlaps = new int[count];
        Rect[] matchRects = new Rect[count];
        Arrays.fill(expectedOverlaps, -1);
        seamMessages[0] = "";

        if (count == 1) {
            contentRects[0] = new Rect(0, 0, bounds.width, bounds.heights[0]);
            matchRects[0] = new Rect(contentRects[0]);
            LongScreenshotStitcher.StitchPlan plan = new LongScreenshotStitcher.StitchPlan(
                    overlaps, manualRequired, scores, maxOverlaps, contentRects,
                    seamScores, consensusScores, noMovement, seamMessages,
                    usedScrollDeltas, expectedOverlaps, matchRects);
            return new SegmentPlan(sourceTops, sourceBottoms, bounds.heights[0], plan);
        }

        int[] staticInsets = detectStaticInsets(frameFiles);

        Bitmap previous = decode(frameFiles.get(0));
        try {
            for (int i = 1; i < count; i++) {
                Bitmap next = decode(frameFiles.get(i));
                try {
                    ArrayList<Bitmap> pair = new ArrayList<>(2);
                    pair.add(previous);
                    pair.add(next);
                    int delta = scrollDeltas != null && i < scrollDeltas.length
                            ? Math.max(0, scrollDeltas[i])
                            : 0;
                    int expectedOverlap = recentExpectedOverlap(
                            overlaps, manualRequired, noMovement, i);
                    LongScreenshotStitcher.StitchPlan pathPlan = null;
                    boolean pathSupported = false;
                    if (expectedOverlap > 0
                            && hasStableSessionTrajectory(
                                    overlaps, manualRequired, noMovement, i, expectedOverlap)) {
                        pathPlan = LongScreenshotStitcher.analyzePairWithPathExpectedOverlap(
                                pair,
                                expectedOverlap,
                                staticInsets[0],
                                staticInsets[1]);
                        pathSupported = !pathPlan.manualRequired[1]
                                && LongScreenshotStitcher.supportsSessionTrajectoryExpectedOverlap(
                                        expectedOverlap,
                                        pathPlan.overlaps[1],
                                        pathPlan.scores[1],
                                        pathPlan.consensusScores[1],
                                        pathPlan.seamScores[1]);
                        Log.i(TAG, "path-first seam=" + i
                                + " expected=" + expectedOverlap
                                + " overlap=" + pathPlan.overlaps[1]
                                + " score=" + pathPlan.scores[1]
                                + " consensus=" + pathPlan.consensusScores[1]
                                + " seamScore=" + pathPlan.seamScores[1]
                                + " accepted=" + pathSupported
                                + " reason=" + pathPlan.seamMessages[1]);
                    }
                    LongScreenshotStitcher.StitchPlan pairPlan = pathSupported
                            ? pathPlan
                            : LongScreenshotStitcher.analyze(
                                    pair,
                                    true,
                                    new int[] {0, delta},
                                    staticInsets[0],
                                    staticInsets[1]);
                    if (pairPlan.manualRequired[1]
                            && expectedOverlap > 0
                            && pathPlan == null) {
                        pathPlan = LongScreenshotStitcher.analyzePairWithPathExpectedOverlap(
                                pair,
                                expectedOverlap,
                                staticInsets[0],
                                staticInsets[1]);
                        Log.i(TAG, "path-retry seam=" + i
                                + " expected=" + expectedOverlap
                                + " overlap=" + pathPlan.overlaps[1]
                                + " score=" + pathPlan.scores[1]
                                + " consensus=" + pathPlan.consensusScores[1]
                                + " seamScore=" + pathPlan.seamScores[1]
                                + " manual=" + pathPlan.manualRequired[1]
                                + " reason=" + pathPlan.seamMessages[1]);
                        if (!pathPlan.manualRequired[1]
                                && supportsRecentOverlap(
                                        overlaps,
                                        manualRequired,
                                        noMovement,
                                        i,
                                        pathPlan.overlaps[1],
                                        pathPlan.scores[1],
                                        pathPlan.consensusScores[1],
                                        pathPlan.seamScores[1])) {
                            pairPlan = pathPlan;
                            pathSupported = true;
                        }
                    }
                    if (pairPlan.manualRequired[1]) {
                        Log.i(TAG, "rejected seam=" + i
                                + " overlap=" + pairPlan.overlaps[1]
                                + " score=" + pairPlan.scores[1]
                                + " consensus=" + pairPlan.consensusScores[1]
                                + " static=" + staticInsets[0] + "/" + staticInsets[1]
                                + " reason=" + pairPlan.seamMessages[1]);
                        // ponytail: keep the best visual candidate and its manualRequired flag so a
                        // queued task always produces a recoverable draft; a full Top-K path editor is
                        // the upgrade when a user needs to override an irreducibly periodic seam.
                    }
                    if (pathSupported) {
                        Log.i(TAG, "path-confirmed seam=" + i
                                + " overlap=" + pairPlan.overlaps[1]
                                + " score=" + pairPlan.scores[1]
                                + " consensus=" + pairPlan.consensusScores[1]);
                    }
                    int overlap = pairPlan.overlaps[1];
                    int split = LongScreenshotStitcher.viewportCropTop(
                            pair,
                            overlap,
                            staticInsets[0],
                            staticInsets[1]);
                    Rect previousMatch = pairPlan.matchRects[0];
                    Rect nextMatch = pairPlan.matchRects[1];

                    sourceBottoms[i - 1] = previousMatch.bottom - (overlap - split);
                    sourceTops[i] = nextMatch.top + split;
                    validateSegment(i - 1, sourceTops[i - 1], sourceBottoms[i - 1], bounds.heights[i - 1]);
                    overlaps[i] = overlap;
                    manualRequired[i] = pairPlan.manualRequired[1];
                    scores[i] = pairPlan.scores[1];
                    maxOverlaps[i] = pairPlan.maxOverlaps[1];
                    seamScores[i] = pairPlan.seamScores[1];
                    consensusScores[i] = pairPlan.consensusScores[1];
                    noMovement[i] = pairPlan.noMovement[1];
                    seamMessages[i] = pathSupported
                            ? "连续滚动轨迹确认"
                            : pairPlan.manualRequired[1]
                                    ? "低置信候选：" + pairPlan.seamMessages[1]
                                    : pairPlan.seamMessages[1];
                    usedScrollDeltas[i] = delta;
                    expectedOverlaps[i] = pairPlan.expectedOverlaps[1];
                    if (matchRects[i - 1] == null) {
                        matchRects[i - 1] = new Rect(previousMatch);
                    }
                    matchRects[i] = new Rect(nextMatch);
                } finally {
                    previous.recycle();
                    previous = next;
                }
                if (progress != null) {
                    progress.onProgress(i, count - 1);
                }
            }
        } finally {
            if (!previous.isRecycled()) {
                previous.recycle();
            }
        }

        long totalHeight = 0L;
        for (int i = 0; i < count; i++) {
            validateSegment(i, sourceTops[i], sourceBottoms[i], bounds.heights[i]);
            contentRects[i] = new Rect(0, sourceTops[i], bounds.width, sourceBottoms[i]);
            totalHeight += sourceBottoms[i] - sourceTops[i];
            if (totalHeight > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("长图高度超过 PNGJ/Android 可处理范围");
            }
        }
        LongScreenshotStitcher.StitchPlan plan = new LongScreenshotStitcher.StitchPlan(
                overlaps, manualRequired, scores, maxOverlaps, contentRects,
                seamScores, consensusScores, noMovement, seamMessages,
                usedScrollDeltas, expectedOverlaps, matchRects);
        return new SegmentPlan(sourceTops, sourceBottoms, (int) totalHeight, plan);
    }

    static boolean supportsRecentOverlap(
            int[] overlaps,
            boolean[] manualRequired,
            boolean[] noMovement,
            int seamIndex,
            int candidate,
            int score,
            int consensus,
            int seamScore) {
        int median = recentExpectedOverlap(overlaps, manualRequired, noMovement, seamIndex);
        return LongScreenshotStitcher.supportsTrajectoryExpectedOverlap(
                median, candidate, score, consensus, seamScore);
    }

    static int recentExpectedOverlap(
            int[] overlaps,
            boolean[] manualRequired,
            boolean[] noMovement,
            int seamIndex) {
        int[] recent = new int[PATH_RECENT_LIMIT];
        int count = 0;
        for (int i = seamIndex - 1; i >= 1 && count < PATH_RECENT_LIMIT; i--) {
            if (!manualRequired[i] && !noMovement[i] && overlaps[i] > 0) {
                recent[count++] = overlaps[i];
            }
        }
        if (count < PATH_MIN_SUPPORT) {
            return -1;
        }
        Arrays.sort(recent, 0, count);
        int median = recent[count / 2];
        int stable = 0;
        for (int i = 0; i < count; i++) {
            if (Math.abs(recent[i] - median) <= PATH_STABILITY_TOLERANCE) {
                stable++;
            }
        }
        // ponytail: a five-seam median resolves locally repeated chat tiles; a full Top-K path is the upgrade path for deliberate variable-step capture.
        return stable >= 2 ? median : -1;
    }

    private static boolean hasStableSessionTrajectory(
            int[] overlaps,
            boolean[] manualRequired,
            boolean[] noMovement,
            int seamIndex,
            int expectedOverlap) {
        int supported = 0;
        for (int i = seamIndex - 1;
                i >= 1 && i >= seamIndex - PATH_RECENT_LIMIT;
                i--) {
            if (!manualRequired[i]
                    && !noMovement[i]
                    && overlaps[i] > 0
                    && Math.abs(overlaps[i] - expectedOverlap) <= SESSION_PATH_STABILITY_TOLERANCE) {
                supported++;
            }
        }
        // ponytail: require three tightly clustered seams before skipping a global search; this
        // rejects variable-step content while keeping the real long-session 48px retry bounded.
        return supported >= SESSION_PATH_MIN_SUPPORT;
    }

    private static int[] detectStaticInsets(List<File> frameFiles) throws IOException {
        int sampleCount = Math.min(5, frameFiles.size());
        int[] topInsets = new int[Math.max(1, sampleCount - 1)];
        int[] bottomInsets = new int[Math.max(1, sampleCount - 1)];
        int pairCount = 0;
        Bitmap previous = null;
        try {
            for (int sample = 0; sample < sampleCount; sample++) {
                int index = sampleCount == 1
                        ? 0
                        : sample * (frameFiles.size() - 1) / (sampleCount - 1);
                Bitmap current = decode(frameFiles.get(index));
                if (previous != null) {
                    Bitmap old = previous;
                    previous = current;
                    ArrayList<Bitmap> pair = new ArrayList<>(2);
                    pair.add(old);
                    pair.add(current);
                    try {
                        int[] insets = LongScreenshotStitcher.detectStaticInsets(pair);
                        topInsets[pairCount] = insets[0];
                        bottomInsets[pairCount] = insets[1];
                        pairCount++;
                    } finally {
                        old.recycle();
                    }
                } else {
                    previous = current;
                }
            }
            return new int[] {
                    conservativeInset(topInsets, pairCount),
                    conservativeInset(bottomInsets, pairCount)
            };
        } finally {
            if (previous != null) {
                previous.recycle();
            }
        }
    }

    private static int conservativeInset(int[] values, int count) {
        if (count <= 0) {
            return 0;
        }
        Arrays.sort(values, 0, count);
        // ponytail: the smallest distant-pair inset avoids treating chat whitespace as fixed chrome;
        // a per-row motion mask is the upgrade path when fixed chrome itself animates.
        return values[0];
    }

    private static void writePng(
            List<File> frameFiles,
            SegmentPlan segments,
            int width,
            File output,
            ProgressCallback progress) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create preview dir");
        }
        ImageInfo imageInfo = new ImageInfo(width, segments.totalHeight, 8, true);
        PngWriter writer = new PngWriter(output, imageInfo, true);
        writer.setCompLevel(PNG_COMPRESSION_LEVEL);
        writer.setFilterType(FilterType.FILTER_ADAPTIVE_FAST);
        ImageLineInt line = new ImageLineInt(imageInfo);
        int blockRows = CROP_BLOCK_ROWS;
        int[] argb = new int[width * blockRows];
        int[] rgba = line.getScanline();
        try {
            for (int i = 0; i < frameFiles.size(); i++) {
                Bitmap bitmap = decode(frameFiles.get(i));
                try {
                    int top = segments.sourceTops[i];
                    int bottom = segments.sourceBottoms[i];
                    for (int blockTop = top; blockTop < bottom; blockTop += blockRows) {
                        int rows = Math.min(blockRows, bottom - blockTop);
                        bitmap.getPixels(argb, 0, width, 0, blockTop, width, rows);
                        for (int row = 0; row < rows; row++) {
                            int source = row * width;
                            scrubRightEdgeIndicators(argb, source, width);
                            for (int x = 0, sample = 0; x < width; x++, source++, sample += 4) {
                                int color = argb[source];
                                rgba[sample] = (color >>> 16) & 0xff;
                                rgba[sample + 1] = (color >>> 8) & 0xff;
                                rgba[sample + 2] = color & 0xff;
                                rgba[sample + 3] = (color >>> 24) & 0xff;
                            }
                            writer.writeRow(line);
                        }
                    }
                } finally {
                    bitmap.recycle();
                }
                if (progress != null) {
                    progress.onProgress(i + 1, frameFiles.size());
                }
            }
            writer.end();
        } catch (IOException | RuntimeException | Error exception) {
            writer.close();
            output.delete();
            throw exception;
        }
    }

    private static void writeCroppedPng(
            BitmapRegionDecoder decoder,
            Rect crop,
            File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create crop dir");
        }
        ImageInfo imageInfo = new ImageInfo(crop.width(), crop.height(), 8, true);
        PngWriter writer = new PngWriter(output, imageInfo, true);
        writer.setCompLevel(PNG_COMPRESSION_LEVEL);
        writer.setFilterType(FilterType.FILTER_ADAPTIVE_FAST);
        ImageLineInt line = new ImageLineInt(imageInfo);
        int[] argb = new int[crop.width()];
        int[] rgba = line.getScanline();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        try {
            for (int top = crop.top; top < crop.bottom; top += CROP_BLOCK_ROWS) {
                int bottom = Math.min(crop.bottom, top + CROP_BLOCK_ROWS);
                Bitmap block = decoder.decodeRegion(
                        new Rect(crop.left, top, crop.right, bottom), options);
                if (block == null
                        || block.getWidth() != crop.width()
                        || block.getHeight() != bottom - top) {
                    if (block != null) {
                        block.recycle();
                    }
                    throw new IOException("长图裁剪分块解码失败");
                }
                try {
                    for (int y = 0; y < block.getHeight(); y++) {
                        block.getPixels(argb, 0, crop.width(), 0, y, crop.width(), 1);
                        for (int x = 0, sample = 0; x < crop.width(); x++, sample += 4) {
                            int color = argb[x];
                            rgba[sample] = (color >>> 16) & 0xff;
                            rgba[sample + 1] = (color >>> 8) & 0xff;
                            rgba[sample + 2] = color & 0xff;
                            rgba[sample + 3] = (color >>> 24) & 0xff;
                        }
                        writer.writeRow(line);
                    }
                } finally {
                    block.recycle();
                }
            }
            writer.end();
        } catch (IOException | RuntimeException | Error exception) {
            writer.close();
            output.delete();
            throw exception;
        }
    }

    private static void publish(File part, File output) throws IOException {
        if ((output.exists() && !output.delete()) || !part.renameTo(output)) {
            throw new IOException("流式 PNG 发布失败");
        }
    }

    private static void verify(File output, int expectedWidth, int expectedHeight) throws IOException {
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(output.getAbsolutePath(), false);
            if (decoder.getWidth() != expectedWidth || decoder.getHeight() != expectedHeight) {
                throw new IOException("流式 PNG 尺寸校验失败");
            }
            int[] rows = {0, expectedHeight / 2, expectedHeight - 1};
            for (int y : rows) {
                Bitmap sample = decoder.decodeRegion(new Rect(0, y, 1, y + 1), null);
                if (sample == null) {
                    throw new IOException("流式 PNG 区域校验失败");
                }
                sample.recycle();
            }
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    private static FrameBounds readBounds(List<File> files) throws IOException {
        int width = Integer.MAX_VALUE;
        int[] heights = new int[files.size()];
        for (int i = 0; i < files.size(); i++) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(files.get(i).getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw new IOException("无法读取原始帧：" + files.get(i).getName());
            }
            width = Math.min(width, options.outWidth);
            heights[i] = options.outHeight;
        }
        return new FrameBounds(width, heights);
    }

    private static Bitmap decode(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new IOException("无法解码原始帧：" + file.getName());
        }
        return bitmap;
    }

    private static void validateSegment(int index, int top, int bottom, int frameHeight) {
        if (top < 0 || bottom > frameHeight || bottom <= top) {
            throw new IllegalArgumentException(
                    "第 " + (index + 1) + " 帧没有可输出内容：" + top + "-" + bottom);
        }
    }

    private static void scrubRightEdgeIndicators(int[] row, int offset, int width) {
        int scrubWidth = clamp(width / 45, EDGE_INDICATOR_MIN_SCRUB_PX, EDGE_INDICATOR_MAX_SCRUB_PX);
        int startX = Math.max(0, width - scrubWidth);
        int referenceX = Math.max(0, startX - 3);
        int reference = row[offset + referenceX];
        int referenceGray = gray(reference);
        for (int x = startX; x < width; x++) {
            int index = offset + x;
            int color = row[index];
            if (referenceGray > 80
                    && gray(color) + 16 < referenceGray
                    && colorDistance(color, reference) >= EDGE_INDICATOR_CONTRAST) {
                row[index] = reference;
            }
        }
    }

    private static int gray(int color) {
        return (((color >> 16) & 0xff) * 30
                + ((color >> 8) & 0xff) * 59
                + (color & 0xff) * 11) / 100;
    }

    private static int colorDistance(int first, int second) {
        int red = Math.abs(((first >> 16) & 0xff) - ((second >> 16) & 0xff));
        int green = Math.abs(((first >> 8) & 0xff) - ((second >> 8) & 0xff));
        int blue = Math.abs((first & 0xff) - (second & 0xff));
        return (red + green + blue) / 3;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Result {
        final File file;
        final LongScreenshotStitcher.StitchPlan plan;
        final int width;
        final int height;

        Result(File file, LongScreenshotStitcher.StitchPlan plan, int width, int height) {
            this.file = file;
            this.plan = plan;
            this.width = width;
            this.height = height;
        }
    }

    private static final class FrameBounds {
        final int width;
        final int[] heights;

        FrameBounds(int width, int[] heights) {
            this.width = width;
            this.heights = heights;
        }
    }

    private static final class SegmentPlan {
        final int[] sourceTops;
        final int[] sourceBottoms;
        final int totalHeight;
        final LongScreenshotStitcher.StitchPlan plan;

        SegmentPlan(
                int[] sourceTops,
                int[] sourceBottoms,
                int totalHeight,
                LongScreenshotStitcher.StitchPlan plan) {
            this.sourceTops = sourceTops;
            this.sourceBottoms = sourceBottoms;
            this.totalHeight = totalHeight;
            this.plan = plan;
        }
    }
}
