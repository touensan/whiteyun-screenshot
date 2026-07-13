$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "spooled_below_streaming_threshold",
    "StreamingLongScreenshotStitcher",
    "Bitmap.CompressFormat.PNG",
    "framesForStitch(boolean autoMode)",
    "stitchScrollDeltasArray(boolean autoMode, int frameCount)",
    "reversed_for_backward_scroll",
    "Math.abs(rawDelta)"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C38 auto stitch performance behavior: $needle"
    }
}

foreach ($needle in @(
    "estimateScrollDelta",
    "scoreFastOverlap",
    "cheap translation estimate",
    "for (int overlap = guidedFrom; overlap <= guidedTo; overlap += 4)",
    "estimated deltas do not get confidence bonuses"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C38 fast overlap behavior: $needle"
    }
}

foreach ($needle in @(
    "mimeTypeForFile",
    'intent.setType("image/*")',
    'intent.setDataAndType(uri, "image/*")'
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C38 preview persistence behavior: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 38')) {
    throw "C12 has not included C38 smoke"
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

Write-Host "C38 smoke check passed: $apk"
