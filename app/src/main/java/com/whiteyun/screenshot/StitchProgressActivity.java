package com.whiteyun.screenshot;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

public class StitchProgressActivity extends Activity {
    private TextView title;
    private TextView status;
    private TextView percent;
    private ProgressBar progressBar;
    private Button background;
    private Button cancel;
    private Button retry;
    private ValueAnimator progressAnimator;
    private int displayedProgress;
    private String resultPath = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CaptureService.ACTION_STITCH_STATUS.equals(intent.getAction())) {
                updateFromIntent(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.c31_stitch_title);
        buildContent();
        updateUi(
                getString(R.string.c31_stitch_preparing),
                0,
                true,
                false,
                false,
                "");
    }

    @Override
    // ponytail: API 29-32 have no receiver export flag; the signature permission is the app-only boundary.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
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
        sendServiceAction(CaptureService.ACTION_STITCH_POLL);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelProgressAnimation();
        super.onDestroy();
    }

    private void buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = dp(24);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff8fafc);

        title = new TextView(this);
        title.setText(R.string.c31_stitch_title);
        title.setTextColor(0xff111827);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(0xff374151);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(status, statusParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8));
        progressParams.topMargin = dp(22);
        root.addView(progressBar, progressParams);

        percent = new TextView(this);
        percent.setTextColor(0xff6b7280);
        percent.setTextSize(14);
        percent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams percentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        percentParams.topMargin = dp(8);
        root.addView(percent, percentParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(28);
        root.addView(actions, actionParams);

        background = new Button(this);
        background.setText(R.string.c52_stitch_queue_view);
        background.setOnClickListener(view -> openQueue());
        actions.addView(background, buttonParams());

        cancel = new Button(this);
        cancel.setText(R.string.c31_stitch_cancel);
        cancel.setOnClickListener(view -> sendServiceAction(CaptureService.ACTION_STITCH_CANCEL));
        actions.addView(cancel, buttonParams());

        retry = new Button(this);
        retry.setText(R.string.c31_stitch_retry);
        retry.setOnClickListener(view -> {
            retry.setEnabled(false);
            sendServiceAction(CaptureService.ACTION_STITCH_RETRY);
        });
        actions.addView(retry, buttonParams());

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private void updateFromIntent(Intent intent) {
        String message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE);
        int progress = intent.getIntExtra(CaptureService.EXTRA_STITCH_PROGRESS, 0);
        boolean running = intent.getBooleanExtra(CaptureService.EXTRA_STITCH_RUNNING, false);
        boolean done = intent.getBooleanExtra(CaptureService.EXTRA_STITCH_DONE, false);
        boolean canRetry = intent.getBooleanExtra(CaptureService.EXTRA_STITCH_CAN_RETRY, false);
        resultPath = intent.getStringExtra(CaptureService.EXTRA_STITCH_RESULT_PATH);
        updateUi(
                message,
                progress,
                running,
                done,
                canRetry,
                resultPath);
    }

    private void updateUi(
            String message,
            int progress,
            boolean running,
            boolean done,
            boolean canRetry,
            String path) {
        String safeMessage = message == null || message.isEmpty()
                ? getString(R.string.c31_stitch_preparing)
                : message;
        int safeProgress = Math.max(0, Math.min(100, progress));
        status.setText(safeMessage);
        animateProgressTo(safeProgress, running && !done && safeProgress >= displayedProgress);
        cancel.setEnabled(running);
        retry.setEnabled(canRetry && !done);
        title.setText(done ? R.string.c31_stitch_done_title : R.string.c31_stitch_title);
        progressBar.setVisibility(done ? View.INVISIBLE : View.VISIBLE);
        boolean hasResult = done && path != null && new File(path).isFile();
        background.setText(hasResult ? R.string.c51_stitch_view_result : R.string.c52_stitch_queue_view);
        background.setOnClickListener(view -> {
            if (!hasResult) {
                openQueue();
                return;
            }
            Intent preview = new Intent(this, PreviewActivity.class)
                    .putExtra(PreviewActivity.EXTRA_IMAGE_PATH, path)
                    .putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, true);
            startActivity(preview);
            finish();
        });
    }

    private void animateProgressTo(int target, boolean smooth) {
        int current = displayedProgress;
        if (!smooth || Math.abs(target - current) <= 1) {
            cancelProgressAnimation();
            setDisplayedProgress(target);
            return;
        }
        cancelProgressAnimation();
        int distance = Math.abs(target - current);
        progressAnimator = ValueAnimator.ofInt(current, target);
        progressAnimator.setDuration(Math.max(180, Math.min(650, distance * 24L)));
        progressAnimator.addUpdateListener(animation ->
                setDisplayedProgress((Integer) animation.getAnimatedValue()));
        progressAnimator.start();
    }

    private void setDisplayedProgress(int value) {
        displayedProgress = Math.max(0, Math.min(100, value));
        progressBar.setProgress(displayedProgress);
        percent.setText(getString(R.string.c31_stitch_percent, displayedProgress));
    }

    private void cancelProgressAnimation() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, StitchQueueService.class).setAction(action);
        if (CaptureService.ACTION_STITCH_RETRY.equals(action)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void openQueue() {
        startActivity(new Intent(this, StitchQueueActivity.class));
        finish();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
