package com.whiteyun.screenshot;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/** Shows durable stitch jobs and keeps the queue actionable while the app is open. */
public final class StitchQueueActivity extends LocalizedActivity {
    static final String EXTRA_EXPECT_NEW_JOB = "expect_new_stitch_job";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private boolean receiverRegistered;
    private long pendingEnqueueStartedAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE);
            boolean running = intent.getBooleanExtra(CaptureService.EXTRA_STITCH_RUNNING, false);
            if (getString(R.string.c52_stitch_queue_preparing).equals(message)) {
                pendingEnqueueStartedAt = System.currentTimeMillis();
            } else if (!running && message != null && !message.isEmpty()) {
                pendingEnqueueStartedAt = 0;
            }
            refresh();
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_EXPECT_NEW_JOB, false)) {
            pendingEnqueueStartedAt = System.currentTimeMillis();
        }
        setTitle(R.string.c52_stitch_queue_title);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(18));
        content.setBackgroundColor(0xfff8fafc);

        TextView heading = new TextView(this);
        heading.setText(R.string.c52_stitch_queue_title);
        heading.setTextColor(0xff111827);
        heading.setTextSize(24);
        content.addView(heading, matchWrap());

        status = new TextView(this);
        status.setTextColor(0xff6b7280);
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        content.addView(status, matchWrap());

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(content);
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_EXPECT_NEW_JOB, false)) {
            pendingEnqueueStartedAt = System.currentTimeMillis();
            refresh();
        }
    }

    @Override
    // ponytail: API 29-32 have no receiver export flag; the signature permission is the app-only boundary.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_STITCH_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    receiver,
                    filter,
                    CaptureService.PERMISSION_INTERNAL_BROADCAST,
                    null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(
                    receiver,
                    filter,
                    CaptureService.PERMISSION_INTERNAL_BROADCAST,
                    null);
        }
        receiverRegistered = true;
        StitchQueueService.resumePending(this);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshRunnable);
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void refresh() {
        if (list == null) {
            return;
        }
        try {
            List<StitchQueueStore.Job> jobs = StitchQueueStore.list(this);
            if (pendingEnqueueStartedAt > 0) {
                for (StitchQueueStore.Job job : jobs) {
                    if (job.createdAt >= pendingEnqueueStartedAt) {
                        pendingEnqueueStartedAt = 0;
                        break;
                    }
                }
            }
            list.removeAllViews();
            boolean pending = pendingEnqueueStartedAt > 0;
            if (pending) {
                list.addView(createPendingRow(), matchWrapWithTop(10));
            }
            if (jobs.isEmpty() && !pending) {
                status.setText(R.string.c52_stitch_queue_empty);
                return;
            }
            status.setText(getString(R.string.c52_stitch_queue_count, jobs.size() + (pending ? 1 : 0)));
            for (StitchQueueStore.Job job : jobs) {
                list.addView(createJobRow(job), matchWrapWithTop(10));
            }
        } catch (IOException exception) {
            status.setText(getString(R.string.c52_stitch_queue_error, safeError(exception.getMessage())));
        }
    }

    private View createPendingRow() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundColor(0xffffffff);

        TextView title = new TextView(this);
        title.setText(R.string.c52_stitch_queue_title);
        title.setTextColor(0xff111827);
        title.setTextSize(16);
        card.addView(title, matchWrap());

        TextView message = new TextView(this);
        message.setText(R.string.c52_stitch_queue_preparing);
        message.setTextColor(0xff6b7280);
        message.setTextSize(13);
        message.setPadding(0, dp(5), 0, dp(5));
        card.addView(message, matchWrap());

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        card.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7)));
        return card;
    }

    private View createJobRow(StitchQueueStore.Job job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundColor(0xffffffff);

        TextView title = new TextView(this);
        title.setText(getString(
                R.string.c52_stitch_queue_row_title,
                modeLabel(job.mode),
                formatDate(job.createdAt)));
        title.setTextColor(0xff111827);
        title.setTextSize(16);
        card.addView(title, matchWrap());

        TextView state = new TextView(this);
        state.setText(getString(R.string.c52_stitch_queue_row_state, stateLabel(job.state), job.progress));
        state.setTextColor(0xff374151);
        state.setTextSize(14);
        state.setPadding(0, dp(5), 0, 0);
        card.addView(state, matchWrap());

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(Math.max(0, Math.min(100, job.progress)));
        card.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7)));

        TextView message = new TextView(this);
        message.setText(displayMessage(job));
        message.setTextColor(0xff6b7280);
        message.setTextSize(13);
        message.setPadding(0, dp(5), 0, 0);
        card.addView(message, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        if (StitchQueueStore.STATE_QUEUED.equals(job.state)
                || StitchQueueStore.STATE_RUNNING.equals(job.state)) {
            Button cancel = actionButton(R.string.c52_stitch_queue_cancel);
            cancel.setOnClickListener(view -> sendQueueAction(CaptureService.ACTION_STITCH_CANCEL, job.id, false));
            actions.addView(cancel, buttonParams());
        } else if (StitchQueueStore.STATE_DONE.equals(job.state)
                || StitchQueueStore.STATE_REVIEW.equals(job.state)) {
            Button open = actionButton(R.string.c52_stitch_queue_open);
            open.setOnClickListener(view -> openResult(job.resultPath));
            actions.addView(open, buttonParams());
            if (StitchQueueStore.STATE_REVIEW.equals(job.state)) {
                Button retry = actionButton(R.string.c52_stitch_queue_retry);
                retry.setOnClickListener(view -> sendQueueAction(CaptureService.ACTION_STITCH_RETRY, job.id, true));
                actions.addView(retry, buttonParams());
            }
        } else if (StitchQueueStore.STATE_FAILED.equals(job.state)
                || StitchQueueStore.STATE_CANCELED.equals(job.state)) {
            Button retry = actionButton(R.string.c52_stitch_queue_retry);
            retry.setOnClickListener(view -> sendQueueAction(CaptureService.ACTION_STITCH_RETRY, job.id, true));
            actions.addView(retry, buttonParams());
        }
        if (actions.getChildCount() > 0) {
            card.addView(actions, matchWrapWithTop(4));
        }
        return card;
    }

    private void sendQueueAction(String action, String jobId, boolean foreground) {
        Intent intent = new Intent(this, StitchQueueService.class)
                .setAction(action)
                .putExtra(StitchQueueService.EXTRA_JOB_ID, jobId);
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        handler.postDelayed(this::refresh, 120);
    }

    private void openResult(String path) {
        if (path == null || !new File(path).isFile()) {
            return;
        }
        startActivity(new Intent(this, PreviewActivity.class)
                .putExtra(PreviewActivity.EXTRA_IMAGE_PATH, path)
                .putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, true));
    }

    private Button actionButton(int text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    private String modeLabel(String mode) {
        return CaptureService.MODE_AUTO.equals(mode)
                ? getString(R.string.c52_stitch_queue_mode_auto)
                : getString(R.string.c52_stitch_queue_mode_manual);
    }

    private String stateLabel(String state) {
        if (StitchQueueStore.STATE_QUEUED.equals(state)) return getString(R.string.c52_stitch_queue_state_queued);
        if (StitchQueueStore.STATE_RUNNING.equals(state)) return getString(R.string.c52_stitch_queue_state_running);
        if (StitchQueueStore.STATE_DONE.equals(state)) return getString(R.string.c52_stitch_queue_state_done);
        if (StitchQueueStore.STATE_REVIEW.equals(state)) return getString(R.string.c52_stitch_queue_state_review);
        if (StitchQueueStore.STATE_FAILED.equals(state)) return getString(R.string.c52_stitch_queue_state_failed);
        return getString(R.string.c52_stitch_queue_state_canceled);
    }

    private String displayMessage(StitchQueueStore.Job job) {
        // ponytail: derive saved-job copy from state/progress so it follows the current locale;
        // add structured error codes if queue rows later need localized diagnostic details.
        if (StitchQueueStore.STATE_QUEUED.equals(job.state)) return getString(R.string.c51_stitch_queued);
        if (StitchQueueStore.STATE_DONE.equals(job.state)) return getString(R.string.c31_stitch_done);
        if (StitchQueueStore.STATE_REVIEW.equals(job.state)) return getString(R.string.c52_stitch_queue_state_review);
        if (StitchQueueStore.STATE_FAILED.equals(job.state)) return getString(R.string.c52_stitch_queue_state_failed);
        if (StitchQueueStore.STATE_CANCELED.equals(job.state)) return getString(R.string.queue_canceled);
        if (job.progress >= 94) return getString(R.string.c31_stitch_writing_debug);
        if (job.progress >= 64) return getString(R.string.c31_stitch_generating_preview);
        if (job.progress >= 24) return getString(R.string.c31_stitch_analyzing_overlap);
        return getString(R.string.c51_stitch_running);
    }

    private String formatDate(long millis) {
        Date date = new Date(millis);
        return DateFormat.getDateFormat(this).format(date)
                + " " + DateFormat.getTimeFormat(this).format(date);
    }

    private String safeError(String error) {
        return error == null || error.isEmpty() ? getString(R.string.unknown_error) : error;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        params.setMarginStart(dp(4));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
