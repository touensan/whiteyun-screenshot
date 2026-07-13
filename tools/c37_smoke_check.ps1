$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$selfTest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java")

foreach ($needle in @(
    "EDGE_INDICATOR_MIN_SCRUB_PX",
    "EDGE_INDICATOR_MAX_SCRUB_PX",
    "EDGE_INDICATOR_CONTRAST",
    "scrubRightEdgeIndicators",
    "rightEdgeScrubWidth",
    "output.setPixel(x, y, reference)",
    "Android/WeChat scroll thumbs live in the last few edge pixels"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C37 right-edge scrub behavior: $needle"
    }
}

foreach ($needle in @(
    "countRightEdgeIndicatorPixels",
    "right edge indicator pixels=",
    "canvas.drawRoundRect(WIDTH - 5",
    "Small changing viewport content"
)) {
    if (-not $selfTest.Contains($needle)) {
        throw "Missing C37 debug self-test coverage: $needle"
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

Write-Host "C37 smoke check passed: $apk"
