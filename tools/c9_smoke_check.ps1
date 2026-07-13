$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$activity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchImagesActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "STEP_SELECT",
    "STEP_CONFIG",
    "STEP_PREVIEW_LIST",
    "STEP_SEAM_ADJUST",
    "renderSelectStep",
    "renderConfigStep",
    "renderPreviewListStep",
    "renderSeamAdjustStep",
    "analyzeStitchConfig",
    "ContentResolver().loadThumbnail",
    "c9_generate_result",
    "c9_back_to_preview",
    "ponytail:"
)) {
    if (-not $activity.Contains($needle)) {
        throw "Missing C9 staged stitch flow behavior: $needle"
    }
}

foreach ($needle in @(
    "ACTION_OPEN_DOCUMENT",
    "EXTRA_ALLOW_MULTIPLE",
    "MIN_IMAGES = 2",
    "LongScreenshotStitcher.analyze",
    "LongScreenshotStitcher.stitch(bitmaps, requestedOverlaps)",
    "PreviewActivity.EXTRA_IMAGE_PATH"
)) {
    if (-not $activity.Contains($needle)) {
        throw "C9 regressed C5 picker/stitch behavior: $needle"
    }
}

foreach ($forbidden in @(
    "MAX_IMAGES",
    "EXTRA_PICK_IMAGES_MAX",
    "EXTRA_PICK_IMAGES_IN_ORDER"
)) {
    if ($activity.Contains($forbidden)) {
        throw "C9 should not keep a picker max-count cap: $forbidden"
    }
}

foreach ($needle in @(
    "c9_step_select",
    "c9_step_config",
    "c9_step_preview_list",
    "c9_step_adjust_seam",
    "c9_status_preview_list_ready",
    "c9_overlap_row"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C9 string: $needle"
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

Write-Host "C9 smoke check passed: $apk"
