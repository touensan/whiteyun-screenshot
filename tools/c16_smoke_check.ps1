$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")

foreach ($needle in @(
    "BitmapRegionDecoder",
    "SeekBar",
    "cropTopSeek",
    "cropBottomSeek",
    "updateCropPreview",
    "copyCroppedFileToMediaStore",
    "cropRect",
    "savedUri = null",
    "R.string.c16_crop_title",
    "R.string.c16_status_crop_updated",
    "ponytail:"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C16 preview crop behavior: $needle"
    }
}

foreach ($needle in @(
    "c16_crop_title",
    "c16_crop_top",
    "c16_crop_bottom",
    "c16_crop_minus_10",
    "c16_crop_plus_10",
    "c16_crop_reset",
    "c16_status_crop_updated"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C16 string: $needle"
    }
}

foreach ($forbidden in @(
    "READ_MEDIA_IMAGES",
    "READ_EXTERNAL_STORAGE"
)) {
    if ($manifest.Contains($forbidden)) {
        throw "C16 should not add broad storage permission: $forbidden"
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

Write-Host "C16 smoke check passed: $apk"
