$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$build = Get-Content -Raw -LiteralPath (Join-Path $root "app/build.gradle")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$streaming = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StreamingLongScreenshotStitcher.java")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$selfTest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java")
$c12Path = Join-Path $root "tools/c12_release_check.ps1"
$c12 = if (Test-Path -LiteralPath $c12Path) {
    Get-Content -Raw -LiteralPath $c12Path
} else {
    ""
}
$pngjLicense = Join-Path $root "app/src/main/assets/third_party_licenses/pngj-2.1.0.txt"

if (-not $build.Contains("implementation 'ar.com.hjg:pngj:2.1.0'")) {
    throw "C45 PNGJ dependency is missing"
}
if (-not (Test-Path -LiteralPath $pngjLicense)) {
    throw "C45 PNGJ Apache-2.0 notice is missing"
}

foreach ($needle in @(
    "AUTO_MEMORY_FRAME_LIMIT = 1",
    "AUTO_STORAGE_RESERVE_BYTES",
    "writeAutoFrameFile",
    "autoFrameFilesForStitch",
    "StreamingLongScreenshotStitcher.stitch",
    "spooled_count=",
    "clearManualFrames"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C45 disk-spooled capture behavior: $needle"
    }
}

if ($service.Contains("MAX_AUTO_FRAMES")) {
    throw "C45 automatic capture must not have a fixed frame count"
}
if ($service.Contains("MAX_MANUAL_FRAMES")) {
    throw "Manual capture must not have a fixed frame count"
}
foreach ($needle in @(
    "autoFrameFiles.add(writeAutoFrameFile(bitmap, autoFrameFiles.size()))",
    "if (manualFrames.size() > AUTO_MEMORY_FRAME_LIMIT)",
    "autoMode ? MODE_AUTO : MODE_MANUAL"
)) {
    if (-not $service.Contains($needle)) {
        throw "Manual capture is not using the disk-spooled long-shot path: $needle"
    }
}

foreach ($needle in @(
    "new ImageInfo(width, segments.totalHeight, 8, true)",
    "new PngWriter",
    "PNG_COMPRESSION_LEVEL = 3",
    "FRAME_PNG_COMPRESSION_LEVEL = 1",
    "writeFramePng",
    "FilterType.FILTER_ADAPTIVE_FAST",
    "writer.writeRow(line)",
    "writer.end()",
    '".part"',
    "BitmapRegionDecoder.newInstance",
    "writeCroppedPng",
    "CROP_BLOCK_ROWS = 256",
    "sourceTops",
    "sourceBottoms",
    "viewportCropTop"
)) {
    if (-not $streaming.Contains($needle)) {
        throw "Missing C45 streaming PNG behavior: $needle"
    }
}

foreach ($needle in @(
    "writeCroppedFileToFile",
    "StreamingLongScreenshotStitcher.crop",
    'File.createTempFile("cropped-", ".png", getCacheDir())'
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C45 streaming crop/save behavior: $needle"
    }
}

foreach ($needle in @(
    "runStreamingCheck",
    "runVeryLongStreamingCheck",
    "frameCount > 20 && expectedHeight > 40000",
    "StreamingLongScreenshotStitcher.stitch",
    "StreamingLongScreenshotStitcher.crop",
    "StreamingLongScreenshotStitcher.writeFramePng",
    "streaming output height expected="
)) {
    if (-not $selfTest.Contains($needle)) {
        throw "Missing C45 runnable regression: $needle"
    }
}

if ((Test-Path -LiteralPath $c12Path) -and -not $c12.Contains('$smokeStages += 45')) {
    throw "C12 has not included C45 smoke"
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

Write-Host "C45 smoke check passed: $apk"
