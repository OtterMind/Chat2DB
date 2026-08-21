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
"$VENV/bin/pip" install -q fastapi "uvicorn[standard]" sqlalchemy pydantic-settings \
    psutil python-multipart scenedetect pytest imageio-ffmpeg

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
( cd "$ROOT/frontend" && npm install --no-audit --no-fund )

cat <<TXT

Ready. Two terminals:

  export CE_FFMPEG_DIR=$ROOT/.ffmpeg
  $VENV/bin/python $ROOT/backend/run_backend.py

  cd $ROOT/frontend && npm run dev

Checks:
  $VENV/bin/python -m pytest            # from ce-app/backend
  npm run test:ui                       # from ce-app/frontend, dev server running
TXT
