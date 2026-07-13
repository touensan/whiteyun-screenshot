$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$preview = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/PreviewActivity.java")
$capture = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$drafts = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/DraftStore.java")
$build = Get-Content -Raw -LiteralPath (Join-Path $root "app/build.gradle")
$selfTest = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java")
$betaManifest = Join-Path $root "app/src/beta/AndroidManifest.xml"
$betaActivity = Join-Path $root "app/src/beta/java/com/whiteyun/screenshot/BetaDiagnosticsActivity.java"
$c12 = Get-Content -Raw -LiteralPath (Join-Path $root "tools/c12_release_check.ps1")

foreach ($needle in @(
    "visibleDisplayRect",
    "previewSourceWindow",
    "boundedPreviewTileSource",
    "PREVIEW_TILE_MAX_DECODED_BYTES",
    "PREVIEW_FALLBACK_MAX_DECODED_BYTES",
    "previewScroll.getScrollY()",
    "tileExecutor",
    "fallbackExecutor",
    "previewFallbackSampleSize",
    "drawFallback",
    "requestTile",
    "decodeTile",
    "canvas.drawBitmap"
)) {
    if (-not $preview.Contains($needle)) {
        throw "Missing C48 bounded preview behavior: $needle"
    }
}

foreach ($needle in @(
    "DraftStore.promotePreview(this, preview)",
    "recoverablePreview"
)) {
    if (-not $capture.Contains($needle)) {
        throw "Missing C48 recoverable result behavior: $needle"
    }
}

foreach ($needle in @(
    "promotePreview",
    "promoteLatestPreviewCache",
    "isDraftFile"
)) {
    if (-not $drafts.Contains($needle)) {
        throw "Missing C48 draft recovery behavior: $needle"
    }
}

foreach ($needle in @(
    "beta {",
    "debuggable false",
    "minifyEnabled true",
    "shrinkResources true",
    "beta.java.srcDir 'src/debug/java'"
)) {
    if (-not $build.Contains($needle)) {
        throw "Missing C48 permanent Beta behavior: $needle"
    }
}

foreach ($file in @($betaManifest, $betaActivity)) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "Missing C48 Beta diagnostics file: $file"
    }
}

foreach ($needle in @(
    "long preview must decode only the first viewport",
    "long preview viewport must follow scroll position",
    "Canvas allocation limit"
)) {
    if (-not $selfTest.Contains($needle)) {
        throw "Missing C48 runnable regression: $needle"
    }
}

if (-not $c12.Contains('$smokeStages += 48')) {
    throw "C12 has not included C48 smoke"
}

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleBeta
    if ($LASTEXITCODE -ne 0) {
        throw "C48 build failed"
    }
} finally {
    Pop-Location
}

$betaApk = Join-Path $root "app/build/outputs/apk/beta/app-beta.apk"
if (-not (Test-Path -LiteralPath $betaApk)) {
    throw "Missing Beta APK: $betaApk"
}

Write-Host "C48 smoke check passed: $betaApk"
