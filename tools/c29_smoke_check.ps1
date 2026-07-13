$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$stitcher = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/LongScreenshotStitcher.java")
$capture = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$debugStore = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/StitchDebugStore.java")
$diagnostics = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/Diagnostics.java")
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "BLOCK_CONSENSUS_ACCEPT_THRESHOLD",
    "CONSENSUS_BLOCK_WIDTH",
    "scoreBlockConsensus",
    "scoreConsensusBlock",
    "likelihood",
    "consensusScores",
    "BLOCK_CONSENSUS_TARGET"
)) {
    if (-not $stitcher.Contains($needle)) {
        throw "Missing C29 consensus stitch behavior: $needle"
    }
}

foreach ($needle in @(
    "StitchDebugStore.write",
    "LongScreenshotStitcher.analyze",
    "stitchPlan.overlaps.clone()",
    "stitch_debug"
)) {
    if (-not $capture.Contains($needle)) {
        throw "Missing C29 capture debug hook: $needle"
    }
}

foreach ($needle in @(
    "whiteyun-stitch-debug-v1",
    "stitch-debug",
    "preview.png",
    "consensusScore",
    "MAX_SESSIONS"
)) {
    if (-not $debugStore.Contains($needle)) {
        throw "Missing C29 stitch debug store behavior: $needle"
    }
}

if (-not $diagnostics.Contains("Stitch debug:")) {
    throw "Diagnostics does not expose latest stitch debug info"
}

foreach ($script in @(
    "tools/c29_stitch_debug.py",
    "tools/c29_export_latest_stitch_debug.py"
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $script))) {
        throw "Missing C29 developer tool: $script"
    }
}

foreach ($needle in @(
    "--manifest",
    "prepare_content_rects",
    "detect_static_edge",
    "contentRects",
    "previousContentRect",
    "nextContentRect"
)) {
    $scriptText = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c29_stitch_debug.py")
    if (-not $scriptText.Contains($needle)) {
        throw "Missing C29 manifest/content-rect replay behavior: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 29')) {
    throw "C12 has not included C29 smoke"
}

$sampleRoot = "D:\wechat\xwechat_files\wxid_n5guyg7w2cft22_dd07\temp\RWTemp\2026-07\5cae55952541febed130d98ad158c5db"
$sampleImages = @(
    (Join-Path $sampleRoot "33d9296aa0512e18ffd4dc0400483270.jpg"),
    (Join-Path $sampleRoot "4722d24d43517a2fa8e08091d19bd7f6.jpg"),
    (Join-Path $sampleRoot "c4f89d7c0c201410b02b15af348800e8.jpg"),
    (Join-Path $sampleRoot "56f066e9079e23264ace9eae88b0ee76.jpg"),
    (Join-Path $sampleRoot "b9daf78a82409541ed04c2333f6ac403.jpg")
)

if (($sampleImages | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -eq 0) {
    $workspaceRoot = Resolve-Path (Join-Path $root "..")
    $out = Join-Path $workspaceRoot "tmp/c29-smoke-merged.png"
    $report = Join-Path $workspaceRoot "tmp/c29-smoke-report.json"
    & python (Join-Path $root "tools/c29_stitch_debug.py") `
        --order given `
        --crop-top 120 `
        --crop-bottom 24 `
        --output $out `
        --report $report `
        @sampleImages
    if ($LASTEXITCODE -ne 0) {
        throw "C29 offline stitch debug tool failed"
    }
    $json = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
    if ($json.matches.Count -ne 4 -or -not (Test-Path -LiteralPath $out)) {
        throw "C29 offline stitch report is incomplete"
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

Write-Host "C29 smoke check passed: $apk"
