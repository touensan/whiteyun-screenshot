param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root 'app\src\main\res'
$expected = @(
    'values', 'values-zh-rCN', 'values-zh-rTW', 'values-es', 'values-fr',
    'values-de', 'values-pt-rBR', 'values-ru', 'values-ja', 'values-ko',
    'values-ar', 'values-hi', 'values-b+id', 'values-it', 'values-tr',
    'values-vi', 'values-th', 'values-pl', 'values-nl'
)
$expectedTags = @(
    'en', 'zh-CN', 'zh-TW', 'es', 'fr', 'de', 'pt-BR', 'ru', 'ja', 'ko',
    'ar', 'hi', 'id', 'it', 'tr', 'vi', 'th', 'pl', 'nl'
)

function Read-Strings([string]$directory) {
    $path = Join-Path (Join-Path $res $directory) 'strings.xml'
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing locale file: $directory"
    }
    [xml]$xml = Get-Content -LiteralPath $path -Raw
    $map = [ordered]@{}
    foreach ($node in $xml.resources.string) {
        $map[$node.name] = [string]$node.'#text'
    }
    return $map
}

function Format-Tokens([string]$value) {
    return @([regex]::Matches($value, '%(?:\d+\$[sd]|%)') | ForEach-Object Value | Sort-Object)
}

$base = Read-Strings 'values'
if ($base.Count -lt 400) {
    throw "Default locale unexpectedly small: $($base.Count) strings"
}
if (($base.Values -join "`n") -match '[\p{IsCJKUnifiedIdeographs}]') {
    throw 'Default English resources still contain Chinese characters'
}

foreach ($directory in $expected) {
    $localized = Read-Strings $directory
    if ($localized.Count -ne $base.Count) {
        throw "$directory has $($localized.Count) strings; expected $($base.Count)"
    }
    foreach ($name in $base.Keys) {
        if (-not $localized.Contains($name)) {
            throw "$directory is missing $name"
        }
        $sourceTokens = @(Format-Tokens $base[$name])
        $localizedTokens = @(Format-Tokens $localized[$name])
        if (($sourceTokens -join ',') -ne ($localizedTokens -join ',')) {
            throw "$directory/$name format tokens differ: $($localizedTokens -join ',')"
        }
    }
    if (($directory -notin @('values', 'values-zh-rCN', 'values-zh-rTW')) -and ($localized['app_name'] -match '[\p{IsCJKUnifiedIdeographs}]')) {
        throw "$directory app_name still contains Chinese characters"
    }
}

$localeSource = Get-Content -LiteralPath (Join-Path $root 'app\src\main\java\com\whiteyun\screenshot\AppLocale.java') -Raw
foreach ($tag in $expectedTags) {
    if ($localeSource -notmatch ('"' + [regex]::Escape($tag) + '"')) {
        throw "AppLocale is missing $tag"
    }
}

$manifest = Get-Content -LiteralPath (Join-Path $root 'app\src\main\AndroidManifest.xml') -Raw
if ($manifest -notmatch 'android:supportsRtl="true"') {
    throw 'Manifest must enable RTL support'
}
if (($manifest -notmatch 'android:icon="@drawable/ic_launcher_whiteyun"') -or ($manifest -notmatch 'android:roundIcon="@drawable/ic_launcher_whiteyun"')) {
    throw 'Manifest must keep the original WhiteYun launcher artwork'
}
if (([regex]::Matches($manifest, '<activity-alias\b')).Count -ne ($expectedTags.Count + 1)) {
    throw 'Manifest must provide one default and one launcher alias per language'
}
$appLocaleSource = Get-Content -LiteralPath (Join-Path $root 'app\src\main\java\com\whiteyun\screenshot\AppLocale.java') -Raw
if ($appLocaleSource -notmatch 'syncLauncherAlias') {
    throw 'AppLocale must synchronize the launcher label alias'
}
$menu = Get-Content -LiteralPath (Join-Path $res 'menu\menu_main.xml') -Raw
if ($menu -notmatch 'action_language' -or $menu -notmatch 'ic_translate') {
    throw 'Home menu must expose the language switcher'
}
foreach ($iconPath in @(
    'drawable\ic_translate.xml',
    'mipmap\ic_launcher.xml',
    'mipmap\ic_launcher_round.xml')) {
    if (-not (Test-Path -LiteralPath (Join-Path $res $iconPath))) {
        throw "Missing launcher/language icon: $iconPath"
    }
}
$properties = Get-Content -LiteralPath (Join-Path $res 'resources.properties') -Raw
if ($properties -notmatch 'unqualifiedResLocale=en') {
    throw 'Default resource locale must be English'
}

if (-not $SkipBuild) {
    & (Join-Path $root 'gradlew.bat') :app:assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Debug build failed with exit code $LASTEXITCODE"
    }
    $generatedPath = Join-Path $root 'app\build\generated\res\localeConfig\debug\xml\_generated_res_locale_config.xml'
    [xml]$generated = Get-Content -LiteralPath $generatedPath -Raw
    $namespace = 'http://schemas.android.com/apk/res/android'
    $generatedTags = @($generated.'locale-config'.locale | ForEach-Object { $_.GetAttribute('name', $namespace) })
    if ((@($generatedTags | Sort-Object) -join ',') -ne (@($expectedTags | Sort-Object) -join ',')) {
        throw "Generated LocaleConfig differs: $($generatedTags -join ',')"
    }
}

Write-Host "C62 localization check passed: $($expected.Count) languages, $($base.Count) strings each."
