package com.whiteyun.screenshot;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Permanent diagnostics surface for the same-signature Beta build. */
public final class BetaDiagnosticsActivity extends LocalizedActivity {
    private TextView report;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(button(R.string.beta_diagnostics_refresh, view -> refresh()));
        actions.addView(button(R.string.beta_diagnostics_recover, view -> recoverLatestPreview()));
        actions.addView(button(R.string.beta_diagnostics_open_draft, view -> openLatestDraft()));
        actions.addView(button(R.string.beta_diagnostics_self_test, view -> runSelfTest()));
        actions.addView(button(R.string.beta_diagnostics_export, view -> exportDiagnostics()));
        content.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        report = new TextView(this);
        report.setTextIsSelectable(true);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(12);
        report.setPadding(0, 20, 0, 40);
        content.addView(report, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refresh();
        if (getIntent().getBooleanExtra("recover_latest", false)) {
            content.post(this::recoverLatestPreview);
        }
    }

    private Button button(int text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        return button;
    }

    private void refresh() {
        List<DraftStore.DraftItem> drafts = DraftStore.list(this, 0);
        report.setText(Diagnostics.buildReport(this)
                + "\nBeta storage:\n"
                + "Drafts: " + drafts.size() + "\n"
                + "Auto frame sessions: " + directoryCount(new File(getCacheDir(), "auto-frames")) + "\n");
    }

    private void recoverLatestPreview() {
        try {
            File recovered = DraftStore.promoteLatestPreviewCache(this);
            if (recovered == null) {
                report.setText(R.string.beta_diagnostics_no_preview);
                return;
            }
            openPreview(recovered);
        } catch (Exception exception) {
            report.setText(exception.toString());
        }
    }

    private void openLatestDraft() {
        List<DraftStore.DraftItem> drafts = DraftStore.list(this, 1);
        if (drafts.isEmpty()) {
            report.setText(R.string.beta_diagnostics_no_draft);
            return;
        }
        openPreview(drafts.get(0).file);
    }

    private void openPreview(File file) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, PreviewActivity.RESULT_KIND_AUTO);
        intent.putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, true);
        startActivity(intent);
    }

    private void runSelfTest() {
        Intent intent = new Intent();
        intent.setClassName(this, getPackageName() + ".StitchSelfTestActivity");
        intent.putExtra("run_id", "beta-" + System.currentTimeMillis());
        startActivity(intent);
    }

    private void exportDiagnostics() {
        try {
            Uri uri = Diagnostics.export(this);
            report.setText(getString(R.string.c6_status_diagnostics_saved, uri));
        } catch (Exception exception) {
            report.setText(exception.toString());
        }
    }

    private static int directoryCount(File root) {
        File[] directories = root.listFiles(File::isDirectory);
        return directories == null ? 0 : directories.length;
    }
}
