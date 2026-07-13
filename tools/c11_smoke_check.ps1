$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$capture = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$stitch = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchImagesActivity.java")
$web = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/WebPageCaptureActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "EXTRA_SOURCE_FILE_PATHS",
    "EXTRA_SOURCE_URIS",
    "EXTRA_RESULT_KIND",
    "RESULT_KIND_SINGLE",
    "RESULT_KIND_STITCH",
    "RESULT_KIND_WEBPAGE",
    "preview_browse",
    "preview_share",
    "preview_new",
    "preview_save_draft",
    "DraftStore.createDraftFile",
    "preview_save_originals",
    "saveOriginalSources",
    "ACTION_VIEW",
    "ACTION_SEND",
    "FLAG_GRANT_READ_URI_PERMISSION"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C11 unified preview behavior: $needle"
    }
}

foreach ($needle in @(
    "cropSystemBars(bitmapFromImage(image))",
    "writeOriginalFrameFiles",
    "PreviewActivity.RESULT_KIND_SINGLE",
    "PreviewActivity.RESULT_KIND_MANUAL",
    "PreviewActivity.RESULT_KIND_AUTO",
    "c11_status_single_preview_ready",
    "ponytail:"
)) {
    if (-not $capture.Contains($needle)) {
        throw "Missing C11 capture result behavior: $needle"
    }
}

foreach ($needle in @(
    "cropSystemBarsCheck",
    "c11_crop_system_bars",
    "seamPreview",
    "refreshSeamPreview",
    "buildSeamPreview",
    "ClipData.newUri",
    "PreviewActivity.EXTRA_SOURCE_URIS",
    "PreviewActivity.RESULT_KIND_STITCH"
)) {
    if (-not $stitch.Contains($needle)) {
        throw "Missing C11 stitch edit behavior: $needle"
    }
}

foreach ($needle in @(
    "PreviewActivity.RESULT_KIND_WEBPAGE"
)) {
    if (-not $web.Contains($needle)) {
        throw "Missing C11 webpage preview kind: $needle"
    }
}

foreach ($needle in @(
    "preview_browse",
    "preview_share",
    "preview_new",
    "preview_save_draft",
    "preview_draft_saved",
    "preview_save_originals",
    "preview_saved_with_originals",
    "c11_crop_system_bars",
    "c11_seam_preview",
    "c11_status_single_preview_ready"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C11 string: $needle"
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

Write-Host "C11 smoke check passed: $apk"
