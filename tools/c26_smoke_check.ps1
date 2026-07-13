$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$activity = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchImagesActivity.java")
$strings = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/res/values/strings.xml")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "CANDIDATE_LIMIT",
    "OVERLAP_CONFIDENCE_DELTA",
    "SEAM_SCORE_THRESHOLD",
    "NO_MOVEMENT_SCORE_THRESHOLD = 4",
    "NO_MOVEMENT_SCORE_THRESHOLD",
    "LOW_TEXTURE_THRESHOLD",
    "BLOCK_CONSENSUS_ACCEPT_THRESHOLD",
    "CONSENSUS_MAX_BLOCK_ROWS_COARSE",
    "CONSENSUS_MAX_BLOCK_ROWS_DETAILED",
    "ANCHOR_MARGIN_DIVISOR",
    "side anchors",
    "scoreBlockConsensus",
    "row caps skipped real content",
    "cached image pyramids/NCC",
    "consensusScores",
    "matchScore",
    "block consensus is the main matcher",
    "NATURAL_OVERLAP_MIN_DIVISOR",
    "MANUAL_OVERLAP_MAX_NUMERATOR = 3",
    "MANUAL_OVERLAP_TARGET_NUMERATOR = 1",
    "NATURAL_OVERLAP_MAX_NUMERATOR = 4",
    "NATURAL_OVERLAP_TARGET_NUMERATOR = 11",
    "NATURAL_OVERLAP_TARGET_TOLERANCE_DIVISOR = 12",
    "scoreTexturedTranslation",
    "TM_SQDIFF-style nomination pass",
    "MIN_NEW_CONTENT_AFTER_OVERLAP",
    "NATURAL_OVERLAP_MAX_NUMERATOR",
    "NATURAL_OVERLAP_TARGET_NUMERATOR",
    "targetOverlapPenalty",
    "MAX_STITCH_PIXELS = 54_000_000",
    "scoreCandidate",
    "sampleX",
    "scoreMaskedOverlap",
    "scoreOverlapEdges",
    "scoreSeamBoundary",
    "scoreOverlapTexture",
    "scoreTexturedTemplate",
    "TEXTURE_TEMPLATE_MIN_WEIGHT",
    "scoreAlignedContent",
    "maxSearchOverlap",
    "fullOverlap",
    "maxOverlap(previous, next) - MIN_NEW_CONTENT_AFTER_OVERLAP",
    "buildPlan(prepared, true, true, null).overlaps",
    "analyze(List<Bitmap> frames, boolean autoMode)",
    "analyze(List<Bitmap> frames, boolean autoMode, int[] scrollDeltas)",
    "expectedOverlaps",
    "matchRects",
    "candidateResult",
    "Candidate[] coarse",
    "Candidate[] refined",
    "samePositionScore",
    "seamMessage(best, confident)",
    "no-movement seams are full-overlap skips",
    "seamScores",
    "noMovement",
    "seamMessages",
    "contentRects",
    "seamScore > SEAM_SCORE_THRESHOLD && !strongEvidence",
    "ponytail:"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C26 stitcher behavior: $needle"
    }
}

foreach ($needle in @(
    "R.string.c26_overlap_row",
    "R.string.c26_seam_label",
    "R.string.c26_manual_confirmed",
    "stitchPlan.seamMessages",
    "stitchPlan.seamScores",
    "stitchPlan.consensusScores",
    "stitchPlan.manualRequired[index] ?",
    "private String seamMessage"
)) {
    if (-not $activity.Contains($needle)) {
        throw "Missing C26 stitch UI behavior: $needle"
    }
}

foreach ($needle in @(
    "c26_overlap_row",
    "c26_seam_label",
    "c26_manual_confirmed"
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C26 string: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 26')) {
    throw "C12 has not included C26 smoke"
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

Write-Host "C26 smoke check passed: $apk"
