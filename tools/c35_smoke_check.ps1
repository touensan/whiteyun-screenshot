param(
    [string]$Serial = "",
    [switch]$BuildOnly,
    [switch]$SkipInstall,
    [ValidateRange(10, 600)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$manifestPath = Join-Path $root "app/src/debug/AndroidManifest.xml"
$activityPath = Join-Path $root "app/src/debug/java/com/whiteyun/screenshot/StitchSelfTestActivity.java"
$servicePath = Join-Path $root "app/src/main/java/com/whiteyun/screenshot/CaptureService.java"
$accessibilityPath = Join-Path $root "app/src/main/java/com/whiteyun/screenshot/AutoScrollAccessibilityService.java"
$mainPath = Join-Path $root "app/src/main/java/com/whiteyun/screenshot/MainActivity.java"
$stringsPath = Join-Path $root "app/src/main/res/values/strings.xml"
$chatFixturePath = Join-Path $root "tools/fixtures/wechat_like_long_chat.html"

foreach ($path in @($manifestPath, $activityPath, $servicePath, $accessibilityPath, $mainPath, $stringsPath, $chatFixturePath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing C35 debug self-test file: $path"
    }
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath
$activity = Get-Content -Raw -LiteralPath $activityPath
$service = Get-Content -Raw -LiteralPath $servicePath
$accessibility = Get-Content -Raw -LiteralPath $accessibilityPath
$main = Get-Content -Raw -LiteralPath $mainPath
$strings = Get-Content -Raw -LiteralPath $stringsPath
foreach ($needle in @(
    '.StitchSelfTestActivity',
    'android:exported="true"'
)) {
    if (-not $manifest.Contains($needle)) {
        throw "Missing C35 debug manifest behavior: $needle"
    }
}
foreach ($needle in @(
    'MANUAL_SCROLL_SETTLE_MS = 450',
    'int hideDelay = settledManualFrame',
    'manualScrollSettledRunnable',
    'requestManualSample(0)',
    'cancelPendingManualFrameRequest',
    'frameScrollDeltasArray()',
    'LongScreenshotStitcher.analyze(manualFrames, autoMode, scrollDeltas)',
    'Button cancel = overlayButton(R.string.overlay_cancel',
    'LongScreenshotStitcher.isNearDuplicate('
)) {
    if (-not $service.Contains($needle)) {
        throw "Missing C35 manual capture behavior: $needle"
    }
}
foreach ($needle in @(
    'TYPE_VIEW_SCROLLED',
    'setScrollObserver',
    'UNDEFINED_SCROLL_DELTA = -1',
    'scrollSourceKey(event)',
    'resolveVerticalScrollDelta'
)) {
    if (-not $accessibility.Contains($needle)) {
        throw "Missing C35 accessibility behavior: $needle"
    }
}
if (-not $main.Contains('private void startManualCaptureFlow()')) {
    throw 'Manual mode does not enforce the accessibility preflight'
}
foreach ($needle in @(
    'c35_manual_scroll_intro',
    'c35_status_auto_sampled',
    'c35_status_waiting_for_settle'
)) {
    if (-not $strings.Contains($needle)) {
        throw "Missing C35 user-facing copy: $needle"
    }
}
foreach ($needle in @(
    'LongScreenshotStitcher.analyze(frames, true, SCROLL_DELTA_HINTS)',
    'LongScreenshotStitcher.stitch(frames, plan.overlaps.clone())',
    'PASS runId=',
    'FAIL runId=',
    'fixed top=',
    'output height expected=',
    'two-frame overlap expected=',
    'Small changing viewport content'
)) {
    if (-not $activity.Contains($needle)) {
        throw "Missing C35 device self-test behavior: $needle"
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
    throw "Missing debug APK: $apk"
}
if ($BuildOnly) {
    Write-Host "C35 build smoke passed: $apk"
    exit 0
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    throw "adb is not available"
}
$devices = @(& $adb.Source devices | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
})
if ($Serial) {
    if ($devices -notcontains $Serial) {
        throw "Requested device is not online: $Serial"
    }
} elseif ($devices.Count -eq 1) {
    $Serial = $devices[0]
} elseif ($devices.Count -eq 0) {
    throw "No connected Android device or emulator"
} else {
    throw "More than one device is online; pass -Serial <serial>"
}

$adbPrefix = @('-s', $Serial)
if (-not $SkipInstall) {
    $installOutput = @(& $adb.Source @adbPrefix install -r -t $apk 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not (($installOutput -join "`n").Contains("Success"))) {
        throw (("Debug APK install failed on {0}. Use a clean emulator, or explicitly remove the differently signed release build; this script will not erase app data.`n{1}" -f
                $Serial, ($installOutput -join "`n")))
    }
}

$runId = [Guid]::NewGuid().ToString("N")
$component = "com.whiteyun.screenshot/com.whiteyun.screenshot.StitchSelfTestActivity"
$startOutput = @(& $adb.Source @adbPrefix shell am start -W -S -n $component --es run_id $runId 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Cannot start C35 self-test activity:`n$($startOutput -join "`n")"
}

$tag = "WhiteYunStitchSelfTest"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $logs = @(& $adb.Source @adbPrefix logcat -d -v brief -s "${tag}:I" "*:S" 2>&1)
    $joined = $logs -join "`n"
    if ($joined.Contains("PASS runId=$runId")) {
        $line = ($logs | Select-String -SimpleMatch "PASS runId=$runId" | Select-Object -Last 1).Line
        Write-Host "C35 device smoke passed on $Serial"
        Write-Host $line
        exit 0
    }
    if ($joined.Contains("FAIL runId=$runId")) {
        $failure = ($logs | Select-String -SimpleMatch "FAIL runId=$runId" | Select-Object -Last 1).Line
        throw "C35 device self-test failed on $Serial`: $failure"
    }
    Start-Sleep -Milliseconds 500
}

throw "C35 device self-test timed out after $TimeoutSeconds seconds on $Serial (runId=$runId)"
