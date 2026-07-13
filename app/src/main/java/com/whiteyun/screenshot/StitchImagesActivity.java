package com.whiteyun.screenshot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StitchImagesActivity extends Activity {
    private static final int REQUEST_PICK_IMAGES = 5001;
    private static final int MIN_IMAGES = 2;
    private static final int MAX_DECODE_WIDTH = 1440;
    private static final int MAX_DECODE_HEIGHT = 5000;
    private static final int STEP_SELECT = 0;
    private static final int STEP_CONFIG = 1;
    private static final int STEP_PREVIEW_LIST = 2;
    private static final int STEP_SEAM_ADJUST = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> imageUris = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout list;
    private TextView status;
    private TextView seamLabel;
    private SeekBar seamSeek;
    private ImageView seamPreview;
    private CheckBox cropSystemBarsCheck;
    private Button pickButton;
    private Button configureButton;
    private Button joinButton;
    private Button generateButton;
    private Button previousSeamButton;
    private Button nextSeamButton;
    private LongScreenshotStitcher.StitchPlan stitchPlan;
    private CharSequence currentStatusText;
    private int currentSeam;
    private int seamPreviewToken;
    private int step = STEP_SELECT;
    private boolean busy;
    private boolean cropSystemBars;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff8fafc);
        root.setPadding(dp(16), dp(28), dp(16), dp(16));
        setContentView(root);

        cropSystemBars = AppPreferences.isStitchCropSystemBars(this);
        currentStatusText = getString(R.string.c5_status_empty);
        // ponytail: keep the old four-screen flow inside one Activity; split only if this state must survive rotation.
        renderStep();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void renderStep() {
        root.removeAllViews();
        list = null;
        seamLabel = null;
        seamSeek = null;
        pickButton = null;
        configureButton = null;
        joinButton = null;
        generateButton = null;
        previousSeamButton = null;
        nextSeamButton = null;
        seamPreview = null;
        cropSystemBarsCheck = null;

        if (step == STEP_SELECT) {
            addTitle(R.string.c9_step_select);
            addStatus();
            renderSelectStep();
        } else if (step == STEP_CONFIG) {
            addTitle(R.string.c9_step_config);
            addStatus();
            renderConfigStep();
        } else if (step == STEP_PREVIEW_LIST) {
            addTitle(R.string.c9_step_preview_list);
            addStatus();
            renderPreviewListStep();
        } else {
            addTitle(R.string.c9_step_adjust_seam);
            addStatus();
            renderSeamAdjustStep();
        }
        setBusy(busy);
    }

    private void renderSelectStep() {
        addIntro(R.string.c9_select_intro, false);

        pickButton = fullButton(R.string.c5_pick_images);
        pickButton.setOnClickListener(view -> openImagePicker());
        root.addView(pickButton, fullButtonParams(16));

        if (!imageUris.isEmpty()) {
            addSelectionList(false);
            Button next = fullButton(R.string.c9_continue_config);
            next.setOnClickListener(view -> {
                step = STEP_CONFIG;
                renderStep();
            });
            root.addView(next, fullButtonParams(12));
        }
    }

    private void renderConfigStep() {
        if (imageUris.isEmpty()) {
            step = STEP_SELECT;
            renderStep();
            return;
        }

        addIntro(R.string.c9_config_intro, false);
        cropSystemBarsCheck = new CheckBox(this);
        cropSystemBarsCheck.setText(R.string.c11_crop_system_bars);
        cropSystemBarsCheck.setTextColor(0xff1f2937);
        cropSystemBarsCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cropSystemBarsCheck.setChecked(cropSystemBars);
        cropSystemBarsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cropSystemBars = isChecked;
            AppPreferences.setStitchCropSystemBars(this, isChecked);
            stitchPlan = null;
            currentSeam = 0;
            setStatus(isChecked ? R.string.c11_status_crop_enabled : R.string.c11_status_crop_disabled);
        });
        root.addView(cropSystemBarsCheck, fullButtonParams(8));
        addSelectionList(true);

        pickButton = fullButton(R.string.c9_reselect_images);
        pickButton.setOnClickListener(view -> openImagePicker());
        root.addView(pickButton, fullButtonParams(12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        configureButton = actionButton(R.string.c9_analyze_config);
        configureButton.setOnClickListener(view -> analyzeStitchConfig());
        actions.addView(configureButton, actionParams());

        joinButton = actionButton(R.string.c9_join_after_config);
        joinButton.setOnClickListener(view -> analyzeStitchConfig());
        actions.addView(joinButton, actionParams());
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void renderPreviewListStep() {
        if (imageUris.isEmpty()) {
            step = STEP_SELECT;
            renderStep();
            return;
        }

        addIntro(R.string.c9_preview_intro, true);
        addSelectionList(false);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button back = actionButton(R.string.c9_back_to_config);
        back.setOnClickListener(view -> {
            step = STEP_CONFIG;
            renderStep();
        });
        actions.addView(back, actionParams());

        Button adjust = actionButton(R.string.c9_adjust_seam);
        adjust.setEnabled(stitchPlan != null && imageUris.size() > 1);
        adjust.setOnClickListener(view -> {
            if (stitchPlan != null) {
                currentSeam = stitchPlan.firstManualSeam();
                step = STEP_SEAM_ADJUST;
                renderStep();
            }
        });
        actions.addView(adjust, actionParams());
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        generateButton = fullButton(R.string.c9_generate_result);
        generateButton.setOnClickListener(view -> generatePreview());
        root.addView(generateButton, fullButtonParams(12));
    }

    private void renderSeamAdjustStep() {
        if (stitchPlan == null) {
            step = STEP_PREVIEW_LIST;
            renderStep();
            return;
        }

        addIntro(R.string.c9_adjust_intro, false);

        seamLabel = new TextView(this);
        seamLabel.setTextColor(0xff1f2937);
        seamLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams seamLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        seamLabelParams.topMargin = dp(12);
        root.addView(seamLabel, seamLabelParams);

        seamSeek = new SeekBar(this);
        seamSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && stitchPlan != null && currentSeam > 0) {
                    stitchPlan.overlaps[currentSeam] = progress;
                    stitchPlan.manualRequired[currentSeam] = false;
                    stitchPlan.seamMessages[currentSeam] = getString(R.string.c26_manual_confirmed);
                    updateSeamControls();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                refreshSeamPreview();
            }
        });
        root.addView(seamSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView previewTitle = new TextView(this);
        previewTitle.setText(R.string.c11_seam_preview);
        previewTitle.setTextColor(0xff1f2937);
        previewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams previewTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        previewTitleParams.topMargin = dp(8);
        root.addView(previewTitle, previewTitleParams);

        seamPreview = new ImageView(this);
        seamPreview.setAdjustViewBounds(true);
        seamPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        seamPreview.setBackgroundColor(0xffffffff);
        seamPreview.setContentDescription(getString(R.string.c11_seam_preview));
        LinearLayout.LayoutParams seamPreviewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180));
        seamPreviewParams.topMargin = dp(6);
        root.addView(seamPreview, seamPreviewParams);

        LinearLayout nudgeActions = new LinearLayout(this);
        nudgeActions.setOrientation(LinearLayout.HORIZONTAL);
        Button minusTen = smallButton(R.string.c9_ten_px_down);
        minusTen.setOnClickListener(view -> adjustCurrentSeam(-10));
        nudgeActions.addView(minusTen, actionParams());
        Button minusOne = smallButton(R.string.c9_one_px_down);
        minusOne.setOnClickListener(view -> adjustCurrentSeam(-1));
        nudgeActions.addView(minusOne, actionParams());
        Button plusOne = smallButton(R.string.c9_one_px_up);
        plusOne.setOnClickListener(view -> adjustCurrentSeam(1));
        nudgeActions.addView(plusOne, actionParams());
        Button plusTen = smallButton(R.string.c9_ten_px_up);
        plusTen.setOnClickListener(view -> adjustCurrentSeam(10));
        nudgeActions.addView(plusTen, actionParams());
        root.addView(nudgeActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refreshPreview = fullButton(R.string.c11_refresh_seam_preview);
        refreshPreview.setOnClickListener(view -> refreshSeamPreview());
        root.addView(refreshPreview, fullButtonParams(4));

        LinearLayout seamActions = new LinearLayout(this);
        seamActions.setOrientation(LinearLayout.HORIZONTAL);
        previousSeamButton = actionButton(R.string.c5_previous_seam);
        previousSeamButton.setOnClickListener(view -> {
            currentSeam = Math.max(1, currentSeam - 1);
            updateSeamControls();
            refreshSeamPreview();
        });
        seamActions.addView(previousSeamButton, actionParams());

        nextSeamButton = actionButton(R.string.c5_next_seam);
        nextSeamButton.setOnClickListener(view -> {
            currentSeam = Math.min(imageUris.size() - 1, currentSeam + 1);
            updateSeamControls();
            refreshSeamPreview();
        });
        seamActions.addView(nextSeamButton, actionParams());
        root.addView(seamActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bottomActions = new LinearLayout(this);
        bottomActions.setOrientation(LinearLayout.HORIZONTAL);
        Button back = actionButton(R.string.c9_back_to_preview);
        back.setOnClickListener(view -> {
            step = STEP_PREVIEW_LIST;
            renderStep();
        });
        bottomActions.addView(back, actionParams());

        generateButton = actionButton(R.string.c9_generate_result);
        generateButton.setOnClickListener(view -> generatePreview());
        bottomActions.addView(generateButton, actionParams());
        root.addView(bottomActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        updateSeamControls();
        refreshSeamPreview();
    }

    private void addTitle(int titleRes) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(0xff111827);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addStatus() {
        status = new TextView(this);
        status.setText(currentStatusText);
        status.setTextColor(0xff4b5563);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        root.addView(status, statusParams);
    }

    private void addIntro(int introRes, boolean highlight) {
        TextView intro = new TextView(this);
        intro.setText(introRes);
        intro.setTextColor(0xff1f2937);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setPadding(dp(12), dp(10), dp(12), dp(10));
        intro.setBackgroundColor(highlight ? 0xfffff7cc : 0xffffffff);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        root.addView(intro, params);
    }

    private void addSelectionList(boolean allowReorder) {
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView listScroll = new ScrollView(this);
        listScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        listParams.topMargin = dp(12);
        root.addView(listScroll, listParams);
        renderSelection(allowReorder);
    }

    private void openImagePicker() {
        Intent intent = createPickerIntent();
        startActivityForResult(intent, REQUEST_PICK_IMAGES);
    }

    private Intent createPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }

        ArrayList<Uri> picked = collectUris(data);
        if (picked.size() < MIN_IMAGES) {
            setStatus(getString(R.string.c5_status_need_count, MIN_IMAGES));
            return;
        }

        imageUris.clear();
        imageUris.addAll(picked);
        stitchPlan = null;
        currentSeam = 0;
        step = STEP_CONFIG;
        setStatus(getString(R.string.c9_status_config_ready, imageUris.size()));
        renderStep();
    }

    private ArrayList<Uri> collectUris(Intent data) {
        ArrayList<Uri> picked = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    picked.add(uri);
                    persistReadGrant(data, uri);
                }
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
            persistReadGrant(data, data.getData());
        }
        return picked;
    }

    private void persistReadGrant(Intent data, Uri uri) {
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void renderSelection() {
        renderSelection(true);
    }

    private void renderSelection(boolean allowReorder) {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        for (int i = 0; i < imageUris.size(); i++) {
            list.addView(selectionRow(i, allowReorder));
        }
    }

    private View selectionRow(int index, boolean allowReorder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(0xffe5e7eb);
        bindThumbnail(thumbnail, imageUris.get(index));
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        thumbnailParams.rightMargin = dp(10);
        row.addView(thumbnail, thumbnailParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(getString(R.string.c5_image_row, index + 1, shortName(imageUris.get(index))));
        label.setTextColor(0xff111827);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textColumn.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!allowReorder && stitchPlan != null && index > 0) {
            TextView seamInfo = new TextView(this);
            seamInfo.setText(getString(
                    R.string.c26_overlap_row,
                    stitchPlan.overlaps[index],
                    stitchPlan.scores[index],
                    stitchPlan.seamScores[index],
                    stitchPlan.consensusScores[index],
                    seamMessage(index)));
            seamInfo.setTextColor(stitchPlan.manualRequired[index] ? 0xffb91c1c : 0xff6b7280);
            seamInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textColumn.addView(seamInfo, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        if (allowReorder) {
            Button up = smallButton(R.string.c5_move_up);
            up.setEnabled(index > 0);
            up.setOnClickListener(view -> moveImage(index, -1));
            row.addView(up);

            Button down = smallButton(R.string.c5_move_down);
            down.setEnabled(index < imageUris.size() - 1);
            down.setOnClickListener(view -> moveImage(index, 1));
            row.addView(down);
        }
        return row;
    }

    private void bindThumbnail(ImageView thumbnail, Uri uri) {
        try {
            thumbnail.setImageBitmap(getContentResolver().loadThumbnail(uri, new Size(dp(64), dp(64)), null));
        } catch (IOException | SecurityException exception) {
            thumbnail.setImageDrawable(null);
        }
    }

    private void moveImage(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= imageUris.size()) {
            return;
        }
        Collections.swap(imageUris, index, target);
        stitchPlan = null;
        currentSeam = 0;
        renderSelection();
        updateSeamControls();
        setStatus(R.string.c5_status_order_changed);
    }

    private void analyzeStitchConfig() {
        if (busy) {
            return;
        }
        if (imageUris.size() < MIN_IMAGES) {
            setStatus(getString(R.string.c5_status_need_count, MIN_IMAGES));
            return;
        }

        setBusy(true);
        setStatus(R.string.c9_status_analyzing);
        worker.execute(() -> {
            ArrayList<Bitmap> bitmaps = new ArrayList<>();
            try {
                for (Uri uri : imageUris) {
                    bitmaps.add(decodeBitmap(uri));
                }

                LongScreenshotStitcher.StitchPlan plan = LongScreenshotStitcher.analyze(bitmaps);
                mainHandler.post(() -> {
                    stitchPlan = plan;
                    currentSeam = plan.firstManualSeam();
                    step = STEP_PREVIEW_LIST;
                    setStatus(plan.needsManualAdjustment()
                            ? getString(R.string.c5_status_manual_needed, currentSeam, currentSeam + 1)
                            : getString(R.string.c9_status_preview_list_ready, imageUris.size()));
                    renderStep();
                    setBusy(false);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setStatus(getString(R.string.c5_status_failed, exception.getMessage()));
                    setBusy(false);
                });
            } catch (OutOfMemoryError error) {
                mainHandler.post(() -> {
                    setStatus(getString(R.string.c5_status_failed, getString(R.string.c5_status_memory_limit)));
                    setBusy(false);
                });
            } finally {
                for (Bitmap bitmap : bitmaps) {
                    bitmap.recycle();
                }
            }
        });
    }

    private void generatePreview() {
        if (busy) {
            return;
        }
        if (imageUris.size() < MIN_IMAGES) {
            setStatus(getString(R.string.c5_status_need_count, MIN_IMAGES));
            return;
        }
        if (stitchPlan != null && stitchPlan.needsManualAdjustment()) {
            currentSeam = stitchPlan.firstManualSeam();
            step = STEP_SEAM_ADJUST;
            setStatus(getString(R.string.c5_status_manual_needed, currentSeam, currentSeam + 1));
            renderStep();
            return;
        }

        setBusy(true);
        final int[] overlapSnapshot = stitchPlan == null ? null : stitchPlan.overlaps.clone();
        worker.execute(() -> {
            ArrayList<Bitmap> bitmaps = new ArrayList<>();
            try {
                for (Uri uri : imageUris) {
                    bitmaps.add(decodeBitmap(uri));
                }

                int[] requestedOverlaps = overlapSnapshot;
                if (requestedOverlaps == null) {
                    LongScreenshotStitcher.StitchPlan plan = LongScreenshotStitcher.analyze(bitmaps);
                    if (plan.needsManualAdjustment()) {
                        mainHandler.post(() -> {
                            stitchPlan = plan;
                            currentSeam = plan.firstManualSeam();
                            step = STEP_PREVIEW_LIST;
                            setStatus(getString(
                                    R.string.c5_status_manual_needed,
                                    currentSeam,
                                    currentSeam + 1));
                            renderStep();
                            setBusy(false);
                        });
                        return;
                    }
                    requestedOverlaps = plan.overlaps.clone();
                }

                Bitmap stitched = LongScreenshotStitcher.stitch(bitmaps, requestedOverlaps);
                File preview = writePreviewFile(stitched);
                stitched.recycle();
                mainHandler.post(() -> {
                    setStatus(R.string.c5_status_preview_ready);
                    setBusy(false);
                    openPreview(preview);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    setStatus(getString(R.string.c5_status_failed, exception.getMessage()));
                    setBusy(false);
                });
            } catch (OutOfMemoryError error) {
                mainHandler.post(() -> {
                    setStatus(getString(R.string.c5_status_failed, getString(R.string.c5_status_memory_limit)));
                    setBusy(false);
                });
            } finally {
                for (Bitmap bitmap : bitmaps) {
                    bitmap.recycle();
                }
            }
        });
        setStatus(R.string.c5_status_stitching);
    }

    private Bitmap decodeBitmap(Uri uri) throws IOException {
        ContentResolver resolver = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("openInputStream returned null");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法读取图片尺寸");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > MAX_DECODE_WIDTH
                || bounds.outHeight / options.inSampleSize > MAX_DECODE_HEIGHT) {
            options.inSampleSize *= 2;
        }

        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("openInputStream returned null");
            }
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) {
                throw new IOException("无法解码图片");
            }
            return cropSystemBars ? cropSystemBars(bitmap) : bitmap;
        }
    }

    private Bitmap cropSystemBars(Bitmap bitmap) {
        int top = systemBarPixels("status_bar_height");
        int bottom = systemBarPixels("navigation_bar_height");
        int maxCrop = Math.max(0, bitmap.getHeight() / 4);
        int cropTop = Math.min(top, maxCrop);
        int cropBottom = Math.min(bottom, maxCrop);
        int height = bitmap.getHeight() - cropTop - cropBottom;
        if (height <= bitmap.getHeight() / 2 || (cropTop == 0 && cropBottom == 0)) {
            return bitmap;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.getWidth(), height);
        bitmap.recycle();
        return cropped;
    }

    private int systemBarPixels(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private void refreshSeamPreview() {
        if (seamPreview == null || stitchPlan == null
                || currentSeam <= 0 || currentSeam >= imageUris.size()) {
            if (seamPreview != null) {
                seamPreview.setImageDrawable(null);
            }
            return;
        }

        final int token = ++seamPreviewToken;
        final int seam = currentSeam;
        final int overlap = stitchPlan.overlaps[currentSeam];
        worker.execute(() -> {
            Bitmap previous = null;
            Bitmap next = null;
            Bitmap preview = null;
            try {
                previous = decodeBitmap(imageUris.get(seam - 1));
                next = decodeBitmap(imageUris.get(seam));
                preview = buildSeamPreview(previous, next, overlap);
                Bitmap ready = preview;
                preview = null;
                mainHandler.post(() -> {
                    if (token == seamPreviewToken && seamPreview != null) {
                        seamPreview.setImageBitmap(ready);
                    } else {
                        ready.recycle();
                    }
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    if (token == seamPreviewToken) {
                        setStatus(getString(R.string.c5_status_failed, exception.getMessage()));
                    }
                });
            } catch (OutOfMemoryError error) {
                mainHandler.post(() -> {
                    if (token == seamPreviewToken) {
                        setStatus(getString(R.string.c5_status_failed, getString(R.string.c5_status_memory_limit)));
                    }
                });
            } finally {
                if (previous != null) {
                    previous.recycle();
                }
                if (next != null) {
                    next.recycle();
                }
                if (preview != null) {
                    preview.recycle();
                }
            }
        });
    }

    private Bitmap buildSeamPreview(Bitmap previous, Bitmap next, int overlap) {
        int width = Math.min(previous.getWidth(), next.getWidth());
        int band = Math.max(80, Math.min(320, Math.min(previous.getHeight(), next.getHeight()) / 4));
        int topHeight = Math.min(band, previous.getHeight());
        int nextStart = Math.max(0, Math.min(overlap, Math.max(0, next.getHeight() - 1)));
        int bottomHeight = Math.min(band, next.getHeight() - nextStart);
        int divider = 4;

        Bitmap output = Bitmap.createBitmap(width, topHeight + divider + bottomHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(0xffffffff);
        canvas.drawBitmap(
                previous,
                new Rect(0, previous.getHeight() - topHeight, width, previous.getHeight()),
                new Rect(0, 0, width, topHeight),
                null);
        Paint paint = new Paint();
        paint.setColor(0xffff5252);
        canvas.drawRect(0, topHeight, width, topHeight + divider, paint);
        if (bottomHeight > 0) {
            canvas.drawBitmap(
                    next,
                    new Rect(0, nextStart, width, nextStart + bottomHeight),
                    new Rect(0, topHeight + divider, width, topHeight + divider + bottomHeight),
                    null);
        }
        return output;
    }

    private void adjustCurrentSeam(int delta) {
        if (stitchPlan == null || currentSeam <= 0) {
            return;
        }
        int max = Math.max(1, stitchPlan.maxOverlaps[currentSeam]);
        int overlap = Math.max(0, Math.min(max, stitchPlan.overlaps[currentSeam] + delta));
        stitchPlan.overlaps[currentSeam] = overlap;
        stitchPlan.manualRequired[currentSeam] = false;
        stitchPlan.seamMessages[currentSeam] = getString(R.string.c26_manual_confirmed);
        setStatus(R.string.c9_status_adjusted);
        updateSeamControls();
        refreshSeamPreview();
    }

    private void updateSeamControls() {
        if (seamSeek == null || seamLabel == null
                || previousSeamButton == null || nextSeamButton == null) {
            return;
        }

        boolean hasPlan = stitchPlan != null && imageUris.size() > 1;
        seamSeek.setEnabled(hasPlan);
        previousSeamButton.setEnabled(hasPlan && currentSeam > 1);
        nextSeamButton.setEnabled(hasPlan && currentSeam < imageUris.size() - 1);
        if (!hasPlan) {
            seamLabel.setText(R.string.c5_seam_idle);
            seamSeek.setMax(1);
            seamSeek.setProgress(0);
            return;
        }

        currentSeam = Math.max(1, Math.min(currentSeam, imageUris.size() - 1));
        int max = Math.max(1, stitchPlan.maxOverlaps[currentSeam]);
        int overlap = Math.max(0, Math.min(max, stitchPlan.overlaps[currentSeam]));
        stitchPlan.overlaps[currentSeam] = overlap;
        seamSeek.setMax(max);
        seamSeek.setProgress(overlap);
        seamLabel.setText(getString(
                R.string.c26_seam_label,
                currentSeam,
                currentSeam + 1,
                overlap,
                stitchPlan.scores[currentSeam],
                stitchPlan.seamScores[currentSeam],
                stitchPlan.consensusScores[currentSeam],
                seamMessage(currentSeam)));
    }

    private String seamMessage(int seam) {
        if (stitchPlan == null || stitchPlan.seamMessages == null
                || seam < 0 || seam >= stitchPlan.seamMessages.length
                || stitchPlan.seamMessages[seam] == null
                || stitchPlan.seamMessages[seam].isEmpty()) {
            return getString(R.string.c26_manual_confirmed);
        }
        return stitchPlan.seamMessages[seam];
    }

    private void setStatus(int statusRes) {
        setStatus(getString(statusRes));
    }

    private void setStatus(CharSequence text) {
        currentStatusText = text;
        if (status != null) {
            status.setText(text);
        }
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        if (pickButton != null) {
            pickButton.setEnabled(!busy);
        }
        if (configureButton != null) {
            configureButton.setEnabled(!busy);
        }
        if (joinButton != null) {
            joinButton.setEnabled(!busy);
        }
        if (generateButton != null) {
            generateButton.setEnabled(!busy);
        }
    }

    private Button fullButton(int labelRes) {
        Button button = actionButton(labelRes);
        button.setMinHeight(dp(52));
        return button;
    }

    private Button actionButton(int labelRes) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(labelRes);
        return button;
    }

    private Button smallButton(int labelRes) {
        Button button = actionButton(labelRes);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setMinWidth(dp(52));
        return button;
    }

    private LinearLayout.LayoutParams fullButtonParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        params.topMargin = dp(8);
        return params;
    }

    private File writePreviewFile(Bitmap bitmap) throws IOException {
        File dir = new File(getCacheDir(), "manual");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        File file = new File(dir, "WhiteYunLongShot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG write failed");
            }
        }
        return file;
    }

    private void openPreview(File file) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, PreviewActivity.RESULT_KIND_STITCH);
        ArrayList<String> sourceValues = new ArrayList<>();
        ClipData clipData = null;
        for (Uri uri : imageUris) {
            sourceValues.add(uri.toString());
            if (clipData == null) {
                clipData = ClipData.newUri(getContentResolver(), "whiteyun-originals", uri);
            } else {
                clipData.addItem(new ClipData.Item(uri));
            }
        }
        if (!sourceValues.isEmpty()) {
            intent.putStringArrayListExtra(PreviewActivity.EXTRA_SOURCE_URIS, sourceValues);
            intent.setClipData(clipData);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(intent);
    }

    private String shortName(Uri uri) {
        String value = uri.getLastPathSegment();
        if (value == null || value.isEmpty()) {
            value = uri.toString();
        }
        return value.length() > 28 ? value.substring(value.length() - 28) : value;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}
