$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    ".PreviewActivity",
    ".CaptureService"
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing manifest wiring: $needle"
    }
}

foreach ($needle in @(
    "startCaptureFlow(CaptureService.MODE_MANUAL)",
    "putExtra(CaptureService.EXTRA_MODE, pendingCaptureMode)"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing manual entry behavior: $needle"
    }
}

foreach ($needle in @(
    "ACTION_MANUAL_SAMPLE",
    "ACTION_MANUAL_FINISH",
    "LongScreenshotStitcher.stitch",
    "LongScreenshotStitcher.isNearDuplicate",
    "manualFrames.isEmpty()",
    "overlayStatusText = info",
    "panel.setMinimumWidth(dp(176))",
    "PreviewActivity.EXTRA_IMAGE_PATH"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C3 service behavior: $needle"
    }
}

foreach ($needle in @(
    "static Bitmap stitch",
    "static long fingerprint",
    "findOverlap",
    "ponytail:"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing stitcher behavior: $needle"
    }
}

foreach ($needle in @(
    "copyToMediaStore",
    "MediaStore.Images.Media.IS_PENDING",
    "RegionPreviewView"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing preview behavior: $needle"
    }
}

foreach ($needle in @(
    "overlay_sample",
    "overlay_finish",
    "preview_save"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C3 string: $needle"
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

Write-Host "C3 smoke check passed: $apk"
