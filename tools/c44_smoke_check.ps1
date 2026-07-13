$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$streaming = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StreamingLongScreenshotStitcher.java")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$queue = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchQueueService.java")
$accessibility = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollAccessibilityService.java")
$selfTest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "CANDIDATE_LIMIT = 8",
    "VISUAL_RANK_PRIOR_MAX = 10",
    "scoreTexturedTranslation",
    "TM_SQDIFF-style nomination pass",
    "trustedExpectedOverlap",
    "Untrusted deltas may nominate a peak",
    "candidate.score < second.score",
    "boolean decisiveVisualLead = second != null",
    "BLOCK_CONSENSUS_STRONG",
    "boolean untrustedSearch = !expectedCanConfirm",
    "allowExactExpectedOverlap",
    "guidedPeak - 3",
    "one dense local pass",
    "neighboringExpectedOverlap",
    "supportsTrajectoryExpectedOverlap",
    "supportsSessionTrajectoryExpectedOverlap",
    "neighbor-path-confirmed",
    "TRAJECTORY_MIN_CONSENSUS = 94",
    "TRAJECTORY_LOW_TEXTURE_MIN_CONSENSUS = 90",
    "detectStaticInsets(List<Bitmap> frames)",
    "prepareFrames(frames, staticTop, staticBottom)"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C44 visual-coordinate behavior: $needle"
    }
}

foreach ($needle in @(
    "int[] staticInsets = detectStaticInsets(frameFiles)",
    "staticInsets[0]",
    "staticInsets[1]",
    "pairPlan.manualRequired[1]",
    "pairPlan.seamMessages[1]",
    "conservativeInset",
    "smallest distant-pair inset",
    "recentExpectedOverlap",
    "analyzePairWithPathExpectedOverlap",
    "path-first seam=",
    "supportsSessionTrajectoryExpectedOverlap"
)) {
    if (-not $streaming.Contains($needle)) {
        throw "Missing C44 streaming safety behavior: $needle"
    }
}

foreach ($needle in @(
    "enqueueStitchJob(autoMode)",
    "StitchQueueStore.enqueueAuto",
    "StitchQueueStore.enqueueManual",
    "StitchQueueService.start(this)",
    "AUTO_MEMORY_FRAME_LIMIT = 1"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C44 queued-stitch capture behavior: $needle"
    }
}

foreach ($needle in @(
    "STATE_QUEUED",
    "START_REDELIVER_INTENT",
    "StreamingLongScreenshotStitcher.stitch",
    "DraftStore.promotePreview",
    "StitchQueueStore.takeNext",
    "FOREGROUND_SERVICE_TYPE_DATA_SYNC"
)) {
    if (-not $queue.Contains($needle)) {
        throw "Missing C44 durable queue behavior: $needle"
    }
}

foreach ($needle in @(
    "SWIPE_END_FRACTION = 0.62f",
    "FAST_SWIPE_DURATION_MS = 280",
    "the 16%-screen gesture trades about 1.5x more frames"
)) {
    if (-not $accessibility.Contains($needle)) {
        throw "Missing C44 high-overlap capture behavior: $needle"
    }
}

foreach ($needle in @(
    "AUTO_FAST_SCROLL_SETTLE_MS = 320",
    "AUTO_FAST_FRAME_PREPARE_MS = 180",
    "requestScroll(autoScrollReverseDirection, speedMode"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C44 speed-mode cadence: $needle"
    }
}

foreach ($needle in @(
    "runRepeatedTranscriptCheck",
    "repeated transcript overlap expected=",
    "streaming pixel mismatch",
    "very-long pixel mismatch",
    "9999",
    "118, 530",
    "recent overlap median",
    "below-low-texture recent-path consensus must fail closed",
    "two-sided early seam trajectory",
    "low-texture health card trajectory",
    "unstable neighboring trajectory must fail closed",
    "stable session trajectory may confirm",
    "ambiguous streaming seam must keep its low-confidence marker",
    "ambiguous streaming seam must publish a recoverable output",
    "1.6 percent rotating banner must remain a duplicate",
    "3.1 percent content change must not be a duplicate"
)) {
    if (-not $selfTest.Contains($needle)) {
        throw "Missing C44 deterministic regression: $needle"
    }
}

if (-not $stitcher.Contains("NEAR_DUPLICATE_CHANGED_PERCENT = 2")) {
    throw "Missing C44 dynamic-banner duplicate tolerance"
}

foreach ($removed in @(
    "preferContentPreservingOverlap",
    "OVERLAP_CONTENT_PRESERVE_TIE_DELTA"
)) {
    if ($stitcher.Contains($removed)) {
        throw "C44 obsolete greedy overlap preference remains: $removed"
    }
}

if (-not $c12.Contains('$smokeStages += 44')) {
    throw "C12 has not included C44 smoke"
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

Write-Host "C44 smoke check passed: $apk"
