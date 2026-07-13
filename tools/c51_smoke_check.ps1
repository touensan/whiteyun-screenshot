$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$capture = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$store = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueStore.java")
$queue = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueService.java")
$progress = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchProgressActivity.java")
$queueActivity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueActivity.java")
$preferences = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AppPreferences.java")
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/AndroidManifest.xml")
$selfTest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java")

foreach ($needle in @(
    "enqueueStitchJob(autoMode)",
    "StitchQueueStore.enqueueAuto",
    "StitchQueueStore.enqueueManual",
    "StitchQueueService.start(this)",
    "AUTO_MEMORY_FRAME_LIMIT = 1"
)) {
    if (-not $capture.Contains($needle)) {
        throw "Missing C51 capture-to-queue behavior: $needle"
    }
}

foreach ($needle in @(
    "StitchQueueActivity",
    "ACTION_STITCH_CANCEL",
    "ACTION_STITCH_RETRY",
    "ProgressBar"
)) {
    if (-not $queueActivity.Contains($needle)) {
        throw "Missing C52 visible queue behavior: $needle"
    }
}
foreach ($needle in @(
    "isStitchNotificationsEnabled",
    "setStitchNotificationsEnabled"
)) {
    if (-not $preferences.Contains($needle)) {
        throw "Missing C52 notification setting: $needle"
    }
}

foreach ($needle in @(
    "STATE_QUEUED",
    "STATE_RUNNING",
    "enqueueAuto",
    "enqueueManual",
    "takeNext",
    "job.properties",
    "renameTo",
    "removeAutoFrames"
)) {
    if (-not $store.Contains($needle)) {
        throw "Missing C51 durable queue-store behavior: $needle"
    }
}

foreach ($needle in @(
    "START_REDELIVER_INTENT",
    "StreamingLongScreenshotStitcher.stitch",
    "DraftStore.promotePreview",
    "StitchQueueStore.takeNext",
    "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
    "StitchCanceledException"
)) {
    if (-not $queue.Contains($needle)) {
        throw "Missing C51 queue-worker behavior: $needle"
    }
}

if (-not $progress.Contains("StitchQueueService.class")) {
    throw "C51 progress screen is not connected to the queue service"
}
if (-not $manifest.Contains('android:name=".StitchQueueService"') -or -not $manifest.Contains('android:foregroundServiceType="dataSync"')) {
    throw "C51 data-sync foreground service is not declared"
}
if (-not $manifest.Contains('android:name=".StitchQueueActivity"')) {
    throw "Missing C52 queue activity declaration"
}
foreach ($needle in @(
    "ambiguous streaming seam must keep its low-confidence marker",
    "ambiguous streaming seam must publish a recoverable output",
    "path-only retry must stay inside the stable trajectory window",
    "stable session trajectory may confirm"
)) {
    if (-not $selfTest.Contains($needle)) {
        throw "Missing C51 runnable stitch regression: $needle"
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

Write-Host "C51 smoke check passed"
