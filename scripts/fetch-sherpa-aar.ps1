# Downloads the prebuilt sherpa-onnx Android .aar into app/libs/.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/fetch-sherpa-aar.ps1
$ErrorActionPreference = "Stop"
$ver = "1.13.2"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/sherpa-onnx-$ver.aar"
$libs = Join-Path $PSScriptRoot "..\app\libs"
$dest = Join-Path $libs "sherpa-onnx-$ver.aar"

New-Item -ItemType Directory -Force -Path $libs | Out-Null
Write-Host "Downloading $url"
try {
    Invoke-WebRequest -Uri $url -OutFile $dest
    Write-Host "Saved to $dest"
} catch {
    Write-Warning "下载失败：$($_.Exception.Message)"
    Write-Warning "若该 release 未直接附带 .aar，请改用 README 方式 B（下载 android tar.bz2，拷 jniLibs + kotlin-api 源码）。"
    exit 1
}
