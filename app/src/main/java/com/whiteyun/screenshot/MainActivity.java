package com.whiteyun.screenshot;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends LocalizedActivity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private static final int REQUEST_POST_NOTIFICATIONS = 1002;
    private static final int TAB_HOME = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_SETTINGS = 2;
    private static final String STATE_SELECTED_TAB = "selected_tab";

    private TextView status;
    private TextView help;
    private TextView closeHelp;
    private TextView draftStatus;
    private TextView historyStatus;
    private TextView settingsStatus;
    private TextView navHome;
    private TextView navHistory;
    private TextView navSettings;
    private ScrollView mainScroll;
    private ScrollView historyScroll;
    private ScrollView settingsScroll;
    private LinearLayout draftList;
    private LinearLayout historyList;
    private LinearLayout settingsContent;
    private CheckBox autoScrollOption;
    private CheckBox speedModeOption;
    private CheckBox manualCaptureOption;
    private MediaProjectionManager projectionManager;
    private boolean startAfterNotificationPermission;
    private boolean updatingCaptureOptions;
    private boolean inForeground;
    private boolean coreFlowStarted;
    private boolean settingsBuilt;
    private boolean tabsInitialized;
    private boolean pendingSpeedMode = true;
    private int selectedTab = TAB_HOME;
    private int tabAnimationToken;
    private View currentTabContent;
    private String pendingCaptureMode = CaptureService.MODE_CORE;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE);
            if (message != null) {
                status.setText(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setContentView(R.layout.activity_main);
        configureActionBar();
        applyActionBarOffset();

        status = findViewById(R.id.status);
        help = findViewById(R.id.tv_help);
        closeHelp = findViewById(R.id.tv_close_help);
        draftStatus = findViewById(R.id.draft_status);
        historyStatus = findViewById(R.id.history_status);
        settingsStatus = findViewById(R.id.settings_status);
        navHome = findViewById(R.id.nav_home);
        navHistory = findViewById(R.id.nav_history);
        navSettings = findViewById(R.id.nav_settings);
        mainScroll = findViewById(R.id.main_scroll);
        historyScroll = findViewById(R.id.history_scroll);
        settingsScroll = findViewById(R.id.settings_scroll);
        draftList = findViewById(R.id.draft_list);
        historyList = findViewById(R.id.history_list);
        settingsContent = findViewById(R.id.settings_content);
        autoScrollOption = findViewById(R.id.cb_auto_scroll);
        speedModeOption = findViewById(R.id.cb_speed_mode);
        manualCaptureOption = findViewById(R.id.cb_manual_capture);

        loadCapturePreferences();
        addPermissionSummary();
        wireCaptureOptions();

        findViewById(R.id.card_capture_screenshot).setOnClickListener(
                view -> startScreenshotCaptureFromOptions());
        findViewById(R.id.card_capture_webpage).setOnClickListener(view -> openWebPageCapture());
        findViewById(R.id.card_select_images).setOnClickListener(view -> openStitchImages());
        findViewById(R.id.history_entry).setOnClickListener(view -> showHistoryTab());
        findViewById(R.id.queue_entry).setOnClickListener(view -> openStitchQueue());
        findViewById(R.id.history_refresh).setOnClickListener(view -> loadHistory());
        navHome.setOnClickListener(view -> showHomeTab());
        navHistory.setOnClickListener(view -> openHistory());
        navSettings.setOnClickListener(view -> openSettingsPage());
        findViewById(R.id.iv_auto_scroll_intro).setOnClickListener(view ->
                showCaptureInfo(R.string.c30_auto_scroll_intro_title, R.string.c30_auto_scroll_intro_body));
        findViewById(R.id.iv_speed_mode_intro).setOnClickListener(view ->
                showCaptureInfo(R.string.c30_speed_mode_intro_title, R.string.c30_speed_mode_intro_body));
        findViewById(R.id.iv_auto_capture_intro).setOnClickListener(view ->
                showCaptureInfo(R.string.c30_manual_auto_intro_title, R.string.c30_manual_auto_intro_body));
        closeHelp.setOnClickListener(view -> setHelpVisible(false));
        mainScroll.post(() -> mainScroll.scrollTo(0, 0));
        int restoredTab = savedInstanceState == null
                ? TAB_HOME
                : savedInstanceState.getInt(STATE_SELECTED_TAB, TAB_HOME);
        if (restoredTab == TAB_HISTORY) {
            showHistoryTab();
        } else if (restoredTab == TAB_SETTINGS) {
            showSettingsTab();
        } else {
            showHomeTab();
        }
    }

    private void configureActionBar() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.app_name);
        }
    }

    private void applyActionBarOffset() {
        View container = findViewById(R.id.container);
        View contentFrame = findViewById(android.R.id.content);
        // ponytail: keep the native ActionBar; if this platform id disappears, replace it with an in-layout toolbar.
        int actionBarContainerId = getResources().getIdentifier(
                "action_bar_container",
                "id",
                "android");
        View actionBarContainer = actionBarContainerId == 0 ? null : findViewById(actionBarContainerId);
        if (actionBarContainer == null || contentFrame == null) {
            return;
        }
        container.post(() -> {
            int[] contentLocation = new int[2];
            int[] actionBarLocation = new int[2];
            contentFrame.getLocationOnScreen(contentLocation);
            actionBarContainer.getLocationOnScreen(actionBarLocation);
            int overlap = Math.max(
                    0,
                    actionBarLocation[1] + actionBarContainer.getHeight() - contentLocation[1]);
            container.setPadding(0, overlap, 0, container.getPaddingBottom());
        });
    }

    @Override
    // ponytail: API 29-32 have no receiver export flag; the signature permission is the app-only boundary.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        inForeground = true;
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    statusReceiver,
                    filter,
                    CaptureService.PERMISSION_INTERNAL_BROADCAST,
                    null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(
                    statusReceiver,
                    filter,
                    CaptureService.PERMISSION_INTERNAL_BROADCAST,
                    null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StitchQueueService.resumePending(this);
        loadDrafts();
        if (selectedTab == TAB_HISTORY) {
            loadHistory();
        }
        if (selectedTab == TAB_SETTINGS && settingsBuilt) {
            buildSettingsTab();
        }
    }

    @Override
    protected void onStop() {
        inForeground = false;
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean home = selectedTab == TAB_HOME;
        MenuItem language = menu.findItem(R.id.action_language);
        MenuItem settings = menu.findItem(R.id.action_settings);
        MenuItem usage = menu.findItem(R.id.action_usage);
        if (language != null) {
            language.setVisible(home);
        }
        if (settings != null) {
            settings.setVisible(home);
        }
        if (usage != null) {
            usage.setVisible(home);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_language) {
            showLanguagePicker();
            return true;
        }
        if (itemId == R.id.action_settings) {
            showSettingsMenu();
            return true;
        }
        if (itemId == R.id.action_usage) {
            setHelpVisible(help.getVisibility() != View.VISIBLE);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addPermissionSummary() {
        TextView summary = findViewById(R.id.permission_summary);
        summary.setText(R.string.c6_permission_summary);
    }

    private void loadCapturePreferences() {
        updatingCaptureOptions = true;
        boolean autoScroll = AppPreferences.isAutoScrollEnabled(this);
        boolean manualCapture = AppPreferences.isManualCaptureEnabled(this);
        if (autoScroll && manualCapture) {
            manualCapture = false;
        }
        autoScrollOption.setChecked(autoScroll);
        speedModeOption.setChecked(AppPreferences.isSpeedModeEnabled(this));
        manualCaptureOption.setChecked(manualCapture);
        updatingCaptureOptions = false;
    }

    private void wireCaptureOptions() {
        autoScrollOption.setOnCheckedChangeListener((button, checked) -> {
            if (updatingCaptureOptions) {
                return;
            }
            updatingCaptureOptions = true;
            if (checked) {
                manualCaptureOption.setChecked(false);
            }
            updatingCaptureOptions = false;
            updateCaptureOptionStatus();
        });
        manualCaptureOption.setOnCheckedChangeListener((button, checked) -> {
            if (updatingCaptureOptions) {
                return;
            }
            updatingCaptureOptions = true;
            if (checked) {
                autoScrollOption.setChecked(false);
            }
            updatingCaptureOptions = false;
            updateCaptureOptionStatus();
        });
        speedModeOption.setOnCheckedChangeListener((button, checked) -> updateCaptureOptionStatus());
        updateCaptureOptionStatus();
    }

    private void updateCaptureOptionStatus() {
        if (status == null) {
            return;
        }
        if (autoScrollOption.isChecked()) {
            status.setText(R.string.c8_status_option_auto);
        } else if (manualCaptureOption.isChecked()) {
            status.setText(R.string.c8_status_option_manual);
        } else if (speedModeOption.isChecked()) {
            status.setText(R.string.c8_status_option_speed);
        } else {
            status.setText(R.string.c8_status_option_core);
        }
        AppPreferences.setCaptureOptions(
                this,
                autoScrollOption.isChecked(),
                speedModeOption.isChecked(),
                manualCaptureOption.isChecked());
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            status.setText(R.string.c2_status_overlay_ready);
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        status.setText(R.string.c2_status_overlay_needed);
    }

    private void requestAccessibilityPermission() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        status.setText(isAutoScrollAccessibilityEnabled()
                ? R.string.c4_status_accessibility_ready
                : R.string.c4_status_accessibility_needed);
    }

    private void startScreenshotCaptureFromOptions() {
        coreFlowStarted = true;
        pendingSpeedMode = speedModeOption.isChecked();
        if (autoScrollOption.isChecked()) {
            startAutoCaptureFlow();
            return;
        }
        if (manualCaptureOption.isChecked()) {
            startManualCaptureFlow();
            return;
        }
        startCaptureFlow(CaptureService.MODE_CORE);
    }

    private void startAutoCaptureFlow() {
        if (!isAutoScrollAccessibilityEnabled()) {
            requestAccessibilityPermission();
            Toast.makeText(this, R.string.c4_toast_accessibility_needed, Toast.LENGTH_LONG).show();
            return;
        }
        startCaptureFlow(CaptureService.MODE_AUTO);
    }

    private void startManualCaptureFlow() {
        if (!isAutoScrollAccessibilityEnabled()) {
            requestAccessibilityPermission();
            Toast.makeText(this, R.string.c35_toast_accessibility_needed, Toast.LENGTH_LONG).show();
            return;
        }
        startCaptureFlow(CaptureService.MODE_MANUAL);
    }

    private void startCaptureFlow(String mode) {
        pendingCaptureMode = mode;
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            Toast.makeText(this, R.string.c2_toast_overlay_needed, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startAfterNotificationPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent = projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = projectionManager.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION);
        status.setText(R.string.c2_status_projection_requested);
    }

    private void sendCaptureAction(String action) {
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(action);
        startService(intent);
    }

    private void openStitchImages() {
        coreFlowStarted = true;
        status.setText(R.string.action_stitch_images);
        startActivity(new Intent(this, StitchImagesActivity.class));
    }

    private void openWebPageCapture() {
        coreFlowStarted = true;
        status.setText(R.string.capture_web_page);
        startActivity(new Intent(this, WebPageCaptureActivity.class));
    }

    private void openHistory() {
        showHistoryTab();
    }

    private void openSettingsPage() {
        showSettingsTab();
    }

    private void openStitchQueue() {
        startActivity(new Intent(this, StitchQueueActivity.class));
    }

    private void showHomeTab() {
        selectedTab = TAB_HOME;
        setHelpVisible(false);
        settingsStatus.setVisibility(View.GONE);
        switchTabContent(mainScroll, tabsInitialized);
        configureActionBar();
        invalidateOptionsMenu();
        selectBottomNav(navHome);
        loadDrafts();
        tabsInitialized = true;
    }

    private void showHistoryTab() {
        selectedTab = TAB_HISTORY;
        setHelpVisible(false);
        settingsStatus.setVisibility(View.GONE);
        switchTabContent(historyScroll, tabsInitialized);
        setActionBarTitle(R.string.c13_history_title);
        invalidateOptionsMenu();
        selectBottomNav(navHistory);
        loadHistory();
        historyScroll.post(() -> historyScroll.scrollTo(0, 0));
        tabsInitialized = true;
    }

    private void showSettingsTab() {
        selectedTab = TAB_SETTINGS;
        setHelpVisible(false);
        switchTabContent(settingsScroll, tabsInitialized);
        settingsStatus.setVisibility(View.VISIBLE);
        settingsStatus.bringToFront();
        setActionBarTitle(R.string.nav_settings);
        invalidateOptionsMenu();
        selectBottomNav(navSettings);
        buildSettingsTab();
        settingsScroll.post(() -> settingsScroll.scrollTo(0, 0));
        tabsInitialized = true;
    }

    private void setActionBarTitle(int titleRes) {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(titleRes);
        }
    }

    private void selectBottomNav(TextView selected) {
        if (navHome == null || navHistory == null || navSettings == null) {
            return;
        }
        animateBottomNavItem(navHome, selected == navHome);
        animateBottomNavItem(navHistory, selected == navHistory);
        animateBottomNavItem(navSettings, selected == navSettings);
    }

    private void switchTabContent(View target, boolean animate) {
        if (target == null) {
            return;
        }
        if (currentTabContent == target && target.getVisibility() == View.VISIBLE) {
            return;
        }
        View old = currentTabContent;
        currentTabContent = target;
        if (!animate || old == null) {
            tabAnimationToken++;
            mainScroll.setVisibility(target == mainScroll ? View.VISIBLE : View.GONE);
            historyScroll.setVisibility(target == historyScroll ? View.VISIBLE : View.GONE);
            settingsScroll.setVisibility(target == settingsScroll ? View.VISIBLE : View.GONE);
            resetTabView(mainScroll);
            resetTabView(historyScroll);
            resetTabView(settingsScroll);
            return;
        }

        int token = ++tabAnimationToken;
        setInactiveTabViewsGone(old, target);
        target.animate().cancel();
        target.setVisibility(View.VISIBLE);
        target.setAlpha(1f);
        target.setTranslationY(0f);
        target.bringToFront();

        View targetBody = getTabAnimationBody(target);
        targetBody.animate().cancel();
        targetBody.setAlpha(1f);
        targetBody.setTranslationY(dp(8));
        targetBody.animate()
                .translationY(0f)
                .setDuration(160)
                .start();

        if (old != null) {
            View oldBody = getTabAnimationBody(old);
            oldBody.animate().cancel();
            oldBody.animate()
                    .alpha(0f)
                    .translationY(-dp(4))
                    .setDuration(110)
                    .withEndAction(() -> {
                        if (token != tabAnimationToken) {
                            return;
                        }
                        old.setVisibility(View.GONE);
                        resetTabView(old);
                    })
                    .start();
        }
    }

    private View getTabAnimationBody(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getChildCount() > 0) {
                return group.getChildAt(0);
            }
        }
        return view;
    }

    private void setInactiveTabViewsGone(View keepVisible, View target) {
        hideInactiveTabView(mainScroll, keepVisible, target);
        hideInactiveTabView(historyScroll, keepVisible, target);
        hideInactiveTabView(settingsScroll, keepVisible, target);
    }

    private void hideInactiveTabView(View view, View keepVisible, View target) {
        if (view == null || view == keepVisible || view == target) {
            return;
        }
        view.animate().cancel();
        view.setVisibility(View.GONE);
        resetTabView(view);
    }

    private void resetTabView(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
        View body = getTabAnimationBody(view);
        if (body != view) {
            body.animate().cancel();
            body.setAlpha(1f);
            body.setTranslationY(0f);
        }
    }

    private void animateBottomNavItem(TextView item, boolean selected) {
        if (item == null) {
            return;
        }
        int targetColor = getColor(selected ? R.color.colorPrimaryDark : R.color.whiteyun_muted_text);
        Object oldAnimator = item.getTag();
        if (oldAnimator instanceof ValueAnimator) {
            ((ValueAnimator) oldAnimator).cancel();
        }
        ValueAnimator colorAnimator = ValueAnimator.ofArgb(item.getCurrentTextColor(), targetColor);
        colorAnimator.setDuration(tabsInitialized ? 160 : 0);
        colorAnimator.addUpdateListener(animation -> item.setTextColor((int) animation.getAnimatedValue()));
        item.setTag(colorAnimator);
        colorAnimator.start();
        item.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        item.animate().cancel();
        item.animate()
                .alpha(selected ? 1f : 0.82f)
                .scaleX(selected ? 1.08f : 1f)
                .scaleY(selected ? 1.08f : 1f)
                .setDuration(tabsInitialized ? 160 : 0)
                .start();
    }

    private void loadDrafts() {
        if (draftList == null || draftStatus == null) {
            return;
        }
        draftList.removeAllViews();
        List<DraftStore.DraftItem> drafts = DraftStore.list(this, 3);
        if (drafts.isEmpty()) {
            draftStatus.setText(R.string.draft_empty);
            return;
        }
        draftStatus.setText(getString(R.string.draft_count, drafts.size()));
        for (int i = 0; i < drafts.size(); i++) {
            draftList.addView(createDraftRow(drafts.get(i), i == 0), matchWrapParams(8));
        }
    }

    private View createDraftRow(DraftStore.DraftItem item, boolean latest) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundColor(0xfff9fafb);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> editDraft(item));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setBackgroundColor(0xffeef2f7);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setImageBitmap(decodeDraftThumbnail(item.file));
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(84), dp(118)));

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPaddingRelative(dp(12), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(latest ? R.string.draft_latest_title : R.string.draft_item_title);
        name.setTextColor(0xff111827);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setMaxLines(1);
        detail.addView(name, matchWrapParams(0));

        TextView meta = new TextView(this);
        meta.setText(draftMeta(item));
        meta.setTextColor(0xff6b7280);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        meta.setMaxLines(2);
        detail.addView(meta, matchWrapParams(2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = smallButton(R.string.c13_history_edit);
        edit.setTextColor(0xffffffff);
        edit.setBackgroundColor(0xffff9800);
        edit.setOnClickListener(view -> editDraft(item));
        actions.addView(edit, actionButtonParams());
        Button delete = smallButton(R.string.draft_delete);
        delete.setTextColor(0xff374151);
        delete.setBackgroundColor(0xffe5e7eb);
        delete.setOnClickListener(view -> {
            item.file.delete();
            loadDrafts();
        });
        actions.addView(delete, actionButtonParams());
        detail.addView(actions, matchWrapParams(4));

        row.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Bitmap decodeDraftThumbnail(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int targetWidth = dp(168);
        while (bounds.outWidth / options.inSampleSize > targetWidth) {
            options.inSampleSize *= 2;
        }
        int regionHeight = Math.min(bounds.outHeight, Math.max(bounds.outWidth, bounds.outWidth * 4 / 3));
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
            // ponytail: a top crop is enough for a draft clue; use a filmstrip if users need full long-image browsing here.
            return decoder.decodeRegion(new Rect(0, 0, bounds.outWidth, regionHeight), options);
        } catch (IOException | OutOfMemoryError ignored) {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    private String draftMeta(DraftStore.DraftItem item) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(item.file.getAbsolutePath(), bounds);
        String dimensions = bounds.outWidth > 0 && bounds.outHeight > 0
                ? getString(R.string.draft_dimensions, bounds.outWidth, bounds.outHeight)
                : getString(R.string.draft_dimensions_unknown);
        return getString(
                R.string.draft_saved_meta,
                formatDateMillis(item.modifiedMillis),
                dimensions,
                formatSize(item.sizeBytes));
    }

    private void editDraft(DraftStore.DraftItem item) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATH, item.file.getAbsolutePath());
        intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, PreviewActivity.RESULT_KIND_MANUAL);
        intent.putExtra(PreviewActivity.EXTRA_KEEP_SOURCE_FILE, true);
        startActivity(intent);
    }

    private void loadHistory() {
        if (historyList == null || historyStatus == null) {
            return;
        }
        historyList.removeAllViews();
        List<HistoryItem> items;
        try {
            items = queryHistory();
        } catch (RuntimeException exception) {
            historyStatus.setText(getString(R.string.c13_history_load_failed, exception.getMessage()));
            return;
        }
        if (items.isEmpty()) {
            historyStatus.setText(R.string.c13_history_empty);
            return;
        }
        historyStatus.setText(getString(R.string.c13_history_count, items.size()));
        for (HistoryItem item : items) {
            historyList.addView(createHistoryRow(item), matchWrapParams(8));
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
        row.setOnClickListener(view -> editHistory(item));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setBackgroundColor(0xffe5e7eb);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setContentDescription(item.name);
        try {
            thumbnail.setImageBitmap(getContentResolver().loadThumbnail(item.uri, new Size(dp(84), dp(112)), null));
        } catch (Exception ignored) {
            thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(84), dp(112)));

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPaddingRelative(dp(12), 0, dp(8), 0);

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
                formatDateSeconds(item.dateAddedSeconds),
                item.width,
                item.height,
                formatSize(item.sizeBytes)));
        meta.setTextColor(0xff4b5563);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detail.addView(meta, matchWrapParams(6));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = smallButton(R.string.c13_history_edit);
        edit.setOnClickListener(view -> editHistory(item));
        actionRow.addView(edit, actionButtonParams());
        Button browse = smallButton(R.string.preview_browse);
        browse.setOnClickListener(view -> browseHistory(item));
        actionRow.addView(browse, actionButtonParams());
        Button share = smallButton(R.string.preview_share);
        share.setOnClickListener(view -> shareHistory(item));
        actionRow.addView(share, actionButtonParams());
        Button delete = smallButton(R.string.c13_history_delete);
        delete.setOnClickListener(view -> confirmDeleteHistory(item));
        actionRow.addView(delete, actionButtonParams());
        detail.addView(actionRow, matchWrapParams(8));

        row.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void editHistory(HistoryItem item) {
        try {
            Intent intent = new Intent(this, PreviewActivity.class);
            intent.putExtra(PreviewActivity.EXTRA_IMAGE_URI, item.uri.toString());
            intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, resultKind(item.name));
            startActivity(intent);
        } catch (Exception exception) {
            historyStatus.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void browseHistory(HistoryItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_browse)));
        } catch (Exception exception) {
            historyStatus.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void shareHistory(HistoryItem item) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, item.uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_share)));
        } catch (Exception exception) {
            historyStatus.setText(getString(R.string.c13_history_action_failed, exception.getMessage()));
        }
    }

    private void confirmDeleteHistory(HistoryItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.c13_history_delete)
                .setMessage(getString(R.string.c13_history_delete_confirm, item.name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteHistory(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteHistory(HistoryItem item) {
        try {
            ContentResolver resolver = getContentResolver();
            int deleted = resolver.delete(item.uri, null, null);
            historyStatus.setText(deleted > 0
                    ? R.string.c13_history_deleted
                    : R.string.c13_history_delete_missing);
            loadHistory();
        } catch (SecurityException exception) {
            historyStatus.setText(getString(R.string.c13_history_delete_failed, exception.getMessage()));
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

    private void buildSettingsTab() {
        settingsBuilt = true;
        settingsContent.removeAllViews();
        Button changeLanguage = actionButton(R.string.language_change);
        changeLanguage.setOnClickListener(view -> showLanguagePicker());
        String languageTag = AppLocale.currentTag(this);
        String languageName = languageTag.isEmpty()
                ? getString(R.string.language_system_default)
                : AppLocale.nativeDisplayName(languageTag);
        settingsContent.addView(settingsPanel(
                getString(R.string.language_title),
                getString(R.string.language_current, languageName)
                        + "\n" + getString(R.string.language_body),
                changeLanguage));

        Switch stitchNotifications = new Switch(this);
        stitchNotifications.setText(R.string.c52_stitch_notifications_toggle);
        stitchNotifications.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stitchNotifications.setTextColor(0xff111827);
        stitchNotifications.setChecked(AppPreferences.isStitchNotificationsEnabled(this));
        stitchNotifications.setOnCheckedChangeListener((button, checked) ->
                AppPreferences.setStitchNotificationsEnabled(this, checked));
        settingsContent.addView(settingsPanel(
                getString(R.string.c52_stitch_notifications_title),
                getString(R.string.c52_stitch_notifications_body),
                stitchNotifications));
        Switch captureStatusBar = new Switch(this);
        captureStatusBar.setText(R.string.c52_status_bar_toggle);
        captureStatusBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        captureStatusBar.setTextColor(0xff111827);
        captureStatusBar.setChecked(AppPreferences.isCaptureStatusBarEnabled(this));
        captureStatusBar.setOnCheckedChangeListener((button, checked) ->
                AppPreferences.setCaptureStatusBarEnabled(this, checked));
        settingsContent.addView(settingsPanel(
                getString(R.string.c52_status_bar_title),
                getString(R.string.c52_status_bar_body),
                captureStatusBar));
        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_overview_title),
                getString(R.string.c14_support_overview_body)));
        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_app_info_title),
                buildAppInfo()));
        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_permission_title),
                buildPermissionInfo()));

        Button feedback = actionButton(R.string.c14_support_feedback);
        feedback.setOnClickListener(view -> sendFeedback());
        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_feedback_title),
                getString(R.string.c14_support_feedback_body),
                feedback));

        Button shareDiagnostics = actionButton(R.string.c14_support_share_diagnostics);
        shareDiagnostics.setOnClickListener(view -> shareDiagnostics());
        Button exportDiagnostics = actionButton(R.string.action_export_diagnostics);
        exportDiagnostics.setOnClickListener(view -> exportDiagnosticsToSettings());
        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_diagnostics_title),
                getString(R.string.c14_support_diagnostics_body),
                shareDiagnostics,
                exportDiagnostics));

        if (BuildConfig.BETA_DIAGNOSTICS) {
            Button betaDiagnostics = actionButton(R.string.beta_diagnostics_open);
            betaDiagnostics.setOnClickListener(view -> openBetaDiagnostics());
            settingsContent.addView(settingsPanel(
                    getString(R.string.beta_diagnostics_title),
                    getString(R.string.beta_diagnostics_body),
                    betaDiagnostics));
        }

        settingsContent.addView(settingsPanel(
                getString(R.string.c14_support_update_title),
                getString(R.string.c14_support_update_not_configured)));
    }

    private void showLanguagePicker() {
        String[] supported = AppLocale.supportedTags();
        CharSequence[] labels = new CharSequence[supported.length + 1];
        labels[0] = getString(R.string.language_system_default);
        String current = AppLocale.currentTag(this);
        int checked = 0;
        for (int i = 0; i < supported.length; i++) {
            labels[i + 1] = AppLocale.nativeDisplayName(supported[i]);
            if (supported[i].equals(current)) {
                checked = i + 1;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_picker_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    String selected = which == 0 ? AppLocale.SYSTEM_DEFAULT : supported[which - 1];
                    dialog.dismiss();
                    if (selected.equals(current)) {
                        return;
                    }
                    confirmLanguageRestart(selected);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmLanguageRestart(String selected) {
        Context targetContext = AppLocale.forTag(this, selected);
        new AlertDialog.Builder(this)
                .setTitle(targetContext.getString(R.string.language_restart_title))
                .setMessage(targetContext.getString(R.string.language_restart_message))
                .setNegativeButton(targetContext.getString(R.string.language_restart_cancel), null)
                .setPositiveButton(targetContext.getString(R.string.language_restart_confirm), (dialog, which) -> {
                    AppLocale.set(this, selected);
                    restartApplication();
                })
                .show();
    }

    private void restartApplication() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private View settingsPanel(String title, String body, View... actions) {
        return settingsPanel(title, bodyText(body), actions);
    }

    private void openBetaDiagnostics() {
        try {
            Intent intent = new Intent();
            intent.setClassName(this, getPackageName() + ".BetaDiagnosticsActivity");
            startActivity(intent);
        } catch (RuntimeException exception) {
            settingsStatus.setText(getString(
                    R.string.c14_support_action_failed,
                    exception.getMessage()));
        }
    }

    private View settingsPanel(String title, TextView bodyView, View... actions) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xffffffff);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xff111827);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        panel.addView(titleView, matchWrapParams(0));

        panel.addView(bodyView, matchWrapParams(8));

        if (actions != null && actions.length > 0) {
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.VERTICAL);
            for (View action : actions) {
                actionRow.addView(action, matchHeightParams(6, dp(46)));
            }
            panel.addView(actionRow, matchWrapParams(0));
        }
        return panelWithMargin(panel);
    }

    private View panelWithMargin(View panel) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(12), 0, 0);
        wrapper.addView(panel, matchWrapParams(0));
        return wrapper;
    }

    private TextView bodyText(String body) {
        TextView bodyView = new TextView(this);
        bodyView.setText(body);
        bodyView.setTextColor(0xff374151);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bodyView.setLineSpacing(0, 1.15f);
        return bodyView;
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
                isAutoScrollAccessibilityEnabled() ? getString(R.string.c14_permission_ready) : getString(R.string.c14_permission_needed),
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
            settingsStatus.setText(getString(R.string.c14_support_action_failed, exception.getMessage()));
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
            settingsStatus.setText(getString(R.string.c14_support_diagnostics_ready, uri));
        } catch (Exception exception) {
            settingsStatus.setText(getString(R.string.c6_status_diagnostics_failed, exception.getMessage()));
        }
    }

    private void exportDiagnosticsToSettings() {
        try {
            Uri uri = Diagnostics.export(this);
            settingsStatus.setText(getString(R.string.c6_status_diagnostics_saved, uri));
        } catch (Exception exception) {
            settingsStatus.setText(getString(R.string.c6_status_diagnostics_failed, exception.getMessage()));
        }
    }

    private void showSettingsMenu() {
        String[] items = {
                getString(R.string.action_grant_overlay),
                getString(R.string.action_grant_accessibility),
                getString(R.string.c14_support_title),
                getString(R.string.action_export_diagnostics)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_settings)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        requestOverlayPermission();
                    } else if (which == 1) {
                        requestAccessibilityPermission();
                    } else if (which == 2) {
                        openSupport();
                    } else {
                        exportDiagnostics();
                    }
                })
                .show();
    }

    private void showCaptureInfo(int titleRes, int messageRes) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.c30_info_ok, null)
                .show();
    }

    private void openSupport() {
        status.setText(R.string.c14_support_title);
        startActivity(new Intent(this, SupportActivity.class));
    }

    private void setHelpVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        help.setVisibility(visibility);
        closeHelp.setVisibility(visibility);
    }

    private void exportDiagnostics() {
        try {
            Uri uri = Diagnostics.export(this);
            status.setText(getString(R.string.c6_status_diagnostics_saved, uri));
        } catch (Exception exception) {
            status.setText(getString(R.string.c6_status_diagnostics_failed, exception.getMessage()));
        }
    }

    private Button smallButton(int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return button;
    }

    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMarginEnd(dp(4));
        return params;
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

    private String formatDateSeconds(long addedSeconds) {
        if (addedSeconds <= 0) {
            return getString(R.string.c13_history_date_unknown);
        }
        return formatDateMillis(addedSeconds * 1000L);
    }

    private String formatDateMillis(long millis) {
        if (millis <= 0) {
            return getString(R.string.c13_history_date_unknown);
        }
        Date date = new Date(millis);
        return DateFormat.getDateFormat(this).format(date)
                + " " + DateFormat.getTimeFormat(this).format(date);
    }

    private String formatSize(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS && startAfterNotificationPermission) {
            startAfterNotificationPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestProjection();
            } else {
                status.setText(R.string.c6_status_notification_denied);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            status.setText(R.string.c2_status_projection_denied);
            return;
        }
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(CaptureService.ACTION_START);
        intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        intent.putExtra(CaptureService.EXTRA_MODE, pendingCaptureMode);
        intent.putExtra(CaptureService.EXTRA_SPEED_MODE, pendingSpeedMode);
        startForegroundService(intent);
        status.setText(CaptureService.MODE_MANUAL.equals(pendingCaptureMode)
                ? R.string.c3_status_starting
                : CaptureService.MODE_AUTO.equals(pendingCaptureMode)
                        ? R.string.c4_status_starting
                        : R.string.c2_status_starting);
    }

    private boolean isAutoScrollAccessibilityEnabled() {
        return AutoScrollAccessibilityService.isEnabled(this);
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
