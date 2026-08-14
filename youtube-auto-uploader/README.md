# YouTube Auto-Uploader & AI Clipper

A Windows 10/11 x64 Electron desktop application for authorized YouTube publishing, channel monitoring, batch jobs, and local-AI highlight clipping. The end user does not need Node.js or a terminal.

> **Copyright and platform policy:** Only download or upload media you own, that is public domain, or for which you have explicit permission. Re-uploading third-party media may violate copyright law and YouTube's Terms. The application displays this warning before sign-in.

## End-user installation

1. Download **YouTube Auto-Uploader 1.0.0 x64.exe**.
2. Double-click it, choose an install directory, and select **Finish**. Because community builds are unsigned, Windows SmartScreen may require **More info → Run anyway**.
3. Install [Ollama for Windows](https://ollama.com/download/windows) if you intend to use AI Clipper.
4. Follow the in-app Google OAuth wizard and sign in.

Application data is kept under the current user's Electron `userData` directory. Uninstall from Windows **Installed apps**. Uninstall does not remove user data by default.

## Google OAuth setup

The app never embeds or asks the developer for credentials. Each end user creates a Google Desktop OAuth client:

1. Open [Google Cloud Credentials](https://console.cloud.google.com/apis/credentials).
2. Create/select a project and enable **YouTube Data API v3**.
3. Configure the OAuth consent screen. Add your Google account as a test user while the app is in testing mode.
4. Create **OAuth client ID → Desktop app**. Desktop clients support the dynamic loopback callback used by the application (`http://127.0.0.1:<random-port>/callback`).
5. Paste the client ID and secret into the in-app guide and select **Test & Save**.
6. Select **Sign in with Google** and approve the requested upload/read scopes.

Credentials and tokens are stored through Windows Credential Manager (`keytar`), with Electron OS encryption (`safeStorage`) as a fallback. They are not stored in source or renderer storage.

## Features

- **Single upload:** paste a standard video, short, mobile, or `youtu.be` URL; review metadata and upload privately, unlisted, or publicly.
- **Batch channel:** resolve a channel or playlist, select videos, and queue them.
- **Auto-sync:** add source channels; the first scan establishes a baseline and does not backfill. Later videos are queued or auto-uploaded according to channel settings.
- **AI Clipper:** choose a URL or local file, an installed Ollama model, and clip count. The main process asks Ollama for strict JSON timestamps, renders vertical H.264/AAC clips with FFmpeg, and extracts thumbnails.
- **Pending approval / History:** approve AI results and inspect job state.
- **Tray:** closing minimizes to tray by default; double-click restores the window.

### Ollama

Install Ollama, then install a model using Ollama's UI or command-line tooling. Recommended models are `qwen2.5:7b-instruct-q4_0` for typical computers or a larger Qwen/Llama model when RAM permits. The app connects only to `http://127.0.0.1:11434` by default.

## Build in GitHub without installing developer tools

The repository includes `.github/workflows/build-youtube-uploader.yml`. After the source is pushed to GitHub:

1. Open the repository's **Actions** tab.
2. Select **Build YouTube Auto-Uploader**.
3. Choose **Run workflow** and wait for the Windows job to finish.
4. Open the completed run and download **YouTube-Auto-Uploader-1.0.0-Windows-x64** under **Artifacts**.
5. Extract the ZIP. It contains the NSIS installer, portable executable, and `SHA256SUMS.txt`.

The workflow builds on Windows Server 2022, runs static checks and smoke tests, verifies FFmpeg and yt-dlp, rebuilds native Electron modules, and only uploads artifacts when all checks pass. It does not sign the files unless a future workflow is configured with a private code-signing certificate.

## Developer build

Requirements: Node.js 18/20, npm, and a Windows x64 packaging environment (or a correctly configured cross-build host).

```text
npm install
npm run typecheck
npm run lint
npm run smoke
npm run dist:win
```

`prebuild:win` downloads the official yt-dlp Windows asset, verifies it against the release's `SHA2-256SUMS`, downloads FFmpeg essentials, and places the executables under `resources/binaries`. Native dependencies are rebuilt by electron-builder.

## Troubleshooting

- **OAuth callback fails:** verify you created a *Desktop app* client, the YouTube API is enabled, and the browser/firewall permits loopback traffic.
- **Quota exceeded:** wait until the YouTube quota resets; reduce frequent channel checks.
- **Ollama unavailable:** start Ollama, verify port 11434, then reopen AI Clipper.
- **FFmpeg/yt-dlp missing:** reinstall from the NSIS package. Development builds must run `npm run prebuild:win`.
- **Video unavailable:** private, members-only, age-restricted, DRM-protected, or region-restricted videos may not be downloadable.
- Logs are under the application's `userData/logs` directory.

## خلاصه فارسی

این برنامه برای ویندوز ۱۰/۱۱ ساخته شده و بارگذاری ویدیو، پایش کانال و ساخت کلیپ کوتاه با Ollama محلی را مدیریت می‌کند. فقط محتوایی را استفاده کنید که مالک آن هستید یا اجازهٔ رسمی دارید. اطلاعات OAuth در Credential Manager ویندوز ذخیره می‌شود. برای شروع، YouTube Data API v3 را در Google Cloud فعال کنید، یک OAuth Client از نوع Desktop بسازید و مشخصات را در راهنمای داخل برنامه وارد کنید.
