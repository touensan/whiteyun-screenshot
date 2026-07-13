$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")

foreach ($needle in @(
    "contentRects",
    "matchRects",
    "PreparedFrames",
    "prepareFrames",
    "detectStaticEdge",
    "scoreStaticEdgeRow",
    "STATIC_EDGE_MIN_PX",
    "STATIC_EDGE_MAX_PX",
    "int outputTop = i == 0 ? 0 : matchTop",
    "int outputBottom = i == frames.size() - 1 ? frame.getHeight() : matchBottom",
    "75th-percentile row score",
    "stable >= maxScan",
    "cloneRects",
    "ponytail:"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C25 fixed chrome behavior: $needle"
    }
}

foreach ($needle in @(
    "static Bitmap stitch(List<Bitmap> frames, int[] overlaps)",
    "static StitchPlan analyze(List<Bitmap> frames)",
    "manualRequired",
    "maxOverlaps"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "C25 regressed existing stitcher API: $needle"
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

Write-Host "C25 smoke check passed: $apk"
