package com.whiteyun.screenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A single foreground worker that drains durable long-shot stitch jobs in FIFO order. */
public final class StitchQueueService extends LocalizedService {
    static final String ACTION_PROCESS = "com.whiteyun.screenshot.action.PROCESS_STITCH_QUEUE";
    static final String EXTRA_JOB_ID = "stitch_job_id";

    private static final String CHANNEL_ID = "stitch_queue";
    private static final String COMPLETION_CHANNEL_ID = "stitch_complete";
    private static final int NOTIFICATION_ID = 11;
    private static final int COMPLETION_NOTIFICATION_BASE = 400;

    private HandlerThread workerThread;
    private Handler worker;
    private volatile String canceledJobId = "";
    private volatile StitchQueueStore.Job currentJob;
    private boolean processing;
    private int lastSavedProgress = -1;
    private String lastSavedMessage = "";

    public static void start(Context context) {
        Intent intent = new Intent(context, StitchQueueService.class).setAction(ACTION_PROCESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void resumePending(Context context) {
        if (StitchQueueStore.hasPending(context)) {
            start(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        workerThread = new HandlerThread("whiteyun-stitch-queue");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_PROCESS : intent.getAction();
        String jobId = intent == null ? "" : intent.getStringExtra(EXTRA_JOB_ID);
        if (CaptureService.ACTION_STITCH_POLL.equals(action)) {
            worker.post(this::publishCurrentStatus);
            return START_NOT_STICKY;
        }
        if (CaptureService.ACTION_STITCH_CANCEL.equals(action)) {
            StitchQueueStore.Job running = currentJob;
            if (running != null && (jobId == null || jobId.isEmpty() || running.id.equals(jobId))) {
                canceledJobId = running.id;
            } else {
                String requestedJobId = jobId == null ? "" : jobId;
                worker.post(() -> cancelQueuedJob(requestedJobId));
            }
            return START_NOT_STICKY;
        }
        if (CaptureService.ACTION_STITCH_RETRY.equals(action)) {
            startForegroundForQueue(getString(R.string.c51_stitch_queued));
            String requestedJobId = jobId == null ? "" : jobId;
            worker.post(() -> retryJob(requestedJobId));
            return START_REDELIVER_INTENT;
        }
        startForegroundForQueue(getString(R.string.c51_stitch_queued));
        worker.post(this::processNext);
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        HandlerThread thread = workerThread;
        workerThread = null;
        worker = null;
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void processNext() {
        if (processing) {
            return;
        }
        StitchQueueStore.Job job;
        try {
            job = StitchQueueStore.takeNext(this);
        } catch (IOException exception) {
            publishError(getString(R.string.c51_stitch_queue_failed, exception.getMessage()));
            stopWhenIdle();
            return;
        }
        if (job == null) {
            publishCurrentStatus();
            stopWhenIdle();
            return;
        }
        processing = true;
        currentJob = job;
        canceledJobId = "";
        lastSavedProgress = -1;
        lastSavedMessage = "";
        runJob(job);
    }

    private void runJob(StitchQueueStore.Job job) {
        try {
            publishJob(
                    job,
                    getString(R.string.c51_stitch_running),
                    5,
                    true,
                    false,
                    false,
                    "");
            File output = new File(job.directory, "result.png");
            StreamingLongScreenshotStitcher.Result result = StreamingLongScreenshotStitcher.stitch(
                    job.frameFiles,
                    job.scrollDeltas,
                    output,
                    (completed, total) -> {
                        checkCanceled(job);
                        publishProgress(job, R.string.c31_stitch_analyzing_overlap, 24, 64, completed, total);
                    },
                    (completed, total) -> {
                        checkCanceled(job);
                        publishProgress(job, R.string.c31_stitch_generating_preview, 64, 94, completed, total);
                    });
            checkCanceled(job);
            publishJob(
                    job,
                    getString(R.string.c31_stitch_writing_debug),
                    94,
                    true,
                    false,
                    false,
                    "");
            File draft = DraftStore.promotePreview(this, result.file);
            ArrayList<String> sourceFiles = filePaths(job.frameFiles);
            StitchDebugStore.write(this, job.mode, null, result.plan, draft, sourceFiles);
            int reviewSeams = countReviewSeams(result.plan);
            if (CaptureService.MODE_AUTO.equals(job.mode) && reviewSeams == 0) {
                StitchQueueStore.removeAutoFrames(job);
            }
            // ponytail: keep raw frames only for a low-confidence result so a later matcher can
            // retry it; clean automatic jobs still release their spool immediately.
            job.state = reviewSeams == 0
                    ? StitchQueueStore.STATE_DONE
                    : StitchQueueStore.STATE_REVIEW;
            job.progress = 100;
            job.resultPath = draft.getAbsolutePath();
            job.message = reviewSeams == 0
                    ? getString(R.string.c31_stitch_done)
                    : getString(R.string.c51_stitch_done_review, reviewSeams);
            StitchQueueStore.save(job);
            publishJob(job, job.message, 100, false, true, false, job.resultPath);
            showCompletionNotification(job, draft, sourceFiles);
        } catch (StitchCanceledException canceled) {
            job.state = StitchQueueStore.STATE_CANCELED;
            job.message = getString(R.string.c31_stitch_canceled);
            job.progress = 0;
            saveAndPublish(job, false, false, true);
        } catch (OutOfMemoryError error) {
            job.state = StitchQueueStore.STATE_FAILED;
            job.message = getString(R.string.c6_status_memory_limit);
            job.progress = 0;
            saveAndPublish(job, false, false, true);
        } catch (Exception error) {
            job.state = StitchQueueStore.STATE_FAILED;
            String detail = error.getMessage();
            job.message = getString(
                    R.string.c51_stitch_queue_failed,
                    detail == null || detail.isEmpty() ? error.getClass().getSimpleName() : detail);
            job.progress = 0;
            saveAndPublish(job, false, false, true);
        } finally {
            currentJob = null;
            processing = false;
            Handler handler = worker;
            if (handler != null) {
                handler.post(this::processNext);
            }
        }
    }

    private void checkCanceled(StitchQueueStore.Job job) {
        if (job.id.equals(canceledJobId)) {
            throw new StitchCanceledException();
        }
    }

    private void cancelQueuedJob(String jobId) {
        try {
            StitchQueueStore.Job canceled = StitchQueueStore.cancel(this, jobId);
            if (canceled != null) {
                publishJob(canceled, canceled.message, canceled.progress, false, false, true, "");
            } else {
                publishCurrentStatus();
            }
        } catch (IOException exception) {
            publishError(getString(R.string.c51_stitch_queue_failed, exception.getMessage()));
        }
    }

    private void retryJob(String jobId) {
        try {
            StitchQueueStore.Job job = StitchQueueStore.requeue(this, jobId);
            if (job != null) {
                publishJob(job, job.message, 0, true, false, false, "");
            }
            processNext();
        } catch (IOException exception) {
            publishError(getString(R.string.c51_stitch_queue_failed, exception.getMessage()));
            stopWhenIdle();
        }
    }

    private void publishCurrentStatus() {
        StitchQueueStore.Job job = currentJob;
        if (job == null) {
            job = StitchQueueStore.latest(this);
        }
        if (job == null) {
            publishStatus(
                    getString(R.string.c31_stitch_not_running),
                    0,
                    false,
                    false,
                    false,
                    "");
            return;
        }
        boolean running = StitchQueueStore.STATE_RUNNING.equals(job.state)
                || StitchQueueStore.STATE_QUEUED.equals(job.state);
        publishJob(
                job,
                job.message,
                job.progress,
                running,
                StitchQueueStore.STATE_DONE.equals(job.state)
                        || StitchQueueStore.STATE_REVIEW.equals(job.state),
                StitchQueueStore.STATE_FAILED.equals(job.state)
                        || StitchQueueStore.STATE_CANCELED.equals(job.state),
                job.resultPath);
        if (!processing && !StitchQueueStore.hasPending(this)) {
            stopWhenIdle();
        }
    }

    private void publishProgress(
            StitchQueueStore.Job job,
            int messageRes,
            int start,
            int end,
            int completed,
            int total) {
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(safeTotal, completed));
        int progress = start + (end - start) * safeCompleted / safeTotal;
        if (progress == job.progress && getString(messageRes).equals(job.message)) {
            return;
        }
        publishJob(job, getString(messageRes), progress, true, false, false, "");
    }

    private void saveAndPublish(StitchQueueStore.Job job, boolean running, boolean done, boolean canRetry) {
        try {
            StitchQueueStore.save(job);
        } catch (IOException exception) {
            job.message = getString(R.string.c51_stitch_queue_failed, exception.getMessage());
        }
        publishJob(job, job.message, job.progress, running, done, canRetry, job.resultPath);
    }

    private void publishJob(
            StitchQueueStore.Job job,
            String message,
            int progress,
            boolean running,
            boolean done,
            boolean canRetry,
            String resultPath) {
        job.message = message == null ? "" : message;
        job.progress = Math.max(0, Math.min(100, progress));
        if (resultPath != null && !resultPath.isEmpty()) {
            job.resultPath = resultPath;
        }
        boolean shouldPersistProgress = running
                && (lastSavedProgress < 0
                        || job.progress == 0
                        || job.progress - lastSavedProgress >= 2
                        || !job.message.equals(lastSavedMessage));
        if (shouldPersistProgress) {
            try {
                StitchQueueStore.save(job);
                lastSavedProgress = job.progress;
                lastSavedMessage = job.message;
            } catch (IOException ignored) {
                // The in-memory foreground job remains usable; its next progress write retries persistence.
            }
        }
        publishStatus(job.message, job.progress, running, done, canRetry, job.resultPath);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && running) {
            manager.notify(NOTIFICATION_ID, buildQueueNotification(job.message, job.progress, true, null, CHANNEL_ID));
        }
    }

    private void publishError(String message) {
        publishStatus(message, 0, false, false, true, "");
    }

    private void publishStatus(
            String message,
            int progress,
            boolean running,
            boolean done,
            boolean canRetry,
            String resultPath) {
        Intent intent = new Intent(CaptureService.ACTION_STITCH_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(CaptureService.EXTRA_MESSAGE, message == null ? "" : message);
        intent.putExtra(CaptureService.EXTRA_STITCH_PROGRESS, Math.max(0, Math.min(100, progress)));
        intent.putExtra(CaptureService.EXTRA_STITCH_RUNNING, running);
        intent.putExtra(CaptureService.EXTRA_STITCH_DONE, done);
        intent.putExtra(CaptureService.EXTRA_STITCH_CAN_RETRY, canRetry);
        intent.putExtra(CaptureService.EXTRA_STITCH_RESULT_PATH, resultPath == null ? "" : resultPath);
        sendBroadcast(intent, CaptureService.PERMISSION_INTERNAL_BROADCAST);
    }

    private void startForegroundForQueue(String message) {
        Notification notification = buildQueueNotification(message, 0, true, null, CHANNEL_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildQueueNotification(
            String message,
            int progress,
            boolean ongoing,
            PendingIntent completionIntent,
            String channelId) {
        Intent progressIntent = new Intent(this, StitchQueueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = completionIntent == null
                ? PendingIntent.getActivity(
                        this,
                        11,
                        progressIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : completionIntent;
        Notification.Builder builder = new Notification.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle(getString(R.string.c51_stitch_queue_notification))
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) {
            builder.setProgress(100, Math.max(0, Math.min(100, progress)), false);
            Intent cancel = new Intent(this, StitchQueueService.class)
                    .setAction(CaptureService.ACTION_STITCH_CANCEL);
            builder.addAction(
                    R.drawable.ic_stat_capture,
                    getString(R.string.c31_stitch_cancel),
                    PendingIntent.getService(
                            this,
                            12,
                            cancel,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            builder.setAutoCancel(true);
        }
        return builder.build();
    }

    private void showCompletionNotification(
            StitchQueueStore.Job job,
            File draft,
            ArrayList<String> sourceFiles) {
        Intent intent = new Intent(this, PreviewActivity.class)
                .putExtra(PreviewActivity.EXTRA_IMAGE_PATH, draft.getAbsolutePath())
                .putExtra(
                        PreviewActivity.EXTRA_RESULT_KIND,
                        CaptureService.MODE_AUTO.equals(job.mode)
                                ? PreviewActivity.RESULT_KIND_AUTO
                                : PreviewActivity.RESULT_KIND_MANUAL)
                .putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!CaptureService.MODE_AUTO.equals(job.mode) && !sourceFiles.isEmpty()) {
            intent.putStringArrayListExtra(PreviewActivity.EXTRA_SOURCE_FILE_PATHS, sourceFiles);
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                COMPLETION_NOTIFICATION_BASE + Math.abs(job.id.hashCode() % 1000),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            if (!AppPreferences.isStitchNotificationsEnabled(this)) {
                return;
            }
            manager.notify(
                    COMPLETION_NOTIFICATION_BASE + Math.abs(job.id.hashCode() % 1000),
                    buildQueueNotification(job.message, 100, false, pendingIntent, COMPLETION_CHANNEL_ID));
        }
    }

    private void stopWhenIdle() {
        if (processing || StitchQueueStore.hasPending(this)) {
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    private void ensureNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.c51_stitch_queue_notification),
                NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                COMPLETION_CHANNEL_ID,
                getString(R.string.c51_stitch_complete_notification),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static ArrayList<String> filePaths(List<File> files) {
        ArrayList<String> paths = new ArrayList<>(files.size());
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
    }

    private static int countReviewSeams(LongScreenshotStitcher.StitchPlan plan) {
        if (plan == null || plan.manualRequired == null) {
            return 0;
        }
        int count = 0;
        for (int i = 1; i < plan.manualRequired.length; i++) {
            if (plan.manualRequired[i]) {
                count++;
            }
        }
        return count;
    }

    private static final class StitchCanceledException extends RuntimeException {
    }
}
