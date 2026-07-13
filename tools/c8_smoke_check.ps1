$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$layout = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/layout/activity_main.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "speedModeOption",
    "wireCaptureOptions",
    "updateCaptureOptionStatus",
    "autoScrollOption.setOnCheckedChangeListener",
    "manualCaptureOption.setOnCheckedChangeListener",
    "startCaptureFlow(CaptureService.MODE_AUTO)",
    "startCaptureFlow(CaptureService.MODE_MANUAL)",
    "startCaptureFlow(CaptureService.MODE_CORE)",
    "putExtra(CaptureService.EXTRA_SPEED_MODE, pendingSpeedMode)"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C8 main behavior: $needle"
    }
}

foreach ($needle in @(
    "cb_auto_scroll",
    "cb_speed_mode",
    "cb_manual_capture"
)) {
    if (-not $layout.Contains($needle)) {
        throw "Missing C8 option in layout: $needle"
    }
}

foreach ($needle in @(
    "ACTION_CAPTURE_BEGIN",
    "EXTRA_SPEED_MODE",
    "OVERLAY_STAGE_READY",
    "OVERLAY_STAGE_RUNNING",
    "beginCaptureFlow",
    "finishCaptureFlow",
    "readyStatus",
    "runningStatus",
    "overlayStage == OVERLAY_STAGE_READY",
    "Gravity.START | Gravity.CENTER_VERTICAL",
    "overlay_capture",
    "overlay_done",
    "overlay_cancel",
    "scroll_intro"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C8 capture service behavior: $needle"
    }
}

foreach ($needle in @(
    "c8_status_option_auto",
    "c8_status_option_speed",
    "c8_status_option_manual",
    "c8_status_ready_speed",
    "overlay_capture",
    "overlay_done",
    "overlay_cancel",
    "scroll_intro"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C8 string: $needle"
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

Write-Host "C8 smoke check passed: $apk"
