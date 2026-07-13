$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$progress = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchProgressActivity.java")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "startStitchQueueActivity();",
    "enqueueStitchJob(autoMode);",
    "StitchQueueStore.enqueueAuto",
    "StitchQueueStore.enqueueManual",
    "StitchQueueService.start(this)",
    "BAL blocks this after the service drops its visible window",
    "publishStitchProgress",
    "CAPTURE_OVERLAY_HIDE_MS = 120",
    "mainHandler.post(this::showOverlay);",
    ".addAction(R.drawable.ic_stat_capture, getString(R.string.overlay_cancel), stopPendingIntent)"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C31 stitch progress service behavior: $needle"
    }
}

foreach ($needle in @(
    "class StitchProgressActivity",
    "PERMISSION_INTERNAL_BROADCAST",
    "ACTION_STITCH_POLL",
    "StitchQueueService.class",
    "StitchQueueActivity.class",
    "c52_stitch_queue_view",
    "c31_stitch_cancel",
    "c31_stitch_retry",
    "ProgressBar"
)) {
    if (-not $progress.Contains($needle)) {
        throw "Missing C31 stitch progress activity behavior: $needle"
    }
}

foreach ($needle in @(
    'android:name=".StitchProgressActivity"',
    'android:exported="false"',
    'android:noHistory="true"'
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C31 manifest entry: $needle"
    }
}

foreach ($needle in @(
    "c31_stitch_title",
    "c31_stitch_analyzing_overlap",
    "c52_stitch_queue_view",
    "c31_stitch_cancel",
    "c31_stitch_retry"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C31 string: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 31')) {
    throw "C12 has not included C31 smoke"
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

Write-Host "C31 smoke check passed: $apk"
