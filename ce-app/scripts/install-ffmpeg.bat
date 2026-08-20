@echo off
REM Cutting Edge (CE) — FFmpeg Auto-Installer
echo.
echo Installing FFmpeg for Cutting Edge...
echo.
set FFMPEG_DIR=%PROGRAMDATA%\CuttingEdge\bin
mkdir "%FFMPEG_DIR%" 2>nul
echo [1/3] Downloading FFmpeg full build...
curl -L "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-full.7z" -o "%TEMP%\ffmpeg.7z"
echo [2/3] Extracting...
powershell -Command "Expand-Archive -Path '%TEMP%\ffmpeg.7z' -DestinationPath '%TEMP%\ffmpeg_extract' -Force"
echo [3/3] Installing...
for /d %%i in ("%TEMP%\ffmpeg_extract\*") do (
    copy "%%i\bin\ffmpeg.exe" "%FFMPEG_DIR%\ffmpeg.exe"
    copy "%%i\bin\ffprobe.exe" "%FFMPEG_DIR%\ffprobe.exe"
)
setx PATH "%PATH%;%FFMPEG_DIR%"
del "%TEMP%\ffmpeg.7z" 2>nul
rmdir /s /q "%TEMP%\ffmpeg_extract" 2>nul
echo.
echo FFmpeg installed to %FFMPEG_DIR%
pause