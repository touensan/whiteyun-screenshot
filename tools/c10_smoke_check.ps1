$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$activity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/WebPageCaptureActivity.java")
$layout = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/layout/activity_capture_web_page.xml")
$ids = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/ids.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$styles = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/styles.xml")

foreach ($needle in @(
    "android.permission.INTERNET",
    ".WebPageCaptureActivity",
    "AppTheme.NoActionBar"
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C10 manifest behavior: $needle"
    }
}

foreach ($needle in @(
    "openWebPageCapture",
    "startActivity(new Intent(this, WebPageCaptureActivity.class))"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C10 main entry behavior: $needle"
    }
}

foreach ($needle in @(
    "WebView.enableSlowWholeDocumentDraw",
    "settings.setJavaScriptEnabled(true)",
    "settings.setDomStorageEnabled(true)",
    "new WebView(this)",
    "webView.setId(R.id.webview)",
    "webFrame.postDelayed(this::createWebView",
    "evaluateJavascript",
    "data-whiteyun-sticky-hidden",
    "setStartPosition",
    "renderCaptureBitmap",
    "MAX_CAPTURE_HEIGHT",
    "MAX_CAPTURE_PIXELS",
    "PreviewActivity.EXTRA_IMAGE_PATH",
    "ponytail:"
)) {
    if (-not $activity.Contains($needle)) {
        throw "Missing C10 webpage capture behavior: $needle"
    }
}

foreach ($needle in @(
    "ib_back",
    "et_url",
    "ib_refresh",
    "ib_clear_url",
    "progress_bar",
    "btn_set_start_pos",
    "btn_clear_sticky",
    "web_frame",
    "webpage_end_divider",
    "btn_capture",
    "android:fitsSystemWindows=`"true`"",
    "flagForceAscii",
    "textNoSuggestions"
)) {
    if (-not $layout.Contains($needle)) {
        throw "Missing C10 layout element: $needle"
    }
}

foreach ($needle in @(
    "title_activity_capture_web_page",
    "url_hint",
    "clear_sticky",
    "set_start_pos",
    "web_page_end_and_capture",
    "c10_web_status_preview_ready"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C10 string: $needle"
    }
}

if (-not $styles.Contains("AppTheme.NoActionBar")) {
    throw "Missing C10 no-action-bar style"
}

if (-not $ids.Contains('name="webview"')) {
    throw "Missing C10 programmatic WebView id"
}

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assembleDebug failed"
    }
} finally {
    Pop-Location
}

$apk = Join-Path $root "app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path -LiteralPath $apk)) {
    throw "Missing APK: $apk"
}

Write-Host "C10 smoke check passed: $apk"
