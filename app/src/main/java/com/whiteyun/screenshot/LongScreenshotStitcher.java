package com.whiteyun.screenshot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

final class LongScreenshotStitcher {
    private static final String TAG = "WhiteYunStitch";
    private static final int MAX_STITCH_HEIGHT = 40000;
    private static final int MAX_STITCH_PIXELS = 54_000_000;
    private static final int OVERLAP_SCORE_THRESHOLD = 45;
    private static final int OVERLAP_STRONG_SCORE_THRESHOLD = 18;
    private static final int OVERLAP_CONFIDENCE_DELTA = 8;
    private static final int OVERLAP_VISUAL_LEAD_DELTA = 4;
    private static final int SEAM_SCORE_THRESHOLD = 58;
    private static final int NO_MOVEMENT_SCORE_THRESHOLD = 4;
    private static final int LOW_TEXTURE_THRESHOLD = 5;
    private static final int BLOCK_CONSENSUS_ACCEPT_THRESHOLD = 45;
    private static final int BLOCK_CONSENSUS_TARGET = 62;
    private static final int BLOCK_CONSENSUS_STRONG = 95;
    private static final int BLOCK_CONSENSUS_LEAD = 5;
    private static final int CONSENSUS_BLOCK_WIDTH = 64;
    private static final int CONSENSUS_BLOCK_HEIGHT = 48;
    private static final int CONSENSUS_SAMPLE_STEP = 12;
    private static final int FEATURE_X_STEP = 4;
    private static final int CONSENSUS_MAX_BLOCK_ROWS_COARSE = 0;
    private static final int CONSENSUS_MAX_BLOCK_ROWS_DETAILED = 0;
    private static final int ANCHOR_MARGIN_DIVISOR = 24;
    private static final int TEXTURE_TEMPLATE_MIN_WEIGHT = 120;
    private static final int CANDIDATE_LIMIT = 8;
    private static final int MIN_NEW_CONTENT_AFTER_OVERLAP = 96;
    private static final int AUTO_MIN_OVERLAP_DIVISOR = 6;
    private static final int MANUAL_OVERLAP_MAX_NUMERATOR = 3;
    private static final int MANUAL_OVERLAP_MAX_DENOMINATOR = 5;
    private static final int MANUAL_OVERLAP_TARGET_NUMERATOR = 1;
    private static final int MANUAL_OVERLAP_TARGET_DENOMINATOR = 2;
    private static final int NATURAL_OVERLAP_MIN_DIVISOR = 5;
    private static final int NATURAL_OVERLAP_MAX_NUMERATOR = 4;
    private static final int NATURAL_OVERLAP_MAX_DENOMINATOR = 5;
    private static final int NATURAL_OVERLAP_TARGET_NUMERATOR = 11;
    private static final int NATURAL_OVERLAP_TARGET_DENOMINATOR = 20;
    private static final int NATURAL_OVERLAP_TARGET_TOLERANCE_DIVISOR = 12;
    private static final int VIEWPORT_BOTTOM_GUARD_PX = 96;
    private static final int EXPECTED_OVERLAP_TOLERANCE_DIVISOR = 80;
    private static final int VISUAL_RANK_PRIOR_MAX = 10;
    private static final int STATIC_EDGE_SCORE_THRESHOLD = 14;
    private static final int STATIC_EDGE_MIN_PX = 48;
    private static final int STATIC_EDGE_MAX_PX = 420;
    private static final int STATIC_EDGE_SCAN_STEP_PX = 4;
    private static final int EDGE_INDICATOR_MIN_SCRUB_PX = 6;
    private static final int EDGE_INDICATOR_MAX_SCRUB_PX = 36;
    private static final int EDGE_INDICATOR_CONTRAST = 24;
    private static final int TRAJECTORY_NEIGHBOR_LIMIT = 3;
    private static final int TRAJECTORY_STABILITY_TOLERANCE = 96;
    private static final int TRAJECTORY_MAX_DEVIATION = 48;
    private static final int TRAJECTORY_MAX_SCORE = 18;
    private static final int TRAJECTORY_MIN_CONSENSUS = 94;
    private static final int TRAJECTORY_LOW_TEXTURE_MIN_CONSENSUS = 90;
    private static final int TRAJECTORY_MAX_SEAM_SCORE = 18;
    private static final int TRAJECTORY_LOW_TEXTURE_MAX_SEAM_SCORE = 10;
    private static final int NEAR_DUPLICATE_CHANGED_PERCENT = 2;

    private LongScreenshotStitcher() {
    }

    interface ProgressCallback {
        void onProgress(int completed, int total);
    }

    static final class StitchPlan {
        final int[] overlaps;
        final boolean[] manualRequired;
        final int[] scores;
        final int[] maxOverlaps;
        final Rect[] contentRects;
        final int[] seamScores;
        final int[] consensusScores;
        final boolean[] noMovement;
        final String[] seamMessages;
        final int[] scrollDeltas;
        final int[] expectedOverlaps;
        final Rect[] matchRects;

        StitchPlan(
                int[] overlaps,
                boolean[] manualRequired,
                int[] scores,
                int[] maxOverlaps,
                Rect[] contentRects,
                int[] seamScores,
                int[] consensusScores,
                boolean[] noMovement,
                String[] seamMessages,
                int[] scrollDeltas,
                int[] expectedOverlaps,
                Rect[] matchRects) {
            this.overlaps = overlaps;
            this.manualRequired = manualRequired;
            this.scores = scores;
            this.maxOverlaps = maxOverlaps;
            this.contentRects = contentRects;
            this.seamScores = seamScores;
            this.consensusScores = consensusScores;
            this.noMovement = noMovement;
            this.seamMessages = seamMessages;
            this.scrollDeltas = scrollDeltas;
            this.expectedOverlaps = expectedOverlaps;
            this.matchRects = matchRects;
        }

        boolean needsManualAdjustment() {
            for (int i = 1; i < manualRequired.length; i++) {
                if (manualRequired[i]) {
                    return true;
                }
            }
            return false;
        }

        int firstManualSeam() {
            for (int i = 1; i < manualRequired.length; i++) {
                if (manualRequired[i]) {
                    return i;
                }
            }
            return overlaps.length > 1 ? 1 : 0;
        }
    }

