$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "Bitmap.CompressFormat.PNG",
    'int quality = 100',
    'String extension = ".png"',
    "yyyyMMdd_HHmmss_SSS",
    "resetScrollTracking();"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C39 lossless longshot output behavior: $needle"
    }
}

if ($service.Contains("Bitmap.CompressFormat.JPEG")) {
    throw "C39 longshot preview cache must not use lossy JPEG"
}

foreach ($needle in @(
    "trustedScrollDelta",
    "rankingExpectedOverlap",
    "hasGuidedVisualEvidence",
    "estimated deltas do not get confidence bonuses",
    "accessibility scroll deltas are event hints",
    "return maxScan;",
    "scoreTexturedTranslation",
    "Untrusted deltas may nominate a peak",
    "VISUAL_RANK_PRIOR_MAX = 10",
    "CANDIDATE_LIMIT = 8",
    "AUTO_MIN_OVERLAP_DIVISOR = 6",
    "boolean expectedCanConfirm",
    "expectedCanConfirm",
    "VIEWPORT_BOTTOM_GUARD_PX = 96",
    "protectViewportBottom",
    "screenshot viewports often cut through a chat text row"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C39 guided-overlap fallback behavior: $needle"
    }
}

foreach ($removed in @(
    "AUTO_UNTRUSTED_OVERLAP_MAX_PX",
    "AUTO_UNTRUSTED_OVERLAP_TARGET_PX",
    "maxSearchOverlap = Math.min(maxSearchOverlap, preserveCap)",
    "preferContentPreservingOverlap",
    "OVERLAP_CONTENT_PRESERVE_TIE_DELTA"
)) {
    if ($stitcher.Contains($removed)) {
        throw "C39 check failed: obsolete small-overlap cap remains: $removed"
    }
}

if (-not ($service.Contains("LongScreenshotStitcher.stitch(") `
        -and $service.Contains("stitchPlan.overlaps.clone(),") `
        -and $service.Contains("true,") `
        -and $service.Contains("STITCH_PROGRESS_DRAW_START"))) {
    throw "CaptureService must enable viewport bottom guard for long screenshots"
}

foreach ($needle in @(
    "RegionPreviewView",
    "BitmapRegionDecoder.newInstance",
    "previewScroll.getScrollY()",
    "decodeRegion(source, options)",
    "tileExecutor",
    "requestTile",
    "decodeTile",
    "PREVIEW_TILE_PREFETCH_SOURCE_PX",
    "PREVIEW_TILE_MAX_DECODED_BYTES",
    "PREVIEW_FALLBACK_MAX_DECODED_BYTES",
    "previewFallbackSampleSize",
    "drawFallback",
    "sourceWidth / (sample * 2) >= getWidth()",
    "setChromeVisible(!topChromeVisible || !bottomChromeVisible)"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C39 crisp long-preview behavior: $needle"
    }
}

if ($preview.Contains("bounds.outHeight / options.inSampleSize > 12000")) {
    throw "C39 preview must not downsample the whole long image by height"
}

if ($preview.Contains("private void ensureTile")) {
    throw "C39 preview tile decode must not run synchronously from onDraw"
}

if (-not $c12.Contains('$smokeStages += 39')) {
    throw "C12 has not included C39 smoke"
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

Write-Host "C39 smoke check passed: $apk"
