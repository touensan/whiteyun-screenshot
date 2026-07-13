$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$prefs = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AppPreferences.java")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$stitch = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchImagesActivity.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")

foreach ($needle in @(
    "SharedPreferences",
    "capture_auto_scroll",
    "capture_speed_mode",
    "capture_manual_capture",
    "stitch_crop_system_bars",
    "preview_save_originals",
    "Context.MODE_PRIVATE",
    "ponytail:"
)) {
    if (-not $prefs.Contains($needle)) {
        throw "Missing C15 preference behavior: $needle"
    }
}

foreach ($needle in @(
    "loadCapturePreferences",
    "AppPreferences.isAutoScrollEnabled",
    "AppPreferences.isSpeedModeEnabled",
    "AppPreferences.isManualCaptureEnabled",
    "AppPreferences.setCaptureOptions"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C15 home preference wiring: $needle"
    }
}

foreach ($needle in @(
    "AppPreferences.isStitchCropSystemBars",
    "AppPreferences.setStitchCropSystemBars"
)) {
    if (-not $stitch.Contains($needle)) {
        throw "Missing C15 stitch preference wiring: $needle"
    }
}

foreach ($needle in @(
    "AppPreferences.isSaveOriginals",
    "AppPreferences.setSaveOriginals"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C15 preview preference wiring: $needle"
    }
}

foreach ($forbidden in @(
    "READ_MEDIA_IMAGES",
    "READ_EXTERNAL_STORAGE"
)) {
    if ($manifest.Contains($forbidden)) {
        throw "C15 should not add broad storage permission: $forbidden"
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

Write-Host "C15 smoke check passed: $apk"
