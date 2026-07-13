$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$history = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/HistoryActivity.java")
$main = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java")
$layout = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/layout/activity_main.xml")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

foreach ($needle in @(
    "MediaStore.Images.Media.RELATIVE_PATH",
    "WhiteYunScreenshot_%",
    "WhiteYunLongShot_%",
    "WhiteYunWebPage_%",
    "loadThumbnail",
    "Intent.ACTION_VIEW",
    "Intent.ACTION_SEND",
    "PreviewActivity.EXTRA_IMAGE_URI",
    "PreviewActivity.EXTRA_RESULT_KIND",
    "edit(item)",
    "resolver.delete",
    "applyActionBarOffset",
    "ponytail:"
)) {
    if (-not $history.Contains($needle)) {
        throw "Missing C13 history behavior: $needle"
    }
}

foreach ($needle in @(
    "openHistory",
    "showHistoryTab",
    "historyScroll",
    "historyList",
    "loadHistory",
    "PreviewActivity.EXTRA_IMAGE_URI",
    "switchTabContent",
    "animateBottomNavItem",
    "ValueAnimator.ofArgb",
    "tabAnimationToken",
    "getTabAnimationBody",
    "setInactiveTabViewsGone",
    "navHistory",
    "navSettings",
    "openSettingsPage"
)) {
    if (-not $main.Contains($needle)) {
        throw "Missing C13 home wiring: $needle"
    }
}

foreach ($needle in @(
    "@+id/tab_content_host",
    "@+id/bottom_nav",
    "@+id/nav_home",
    "@+id/nav_history",
    "@+id/nav_settings",
    "@+id/history_scroll",
    "@+id/history_list",
    "@+id/history_entry"
)) {
    if (-not $layout.Contains($needle)) {
        throw "Missing C13 bottom nav in home layout: $needle"
    }
}

if ($main.Contains("startActivity(new Intent(this, HistoryActivity.class))")) {
    throw "C13 bottom history tab should render in MainActivity, not launch HistoryActivity"
}

if (-not $manifest.Contains("android:name=`".HistoryActivity`"")) {
    throw "Missing C13 HistoryActivity manifest registration"
}

foreach ($needle in @(
    "c13_history_title",
    "c13_history_empty",
    "c13_history_count",
    "c13_history_edit",
    "c13_history_delete",
    "c13_kind_webpage",
    "nav_home",
    "nav_history",
    "nav_settings"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C13 string: $needle"
    }
}

foreach ($forbidden in @(
    "READ_MEDIA_IMAGES",
    "READ_EXTERNAL_STORAGE"
)) {
    if ($manifest.Contains($forbidden)) {
        throw "C13 should not add broad gallery permission: $forbidden"
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

Write-Host "C13 smoke check passed: $apk"
