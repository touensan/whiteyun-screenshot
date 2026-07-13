package com.whiteyun.screenshot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebPageCaptureActivity extends Activity {
    private static final int MAX_CAPTURE_HEIGHT = 32000;
    private static final int MAX_CAPTURE_PIXELS = 36_000_000;

    static {
        WebView.enableSlowWholeDocumentDraw();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText urlInput;
    private ProgressBar progressBar;
    private TextView status;
    private FrameLayout webFrame;
    private WebView webView;
    private Button setStartButton;
    private Button clearStickyButton;
    private Button captureButton;
    private View endDivider;
    private int startScrollY;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_web_page);

        ImageButton backButton = findViewById(R.id.ib_back);
        ImageButton refreshButton = findViewById(R.id.ib_refresh);
        ImageButton clearUrlButton = findViewById(R.id.ib_clear_url);
        urlInput = findViewById(R.id.et_url);
        progressBar = findViewById(R.id.progress_bar);
        status = findViewById(R.id.web_status);
        webFrame = findViewById(R.id.web_frame);
        setStartButton = findViewById(R.id.btn_set_start_pos);
        clearStickyButton = findViewById(R.id.btn_clear_sticky);
        captureButton = findViewById(R.id.btn_capture);
        endDivider = findViewById(R.id.webpage_end_divider);

        updateStartButton();
        status.setText(R.string.c10_web_status_empty);
        setBusy(true);

        backButton.setOnClickListener(view -> navigateBack());
        refreshButton.setOnClickListener(view -> loadFromInputOrRefresh());
        clearUrlButton.setOnClickListener(view -> {
            urlInput.setText("");
            status.setText(R.string.c10_web_status_empty);
        });
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (done || enter) {
                loadFromInput();
                return true;
            }
            return false;
        });
        clearStickyButton.setOnClickListener(view -> clearStickyElements());
        setStartButton.setOnClickListener(view -> setStartPosition());
        captureButton.setOnClickListener(view -> captureWebPage());

        webFrame.postDelayed(this::createWebView, 250);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request == null || !isSafeWebUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                startScrollY = 0;
                urlInput.setText(url);
                updateStartButton();
                endDivider.setVisibility(View.VISIBLE);
                status.setText(R.string.c10_web_status_loaded);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText(getString(R.string.c10_web_status_failed, error.getDescription()));
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });
    }

    private void createWebView() {
        if (isFinishing() || webView != null) {
            return;
        }
        webView = new WebView(this);
        webView.setId(R.id.webview);
        webFrame.addView(webView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        configureWebView();
        setBusy(false);

        String initialUrl = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (initialUrl != null && !initialUrl.trim().isEmpty()) {
            urlInput.setText(initialUrl.trim());
            loadFromInput();
        } else {
            status.setText(R.string.c10_web_status_empty);
        }
    }

    private void navigateBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        finish();
    }

    private void loadFromInputOrRefresh() {
        if (webView == null) {
            status.setText(R.string.c10_web_status_loading);
            return;
        }
        if (urlInput.getText().toString().trim().isEmpty() && webView.getUrl() != null) {
            webView.reload();
            return;
        }
        loadFromInput();
    }

    private void loadFromInput() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) {
            status.setText(R.string.c10_web_status_empty);
            return;
        }
        if (webView == null) {
            status.setText(R.string.c10_web_status_loading);
            return;
        }
        setBusy(false);
        startScrollY = 0;
        updateStartButton();
        status.setText(R.string.c10_web_status_loading);
        String normalized = normalizeUrl(raw);
        if (normalized.isEmpty()) {
            status.setText(R.string.c54_web_status_https_required);
            return;
        }
        webView.loadUrl(normalized);
    }

    private String normalizeUrl(String raw) {
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("https://")) {
            return value;
        }
        if (lower.startsWith("http://")) {
            return "https://" + value.substring("http://".length());
        }
        if (value.contains(":")) {
            return "";
        }
        if (looksLikeUrl(value)) {
            return "https://" + value;
        }
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            return "https://www.google.com/search?q=" + value;
        }
    }

    private static boolean isSafeWebUrl(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
    }

    private boolean looksLikeUrl(String value) {
        return value.contains(".")
                || value.startsWith("localhost")
                || value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?");
    }

    private void clearStickyElements() {
        if (webView == null) {
            status.setText(R.string.c10_web_status_empty);
            return;
        }
        webView.evaluateJavascript(
                "(function(){"
                        + "var n=0;"
                        + "Array.prototype.slice.call(document.querySelectorAll('*')).forEach(function(e){"
                        + "var s=getComputedStyle(e);"
                        + "if((s.position==='fixed'||s.position==='sticky')&&e.offsetWidth>innerWidth*0.2&&e.offsetHeight>16){"
                        + "e.setAttribute('data-whiteyun-sticky-hidden','true');"
                        + "e.style.setProperty('display','none','important');"
                        + "n++;"
                        + "}"
                        + "});"
                        + "return n;"
                        + "})()",
                value -> status.setText(R.string.c10_web_status_sticky_cleared));
    }

    private void setStartPosition() {
        if (webView == null) {
            return;
        }
        startScrollY = Math.max(0, webView.getScrollY());
        updateStartButton();
        status.setText(getString(R.string.c10_web_status_start_set, startScrollY));
    }

    private void updateStartButton() {
        if (setStartButton != null) {
            setStartButton.setText(getString(R.string.set_start_pos, startScrollY));
        }
    }

    private void captureWebPage() {
        if (busy) {
            return;
        }
        if (webView == null) {
            status.setText(R.string.c10_web_status_empty);
            return;
        }
        if (webView.getContentHeight() <= 0 || webView.getWidth() <= 0) {
            status.setText(R.string.c10_web_status_empty);
            return;
        }

        setBusy(true);
        status.setText(R.string.c10_web_status_capturing);
        Bitmap bitmap;
        try {
            bitmap = renderCaptureBitmap();
        } catch (Exception exception) {
            status.setText(getString(R.string.c10_web_status_failed, exception.getMessage()));
            setBusy(false);
            return;
        } catch (OutOfMemoryError error) {
            status.setText(getString(R.string.c10_web_status_failed, getString(R.string.c5_status_memory_limit)));
            setBusy(false);
            return;
        }

        worker.execute(() -> {
            try {
                File preview = writePreviewFile(bitmap);
                bitmap.recycle();
                mainHandler.post(() -> {
                    setBusy(false);
                    status.setText(R.string.c10_web_status_preview_ready);
                    openPreview(preview);
                });
            } catch (Exception exception) {
                bitmap.recycle();
                mainHandler.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.c10_web_status_failed, exception.getMessage()));
                });
            }
        });
    }

    private Bitmap renderCaptureBitmap() {
        int width = Math.max(1, webView.getWidth());
        int originalHeight = Math.max(1, webView.getHeight());
        int originalScrollY = Math.max(0, webView.getScrollY());
        int contentHeight = Math.max(1, Math.round(webView.getContentHeight() * webView.getScale()));
        int startY = Math.min(Math.max(0, startScrollY), contentHeight - 1);
        int endY = originalScrollY > startY
                ? Math.min(contentHeight, originalScrollY + originalHeight)
                : contentHeight;
        int captureHeight = Math.max(1, endY - startY);
        int heightByPixels = Math.max(1, MAX_CAPTURE_PIXELS / width);
        int cappedHeight = Math.min(captureHeight, Math.min(MAX_CAPTURE_HEIGHT, heightByPixels));
        if (cappedHeight < captureHeight) {
            status.setText(R.string.c10_web_status_too_large);
        }

        // ponytail: one-shot WebView draw is enough for C10; upgrade to tiled rendering if users need full 100k px pages.
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int contentHeightSpec = View.MeasureSpec.makeMeasureSpec(contentHeight, View.MeasureSpec.EXACTLY);
        int originalHeightSpec = View.MeasureSpec.makeMeasureSpec(originalHeight, View.MeasureSpec.EXACTLY);
        int oldLayoutWidth = webView.getWidth();
        int oldLayoutHeight = webView.getHeight();

        webView.scrollTo(0, 0);
        webView.measure(widthSpec, contentHeightSpec);
        webView.layout(0, 0, width, contentHeight);

        Bitmap bitmap = Bitmap.createBitmap(width, cappedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(0, -startY);
        webView.draw(canvas);

        webView.measure(widthSpec, originalHeightSpec);
        webView.layout(0, 0, oldLayoutWidth, oldLayoutHeight);
        webView.scrollTo(0, originalScrollY);
        webView.requestLayout();
        return bitmap;
    }

    private File writePreviewFile(Bitmap bitmap) throws IOException {
        File dir = new File(getCacheDir(), "webpage");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create cache dir");
        }
        File file = new File(dir, "WhiteYunWebPage_"
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
        intent.putExtra(PreviewActivity.EXTRA_RESULT_KIND, PreviewActivity.RESULT_KIND_WEBPAGE);
        startActivity(intent);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        urlInput.setEnabled(!busy);
        setStartButton.setEnabled(!busy);
        clearStickyButton.setEnabled(!busy);
        captureButton.setEnabled(!busy);
    }
}
