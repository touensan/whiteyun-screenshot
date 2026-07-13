$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$gradle = Get-Content -Raw -LiteralPath (Join-Path $root "app/build.gradle")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$diagnostics = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/Diagnostics.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "android:name=`".WhiteYunScreenshotApp`"",
    ".CaptureService",
    ".StitchImagesActivity"
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C6 manifest wiring: $needle"
    }
}

foreach ($needle in @(
    "keystore.properties",
    "WHITEYUN_RELEASE_STORE_FILE",
    "signingConfigs",
    "buildTypes",
    "versionCode 8",
    "versionName '1.6.1'"
)) {
    if (-not $gradle.Contains($needle)) {
        throw "Missing C6 release config: $needle"
    }
}

foreach ($needle in @(
    "addPermissionSummary",
    "Diagnostics.export",
    "action_export_diagnostics",
    "c6_status_notification_denied"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C6 main behavior: $needle"
    }
}

foreach ($needle in @(
    "MAX_STITCH_PIXELS",
    "ensureStitchLimit",
    "MAX_STITCH_HEIGHT"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C6 stitch limit: $needle"
    }
}

foreach ($needle in @(
    "catch (OutOfMemoryError",
    "c6_status_memory_limit"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C6 memory fallback: $needle"
    }
}

foreach ($needle in @(
    "Thread.UncaughtExceptionHandler",
    "last_crash.txt",
    "MediaStore.Downloads",
    "Environment.DIRECTORY_DOWNLOADS"
)) {
    if (-not $diagnostics.Contains($needle)) {
        throw "Missing C6 diagnostics behavior: $needle"
    }
}

foreach ($needle in @(
    "c6_permission_summary",
    "c6_status_diagnostics_saved",
    "c6_status_memory_limit"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C6 string: $needle"
    }
}

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle assemble failed"
    }
} finally {
    Pop-Location
}

$debugApk = Join-Path $root "app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path -LiteralPath $debugApk)) {
    throw "Missing debug APK: $debugApk"
}

$releaseDir = Join-Path $root "app/build/outputs/apk/release"
$signedRelease = Join-Path $releaseDir "app-release.apk"
$unsignedRelease = Join-Path $releaseDir "app-release-unsigned.apk"
if (Test-Path -LiteralPath $signedRelease) {
    Write-Host "C6 release APK is signed: $signedRelease"
} elseif (Test-Path -LiteralPath $unsignedRelease) {
    Write-Host "C6 release APK is unsigned because no release keystore was configured: $unsignedRelease"
} else {
    throw "Missing release APK in $releaseDir"
}

Write-Host "C6 smoke check passed: $debugApk"
