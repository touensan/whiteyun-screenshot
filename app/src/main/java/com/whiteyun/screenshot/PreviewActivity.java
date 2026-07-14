package com.whiteyun.screenshot;

import android.app.Activity;
import android.app.ActionBar;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class PreviewActivity extends LocalizedActivity {
    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_SOURCE_FILE_PATHS = "source_file_paths";
    public static final String EXTRA_SOURCE_URIS = "source_uris";
    public static final String EXTRA_RESULT_KIND = "result_kind";
    public static final String EXTRA_KEEP_SOURCE_FILE = "keep_source_file";
    public static final String RESULT_KIND_SINGLE = "single";
    public static final String RESULT_KIND_MANUAL = "manual";
    public static final String RESULT_KIND_AUTO = "auto";
    public static final String RESULT_KIND_STITCH = "stitch";
    public static final String RESULT_KIND_WEBPAGE = "webpage";
    private static final int CROP_STEP_PX = 10;
    private static final int PREVIEW_TILE_PREFETCH_SOURCE_PX = 360;
    private static final int PREVIEW_TILE_MAX_DECODED_HEIGHT = 4096;
    private static final long PREVIEW_TILE_MAX_DECODED_BYTES = 24L * 1024L * 1024L;
    private static final int PREVIEW_FALLBACK_MAX_DECODED_HEIGHT = 8192;
    private static final long PREVIEW_FALLBACK_MAX_DECODED_BYTES = 12L * 1024L * 1024L;

    private File imageFile;
    private Uri imageUri;
    private RegionPreviewView previewImage;
    private TextView status;
    private TextView cropTopLabel;
    private TextView cropBottomLabel;
    private SeekBar cropTopSeek;
    private SeekBar cropBottomSeek;
    private CheckBox saveOriginals;
    private Uri savedUri;
    private ArrayList<String> sourceFilePaths;
    private ArrayList<String> sourceUris;
    private String resultKind;
    private boolean keepSourceFile;
    private int imageHeight;
    private int cropTopPx;
    private int cropBottomPx;
    private boolean updatingCropControls;
    private LinearLayout chromeTop;
    private LinearLayout chromeBottom;
    private LinearLayout cropPanel;
    private ScrollView previewScroll;
    private Button editToggle;
    private boolean topChromeVisible = true;
    private boolean bottomChromeVisible = true;
    private float previewTouchStartY;
    private boolean previewTouchMoved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configurePreviewWindow();
        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        imageFile = path == null ? null : new File(path);
        String uriValue = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        imageUri = uriValue == null || uriValue.trim().isEmpty() ? getIntent().getData() : Uri.parse(uriValue);
        sourceFilePaths = getIntent().getStringArrayListExtra(EXTRA_SOURCE_FILE_PATHS);
        sourceUris = getIntent().getStringArrayListExtra(EXTRA_SOURCE_URIS);
        resultKind = getIntent().getStringExtra(EXTRA_RESULT_KIND);
        keepSourceFile = getIntent().getBooleanExtra(EXTRA_KEEP_SOURCE_FILE, false)
                || DraftStore.isDraftFile(this, imageFile);
        if (resultKind == null || resultKind.trim().isEmpty()) {
            resultKind = RESULT_KIND_MANUAL;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xfff8fafc);
        root.setPadding(dp(8), statusBarHeight() + dp(8), dp(8), dp(10));

        chromeTop = new LinearLayout(this);
        chromeTop.setOrientation(LinearLayout.HORIZONTAL);
        chromeTop.setGravity(Gravity.CENTER_VERTICAL);
        chromeTop.setPadding(0, 0, 0, dp(6));
        chromeTop.setBackgroundColor(0xf2f8fafc);

        Button back = actionButton(R.string.preview_back);
        back.setOnClickListener(view -> finish());
        chromeTop.addView(back, new LinearLayout.LayoutParams(dp(82), dp(44)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPaddingRelative(dp(8), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText(R.string.preview_title);
        title.setTextColor(0xff111827);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleBlock.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText(R.string.preview_ready);
        if (DraftStore.isDraftFile(this, imageFile)) {
            status.setText(R.string.preview_recovery_draft_ready);
        }
        status.setTextColor(0xff4b5563);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setMaxLines(2);
        titleBlock.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chromeTop.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        RegionPreviewView image = new RegionPreviewView(this);
        previewImage = image;
        image.setBackgroundColor(0xffffffff);
        image.setOnTouchListener((view, event) -> handlePreviewTouch(event));
        if (hasImageSource()) {
            readImageBounds();
            try {
                image.setImageSource();
            } catch (IOException | OutOfMemoryError error) {
                status.setText(R.string.c6_status_preview_too_large);
            }
        } else {
            status.setText(R.string.preview_missing);
        }

        ScrollView scrollView = new ScrollView(this);
        previewScroll = scrollView;
        scrollView.setFillViewport(false);
        scrollView.setOnTouchListener((view, event) -> {
            boolean handled = handlePreviewTouch(event);
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                updateEdgeChrome((ScrollView) view);
            }
            return handled;
        });
        scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (previewImage != null) {
                previewImage.invalidate();
            }
            if (Math.abs(scrollY - oldScrollY) > dp(8)
                    && !isAtTop(scrollView)
                    && !isAtBottom(scrollView)) {
                setChromeVisible(false);
            }
            updateEdgeChrome(scrollView);
        });
        scrollView.addView(image, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(scrollView, scrollParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(chromeTop, topParams);

        chromeBottom = new LinearLayout(this);
        chromeBottom.setOrientation(LinearLayout.VERTICAL);
        chromeBottom.setPadding(0, dp(8), 0, 0);
        chromeBottom.setBackgroundColor(0xf2f8fafc);

        if (hasOriginalSources()) {
            saveOriginals = new CheckBox(this);
            saveOriginals.setText(R.string.preview_save_originals);
            saveOriginals.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            saveOriginals.setTextColor(0xff1f2937);
            saveOriginals.setChecked(AppPreferences.isSaveOriginals(this));
            saveOriginals.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> AppPreferences.setSaveOriginals(this, isChecked));
            chromeBottom.addView(saveOriginals, wrapParams(0));
        }

        cropPanel = new LinearLayout(this);
        cropPanel.setOrientation(LinearLayout.VERTICAL);
        cropPanel.setVisibility(View.GONE);
        if (imageHeight > 1) {
            addCropControls(cropPanel);
            chromeBottom.addView(cropPanel, wrapParams(0));
        }

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER);

        Button save = actionButton(R.string.preview_save);
        save.setOnClickListener(view -> saveToGallery());
        primaryActions.addView(save, actionParams());

        Button draft = actionButton(R.string.preview_save_draft);
        draft.setOnClickListener(view -> saveDraft());
        primaryActions.addView(draft, actionParams());

        editToggle = actionButton(R.string.preview_edit);
        editToggle.setOnClickListener(view -> toggleCropPanel());
        primaryActions.addView(editToggle, actionParams());
        chromeBottom.addView(primaryActions, wrapParams(0));

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setGravity(Gravity.CENTER);

        Button browse = actionButton(R.string.preview_browse);
        browse.setOnClickListener(view -> browseImage());
        secondaryActions.addView(browse, actionParams());

        Button share = actionButton(R.string.preview_share);
        share.setOnClickListener(view -> shareImage());
        secondaryActions.addView(share, actionParams());

        Button fresh = actionButton(R.string.preview_new);
        fresh.setOnClickListener(view -> startNewCapture());
        secondaryActions.addView(fresh, actionParams());
        chromeBottom.addView(secondaryActions, wrapParams(6));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(chromeBottom, bottomParams);

        setContentView(root);
        root.post(this::updatePreviewSafeArea);
    }

    @Override
    protected void onDestroy() {
        if (previewImage != null) {
            previewImage.close();
        }
        deleteSourceCacheFiles();
        super.onDestroy();
    }

    private void configurePreviewWindow() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        getWindow().setStatusBarColor(0xfff8fafc);
        getWindow().setNavigationBarColor(0xfff8fafc);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void setChromeVisible(boolean visible) {
        setTopChromeVisible(visible, true);
        setBottomChromeVisible(visible, true);
    }

    private void setTopChromeVisible(boolean visible, boolean animate) {
        if (topChromeVisible == visible) {
            return;
        }
        topChromeVisible = visible;
        animateChromePart(chromeTop, visible, true, animate);
    }

    private void setBottomChromeVisible(boolean visible, boolean animate) {
        if (bottomChromeVisible == visible) {
            return;
        }
        bottomChromeVisible = visible;
        animateChromePart(chromeBottom, visible, false, animate);
    }

    private void animateChromePart(View view, boolean visible, boolean top, boolean animate) {
        if (view == null) {
            return;
        }
        float hiddenOffset = dp(18) * (top ? -1 : 1);
        view.animate().cancel();
        if (!animate) {
            view.setAlpha(visible ? 1f : 0f);
            view.setTranslationY(visible ? 0f : hiddenOffset);
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            updatePreviewSafeArea();
            return;
        }
        if (visible) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(hiddenOffset);
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .withEndAction(this::updatePreviewSafeArea)
                    .start();
            return;
        }
        view.animate()
                .alpha(0f)
                .translationY(hiddenOffset)
                .setDuration(150)
                .withEndAction(() -> {
                    if ((top && !topChromeVisible) || (!top && !bottomChromeVisible)) {
                        view.setVisibility(View.GONE);
                    }
                    updatePreviewSafeArea();
                })
                .start();
    }

    private void updatePreviewSafeArea() {
        if (previewScroll == null || chromeTop == null || chromeBottom == null) {
            return;
        }
        int top = topChromeVisible ? chromeTop.getHeight() : 0;
        int bottom = bottomChromeVisible ? chromeBottom.getHeight() : 0;
        previewScroll.setPadding(0, top, 0, bottom);
        previewScroll.setClipToPadding(true);
    }

    private void updateEdgeChrome(ScrollView scrollView) {
        if (isAtTop(scrollView)) {
            setTopChromeVisible(true, true);
        }
        if (isAtBottom(scrollView)) {
            setBottomChromeVisible(true, true);
        }
    }

    private boolean isAtTop(ScrollView scrollView) {
        return scrollView != null && !scrollView.canScrollVertically(-1);
    }

    private boolean isAtBottom(ScrollView scrollView) {
        return scrollView != null && !scrollView.canScrollVertically(1);
    }

    private void toggleCropPanel() {
        if (cropPanel == null || imageHeight <= 1) {
            status.setText(R.string.preview_missing);
            return;
        }
        setChromeVisible(true);
        boolean show = cropPanel.getVisibility() != View.VISIBLE;
        cropPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (editToggle != null) {
            editToggle.setText(show ? R.string.preview_hide_tools : R.string.preview_edit);
        }
        chromeBottom.post(this::updatePreviewSafeArea);
    }

    private boolean handlePreviewTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            previewTouchStartY = event.getY();
            previewTouchMoved = false;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE
                && Math.abs(event.getY() - previewTouchStartY) > dp(8)) {
            previewTouchMoved = true;
            setChromeVisible(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (!previewTouchMoved) {
                setChromeVisible(!topChromeVisible || !bottomChromeVisible);
                return true;
            }
            previewTouchMoved = false;
        }
        return false;
    }

    private Button actionButton(int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams wrapParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void addCropControls(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText(R.string.c16_crop_title);
        title.setTextColor(0xff1f2937);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(title, wrapParams(10));

        cropTopLabel = cropLabel();
        root.addView(cropTopLabel, wrapParams(4));
        cropTopSeek = cropSeekBar();
        root.addView(cropTopSeek, wrapParams(2));
        root.addView(adjustmentRow(true), wrapParams(2));

        cropBottomLabel = cropLabel();
        root.addView(cropBottomLabel, wrapParams(6));
        cropBottomSeek = cropSeekBar();
        root.addView(cropBottomSeek, wrapParams(2));
        root.addView(adjustmentRow(false), wrapParams(2));

        Button reset = actionButton(R.string.c16_crop_reset);
        reset.setOnClickListener(view -> resetCrop());
        root.addView(reset, wrapParams(4));

        wireCropSeekBar(cropTopSeek, true);
        wireCropSeekBar(cropBottomSeek, false);
        updateCropLabels(false);
    }

    private TextView cropLabel() {
        TextView label = new TextView(this);
        label.setTextColor(0xff4b5563);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return label;
    }

    private SeekBar cropSeekBar() {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(Math.max(0, imageHeight - 1));
        return seekBar;
    }

    private LinearLayout adjustmentRow(boolean top) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button down = actionButton(R.string.c16_crop_minus_10);
        down.setOnClickListener(view -> adjustCrop(top, -CROP_STEP_PX));
        row.addView(down, actionParams());

        Button up = actionButton(R.string.c16_crop_plus_10);
        up.setOnClickListener(view -> adjustCrop(top, CROP_STEP_PX));
        row.addView(up, actionParams());

        return row;
    }

    private void wireCropSeekBar(SeekBar seekBar, boolean top) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || updatingCropControls) {
                    return;
                }
                setCropValue(top, progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                updateCropPreview();
            }
        });
    }

    private void adjustCrop(boolean top, int delta) {
        int current = top ? cropTopPx : cropBottomPx;
        setCropValue(top, current + delta, true);
    }

    private void resetCrop() {
        cropTopPx = 0;
        cropBottomPx = 0;
        savedUri = null;
        syncCropSeekBars();
        updateCropLabels(true);
        updateCropPreview();
    }

    private void setCropValue(boolean top, int requested, boolean refreshPreview) {
        int value = Math.max(0, requested);
        if (top) {
            cropTopPx = Math.min(value, Math.max(0, imageHeight - cropBottomPx - 1));
        } else {
            cropBottomPx = Math.min(value, Math.max(0, imageHeight - cropTopPx - 1));
        }
        savedUri = null;
        syncCropSeekBars();
        updateCropLabels(true);
        if (refreshPreview) {
            updateCropPreview();
        }
    }

    private void syncCropSeekBars() {
        updatingCropControls = true;
        if (cropTopSeek != null) {
            cropTopSeek.setProgress(cropTopPx);
        }
        if (cropBottomSeek != null) {
            cropBottomSeek.setProgress(cropBottomPx);
        }
        updatingCropControls = false;
    }

    private void updateCropLabels(boolean announce) {
        if (cropTopLabel != null) {
            cropTopLabel.setText(getString(R.string.c16_crop_top, cropTopPx));
        }
        if (cropBottomLabel != null) {
            cropBottomLabel.setText(getString(R.string.c16_crop_bottom, cropBottomPx));
        }
        if (announce) {
            status.setText(getString(R.string.c16_status_crop_updated, cropTopPx, cropBottomPx));
        }
    }

    private void updateCropPreview() {
        if (previewImage == null) {
            return;
        }
        previewImage.setCrop(cropTopPx, cropBottomPx);
    }

    private void saveToGallery() {
        try {
            Uri uri = ensureSaved();
            int originals = saveOriginals != null && saveOriginals.isChecked()
                    ? saveOriginalSources()
                    : 0;
            if (originals > 0) {
                status.setText(getString(R.string.preview_saved_with_originals, uri, originals));
            } else {
                status.setText(getString(R.string.preview_saved, uri));
            }
        } catch (IOException exception) {
            status.setText(getString(R.string.preview_save_failed, exception.getMessage()));
        }
    }

    private void saveDraft() {
        if (!hasCrop() && DraftStore.isDraftFile(this, imageFile)) {
            status.setText(R.string.preview_recovery_draft_ready);
            return;
        }
        try {
            File draft = DraftStore.createDraftFile(this);
            writeCurrentImageToFile(draft);
            status.setText(getString(R.string.preview_draft_saved, draft.getName()));
        } catch (IOException exception) {
            status.setText(getString(R.string.preview_draft_failed, exception.getMessage()));
        }
    }

    private void browseImage() {
        try {
            Uri uri = ensureSaved();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_browse)));
        } catch (Exception exception) {
            status.setText(getString(R.string.preview_save_failed, exception.getMessage()));
        }
    }

    private void shareImage() {
        try {
            Uri uri = ensureSaved();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.preview_share)));
        } catch (Exception exception) {
            status.setText(getString(R.string.preview_save_failed, exception.getMessage()));
        }
    }

    private void startNewCapture() {
        if (!keepSourceFile) {
            deleteCacheFile();
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private Uri ensureSaved() throws IOException {
        if (savedUri != null) {
            return savedUri;
        }
        if (!hasImageSource()) {
            throw new IOException(getString(R.string.preview_missing));
        }
        if (imageFile != null && imageFile.isFile()) {
            savedUri = hasCrop()
                    ? copyCroppedFileToMediaStore(imageFile, resultPrefix())
                    : copyFileToMediaStore(imageFile, resultPrefix(), mimeTypeForFile(imageFile));
        } else if (imageUri != null && hasCrop()) {
            savedUri = copyCroppedUriToMediaStore(imageUri, resultPrefix());
        } else {
            savedUri = imageUri;
        }
        return savedUri;
    }

    private Uri copyFileToMediaStore(File source, String prefix, String mimeType) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            return copyStreamToMediaStore(input, prefix, mimeType);
        }
    }

    private Uri copyToMediaStore(File source) throws IOException {
        return copyFileToMediaStore(source, resultPrefix(), mimeTypeForFile(source));
    }

    private Uri copyUriToMediaStore(Uri source, String prefix) throws IOException {
        String mimeType = getContentResolver().getType(source);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            mimeType = "image/png";
        }
        try (InputStream input = getContentResolver().openInputStream(source)) {
            if (input == null) {
                throw new IOException("openInputStream returned null");
            }
            return copyStreamToMediaStore(input, prefix, mimeType);
        }
    }

    private Uri copyStreamToMediaStore(InputStream input, String prefix, String mimeType) throws IOException {
        Uri uri = createPendingImage(prefix, mimeType);
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("MediaStore openOutputStream returned null");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            getContentResolver().delete(uri, null, null);
            throw exception;
        }
        publishPendingImage(uri);
        return uri;
    }

    private Uri copyCroppedFileToMediaStore(File source, String prefix) throws IOException {
        File cropped = File.createTempFile("cropped-", ".png", getCacheDir());
        try {
            writeCroppedFileToFile(source, cropped);
            return copyFileToMediaStore(cropped, prefix, "image/png");
        } finally {
            cropped.delete();
        }
    }

    private Uri copyCroppedUriToMediaStore(Uri source, String prefix) throws IOException {
        // ponytail: imported URIs still decode the whole crop; spool to a temp file and use the
        // streaming cropper if unlimited imported-image cropping becomes a product requirement.
        try (InputStream input = openUriInputStream(source)) {
            return copyCroppedStreamToMediaStore(input, prefix);
        }
    }

    private void writeCurrentImageToFile(File target) throws IOException {
        if (!hasImageSource()) {
            throw new IOException(getString(R.string.preview_missing));
        }
        if (hasCrop() && imageFile != null && imageFile.isFile()) {
            writeCroppedFileToFile(imageFile, target);
            return;
        }
        try (InputStream input = openCurrentImageInputStream()) {
            if (hasCrop()) {
                writeCroppedStreamToFile(input, target);
            } else {
                copyStreamToFile(input, target);
            }
        }
    }

    private void writeCroppedFileToFile(File source, File target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(getString(R.string.error_crop_source_unreadable));
        }
        StreamingLongScreenshotStitcher.crop(
                source,
                cropRect(bounds.outWidth, bounds.outHeight),
                target);
    }

    private InputStream openCurrentImageInputStream() throws IOException {
        if (imageFile != null && imageFile.isFile()) {
            return new FileInputStream(imageFile);
        }
        return openUriInputStream(imageUri);
    }

    private Uri copyCroppedStreamToMediaStore(InputStream input, String prefix) throws IOException {
        Uri uri = createPendingImage(prefix, "image/png");
        BitmapRegionDecoder decoder = null;
        Bitmap cropped = null;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("MediaStore openOutputStream returned null");
            }
            decoder = BitmapRegionDecoder.newInstance(input, false);
            Rect crop = cropRect(decoder.getWidth(), decoder.getHeight());
            cropped = decoder.decodeRegion(crop, null);
            if (cropped == null) {
                throw new IOException("decodeRegion returned null");
            }
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG compress failed");
            }
        } catch (IOException | OutOfMemoryError exception) {
            getContentResolver().delete(uri, null, null);
            throw new IOException(exception.getMessage(), exception);
        } finally {
            if (cropped != null) {
                cropped.recycle();
            }
            if (decoder != null) {
                decoder.recycle();
            }
        }
        publishPendingImage(uri);
        return uri;
    }

    private void writeCroppedStreamToFile(InputStream input, File target) throws IOException {
        BitmapRegionDecoder decoder = null;
        Bitmap cropped = null;
        try (OutputStream output = new java.io.FileOutputStream(target)) {
            decoder = BitmapRegionDecoder.newInstance(input, false);
            Rect crop = cropRect(decoder.getWidth(), decoder.getHeight());
            cropped = decoder.decodeRegion(crop, null);
            if (cropped == null) {
                throw new IOException("decodeRegion returned null");
            }
            if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG compress failed");
            }
        } catch (IOException | OutOfMemoryError exception) {
            target.delete();
            throw new IOException(exception.getMessage(), exception);
        } finally {
            if (cropped != null) {
                cropped.recycle();
            }
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    private void copyStreamToFile(InputStream input, File target) throws IOException {
        try (OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            target.delete();
            throw exception;
        }
    }

    private Uri createPendingImage(String prefix, String mimeType) throws IOException {
        ContentResolver resolver = getContentResolver();
        String extension = "image/jpeg".equals(mimeType) ? ".jpg" : ".png";
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + extension;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WhiteYunScreenshot");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null");
        }

        return uri;
    }

    private void publishPendingImage(Uri uri) {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private int saveOriginalSources() throws IOException {
        int saved = 0;
        if (sourceFilePaths != null) {
            for (String path : sourceFilePaths) {
                File file = new File(path);
                if (file.isFile()) {
                    copyFileToMediaStore(file, "WhiteYunOriginal", "image/png");
                    saved++;
                }
            }
        }
        if (sourceUris != null) {
            for (String value : sourceUris) {
                if (value != null && !value.isEmpty()) {
                    copyUriToMediaStore(Uri.parse(value), "WhiteYunOriginal");
                    saved++;
                }
            }
        }
        return saved;
    }

    private String mimeTypeForFile(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/png";
    }

    private boolean hasOriginalSources() {
        return (sourceFilePaths != null && !sourceFilePaths.isEmpty())
                || (sourceUris != null && !sourceUris.isEmpty());
    }

    private boolean hasImageSource() {
        return (imageFile != null && imageFile.isFile()) || imageUri != null;
    }

    private boolean hasCrop() {
        return cropTopPx > 0 || cropBottomPx > 0;
    }

    private Rect cropRect(int width, int height) {
        // ponytail: region cropping handles vertical trims only; add side handles if users ask for freeform crop.
        int top = Math.min(cropTopPx, Math.max(0, height - 1));
        int bottomTrim = Math.min(cropBottomPx, Math.max(0, height - top - 1));
        return new Rect(0, top, width, height - bottomTrim);
    }

    private String resultPrefix() {
        if (RESULT_KIND_SINGLE.equals(resultKind)) {
            return "WhiteYunScreenshot";
        }
        if (RESULT_KIND_WEBPAGE.equals(resultKind)) {
            return "WhiteYunWebPage";
        }
        return "WhiteYunLongShot";
    }

    private BitmapFactory.Options decodeImageBounds() throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (imageFile != null && imageFile.isFile()) {
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
        } else if (imageUri != null) {
            try (InputStream input = openUriInputStream(imageUri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
        }
        return bounds;
    }

    private void readImageBounds() {
        BitmapFactory.Options bounds;
        try {
            bounds = decodeImageBounds();
        } catch (IOException exception) {
            imageHeight = 0;
            return;
        }
        imageHeight = Math.max(0, bounds.outHeight);
    }

    private InputStream openUriInputStream(Uri uri) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("openInputStream returned null");
        }
        return input;
    }

    private final class RegionPreviewView extends View {
        private final Object decoderLock = new Object();
        private final ExecutorService tileExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WhiteYunPreviewTile");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        private final ExecutorService fallbackExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WhiteYunPreviewFallback");
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
        private BitmapRegionDecoder decoder;
        private Bitmap tileBitmap;
        private Rect tileSource;
        private Rect pendingTileSource;
        private Bitmap fallbackBitmap;
        private int fallbackSampleSize;
        private Future<?> tileFuture;
        private Future<?> fallbackFuture;
        private volatile long tileRequestId;
        private volatile long fallbackRequestId;
        private volatile boolean closed;
        private int sourceWidth;
        private int sourceHeight;
        private int previewCropTop;
        private int previewCropBottom;

        RegionPreviewView(Context context) {
            super(context);
        }

        void setImageSource() throws IOException {
            resetSource();
            synchronized (decoderLock) {
                if (imageFile != null && imageFile.isFile()) {
                    decoder = BitmapRegionDecoder.newInstance(imageFile.getAbsolutePath(), false);
                } else {
                    try (InputStream input = openUriInputStream(imageUri)) {
                        decoder = BitmapRegionDecoder.newInstance(input, false);
                    }
                }
                sourceWidth = decoder.getWidth();
                sourceHeight = decoder.getHeight();
            }
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                resetSource();
                throw new IOException("invalid preview size");
            }
            closed = false;
            loadInitialFallback();
            requestFallback();
            requestLayout();
            invalidate();
        }

        void setCrop(int top, int bottom) {
            int clampedTop = clampInt(top, 0, Math.max(0, sourceHeight - 1));
            int clampedBottom = clampInt(bottom, 0, Math.max(0, sourceHeight - clampedTop - 1));
            if (previewCropTop == clampedTop && previewCropBottom == clampedBottom) {
                return;
            }
            previewCropTop = clampedTop;
            previewCropBottom = clampedBottom;
            cancelTileRequest();
            recycleTile();
            requestLayout();
            invalidate();
        }

        void close() {
            closed = true;
            cancelTileRequest();
            cancelFallbackRequest();
            tileExecutor.shutdownNow();
            fallbackExecutor.shutdownNow();
            resetDecoder();
            recycleTile();
            recycleFallback();
            sourceWidth = 0;
            sourceHeight = 0;
            previewCropTop = 0;
            previewCropBottom = 0;
        }

        private void resetSource() {
            closed = true;
            cancelTileRequest();
            cancelFallbackRequest();
            resetDecoder();
            recycleTile();
            recycleFallback();
            pendingTileSource = null;
            closed = false;
        }

        private void resetDecoder() {
            synchronized (decoderLock) {
                if (decoder != null) {
                    decoder.recycle();
                    decoder = null;
                }
            }
        }

        private void cancelTileRequest() {
            tileRequestId++;
            pendingTileSource = null;
            if (tileFuture != null) {
                tileFuture.cancel(true);
                tileFuture = null;
            }
        }

        private void cancelFallbackRequest() {
            fallbackRequestId++;
            if (fallbackFuture != null) {
                fallbackFuture.cancel(true);
                fallbackFuture = null;
            }
        }

        private void requestFallback() {
            if (closed || decoder == null) {
                return;
            }
            int targetSampleSize = previewFallbackSampleSize(sourceWidth, sourceHeight);
            if (fallbackBitmap != null && fallbackSampleSize <= targetSampleSize) {
                return;
            }
            final long requestId = ++fallbackRequestId;
            if (fallbackFuture != null) {
                fallbackFuture.cancel(true);
            }
            fallbackFuture = fallbackExecutor.submit(() -> decodeFallback(requestId));
        }

        private void loadInitialFallback() {
            int targetSampleSize = previewFallbackSampleSize(sourceWidth, sourceHeight);
            int initialSampleSize = targetSampleSize >= (1 << 29)
                    ? targetSampleSize
                    : targetSampleSize * 2;
            try {
                Bitmap initial = decodeBitmapAtSample(initialSampleSize);
                if (initial != null) {
                    fallbackBitmap = initial;
                    fallbackSampleSize = initialSampleSize;
                }
            } catch (IOException | OutOfMemoryError | RuntimeException ignored) {
                // Tile decoding remains the fallback if the quick whole-image preview is unavailable.
            }
        }

        private void decodeFallback(long requestId) {
            Bitmap decoded = null;
            try {
                int sampleSize = previewFallbackSampleSize(sourceWidth, sourceHeight);
                decoded = decodeBitmapAtSample(sampleSize);
                if (decoded == null) {
                    throw new IOException("fallback decode returned null");
                }
                final Bitmap result = decoded;
                if (!post(() -> installFallback(requestId, sampleSize, result))) {
                    result.recycle();
                }
                decoded = null;
            } catch (IOException | OutOfMemoryError | RuntimeException error) {
                if (decoded != null) {
                    decoded.recycle();
                }
            }
        }

        private Bitmap decodeBitmapAtSample(int sampleSize) throws IOException {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = Math.max(1, sampleSize);
            Bitmap decoded;
            if (imageFile != null && imageFile.isFile()) {
                decoded = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
            } else {
                try (InputStream input = openUriInputStream(imageUri)) {
                    decoded = BitmapFactory.decodeStream(input, null, options);
                }
            }
            return decoded;
        }

        private void installFallback(long requestId, int sampleSize, Bitmap decoded) {
            if (closed || requestId != fallbackRequestId || decoder == null) {
                decoded.recycle();
                return;
            }
            recycleFallback();
            fallbackBitmap = decoded;
            fallbackSampleSize = Math.max(1, sampleSize);
            fallbackFuture = null;
            invalidate();
        }

        private void recycleFallback() {
            if (fallbackBitmap != null) {
                fallbackBitmap.recycle();
                fallbackBitmap = null;
            }
            fallbackSampleSize = 0;
        }

        private void requestTile(Rect wanted) {
            if (closed || decoder == null || sourceWidth <= 0 || sourceHeight <= 0) {
                return;
            }
            if (tileBitmap != null && tileSource != null && tileSource.contains(wanted)) {
                return;
            }
            if (pendingTileSource != null && pendingTileSource.contains(wanted)) {
                return;
            }
            final int sampleSize = previewSampleSize();
            final Rect source = boundedPreviewTileSource(
                    wanted,
                    previewCropTop,
                    Math.max(previewCropTop + 1, sourceHeight - previewCropBottom),
                    sourceWidth,
                    sampleSize);
            pendingTileSource = source;
            final long requestId = ++tileRequestId;
            if (tileFuture != null) {
                tileFuture.cancel(true);
            }
            tileFuture = tileExecutor.submit(() -> decodeTile(requestId, source, sampleSize));
        }

        private void decodeTile(long requestId, Rect source, int sampleSize) {
            Bitmap decoded = null;
            try {
                synchronized (decoderLock) {
                    if (closed || requestId != tileRequestId || decoder == null) {
                        return;
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    options.inSampleSize = sampleSize;
                    decoded = decoder.decodeRegion(source, options);
                }
                if (decoded == null) {
                    throw new IOException("decodeRegion returned null");
                }
                final Bitmap result = decoded;
                if (!post(() -> installTile(requestId, source, result))) {
                    result.recycle();
                }
                decoded = null;
            } catch (IOException | OutOfMemoryError | RuntimeException error) {
                if (decoded != null) {
                    decoded.recycle();
                }
                post(() -> failTile(requestId));
            }
        }

        private void installTile(long requestId, Rect source, Bitmap decoded) {
            if (closed || requestId != tileRequestId || decoder == null) {
                decoded.recycle();
                return;
            }
            Bitmap previous = tileBitmap;
            tileBitmap = null;
            tileSource = null;
            tileBitmap = decoded;
            tileSource = new Rect(source);
            pendingTileSource = null;
            recycleTileOffMainThread(previous);
            invalidate();
        }

        private void recycleTileOffMainThread(Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            try {
                tileExecutor.execute(bitmap::recycle);
            } catch (RuntimeException error) {
                bitmap.recycle();
            }
        }

        private void failTile(long requestId) {
            if (!closed && requestId == tileRequestId && status != null) {
                pendingTileSource = null;
                status.setText(R.string.c6_status_preview_too_large);
            }
        }

        private void discardTile() {
            recycleTile();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int requestedWidth = MeasureSpec.getSize(widthMeasureSpec);
            if (requestedWidth <= 0) {
                requestedWidth = Math.max(1, sourceWidth);
            }
            int measuredWidth = resolveSize(requestedWidth, widthMeasureSpec);
            int measuredHeight = Math.max(1, displayHeight(measuredWidth));
            setMeasuredDimension(measuredWidth, measuredHeight);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width != oldWidth) {
                cancelTileRequest();
                recycleTile();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xffffffff);
            if (decoder == null || sourceWidth <= 0 || sourceHeight <= 0 || getWidth() <= 0) {
                return;
            }
            Rect visible = visibleDisplayRect(canvas);
            int visibleTop = clampInt(visible.top, 0, Math.max(0, getHeight() - 1));
            int visibleBottom = clampInt(visible.bottom, visibleTop + 1, Math.max(visibleTop + 1, getHeight()));
            float scale = getWidth() / (float) sourceWidth;
            Rect wanted = previewSourceWindow(
                    sourceWidth,
                    sourceHeight,
                    previewCropTop,
                    previewCropBottom,
                    getWidth(),
                    visibleTop,
                    visibleBottom);
            requestTile(wanted);
            boolean tileCoversViewport = tileBitmap != null
                    && tileSource != null
                    && tileSource.contains(wanted);
            if (!tileCoversViewport && fallbackBitmap != null) {
                drawFallback(canvas);
            }
            if (tileBitmap == null || tileSource == null) {
                return;
            }
            Rect dst = new Rect(
                    0,
                    Math.round((tileSource.top - previewCropTop) * scale),
                    getWidth(),
                    Math.round((tileSource.bottom - previewCropTop) * scale));
            try {
                canvas.drawBitmap(tileBitmap, null, dst, null);
            } catch (RuntimeException error) {
                discardTile();
                if (status != null) {
                    status.setText(R.string.c6_status_preview_too_large);
                }
            }
        }

        private void drawFallback(Canvas canvas) {
            if (fallbackBitmap == null || fallbackSampleSize <= 0 || getWidth() <= 0) {
                return;
            }
            int sourceTop = clampInt(
                    previewCropTop / fallbackSampleSize,
                    0,
                    Math.max(0, fallbackBitmap.getHeight() - 1));
            int sourceBottom = clampInt(
                    (sourceHeight - previewCropBottom + fallbackSampleSize - 1) / fallbackSampleSize,
                    sourceTop + 1,
                    fallbackBitmap.getHeight());
            Rect source = new Rect(0, sourceTop, fallbackBitmap.getWidth(), sourceBottom);
            Rect destination = new Rect(0, 0, getWidth(), displayHeight(getWidth()));
            canvas.drawBitmap(fallbackBitmap, source, destination, null);
        }

        private Rect visibleDisplayRect(Canvas canvas) {
            if (previewScroll != null && previewScroll.getHeight() > 0) {
                int top = previewScroll.getScrollY();
                return new Rect(0, top, getWidth(), top + previewScroll.getHeight());
            }
            Rect visible = new Rect();
            if (!canvas.getClipBounds(visible)) {
                visible.set(0, 0, getWidth(), getHeight());
            }
            return visible;
        }

        private int displayHeight(int width) {
            if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0) {
                return 1;
            }
            int displaySourceHeight = Math.max(1, sourceHeight - previewCropTop - previewCropBottom);
            return Math.max(1, Math.round(displaySourceHeight * (width / (float) sourceWidth)));
        }

        private int previewSampleSize() {
            int sample = 1;
            while (getWidth() > 0 && sourceWidth / (sample * 2) >= getWidth()) {
                sample *= 2;
            }
            return sample;
        }

        private void recycleTile() {
            if (tileBitmap != null) {
                tileBitmap.recycle();
                tileBitmap = null;
            }
            tileSource = null;
        }
    }

    static Rect previewSourceWindow(
            int sourceWidth,
            int sourceHeight,
            int cropTop,
            int cropBottom,
            int displayWidth,
            int visibleTop,
            int visibleBottom) {
        int safeSourceWidth = Math.max(1, sourceWidth);
        int safeSourceHeight = Math.max(1, sourceHeight);
        int safeCropTop = clampInt(cropTop, 0, safeSourceHeight - 1);
        int contentBottom = Math.max(
                safeCropTop + 1,
                safeSourceHeight - clampInt(cropBottom, 0, safeSourceHeight - safeCropTop - 1));
        float scale = Math.max(1, displayWidth) / (float) safeSourceWidth;
        int top = clampInt(
                safeCropTop + (int) Math.floor(Math.max(0, visibleTop) / scale),
                safeCropTop,
                contentBottom - 1);
        int bottom = clampInt(
                safeCropTop + (int) Math.ceil(Math.max(visibleTop + 1, visibleBottom) / scale),
                top + 1,
                contentBottom);
        return new Rect(0, top, safeSourceWidth, bottom);
    }

    static Rect boundedPreviewTileSource(
            Rect wanted,
            int contentTop,
            int contentBottom,
            int sourceWidth,
            int sampleSize) {
        int safeTop = clampInt(contentTop, 0, Math.max(0, contentBottom - 1));
        int safeBottom = Math.max(safeTop + 1, contentBottom);
        int maxHeight = previewMaxSourceTileHeight(sourceWidth, sampleSize);
        int wantedHeight = Math.min(maxHeight, Math.max(1, wanted.height()));
        int targetHeight = Math.min(
                maxHeight,
                wantedHeight + PREVIEW_TILE_PREFETCH_SOURCE_PX * 2);
        int top = wanted.top - Math.max(0, targetHeight - wantedHeight) / 2;
        top = clampInt(top, safeTop, Math.max(safeTop, safeBottom - targetHeight));
        int bottom = Math.min(safeBottom, top + targetHeight);
        top = Math.max(safeTop, bottom - targetHeight);
        return new Rect(0, top, Math.max(1, sourceWidth), bottom);
    }

    static int previewMaxSourceTileHeight(int sourceWidth, int sampleSize) {
        int sample = Math.max(1, sampleSize);
        long decodedWidth = Math.max(1L, (Math.max(1, sourceWidth) + sample - 1L) / sample);
        long decodedHeightByBytes = Math.max(
                1L,
                PREVIEW_TILE_MAX_DECODED_BYTES / (decodedWidth * 4L));
        long maxSourceHeight = Math.min(
                (long) PREVIEW_TILE_MAX_DECODED_HEIGHT * sample,
                decodedHeightByBytes * sample);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, maxSourceHeight));
    }

    static int previewFallbackSampleSize(int sourceWidth, int sourceHeight) {
        int safeWidth = Math.max(1, sourceWidth);
        int safeHeight = Math.max(1, sourceHeight);
        int sample = 1;
        while (true) {
            long decodedWidth = (safeWidth + sample - 1L) / sample;
            long decodedHeight = (safeHeight + sample - 1L) / sample;
            long decodedBytes = decodedWidth * decodedHeight * 4L;
            if (decodedHeight <= PREVIEW_FALLBACK_MAX_DECODED_HEIGHT
                    && decodedBytes <= PREVIEW_FALLBACK_MAX_DECODED_BYTES) {
                return sample;
            }
            if (sample >= 1 << 29) {
                return sample;
            }
            sample *= 2;
        }
    }

    private static int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void deleteCacheFile() {
        if (imageFile != null && imageFile.isFile()) {
            imageFile.delete();
        }
    }

    private void deleteSourceCacheFiles() {
        if (sourceFilePaths == null) {
            return;
        }
        for (String path : sourceFilePaths) {
            if (path != null) {
                File file = new File(path);
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}
