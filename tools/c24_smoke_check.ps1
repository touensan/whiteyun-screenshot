$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "autoFinishing",
    "AUTO_MEMORY_FRAME_LIMIT = 1",
    "AUTO_STORAGE_RESERVE_BYTES",
    "autoFrameFiles",
    "StreamingLongScreenshotStitcher",
    "AUTO_TERMINAL_STATUS_MS",
    "overlayStatusText",
    "lastStatusMessage",
    "updateOverlayStatus",
    "updateCaptureNotification",
    "finishOrStopAutoAfterFailure",
    "stopAutoAfterFatalFailure",
    "cancelActiveAutoFrameRequest",
    "Intent.FLAG_ACTIVITY_CLEAR_TOP",
    "mainHandler.post(this::showOverlay)",
    "mainHandler.post(this::refreshOverlay)",
    "frameRequestInFlight.compareAndSet(FRAME_AUTO_SAMPLE, FRAME_NONE)"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C24 auto session hardening: $needle"
    }
}

if ($service.Contains("|| (MODE_AUTO.equals(captureMode) && autoRunning)")) {
    throw "C24 regression: auto overlay is still hidden for the whole running session"
}

foreach ($needle in @(
    "c4_status_auto_sampled",
    "c24_status_partial_preview"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C24 string: $needle"
    }
}

if ($service.Contains("MAX_AUTO_FRAMES")) {
    throw "C24 regression: automatic capture still has a fixed frame cap"
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

Write-Host "C24 smoke check passed: $apk"
