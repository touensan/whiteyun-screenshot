package com.whiteyun.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class CaptureService extends LocalizedService {
    public static final String ACTION_START = "com.whiteyun.screenshot.action.START_CAPTURE";
    public static final String ACTION_CAPTURE_BEGIN = "com.whiteyun.screenshot.action.CAPTURE_BEGIN";
    public static final String ACTION_SAVE_FRAME = "com.whiteyun.screenshot.action.SAVE_FRAME";
    public static final String ACTION_MANUAL_SAMPLE = "com.whiteyun.screenshot.action.MANUAL_SAMPLE";
    public static final String ACTION_MANUAL_FINISH = "com.whiteyun.screenshot.action.MANUAL_FINISH";
    public static final String ACTION_AUTO_START = "com.whiteyun.screenshot.action.AUTO_START";
    public static final String ACTION_STOP = "com.whiteyun.screenshot.action.STOP_CAPTURE";
    public static final String ACTION_STATUS = "com.whiteyun.screenshot.action.CAPTURE_STATUS";
    public static final String ACTION_STITCH_STATUS = "com.whiteyun.screenshot.action.STITCH_STATUS";
    public static final String PERMISSION_INTERNAL_BROADCAST =
            "com.whiteyun.screenshot.permission.INTERNAL_BROADCAST";
    public static final String ACTION_STITCH_CANCEL = "com.whiteyun.screenshot.action.STITCH_CANCEL";
    public static final String ACTION_STITCH_RETRY = "com.whiteyun.screenshot.action.STITCH_RETRY";
    public static final String ACTION_STITCH_POLL = "com.whiteyun.screenshot.action.STITCH_POLL";
    public static final String MODE_CORE = "core";
    public static final String MODE_MANUAL = "manual";
    public static final String MODE_AUTO = "auto";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SPEED_MODE = "speed_mode";
    public static final String EXTRA_STITCH_PROGRESS = "stitch_progress";
    public static final String EXTRA_STITCH_RUNNING = "stitch_running";
    public static final String EXTRA_STITCH_DONE = "stitch_done";
    public static final String EXTRA_STITCH_CAN_RETRY = "stitch_can_retry";
    public static final String EXTRA_STITCH_RESULT_PATH = "stitch_result_path";

    private static final String CHANNEL_ID = "capture";
    private static final String LOG_TAG = "WhiteYunCapture";
    private static final int NOTIFICATION_ID = 10;
    private static final int OVERLAY_STAGE_READY = 0;
    private static final int OVERLAY_STAGE_RUNNING = 1;
    private static final int FRAME_NONE = 0;
    private static final int FRAME_SINGLE_SAVE = 1;
    private static final int FRAME_MANUAL_SAMPLE = 2;
    private static final int FRAME_AUTO_SAMPLE = 3;
    private static final int ADD_FRAME_ADDED = 1;
    private static final int ADD_FRAME_DUPLICATE = 2;
    private static final int AUTO_SCROLL_SETTLE_MS = 900;
    private static final int AUTO_FAST_SCROLL_SETTLE_MS = 320;
    private static final int AUTO_FAST_FRAME_PREPARE_MS = 180;
    private static final int AUTO_NOTIFICATION_SETTLE_MS = 1500;
    private static final int MAX_AUTO_SCROLL_FAILURES = 2;
    private static final int AUTO_MEMORY_FRAME_LIMIT = 1;
    private static final long AUTO_STORAGE_RESERVE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_MANUAL_FRAMES = 10;
    private static final int AUTO_TERMINAL_STATUS_MS = 6000;
    private static final int CAPTURE_OVERLAY_HIDE_MS = 120;
    private static final int MANUAL_CAPTURE_OVERLAY_HIDE_MS = 500;
    private static final int MANUAL_SCROLL_SETTLE_MS = 450;
    private static final int MIN_MANUAL_SCROLL_DELTA = 96;
    private static final int AUTO_STOP_LINE_NUMERATOR = 42;
    private static final int AUTO_STOP_LINE_DENOMINATOR = 100;
    private static final int STITCH_IDLE = 0;
    private static final int STITCH_RUNNING = 1;
    private static final int STITCH_CANCELED = 2;
    private static final int STITCH_FAILED = 3;
    private static final int STITCH_DONE = 4;
    private static final int STITCH_PROGRESS_PREPARE = 5;
    private static final int STITCH_PROGRESS_WRITE_START = 8;
    private static final int STITCH_PROGRESS_WRITE_END = 24;
    private static final int STITCH_PROGRESS_ANALYZE_START = 24;
    private static final int STITCH_PROGRESS_ANALYZE_END = 64;
    private static final int STITCH_PROGRESS_DRAW_START = 64;
    private static final int STITCH_PROGRESS_DRAW_END = 76;
    private static final int STITCH_PROGRESS_PREVIEW = 82;
    private static final int STITCH_PROGRESS_DEBUG = 94;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger frameRequestInFlight = new AtomicInteger(FRAME_NONE);
    private final AtomicInteger frameRequestToken = new AtomicInteger();
    private final AtomicInteger manualCaptureGeneration = new AtomicInteger();
    private final AtomicInteger stitchJobToken = new AtomicInteger();
    private final List<Bitmap> manualFrames = new ArrayList<>();
    private final ArrayList<File> autoFrameFiles = new ArrayList<>();
    private final List<Integer> frameScrollDeltas = new ArrayList<>();
    private final AutoScrollAccessibilityService.ScrollObserver scrollObserver = this::onObservedScroll;
    private final Runnable manualScrollSettledRunnable = this::captureAfterManualScrollSettled;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private View overlayView;
    private View rangeOverlayView;
    private View autoStopTouchView;
    private String captureMode = MODE_CORE;
    private int overlayStage = OVERLAY_STAGE_READY;
    private long lastManualFingerprint;
    private volatile int activeFrameRequestToken;
    private volatile int activeManualCaptureGeneration;
    private int autoScrollFailures;
    private boolean autoRunning;
    private boolean autoFinishing;
    private boolean autoScrollReverseDirection;
    private boolean backgroundStitching;
    private boolean releasingProjectionForStitch;
    private boolean captureForegroundStarted;
    private boolean speedMode;
    private AutoScrollEvidenceStore.Session autoEvidenceSession;
    private int autoFrameAttempts;
    private File autoFrameDir;
    private int stitchState = STITCH_IDLE;

    private interface FrameWriteProgress {
        void onFrameWritten(int completed, int total);
    }
    private int stitchProgress;
    private boolean stitchAutoMode;
    private String stitchProgressMessage = "";
    private String stitchResultPath = "";
    private TextView overlayStatusText;
    private String lastStatusMessage = "";
    private boolean scrollObservationActive;
    private boolean manualScrollCaptureEnabled;
    private boolean manualScrollSettling;
    private boolean scrollObservedSinceFrame;
    private boolean reliableScrollDeltaSinceFrame;
    private boolean incompleteScrollDeltaSinceFrame;
    private int pendingScrollDeltaY;
    private volatile boolean sessionEnding;
    private volatile String captureTargetPackage = "";
    private int captureTargetWindowId = -1;
    private String captureTargetSourceKey = "";
    private boolean accessibilityAttentionNeeded;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            mainHandler.post(() -> {
                if (backgroundStitching || releasingProjectionForStitch) {
                    return;
                }
                publishStatus(getString(R.string.c2_status_projection_stopped));
                stopSession();
                stopSelf();
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            startForegroundForCapture(getString(R.string.c2_status_starting));
            startProjection(intent);
        } else if (ACTION_CAPTURE_BEGIN.equals(action)) {
            beginCaptureFlow(false);
        } else if (ACTION_SAVE_FRAME.equals(action)) {
            requestSaveFrame();
        } else if (ACTION_MANUAL_SAMPLE.equals(action)) {
            requestManualSample();
        } else if (ACTION_MANUAL_FINISH.equals(action)) {
            finishCaptureFlow();
        } else if (ACTION_AUTO_START.equals(action)) {
            beginCaptureFlow(true);
        } else if (ACTION_STOP.equals(action)) {
            cancelCaptureSession();
        } else if (ACTION_STITCH_CANCEL.equals(action)) {
            cancelStitchJob();
        } else if (ACTION_STITCH_RETRY.equals(action)) {
            retryStitchJob();
        } else if (ACTION_STITCH_POLL.equals(action)) {
            publishCurrentStitchStatus();
            if (stitchState == STITCH_IDLE && captureHandler == null && !captureForegroundStarted) {
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundForCapture(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text));
        }
        captureForegroundStarted = true;
    }

    private void startProjection(Intent intent) {
        stopSession();
        sessionEnding = false;
        accessibilityAttentionNeeded = false;
        String requestedMode = intent.getStringExtra(EXTRA_MODE);
        if (MODE_AUTO.equals(requestedMode)) {
            captureMode = MODE_AUTO;
        } else if (MODE_MANUAL.equals(requestedMode)) {
            captureMode = MODE_MANUAL;
        } else {
            captureMode = MODE_CORE;
        }
        speedMode = intent.getBooleanExtra(EXTRA_SPEED_MODE, true);
        overlayStage = OVERLAY_STAGE_READY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            failAndStop(getString(R.string.c2_status_projection_denied));
            return;
        }

        captureThread = new HandlerThread("whiteyun-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            failAndStop(getString(R.string.c2_status_projection_denied));
            return;
        }
        projection.registerCallback(projectionCallback, mainHandler);

        DisplaySpec spec = displaySpec();
        imageReader = ImageReader.newInstance(spec.width, spec.height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "WhiteYunScreenshot",
                spec.width,
                spec.height,
                spec.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
        showOverlay();
        String ready = readyStatus();
        publishStatus(ready);
        startForegroundForCapture(ready);
    }

    private String readyStatus() {
        if (MODE_AUTO.equals(captureMode)) {
            return getString(R.string.c4_status_ready);
        }
        if (MODE_MANUAL.equals(captureMode)) {
            return getString(R.string.c8_status_ready_manual);
        }
        return getString(speedMode ? R.string.c8_status_ready_speed : R.string.c8_status_ready_core);
    }

    private String runningStatus() {
        if (MODE_AUTO.equals(captureMode)) {
            return getString(R.string.c4_status_running);
        }
        if (MODE_MANUAL.equals(captureMode)) {
            return getString(R.string.c3_status_running);
        }
        return getString(R.string.c2_status_running);
    }

    private void beginCaptureFlow(boolean fromNotification) {
        if (captureHandler == null || sessionEnding) {
            publishStatus(getString(R.string.c2_status_not_running));
            return;
        }
        overlayStage = OVERLAY_STAGE_RUNNING;
        if (MODE_AUTO.equals(captureMode)) {
            refreshOverlay();
            startAutoLongShot(fromNotification);
            return;
        }
        String running = runningStatus();
        publishStatus(running);
        startForegroundForCapture(running);
        refreshOverlay();
        if (MODE_MANUAL.equals(captureMode)) {
            manualScrollCaptureEnabled = AutoScrollAccessibilityService.isRunning();
            if (manualScrollCaptureEnabled) {
                publishStatus(getString(R.string.c35_status_capturing_first));
                requestManualSample(0);
            } else {
                publishStatus(getString(R.string.c35_status_accessibility_fallback));
            }
        }
    }

    private void requestSaveFrame() {
        requestFrame(FRAME_SINGLE_SAVE, R.string.c2_status_preparing_save, R.string.c2_status_saving);
    }

    private void requestManualSample() {
        requestManualSample(900);
    }

    private void requestManualSample(int preparationDelayMs) {
        if (!MODE_MANUAL.equals(captureMode)) {
            publishStatus(getString(R.string.c2_status_not_running));
            return;
        }
        if (manualFrames.size() >= MAX_MANUAL_FRAMES) {
            publishStatus(getString(R.string.c3_status_sample_limit, MAX_MANUAL_FRAMES));
            mainHandler.post(this::showOverlay);
            return;
        }
        if (!requestFrame(
                    FRAME_MANUAL_SAMPLE,
                    R.string.c3_status_preparing_sample,
                    R.string.c3_status_sampling,
                    preparationDelayMs)) {
            mainHandler.post(this::showOverlay);
        }
    }

    private boolean startScrollObservation() {
        if (scrollObservationActive) {
            return true;
        }
        scrollObservationActive = AutoScrollAccessibilityService.setScrollObserver(scrollObserver);
        if (!scrollObservationActive) {
            return false;
        }
        AutoScrollAccessibilityService.captureActiveWindow(snapshot -> {
            String packageName = snapshot == null ? "" : snapshot.packageName;
            if (packageName != null
                    && !packageName.isEmpty()
                    && !getPackageName().equals(packageName)
                    && !"com.android.systemui".equals(packageName)) {
                captureTargetPackage = packageName;
            }
        });
        return true;
    }

    private void stopScrollObservation() {
        AutoScrollAccessibilityService.clearScrollObserver(scrollObserver);
        scrollObservationActive = false;
        manualScrollCaptureEnabled = false;
        manualScrollSettling = false;
        captureTargetPackage = "";
        captureTargetWindowId = -1;
        captureTargetSourceKey = "";
        Handler handler = captureHandler;
        if (handler != null) {
            handler.removeCallbacks(manualScrollSettledRunnable);
        }
    }

    private void onObservedScroll(
            int deltaY,
            boolean hasReliableDelta,
            String packageName,
            int windowId,
            String sourceKey) {
        Handler handler = captureHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            boolean reliable = hasReliableDelta;
            boolean sourceConflict = false;
            if (sessionEnding
                    || backgroundStitching
                    || overlayStage != OVERLAY_STAGE_RUNNING
                    || (!MODE_MANUAL.equals(captureMode) && !autoRunning)) {
                return;
            }
            if (!captureTargetPackage.isEmpty() && !captureTargetPackage.equals(packageName)) {
                return;
            }
            if (captureTargetPackage.isEmpty()) {
                captureTargetPackage = packageName == null ? "" : packageName;
            }
            if (captureTargetWindowId < 0) {
                captureTargetWindowId = windowId;
            } else if (windowId >= 0 && captureTargetWindowId != windowId) {
                return;
            }
            if (reliable
                    && sourceKey != null
                    && !sourceKey.isEmpty()
                    && captureTargetSourceKey.isEmpty()) {
                captureTargetSourceKey = sourceKey;
            } else if (reliable
                    && !captureTargetSourceKey.isEmpty()
                    && sourceKey != null
                    && !sourceKey.isEmpty()
                    && !captureTargetSourceKey.equals(sourceKey)) {
                // ponytail: one scroll source avoids double-counting nested scroll containers; unknown-delta events still debounce capture.
                reliable = false;
                sourceConflict = true;
            }
            scrollObservedSinceFrame = true;
            if (reliable) {
                reliableScrollDeltaSinceFrame = true;
                pendingScrollDeltaY += deltaY;
            } else if (sourceConflict) {
                incompleteScrollDeltaSinceFrame = true;
            }
            if (!MODE_MANUAL.equals(captureMode) || !manualScrollCaptureEnabled) {
                return;
            }
            manualCaptureGeneration.incrementAndGet();
            cancelPendingManualFrameRequest();
            handler.removeCallbacks(manualScrollSettledRunnable);
            if (!manualScrollSettling) {
                manualScrollSettling = true;
                mainHandler.post(this::removeCaptureOverlays);
                publishStatus(getString(R.string.c35_status_waiting_for_settle));
            }
            handler.postDelayed(manualScrollSettledRunnable, MANUAL_SCROLL_SETTLE_MS);
        });
    }

    private void captureAfterManualScrollSettled() {
        manualScrollSettling = false;
        if (sessionEnding || !MODE_MANUAL.equals(captureMode) || !manualScrollCaptureEnabled) {
            return;
        }
        boolean trustworthyDelta = reliableScrollDeltaSinceFrame && !incompleteScrollDeltaSinceFrame;
        if (trustworthyDelta && pendingScrollDeltaY <= 0) {
            resetScrollTracking();
            publishStatus(getString(R.string.c35_status_reverse_scroll_skipped));
            mainHandler.post(this::showOverlay);
            return;
        }
        if (trustworthyDelta && pendingScrollDeltaY < MIN_MANUAL_SCROLL_DELTA) {
            publishStatus(getString(R.string.c35_status_scroll_more));
            mainHandler.post(this::showOverlay);
            return;
        }
        if (frameRequestInFlight.get() != FRAME_NONE) {
            Handler handler = captureHandler;
            if (handler != null) {
                handler.postDelayed(manualScrollSettledRunnable, CAPTURE_OVERLAY_HIDE_MS);
            }
            return;
        }
        requestManualSample(0);
    }

    private void cancelPendingManualFrameRequest() {
        if (frameRequestInFlight.compareAndSet(FRAME_MANUAL_SAMPLE, FRAME_NONE)) {
            activeFrameRequestToken = 0;
        }
    }

    private int consumeScrollDeltaForAcceptedFrame() {
        int rawDelta = reliableScrollDeltaSinceFrame && !incompleteScrollDeltaSinceFrame
                ? pendingScrollDeltaY
                : 0;
        int delta = autoRunning ? Math.abs(rawDelta) : Math.max(0, rawDelta);
        if (delta > 0) {
            Log.i(LOG_TAG, "scroll_delta raw=" + rawDelta
                    + ",used=" + delta
                    + ",auto=" + autoRunning
                    + ",reverse=" + autoScrollReverseDirection);
        }
        resetScrollTracking();
        return delta;
    }

    private void resetScrollTracking() {
        scrollObservedSinceFrame = false;
        reliableScrollDeltaSinceFrame = false;
        incompleteScrollDeltaSinceFrame = false;
        pendingScrollDeltaY = 0;
        manualScrollSettling = false;
    }

    private void startAutoLongShot() {
        startAutoLongShot(false);
    }

    private void startAutoLongShot(boolean fromNotification) {
        if (!MODE_AUTO.equals(captureMode) || captureHandler == null) {
            publishStatus(getString(R.string.c2_status_not_running));
            return;
        }
        if (autoRunning) {
            publishStatus(getString(R.string.c2_status_save_busy));
            return;
        }
        if (!AutoScrollAccessibilityService.isRunning()) {
            accessibilityAttentionNeeded = true;
            int statusRes = AutoScrollAccessibilityService.isEnabled(this)
                    ? R.string.c4_status_accessibility_disconnected
                    : R.string.c4_status_no_accessibility;
            String message = getString(statusRes);
            publishStatus(message);
            startForegroundForCapture(message);
            mainHandler.post(this::showOverlay);
            openAccessibilitySettings();
            return;
        }
        accessibilityAttentionNeeded = false;
        clearManualFrames();
        lastManualFingerprint = 0L;
        autoScrollFailures = 0;
        overlayStage = OVERLAY_STAGE_RUNNING;
        autoRunning = true;
        autoFinishing = false;
        autoFrameAttempts = 0;
        autoScrollReverseDirection = false;
        DisplaySpec spec = displaySpec();
        autoEvidenceSession = AutoScrollEvidenceStore.start(
                this,
                spec.width,
                spec.height,
                spec.densityDpi,
                0);
        recordAutoEvent("auto_start", "fromNotification=" + fromNotification);
        recordAutoWindow("auto_start");
        publishStatus(getString(R.string.c4_status_running));
        startForegroundForCapture(getString(R.string.c4_status_running));
        mainHandler.post(this::showOverlay);
        boolean dismissingShade = fromNotification && AutoScrollAccessibilityService.dismissNotificationShade();
        captureHandler.postDelayed(() -> {
            if (autoRunning
                    && !requestFrame(
                            FRAME_AUTO_SAMPLE,
                            R.string.c4_status_preparing_sample,
                            R.string.c4_status_sampling,
                            speedMode ? AUTO_FAST_FRAME_PREPARE_MS : 900)) {
                finishOrStopAutoAfterFailure(
                        "initial_frame_request_failed",
                        getString(R.string.c4_status_failed, getString(R.string.c2_status_save_busy)));
            }
        }, dismissingShade ? AUTO_NOTIFICATION_SETTLE_MS : 0);
    }

    private boolean requestFrame(int request, int preparingStatus, int captureStatus) {
        return requestFrame(request, preparingStatus, captureStatus, 900);
    }

    private boolean requestFrame(int request, int preparingStatus, int captureStatus, int preparationDelayMs) {
        Handler frameHandler = captureHandler;
        if (imageReader == null || frameHandler == null || sessionEnding) {
            publishStatus(getString(R.string.c2_status_not_running));
            return false;
        }
        if (!frameRequestInFlight.compareAndSet(FRAME_NONE, request)) {
            publishStatus(getString(R.string.c2_status_save_busy));
            return false;
        }
        int token = frameRequestToken.incrementAndGet();
        activeFrameRequestToken = token;
        if (request == FRAME_MANUAL_SAMPLE) {
            activeManualCaptureGeneration = manualCaptureGeneration.get();
        }
        if (request == FRAME_AUTO_SAMPLE) {
            recordAutoEvent("frame_request", "token=" + token);
            recordAutoWindow("frame_request");
        }
        publishStatus(getString(preparingStatus));
        frameHandler.postDelayed(() -> {
            if (activeFrameRequestToken != token
                    || frameRequestInFlight.get() != request
                    || sessionEnding) {
                return;
            }
            boolean settledManualFrame = request == FRAME_MANUAL_SAMPLE
                    && manualScrollCaptureEnabled
                    && scrollObservedSinceFrame;
            if (!settledManualFrame) {
                // ponytail: first/tap captures drain stale overlay buffers; a settled scroll already produced the clean latest frame.
                discardLatestImage();
            }
            mainHandler.post(() -> {
                if (activeFrameRequestToken != token
                        || frameRequestInFlight.get() != request
                        || sessionEnding) {
                    return;
                }
                removeCaptureOverlays();
                publishStatus(getString(captureStatus));
                int hideDelay = settledManualFrame
                        ? CAPTURE_OVERLAY_HIDE_MS
                        : request == FRAME_MANUAL_SAMPLE
                                ? MANUAL_CAPTURE_OVERLAY_HIDE_MS
                                : CAPTURE_OVERLAY_HIDE_MS;
                frameHandler.postDelayed(() -> captureFrameNow(request, token), hideDelay);
            });
        }, Math.max(0, preparationDelayMs));
        frameHandler.postDelayed(() -> {
            if (activeFrameRequestToken == token
                    && frameRequestInFlight.compareAndSet(request, FRAME_NONE)) {
                activeFrameRequestToken = 0;
                if (request == FRAME_AUTO_SAMPLE) {
                    recordAutoEvent("frame_timeout", "token=" + token);
                    finishOrStopAutoAfterFailure(
                            "frame_timeout",
                            getString(R.string.c4_status_failed, getString(R.string.c2_status_save_timeout)));
                    return;
                }
                mainHandler.post(this::showOverlay);
                publishStatus(getString(R.string.c2_status_save_timeout));
            }
        }, 4000);
        return true;
    }

    private void discardLatestImage() {
        Image stale = null;
        try {
            stale = imageReader == null ? null : imageReader.acquireLatestImage();
        } catch (IllegalStateException ignored) {
            // The projection may be closing while a finish/cancel action invalidates this request.
        } finally {
            if (stale != null) {
                stale.close();
            }
        }
    }

    private void captureFrameNow(int request, int token) {
        int requestManualGeneration = activeManualCaptureGeneration;
        if (activeFrameRequestToken != token
                || !frameRequestInFlight.compareAndSet(request, FRAME_NONE)) {
            return;
        }
        activeFrameRequestToken = 0;
        Image image = null;
        try {
            image = imageReader == null ? null : imageReader.acquireLatestImage();
            if (image == null) {
                if (request == FRAME_AUTO_SAMPLE) {
                    recordAutoEvent("frame_missing", "ImageReader returned null");
                    finishOrStopAutoAfterFailure(
                            "frame_missing",
                            getString(R.string.c4_status_failed, getString(R.string.c2_status_save_timeout)));
                    return;
                }
                if (request == FRAME_MANUAL_SAMPLE && manualFrames.isEmpty()) {
                    manualScrollCaptureEnabled = false;
                    publishStatus(getString(R.string.c35_status_first_capture_failed));
                }
                mainHandler.post(this::showOverlay);
                publishStatus(getString(R.string.c2_status_save_timeout));
                return;
            }
            if (request == FRAME_MANUAL_SAMPLE
                    && (sessionEnding || requestManualGeneration != manualCaptureGeneration.get())) {
                return;
            }
            if (request == FRAME_SINGLE_SAVE) {
                Bitmap bitmap = cropSystemBars(bitmapFromImage(image));
                File preview = writePreviewFile(bitmap, "single", "WhiteYunScreenshot");
                bitmap.recycle();
                openPreview(preview, null, PreviewActivity.RESULT_KIND_SINGLE);
                publishStatus(getString(R.string.c11_status_single_preview_ready));
                startForegroundForCapture(getString(R.string.c2_status_running));
            } else if (request == FRAME_MANUAL_SAMPLE) {
                addManualFrame(image);
            } else if (request == FRAME_AUTO_SAMPLE) {
                int result = addLongShotFrame(image, true);
                handleAutoSampleResult(result);
            }
        } catch (OutOfMemoryError error) {
            if (request != FRAME_NONE) {
                if (request == FRAME_AUTO_SAMPLE) {
                    recordAutoEvent("capture_oom", getString(R.string.c6_status_memory_limit));
                    stopAutoAfterFatalFailure(
                            "capture_oom",
                            getString(R.string.c4_status_failed, getString(R.string.c6_status_memory_limit)));
                } else {
                    if (request == FRAME_MANUAL_SAMPLE && manualFrames.isEmpty()) {
                        manualScrollCaptureEnabled = false;
                    }
                    publishStatus(getString(R.string.c2_status_save_failed, getString(R.string.c6_status_memory_limit)));
                }
            }
        } catch (Exception exception) {
            if (request != FRAME_NONE) {
                if (request == FRAME_AUTO_SAMPLE) {
                    recordAutoEvent("capture_exception", exception.getMessage());
                    stopAutoAfterFatalFailure(
                            "capture_exception",
                            getString(R.string.c4_status_failed, exception.getMessage()));
                } else {
                    if (request == FRAME_MANUAL_SAMPLE && manualFrames.isEmpty()) {
                        manualScrollCaptureEnabled = false;
                    }
                    publishStatus(getString(R.string.c2_status_save_failed, exception.getMessage()));
                }
            }
        } finally {
            if (image != null) {
                image.close();
            }
            if (request != FRAME_AUTO_SAMPLE) {
                mainHandler.post(this::showOverlay);
            }
        }
    }

    private void addManualFrame(Image image) throws IOException {
        addLongShotFrame(image, false);
    }

    private int addLongShotFrame(Image image, boolean auto) throws IOException {
        Bitmap raw = bitmapFromImage(image);
        int attempt = auto ? ++autoFrameAttempts : 0;
        AutoScrollEvidenceStore.Session evidence = autoEvidenceSession;
        if (auto && evidence != null && evidence.isEnabled()) {
            evidence.saveRawFrame(raw, attempt);
        }
        Bitmap bitmap = cropSystemBars(raw);
        long fingerprint = LongScreenshotStitcher.fingerprint(bitmap);
        if (!manualFrames.isEmpty()
                && LongScreenshotStitcher.isNearDuplicate(
                        manualFrames.get(manualFrames.size() - 1),
                        bitmap)) {
            if (auto && evidence != null && evidence.isEnabled()) {
                evidence.recordFrameResult(
                        attempt,
                        false,
                        true,
                        fingerprint,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        manualFrames.size(),
                        autoScrollFailures);
            }
            bitmap.recycle();
            resetScrollTracking();
            if (!auto) {
                publishStatus(getString(R.string.c3_status_duplicate));
            }
            return ADD_FRAME_DUPLICATE;
        }
        boolean firstFrame = manualFrames.isEmpty();
        int scrollDelta = firstFrame ? 0 : consumeScrollDeltaForAcceptedFrame();
        if (auto) {
            try {
                autoFrameFiles.add(writeAutoFrameFile(bitmap, autoFrameFiles.size()));
            } catch (IOException exception) {
                bitmap.recycle();
                throw exception;
            }
        }
        manualFrames.add(bitmap);
        if (auto && manualFrames.size() > AUTO_MEMORY_FRAME_LIMIT) {
            // ponytail: automatic sessions spool every frame and retain only the newest Bitmap; pairwise decode is the upgrade path that removes even this one-frame residency.
            for (int i = 0; i < manualFrames.size() - 1; i++) {
                Bitmap old = manualFrames.get(i);
                if (old != null && !old.isRecycled()) {
                    old.recycle();
                }
                manualFrames.set(i, null);
            }
        }
        frameScrollDeltas.add(scrollDelta);
        lastManualFingerprint = fingerprint;
        if (firstFrame) {
            resetScrollTracking();
            if (auto || manualScrollCaptureEnabled) {
                boolean observing = startScrollObservation();
                if (!auto) {
                    manualScrollCaptureEnabled = observing;
                }
            }
        }
        if (auto && evidence != null && evidence.isEnabled()) {
            evidence.recordFrameResult(
                    attempt,
                    true,
                    false,
                    fingerprint,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    manualFrames.size(),
                    autoScrollFailures);
        }
        publishStatus(auto
                ? getString(R.string.c4_status_auto_sampled, manualFrames.size())
                : manualScrollCaptureEnabled
                        ? getString(R.string.c35_status_auto_sampled, manualFrames.size())
                        : getString(R.string.c3_status_sampled, manualFrames.size()));
        return ADD_FRAME_ADDED;
    }

    private void handleAutoSampleResult(int result) {
        if (!autoRunning) {
            return;
        }
        if (result == ADD_FRAME_ADDED) {
            autoScrollFailures = 0;
        }
        if (result == ADD_FRAME_DUPLICATE
                && manualFrames.size() < 2
                && autoScrollFailures < MAX_AUTO_SCROLL_FAILURES) {
            autoScrollFailures++;
            if (!autoScrollReverseDirection) {
                autoScrollReverseDirection = true;
                recordAutoEvent("scroll_direction_fallback", "reverse_after_initial_duplicate");
            }
            requestAutoScroll();
            return;
        }
        if (result == ADD_FRAME_DUPLICATE) {
            finishAutoLongShot("bottom_or_duplicate");
            return;
        }
        requestAutoScroll();
    }

    private void requestAutoScroll() {
        publishStatus(getString(R.string.c4_status_scrolling));
        mainHandler.post(this::showOverlay);
        recordAutoEvent("scroll_request", "reverse=" + autoScrollReverseDirection);
        recordAutoWindow("before_scroll");
        // ponytail: duplicate-frame bottom detection is a cheap C4 heuristic; upgrade to node scroll-state plus seam scoring when app coverage matters.
        if (!AutoScrollAccessibilityService.requestScroll(autoScrollReverseDirection, speedMode, completed -> {
            recordAutoEvent("scroll_result", "completed=" + completed);
            recordAutoWindow("after_scroll");
            if (!completed) {
                if (manualFrames.size() < 2 && autoScrollFailures < MAX_AUTO_SCROLL_FAILURES) {
                    autoScrollFailures++;
                    requestAutoScroll();
                } else {
                    finishAutoLongShot();
                }
                return;
            }
            Handler handler = captureHandler;
            if (handler == null) {
                finishAutoLongShot();
                return;
            }
            handler.postDelayed(() -> {
                if (autoRunning
                        && !requestFrame(
                            FRAME_AUTO_SAMPLE,
                            R.string.c4_status_preparing_sample,
                            R.string.c4_status_sampling,
                            speedMode ? AUTO_FAST_FRAME_PREPARE_MS : 900)) {
                    finishOrStopAutoAfterFailure(
                            "frame_request_busy_after_scroll",
                            getString(R.string.c4_status_failed, getString(R.string.c2_status_save_busy)));
                }
            }, speedMode ? AUTO_FAST_SCROLL_SETTLE_MS : AUTO_SCROLL_SETTLE_MS);
        })) {
            recordAutoEvent("scroll_request_failed", "accessibility unavailable");
            finishOrStopAutoAfterFailure("scroll_request_failed", getString(R.string.c4_status_no_accessibility));
        }
    }

    private void finishAutoLongShot() {
        finishAutoLongShot("bottom_or_duplicate");
    }

    private void finishAutoLongShot(String reason) {
        if (autoFinishing) {
            return;
        }
        autoFinishing = true;
        autoRunning = false;
        cancelActiveAutoFrameRequest();
        recordAutoEvent("auto_finish", reason);
        publishStatus(getString(("user_done".equals(reason) || "red_line_stop".equals(reason))
                        ? R.string.c4_status_user_done
                        : R.string.c4_status_bottom));
        mainHandler.post(this::refreshOverlay);
        mainHandler.post(this::finishManualLongShot);
    }

    private void finishOrStopAutoAfterFailure(String reason, String message) {
        recordAutoEvent("auto_failure", reason);
        if (!manualFrames.isEmpty()) {
            publishStatus(getString(R.string.c24_status_partial_preview));
            finishAutoLongShot(reason);
            return;
        }
        stopAutoAfterFatalFailure(reason, message);
    }

    private void stopAutoAfterFatalFailure(String reason, String message) {
        autoRunning = false;
        autoFinishing = true;
        cancelActiveAutoFrameRequest();
        closeAutoEvidence(reason, false);
        publishStatus(message);
        mainHandler.post(() -> {
            refreshOverlay();
            mainHandler.postDelayed(this::stopSelf, AUTO_TERMINAL_STATUS_MS);
        });
    }

    private void cancelActiveAutoFrameRequest() {
        activeFrameRequestToken = 0;
        frameRequestInFlight.compareAndSet(FRAME_AUTO_SAMPLE, FRAME_NONE);
    }

    private void finishCaptureFlow() {
        if (MODE_CORE.equals(captureMode)) {
            publishStatus(getString(R.string.c2_status_stopped));
            stopSelf();
            return;
        }
        if (MODE_AUTO.equals(captureMode)) {
            finishAutoLongShot("user_done");
            return;
        }
        finishManualLongShot();
    }

    private void finishManualLongShot() {
        boolean autoMode = MODE_AUTO.equals(captureMode);
        if (MODE_MANUAL.equals(captureMode)) {
            if (sessionEnding) {
                return;
            }
            sessionEnding = true;
            stopScrollObservation();
            manualCaptureGeneration.incrementAndGet();
            cancelPendingManualFrameRequest();
            Handler handler = captureHandler;
            if (handler == null) {
                sessionEnding = false;
                publishStatus(getString(R.string.c2_status_not_running));
                return;
            }
            // The barrier lets any already-accepted frame finish before the UI thread reads manualFrames.
            handler.post(() -> mainHandler.post(() -> finishLongShotNow(false)));
            return;
        }
        finishLongShotNow(autoMode);
    }

    private void finishLongShotNow(boolean autoMode) {
        if ((!MODE_MANUAL.equals(captureMode) && !autoMode) || captureHandler == null) {
            publishStatus(getString(R.string.c2_status_not_running));
            return;
        }
        autoRunning = false;
        if (manualFrames.isEmpty()) {
            publishStatus(getString(autoMode ? R.string.c4_status_no_samples : R.string.c3_status_no_samples));
            if (autoMode) {
                closeAutoEvidence("no_samples", false);
                autoFinishing = true;
                mainHandler.post(() -> {
                    refreshOverlay();
                    mainHandler.postDelayed(this::stopSelf, AUTO_TERMINAL_STATUS_MS);
                });
            } else {
                sessionEnding = false;
                mainHandler.post(this::refreshOverlay);
            }
            return;
        }
        // ponytail: launch while the overlay is still visible; Android BAL blocks this after the service drops its visible window.
        startStitchQueueActivity();
        if (!autoMode) {
            removeOverlay();
        }
        enqueueStitchJob(autoMode);
    }

    private void enqueueStitchJob(boolean autoMode) {
        Handler handler = captureHandler;
        if (handler == null) {
            publishStitchProgress(getString(R.string.c31_stitch_not_running), 0, false, false, false, "");
            return;
        }
        backgroundStitching = true;
        stopScrollObservation();
        mainHandler.post(this::removeOverlay);
        stitchAutoMode = autoMode;
        stitchResultPath = "";
        stitchState = STITCH_RUNNING;
        publishStatus(getString(R.string.c52_stitch_queue_preparing));
        publishStitchProgress(
                getString(R.string.c52_stitch_queue_preparing),
                0,
                true,
                false,
                false,
                "");
        handler.post(() -> {
            try {
                int frameCount = manualFrames.size();
                int[] deltas = stitchScrollDeltasArray(autoMode, frameCount);
                StitchQueueStore.Job job = autoMode
                        ? StitchQueueStore.enqueueAuto(
                                this,
                                MODE_AUTO,
                                autoFrameDir,
                                autoFrameFilesForStitch(),
                                deltas)
                        : StitchQueueStore.enqueueManual(this, MODE_MANUAL, manualFrames, deltas);
                if (autoMode) {
                    recordAutoEvent("stitch_queued", job.id);
                    closeAutoEvidence("stitch_queued", true);
                }
                mainHandler.post(() -> {
                    backgroundStitching = false;
                    stitchState = STITCH_DONE;
                    releaseProjectionForBackgroundStitch();
                    clearManualFrames();
                    publishStitchProgress(
                            getString(R.string.c51_stitch_queued),
                            0,
                            true,
                            false,
                            false,
                            "");
                    StitchQueueService.start(this);
                    stopSelf();
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    backgroundStitching = false;
                    stitchState = STITCH_FAILED;
                    String message = getString(R.string.c51_stitch_queue_failed, exception.getMessage());
                    publishStatus(message);
                    publishStitchProgress(message, 0, false, false, true, "");
                });
            }
        });
    }

    private void startStitchJob(boolean autoMode) {
        if (captureHandler == null) {
            publishStitchProgress(getString(R.string.c31_stitch_not_running), 0, false, false, false, "");
            return;
        }
        if (manualFrames.isEmpty()) {
            publishStitchProgress(getString(R.string.c31_stitch_no_frames), 0, false, false, false, "");
            return;
        }
        backgroundStitching = true;
        stopScrollObservation();
        mainHandler.post(this::removeOverlay);
        releaseProjectionForBackgroundStitch();
        stitchAutoMode = autoMode;
        stitchResultPath = "";
        stitchState = STITCH_RUNNING;
        String stitchingStatus = getString(autoMode ? R.string.c4_status_stitching : R.string.c3_status_stitching);
        publishStatus(stitchingStatus);
        publishStitchProgress(
                getString(R.string.c31_stitch_preparing),
                STITCH_PROGRESS_PREPARE,
                true,
                false,
                false,
                "");
        int stitchToken = stitchJobToken.incrementAndGet();
        captureHandler.post(() -> {
            try {
                long jobStartMs = SystemClock.elapsedRealtime();
                long stageStartMs = jobStartMs;
                boolean streamingAuto = autoMode && manualFrames.size() > AUTO_MEMORY_FRAME_LIMIT;
                List<Bitmap> stitchFrames = streamingAuto
                        ? Collections.emptyList()
                        : framesForStitch(autoMode);
                if (streamingAuto) {
                    for (int i = 0; i < manualFrames.size(); i++) {
                        Bitmap resident = manualFrames.get(i);
                        if (resident != null && !resident.isRecycled()) {
                            resident.recycle();
                            manualFrames.set(i, null);
                        }
                    }
                }
                int frameCount = streamingAuto ? autoFrameFiles.size() : stitchFrames.size();
                int[] stitchScrollDeltas = stitchScrollDeltasArray(autoMode, frameCount);
                if (autoMode && autoScrollReverseDirection) {
                    recordAutoEvent("frame_order", "reversed_for_backward_scroll");
                }
                String writingFrames = getString(R.string.c31_stitch_writing_frames);
                publishStitchProgress(
                        writingFrames,
                        STITCH_PROGRESS_WRITE_START,
                        true,
                        false,
                        false,
                        "");
                ArrayList<File> streamingFiles = streamingAuto
                        ? autoFrameFilesForStitch()
                        : new ArrayList<>();
                ArrayList<String> sourceFiles = streamingAuto
                        ? filePaths(streamingFiles)
                        : autoMode
                                ? new ArrayList<>()
                                : writeOriginalFrameFiles(
                                manualFrames,
                                "manual-originals",
                                (completed, total) -> publishStitchStageProgress(
                                        writingFrames,
                                        STITCH_PROGRESS_WRITE_START,
                                        STITCH_PROGRESS_WRITE_END,
                                        completed,
                                        total));
                if (autoMode) {
                    publishStitchStageProgress(
                            writingFrames,
                            STITCH_PROGRESS_WRITE_START,
                            STITCH_PROGRESS_WRITE_END,
                            1,
                            1);
                }
                stageStartMs = logStitchTiming(autoMode, "write_originals", jobStartMs, stageStartMs);
                if (stitchJobToken.get() != stitchToken) {
                    return;
                }
                if (autoMode) {
                    recordAutoEvent("original_frames", streamingAuto
                            ? "spooled_count=" + sourceFiles.size()
                            : "spooled_below_streaming_threshold");
                }
                String analyzingOverlap = getString(R.string.c31_stitch_analyzing_overlap);
                publishStitchProgress(
                        analyzingOverlap,
                        STITCH_PROGRESS_ANALYZE_START,
                        true,
                        false,
                        false,
                        "");
                LongScreenshotStitcher.StitchPlan stitchPlan;
                File preview;
                String generatingPreview = getString(R.string.c31_stitch_generating_preview);
                if (streamingAuto) {
                    File output = createPreviewFile("manual", "WhiteYunLongShot", ".png");
                    StreamingLongScreenshotStitcher.Result result =
                            StreamingLongScreenshotStitcher.stitch(
                                    streamingFiles,
                                    stitchScrollDeltas,
                                    output,
                                    (completed, total) -> publishStitchStageProgress(
                                            analyzingOverlap,
                                            STITCH_PROGRESS_ANALYZE_START,
                                            STITCH_PROGRESS_ANALYZE_END,
                                            completed,
                                            total),
                                    (completed, total) -> publishStitchStageProgress(
                                            generatingPreview,
                                            STITCH_PROGRESS_DRAW_START,
                                            STITCH_PROGRESS_DEBUG,
                                            completed,
                                            total));
                    stitchPlan = result.plan;
                    preview = result.file;
                    stageStartMs = logStitchTiming(autoMode, "streaming_stitch", jobStartMs, stageStartMs);
                } else {
                    stitchPlan = LongScreenshotStitcher.analyze(
                            stitchFrames,
                            autoMode,
                            stitchScrollDeltas,
                            (completed, total) -> publishStitchStageProgress(
                                    analyzingOverlap,
                                    STITCH_PROGRESS_ANALYZE_START,
                                    STITCH_PROGRESS_ANALYZE_END,
                                            completed,
                                            total));
                    if (stitchPlan.needsManualAdjustment()) {
                        int seam = stitchPlan.firstManualSeam();
                        throw new IOException(getString(R.string.error_stitch_ambiguous_segment, seam));
                    }
                    // C31 continuity: LongScreenshotStitcher.analyze(manualFrames, autoMode, scrollDeltas)
                    stageStartMs = logStitchTiming(autoMode, "analyze_overlap", jobStartMs, stageStartMs);
                    if (stitchJobToken.get() != stitchToken) {
                        return;
                    }
                    publishStitchProgress(
                            generatingPreview,
                            STITCH_PROGRESS_DRAW_START,
                            true,
                            false,
                            false,
                            "");
                    Bitmap stitched = LongScreenshotStitcher.stitch(
                            stitchFrames,
                            stitchPlan.overlaps.clone(),
                            true,
                            (completed, total) -> publishStitchStageProgress(
                                    generatingPreview,
                                    STITCH_PROGRESS_DRAW_START,
                                    STITCH_PROGRESS_DRAW_END,
                                    completed,
                                    total));
                    stageStartMs = logStitchTiming(autoMode, "draw_stitched", jobStartMs, stageStartMs);
                    try {
                        if (stitchJobToken.get() != stitchToken) {
                            return;
                        }
                        publishStitchProgress(
                                generatingPreview,
                                STITCH_PROGRESS_DRAW_END,
                                true,
                                false,
                                false,
                                "");
                        preview = writePreviewFile(stitched);
                    } finally {
                        stitched.recycle();
                    }
                    stageStartMs = logStitchTiming(autoMode, "write_preview", jobStartMs, stageStartMs);
                }
                if (stitchJobToken.get() != stitchToken) {
                    return;
                }
                publishStitchProgress(
                        getString(R.string.c31_stitch_writing_debug),
                        STITCH_PROGRESS_PREVIEW,
                        true,
                        false,
                        false,
                        "");
                File recoverablePreview = DraftStore.promotePreview(this, preview);
                File stitchDebug = StitchDebugStore.write(
                        this,
                        autoMode ? "auto" : "manual",
                        stitchFrames,
                        stitchPlan,
                        recoverablePreview,
                        sourceFiles);
                stageStartMs = logStitchTiming(autoMode, "write_debug", jobStartMs, stageStartMs);
                if (stitchJobToken.get() != stitchToken) {
                    return;
                }
                publishStitchProgress(
                        getString(R.string.c31_stitch_writing_debug),
                        STITCH_PROGRESS_DEBUG,
                        true,
                        false,
                        false,
                        "");
                if (autoMode) {
                    recordAutoEvent("stitch_success", recoverablePreview.getAbsolutePath());
                    recordAutoEvent("stitch_debug", stitchDebug.getAbsolutePath());
                    closeAutoEvidence("preview_opened", true);
                }
                stitchResultPath = recoverablePreview.getAbsolutePath();
                mainHandler.post(() -> {
                    if (stitchJobToken.get() != stitchToken) {
                        return;
                    }
                    backgroundStitching = false;
                    stitchState = STITCH_DONE;
                    clearManualFrames();
                    publishStitchProgress(getString(R.string.c31_stitch_done), 100, false, true, false, stitchResultPath);
                    openPreview(recoverablePreview, autoMode ? new ArrayList<>() : sourceFiles, autoMode
                            ? PreviewActivity.RESULT_KIND_AUTO
                            : PreviewActivity.RESULT_KIND_MANUAL);
                    publishStatus(getString(autoMode
                            ? R.string.c4_status_preview_ready
                            : R.string.c3_status_preview_ready));
                    stopSelf();
                });
            } catch (OutOfMemoryError error) {
                if (stitchJobToken.get() != stitchToken) {
                    return;
                }
                backgroundStitching = false;
                stitchState = STITCH_FAILED;
                publishStitchProgress(
                        getString(R.string.c31_stitch_failed, getString(R.string.c6_status_memory_limit)),
                        0,
                        false,
                        false,
                        false,
                        "");
                clearManualFrames();
                if (autoMode) {
                    recordAutoEvent("stitch_oom", getString(R.string.c6_status_memory_limit));
                    closeAutoEvidence("stitch_oom", false);
                }
                publishStatus(getString(
                        autoMode ? R.string.c4_status_failed : R.string.c3_status_failed,
                        getString(R.string.c6_status_memory_limit)));
                if (autoMode) {
                    mainHandler.post(() -> {
                        refreshOverlay();
                        mainHandler.postDelayed(this::stopSelf, AUTO_TERMINAL_STATUS_MS);
                    });
                } else {
                    mainHandler.postDelayed(this::stopSelf, AUTO_TERMINAL_STATUS_MS);
                }
            } catch (Exception exception) {
                if (stitchJobToken.get() != stitchToken) {
                    return;
                }
                backgroundStitching = false;
                stitchState = STITCH_FAILED;
                publishStitchProgress(
                        getString(R.string.c31_stitch_failed, exception.getMessage()),
                        0,
                        false,
                        false,
                        !manualFrames.isEmpty(),
                        "");
                if (autoMode) {
                    recordAutoEvent("stitch_exception", exception.getMessage());
                    closeAutoEvidence("stitch_exception", false);
                }
                publishStatus(getString(
                        autoMode ? R.string.c4_status_failed : R.string.c3_status_failed,
                        exception.getMessage()));
            }
        });
    }

    private void releaseProjectionForBackgroundStitch() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            releasingProjectionForStitch = true;
            try {
                projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (RuntimeException ignored) {
                // ponytail: projection release after the last frame is best-effort; stopSession still owns final cleanup.
            } finally {
                projection = null;
                releasingProjectionForStitch = false;
            }
        }
    }

    private void startStitchQueueActivity() {
        Intent intent = new Intent(this, StitchQueueActivity.class)
                .putExtra(StitchQueueActivity.EXTRA_EXPECT_NEW_JOB, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void cancelStitchJob() {
        if (stitchState != STITCH_RUNNING) {
            publishCurrentStitchStatus();
            return;
        }
        stitchJobToken.incrementAndGet();
        backgroundStitching = false;
        stitchState = STITCH_CANCELED;
        publishStatus(getString(R.string.c31_stitch_canceled));
        publishStitchProgress(
                getString(R.string.c31_stitch_canceled),
                stitchProgress,
                false,
                false,
                !manualFrames.isEmpty(),
                "");
        if (stitchAutoMode) {
            recordAutoEvent("stitch_canceled", "user_cancel");
            closeAutoEvidence("stitch_canceled", false);
        }
    }

    private void retryStitchJob() {
        if (stitchState == STITCH_RUNNING) {
            publishCurrentStitchStatus();
            return;
        }
        if (manualFrames.isEmpty()) {
            publishStitchProgress(getString(R.string.c31_stitch_no_frames), 0, false, false, false, "");
            return;
        }
        enqueueStitchJob(stitchAutoMode);
    }

    private void publishCurrentStitchStatus() {
        if (stitchState == STITCH_IDLE) {
            publishStitchProgress(getString(R.string.c31_stitch_not_running), 0, false, false, false, "");
            return;
        }
        publishStitchProgress(
                stitchProgressMessage.isEmpty()
                        ? getString(R.string.c31_stitch_preparing)
                        : stitchProgressMessage,
                stitchProgress,
                stitchState == STITCH_RUNNING,
                stitchState == STITCH_DONE,
                stitchState != STITCH_RUNNING && !manualFrames.isEmpty(),
                stitchResultPath);
    }

    private void publishStitchProgress(
            String message,
            int progress,
            boolean running,
            boolean done,
            boolean canRetry,
            String resultPath) {
        stitchProgressMessage = message == null ? "" : message;
        stitchProgress = Math.max(0, Math.min(100, progress));
        if (resultPath != null && !resultPath.isEmpty()) {
            stitchResultPath = resultPath;
        }
        Intent intent = new Intent(ACTION_STITCH_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, stitchProgressMessage);
        intent.putExtra(EXTRA_STITCH_PROGRESS, stitchProgress);
        intent.putExtra(EXTRA_STITCH_RUNNING, running);
        intent.putExtra(EXTRA_STITCH_DONE, done);
        intent.putExtra(EXTRA_STITCH_CAN_RETRY, canRetry);
        intent.putExtra(EXTRA_STITCH_RESULT_PATH, stitchResultPath);
        sendBroadcast(intent, PERMISSION_INTERNAL_BROADCAST);
        updateCaptureNotification(stitchProgressMessage);
    }

    private void publishStitchStageProgress(
            String message,
            int start,
            int end,
            int completed,
            int total) {
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(safeTotal, completed));
        int progress = start + (end - start) * safeCompleted / safeTotal;
        publishStitchProgress(message, progress, true, false, false, "");
    }

    private long logStitchTiming(boolean autoMode, String stage, long jobStartMs, long stageStartMs) {
        long now = SystemClock.elapsedRealtime();
        String detail = stage
                + "_ms=" + (now - stageStartMs)
                + ",total_ms=" + (now - jobStartMs)
                + ",frames=" + manualFrames.size();
        Log.i(LOG_TAG, "stitch_timing " + detail);
        if (autoMode) {
            recordAutoEvent("stitch_timing", detail);
        }
        return now;
    }

    private File writePreviewFile(Bitmap bitmap) throws IOException {
        return writePreviewFile(bitmap, "manual", "WhiteYunLongShot");
    }

    private File writePreviewFile(Bitmap bitmap, String cacheDir, String prefix) throws IOException {
        Bitmap.CompressFormat format = Bitmap.CompressFormat.PNG;
        int quality = 100;
        String extension = ".png";
        File file = createPreviewFile(cacheDir, prefix, extension);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(format, quality, output)) {
                throw new IOException("image write failed");
            }
        }
        return file;
    }

    private File createPreviewFile(String cacheDir, String prefix, String extension) throws IOException {
        File dir = new File(getCacheDir(), cacheDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        return new File(dir, prefix + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + extension);
    }

    private ArrayList<String> writeOriginalFrameFiles(
            List<Bitmap> frames,
            String cacheDir,
            FrameWriteProgress progress) throws IOException {
        ArrayList<String> paths = new ArrayList<>();
        File dir = new File(getCacheDir(), cacheDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        for (int i = 0; i < frames.size(); i++) {
            File file = new File(dir, "WhiteYunOriginal_" + stamp + "_" + (i + 1) + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("PNG write failed");
                }
            }
            paths.add(file.getAbsolutePath());
            if (progress != null) {
                progress.onFrameWritten(i + 1, frames.size());
            }
        }
        return paths;
    }

    private File writeAutoFrameFile(Bitmap bitmap, int index) throws IOException {
        File cache = getCacheDir();
        if (cache.getUsableSpace() < AUTO_STORAGE_RESERVE_BYTES) {
            throw new IOException(getString(R.string.error_storage_low));
        }
        if (autoFrameDir == null) {
            File root = new File(cache, "auto-frames");
            autoFrameDir = new File(root, "auto_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()));
            if (!autoFrameDir.isDirectory() && !autoFrameDir.mkdirs()) {
                throw new IOException("Cannot create auto frame dir");
            }
        }
        File file = new File(autoFrameDir, String.format(Locale.US, "frame_%05d.png", index + 1));
        File part = new File(file.getAbsolutePath() + ".part");
        part.delete();
        long writeStartMs = SystemClock.elapsedRealtime();
        try {
            StreamingLongScreenshotStitcher.writeFramePng(bitmap, part);
        } catch (IOException exception) {
            part.delete();
            file.delete();
            throw exception;
        }
        if ((file.exists() && !file.delete()) || !part.renameTo(file)) {
            part.delete();
            throw new IOException(getString(R.string.error_auto_frames_publish));
        }
        if (cache.getUsableSpace() < AUTO_STORAGE_RESERVE_BYTES) {
            file.delete();
            throw new IOException(getString(R.string.error_storage_low));
        }
        Log.i(LOG_TAG, "auto_frame_spool index=" + (index + 1)
                + " ms=" + (SystemClock.elapsedRealtime() - writeStartMs)
                + " bytes=" + file.length());
        return file;
    }

    private ArrayList<File> autoFrameFilesForStitch() throws IOException {
        if (autoFrameFiles.size() != manualFrames.size()) {
            throw new IOException(getString(R.string.error_auto_manifest_incomplete));
        }
        ArrayList<File> files = new ArrayList<>(autoFrameFiles);
        for (File file : files) {
            if (!file.isFile()) {
                throw new IOException(getString(R.string.error_auto_frame_missing));
            }
        }
        if (autoScrollReverseDirection) {
            Collections.reverse(files);
        }
        return files;
    }

    private static ArrayList<String> filePaths(List<File> files) {
        ArrayList<String> paths = new ArrayList<>(files.size());
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
    }

    private int[] frameScrollDeltasArray() {
        int[] values = new int[manualFrames.size()];
        int count = Math.min(values.length, frameScrollDeltas.size());
        for (int i = 0; i < count; i++) {
            values[i] = frameScrollDeltas.get(i);
        }
        return values;
    }

    private List<Bitmap> framesForStitch(boolean autoMode) {
        ArrayList<Bitmap> frames = new ArrayList<>(manualFrames);
        if (autoMode && autoScrollReverseDirection) {
            Collections.reverse(frames);
        }
        return frames;
    }

    private int[] stitchScrollDeltasArray(boolean autoMode, int frameCount) {
        int[] values = frameScrollDeltasArray();
        if (!autoMode || !autoScrollReverseDirection) {
            return values;
        }
        int[] reversed = new int[frameCount];
        int count = Math.min(frameCount, values.length);
        for (int i = 1; i < count; i++) {
            reversed[i] = values[count - i];
        }
        return reversed;
    }

    private void openPreview(File file, ArrayList<String> sourceFiles, String resultKind) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, resultKind);
        intent.putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, DraftStore.isDraftFile(this, file));
        if (sourceFiles != null && !sourceFiles.isEmpty()) {
            intent.putStringArrayListExtra(PreviewActivity.EXTRA_SOURCE_FILE_PATHS, sourceFiles);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private Uri saveImage(Image image) throws IOException {
        Bitmap bitmap = cropSystemBars(bitmapFromImage(image));
        ContentResolver resolver = getContentResolver();
        String name = "WhiteYunScreenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WhiteYunScreenshot");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            bitmap.recycle();
            throw new IOException("MediaStore insert returned null");
        }

        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG write failed");
            }
        } catch (IOException exception) {
            resolver.delete(uri, null, null);
            throw exception;
        } finally {
            bitmap.recycle();
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private Bitmap bitmapFromImage(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int paddedWidth = width + (rowStride - pixelStride * width) / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == width) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private Bitmap cropSystemBars(Bitmap bitmap) {
        int top = AppPreferences.isCaptureStatusBarEnabled(this)
                ? 0
                : systemBarPixels("status_bar_height");
        int bottom = systemBarPixels("navigation_bar_height");
        int maxCrop = Math.max(0, bitmap.getHeight() / 4);
        int cropTop = Math.min(top, maxCrop);
        int cropBottom = Math.min(bottom, maxCrop);
        int height = bitmap.getHeight() - cropTop - cropBottom;
        if (height <= bitmap.getHeight() / 2 || (cropTop == 0 && cropBottom == 0)) {
            return bitmap;
        }
        // ponytail: platform bar dimensions cover normal screenshots; tune per-device in C12 if gesture/fullscreen modes need it.
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.getWidth(), height);
        bitmap.recycle();
        return cropped;
    }

    private int systemBarPixels(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private void showOverlay() {
        if (sessionEnding || !Settings.canDrawOverlays(this)) {
            return;
        }
        updateRangeOverlay();
        if (overlayView != null) {
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(0, 0, 0, dp(4));
        if (overlayStage != OVERLAY_STAGE_READY) {
            panel.setMinimumWidth(dp(176));
        }

        if (overlayStage == OVERLAY_STAGE_READY) {
            Button start = overlayButton(R.string.overlay_start, 0xff11998e);
            start.setOnClickListener(view -> beginCaptureFlow(false));
            panel.addView(start);

            Button cancel = overlayButton(R.string.overlay_cancel, 0xffe53935);
            cancel.setOnClickListener(view -> {
                publishStatus(getString(R.string.c2_status_stopped));
                stopSelf();
            });
            panel.addView(cancel);
        } else if (MODE_AUTO.equals(captureMode)) {
            addAutoRunningPanel(panel);
        } else {
            String infoText = "";
            int infoMaxWidthDp = 160;
            boolean dynamicInfo = false;
            if (MODE_MANUAL.equals(captureMode)) {
                infoText = manualFrames.isEmpty()
                        ? (lastStatusMessage == null || lastStatusMessage.isEmpty()
                                ? getString(R.string.c35_manual_scroll_intro)
                                : lastStatusMessage)
                        : (lastStatusMessage == null || lastStatusMessage.isEmpty()
                                ? getString(R.string.c3_status_sampled, manualFrames.size())
                                : lastStatusMessage);
                infoMaxWidthDp = 240;
                dynamicInfo = true;
            } else if (MODE_AUTO.equals(captureMode)) {
                infoText = lastStatusMessage == null || lastStatusMessage.isEmpty()
                        ? getString(R.string.c4_status_running)
                        : lastStatusMessage;
                infoMaxWidthDp = 176;
                dynamicInfo = true;
            } else if (speedMode) {
                infoText = getString(R.string.scroll_intro);
            }
            if (!infoText.isEmpty()) {
                TextView info = new TextView(this);
                info.setText(infoText);
                info.setTextColor(0xffffffff);
                info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                info.setMaxWidth(dp(infoMaxWidthDp));
                info.setPadding(dp(8), dp(6), dp(8), dp(6));
                info.setBackgroundColor(0xcc111827);
                LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                statusParams.bottomMargin = dp(8);
                panel.addView(info, statusParams);
                if (dynamicInfo) {
                    overlayStatusText = info;
                }
            }
            boolean autoFinishingNow = MODE_AUTO.equals(captureMode) && autoFinishing;
            boolean needsCaptureButton = !MODE_AUTO.equals(captureMode)
                    && !(MODE_MANUAL.equals(captureMode) && manualScrollCaptureEnabled);
            if (needsCaptureButton) {
                Button capture = overlayButton(R.string.overlay_capture, 0xff33b5e5);
                capture.setOnClickListener(view -> {
                    if (MODE_MANUAL.equals(captureMode)) {
                        requestManualSample();
                    } else {
                        requestSaveFrame();
                    }
                });
                panel.addView(capture);
            }

            if (!autoFinishingNow) {
                Button done = overlayButton(R.string.overlay_done, 0xff11998e);
                done.setOnClickListener(view -> finishCaptureFlow());
                panel.addView(done);
            }
            if (MODE_MANUAL.equals(captureMode) || MODE_AUTO.equals(captureMode)) {
                Button cancel = overlayButton(R.string.overlay_cancel, 0xffe53935);
                cancel.setOnClickListener(view -> cancelCaptureSession());
                panel.addView(cancel);
            }
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.x = dp(16);
        if (overlayStage == OVERLAY_STAGE_RUNNING && MODE_AUTO.equals(captureMode)) {
            DisplaySpec spec = displaySpec();
            int minY = systemBarPixels("status_bar_height") + dp(16);
            int maxY = spec.height - systemBarPixels("navigation_bar_height") - dp(156);
            params.gravity = Gravity.START | Gravity.TOP;
            params.y = Math.max(minY, Math.min(maxY, autoStopLineY(spec.height) + dp(52)));
        } else {
            params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            params.y = 0;
        }
        windowManager.addView(panel, params);
        overlayView = panel;
    }

    private void addAutoRunningPanel(LinearLayout panel) {
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setMinimumWidth(dp(196));
        panel.setBackground(roundedBackground(0xe61f2937, dp(10), 0x33ffffff));

        TextView info = new TextView(this);
        String infoText = lastStatusMessage == null || lastStatusMessage.isEmpty()
                ? getString(R.string.c4_status_running)
                : lastStatusMessage;
        info.setText(infoText);
        info.setTextColor(0xffffffff);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setGravity(Gravity.CENTER);
        info.setMaxWidth(dp(196));
        info.setMaxLines(2);
        info.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        overlayStatusText = info;

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(8);
        panel.addView(actions, actionParams);

        if (!autoFinishing) {
            Button done = overlayButton(R.string.overlay_done, 0xff11998e);
            done.setOnClickListener(view -> finishCaptureFlow());
            actions.addView(done, compactButtonParams());
        }

        Button cancel = overlayButton(R.string.overlay_cancel, 0xffe53935);
        cancel.setOnClickListener(view -> cancelCaptureSession());
        actions.addView(cancel, compactButtonParams());
    }

    private Button overlayButton(int labelRes, int color) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        button.setTextColor(0xffffffff);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(48));
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private GradientDrawable roundedBackground(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private void refreshOverlay() {
        removeControlOverlay();
        showOverlay();
    }

    private void removeOverlay() {
        removeControlOverlay();
        removeRangeOverlay();
    }

    private void removeControlOverlay() {
        if (overlayView != null) {
            overlayView.setVisibility(View.GONE);
            overlayView.setAlpha(0f);
            windowManager.removeViewImmediate(overlayView);
            overlayView = null;
            overlayStatusText = null;
        }
    }

    private void removeCaptureOverlays() {
        removeOverlay();
    }

    private void updateRangeOverlay() {
        if (!Settings.canDrawOverlays(this) || !shouldShowRangeOverlay()) {
            removeRangeOverlay();
            return;
        }
        showRangeOverlay();
    }

    private void removeRangeOverlay() {
        removeAutoStopTouchOverlay();
        if (rangeOverlayView != null) {
            rangeOverlayView.setVisibility(View.GONE);
            windowManager.removeViewImmediate(rangeOverlayView);
            rangeOverlayView = null;
        }
    }

    private void showRangeOverlay() {
        boolean autoStopLine = MODE_AUTO.equals(captureMode);
        if (rangeOverlayView == null) {
            CaptureRangeOverlayView view = new CaptureRangeOverlayView(
                    this,
                    systemBarPixels("status_bar_height"),
                    systemBarPixels("navigation_bar_height"),
                    autoStopLine);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.START | Gravity.TOP;
            windowManager.addView(view, params);
            rangeOverlayView = view;
        }
        if (autoStopLine) {
            showAutoStopTouchOverlay();
        } else {
            removeAutoStopTouchOverlay();
        }
    }

    private void showAutoStopTouchOverlay() {
        if (autoStopTouchView != null) {
            return;
        }
        View view = new View(this);
        view.setOnClickListener(clicked -> {
            if (MODE_AUTO.equals(captureMode) && autoRunning && !autoFinishing) {
                recordAutoEvent("red_line_stop", "tap_above_line");
                publishStatus(getString(R.string.c4_status_user_done));
                finishAutoLongShot("red_line_stop");
            }
        });
        int touchHeight = Math.max(dp(48), autoStopLineY(displaySpec().height));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                touchHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.TOP;
        windowManager.addView(view, params);
        autoStopTouchView = view;
    }

    private void removeAutoStopTouchOverlay() {
        if (autoStopTouchView != null) {
            autoStopTouchView.setVisibility(View.GONE);
            windowManager.removeViewImmediate(autoStopTouchView);
            autoStopTouchView = null;
        }
    }

    private boolean shouldShowRangeOverlay() {
        return overlayStage == OVERLAY_STAGE_RUNNING
                && !backgroundStitching
                && !(MODE_AUTO.equals(captureMode) && autoFinishing)
                && (MODE_AUTO.equals(captureMode) || MODE_MANUAL.equals(captureMode));
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent progressPendingIntent = PendingIntent.getActivity(
                this,
                7,
                new Intent(this, StitchProgressActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true);

        if (stitchState == STITCH_RUNNING) {
            // Foreground-service notification stays visible while CPU stitching finishes.
            builder.setContentIntent(progressPendingIntent)
                    .setProgress(100, stitchProgress, false)
                    .addAction(R.drawable.ic_stat_capture, getString(R.string.c31_stitch_cancel),
                            servicePendingIntent(8, ACTION_STITCH_CANCEL));
        } else if (stitchState == STITCH_CANCELED || stitchState == STITCH_FAILED) {
            builder.setContentIntent(progressPendingIntent)
                    .setProgress(0, 0, false)
                    .addAction(R.drawable.ic_stat_capture, getString(R.string.c31_stitch_retry),
                            servicePendingIntent(9, ACTION_STITCH_RETRY))
                    .addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_cancel), stopPendingIntent);
        } else if (accessibilityAttentionNeeded) {
            PendingIntent accessibilityPendingIntent = accessibilitySettingsPendingIntent();
            builder.setContentIntent(accessibilityPendingIntent)
                    .addAction(R.drawable.ic_stat_capture,
                            getString(R.string.action_grant_accessibility),
                            accessibilityPendingIntent);
        } else if (overlayStage == OVERLAY_STAGE_READY) {
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_start),
                    capturePendingIntent(5, MODE_AUTO.equals(captureMode) ? ACTION_AUTO_START : ACTION_CAPTURE_BEGIN));
        } else if (MODE_MANUAL.equals(captureMode)) {
            if (!manualScrollCaptureEnabled) {
                builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_capture),
                        capturePendingIntent(3, ACTION_MANUAL_SAMPLE));
            }
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_done),
                    capturePendingIntent(4, ACTION_MANUAL_FINISH));
        } else if (MODE_AUTO.equals(captureMode)) {
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_done),
                    capturePendingIntent(6, ACTION_MANUAL_FINISH));
        } else {
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_capture),
                    capturePendingIntent(1, ACTION_SAVE_FRAME));
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_done),
                    capturePendingIntent(4, ACTION_MANUAL_FINISH));
        }
        if (stitchState != STITCH_RUNNING
                && stitchState != STITCH_CANCELED
                && stitchState != STITCH_FAILED) {
            builder.addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_cancel), stopPendingIntent);
        }
        return builder.build();
    }

    private PendingIntent capturePendingIntent(int requestCode, String action) {
        return servicePendingIntent(requestCode, action);
    }

    private PendingIntent servicePendingIntent(int requestCode, String action) {
        Intent intent = new Intent(this, CaptureService.class).setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent accessibilitySettingsPendingIntent() {
        return PendingIntent.getActivity(
                this,
                10,
                accessibilitySettingsIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Intent accessibilitySettingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(accessibilitySettingsIntent());
        } catch (RuntimeException ignored) {
            // Notification action still gives the user a direct way into the same settings screen.
        }
    }

    private void ensureNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private void publishStatus(String message) {
        lastStatusMessage = message == null ? "" : message;
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, lastStatusMessage);
        sendBroadcast(intent, PERMISSION_INTERNAL_BROADCAST);
        updateOverlayStatus(lastStatusMessage);
        updateCaptureNotification(lastStatusMessage);
    }

    private void updateOverlayStatus(String message) {
        if (overlayStatusText == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            overlayStatusText.setText(message);
        } else {
            mainHandler.post(() -> {
                if (overlayStatusText != null) {
                    overlayStatusText.setText(message);
                }
            });
        }
    }

    private void updateCaptureNotification(String message) {
        if (!captureForegroundStarted) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    private void recordAutoEvent(String type, String detail) {
        AutoScrollEvidenceStore.Session session = autoEvidenceSession;
        if (session == null || !session.isEnabled()) {
            return;
        }
        int acceptedFrames = manualFrames.size();
        int failures = autoScrollFailures;
        Handler handler = captureHandler;
        if (handler != null && Looper.myLooper() != handler.getLooper()) {
            handler.post(() -> session.recordEvent(type, detail, acceptedFrames, failures));
            return;
        }
        session.recordEvent(type, detail, acceptedFrames, failures);
    }

    private void recordAutoWindow(String reason) {
        AutoScrollEvidenceStore.Session session = autoEvidenceSession;
        if (session == null || !session.isEnabled()) {
            return;
        }
        Handler handler = captureHandler;
        if (!AutoScrollAccessibilityService.captureActiveWindow(snapshot -> {
            if (handler != null) {
                handler.post(() -> session.recordWindow(reason, snapshot));
            } else {
                session.recordWindow(reason, snapshot);
            }
        })) {
            session.recordWindow(reason, AutoScrollEvidenceStore.WindowSnapshot.unavailable("accessibility unavailable"));
        }
    }

    private void closeAutoEvidence(String reason, boolean success) {
        AutoScrollEvidenceStore.Session session = autoEvidenceSession;
        autoEvidenceSession = null;
        if (session != null && session.isEnabled()) {
            session.close(reason, success);
        }
    }

    private void failAndStop(String message) {
        publishStatus(message);
        stopSelf();
    }

    private void cancelCaptureSession() {
        sessionEnding = true;
        stopScrollObservation();
        manualCaptureGeneration.incrementAndGet();
        cancelPendingManualFrameRequest();
        publishStatus(getString(R.string.c2_status_stopped));
        mainHandler.post(this::removeOverlay);
        Handler handler = captureHandler;
        if (handler == null) {
            stopSelf();
            return;
        }
        // Let an already-running buffer copy finish before stopSession recycles its bitmaps and closes ImageReader.
        handler.post(() -> mainHandler.post(this::stopSelf));
    }

    private void stopSession() {
        sessionEnding = true;
        accessibilityAttentionNeeded = false;
        stopScrollObservation();
        manualCaptureGeneration.incrementAndGet();
        stitchJobToken.incrementAndGet();
        closeAutoEvidence("capture_service_stopped", false);
        removeOverlay();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        frameRequestInFlight.set(FRAME_NONE);
        activeFrameRequestToken = 0;
        clearManualFrames();
        autoScrollFailures = 0;
        autoRunning = false;
        autoFinishing = false;
        backgroundStitching = false;
        releasingProjectionForStitch = false;
        autoFrameAttempts = 0;
        captureForegroundStarted = false;
        overlayStatusText = null;
        lastStatusMessage = "";
        overlayStage = OVERLAY_STAGE_READY;
        speedMode = false;
        captureMode = MODE_CORE;
        lastManualFingerprint = 0L;
        resetScrollTracking();
        captureTargetPackage = "";
        captureTargetWindowId = -1;
        captureTargetSourceKey = "";
    }

    private void clearManualFrames() {
        for (Bitmap bitmap : manualFrames) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        manualFrames.clear();
        frameScrollDeltas.clear();
        autoFrameFiles.clear();
        if (autoFrameDir != null) {
            deleteRecursively(autoFrameDir);
            autoFrameDir = null;
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

    private DisplaySpec displaySpec() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new DisplaySpec(
                    bounds.width(),
                    bounds.height(),
                    getResources().getConfiguration().densityDpi);
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new DisplaySpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }

    private int sp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                getResources().getDisplayMetrics());
    }

    private int autoStopLineY(int height) {
        int preferred = height * AUTO_STOP_LINE_NUMERATOR / AUTO_STOP_LINE_DENOMINATOR;
        int min = systemBarPixels("status_bar_height") + dp(96);
        int max = height - systemBarPixels("navigation_bar_height") - dp(360);
        if (max < min) {
            return Math.max(dp(48), preferred);
        }
        return Math.max(min, Math.min(max, preferred));
    }

    private final class CaptureRangeOverlayView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int topInset;
        private final int bottomInset;
        private final boolean autoStopLine;

        CaptureRangeOverlayView(Context context, int topInset, int bottomInset, boolean autoStopLine) {
            super(context);
            this.topInset = topInset;
            this.bottomInset = bottomInset;
            this.autoStopLine = autoStopLine;
            linePaint.setColor(0xe6ff3b30);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setPathEffect(new DashPathEffect(new float[] {dp(10), dp(8)}, 0));
            hintBackgroundPaint.setColor(0xdd111827);
            hintBackgroundPaint.setStyle(Paint.Style.FILL);
            hintStrokePaint.setColor(0xccff3b30);
            hintStrokePaint.setStyle(Paint.Style.STROKE);
            hintStrokePaint.setStrokeWidth(dp(1));
            hintTextPaint.setColor(0xffffffff);
            hintTextPaint.setTextSize(sp(13));
            hintTextPaint.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (autoStopLine) {
                drawAutoStopLine(canvas);
                return;
            }
            int inset = dp(14);
            int top = clampRangeLine(topInset + dp(8), dp(16), getHeight() - dp(120));
            int bottom = clampRangeLine(getHeight() - bottomInset - dp(8), top + dp(120), getHeight() - dp(16));
            Rect rect = new Rect(inset, top, getWidth() - inset, bottom);
            // ponytail: this is the system-bar-free capture range; fixed app chrome is still cleaned later by the stitcher.
            canvas.drawRect(rect, linePaint);
        }

        private void drawAutoStopLine(Canvas canvas) {
            int lineY = autoStopLineY(getHeight());
            linePaint.setPathEffect(null);
            linePaint.setStrokeWidth(dp(3));
            canvas.drawLine(0, lineY, getWidth(), lineY, linePaint);

            String hint = getString(R.string.c30_red_line_hint);
            float textWidth = hintTextPaint.measureText(hint);
            float left = Math.max(dp(16), (getWidth() - textWidth) / 2f - dp(14));
            float right = Math.min(getWidth() - dp(16), left + textWidth + dp(28));
            if (right == getWidth() - dp(16)) {
                left = Math.max(dp(16), right - textWidth - dp(28));
            }
            float bottom = Math.max(dp(48), lineY - dp(12));
            float top = bottom - dp(34);
            RectF bubble = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(bubble, dp(17), dp(17), hintBackgroundPaint);
            canvas.drawRoundRect(bubble, dp(17), dp(17), hintStrokePaint);
            Paint.FontMetrics metrics = hintTextPaint.getFontMetrics();
            float baseline = bubble.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(hint, bubble.left + dp(14), baseline, hintTextPaint);
        }

        private int clampRangeLine(int value, int min, int max) {
            if (max < min) {
                return min;
            }
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class DisplaySpec {
        final int width;
        final int height;
        final int densityDpi;

        DisplaySpec(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }
}
