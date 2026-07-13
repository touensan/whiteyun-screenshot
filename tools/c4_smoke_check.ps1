$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$accessibility = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollAccessibilityService.java")
$accessibilityXml = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/xml/auto_scroll_accessibility_service.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    ".AutoScrollAccessibilityService",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "@xml/auto_scroll_accessibility_service"
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C4 manifest wiring: $needle"
    }
}

foreach ($needle in @(
    "canPerformGestures=`"true`"",
    "canRetrieveWindowContent=`"true`""
)) {
    if (-not $accessibilityXml.Contains($needle)) {
        throw "Missing accessibility config: $needle"
    }
}

foreach ($needle in @(
    "extends AccessibilityService",
    "GestureDescription",
    "dispatchGesture",
    "GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE",
    "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES",
    "ComponentName.unflattenFromString",
    "SWIPE_START_FRACTION = 0.78f",
    "SWIPE_END_FRACTION = 0.62f",
    "the 16%-screen gesture trades about 1.5x more frames"
)) {
    if (-not $accessibility.Contains($needle)) {
        throw "Missing accessibility service behavior: $needle"
    }
}

foreach ($needle in @(
    "MODE_AUTO",
    "ACTION_AUTO_START",
    "FRAME_AUTO_SAMPLE",
    "AutoScrollAccessibilityService.requestScroll",
    "accessibilitySettingsPendingIntent",
    "openAccessibilitySettings",
    "ponytail:"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C4 capture behavior: $needle"
    }
}

foreach ($needle in @(
    "Settings.ACTION_ACCESSIBILITY_SETTINGS",
    "AutoScrollAccessibilityService.isEnabled(this)",
    "startCaptureFlow(CaptureService.MODE_AUTO)"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C4 main entry behavior: $needle"
    }
}

foreach ($needle in @(
    "action_grant_accessibility",
    "overlay_start",
    "c4_status_accessibility_disconnected",
    "c4_status_ready"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C4 string: $needle"
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

Write-Host "C4 smoke check passed: $apk"
