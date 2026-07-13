$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$accessibility = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollAccessibilityService.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "showCaptureInfo(R.string.c30_auto_scroll_intro_title, R.string.c30_auto_scroll_intro_body)",
    "showCaptureInfo(R.string.c30_speed_mode_intro_title, R.string.c30_speed_mode_intro_body)",
    "showCaptureInfo(R.string.c30_manual_auto_intro_title, R.string.c30_manual_auto_intro_body)",
    "setPositiveButton(R.string.c30_info_ok, null)"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C30 old-mode help dialog behavior: $needle"
    }
}

foreach ($needle in @(
    "AUTO_STOP_LINE_NUMERATOR = 42",
    "AUTO_STOP_LINE_DENOMINATOR = 100",
    "autoStopTouchView",
    "showAutoStopTouchOverlay",
    "removeAutoStopTouchOverlay",
    "red_line_stop",
    "c30_red_line_hint",
    "addAutoRunningPanel(panel)",
    "compactButtonParams()",
    "roundedBackground(0xe61f2937",
    "drawAutoStopLine",
    "FLAG_NOT_TOUCHABLE",
    "R.string.c4_status_user_done"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C30 red-line stop behavior: $needle"
    }
}

foreach ($needle in @(
    "SWIPE_START_FRACTION = 0.78f",
    "SWIPE_END_FRACTION = 0.62f"
)) {
    if (-not $accessibility.Contains($needle)) {
        throw "Missing C30 red-line aligned auto scroll: $needle"
    }
}

foreach ($needle in @(
    "c30_manual_auto_intro_title",
    "c30_speed_mode_intro_title",
    "c30_auto_scroll_intro_title",
    "c30_red_line_hint",
    "c4_status_user_done"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C30 string: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 30')) {
    throw "C12 has not included C30 smoke"
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

Write-Host "C30 smoke check passed: $apk"
