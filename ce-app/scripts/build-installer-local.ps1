# Cutting Edge (CE) — One-click Windows installer builder
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot)

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Cutting Edge (CE) — Installer Builder"    -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Prerequisites
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host "Python not found. Install Python 3.11 from https://www.python.org/downloads/" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "Node.js not found. Install Node 20 from https://nodejs.org/" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
Write-Host "[1/5] Prerequisites OK" -ForegroundColor Green

# 2. FFmpeg
Write-Host "[2/5] Downloading FFmpeg..." -ForegroundColor Yellow
if (-not (Test-Path "build/ffmpeg/ffmpeg.exe")) {
    New-Item -ItemType Directory -Force -Path "build/ffmpeg" | Out-Null
    Invoke-WebRequest -Uri "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-full.7z" -OutFile "$env:TEMP\ffmpeg.7z"
    & 7z x "$env:TEMP\ffmpeg.7z" "-o$env:TEMP\ffmpeg_extract" -y | Out-Null
    $binDir = Get-ChildItem "$env:TEMP\ffmpeg_extract" -Directory | Select-Object -First 1
    Copy-Item "$($binDir.FullName)\bin\ffmpeg.exe" "build/ffmpeg/"
    Copy-Item "$($binDir.FullName)\bin\ffprobe.exe" "build/ffmpeg/"
    Remove-Item "$env:TEMP\ffmpeg.7z"
}
Write-Host "  FFmpeg ready." -ForegroundColor Green

# 3. Backend
Write-Host "[3/5] Building portable backend..." -ForegroundColor Yellow
if (-not (Test-Path "build/backend/python/python.exe")) {
    python -m venv "build/backend/python"
    & "build/backend/python/Scripts/python.exe" -m pip install --upgrade pip -q
    & "build/backend/python/Scripts/python.exe" -m pip install -r "backend/requirements.txt" -q
}
Remove-Item -Recurse -Force "build/backend/app" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "build/backend/core" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "build/backend/uploaders" -ErrorAction SilentlyContinue
Copy-Item -Recurse "backend/app" "build/backend/app"
Copy-Item -Recurse "backend/core" "build/backend/core"
Copy-Item -Recurse "backend/uploaders" "build/backend/uploaders"
Copy-Item "backend/run_backend.py" "build/backend/"
Write-Host "  Backend ready." -ForegroundColor Green

# 4. Frontend
Write-Host "[4/5] Building frontend..." -ForegroundColor Yellow
Set-Location "frontend"
npm install --no-audit --no-fund
npm run build
Set-Location ".."

# 5. Installer
Write-Host "[5/5] Creating Windows installer..." -ForegroundColor Yellow
Set-Location "frontend"
npx electron-builder --win nsis --publish never
Set-Location ".."

Write-Host ""
Write-Host "Done! Installer:" -ForegroundColor Green
Get-ChildItem "frontend/release/*.exe" | ForEach-Object {
    Write-Host "  $($_.FullName)  ($([math]::Round($_.Length/1MB, 1)) MB)" -ForegroundColor Cyan
}
Read-Host "Press Enter to exit"