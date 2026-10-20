param(
    [string]$GradlePath = "",
    [string]$ApkSignerPath = ""
)

# 陆战棋正式发布包：构建 → 校验签名/版本 → 产出商城上传用的 *-store.apk 与 .sha256
# 用法：powershell -ExecutionPolicy Bypass -File scripts\build_store_release.ps1
# 签名材料放在 signing\（已被 .gitignore 忽略），必须始终使用同一份密钥。

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$signingDir = Join-Path $projectRoot "signing"
$propertiesPath = Join-Path $signingDir "k9.release.signing.properties"
$keystorePath = Join-Path $signingDir "debug.keystore"
$expectedPackage = "com.huanglong.pad"
# 商城渠道签名（KEMI K9）证书指纹；换签名必须先确认线上包能被覆盖升级
$expectedSha256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

function Require-File([string]$Path, [string]$What) {
    if (!(Test-Path -LiteralPath $Path)) { throw "缺少${What}：$Path" }
}

if ([string]::IsNullOrWhiteSpace($GradlePath)) {
    $gradleRoot = Join-Path $env:USERPROFILE ".gradle\wrapper\dists"
    $GradlePath = Get-ChildItem -LiteralPath $gradleRoot -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'gradle-8\.' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($ApkSignerPath)) {
    $buildToolsRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools"
    $ApkSignerPath = Get-ChildItem -LiteralPath $buildToolsRoot -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
Require-File $GradlePath "Gradle（可用 -GradlePath 指定）"
Require-File $ApkSignerPath "apksigner（可用 -ApkSignerPath 指定）"
$buildToolsDir = Split-Path -Parent $ApkSignerPath
$aaptPath = Join-Path $buildToolsDir "aapt2.exe"
Require-File $aaptPath "aapt2.exe（应与 apksigner 同目录）"
Require-File $propertiesPath "签名配置（signing\k9.release.signing.properties）"
Require-File $keystorePath "签名库（signing\debug.keystore）"

$buildFile = Get-Content -Raw -LiteralPath (Join-Path $projectRoot "app\build.gradle")
$versionNameMatch = [regex]::Match($buildFile, 'versionName\s+["'']([^"'']+)["'']')
$versionCodeMatch = [regex]::Match($buildFile, 'versionCode\s+(\d+)')
if (!$versionNameMatch.Success) { throw "无法从 app/build.gradle 读取 versionName" }
if (!$versionCodeMatch.Success) { throw "无法从 app/build.gradle 读取 versionCode" }
$versionName = $versionNameMatch.Groups[1].Value
$versionCode = $versionCodeMatch.Groups[1].Value

$publishDir = Join-Path $projectRoot "build\publish"
New-Item -ItemType Directory -Force -Path $publishDir | Out-Null
$outputApk = Join-Path $publishDir "luzhanqi-$versionName-$versionCode-store.apk"
$checksumPath = "$outputApk.sha256"
foreach ($stale in @($outputApk, $checksumPath)) {
    if (Test-Path -LiteralPath $stale) { Remove-Item -LiteralPath $stale -Force }
}

& $GradlePath -p $projectRoot testDebugUnitTest assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败：$LASTEXITCODE" }

$builtApk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
Require-File $builtApk "Release 产物"

# 1) 签名指纹必须是商城 K9 证书（保证能覆盖线上已装版本）
$verifyOutput = & $ApkSignerPath verify --print-certs $builtApk 2>&1
if ($LASTEXITCODE -ne 0) { throw "签名校验失败：$($verifyOutput -join [Environment]::NewLine)" }
$normalized = (($verifyOutput -join "`n").ToLowerInvariant() -replace ':', '')
if (!$normalized.Contains($expectedSha256)) {
    throw "APK 未使用预期证书 $expectedSha256"
}

# 2) 包名与版本必须与 build.gradle 一致
$badging = & $aaptPath dump badging $builtApk 2>&1
if ($LASTEXITCODE -ne 0) { throw "无法读取 APK 元数据：$($badging -join [Environment]::NewLine)" }
$packageMatch = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'")
if (!$packageMatch.Success) { throw "无法解析 APK 包名和版本" }
if ($packageMatch.Groups[1].Value -ne $expectedPackage) {
    throw "APK 包名错误：$($packageMatch.Groups[1].Value)"
}
if ($packageMatch.Groups[2].Value -ne $versionCode) {
    throw "APK versionCode 错误：$($packageMatch.Groups[2].Value)"
}
if ($packageMatch.Groups[3].Value -ne $versionName) {
    throw "APK versionName 错误：$($packageMatch.Groups[3].Value)"
}

Copy-Item -LiteralPath $builtApk -Destination $outputApk -Force
$hashText = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Value "$hashText  $(Split-Path -Leaf $outputApk)" -Encoding ascii

Write-Output "正式发布包：$outputApk"
Write-Output "SHA-256：$hashText"
Write-Output "校验清单：$checksumPath"
