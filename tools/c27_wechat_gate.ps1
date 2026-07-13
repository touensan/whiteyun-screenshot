param(
    [switch]$PrereqOnly,
    [switch]$AnalyzePrivatePixels,
    [int]$MinAcceptedFrames = 2
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetPackage = "com.tencent.mm"
$appPackage = "com.whiteyun.screenshot"
$accessibilityComponent = "$appPackage/com.whiteyun.screenshot.AutoScrollAccessibilityService"
$accessibilityShortComponent = "$appPackage/.AutoScrollAccessibilityService"

function Require-Text($value, $message) {
    if ([string]::IsNullOrWhiteSpace(($value | Out-String))) {
        throw $message
    }
}

$deviceLines = @(& adb devices | Select-String -Pattern "`tdevice$")
if ($deviceLines.Count -lt 1) {
    throw "No connected Android device"
}
if ($deviceLines.Count -gt 1) {
    throw "More than one Android device is connected; keep only the C27 OnePlus target online"
}

$wechatPath = & adb shell pm path $targetPackage
Require-Text $wechatPath "WeChat package is not installed: $targetPackage"

$appInfo = & adb shell dumpsys package $appPackage
Require-Text $appInfo "WhiteYun Screenshot is not installed"
if (-not (($appInfo | Out-String) -match "versionCode=8")) {
    throw "Unexpected app versionCode; install the latest debug APK first"
}

$enabledServices = (& adb shell settings get secure enabled_accessibility_services) -join ""
if (-not ($enabledServices.Contains($accessibilityComponent) -or $enabledServices.Contains($accessibilityShortComponent))) {
    throw "WhiteYun auto-scroll accessibility service is not enabled"
}

$overlay = (& adb shell appops get $appPackage SYSTEM_ALERT_WINDOW) -join ""
if (-not ($overlay -match "allow|foreground")) {
    throw "WhiteYun overlay permission is not allowed: $overlay"
}

if ($PrereqOnly) {
    Write-Host "C27 prerequisite check passed: device, WeChat, accessibility, overlay, app version"
    exit 0
}

$sessionList = @(& adb shell run-as $appPackage ls -1 files/auto-scroll-evidence 2>$null |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -like "auto_*" } |
    Sort-Object)
if ($sessionList.Count -lt 1) {
    throw "No auto-scroll evidence found. Run one WeChat auto long screenshot first."
}

$session = $sessionList[-1]
$manifestRel = "files/auto-scroll-evidence/$session/manifest.json"
$manifestText = (& adb exec-out run-as $appPackage cat $manifestRel) -join "`n"
Require-Text $manifestText "Latest evidence manifest is empty: $manifestRel"
$manifest = $manifestText | ConvertFrom-Json

if ($manifest.schema -ne "whiteyun-auto-scroll-evidence-v1") {
    throw "Unexpected C27 evidence schema: $($manifest.schema)"
}
if ($manifest.success -ne $true -or $manifest.endReason -ne "preview_opened") {
    throw "Auto long screenshot did not finish with preview_opened. success=$($manifest.success), endReason=$($manifest.endReason)"
}

$events = @($manifest.events)
$windows = @($manifest.windows)
$frames = @($manifest.frames)
$accepted = @($events | Where-Object { $_.type -eq "frame_result" -and $_.accepted -eq $true })
$targetWindows = @($windows | Where-Object { $_.packageName -eq $targetPackage })
$scrollRequests = @($events | Where-Object { $_.type -eq "scroll_request" })
$scrollResults = @($events | Where-Object { $_.type -eq "scroll_result" })
$finishEvents = @($events | Where-Object { $_.type -eq "auto_finish" })
$stitchEvents = @($events | Where-Object { $_.type -eq "stitch_success" })

if ($frames.Count -lt $MinAcceptedFrames -or $accepted.Count -lt $MinAcceptedFrames) {
    throw "Not enough accepted WeChat frames. raw=$($frames.Count), accepted=$($accepted.Count), required=$MinAcceptedFrames"
}
if ($targetWindows.Count -lt 1) {
    throw "Latest evidence does not show target package $targetPackage"
}
if ($scrollRequests.Count -lt 1 -or $scrollResults.Count -lt 1) {
    throw "No scroll request/result events in latest evidence"
}
if ($finishEvents.Count -lt 1) {
    throw "No auto_finish event in latest evidence"
}
if ($stitchEvents.Count -lt 1) {
    throw "No stitch_success event in latest evidence"
}

$summary = [ordered]@{
    session = $session
    success = $manifest.success
    endReason = $manifest.endReason
    rawFrames = $frames.Count
    acceptedFrames = $accepted.Count
    wechatWindows = $targetWindows.Count
    scrollRequests = $scrollRequests.Count
    scrollResults = $scrollResults.Count
    autoFinish = $finishEvents[-1].detail
    previewPath = $stitchEvents[-1].detail
}

Write-Host ($summary | ConvertTo-Json -Depth 4)

if ($AnalyzePrivatePixels) {
    Push-Location $root
    try {
        & python .\tools\c27_private_pixel_gate.py --min-accepted-frames $MinAcceptedFrames --target-package $targetPackage
        if ($LASTEXITCODE -ne 0) {
            throw "C27 private pixel gate failed"
        }
    } finally {
        Pop-Location
    }
}

Write-Host "C27 WeChat structural gate passed"
