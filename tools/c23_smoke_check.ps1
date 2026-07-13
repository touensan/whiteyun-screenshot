$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java")
$accessibility = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollAccessibilityService.java")
$evidence = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollEvidenceStore.java")
$diagnostics = Get-Content -Raw -LiteralPath (Join-Path $root "app/src/main/java/com/whiteyun/screenshot/Diagnostics.java")

foreach ($needle in @(
    "whiteyun-auto-scroll-evidence-v1",
    "auto-scroll-evidence",
    "manifest.json",
    "frame_%03d_raw.png",
    "MAX_SESSIONS",
    "latestSummary",
    "sha256",
    "ponytail:"
)) {
    if (-not $evidence.Contains($needle)) {
        throw "Missing C23 evidence store behavior: $needle"
    }
}

foreach ($needle in @(
    "autoEvidenceSession",
    "AutoScrollEvidenceStore.start",
    "saveRawFrame(raw, attempt)",
    "recordFrameResult",
    "recordAutoWindow",
    "scroll_request",
    "scroll_result",
    "stitch_success",
    "closeAutoEvidence"
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C23 capture evidence hook: $needle"
    }
}

foreach ($needle in @(
    "captureActiveWindow",
    "WindowSnapshot",
    "collectStats",
    "latestEvent",
    "eventTypeToString",
    "scrollableNodeCount"
)) {
    if (-not $accessibility.Contains($needle)) {
        throw "Missing C23 accessibility window evidence: $needle"
    }
}

foreach ($needle in @(
    "appendAutoScrollEvidenceInfo",
    "Auto scroll evidence:",
    "AutoScrollEvidenceStore.latestSummary"
)) {
    if (-not $diagnostics.Contains($needle)) {
        throw "Missing C23 diagnostics evidence summary: $needle"
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

Write-Host "C23 smoke check passed: $apk"
