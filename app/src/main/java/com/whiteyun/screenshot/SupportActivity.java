package com.whiteyun.screenshot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Local help and diagnostics page.
 *
 * The open-source build deliberately keeps support offline: no update checker,
 * remote configuration, or diagnostic upload client is included.
 */
public class SupportActivity extends LocalizedActivity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureActionBar();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(248, 250, 252));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        status = bodyText("");
        status.setVisibility(View.GONE);
        content.addView(status, matchWrapParams(0));
        content.addView(panel(
                getString(R.string.c14_support_overview_title),
                getString(R.string.c14_support_overview_body)));
        content.addView(panel(
                getString(R.string.c14_support_app_info_title),
                buildAppInfo()));
        content.addView(panel(
                getString(R.string.c14_support_permission_title),
                buildPermissionInfo()));

        Button feedback = actionButton(R.string.c14_support_feedback);
        feedback.setOnClickListener(view -> sendFeedback());
        content.addView(panel(
                getString(R.string.c14_support_feedback_title),
                getString(R.string.c14_support_feedback_body),
                feedback));

        Button shareDiagnostics = actionButton(R.string.c14_support_share_diagnostics);
        shareDiagnostics.setOnClickListener(view -> shareDiagnostics());
        Button exportDiagnostics = actionButton(R.string.action_export_diagnostics);
        exportDiagnostics.setOnClickListener(view -> exportDiagnostics());
        content.addView(panel(
                getString(R.string.c14_support_diagnostics_title),
                getString(R.string.c14_support_diagnostics_body),
                shareDiagnostics,
                exportDiagnostics));

        content.addView(panel(
                getString(R.string.c14_support_update_title),
                getString(R.string.c14_support_update_not_configured)));

        setContentView(scroll);
        applyActionBarOffset(scroll);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void configureActionBar() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.c14_support_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private View panel(String title, String body, Button... actions) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView titleView = bodyText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(Color.rgb(17, 24, 39));
        panel.addView(titleView, matchWrapParams(0));

        TextView bodyView = bodyText(body);
        panel.addView(bodyView, matchWrapParams(8));

        if (actions != null && actions.length > 0) {
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.VERTICAL);
            for (Button action : actions) {
                actionRow.addView(action, matchHeightParams(6, dp(46)));
            }
            panel.addView(actionRow, matchWrapParams(0));
        }
        LinearLayout.LayoutParams params = matchWrapParams(12);
        return panelWithMargins(panel, params);
    }

    private View panelWithMargins(View view, LinearLayout.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    private TextView bodyText(String value) {
        TextView body = new TextView(this);
        body.setText(value);
        body.setTextColor(Color.rgb(55, 65, 81));
        body.setTextSize(14);
        body.setLineSpacing(0, 1.15f);
        body.setGravity(Gravity.START);
        return body;
    }

    private Button actionButton(int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        return button;
    }

    private String buildAppInfo() {
        String version = "unknown";
        long code = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = String.valueOf(info.versionName);
            code = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return getString(
                R.string.c14_support_app_info,
                version,
                code,
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.DEVICE);
    }

    private String buildPermissionInfo() {
        return getString(
                R.string.c14_support_permission_info,
                Settings.canDrawOverlays(this) ? getString(R.string.c14_permission_ready) : getString(R.string.c14_permission_needed),
                AutoScrollAccessibilityService.isEnabled(this) ? getString(R.string.c14_permission_ready) : getString(R.string.c14_permission_needed),
                notificationPermissionStatus());
    }

    private String notificationPermissionStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return getString(R.string.c14_permission_not_required);
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? getString(R.string.c14_permission_ready)
                : getString(R.string.c14_permission_needed);
    }

    private void sendFeedback() {
        String body = String.format(
                Locale.US,
                "%s%n%n%s%n%s",
                getString(R.string.c14_feedback_template),
                getString(R.string.c14_feedback_device_prefix),
                buildAppInfo());
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:"));
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.c14_feedback_subject));
        intent.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.c14_support_feedback)));
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void shareDiagnostics() {
        try {
            Uri uri = Diagnostics.export(this);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.c14_support_share_diagnostics)));
            showStatus(getString(R.string.c14_support_diagnostics_ready, uri));
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void exportDiagnostics() {
        try {
            Uri uri = Diagnostics.export(this);
            showStatus(getString(R.string.c6_status_diagnostics_saved, uri));
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void showStatus(String message) {
        status.setVisibility(View.VISIBLE);
        status.setText(message);
    }

    private void showError(Exception exception) {
        showStatus(getString(R.string.c6_status_diagnostics_failed, exception.getMessage()));
    }

    private LinearLayout.LayoutParams matchWrapParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private LinearLayout.LayoutParams matchHeightParams(int topMargin, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height);
        params.topMargin = dp(topMargin);
        return params;
    }

    @SuppressLint("ObsoleteSdkInt")
    private void applyActionBarOffset(View root) {
        View contentFrame = findViewById(android.R.id.content);
        if (contentFrame == null) {
            return;
        }
        int actionBarContainerId = getResources().getIdentifier(
                "action_bar_container", "id", "android");
        View actionBarContainer = actionBarContainerId == 0 ? null : findViewById(actionBarContainerId);
        if (actionBarContainer == null) {
            return;
        }
        root.post(() -> {
            int[] contentLocation = new int[2];
            int[] actionBarLocation = new int[2];
            contentFrame.getLocationOnScreen(contentLocation);
            actionBarContainer.getLocationOnScreen(actionBarLocation);
            int overlap = Math.max(
                    0,
                    actionBarLocation[1] + actionBarContainer.getHeight() - contentLocation[1]);
            root.setPadding(root.getPaddingLeft(), overlap, root.getPaddingRight(), root.getPaddingBottom());
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
