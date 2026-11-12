param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Projects\Android\SDK" }),
    [string]$JavaHome = $(if (Test-Path "D:\Program Files\Java\jdk1.8.0_152\bin\javac.exe") { "D:\Program Files\Java\jdk1.8.0_152" } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\Program Files\Android\Android Studio\jbr" }),
    [switch]$SkipSign
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildTools = Join-Path $Sdk "build-tools\33.0.0"
$AndroidJar = Join-Path $Sdk "platforms\android-33\android.jar"
$aapt2 = Join-Path $BuildTools "aapt2.exe"
$zipalign = Join-Path $BuildTools "zipalign.exe"
$apksigner = Join-Path $BuildTools "apksigner.bat"
$d8 = Join-Path $BuildTools "d8.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"

foreach ($p in @($aapt2, $zipalign, $apksigner, $d8, $javac, $jar, $keytool, $AndroidJar)) {
    if (-not (Test-Path $p)) { throw "缺少构建工具: $p" }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH

$build = Join-Path $Root "build"
$compiled = Join-Path $build "compiled"
$gen = Join-Path $build "gen"
$classes = Join-Path $build "classes"
$dex = Join-Path $build "dex"
$dist = Join-Path $Root "dist"
$keystoreDir = Join-Path $Root "keystore"
$keystore = Join-Path $keystoreDir "xiaosa-release.jks"
$storePass = "xiaosa123"
$keyAlias = "xiaosa"
$version = "1.2.0"
$versionCode = 4
$outName = "潇洒K歌-TV播放端-v$version.apk"

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $compiled, $gen, $classes, $dex, $dist, $keystoreDir | Out-Null

Write-Host "==> aapt2 compile"
& $aapt2 compile --dir (Join-Path $Root "res") -o $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

$flat = @(Get-ChildItem $compiled -Filter *.flat | ForEach-Object { $_.FullName })
if ($flat.Count -eq 0) { throw "没有编译出资源 .flat" }

Write-Host "==> aapt2 link"
$unsigned = Join-Path $build "app-unsigned.apk"
$linkArgs = @(
    "link", "-o", $unsigned,
    "-I", $AndroidJar,
    "--manifest", (Join-Path $Root "AndroidManifest.xml"),
    "--java", $gen,
    "--auto-add-overlay",
    "--min-sdk-version", "21",
    "--target-sdk-version", "33"
) + $flat
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "==> javac"
$javaSources = @(Get-ChildItem (Join-Path $Root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($javaSources.Count -eq 0) { throw "src 下没有 .java 文件" }
# javac 对 deprecated API 会往 stderr 打一条 note；PowerShell 的 Stop 模式
# 会把原生程序的 stderr 当成异常，导致明明编译成功却中断。这里临时放宽，
# 编译结果仍然用 $LASTEXITCODE 严格判断。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $AndroidJar -d $classes @javaSources
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($javacExit -ne 0) { throw "javac 失败" }

Write-Host "==> d8"
$classesJar = Join-Path $build "classes.jar"
Push-Location $classes
try {
    & $jar cf $classesJar .
    if ($LASTEXITCODE -ne 0) { throw "打包 classes.jar 失败" }
} finally {
    Pop-Location
}
& $d8 --lib $AndroidJar --min-api 21 --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "==> 打包 classes.dex"
Push-Location $dex
try {
    & $jar uf $unsigned "classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "写入 classes.dex 失败" }
} finally {
    Pop-Location
}

Write-Host "==> zipalign"
$aligned = Join-Path $build "app-aligned.apk"
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

if ($SkipSign) {
    $out = Join-Path $dist $outName
    Copy-Item $aligned $out -Force
    Write-Host "未签名 APK: $out"
    exit 0
}

if (-not (Test-Path $keystore)) {
    Write-Host "==> 生成签名证书（仅首次）"
    & $keytool -genkeypair -v -keystore $keystore -storetype JKS -alias $keyAlias `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $storePass -keypass $storePass `
        -dname "CN=Xiaosa KTV, OU=TV Player, O=Xiaosa, L=CN, S=CN, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败" }
}

Write-Host "==> apksigner"
$out = Join-Path $dist $outName
& $apksigner sign --ks $keystore --ks-pass "pass:$storePass" --key-pass "pass:$storePass" --out $out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "==> 验证"
& $apksigner verify --print-certs $out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify 失败" }

Get-Item $out | Select-Object FullName, Length, LastWriteTime
Write-Host "完成: $out"
exit 0
