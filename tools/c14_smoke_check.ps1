$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$support = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/SupportActivity.java")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "class SupportActivity",
    "Diagnostics.export(this)",
    "Intent.ACTION_SENDTO",
    "Intent.ACTION_SEND",
    "EXTRA_SUBJECT",
    "EXTRA_STREAM",
    "FLAG_GRANT_READ_URI_PERMISSION",
    "Settings.canDrawOverlays",
    "POST_NOTIFICATIONS",
    "applyActionBarOffset",
    "ponytail:"
)) {
    if (-not $support.Contains($needle)) {
        throw "Missing C14 support behavior: $needle"
    }
}

foreach ($needle in @(
    "openSupport",
    "SupportActivity.class",
    "c14_support_title"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C14 main wiring: $needle"
    }
}

if (-not $manifest.Contains("android:name=`".SupportActivity`"")) {
    throw "Missing C14 SupportActivity manifest registration"
}

foreach ($needle in @(
    "c14_support_title",
    "c14_support_feedback",
    "c14_support_share_diagnostics",
    "c14_support_check_update",
    "c14_support_update_not_configured"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C14 string: $needle"
    }
}

foreach ($forbidden in @(
    "READ_MEDIA_IMAGES",
    "READ_EXTERNAL_STORAGE"
)) {
    if ($manifest.Contains($forbidden)) {
        throw "C14 should not add broad storage permission: $forbidden"
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

Write-Host "C14 smoke check passed: $apk"
