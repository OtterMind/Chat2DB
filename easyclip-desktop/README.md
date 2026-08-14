# EasyClip Desktop

A Windows-first, local AI video clipping studio. The app is being built as a lightweight Tauri desktop application with a React interface and a Rust host.

## Current foundation (0.1.0)

- Persian/English RTL/LTR desktop interface
- Local video file picker (MP4, MOV, MKV, WebM, AVI)
- Local project library
- Windows/NVIDIA capability detection through `nvidia-smi`
- Tauri security capability and CSP configuration
- NSIS installer configuration for Windows 11 x64
- Windows GitHub Actions build that uploads the unsigned installer as an artifact

The current build is a functional desktop foundation, not yet the complete video processor. It does not claim to generate clips yet.

## Planned pipeline

1. Bundle and verify FFmpeg/FFprobe
2. Extract audio and generate Persian/English captions with Faster-Whisper (CUDA)
3. Score transcript segments using a local language model
4. Track faces/speakers and automatically crop to 9:16
5. Render MP4 clips with animated captions using NVIDIA NVENC
6. Add clip review, trim and caption editing
7. Add Google OAuth and official YouTube Shorts upload
8. Add optional watched-folder and automatic publishing mode

## Development

```bash
npm install
npm run dev
npm run build
```

A native desktop development build additionally requires Rust and the Tauri prerequisites:

```bash
npm run tauri dev
```

## Windows installer

The workflow `.github/workflows/easyclip-windows.yml` runs on a Windows runner and builds an NSIS `Setup.exe`. The installer is unsigned during development, so Windows SmartScreen may warn until a code-signing certificate is configured.

No API keys, OAuth credentials, models, videos, or generated installers belong in Git.
