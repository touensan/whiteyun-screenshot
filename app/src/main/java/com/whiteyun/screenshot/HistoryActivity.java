package com.whiteyun.screenshot;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends Activity {
    private LinearLayout listContainer;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureActionBar();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfffafafa);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        status = new TextView(this);
        status.setTextColor(0xff374151);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrapParams(0));

        Button refresh = new Button(this);
        refresh.setAllCaps(false);
        refresh.setText(R.string.c13_history_refresh);
        refresh.setOnClickListener(view -> loadHistory());
        root.addView(refresh, matchHeightParams(10, dp(46)));

        ScrollView scrollView = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        scrollParams.topMargin = dp(12);
        root.addView(scrollView, scrollParams);

        setContentView(root);
        applyActionBarOffset(root, dp(16));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
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
            actionBar.setTitle(R.string.c13_history_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void applyActionBarOffset(View root, int topPadding) {
        View contentFrame = findViewById(android.R.id.content);
        int actionBarContainerId = getResources().getIdentifier(
                "action_bar_container",
                "id",
                "android");
        View actionBarContainer = actionBarContainerId == 0 ? null : findViewById(actionBarContainerId);
        if (actionBarContainer == null || contentFrame == null) {
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
            root.setPadding(dp(16), topPadding + overlap, dp(16), dp(16));
        });
    }

    private void loadHistory() {
        listContainer.removeAllViews();
        List<HistoryItem> items;
        try {
            items = queryHistory();
        } catch (RuntimeException exception) {
            status.setText(getString(R.string.c13_history_load_failed, exception.getMessage()));
            return;
        }
        if (items.isEmpty()) {
            status.setText(R.string.c13_history_empty);
            return;
        }
        status.setText(getString(R.string.c13_history_count, items.size()));
        for (HistoryItem item : items) {
            listContainer.addView(createHistoryRow(item), matchWrapParams(8));
        }
    }

    private List<HistoryItem> queryHistory() {
        List<HistoryItem> items = new ArrayList<>();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE
        };
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND ("
                + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?)";
        String[] selectionArgs = {
                Environment.DIRECTORY_PICTURES + "/WhiteYunScreenshot%",
                "WhiteYunScreenshot_%",
                "WhiteYunLongShot_%",
                "WhiteYunWebPage_%"
        };
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        // ponytail: derive history from app-created MediaStore rows; if users move files, add a JSON index later.
        try (Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                selectionArgs,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return items;
            }
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
            int heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (cursor.moveToNext() && items.size() < 100) {
                long id = cursor.getLong(idIndex);
                String name = cursor.getString(nameIndex);
                Uri uri = ContentUris.withAppendedId(collection, id);
                items.add(new HistoryItem(
                        uri,
                        name == null ? "" : name,
                        cursor.getLong(dateIndex),
                        cursor.getInt(widthIndex),
                        cursor.getInt(heightIndex),
                        cursor.getLong(sizeIndex)));
            }
        }
        return items;
    }

    private View createHistoryRow(HistoryItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xffffffff);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setOnClickListener(view -> edit(item));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setBackgroundColor(0xffe5e7eb);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setContentDescription(item.name);
        try {
            Bitmap bitmap = getContentResolver().loadThumbnail(item.uri, new Size(dp(84), dp(112)), null);
            thumbnail.setImageBitmap(bitmap);
        } catch (Exception ignored) {
            thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(84), dp(112));
        row.addView(thumbnail, thumbParams);

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(12), 0, dp(8), 0);

        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextColor(0xff111827);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setMaxLines(2);
        detail.addView(name, matchWrapParams(0));

        TextView meta = new TextView(this);
        meta.setText(getString(
                R.string.c13_history_meta,
                kindLabel(item.name),
                formatDate(item.dateAddedSeconds),
                item.width,
                item.height,
                formatSize(item.sizeBytes)));
        meta.setTextColor(0xff4b5563);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setPadding(0, dp(6), 0, 0);
        detail.addView(meta, matchWrapParams(0));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(8), 0, 0);
        Button edit = smallButton(R.string.c13_history_edit);
        edit.setOnClickListener(view -> edit(item));
        actionRow.addView(edit, actionButtonParams());
        Button browse = smallButton(R.string.preview_browse);
        browse.setOnClickListener(view -> browse(item));
        actionRow.addView(browse, actionButtonParams());
        Button share = smallButton(R.string.preview_share);
        share.setOnClickListener(view -> share(item));
        actionRow.addView(share, actionButtonParams());
        Button delete = smallButton(R.string.c13_history_delete);
        delete.setOnClickListener(view -> confirmDelete(item));
        actionRow.addView(delete, actionButtonParams());
        detail.addView(actionRow, matchWrapParams(0));

        row.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button smallButton(int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return button;
    }

    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.rightMargin = dp(4);
        return params;
    }

    private void edit(HistoryItem item) {
        try {
            Intent intent = new Intent(this, PreviewActivity.class);
            intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, item.uri.toString());
            intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, resultKind(item.name));
            startActivity(intent);
        } catch (Exception exception) {
            status.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void browse(HistoryItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_browse)));
        } catch (Exception exception) {
            status.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void share(HistoryItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, item.uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_share)));
        } catch (Exception exception) {
            status.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void confirmDelete(HistoryItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.c13_history_delete)
                .setMessage(getString(R.string.c13_history_delete_confirm, item.name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> delete(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void delete(HistoryItem item) {
        try {
            ContentResolver resolver = getContentResolver();
            int deleted = resolver.delete(item.uri, null, null);
            status.setText(deleted > 0
                    ? R.string.c13_history_deleted
                    : R.string.c13_history_delete_missing);
            loadHistory();
        } catch (SecurityException exception) {
            status.setText(getString(R.string.c13_history_delete_failed, exception.getMessage()));
        }
    }

    private String kindLabel(String name) {
        if (name.startsWith("WhiteYunScreenshot_")) {
            return getString(R.string.c13_kind_single);
        }
        if (name.startsWith("WhiteYunWebPage_")) {
            return getString(R.string.c13_kind_webpage);
        }
        return getString(R.string.c13_kind_longshot);
    }

    private String resultKind(String name) {
        if (name.startsWith("WhiteYunScreenshot_")) {
            return PreviewActivity.RESULT_KIND_SINGLE;
        }
        if (name.startsWith("WhiteYunWebPage_")) {
            return PreviewActivity.RESULT_KIND_WEBPAGE;
        }
        return PreviewActivity.RESULT_KIND_MANUAL;
    }

    private String formatDate(long addedSeconds) {
        if (addedSeconds <= 0) {
            return getString(R.string.c13_history_date_unknown);
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(addedSeconds * 1000L));
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private LinearLayout.LayoutParams matchWrapParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams matchHeightParams(int topMarginDp, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }

    private static final class HistoryItem {
        final Uri uri;
        final String name;
        final long dateAddedSeconds;
        final int width;
        final int height;
        final long sizeBytes;

        HistoryItem(Uri uri, String name, long dateAddedSeconds, int width, int height, long sizeBytes) {
            this.uri = uri;
            this.name = name;
            this.dateAddedSeconds = dateAddedSeconds;
            this.width = width;
            this.height = height;
            this.sizeBytes = sizeBytes;
        }
    }
}
