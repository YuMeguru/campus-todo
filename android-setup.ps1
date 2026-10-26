# Campus Todo - Android build environment bootstrap (everything lands on D:).
#
# ASCII only on purpose: Windows PowerShell 5.1 decodes .ps1 as the ANSI codepage,
# so non-ASCII text in this file breaks the parser before anything runs.
#
# Why not the official sdkmanager: dl.google.com and github.com are both unreachable
# from this machine (measured). Adoptium's GitHub redirect and the Google SDK repo
# therefore fail. This script uses mirrors that were each verified reachable:
#   JDK 17      -> TUNA Adoptium mirror
#   Android SDK -> Tencent AndroidSDK mirror (full mirror of dl.google.com/android/repository)
#   Gradle      -> Tencent Gradle mirror
#   AGP         -> Aliyun Google Maven (configured in build.gradle.kts, not here)
#
# Usage: powershell -ExecutionPolicy Bypass -File android-setup.ps1
# Idempotent: targets that are already in place are skipped.

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$Root      = 'D:\Android'
$Sdk       = "$Root\sdk"
$JdkDir    = "$Root\jdk"
$GradleDir = "$Root\gradle-dist"
$Dl        = "$Root\_dl"

$JdkUrl    = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$GradleUrl = 'https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip'
$SdkBase   = 'https://mirrors.cloud.tencent.com/AndroidSDK'
$SdkPkgs   = @(
    @{ Zip = 'commandlinetools-win-16111833_latest.zip'; Probe = 'bin\sdkmanager.bat'; Dest = "$Sdk\cmdline-tools\latest" },
    @{ Zip = 'platform-tools_r37.0.1-win.zip';           Probe = 'adb.exe';             Dest = "$Sdk\platform-tools" },
    @{ Zip = 'platform-36_r02.zip';                      Probe = 'android.jar';         Dest = "$Sdk\platforms\android-36" },
    @{ Zip = 'build-tools_r36_windows.zip';              Probe = 'aapt2.exe';           Dest = "$Sdk\build-tools\36.0.0" }
)

function Say($m) { Write-Host "[setup] $m" }

function Get-File($url, $out) {
    if (Test-Path $out) { Say "already downloaded: $(Split-Path $out -Leaf)"; return }
    Say "downloading $url"
    & curl.exe -sL --fail -m 1800 -o $out $url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out) -or (Get-Item $out).Length -eq 0) {
        throw "download failed: $url"
    }
    Say ("  -> {0:N1} MB" -f ((Get-Item $out).Length / 1MB))
}

# Extract into a scratch dir, locate the level that actually holds the probe file,
# then move that level to its canonical SDK path.
function Expand-To($zip, $probe, $dest) {
    if (Test-Path (Join-Path $dest $probe)) { Say "already in place: $dest"; return }

    $tmp = Join-Path $Dl ("x_" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Say "extracting $(Split-Path $zip -Leaf)"
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $zip -C $tmp
        if ($LASTEXITCODE -ne 0) { throw "tar failed on $zip" }
    } else {
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
    }

    $probeLeaf = Split-Path $probe -Leaf
    $hits = @(Get-ChildItem $tmp -Recurse -File -Filter $probeLeaf -ErrorAction SilentlyContinue)
    if ($hits.Count -eq 0) { throw "probe $probe not found after extracting $zip" }

    $src = $null
    if ($probe.Contains('\')) {
        foreach ($h in $hits) {
            $cand = $h.Directory.FullName
            for ($i = 0; $i -lt 4; $i++) {
                if (Test-Path (Join-Path $cand $probe)) { $src = $cand; break }
                $parent = Split-Path $cand -Parent
                if (-not $parent -or $parent -eq $tmp) { break }
                $cand = $parent
            }
            if ($src) { break }
        }
    }
    if (-not $src) { $src = $hits[0].Directory.FullName }

    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
    Move-Item $src $dest
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Say "  -> $dest"
}

New-Item -ItemType Directory -Force -Path $Root, $Sdk, $Dl, $GradleDir | Out-Null

# --- JDK 17 -----------------------------------------------------------------
if (Test-Path "$JdkDir\bin\java.exe") {
    Say 'JDK already in place'
} else {
    $zip = "$Dl\jdk17.zip"
    Get-File $JdkUrl $zip
    Expand-To $zip 'bin\java.exe' $JdkDir
}

# --- Gradle 8.11.1 ----------------------------------------------------------
if (Test-Path "$GradleDir\gradle-8.11.1\bin\gradle.bat") {
    Say 'Gradle already in place'
} else {
    $zip = "$Dl\gradle.zip"
    Get-File $GradleUrl $zip
    Expand-To $zip 'bin\gradle.bat' "$GradleDir\gradle-8.11.1"
}

# --- Android SDK packages ---------------------------------------------------
foreach ($p in $SdkPkgs) {
    $zip = Join-Path $Dl $p.Zip
    Get-File "$SdkBase/$($p.Zip)" $zip
    Expand-To $zip $p.Probe $p.Dest
}

Say '--- verification ---'
& "$JdkDir\bin\java.exe" -version 2>&1 | Select-Object -First 1
Say ("adb:        " + (Test-Path "$Sdk\platform-tools\adb.exe"))
Say ("android.jar:" + (Test-Path "$Sdk\platforms\android-36\android.jar"))
Say ("aapt2:      " + (Test-Path "$Sdk\build-tools\36.0.0\aapt2.exe"))
Say ("sdkmanager: " + (Test-Path "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"))
Say ("gradle:     " + (Test-Path "$GradleDir\gradle-8.11.1\bin\gradle.bat"))
Say ("free: C={0:N2} GB D={1:N2} GB" -f ((Get-PSDrive C).Free / 1GB), ((Get-PSDrive D).Free / 1GB))
Say 'done'
