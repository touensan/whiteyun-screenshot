$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$layout = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/layout/activity_main.xml")
$menu = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/menu/menu_main.xml")
$styles = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/styles.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "setContentView(R.layout.activity_main)",
    "onCreateOptionsMenu",
    "showSettingsMenu",
    "addPermissionSummary",
    "startScreenshotCaptureFromOptions",
    "startActivity(new Intent(this, StitchImagesActivity.class))"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C7 main wiring: $needle"
    }
}

foreach ($needle in @(
    "card_capture_screenshot",
    "card_capture_webpage",
    "card_select_images",
    "cb_auto_scroll",
    "cb_speed_mode",
    "cb_manual_capture"
)) {
    if (-not $layout.Contains($needle)) {
        throw "Missing C7 layout element: $needle"
    }
}

if ($main.Contains("addButton(")) {
    throw "C7 should not keep the old full-width button stack"
}

foreach ($needle in @(
    "action_settings",
    "action_usage",
    "ic_settings",
    "ic_help"
)) {
    if (-not $menu.Contains($needle)) {
        throw "Missing C7 menu item: $needle"
    }
}

foreach ($needle in @(
    "Theme.Material.Light",
    "AppTheme.ActionBar",
    "colorPrimaryDark"
)) {
    if (-not $styles.Contains($needle)) {
        throw "Missing C7 action bar style: $needle"
    }
}

foreach ($needle in @(
    "capture_screenshot",
    "capture_web_page",
    "select_images",
    "enable_auto_scroll",
    "enable_speed_mode",
    "disable_auto_capture"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C7 string: $needle"
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

Write-Host "C7 smoke check passed: $apk"
