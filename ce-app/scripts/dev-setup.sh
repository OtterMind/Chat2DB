#!/usr/bin/env bash
# Cutting Edge — one command to a working development environment.
#
#   bash ce-app/scripts/dev-setup.sh
#
# Creates a lightweight backend virtualenv (no heavy AI wheels), fetches a local
# FFmpeg if the system has none, and installs the frontend. Everything lands in
# paths the app already understands, so `npm run dev` + `python run_backend.py`
# work straight after.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${CE_VENV:-$ROOT/.venv}"

echo "→ backend virtualenv at $VENV"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip
# Light set: enough to run the API, the compositor and the test-suite.
# `httpx` is here because it is the transport under starlette's TestClient — five
# test modules import it at collection time, so without it `pytest` dies with
# five collection errors before running a single test. Not a runtime dependency of
# the shipped backend (which calls providers with plain `requests`), which is why
# it stays out of backend/requirements.txt.
"$VENV/bin/pip" install -q fastapi "uvicorn[standard]" pydantic-settings \
    psutil python-multipart scenedetect pytest httpx imageio-ffmpeg
# scenedetect pulls plain `opencv-python`, which cannot import without libGL — and
# a cv2 that will not load silently disables the log-polar zoom measurement, so
# `test_camera_motion_is_recognised[pull]` fails for an environment reason that
# looks exactly like a code regression. Then pin the version we ship: OpenCV 5.x
# dropped the bundled Haar cascades, which silently disables face detection.
"$VENV/bin/pip" uninstall -y -q opencv-python >/dev/null 2>&1 || true
"$VENV/bin/pip" install -q "opencv-python-headless==4.10.0.84"

if command -v ffmpeg >/dev/null 2>&1; then
    echo "→ using system ffmpeg: $(command -v ffmpeg)"
else
    echo "→ no system ffmpeg, extracting the bundled build"
    mkdir -p "$ROOT/.ffmpeg"
    "$VENV/bin/python" - <<'PY'
import os, shutil, stat, imageio_ffmpeg
root = os.environ.get("CE_ROOT") or os.getcwd()
dest = os.path.join(root, ".ffmpeg", "ffmpeg")
shutil.copy(imageio_ffmpeg.get_ffmpeg_exe(), dest)
os.chmod(dest, os.stat(dest).st_mode | stat.S_IEXEC)
print("   ", dest)
PY
    echo "   export CE_FFMPEG_DIR=$ROOT/.ffmpeg"
fi

echo "→ frontend dependencies"
# A network-filtering sandbox cannot download the Electron binary itself
# (`node install.js` → "unable to verify the first certificate"). Say so and give
# the exact fallback rather than dying with an npm stack trace: the checks we run
# here (`npm run verify`, the browser tests) are TypeScript and Chromium, and
# neither needs Electron's binary. Packaging does, and that is the Windows runner.
( cd "$ROOT/frontend" && npm install --no-audit --no-fund ) || {
    cat <<TXT

npm install failed. If the last error mentions a certificate or the Electron
download, this machine cannot fetch the Electron binary. To get the checks
running anyway (no packaged app, no \`npm run dev\`):

  cd $ROOT/frontend && ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install --no-audit --no-fund

TXT
    exit 1
}

cat <<TXT

Ready. Two terminals:

  export CE_FFMPEG_DIR=$ROOT/.ffmpeg
  $VENV/bin/python $ROOT/backend/run_backend.py

  cd $ROOT/frontend && npm run dev

Checks:
  $VENV/bin/python -m pytest            # from ce-app/backend
  npm run test:ui                       # from ce-app/frontend, dev server running
TXT
