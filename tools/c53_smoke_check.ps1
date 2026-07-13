$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$layout = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/layout/activity_main.xml")
$capture = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$store = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueStore.java")
$queueActivity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueActivity.java")
$preferences = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AppPreferences.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")

$select = $layout.IndexOf('android:id="@+id/card_select_images"')
$queue = $layout.IndexOf('android:id="@+id/queue_entry"')
$draft = $layout.IndexOf('android:id="@+id/draft_section"')
if ($select -lt 0 -or $queue -lt 0 -or $draft -lt 0 -or -not ($select -lt $queue -and $queue -lt $draft)) {
    throw "Queue entry is not between image selection and draft preview"
}

foreach ($needle in @(
    "AppPreferences.isCaptureStatusBarEnabled(this)",
    "c52_stitch_queue_preparing",
    "EXTRA_EXPECT_NEW_JOB"
)) {
    if (-not $capture.Contains($needle)) {
        throw "Missing C53 capture behavior: $needle"
    }
}
if (-not $store.Contains("Collections.reverse(jobs)")) {
    throw "Queue display order is not newest-first"
}
foreach ($needle in @(
    "pendingEnqueueStartedAt",
    "createPendingRow",
    "EXTRA_EXPECT_NEW_JOB"
)) {
    if (-not $queueActivity.Contains($needle)) {
        throw "Missing C53 immediate queue behavior: $needle"
    }
}
foreach ($needle in @(
    "isCaptureStatusBarEnabled",
    "setCaptureStatusBarEnabled"
)) {
    if (-not $preferences.Contains($needle)) {
        throw "Missing C53 status-bar setting: $needle"
    }
}
foreach ($needle in @(
    "c52_status_bar_title",
    "c52_status_bar_body",
    "c35_manual_scroll_intro"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C53 user guidance string: $needle"
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

Write-Host "C53 smoke check passed"