    static long fingerprint(Bitmap bitmap) {
        int size = 8;
        int[] values = new int[size * size];
        int total = 0;
        for (int y = 0; y < size; y++) {
            int py = Math.min(bitmap.getHeight() - 1, (y + 1) * bitmap.getHeight() / (size + 1));
            for (int x = 0; x < size; x++) {
                int px = Math.min(bitmap.getWidth() - 1, (x + 1) * bitmap.getWidth() / (size + 1));
                int color = bitmap.getPixel(px, py);
                int gray = (((color >> 16) & 0xff) * 30 + ((color >> 8) & 0xff) * 59 + (color & 0xff) * 11) / 100;
                values[y * size + x] = gray;
                total += gray;
            }
        }

        int average = total / values.length;
        long hash = 0L;
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= average) {
                hash |= 1L << i;
            }
        }
        return hash;
    }

    static boolean isNearDuplicate(long first, long second) {
        return Long.bitCount(first ^ second) <= 2;
    }

    static boolean isNearDuplicate(Bitmap first, Bitmap second) {
        if (first == null
                || second == null
                || first.getWidth() != second.getWidth()
                || first.getHeight() != second.getHeight()) {
            return false;
        }
        int rows = 48;
        int columns = 32;
        long colorTotal = 0L;
        long edgeTotal = 0L;
        int changed = 0;
        int samples = 0;
        int top = first.getHeight() / 8;
        int comparableHeight = Math.max(1, first.getHeight() * 3 / 4);
        for (int row = 0; row < rows; row++) {
            int y = top + (row + 1) * comparableHeight / (rows + 1);
            for (int column = 0; column < columns; column++) {
                int x = (column + 1) * first.getWidth() / (columns + 1);
                int colorDiff = colorDistance(first.getPixel(x, y), second.getPixel(x, y));
                int edgeDiff = Math.abs(pixelEdge(first, x, y) - pixelEdge(second, x, y));
                colorTotal += colorDiff;
                edgeTotal += edgeDiff;
                if (colorDiff > 12 || edgeDiff > 18) {
                    changed++;
                }
                samples++;
            }
        }
        // ponytail: tolerate a tiny rotating banner/caret region at rest; motion-aware region masks are the upgrade path.
        return colorTotal / Math.max(1, samples) <= 1
                && edgeTotal / Math.max(1, samples) <= 2
                && changed * 100 <= samples * NEAR_DUPLICATE_CHANGED_PERCENT;
    }

    static Bitmap stitch(List<Bitmap> frames) {
        PreparedFrames prepared = prepareFrames(frames);
        return stitch(prepared, buildPlan(prepared, true, true, null).overlaps, false, null);
    }

    static StitchPlan analyze(List<Bitmap> frames) {
        return analyze(frames, true);
    }

    static StitchPlan analyze(List<Bitmap> frames, boolean autoMode) {
        return analyze(frames, autoMode, null);
    }

    static StitchPlan analyze(List<Bitmap> frames, boolean autoMode, int[] scrollDeltas) {
        return analyze(frames, autoMode, scrollDeltas, null);
    }

    static StitchPlan analyze(
            List<Bitmap> frames,
            boolean autoMode,
            int[] scrollDeltas,
            ProgressCallback progressCallback) {
        return buildPlan(prepareFrames(frames), true, autoMode, scrollDeltas, progressCallback);
    }

    static StitchPlan analyze(
            List<Bitmap> frames,
            boolean autoMode,
            int[] scrollDeltas,
            int staticTop,
            int staticBottom) {
        return buildPlan(
                prepareFrames(frames, staticTop, staticBottom),
                true,
                autoMode,
                scrollDeltas,
                null);
    }

    static StitchPlan analyzePairWithPathExpectedOverlap(
            List<Bitmap> frames,
            int expectedOverlap,
            int staticTop,
            int staticBottom) {
        PreparedFrames prepared = prepareFrames(frames, staticTop, staticBottom);
        if (prepared.frames.size() != 2) {
            throw new IllegalArgumentException("轨迹接缝只支持相邻两帧");
        }
        int maxOverlap = maxOverlap(prepared.matchRects[0], prepared.matchRects[1]);
        int expected = clamp(expectedOverlap, 0, maxOverlap);
        OverlapResult result = findOverlap(prepared, 1, true, expected, true, false);
        int[] overlaps = {0, result.overlap};
        boolean[] manualRequired = {false, !result.accepted};
        int[] scores = {0, result.score};
        int[] maxOverlaps = {0, maxOverlap};
        int[] seamScores = {0, result.seamScore};
        int[] consensusScores = {0, result.consensusScore};
        boolean[] noMovement = {false, result.noMovement};
        String[] seamMessages = {"", result.message};
        int[] scrollDeltas = {0, Math.max(0, maxOverlap - expected)};
        int[] expectedOverlaps = {-1, expected};
        totalHeight(prepared, overlaps);
        return new StitchPlan(
                overlaps,
                manualRequired,
                scores,
                maxOverlaps,
                cloneRects(prepared.contentRects),
                seamScores,
                consensusScores,
                noMovement,
                seamMessages,
                scrollDeltas,
                expectedOverlaps,
                cloneRects(prepared.matchRects));
    }

    static int[] detectStaticInsets(List<Bitmap> frames) {
        int width = minWidth(frames);
        return new int[] {
                detectStaticEdge(frames, width, true),
                detectStaticEdge(frames, width, false)
        };
    }

    static Bitmap stitch(List<Bitmap> frames, int[] overlaps) {
        return stitch(frames, overlaps, null);
    }

    static Bitmap stitch(List<Bitmap> frames, int[] overlaps, ProgressCallback progressCallback) {
        return stitch(frames, overlaps, false, progressCallback);
    }

    static Bitmap stitch(
            List<Bitmap> frames,
            int[] overlaps,
            boolean protectViewportBottom,
            ProgressCallback progressCallback) {
        return stitch(prepareFrames(frames), overlaps, protectViewportBottom, progressCallback);
    }

    private static Bitmap stitch(
            PreparedFrames prepared,
            int[] overlaps,
            boolean protectViewportBottom,
            ProgressCallback progressCallback) {
        int[] bottomGuards = bottomGuards(prepared, overlaps, protectViewportBottom);
        int totalHeight = totalHeight(prepared, overlaps, bottomGuards);

        Bitmap output = Bitmap.createBitmap(prepared.width, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        int y = 0;
        drawFrame(output, canvas, prepared, 0, 0, bottomGuards[0], y);
        notifyProgress(progressCallback, 1, prepared.frames.size());
        y += segmentHeight(prepared, 0, 0, bottomGuards[0]);
        for (int i = 1; i < prepared.frames.size(); i++) {
            int overlap = adjustedCropTop(overlaps[i], bottomGuards[i - 1]);
            drawFrame(output, canvas, prepared, i, overlap, bottomGuards[i], y);
            notifyProgress(progressCallback, i + 1, prepared.frames.size());
            y += segmentHeight(prepared, i, overlap, bottomGuards[i]);
        }
        return output;
    }

    static int maxOverlap(List<Bitmap> frames, int seamIndex) {
        PreparedFrames prepared = prepareFrames(frames);
        if (seamIndex <= 0 || seamIndex >= prepared.frames.size()) {
            return 0;
        }
        return maxOverlap(prepared.matchRects[seamIndex - 1], prepared.matchRects[seamIndex]);
    }

    static int viewportCropTop(List<Bitmap> frames, int overlap) {
        PreparedFrames prepared = prepareFrames(frames);
        return viewportCropTop(prepared, overlap);
    }

    static int viewportCropTop(
            List<Bitmap> frames,
            int overlap,
            int staticTop,
            int staticBottom) {
        return viewportCropTop(prepareFrames(frames, staticTop, staticBottom), overlap);
    }

    private static int viewportCropTop(PreparedFrames prepared, int overlap) {
        if (prepared.frames.size() != 2) {
            throw new IllegalArgumentException("流式接缝需要相邻两帧");
        }
        int safeOverlap = clamp(overlap, 0, maxOverlap(prepared.matchRects[0], prepared.matchRects[1]));
        return safeViewportCropTop(prepared, 1, safeOverlap);
    }

    private static StitchPlan buildPlan(
            PreparedFrames prepared,
            boolean keepRejectedSuggestion,
            boolean autoMode,
            int[] suppliedScrollDeltas) {
        return buildPlan(prepared, keepRejectedSuggestion, autoMode, suppliedScrollDeltas, null);
    }

    private static StitchPlan buildPlan(
            PreparedFrames prepared,
            boolean keepRejectedSuggestion,
            boolean autoMode,
            int[] suppliedScrollDeltas,
            ProgressCallback progressCallback) {
        int[] overlaps = new int[prepared.frames.size()];
        boolean[] manualRequired = new boolean[prepared.frames.size()];
        int[] scores = new int[prepared.frames.size()];
        int[] maxOverlaps = new int[prepared.frames.size()];
        int[] seamScores = new int[prepared.frames.size()];
        int[] consensusScores = new int[prepared.frames.size()];
        boolean[] noMovement = new boolean[prepared.frames.size()];
        String[] seamMessages = new String[prepared.frames.size()];
        int[] scrollDeltas = new int[prepared.frames.size()];
        int[] expectedOverlaps = new int[prepared.frames.size()];
        Arrays.fill(expectedOverlaps, -1);
        seamMessages[0] = "";
        for (int i = 1; i < prepared.frames.size(); i++) {
            int suppliedDelta = suppliedScrollDeltas != null && i < suppliedScrollDeltas.length
                    ? suppliedScrollDeltas[i]
                    : 0;
            int delta = suppliedDelta;
            // ponytail: accessibility scroll deltas are event hints, not pixel-accurate screenshot displacements in nested apps like WeChat.
            boolean trustedScrollDelta = false;
            maxOverlaps[i] = maxOverlap(prepared.matchRects[i - 1], prepared.matchRects[i]);
            if (delta <= 0 && autoMode) {
                delta = estimateScrollDelta(prepared, i, maxOverlaps[i]);
            }
            scrollDeltas[i] = Math.max(0, delta);
            OverlapResult result;
            if (trustedScrollDelta && delta > maxOverlaps[i] - MIN_NEW_CONTENT_AFTER_OVERLAP) {
                result = new OverlapResult(
                        0,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        0,
                        false,
                        false,
                        "滚动距离过大、重合不足，无法可靠自动接缝");
            } else {
                if (delta > 0) {
                    expectedOverlaps[i] = clamp(maxOverlaps[i] - delta, 0, maxOverlaps[i]);
                }
                result = findOverlap(
                        prepared,
                        i,
                        autoMode,
                        expectedOverlaps[i],
                        trustedScrollDelta,
                        true);
            }
            overlaps[i] = result.accepted || keepRejectedSuggestion ? result.overlap : 0;
            manualRequired[i] = !result.accepted;
            scores[i] = result.score;
            seamScores[i] = result.seamScore;
            consensusScores[i] = result.consensusScore;
            noMovement[i] = result.noMovement;
            seamMessages[i] = result.message;
            prepared.features[i - 1] = null;
            notifyProgress(progressCallback, i, Math.max(1, prepared.frames.size() - 1));
        }
        if (autoMode) {
            for (int i = 1; i < prepared.frames.size(); i++) {
                if (!manualRequired[i]) {
                    continue;
                }
                int expected = neighboringExpectedOverlap(
                        overlaps, manualRequired, noMovement, i);
                if (expected <= 0) {
                    continue;
                }
                OverlapResult pathResult = findOverlap(
                        prepared,
                        i,
                        true,
                        expected,
                        true,
                        false);
                Log.i(TAG, "neighbor-path-retry seam=" + i
                        + " expected=" + expected
                        + " overlap=" + pathResult.overlap
                        + " score=" + pathResult.score
                        + " consensus=" + pathResult.consensusScore
                        + " seamScore=" + pathResult.seamScore
                        + " accepted=" + pathResult.accepted
                        + " reason=" + pathResult.message);
                if (!pathResult.accepted
                        || pathResult.noMovement
                        || !supportsTrajectoryExpectedOverlap(
                                expected,
                                pathResult.overlap,
                                pathResult.score,
                                pathResult.consensusScore,
                                pathResult.seamScore)) {
                    continue;
                }
                overlaps[i] = pathResult.overlap;
                manualRequired[i] = false;
                scores[i] = pathResult.score;
                seamScores[i] = pathResult.seamScore;
                consensusScores[i] = pathResult.consensusScore;
                noMovement[i] = false;
                seamMessages[i] = "相邻滚动轨迹确认";
                expectedOverlaps[i] = expected;
                Log.i(TAG, "neighbor-path-confirmed seam=" + i
                        + " expected=" + expected
                        + " overlap=" + pathResult.overlap
                        + " score=" + pathResult.score
                        + " consensus=" + pathResult.consensusScore);
            }
        }
        totalHeight(prepared, overlaps);
        return new StitchPlan(
                overlaps,
                manualRequired,
                scores,
                maxOverlaps,
                cloneRects(prepared.contentRects),
                seamScores,
                consensusScores,
                noMovement,
                seamMessages,
                scrollDeltas,
                expectedOverlaps,
                cloneRects(prepared.matchRects));
    }

    static int neighboringExpectedOverlap(
            int[] overlaps,
            boolean[] manualRequired,
            boolean[] noMovement,
            int seamIndex) {
        int[] nearby = new int[TRAJECTORY_NEIGHBOR_LIMIT];
        int count = 0;
        for (int distance = 1;
                distance < overlaps.length && count < TRAJECTORY_NEIGHBOR_LIMIT;
                distance++) {
            int left = seamIndex - distance;
            if (left >= 1
                    && !manualRequired[left]
                    && !noMovement[left]
                    && overlaps[left] > 0) {
                nearby[count++] = overlaps[left];
            }
            int right = seamIndex + distance;
            if (count < TRAJECTORY_NEIGHBOR_LIMIT
                    && right < overlaps.length
                    && !manualRequired[right]
                    && !noMovement[right]
                    && overlaps[right] > 0) {
                nearby[count++] = overlaps[right];
            }
        }
        if (count < TRAJECTORY_NEIGHBOR_LIMIT) {
            return -1;
        }
        Arrays.sort(nearby, 0, count);
        int median = nearby[count / 2];
        int stable = 0;
        for (int i = 0; i < count; i++) {
            if (Math.abs(nearby[i] - median) <= TRAJECTORY_STABILITY_TOLERANCE) {
                stable++;
            }
        }
        // ponytail: three nearest accepted seams with two agreeing values fix early ambiguous cards; a full Top-K path is the variable-step upgrade.
        return stable >= 2 ? median : -1;
    }

    static boolean supportsTrajectoryExpectedOverlap(
            int expected,
            int candidate,
            int score,
            int consensus,
            int seamScore) {
        if (expected <= 0
                || candidate <= 0
                || Math.abs(candidate - expected) > TRAJECTORY_MAX_DEVIATION
                || score > TRAJECTORY_MAX_SCORE
                || seamScore > TRAJECTORY_MAX_SEAM_SCORE) {
            return false;
        }
        return consensus >= TRAJECTORY_MIN_CONSENSUS
                || (consensus >= TRAJECTORY_LOW_TEXTURE_MIN_CONSENSUS
                        && score <= 0
                        && seamScore <= TRAJECTORY_LOW_TEXTURE_MAX_SEAM_SCORE);
    }

    static boolean supportsSessionTrajectoryExpectedOverlap(
            int expected,
            int candidate,
            int score,
            int consensus,
            int seamScore) {
        // A stable full-session path is stronger evidence than one low-texture pair. The pair still
        // has to pass the normal visual acceptance gate before this narrower confirmation is used.
        return expected > 0
                && candidate > 0
                && Math.abs(candidate - expected) <= TRAJECTORY_MAX_DEVIATION
                && score <= OVERLAP_SCORE_THRESHOLD
                && consensus >= BLOCK_CONSENSUS_ACCEPT_THRESHOLD
                && seamScore <= SEAM_SCORE_THRESHOLD;
    }

    private static void notifyProgress(ProgressCallback callback, int completed, int total) {
        if (callback != null) {
            callback.onProgress(completed, total);
        }
    }

    private static int minWidth(List<Bitmap> frames) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("没有可拼接的采样帧");
        }

        int width = frames.get(0).getWidth();
        for (Bitmap frame : frames) {
            width = Math.min(width, frame.getWidth());
        }
        return width;
    }

    private static int totalHeight(PreparedFrames prepared, int[] overlaps) {
        return totalHeight(prepared, overlaps, new int[overlaps.length]);
    }

    private static int totalHeight(PreparedFrames prepared, int[] overlaps, int[] bottomGuards) {
        if (overlaps.length != prepared.frames.size()) {
            throw new IllegalArgumentException("接缝数量与图片数量不一致");
        }
        long totalHeight = segmentHeight(prepared, 0, 0, bottomGuards[0]);
        ensureStitchLimit(prepared.width, totalHeight);
        for (int i = 1; i < prepared.frames.size(); i++) {
            int overlap = clamp(overlaps[i], 0, maxOverlap(prepared.matchRects[i - 1], prepared.matchRects[i]));
            overlaps[i] = overlap;
            int cropTop = adjustedCropTop(overlap, bottomGuards[i - 1]);
            totalHeight += segmentHeight(prepared, i, cropTop, bottomGuards[i]);
            ensureStitchLimit(prepared.width, totalHeight);
        }
        return (int) totalHeight;
    }

    private static void ensureStitchLimit(int width, long height) {
        if (height > MAX_STITCH_HEIGHT || (long) width * height > MAX_STITCH_PIXELS) {
            throw new IllegalArgumentException("长图过大，请减少图片数量或压缩截图");
        }
    }

    private static void drawFrame(
            Bitmap output,
            Canvas canvas,
            PreparedFrames prepared,
            int index,
            int cropTop,
            int cropBottom,
            int y) {
        Bitmap frame = prepared.frames.get(index);
        Rect content = prepared.contentRects[index];
        int top = clamp(content.top + cropTop, content.top, content.bottom);
        int bottom = clamp(content.bottom - cropBottom, top, content.bottom);
        Rect src = new Rect(content.left, top, content.right, bottom);
        Rect dst = new Rect(0, y, prepared.width, y + src.height());
        canvas.drawBitmap(frame, src, dst, null);
        scrubRightEdgeIndicators(output, dst.top, dst.bottom);
    }

    private static int[] bottomGuards(PreparedFrames prepared, int[] overlaps, boolean protectViewportBottom) {
        int[] guards = new int[prepared.frames.size()];
        if (!protectViewportBottom) {
            return guards;
        }
        for (int i = 0; i < prepared.frames.size() - 1; i++) {
            int nextOverlap = i + 1 < overlaps.length
                    ? clamp(overlaps[i + 1], 0, maxOverlap(prepared.matchRects[i], prepared.matchRects[i + 1]))
                    : 0;
            int cropTop = safeViewportCropTop(prepared, i + 1, nextOverlap);
            // ponytail: screenshot viewports often cut through a chat text row; replace the bottom strip
            // from the next frame and snap the seam to a nearby low-ink row. A text-line detector is the upgrade path.
            guards[i] = nextOverlap - cropTop;
        }
        return guards;
    }

    private static int safeViewportCropTop(PreparedFrames prepared, int seamIndex, int overlap) {
        if (overlap <= 0) {
            return 0;
        }
        int edgeMargin = Math.min(VIEWPORT_BOTTOM_GUARD_PX, overlap / 4);
        int targetCropTop = overlap / 2;
        int from = edgeMargin;
        int to = Math.max(from, overlap - edgeMargin);
        int bestCropTop = targetCropTop;
        int bestScore = Integer.MAX_VALUE;
        for (int cropTop = from; cropTop <= to; cropTop += 8) {
            int score = viewportCutScore(prepared, seamIndex, overlap, cropTop)
                    + Math.abs(cropTop - targetCropTop) / 8;
            if (score < bestScore) {
                bestScore = score;
                bestCropTop = cropTop;
            }
        }
        int denseFrom = clamp(bestCropTop - 8, from, to);
        int denseTo = clamp(bestCropTop + 8, from, to);
        for (int cropTop = denseFrom; cropTop <= denseTo; cropTop++) {
            int score = viewportCutScore(prepared, seamIndex, overlap, cropTop)
                    + Math.abs(cropTop - targetCropTop) / 8;
            if (score < bestScore) {
                bestScore = score;
                bestCropTop = cropTop;
            }
        }
        return bestCropTop;
    }

    private static int viewportCutScore(PreparedFrames prepared, int seamIndex, int overlap, int cropTop) {
        Rect previous = prepared.matchRects[seamIndex - 1];
        Rect next = prepared.matchRects[seamIndex];
        int previousY = previous.bottom - overlap + cropTop;
        int nextY = next.top + cropTop;
        Bitmap previousFrame = prepared.frames.get(seamIndex - 1);
        Bitmap nextFrame = prepared.frames.get(seamIndex);
        return rowAlignmentScore(previousFrame, previous, previousY, nextFrame, next, nextY, prepared.width) * 4
                + rowInkScore(previousFrame, prepared.width, previousY)
                + rowInkScore(nextFrame, prepared.width, nextY);
    }

    private static int rowAlignmentScore(
            Bitmap previous,
            Rect previousContent,
            int previousY,
            Bitmap next,
            Rect nextContent,
            int nextY,
            int width) {
        int columns = 32;
        int total = 0;
        int samples = 0;
        for (int dy = -10; dy <= 10; dy += 2) {
            int py = clamp(previousY + dy, previousContent.top, previousContent.bottom - 1);
            int ny = clamp(nextY + dy, nextContent.top, nextContent.bottom - 1);
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int colorDiff = colorDistance(previous.getPixel(x, py), next.getPixel(x, ny));
                int edgeDiff = Math.abs(pixelEdge(previous, x, py) - pixelEdge(next, x, ny));
                total += (colorDiff * 45 + edgeDiff * 55) / 100;
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static int rowInkScore(Bitmap bitmap, int width, int centerY) {
        int columns = 32;
        int score = 0;
        int samples = 0;
        for (int dy = -4; dy <= 4; dy++) {
            int y = clamp(centerY + dy, 0, bitmap.getHeight() - 1);
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int gray = gray(bitmap.getPixel(x, y));
                int ink = Math.max(0, 170 - gray);
                int edge = pixelEdge(bitmap, x, y);
                score += ink + Math.min(48, edge);
                samples++;
            }
        }
        return score / Math.max(1, samples);
    }

    private static int adjustedCropTop(int overlap, int previousBottomGuard) {
        return Math.max(0, overlap - previousBottomGuard);
    }

    private static int segmentHeight(PreparedFrames prepared, int index, int cropTop, int cropBottom) {
        Rect content = prepared.contentRects[index];
        int top = clamp(content.top + cropTop, content.top, content.bottom);
        int bottom = clamp(content.bottom - cropBottom, top, content.bottom);
        return bottom - top;
    }

    private static void scrubRightEdgeIndicators(Bitmap output, int top, int bottom) {
        int width = output.getWidth();
        int scrubWidth = rightEdgeScrubWidth(width);
        int startX = Math.max(1, width - scrubWidth);
        int referenceX = Math.max(0, startX - 3);
        int safeTop = Math.max(0, top);
        int safeBottom = Math.min(output.getHeight(), bottom);
        for (int y = safeTop; y < safeBottom; y++) {
            int reference = output.getPixel(referenceX, y);
            int referenceGray = gray(reference);
            for (int x = startX; x < width; x++) {
                int color = output.getPixel(x, y);
                int currentGray = gray(color);
                boolean darkFloatingIndicator = referenceGray > 80
                        && currentGray + 16 < referenceGray
                        && colorDistance(color, reference) >= EDGE_INDICATOR_CONTRAST;
                if (darkFloatingIndicator) {
                    // ponytail: Android/WeChat scroll thumbs live in the last few edge pixels; full semantic scrollbar removal can use Accessibility bounds later.
                    output.setPixel(x, y, reference);
                }
            }
        }
    }

    private static int rightEdgeScrubWidth(int width) {
        return clamp(width / 45, EDGE_INDICATOR_MIN_SCRUB_PX, EDGE_INDICATOR_MAX_SCRUB_PX);
    }

    private static int maxOverlap(Rect previous, Rect next) {
        return Math.min(previous.height(), next.height());
    }

    private static int maxSearchOverlap(Rect previous, Rect next, boolean autoMode, int expectedOverlap) {
        // ponytail: hand swipes have no fixed distance; keep the full safe search range and use mode targets only as soft ranking priors.
        return Math.max(0, maxOverlap(previous, next) - MIN_NEW_CONTENT_AFTER_OVERLAP);
    }

    private static int estimateScrollDelta(PreparedFrames prepared, int seamIndex, int fullOverlap) {
        Bitmap previous = prepared.frames.get(seamIndex - 1);
        Bitmap next = prepared.frames.get(seamIndex);
        Rect previousContent = prepared.matchRects[seamIndex - 1];
        Rect nextContent = prepared.matchRects[seamIndex];
        int maxSearchOverlap = maxSearchOverlap(previousContent, nextContent, true, -1);
        int minOverlap = Math.min(
                Math.max(96, fullOverlap / AUTO_MIN_OVERLAP_DIVISOR),
                maxSearchOverlap);
        if (maxSearchOverlap <= minOverlap) {
            return 0;
        }

        int bestOverlap = minOverlap;
        int bestScore = Integer.MAX_VALUE;
        int coarseStep = maxSearchOverlap > 900 ? 32 : 20;
        for (int overlap = minOverlap; overlap <= maxSearchOverlap; overlap += coarseStep) {
            int score = scoreFastOverlap(previous, previousContent, next, nextContent, prepared.width, overlap);
            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlap;
            }
        }

        int from = clamp(bestOverlap - coarseStep, minOverlap, maxSearchOverlap);
        int to = clamp(bestOverlap + coarseStep, minOverlap, maxSearchOverlap);
        for (int overlap = from; overlap <= to; overlap += 4) {
            int score = scoreFastOverlap(previous, previousContent, next, nextContent, prepared.width, overlap);
            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlap;
            }
        }

        from = clamp(bestOverlap - 4, minOverlap, maxSearchOverlap);
        to = clamp(bestOverlap + 4, minOverlap, maxSearchOverlap);
        for (int overlap = from; overlap <= to; overlap++) {
            int score = scoreFastOverlap(previous, previousContent, next, nextContent, prepared.width, overlap);
            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlap;
            }
        }

        int delta = fullOverlap - bestOverlap;
        // ponytail: cheap translation estimate for apps that omit scroll deltas; use a feature pyramid if parallax feeds appear.
        return delta >= MIN_NEW_CONTENT_AFTER_OVERLAP ? delta : 0;
    }

    private static int scoreFastOverlap(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width,
            int overlap) {
        int rows = 10;
        int columns = 12;
        int total = 0;
        int samples = 0;
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * overlap / (rows + 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int colorScore = colorDistance(previous.getPixel(x, previousY), next.getPixel(x, nextY));
                int edgeScore = Math.abs(pixelEdge(previous, x, previousY) - pixelEdge(next, x, nextY));
                total += (colorScore * 45 + edgeScore * 55) / 100;
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static OverlapResult findOverlap(
            PreparedFrames prepared,
            int seamIndex,
            boolean autoMode,
            int expectedOverlap,
            boolean trustedExpectedOverlap,
            boolean allowExactExpectedOverlap) {
        Bitmap previous = prepared.frames.get(seamIndex - 1);
        Bitmap next = prepared.frames.get(seamIndex);
        Rect previousContent = prepared.matchRects[seamIndex - 1];
        Rect nextContent = prepared.matchRects[seamIndex];
        FrameFeatures previousFeatures = featuresFor(prepared, seamIndex - 1);
        FrameFeatures nextFeatures = featuresFor(prepared, seamIndex);
        int fullOverlap = maxOverlap(previousContent, nextContent);
        int maxSearchOverlap = maxSearchOverlap(previousContent, nextContent, autoMode, expectedOverlap);
        int minOverlap = Math.min(
                autoMode
                        ? Math.max(96, fullOverlap / AUTO_MIN_OVERLAP_DIVISOR)
                        : 96,
                maxSearchOverlap);
        // ponytail: automatic swipes intentionally retain at least one sixth of the viewport; true page jumps need tile capture or a retryable smaller gesture.
        // Untrusted deltas may nominate a peak, but only a verified visual translation may rank or confirm it.
        int nominationExpectedOverlap = expectedOverlap >= minOverlap && expectedOverlap <= maxSearchOverlap
                ? expectedOverlap
                : -1;
        int rankingExpectedOverlap = trustedExpectedOverlap
                && expectedOverlap >= minOverlap
                && expectedOverlap <= maxSearchOverlap
                ? expectedOverlap
                : -1;
        if (maxSearchOverlap <= minOverlap) {
            return new OverlapResult(0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0,
                    false, false, "可用内容太短，需手动确认接缝");
        }

        int samePositionScore = scoreAlignedContent(
                previous,
                previousContent,
                next,
                nextContent,
                prepared.width);
        if (isNearDuplicate(previous, next)
                && samePositionScore <= NO_MOVEMENT_SCORE_THRESHOLD
                && (expectedOverlap < 0
                        || fullOverlap - expectedOverlap < MIN_NEW_CONTENT_AFTER_OVERLAP)) {
            // ponytail: no-movement seams are full-overlap skips; revisit if frame ordering becomes editable.
            return new OverlapResult(
                    fullOverlap,
                    samePositionScore,
                    samePositionScore,
                    0,
                    true,
                    true,
                    "疑似重复帧或无位移，已自动跳过重叠部分");
        }

        // ponytail: estimated deltas do not get confidence bonuses or early exits; untrusted hints must pass
        // through the full search so chat transcripts keep every text row.
        if (trustedExpectedOverlap && expectedOverlap >= minOverlap && expectedOverlap <= maxSearchOverlap) {
            if (allowExactExpectedOverlap) {
                Candidate exact = scoreCandidate(
                        previous,
                        previousContent,
                        next,
                        nextContent,
                        previousFeatures,
                        nextFeatures,
                        prepared.width,
                        expectedOverlap,
                        true,
                        autoMode,
                        rankingExpectedOverlap);
                OverlapResult exactResult = candidateResult(
                        exact,
                        new Candidate[] {exact},
                        rankingExpectedOverlap,
                        fullOverlap,
                        true);
                if (exactResult.accepted && hasGuidedVisualEvidence(exact)) {
                    // ponytail: a reliable accessibility delta is an exact displacement hint; keep visual validation, then avoid a needless image-wide search.
                    return exactResult;
                }
            }
            Candidate[] guided = new Candidate[CANDIDATE_LIMIT];
            int guidedFrom = clamp(expectedOverlap - 48, minOverlap, maxSearchOverlap);
            int guidedTo = clamp(expectedOverlap + 48, minOverlap, maxSearchOverlap);
            for (int overlap = guidedFrom; overlap <= guidedTo; overlap += 4) {
                addCandidate(guided, scoreCandidate(
                        previous,
                        previousContent,
                        next,
                        nextContent,
                        previousFeatures,
                        nextFeatures,
                        prepared.width,
                        overlap,
                        true,
                        autoMode,
                        rankingExpectedOverlap), 24);
            }
            if (guided[0] != null) {
                int guidedPeak = guided[0].overlap;
                for (int overlap = Math.max(guidedFrom, guidedPeak - 3);
                        overlap <= Math.min(guidedTo, guidedPeak + 3);
                        overlap++) {
                    addCandidate(guided, scoreCandidate(
                            previous,
                            previousContent,
                            next,
                            nextContent,
                            previousFeatures,
                            nextFeatures,
                            prepared.width,
                            overlap,
                            true,
                            autoMode,
                            rankingExpectedOverlap), 24);
                }
                // ponytail: 4px guided scanning needs one dense local pass; pyramids are the upgrade if high-DPI text makes this hot.
            }
            OverlapResult guidedResult = candidateResult(
                    guided[0],
                    guided,
                    rankingExpectedOverlap,
                    fullOverlap,
                    true);
            if (guidedResult != null
                    && guidedResult.accepted
                    && (hasGuidedVisualEvidence(guided[0]) || !allowExactExpectedOverlap)) {
                // ponytail: the path-only API deliberately stays inside its +/-48px window once a
                // stable session trajectory asks for a second opinion; normal capture analysis still
                // falls through to the full search when local evidence is not independently strong.
                return guidedResult;
            }
        }

        Candidate[] coarse = new Candidate[CANDIDATE_LIMIT];
        int coarseStep = maxSearchOverlap > 900 ? 24 : 12;
        int peakSeparation = Math.max(48, fullOverlap / 32);
        if (nominationExpectedOverlap >= minOverlap && nominationExpectedOverlap <= maxSearchOverlap) {
            addCandidate(coarse, scoreCoarseCandidate(
                    previous,
                    previousContent,
                    next,
                    nextContent,
                    previousFeatures,
                    nextFeatures,
                    prepared.width,
                    nominationExpectedOverlap,
                    autoMode,
                    rankingExpectedOverlap), peakSeparation);
        }
        for (int overlap = minOverlap; overlap <= maxSearchOverlap; overlap += coarseStep) {
            addCandidate(coarse, scoreCoarseCandidate(
                    previous,
                    previousContent,
                    next,
                    nextContent,
                    previousFeatures,
                    nextFeatures,
                    prepared.width,
                    overlap,
                    autoMode,
                    rankingExpectedOverlap), peakSeparation);
        }

        Candidate[] refined = new Candidate[CANDIDATE_LIMIT];
        for (int candidateIndex = 0; candidateIndex < coarse.length; candidateIndex++) {
            Candidate candidate = coarse[candidateIndex];
            if (candidate == null) {
                continue;
            }
            int refineRadius = Math.max(coarseStep * 2, peakSeparation / 2);
            int from = clamp(candidate.overlap - refineRadius, minOverlap, maxSearchOverlap);
            int to = clamp(candidate.overlap + refineRadius, minOverlap, maxSearchOverlap);
            Candidate localBest = null;
            for (int overlap = from; overlap <= to; overlap += 4) {
                Candidate scored = scoreCandidate(
                        previous,
                        previousContent,
                        next,
                        nextContent,
                        previousFeatures,
                        nextFeatures,
                        prepared.width,
                        overlap,
                        true,
                        autoMode,
                        rankingExpectedOverlap);
                if (localBest == null || isBetterCandidate(scored, localBest)) {
                    localBest = scored;
                }
            }
            int denseFrom = clamp(localBest.overlap - 4, minOverlap, maxSearchOverlap);
            int denseTo = clamp(localBest.overlap + 4, minOverlap, maxSearchOverlap);
            for (int overlap = denseFrom; overlap <= denseTo; overlap++) {
                Candidate scored = scoreCandidate(
                        previous,
                        previousContent,
                        next,
                        nextContent,
                        previousFeatures,
                        nextFeatures,
                        prepared.width,
                        overlap,
                        true,
                        autoMode,
                        rankingExpectedOverlap);
                if (isBetterCandidate(scored, localBest)) {
                    localBest = scored;
                }
            }
            if (localBest != null) {
                addCandidate(refined, localBest, Math.max(24, peakSeparation / 2));
            }
        }

        Candidate best = refined[0];
        if (best == null) {
            return new OverlapResult(0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0,
                    false, false, "没有找到可用接缝候选");
        }
        return candidateResult(
                best,
                refined,
                rankingExpectedOverlap,
                fullOverlap,
                trustedExpectedOverlap);
    }

    private static boolean hasGuidedVisualEvidence(Candidate candidate) {
        return candidate != null
                && (candidate.score <= OVERLAP_STRONG_SCORE_THRESHOLD
                        || candidate.consensusScore >= BLOCK_CONSENSUS_TARGET)
                && candidate.supportBlocks >= 6
                && candidate.seamScore <= SEAM_SCORE_THRESHOLD;
    }

    private static OverlapResult candidateResult(
            Candidate best,
            Candidate[] candidates,
            int expectedOverlap,
            int fullOverlap,
            boolean expectedCanConfirm) {
        if (best == null) {
            return null;
        }
        Candidate second = null;
        for (Candidate candidate : candidates) {
            if (candidate != null
                    && candidate != best
                    && (second == null || candidate.score < second.score)) {
                second = candidate;
            }
        }
        int expectedTolerance = expectedOverlap < 0
                ? 0
                : Math.max(24, Math.min(48, fullOverlap / EXPECTED_OVERLAP_TOLERANCE_DIVISOR));
        boolean expectedSupport = expectedCanConfirm
                && expectedOverlap >= 0
                && Math.abs(best.overlap - expectedOverlap) <= expectedTolerance;
        // ponytail: near-perfect block agreement may beat a repeated chat-shaped decoy; equal periodic
        // patterns still fail closed, while a learned matcher is the upgrade path for scaled content.
        boolean decisiveVisualLead = second != null
                && second.score - best.score >= OVERLAP_VISUAL_LEAD_DELTA
                && best.consensusScore >= BLOCK_CONSENSUS_STRONG
                && best.consensusScore - second.consensusScore >= BLOCK_CONSENSUS_LEAD;
        boolean confident = second == null
                || second.score - best.score >= OVERLAP_CONFIDENCE_DELTA
                || decisiveVisualLead
                || expectedSupport;
        if (!confident && second != null) {
            Log.i(TAG, "ambiguous bestOverlap=" + best.overlap
                    + " bestScore=" + best.score
                    + " bestConsensus=" + best.consensusScore
                    + " bestSeam=" + best.seamScore
                    + " bestColor=" + best.colorScore
                    + " bestEdge=" + best.edgeScore
                    + " secondOverlap=" + second.overlap
                    + " secondScore=" + second.score
                    + " secondConsensus=" + second.consensusScore
                    + " secondSeam=" + second.seamScore
                    + " secondColor=" + second.colorScore
                    + " secondEdge=" + second.edgeScore
                    + " distance=" + Math.abs(best.overlap - second.overlap));
        }
        boolean strongEvidence = best.score <= OVERLAP_STRONG_SCORE_THRESHOLD
                || best.consensusScore >= BLOCK_CONSENSUS_TARGET;
        boolean untrustedSearch = !expectedCanConfirm;
        boolean seamLooksWrong = best.seamScore > SEAM_SCORE_THRESHOLD
                && (untrustedSearch
                        || (!strongEvidence && best.consensusScore < BLOCK_CONSENSUS_TARGET));
        boolean accepted = best.score <= OVERLAP_SCORE_THRESHOLD
                && !seamLooksWrong
                && (best.textureScore >= LOW_TEXTURE_THRESHOLD
                        || best.consensusScore >= BLOCK_CONSENSUS_TARGET)
                && best.consensusScore >= BLOCK_CONSENSUS_ACCEPT_THRESHOLD
                && confident;
        return new OverlapResult(
                best.overlap,
                best.score,
                best.seamScore,
                best.consensusScore,
                accepted,
                false,
                seamMessage(best, confident));
    }

    private static Candidate scoreCandidate(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            FrameFeatures previousFeatures,
            FrameFeatures nextFeatures,
            int width,
            int overlap,
            boolean detailed,
            boolean autoMode,
            int expectedOverlap) {
        int rows = detailed ? Math.max(8, Math.min(32, overlap / 10)) : Math.max(5, Math.min(20, overlap / 18));
        int columns = detailed ? 24 : 16;
        int colorScore = scoreMaskedOverlap(previous, previousContent, next, nextContent, width, overlap, rows, columns);
        int edgeScore = scoreOverlapEdges(previous, previousContent, next, nextContent, width, overlap, rows, columns);
        int seamScore = scoreSeamBoundary(previous, previousContent, next, nextContent, width, overlap);
        int templateScore = scoreTexturedTemplate(previous, previousContent, next, nextContent, width, overlap, detailed);
        int textureScore = Math.min(
                scoreOverlapTexture(previous, previousContent.bottom - overlap, overlap, width, rows, columns),
                scoreOverlapTexture(next, nextContent.top, overlap, width, rows, columns));
        BlockConsensus consensus = scoreBlockConsensus(
                previousContent,
                nextContent,
                previousFeatures,
                nextFeatures,
                overlap,
                detailed);
        int lowTexturePenalty = textureScore < LOW_TEXTURE_THRESHOLD && consensus.supportBlocks < 8
                ? (LOW_TEXTURE_THRESHOLD - textureScore) * 10
                : 0;
        int contentHeight = Math.min(previousContent.height(), nextContent.height());
        int naturalMinOverlap = contentHeight / NATURAL_OVERLAP_MIN_DIVISOR;
        int naturalMaxOverlap = contentHeight
                * (autoMode ? NATURAL_OVERLAP_MAX_NUMERATOR : MANUAL_OVERLAP_MAX_NUMERATOR)
                / (autoMode ? NATURAL_OVERLAP_MAX_DENOMINATOR : MANUAL_OVERLAP_MAX_DENOMINATOR);
        int naturalTargetOverlap;
        if (autoMode) {
            naturalTargetOverlap = contentHeight
                    * NATURAL_OVERLAP_TARGET_NUMERATOR
                    / NATURAL_OVERLAP_TARGET_DENOMINATOR;
        } else {
            naturalTargetOverlap = contentHeight
                    * MANUAL_OVERLAP_TARGET_NUMERATOR
                    / MANUAL_OVERLAP_TARGET_DENOMINATOR;
        }
        int targetTolerance = Math.max(
                160,
                contentHeight / NATURAL_OVERLAP_TARGET_TOLERANCE_DIVISOR);
        int targetDistance = Math.max(0, Math.abs(overlap - naturalTargetOverlap) - targetTolerance);
        // ponytail: block consensus is the main matcher; color/template/anchor terms only break ties.
        int smallOverlapPenalty = overlap < naturalMinOverlap ? (naturalMinOverlap - overlap) / 6 : 0;
        int largeOverlapPenalty = overlap > naturalMaxOverlap ? (overlap - naturalMaxOverlap) / 6 : 0;
        int targetOverlapPenalty = targetDistance / 8;
        int colorPenalty = Math.max(0, colorScore - 16) / 8;
        int edgePenalty = Math.max(0, edgeScore - 12) / 5;
        int templatePenalty = Math.max(0, templateScore - 18) / 3;
        int anchorPenalty = Math.max(0, seamScore - 18) / 3;
        int visualScore = consensus.matchScore
                + colorPenalty
                + edgePenalty
                + templatePenalty
                + anchorPenalty
                + lowTexturePenalty
                + consensus.penalty;
        int rankPenalty;
        if (expectedOverlap >= 0) {
            int expectedTolerance = Math.max(
                    24,
                    Math.min(
                            48,
                            Math.min(previousContent.height(), nextContent.height())
                                    / EXPECTED_OVERLAP_TOLERANCE_DIVISOR));
            int distance = Math.max(0, Math.abs(overlap - expectedOverlap) - expectedTolerance);
            rankPenalty = Math.min(VISUAL_RANK_PRIOR_MAX, distance / 4);
        } else {
            rankPenalty = Math.min(
                    VISUAL_RANK_PRIOR_MAX,
                    smallOverlapPenalty + largeOverlapPenalty + targetOverlapPenalty);
        }
        return new Candidate(overlap, visualScore, visualScore + rankPenalty,
                colorScore, edgeScore, seamScore, textureScore,
                consensus.consensusScore, consensus.supportBlocks);
    }

    private static Candidate scoreCoarseCandidate(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            FrameFeatures previousFeatures,
            FrameFeatures nextFeatures,
            int width,
            int overlap,
            boolean autoMode,
            int expectedOverlap) {
        int fastScore = scoreFastOverlap(
                previous, previousContent, next, nextContent, width, overlap);
        int templateScore = scoreTexturedTranslation(
                previousContent,
                nextContent,
                previousFeatures,
                nextFeatures,
                overlap);
        int visualScore = (templateScore * 4 + fastScore) / 5;
        int contentHeight = Math.min(previousContent.height(), nextContent.height());
        int rankPenalty;
        if (expectedOverlap >= 0) {
            int tolerance = Math.max(24, Math.min(48, contentHeight / EXPECTED_OVERLAP_TOLERANCE_DIVISOR));
            rankPenalty = Math.min(
                    VISUAL_RANK_PRIOR_MAX,
                    Math.max(0, Math.abs(overlap - expectedOverlap) - tolerance) / 4);
        } else {
            int minOverlap = contentHeight / NATURAL_OVERLAP_MIN_DIVISOR;
            int maxOverlap = contentHeight
                    * (autoMode ? NATURAL_OVERLAP_MAX_NUMERATOR : MANUAL_OVERLAP_MAX_NUMERATOR)
                    / (autoMode ? NATURAL_OVERLAP_MAX_DENOMINATOR : MANUAL_OVERLAP_MAX_DENOMINATOR);
            int target = contentHeight
                    * (autoMode ? NATURAL_OVERLAP_TARGET_NUMERATOR : MANUAL_OVERLAP_TARGET_NUMERATOR)
                    / (autoMode ? NATURAL_OVERLAP_TARGET_DENOMINATOR : MANUAL_OVERLAP_TARGET_DENOMINATOR);
            rankPenalty = Math.min(
                    VISUAL_RANK_PRIOR_MAX,
                    Math.max(0, minOverlap - overlap) / 12
                            + Math.max(0, overlap - maxOverlap) / 12
                            + Math.abs(overlap - target) / 48);
        }
        // ponytail: this is a dependency-free TM_SQDIFF-style nomination pass; OpenCV/NCC is the upgrade path for scaled or parallax content.
        return new Candidate(
                overlap,
                visualScore,
                visualScore + rankPenalty,
                visualScore,
                visualScore,
                visualScore,
                0,
                0,
                0);
    }

    private static int scoreTexturedTranslation(
            Rect previousContent,
            Rect nextContent,
            FrameFeatures previous,
            FrameFeatures next,
            int overlap) {
        int availableColumns = Math.min(previous.columns, next.columns);
        int sampledRows = Math.max(20, Math.min(64, overlap / 8));
        int sampledColumns = Math.max(1, Math.min(48, availableColumns));
        long weightedDiff = 0L;
        long totalWeight = 0L;
        int texturedRows = 0;
        for (int row = 0; row < sampledRows; row++) {
            int localY = (row + 1) * overlap / (sampledRows + 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            int rowSupport = 0;
            for (int column = 0; column < sampledColumns; column++) {
                int featureColumn = Math.min(
                        availableColumns - 1,
                        (column + 1) * availableColumns / (sampledColumns + 1));
                int previousEdge = previous.edgeAt(previousY, featureColumn);
                int nextEdge = next.edgeAt(nextY, featureColumn);
                int texture = Math.max(previousEdge, nextEdge);
                if (texture < 3) {
                    continue;
                }
                int weight = Math.min(48, Math.max(4, texture));
                int diff = (Math.abs(previousEdge - nextEdge) * 62
                        + Math.abs(previous.grayAt(previousY, featureColumn)
                                - next.grayAt(nextY, featureColumn)) * 38) / 100;
                weightedDiff += (long) diff * weight;
                totalWeight += weight;
                rowSupport++;
            }
            if (rowSupport >= Math.max(3, sampledColumns / 8)) {
                texturedRows++;
            }
        }
        if (totalWeight == 0L) {
            return BLOCK_CONSENSUS_TARGET * 2;
        }
        int minimumRows = Math.max(6, sampledRows / 5);
        int supportPenalty = texturedRows >= minimumRows ? 0 : (minimumRows - texturedRows) * 4;
        return (int) (weightedDiff / totalWeight) + supportPenalty;
    }

    private static BlockConsensus scoreBlockConsensus(
            Rect previousContent,
            Rect nextContent,
            FrameFeatures previous,
            FrameFeatures next,
            int overlap,
            boolean detailed) {
        int columns = Math.min(previous.columns, next.columns);
        int width = Math.min(previous.width, next.width);
        int margin = Math.max(0, width / ANCHOR_MARGIN_DIVISOR);
        int usableWidth = Math.max(1, width - margin * 2);
        int blockWidth = Math.min(CONSENSUS_BLOCK_WIDTH, usableWidth);
        int blockHeight = Math.min(CONSENSUS_BLOCK_HEIGHT, overlap);
        if (blockWidth < 24 || blockHeight < 24) {
            return new BlockConsensus(0, 0, BLOCK_CONSENSUS_TARGET, BLOCK_CONSENSUS_TARGET);
        }

        long weightedVote = 0L;
        long weightedDiff = 0L;
        long totalWeight = 0L;
        int supportBlocks = 0;
        int allBlocks = 0;
        int possibleRows = Math.max(1, (overlap - blockHeight) / blockHeight + 1);
        // ponytail: full row consensus is slow, but row caps skipped real content; cached image pyramids/NCC are the upgrade path.
        int maxRows = detailed ? CONSENSUS_MAX_BLOCK_ROWS_DETAILED : CONSENSUS_MAX_BLOCK_ROWS_COARSE;
        int sampledRows = maxRows <= 0 ? possibleRows : Math.min(possibleRows, maxRows);
        for (int row = 0; row < sampledRows; row++) {
            int localY = sampledRows == 1 ? 0 : row * (overlap - blockHeight) / (sampledRows - 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            for (int x = margin; x + blockWidth <= width - margin; x += blockWidth) {
                allBlocks++;
                ConsensusBlock block = scoreConsensusBlock(
                        previous,
                        next,
                        (x - margin) / FEATURE_X_STEP,
                        previousY,
                        nextY,
                        blockWidth,
                        blockHeight);
                if (block.texture < 3) {
                    continue;
                }
                int weight = Math.min(block.texture, 42);
                int vote = likelihood(block.diff);
                weightedVote += (long) vote * weight;
                weightedDiff += (long) block.diff * weight;
                totalWeight += weight;
                supportBlocks++;
            }
        }
        if (totalWeight <= 0L) {
            return new BlockConsensus(0, supportBlocks, BLOCK_CONSENSUS_TARGET, BLOCK_CONSENSUS_TARGET);
        }

        int consensusScore = (int) (weightedVote / totalWeight);
        int meanDiff = (int) (weightedDiff / totalWeight);
        int supportPenalty = supportBlocks >= 8 ? 0 : (8 - supportBlocks) * 8;
        int ratioPenalty = allBlocks == 0 || supportBlocks * 100 / allBlocks >= 18
                ? 0
                : (18 - supportBlocks * 100 / Math.max(1, allBlocks)) / 2;
        int consensusPenalty = consensusScore >= BLOCK_CONSENSUS_TARGET
                ? -(consensusScore - BLOCK_CONSENSUS_TARGET) / 5
                : (BLOCK_CONSENSUS_TARGET - consensusScore) / 2;
        int matchScore = meanDiff - consensusScore * 18 / 100 + supportPenalty + ratioPenalty;
        return new BlockConsensus(consensusScore, supportBlocks, consensusPenalty, matchScore);
    }

    private static ConsensusBlock scoreConsensusBlock(
            FrameFeatures previous,
            FrameFeatures next,
            int startColumn,
            int previousY,
            int nextY,
            int blockWidth,
            int blockHeight) {
        int textureTotal = 0;
        int diffTotal = 0;
        int samples = 0;
        for (int y = 0; y < blockHeight; y += CONSENSUS_SAMPLE_STEP) {
            int py = previousY + y;
            int ny = nextY + y;
            for (int localX = 0; localX < blockWidth; localX += CONSENSUS_SAMPLE_STEP) {
                int column = startColumn + localX / FEATURE_X_STEP;
                int previousGray = previous.grayAt(py, column);
                int nextGray = next.grayAt(ny, column);
                int previousEdge = previous.edgeAt(py, column);
                int nextEdge = next.edgeAt(ny, column);
                int texture = Math.max(previousEdge, nextEdge);
                int diff = (Math.abs(previousEdge - nextEdge) * 62
                        + Math.abs(previousGray - nextGray) * 38) / 100;
                textureTotal += texture;
                diffTotal += diff;
                samples++;
            }
        }
        int texture = textureTotal / Math.max(1, samples);
        int diff = diffTotal / Math.max(1, samples);
        return new ConsensusBlock(texture, diff);
    }

    private static int likelihood(int diff) {
        if (diff <= 6) {
            return 100;
        }
        if (diff >= 36) {
            return 0;
        }
        int distance = diff - 6;
        return Math.max(0, 100 - distance * distance / 9);
    }

    private static int scoreMaskedOverlap(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width,
            int overlap,
            int rows,
            int columns) {
        int total = 0;
        int samples = 0;
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * overlap / (rows + 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                total += colorDistance(previous.getPixel(x, previousY), next.getPixel(x, nextY));
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static int scoreOverlapEdges(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width,
            int overlap,
            int rows,
            int columns) {
        int total = 0;
        int samples = 0;
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * overlap / (rows + 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            int previousPrevY = Math.max(previousContent.top, previousY - 1);
            int nextPrevY = Math.max(nextContent.top, nextY - 1);
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int previousEdge = Math.abs(gray(previous.getPixel(x, previousY)) - gray(previous.getPixel(x, previousPrevY)));
                int nextEdge = Math.abs(gray(next.getPixel(x, nextY)) - gray(next.getPixel(x, nextPrevY)));
                total += Math.abs(previousEdge - nextEdge);
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static int scoreSeamBoundary(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width,
            int overlap) {
        int anchorHeight = Math.min(56, Math.min(overlap, previousContent.height()));
        int nextStart = nextContent.top + overlap - anchorHeight;
        if (anchorHeight <= 0 || nextStart < nextContent.top || nextStart + anchorHeight > nextContent.bottom) {
            return Integer.MAX_VALUE / 4;
        }
        int rows = Math.max(4, Math.min(12, anchorHeight / 4));
        int columns = 24;
        int total = 0;
        int samples = 0;
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * anchorHeight / (rows + 1);
            int previousY = previousContent.bottom - anchorHeight + localY;
            int nextY = nextStart + localY;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int previousColor = previous.getPixel(x, previousY);
                int nextColor = next.getPixel(x, nextY);
                int previousEdge = pixelEdge(previous, x, previousY);
                int nextEdge = pixelEdge(next, x, nextY);
                total += (Math.abs(gray(previousColor) - gray(nextColor)) * 45
                        + Math.abs(previousEdge - nextEdge) * 55) / 100;
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static int scoreOverlapTexture(
            Bitmap bitmap,
            int startY,
            int height,
            int width,
            int rows,
            int columns) {
        int total = 0;
        int samples = 0;
        int bottom = Math.min(bitmap.getHeight() - 1, startY + height - 1);
        int top = Math.max(0, startY);
        if (bottom <= top) {
            return 0;
        }
        for (int row = 0; row < rows; row++) {
            int y = top + (row + 1) * (bottom - top) / (rows + 1);
            int previousGray = -1;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int gray = gray(bitmap.getPixel(x, y));
                if (previousGray >= 0) {
                    total += Math.abs(gray - previousGray);
                    samples++;
                }
                if (y > top) {
                    total += Math.abs(gray - gray(bitmap.getPixel(x, y - 1)));
                    samples++;
                }
                previousGray = gray;
            }
        }
        return total / Math.max(1, samples);
    }

    private static int scoreTexturedTemplate(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width,
            int overlap,
            boolean detailed) {
        int rows = detailed ? Math.max(12, Math.min(36, overlap / 8)) : Math.max(8, Math.min(20, overlap / 16));
        int columns = detailed ? 28 : 18;
        long weightedDiff = 0L;
        long totalWeight = 0L;
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * overlap / (rows + 1);
            int previousY = previousContent.bottom - overlap + localY;
            int nextY = nextContent.top + localY;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                int previousColor = previous.getPixel(x, previousY);
                int nextColor = next.getPixel(x, nextY);
                int previousEdge = pixelEdge(previous, x, previousY);
                int nextEdge = pixelEdge(next, x, nextY);
                int texture = Math.max(previousEdge, nextEdge);
                if (texture < 3) {
                    continue;
                }
                int diff = (Math.abs(gray(previousColor) - gray(nextColor)) * 45
                        + Math.abs(previousEdge - nextEdge) * 55) / 100;
                int weight = Math.min(48, texture);
                weightedDiff += (long) diff * weight;
                totalWeight += weight;
            }
        }
        if (totalWeight < TEXTURE_TEMPLATE_MIN_WEIGHT) {
            return 72;
        }
        // ponytail: this is a lightweight template-match substitute; native NCC/OpenCV can replace it if we later add image libs.
        return (int) (weightedDiff / totalWeight);
    }

    private static int scoreAlignedContent(
            Bitmap previous,
            Rect previousContent,
            Bitmap next,
            Rect nextContent,
            int width) {
        int rows = 12;
        int columns = 12;
        int total = 0;
        int samples = 0;
        int comparableHeight = Math.min(previousContent.height(), nextContent.height());
        for (int row = 0; row < rows; row++) {
            int localY = (row + 1) * comparableHeight / (rows + 1);
            int previousY = previousContent.top + localY;
            int nextY = nextContent.top + localY;
            for (int column = 0; column < columns; column++) {
                int x = sampleX(width, column, columns);
                total += colorDistance(previous.getPixel(x, previousY), next.getPixel(x, nextY));
                samples++;
            }
        }
        return total / Math.max(1, samples);
    }

    private static void addCandidate(Candidate[] candidates, Candidate candidate, int minSeparation) {
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] != null
                    && Math.abs(candidates[i].overlap - candidate.overlap) < minSeparation) {
                if (isBetterCandidate(candidate, candidates[i])) {
                    candidates[i] = candidate;
                    sortCandidates(candidates);
                }
                return;
            }
        }
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] == null || isBetterCandidate(candidate, candidates[i])) {
                for (int j = candidates.length - 1; j > i; j--) {
                    candidates[j] = candidates[j - 1];
                }
                candidates[i] = candidate;
                return;
            }
        }
    }

    private static void sortCandidates(Candidate[] candidates) {
        for (int i = 1; i < candidates.length; i++) {
            Candidate candidate = candidates[i];
            int j = i - 1;
            while (j >= 0 && candidate != null
                    && (candidates[j] == null || isBetterCandidate(candidate, candidates[j]))) {
                candidates[j + 1] = candidates[j];
                j--;
            }
            candidates[j + 1] = candidate;
        }
    }

    private static boolean isBetterCandidate(Candidate candidate, Candidate current) {
        return candidate.score < current.score
                || (candidate.score == current.score && candidate.rankScore < current.rankScore);
    }

    private static String seamMessage(Candidate best, boolean confident) {
        if (best.textureScore < LOW_TEXTURE_THRESHOLD
                && best.consensusScore < BLOCK_CONSENSUS_TARGET) {
            return "重叠区纹理过少，已转入手动接缝";
        }
        if (best.consensusScore < BLOCK_CONSENSUS_ACCEPT_THRESHOLD) {
            return "重叠块共识不足，已转入手动接缝";
        }
        boolean strongEvidence = best.score <= OVERLAP_STRONG_SCORE_THRESHOLD
                || best.consensusScore >= BLOCK_CONSENSUS_TARGET;
        if (best.seamScore > SEAM_SCORE_THRESHOLD && !strongEvidence) {
            return "重叠锚点不稳定，已转入手动接缝";
        }
        if (best.score > OVERLAP_SCORE_THRESHOLD) {
            return "候选重叠评分过高，已转入手动接缝";
        }
        if (!confident) {
            return "多个候选接近，已转入手动接缝";
        }
        return "自动接缝通过";
    }

    private static int colorDistance(int first, int second) {
        int red = Math.abs(((first >> 16) & 0xff) - ((second >> 16) & 0xff));
        int green = Math.abs(((first >> 8) & 0xff) - ((second >> 8) & 0xff));
        int blue = Math.abs((first & 0xff) - (second & 0xff));
        return (red + green + blue) / 3;
    }

    private static int sampleX(int width, int column, int columns) {
        // ponytail: side anchors such as list numbers and avatars disambiguate repeated rows; node-aware anchors are the upgrade path.
        int margin = Math.max(0, width / ANCHOR_MARGIN_DIVISOR);
        int usableWidth = Math.max(1, width - margin * 2);
        return Math.min(width - 1, margin + (column + 1) * usableWidth / (columns + 1));
    }

    private static int gray(int color) {
        return (((color >> 16) & 0xff) * 30 + ((color >> 8) & 0xff) * 59 + (color & 0xff) * 11) / 100;
    }

    private static int pixelEdge(Bitmap bitmap, int x, int y) {
        int current = gray(bitmap.getPixel(x, y));
        int left = gray(bitmap.getPixel(Math.max(0, x - 1), y));
        int up = gray(bitmap.getPixel(x, Math.max(0, y - 1)));
        return Math.abs(current - left) + Math.abs(current - up);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PreparedFrames prepareFrames(List<Bitmap> frames) {
        return prepareFrames(frames, -1, -1);
    }

    private static PreparedFrames prepareFrames(
            List<Bitmap> frames,
            int fixedStaticTop,
            int fixedStaticBottom) {
        int width = minWidth(frames);
        Rect[] contentRects = new Rect[frames.size()];
        Rect[] matchRects = new Rect[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            contentRects[i] = new Rect(0, 0, width, frames.get(i).getHeight());
            matchRects[i] = new Rect(contentRects[i]);
        }
        if (frames.size() < 2) {
            return new PreparedFrames(frames, contentRects, matchRects, width);
        }

        boolean useFixedInsets = fixedStaticTop >= 0 && fixedStaticBottom >= 0;
        int staticTop = useFixedInsets
                ? fixedStaticTop
                : detectStaticEdge(frames, width, true);
        int staticBottom = useFixedInsets
                ? fixedStaticBottom
                : detectStaticEdge(frames, width, false);
        for (int i = 0; i < frames.size(); i++) {
            Bitmap frame = frames.get(i);
            int maxInset = Math.min(STATIC_EDGE_MAX_PX, Math.max(0, frame.getHeight() / 4));
            int matchTop = clamp(staticTop, 0, maxInset);
            int matchBottom = frame.getHeight() - clamp(staticBottom, 0, maxInset);
            int minContent = Math.max(160, frame.getHeight() / 3);
            if (matchBottom - matchTop < minContent) {
                matchTop = 0;
                matchBottom = frame.getHeight();
            }
            matchRects[i] = new Rect(0, matchTop, width, matchBottom);
            int outputTop = i == 0 ? 0 : matchTop;
            int outputBottom = i == frames.size() - 1 ? frame.getHeight() : matchBottom;
            contentRects[i] = new Rect(0, outputTop, width, outputBottom);
        }
        return new PreparedFrames(frames, contentRects, matchRects, width);
    }

    private static FrameFeatures featuresFor(PreparedFrames prepared, int index) {
        FrameFeatures features = prepared.features[index];
        if (features == null) {
            features = buildFeatures(prepared.frames.get(index), prepared.width);
            prepared.features[index] = features;
        }
        return features;
    }

    private static FrameFeatures buildFeatures(Bitmap bitmap, int width) {
        int margin = Math.max(0, width / ANCHOR_MARGIN_DIVISOR);
        int usableWidth = Math.max(1, width - margin * 2);
        int columns = Math.max(1, (usableWidth + FEATURE_X_STEP - 1) / FEATURE_X_STEP);
        int height = bitmap.getHeight();
        byte[] grayValues = new byte[height * columns];
        short[] edgeValues = new short[height * columns];
        int[] previousRow = new int[width];
        int[] currentRow = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(currentRow, 0, width, 0, y, width, 1);
            int rowOffset = y * columns;
            for (int column = 0; column < columns; column++) {
                int x = Math.min(width - 1, margin + column * FEATURE_X_STEP);
                int currentGray = gray(currentRow[x]);
                int leftGray = gray(currentRow[Math.max(0, x - 1)]);
                int upGray = y == 0 ? currentGray : gray(previousRow[x]);
                grayValues[rowOffset + column] = (byte) currentGray;
                edgeValues[rowOffset + column] = (short) (Math.abs(currentGray - leftGray)
                        + Math.abs(currentGray - upGray));
            }
            int[] swap = previousRow;
            previousRow = currentRow;
            currentRow = swap;
        }
        return new FrameFeatures(width, height, columns, grayValues, edgeValues);
    }

    private static int detectStaticEdge(List<Bitmap> frames, int width, boolean top) {
        int maxScan = STATIC_EDGE_MAX_PX;
        for (Bitmap frame : frames) {
            maxScan = Math.min(maxScan, Math.max(0, frame.getHeight() / 4));
        }
        int stable = 0;
        for (int offset = 0; offset < maxScan; offset += STATIC_EDGE_SCAN_STEP_PX) {
            int score = scoreStaticEdgeRow(frames, width, offset, top);
            if (score > STATIC_EDGE_SCORE_THRESHOLD) {
                break;
            }
            stable = offset + STATIC_EDGE_SCAN_STEP_PX;
        }
        if (stable < STATIC_EDGE_MIN_PX) {
            return 0;
        }
        if (stable >= maxScan) {
            return maxScan;
        }
        // ponytail: same-position static row detection is the fixed-chrome mask; node bounds can replace it if app-specific tuning becomes necessary.
        return Math.min(stable, maxScan);
    }

    private static int scoreStaticEdgeRow(List<Bitmap> frames, int width, int offset, boolean top) {
        int columns = 24;
        int[] pairScores = new int[Math.max(1, frames.size() - 1)];
        int pairCount = 0;
        for (int i = 1; i < frames.size(); i++) {
            Bitmap previous = frames.get(i - 1);
            Bitmap next = frames.get(i);
            int previousY = top ? offset : previous.getHeight() - 1 - offset;
            int nextY = top ? offset : next.getHeight() - 1 - offset;
            if (previousY < 0 || nextY < 0) {
                continue;
            }
            int[] columnDiffs = new int[columns];
            for (int column = 0; column < columns; column++) {
                int x = Math.min(width - 1, (column + 1) * width / (columns + 1));
                columnDiffs[column] = colorDistance(previous.getPixel(x, previousY), next.getPixel(x, nextY));
            }
            Arrays.sort(columnDiffs);
            int keptColumns = Math.max(1, columns - 3);
            int total = 0;
            for (int column = 0; column < keptColumns; column++) {
                total += columnDiffs[column];
            }
            pairScores[pairCount++] = total / keptColumns;
        }
        if (pairCount == 0) {
            return Integer.MAX_VALUE;
        }
        Arrays.sort(pairScores, 0, pairCount);
        int majorityIndex = Math.min(pairCount - 1, pairCount * 3 / 4);
        // ponytail: a 75th-percentile row score tolerates small clocks/cursors while still requiring most frame pairs to agree.
        return pairScores[majorityIndex];
    }

    private static Rect[] cloneRects(Rect[] rects) {
        Rect[] copy = new Rect[rects.length];
        for (int i = 0; i < rects.length; i++) {
            copy[i] = new Rect(rects[i]);
        }
        return copy;
    }

    private static final class PreparedFrames {
        final List<Bitmap> frames;
        final Rect[] contentRects;
        final Rect[] matchRects;
        final FrameFeatures[] features;
        final int width;

        PreparedFrames(List<Bitmap> frames, Rect[] contentRects, Rect[] matchRects, int width) {
            this.frames = frames;
            this.contentRects = contentRects;
            this.matchRects = matchRects;
            this.features = new FrameFeatures[frames.size()];
            this.width = width;
        }
    }

    private static final class FrameFeatures {
        final int width;
        final int height;
        final int columns;
        final byte[] grayValues;
        final short[] edgeValues;

        FrameFeatures(int width, int height, int columns, byte[] grayValues, short[] edgeValues) {
            this.width = width;
            this.height = height;
            this.columns = columns;
            this.grayValues = grayValues;
            this.edgeValues = edgeValues;
        }

        int grayAt(int y, int column) {
            return grayValues[y * columns + column] & 0xff;
        }

        int edgeAt(int y, int column) {
            return edgeValues[y * columns + column] & 0xffff;
        }
    }

    private static final class Candidate {
        final int overlap;
        final int score;
        final int rankScore;
        final int colorScore;
        final int edgeScore;
        final int seamScore;
        final int textureScore;
        final int consensusScore;
        final int supportBlocks;

        Candidate(int overlap, int score, int rankScore,
                int colorScore, int edgeScore, int seamScore, int textureScore,
                int consensusScore, int supportBlocks) {
            this.overlap = overlap;
            this.score = score;
            this.rankScore = rankScore;
            this.colorScore = colorScore;
            this.edgeScore = edgeScore;
            this.seamScore = seamScore;
            this.textureScore = textureScore;
            this.consensusScore = consensusScore;
            this.supportBlocks = supportBlocks;
        }
    }

    private static final class BlockConsensus {
        final int consensusScore;
        final int supportBlocks;
        final int penalty;
        final int matchScore;

        BlockConsensus(int consensusScore, int supportBlocks, int penalty, int matchScore) {
            this.consensusScore = consensusScore;
            this.supportBlocks = supportBlocks;
            this.penalty = penalty;
            this.matchScore = matchScore;
        }
    }

    private static final class ConsensusBlock {
        final int texture;
        final int diff;

        ConsensusBlock(int texture, int diff) {
            this.texture = texture;
            this.diff = diff;
        }
    }

    private static final class OverlapResult {
        final int overlap;
        final int score;
        final int seamScore;
        final int consensusScore;
        final boolean accepted;
        final boolean noMovement;
        final String message;

        OverlapResult(int overlap, int score, int seamScore, int consensusScore,
                boolean accepted, boolean noMovement, String message) {
            this.overlap = overlap;
            this.score = score;
            this.seamScore = seamScore;
            this.consensusScore = consensusScore;
            this.accepted = accepted;
            this.noMovement = noMovement;
            this.message = message;
        }
    }
}
