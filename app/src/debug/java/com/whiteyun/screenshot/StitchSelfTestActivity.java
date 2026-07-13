package com.whiteyun.screenshot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.File;

/** Debug-only, dependency-free device check for chat-style long screenshot stitching. */
public final class StitchSelfTestActivity extends Activity {
    private static final String TAG = "WhiteYunStitchSelfTest";
    private static final int WIDTH = 360;
    private static final int HEIGHT = 900;
    private static final int FIXED_TOP = 72;
    private static final int FIXED_BOTTOM = 88;
    private static final int CONTENT_HEIGHT = HEIGHT - FIXED_TOP - FIXED_BOTTOM;
    private static final int OVERLAP_TOLERANCE_PX = 2;
    private static final int EDGE_TOLERANCE_PX = 16;
    private static final int[] ACTUAL_SCROLL_DELTAS = {
            -1, 112, 340, 610, 300, 560, 280, 130, 360, 520, 118, 530
    };
    private static final int[] SCROLL_DELTA_HINTS = {
            -1, 112, 340, 610, -1, 560, 280, 130, 360, 520, 118, 530
    };
    private static final int[] UNTRUSTED_WEBVIEW_HINTS = {
            -1, 485, 279, 1831, 2516, 3393, 4246, 378, 5790, 6205, 8200, 9500
    };

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 32, 32, 32);
        status.setTextSize(18);
        status.setText("正在运行聊天拼接自测…");
        setContentView(status);

        if (getIntent() != null && getIntent().getBooleanExtra("replay_latest", false)) {
            status.setText("正在将最近失败的原始帧加入后台队列…");
            new Thread(this::enqueueLatestAutoReplay, "whiteyun-stitch-replay").start();
            return;
        }
        String requestedRunId = getIntent() == null ? null : getIntent().getStringExtra("run_id");
        String runId = requestedRunId == null || requestedRunId.isEmpty()
                ? Long.toString(System.currentTimeMillis())
                : requestedRunId;
        new Thread(() -> runSelfTest(runId), "whiteyun-stitch-self-test").start();
    }

    private void enqueueLatestAutoReplay() {
        try {
            File root = new File(getCacheDir(), "auto-frames");
            File[] sessions = root.listFiles(File::isDirectory);
            check(sessions != null && sessions.length > 0, "没有可恢复的自动长截图原始帧");
            File latest = sessions[0];
            for (File session : sessions) {
                if (session.lastModified() > latest.lastModified()) {
                    latest = session;
                }
            }
            File[] rawFrames = latest.listFiles(file -> file.getName().endsWith(".png"));
            check(rawFrames != null && rawFrames.length >= 2, "最近原始帧不完整");
            java.util.Arrays.sort(rawFrames, (left, right) -> left.getName().compareTo(right.getName()));
            ArrayList<File> frames = new ArrayList<>(rawFrames.length);
            for (File frame : rawFrames) {
                frames.add(frame);
            }
            StitchQueueStore.Job job = StitchQueueStore.enqueueAuto(
                    this,
                    CaptureService.MODE_AUTO,
                    latest,
                    frames,
                    new int[frames.size()]);
            StitchQueueService.start(this);
            String summary = "已将 " + frames.size() + " 帧加入后台拼接队列：" + job.id;
            Log.i(TAG, summary);
            showResult(summary, 0xff146c43);
        } catch (Throwable error) {
            String summary = "恢复队列失败：" + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, summary, error);
            showResult(summary, 0xffb42318);
        }
    }

    private void runSelfTest(String runId) {
        ArrayList<Bitmap> frames = new ArrayList<>();
        Bitmap conversation = null;
        Bitmap stitched = null;
        Bitmap pairStitched = null;
        Bitmap guardStitched = null;
        try {
            runDynamicDuplicateCheck();
            Rect topPreview = PreviewActivity.previewSourceWindow(
                    1440, 31681, 0, 0, 1440, 0, 2952);
            check(topPreview.top == 0 && topPreview.bottom == 2952,
                    "long preview must decode only the first viewport");
            Rect middlePreview = PreviewActivity.previewSourceWindow(
                    1440, 31681, 0, 0, 1440, 15000, 17952);
            check(middlePreview.top == 15000 && middlePreview.bottom == 17952,
                    "long preview viewport must follow scroll position");
            check(PreviewActivity.previewMaxSourceTileHeight(1440, 1) == 4096,
                    "1440px preview tile must stay below the Canvas allocation limit");
            check(PreviewActivity.previewFallbackSampleSize(1440, 31681) == 4,
                    "long preview fallback must stay below the whole-image allocation limit");
            Rect boundedPreview = PreviewActivity.boundedPreviewTileSource(
                    middlePreview, 0, 31681, 1440, 1);
            check(boundedPreview.contains(middlePreview) && boundedPreview.height() <= 4096,
                    "long preview tile must contain the viewport without decoding the full image");
            check(AutoScrollAccessibilityService.resolveVerticalScrollDelta(800, 1900, 1200, true) == 700,
                    "absolute scroll position must beat cumulative WebView deltas");
            check(AutoScrollAccessibilityService.resolveVerticalScrollDelta(800, -1, -1, true) == 800,
                    "reported delta fallback");
            check(AutoScrollAccessibilityService.resolveVerticalScrollDelta(800, 1200, 1200, true) == 0,
                    "no position movement");
            int[] recentOverlaps = {0, 1239, 1239, 1377, 1242, 1251, 0};
            boolean[] recentManual = new boolean[recentOverlaps.length];
            boolean[] recentNoMovement = new boolean[recentOverlaps.length];
            check(StreamingLongScreenshotStitcher.recentExpectedOverlap(
                            recentOverlaps, recentManual, recentNoMovement, 6) == 1242,
                    "recent overlap median");
            check(StreamingLongScreenshotStitcher.supportsRecentOverlap(
                            recentOverlaps, recentManual, recentNoMovement,
                            6, 1235, -14, 94, 10),
                    "stable recent path must confirm repeated-chat candidate");
            check(!StreamingLongScreenshotStitcher.supportsRecentOverlap(
                            recentOverlaps, recentManual, recentNoMovement,
                            6, 1235, -14, 89, 10),
                    "below-low-texture recent-path consensus must fail closed");
            check(!StreamingLongScreenshotStitcher.supportsRecentOverlap(
                            recentOverlaps, recentManual, recentNoMovement,
                            6, 445, -12, 95, 10),
                    "far repeated-chat decoy must not match recent path");
            check(!StreamingLongScreenshotStitcher.supportsRecentOverlap(
                            new int[] {0, 1239, 1242}, new boolean[3], new boolean[3],
                            2, 1235, -14, 98, 10),
                    "two seams are insufficient to confirm a path");
            int[] healthOverlaps = {0, 1375, 1119, 1141, 1098, 2199};
            boolean[] healthManual = new boolean[healthOverlaps.length];
            healthManual[2] = true;
            boolean[] healthNoMovement = new boolean[healthOverlaps.length];
            check(LongScreenshotStitcher.neighboringExpectedOverlap(
                            healthOverlaps, healthManual, healthNoMovement, 2) == 1141,
                    "two-sided early seam trajectory");
            check(LongScreenshotStitcher.supportsTrajectoryExpectedOverlap(
                            1141, 1119, -9, 91, 9),
                    "low-texture health card trajectory");
            check(!LongScreenshotStitcher.supportsTrajectoryExpectedOverlap(
                            1141, 1119, -9, 89, 9),
                    "weak health card consensus must fail closed");
            check(!LongScreenshotStitcher.supportsTrajectoryExpectedOverlap(
                            1141, 1089, -9, 91, 9),
                    "health card candidate outside 48px path must fail closed");
            check(LongScreenshotStitcher.supportsSessionTrajectoryExpectedOverlap(
                            1583, 1587, 20, 90, 20),
                    "stable session trajectory may confirm a visually accepted local seam");
            check(!LongScreenshotStitcher.supportsSessionTrajectoryExpectedOverlap(
                            1583, 1368, 12, 98, 8),
                    "session trajectory must reject the distant global decoy");
            check(LongScreenshotStitcher.neighboringExpectedOverlap(
                            new int[] {0, 700, 1100, 1500}, new boolean[4], new boolean[4], 2) < 0,
                    "unstable neighboring trajectory must fail closed");
            int[] offsets = cumulativeOffsets(ACTUAL_SCROLL_DELTAS);
            conversation = buildConversation(offsets[offsets.length - 1] + CONTENT_HEIGHT + 160);
            for (int i = 0; i < offsets.length; i++) {
                frames.add(buildFrame(conversation, offsets[i], i));
            }

            LongScreenshotStitcher.StitchPlan plan =
                    LongScreenshotStitcher.analyze(frames, true, SCROLL_DELTA_HINTS);
            int[] exactHints = SCROLL_DELTA_HINTS.clone();
            exactHints[4] = ACTUAL_SCROLL_DELTAS[4];
            LongScreenshotStitcher.StitchPlan exactHintPlan =
                    LongScreenshotStitcher.analyze(frames, true, exactHints);
            LongScreenshotStitcher.StitchPlan untrustedHintPlan =
                    LongScreenshotStitcher.analyze(frames, true, UNTRUSTED_WEBVIEW_HINTS);
            Log.i(TAG, String.format(
                    Locale.US,
                    "DIAG_EXACT runId=%s index=4 actualDelta=%d hint=%d overlap=%d"
                            + " score=%d consensus=%d seamScore=%d manualRequired=%s",
                    runId,
                    ACTUAL_SCROLL_DELTAS[4],
                    exactHints[4],
                    exactHintPlan.overlaps[4],
                    exactHintPlan.scores[4],
                    exactHintPlan.consensusScores[4],
                    exactHintPlan.seamScores[4],
                    exactHintPlan.manualRequired[4]));
            check(plan.overlaps.length == frames.size(), "overlap count");
            check(plan.contentRects.length == frames.size(), "content rect count");
            check(plan.contentRects[0].top == 0, "first frame must keep fixed header");
            check(plan.contentRects[plan.contentRects.length - 1].bottom == HEIGHT,
                    "last frame must keep fixed composer");

            for (int i = 1; i < frames.size(); i++) {
                Rect previous = plan.contentRects[i - 1];
                Rect current = plan.contentRects[i];
                Log.i(TAG, String.format(
                        Locale.US,
                        "SEAM runId=%s index=%d actualDelta=%d hint=%d overlap=%d"
                                + " score=%d consensus=%d seamScore=%d manualRequired=%s"
                                + " expectedOverlap=%d previousMatch=%s nextMatch=%s",
                        runId,
                        i,
                        ACTUAL_SCROLL_DELTAS[i],
                        SCROLL_DELTA_HINTS[i],
                        plan.overlaps[i],
                        plan.scores[i],
                        plan.consensusScores[i],
                        plan.seamScores[i],
                        plan.manualRequired[i],
                        plan.expectedOverlaps[i],
                        plan.matchRects[i - 1].toShortString(),
                        plan.matchRects[i].toShortString()));
                int expected = Math.min(previous.height(), current.height())
                        - ACTUAL_SCROLL_DELTAS[i];
                int error = Math.abs(plan.overlaps[i] - expected);
                check(error <= OVERLAP_TOLERANCE_PX,
                        "seam " + i + " overlap expected=" + expected
                                + " actual=" + plan.overlaps[i] + " error=" + error);
                int safeMinimum = Math.max(96, plan.maxOverlaps[i] / 6);
                check(untrustedHintPlan.overlaps[i] >= safeMinimum,
                        "untrusted hint seam " + i + " selected unsafe overlap="
                                + untrustedHintPlan.overlaps[i] + " minimum=" + safeMinimum);
            }

            for (int i = 1; i < frames.size() - 1; i++) {
                Rect content = plan.contentRects[i];
                check(Math.abs(content.top - FIXED_TOP) <= EDGE_TOLERANCE_PX,
                        "frame " + i + " fixed top=" + content.top);
                check(Math.abs((HEIGHT - content.bottom) - FIXED_BOTTOM) <= EDGE_TOLERANCE_PX,
                        "frame " + i + " fixed bottom=" + (HEIGHT - content.bottom));
            }

            stitched = LongScreenshotStitcher.stitch(frames, plan.overlaps.clone());
            int expectedHeight = HEIGHT + offsets[offsets.length - 1];
            int heightError = Math.abs(stitched.getHeight() - expectedHeight);
            check(stitched.getWidth() == WIDTH, "output width=" + stitched.getWidth());
            check(heightError <= OVERLAP_TOLERANCE_PX * (frames.size() - 1),
                    "output height expected=" + expectedHeight
                            + " actual=" + stitched.getHeight() + " error=" + heightError);
            int rightEdgeArtifacts = countRightEdgeIndicatorPixels(stitched);
            check(rightEdgeArtifacts == 0, "right edge indicator pixels=" + rightEdgeArtifacts);

            // With only two frames, the first keeps the header and the last keeps the composer.
            // The reliable delta must refer to the moving viewport, not either enlarged content rect.
            List<Bitmap> pair = frames.subList(0, 2);
            int[] pairDeltas = {-1, ACTUAL_SCROLL_DELTAS[1]};
            LongScreenshotStitcher.StitchPlan pairPlan =
                    LongScreenshotStitcher.analyze(pair, true, pairDeltas);
            int expectedPairOverlap = CONTENT_HEIGHT - pairDeltas[1];
            check(Math.abs(pairPlan.overlaps[1] - expectedPairOverlap) <= OVERLAP_TOLERANCE_PX,
                    "two-frame overlap expected=" + expectedPairOverlap
                            + " actual=" + pairPlan.overlaps[1]);
            pairStitched = LongScreenshotStitcher.stitch(pair, pairPlan.overlaps.clone());
            check(Math.abs(pairStitched.getHeight() - (HEIGHT + pairDeltas[1]))
                            <= OVERLAP_TOLERANCE_PX,
                    "two-frame output height=" + pairStitched.getHeight());

            guardStitched = runViewportBottomGuardCheck();
            runRepeatedTranscriptCheck();
            runAmbiguousTranscriptCheck();
            runStreamingCheck(frames, stitched);
            runVeryLongStreamingCheck();

            String summary = String.format(
                    Locale.US,
                    "PASS runId=%s frames=%d output=%dx%d expectedHeight=%d",
                    runId,
                    frames.size(),
                    stitched.getWidth(),
                    stitched.getHeight(),
                    expectedHeight);
            Log.i(TAG, summary);
            showResult(summary, 0xff146c43);
        } catch (Throwable error) {
            String summary = "FAIL runId=" + runId + " "
                    + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, summary, error);
            showResult(summary, 0xffb42318);
        } finally {
            if (pairStitched != null && !pairStitched.isRecycled()) {
                pairStitched.recycle();
            }
            if (guardStitched != null && !guardStitched.isRecycled()) {
                guardStitched.recycle();
            }
            if (stitched != null && !stitched.isRecycled()) {
                stitched.recycle();
            }
            for (Bitmap frame : frames) {
                if (frame != null && !frame.isRecycled()) {
                    frame.recycle();
                }
            }
            if (conversation != null && !conversation.isRecycled()) {
                conversation.recycle();
            }
        }
    }

    private static Bitmap runViewportBottomGuardCheck() {
        int width = 120;
        int height = 400;
        int overlap = 120;
        int red = 0xffff0000;
        int blue = 0xff0000ff;
        int green = 0xff00aa44;
        Bitmap first = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            first.eraseColor(0xffffffff);
            second.eraseColor(blue);
            Canvas canvas = new Canvas(first);
            Paint paint = new Paint();
            paint.setColor(red);
            canvas.drawRect(0, height - 96, width, height, paint);
            canvas = new Canvas(second);
            paint.setColor(green);
            canvas.drawRect(0, 24, width, height, paint);

            ArrayList<Bitmap> frames = new ArrayList<>();
            frames.add(first);
            frames.add(second);
            Bitmap stitched = LongScreenshotStitcher.stitch(
                    frames,
                    new int[] {0, overlap},
                    true,
                    null);
            int protectedPixel = stitched.getPixel(width / 2, height - 60);
            check(colorDistance(protectedPixel, green) <= 2,
                    "viewport bottom guard did not replace bottom strip");
            return stitched;
        } finally {
            first.recycle();
            second.recycle();
        }
    }

    private static void runRepeatedTranscriptCheck() {
        int delta = 260;
        Bitmap transcript = buildRepeatedTranscript(delta + CONTENT_HEIGHT + 220);
        Bitmap first = null;
        Bitmap second = null;
        try {
            first = buildFrame(transcript, 0, 0);
            second = buildFrame(transcript, delta, 1);
            ArrayList<Bitmap> pair = new ArrayList<>();
            pair.add(first);
            pair.add(second);
            LongScreenshotStitcher.StitchPlan plan = LongScreenshotStitcher.analyze(
                    pair,
                    true,
                    new int[] {0, 9999});
            int expectedOverlap = plan.maxOverlaps[1] - delta;
            check(Math.abs(plan.overlaps[1] - expectedOverlap) <= OVERLAP_TOLERANCE_PX,
                    "repeated transcript overlap expected=" + expectedOverlap
                            + " actual=" + plan.overlaps[1]);
            check(!plan.manualRequired[1], "repeated transcript seam must be unique");
        } finally {
            if (first != null) {
                first.recycle();
            }
            if (second != null) {
                second.recycle();
            }
            transcript.recycle();
        }
    }

    private void runAmbiguousTranscriptCheck() throws Exception {
        int delta = 260;
        Bitmap first = null;
        Bitmap second = null;
        File dir = new File(getCacheDir(), "ambiguous-self-test-" + System.currentTimeMillis());
        check(dir.mkdirs(), "cannot create ambiguous self-test dir");
        try {
            first = buildPeriodicFrame(WIDTH, CONTENT_HEIGHT, 0, 220);
            second = buildPeriodicFrame(WIDTH, CONTENT_HEIGHT, delta, 220);
            ArrayList<Bitmap> pair = new ArrayList<>();
            pair.add(first);
            pair.add(second);
            LongScreenshotStitcher.StitchPlan plan = LongScreenshotStitcher.analyze(
                    pair,
                    true,
                    new int[] {0, 9999});
            check(plan.manualRequired[1], "periodic transcript must remain ambiguous");
            LongScreenshotStitcher.StitchPlan pathPlan =
                    LongScreenshotStitcher.analyzePairWithPathExpectedOverlap(
                            pair,
                            CONTENT_HEIGHT - delta,
                            0,
                            0);
            check(Math.abs(pathPlan.overlaps[1] - (CONTENT_HEIGHT - delta)) <= 48,
                    "path-only retry must stay inside the stable trajectory window");

            ArrayList<File> files = new ArrayList<>();
            for (int i = 0; i < pair.size(); i++) {
                File file = new File(dir, "frame_" + i + ".png");
                StreamingLongScreenshotStitcher.writeFramePng(pair.get(i), file);
                files.add(file);
            }
            File output = new File(dir, "ambiguous.png");
            StreamingLongScreenshotStitcher.Result result = StreamingLongScreenshotStitcher.stitch(
                    files,
                    new int[] {0, 9999},
                    output,
                    null,
                    null);
            check(result.plan.manualRequired[1],
                    "ambiguous streaming seam must keep its low-confidence marker");
            check(output.isFile() && !new File(output.getAbsolutePath() + ".part").exists(),
                    "ambiguous streaming seam must publish a recoverable output");
        } finally {
            if (first != null) {
                first.recycle();
            }
            if (second != null) {
                second.recycle();
            }
            deleteRecursively(dir);
        }
    }

    private static Bitmap buildPeriodicFrame(int width, int height, int globalTop, int period) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int periodicY = (globalTop + y) % period;
            for (int x = 0; x < width; x++) {
                int value = periodicY * 1103515245 + x * 12345 + (periodicY >>> 2) * 265443576;
                pixels[y * width + x] = 0xff000000 | (value & 0x00ffffff);
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static Bitmap buildRepeatedTranscript(int height) {
        int period = 220;
        Bitmap transcript = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(transcript);
        Paint paint = new Paint();
        for (int y = 0; y < height; y += 20) {
            paint.setColor(((y / 20) % (period / 20)) % 2 == 0 ? 0xffeeeeee : 0xffe9e9e9);
            canvas.drawRect(0, y, WIDTH, Math.min(height, y + 20), paint);
        }
        for (int block = 0, top = 18; top < height; block++, top += period) {
            paint.setColor(0xff385f91);
            canvas.drawCircle(26, top + 34, 14, paint);
            paint.setColor(0xffffffff);
            canvas.drawRoundRect(52, top + 8, WIDTH - 26, top + 92, 10, 10, paint);
            paint.setColor(0xff303030);
            paint.setTextSize(17);
            canvas.drawText("重复消息内容", 68, top + 42, paint);
            canvas.drawText("重复消息内容", 68, top + 70, paint);
            paint.setColor(0xff000000 | ((0x1677ff + block * 0x000b1307) & 0x00ffffff));
            canvas.drawRect(68, top + 76, 92, top + 84, paint);
            paint.setColor(0xffd4d4d4);
            canvas.drawRect(0, top + 138, WIDTH, top + 142, paint);
        }
        return transcript;
    }

    private void runStreamingCheck(List<Bitmap> frames, Bitmap oracle) throws Exception {
        File dir = new File(getCacheDir(), "streaming-self-test-" + System.currentTimeMillis());
        check(dir.mkdirs(), "cannot create streaming self-test dir");
        ArrayList<File> files = new ArrayList<>();
        try {
            for (int i = 0; i < frames.size(); i++) {
                File file = new File(dir, String.format(Locale.US, "frame_%03d.png", i + 1));
                StreamingLongScreenshotStitcher.writeFramePng(frames.get(i), file);
                files.add(file);
            }
            File output = new File(dir, "streamed.png");
            StreamingLongScreenshotStitcher.Result result = StreamingLongScreenshotStitcher.stitch(
                    files,
                    SCROLL_DELTA_HINTS,
                    output,
                    null,
                    null);
            check(result.width == WIDTH, "streaming output width=" + result.width);
            check(result.height == oracle.getHeight(),
                    "streaming output height expected=" + oracle.getHeight() + " actual=" + result.height);
            check(output.isFile() && output.length() > 0, "streaming output file");
            Bitmap actual = BitmapFactory.decodeFile(output.getAbsolutePath());
            check(actual != null, "streaming output decode");
            try {
                int[] sampleX = {24, WIDTH / 2, WIDTH - 56};
                for (int y = 0; y < actual.getHeight(); y += 53) {
                    for (int x : sampleX) {
                        check(actual.getPixel(x, y) == oracle.getPixel(x, y),
                                "streaming pixel mismatch x=" + x + " y=" + y);
                    }
                }
            } finally {
                actual.recycle();
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private void runVeryLongStreamingCheck() throws Exception {
        int width = 64;
        int height = 2600;
        int delta = 2100;
        int frameCount = 24;
        int expectedHeight = height + (frameCount - 1) * delta;
        check(frameCount > 20 && expectedHeight > 40000, "very-long fixture must cross old limits");
        File dir = new File(getCacheDir(), "very-long-self-test-" + System.currentTimeMillis());
        check(dir.mkdirs(), "cannot create very-long self-test dir");
        ArrayList<File> files = new ArrayList<>();
        int[] deltas = new int[frameCount];
        try {
            for (int i = 0; i < frameCount; i++) {
                Bitmap frame = buildDeterministicFrame(width, height, i * delta);
                File file = new File(dir, String.format(Locale.US, "frame_%03d.png", i + 1));
                try {
                    StreamingLongScreenshotStitcher.writeFramePng(frame, file);
                } finally {
                    frame.recycle();
                }
                files.add(file);
                if (i > 0) {
                    deltas[i] = delta;
                }
            }
            File output = new File(dir, "very-long.png");
            StreamingLongScreenshotStitcher.Result result = StreamingLongScreenshotStitcher.stitch(
                    files, deltas, output, null, null);
            check(result.width == width, "very-long width=" + result.width);
            check(Math.abs(result.height - expectedHeight) <= frameCount * OVERLAP_TOLERANCE_PX,
                    "very-long height expected=" + expectedHeight + " actual=" + result.height);
            check(output.isFile() && output.length() > 0, "very-long output file");
            Bitmap verified = BitmapFactory.decodeFile(output.getAbsolutePath());
            check(verified != null, "very-long output decode");
            try {
                int[] sampleColumns = {3, width / 2, width - 12};
                for (int seam = 1; seam < frameCount; seam++) {
                    int seamY = seam * delta;
                    for (int offset = -2; offset <= 2; offset++) {
                        int y = seamY + offset;
                        for (int x : sampleColumns) {
                            check(verified.getPixel(x, y) == deterministicColor(y, x),
                                    "very-long pixel mismatch seam=" + seam
                                            + " x=" + x + " y=" + y);
                        }
                    }
                }
            } finally {
                verified.recycle();
            }
            File cropped = new File(dir, "very-long-cropped.png");
            int cropTop = 321;
            int cropBottom = 654;
            StreamingLongScreenshotStitcher.crop(
                    output,
                    new Rect(0, cropTop, result.width, result.height - cropBottom),
                    cropped);
            BitmapFactory.Options cropBounds = new BitmapFactory.Options();
            cropBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cropped.getAbsolutePath(), cropBounds);
            check(cropBounds.outWidth == width, "very-long crop width=" + cropBounds.outWidth);
            check(cropBounds.outHeight == result.height - cropTop - cropBottom,
                    "very-long crop height=" + cropBounds.outHeight);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static Bitmap buildDeterministicFrame(int width, int height, int globalTop) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int globalY = globalTop + y;
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = deterministicColor(globalY, x);
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static int deterministicColor(int globalY, int x) {
        int value = globalY * 1103515245 + x * 12345 + (globalY >>> 3) * 265443576;
        return 0xff000000 | (value & 0x00ffffff);
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

    private void showResult(String text, int color) {
        runOnUiThread(() -> {
            status.setText(text);
            status.setTextColor(color);
        });
    }

    private static int[] cumulativeOffsets(int[] deltas) {
        int[] offsets = new int[deltas.length];
        for (int i = 1; i < deltas.length; i++) {
            check(deltas[i] > 0 && deltas[i] < CONTENT_HEIGHT, "invalid scroll delta " + i);
            offsets[i] = offsets[i - 1] + deltas[i];
        }
        return offsets;
    }

    private static void runDynamicDuplicateCheck() {
        Bitmap still = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
        Bitmap rotatingBanner = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
        try {
            still.eraseColor(Color.WHITE);
            rotatingBanner.eraseColor(Color.WHITE);
            for (int index = 0; index < 24; index++) {
                int row = index / 32;
                int column = index % 32;
                int x = (column + 1) * still.getWidth() / 33;
                int y = still.getHeight() / 8
                        + (row + 1) * (still.getHeight() * 3 / 4) / 49;
                rotatingBanner.setPixel(x, y, Color.rgb(225, 225, 225));
            }
            check(LongScreenshotStitcher.isNearDuplicate(still, rotatingBanner),
                    "1.6 percent rotating banner must remain a duplicate");
            for (int index = 24; index < 48; index++) {
                int row = index / 32;
                int column = index % 32;
                int x = (column + 1) * still.getWidth() / 33;
                int y = still.getHeight() / 8
                        + (row + 1) * (still.getHeight() * 3 / 4) / 49;
                rotatingBanner.setPixel(x, y, Color.rgb(225, 225, 225));
            }
            check(!LongScreenshotStitcher.isNearDuplicate(still, rotatingBanner),
                    "3.1 percent content change must not be a duplicate");
        } finally {
            still.recycle();
            rotatingBanner.recycle();
        }
    }

    private static Bitmap buildConversation(int height) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(0xffededed);

        int y = 18;
        int item = 1;
        while (y < height - 180) {
            if (item % 5 == 1) {
                paint.setColor(0xffd4d4d4);
                canvas.drawRoundRect(WIDTH / 2f - 34, y, WIDTH / 2f + 34, y + 22, 8, 8, paint);
                paint.setColor(0xff666666);
                paint.setTextSize(10);
                canvas.drawText(item % 10 == 1 ? "10:08" : "昨天", WIDTH / 2f - 15, y + 15, paint);
                y += 35;
            }

            boolean right = item % 3 != 0;
            int avatarLeft = right ? WIDTH - 42 : 12;
            paint.setColor(Color.rgb(
                    55 + (item * 37) % 150,
                    55 + (item * 61) % 150,
                    55 + (item * 83) % 150));
            canvas.drawRoundRect(avatarLeft, y, avatarLeft + 30, y + 30, 6, 6, paint);
            paint.setColor(0x99000000 | ((item * 0x193b5d) & 0x00ffffff));
            canvas.drawCircle(avatarLeft + 15, y + 15, 4 + item % 5, paint);

            boolean imageBubble = item % 7 == 0;
            int bubbleWidth = imageBubble ? 158 : 112 + (item % 4) * 24;
            int bubbleHeight = imageBubble ? 116 : 48 + (item % 3) * 24;
            int left = right ? WIDTH - 54 - bubbleWidth : 54;
            int rightEdge = left + bubbleWidth;
            paint.setColor(right ? 0xff95ec69 : 0xffffffff);
            canvas.drawRoundRect(left, y, rightEdge, y + bubbleHeight, 10, 10, paint);

            if (imageBubble) {
                paint.setColor(Color.rgb(
                        70 + (item * 23) % 130,
                        70 + (item * 41) % 130,
                        70 + (item * 59) % 130));
                canvas.drawRect(left + 8, y + 8, rightEdge - 8, y + bubbleHeight - 28, paint);
                paint.setColor(0xff202020);
                paint.setTextSize(11);
                canvas.drawText("图片 " + item, left + 10, y + bubbleHeight - 11, paint);
            } else {
                paint.setColor(0xff242424);
                paint.setTextSize(12);
                int lines = 1 + item % 3;
                for (int line = 0; line < lines; line++) {
                    // Repeated wording creates plausible ambiguous chat blocks; the small id is the anchor.
                    canvas.drawText(
                            "收到，稍后处理  " + item + "-" + (line + 1),
                            left + 9,
                            y + 20 + line * 18,
                            paint);
                }
            }

            paint.setColor(0xffd7d7d7);
            canvas.drawLine(0, y + bubbleHeight + 12, WIDTH, y + bubbleHeight + 12, paint);
            y += bubbleHeight + 31 + (item % 4) * 7;
            item++;
        }
        return bitmap;
    }

    private static Bitmap buildFrame(Bitmap conversation, int offset, int frameIndex) {
        Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(0xff222222);
        canvas.drawRect(0, 0, WIDTH, 20, paint);
        paint.setColor(0xfff7f7f7);
        canvas.drawRect(0, 20, WIDTH, FIXED_TOP, paint);
        paint.setColor(0xff202020);
        paint.setTextSize(16);
        canvas.drawText("‹", 12, 54, paint);
        canvas.drawText("白云测试群 (18)", 48, 53, paint);
        canvas.drawText("…", WIDTH - 28, 53, paint);
        paint.setTextSize(9);
        canvas.drawText("10:" + (10 + frameIndex), 10, 14, paint);

        Rect source = new Rect(0, offset, WIDTH, offset + CONTENT_HEIGHT);
        Rect target = new Rect(0, FIXED_TOP, WIDTH, HEIGHT - FIXED_BOTTOM);
        canvas.drawBitmap(conversation, source, target, null);

        paint.setColor(0xfffafafa);
        canvas.drawRect(0, HEIGHT - FIXED_BOTTOM, WIDTH, HEIGHT, paint);
        paint.setColor(0xffd6d6d6);
        canvas.drawRect(0, HEIGHT - FIXED_BOTTOM, WIDTH, HEIGHT - FIXED_BOTTOM + 1, paint);
        paint.setColor(0xffffffff);
        canvas.drawRoundRect(46, HEIGHT - 66, WIDTH - 72, HEIGHT - 18, 13, 13, paint);
        paint.setColor(0xff8a8a8a);
        paint.setTextSize(13);
        canvas.drawText("输入消息…", 62, HEIGHT - 36, paint);
        paint.setColor(0xff333333);
        paint.setTextSize(20);
        canvas.drawText("+", WIDTH - 42, HEIGHT - 33, paint);

        // Small changing viewport content stands in for caret/GIF/loading animation noise.
        paint.setColor(frameIndex % 2 == 0 ? 0xfff79009 : 0xff155eef);
        canvas.drawCircle(WIDTH - 10, FIXED_TOP + 118, 5, paint);
        paint.setColor(0xff777777);
        int thumbTop = FIXED_TOP + 86 + (frameIndex * 47) % 180;
        canvas.drawRoundRect(WIDTH - 5, thumbTop, WIDTH - 2, thumbTop + 84, 2, 2, paint);
        return frame;
    }

    private static int countRightEdgeIndicatorPixels(Bitmap bitmap) {
        int dirty = 0;
        int startX = WIDTH - 8;
        int referenceX = startX - 3;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            int reference = bitmap.getPixel(referenceX, y);
            int referenceGray = gray(reference);
            for (int x = startX; x < WIDTH; x++) {
                int color = bitmap.getPixel(x, y);
                int currentGray = gray(color);
                if (referenceGray > 80
                        && currentGray + 16 < referenceGray
                        && colorDistance(color, reference) >= 24) {
                    dirty++;
                }
            }
        }
        return dirty;
    }

    private static int gray(int color) {
        return (((color >> 16) & 0xff) * 30 + ((color >> 8) & 0xff) * 59 + (color & 0xff) * 11) / 100;
    }

    private static int colorDistance(int first, int second) {
        int red = Math.abs(((first >> 16) & 0xff) - ((second >> 16) & 0xff));
        int green = Math.abs(((first >> 8) & 0xff) - ((second >> 8) & 0xff));
        int blue = Math.abs((first & 0xff) - (second & 0xff));
        return (red + green + blue) / 3;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
