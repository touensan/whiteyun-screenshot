$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "BitmapRegionDecoder",
    "new Rect(0, 0, bounds.outWidth, regionHeight)",
    "draftMeta",
    "R.string.draft_latest_title",
    "R.string.draft_item_title",
    "bounds.outWidth / options.inSampleSize > targetWidth",
    "ponytail: a top crop is enough for a draft clue"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C36 draft preview behavior: $needle"
    }
}

if ($main.Contains("bounds.outHeight / options.inSampleSize > dp(120)")) {
    throw "C36 thumbnail must not downsample by full long-image height"
}

foreach ($needle in @(
    "previewScroll",
    "updatePreviewSafeArea",
    "previewScroll.setPadding(0, top, 0, bottom)",
    "previewScroll.setClipToPadding(true)",
    "chromeBottom.post(this::updatePreviewSafeArea)"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C36 preview safe area behavior: $needle"
    }
}

foreach ($needle in @(
    "draft_latest_title",
    "draft_item_title",
    "draft_saved_meta",
    "draft_dimensions",
    "draft_dimensions_unknown"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C36 string: $needle"
    }
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

Write-Host "C36 smoke check passed: $apk"
