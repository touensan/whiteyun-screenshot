$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$activity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchImagesActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$gradle = Get-Content -Raw -LiteralPath (Join-Path $root "app/build.gradle")

foreach ($needle in @(
    ".StitchImagesActivity",
    "android:exported=`"false`""
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C5 manifest wiring: $needle"
    }
}

foreach ($needle in @(
    "startActivity(new Intent(this, StitchImagesActivity.class))",
    "action_stitch_images"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C5 main entry behavior: $needle"
    }
}

foreach ($needle in @(
    "ACTION_OPEN_DOCUMENT",
    "EXTRA_ALLOW_MULTIPLE",
    "MIN_IMAGES = 2",
    "picked.size() < MIN_IMAGES",
    "Collections.swap",
    "SeekBar",
    "LongScreenshotStitcher.analyze",
    "LongScreenshotStitcher.stitch(bitmaps, requestedOverlaps)",
    "PreviewActivity.EXTRA_IMAGE_PATH"
)) {
    if (-not $activity.Contains($needle)) {
        throw "Missing C5 activity behavior: $needle"
    }
}

foreach ($forbidden in @(
    "MAX_IMAGES",
    "EXTRA_PICK_IMAGES_MAX",
    "EXTRA_PICK_IMAGES_IN_ORDER"
)) {
    if ($activity.Contains($forbidden)) {
        throw "C5 should not keep a picker max-count cap: $forbidden"
    }
}

foreach ($needle in @(
    "static final class StitchPlan",
    "manualRequired",
    "maxOverlaps",
    "static StitchPlan analyze",
    "static Bitmap stitch(List<Bitmap> frames, int[] overlaps)",
    "ponytail:"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C5 stitcher behavior: $needle"
    }
}

foreach ($needle in @(
    "c5_pick_images",
    "c5_status_manual_needed",
    "c5_seam_label",
    "c5_generate_preview"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C5 string: $needle"
    }
}

if (-not ($gradle -match "versionCode\s+([0-9]+)")) {
    throw "Missing app versionCode"
}
if ([int]$Matches[1] -lt 6) {
    throw "versionCode is lower than the C5 baseline"
}
if (-not $gradle.Contains("versionName")) {
    throw "Missing app versionName"
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

Write-Host "C5 smoke check passed: $apk"
