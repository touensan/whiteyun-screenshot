$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")

foreach ($needle in @(
    "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "SYSTEM_ALERT_WINDOW",
    "foregroundServiceType=`"mediaProjection`"",
    ".CaptureService"
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing manifest wiring: $needle"
    }
}

foreach ($needle in @(
    "ImageReader.newInstance",
    "acquireLatestImage",
    "MediaStore.Images.Media.IS_PENDING",
    "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing capture service behavior: $needle"
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

Write-Host "C2 smoke check passed: $apk"
