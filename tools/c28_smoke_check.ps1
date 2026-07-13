$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "DashPathEffect",
    "CaptureRangeOverlayView",
    "CAPTURE_OVERLAY_HIDE_MS",
    "rangeOverlayView",
    "showRangeOverlay",
    "updateRangeOverlay",
    "removeControlOverlay",
    "removeRangeOverlay",
    "removeCaptureOverlays",
    "shouldShowRangeOverlay",
    "FLAG_NOT_TOUCHABLE",
    "backgroundStitching",
    "releaseProjectionForBackgroundStitch",
    "stitchJobToken",
    "projection.unregisterCallback(projectionCallback)",
    "projection.stop()",
    "Foreground-service notification stays visible"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C28 range/background behavior: $needle"
    }
}

foreach ($needle in @(
    "c3_status_stitching",
    "c4_status_stitching"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C28 background stitching string: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 28')) {
    throw "C12 has not included C28 smoke"
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

Write-Host "C28 smoke check passed: $apk"
