#!/usr/bin/env bash
# Rebuild the headless test environment from nothing.
#
# The sandbox wipes /tmp every few hours, and every one of these steps was
# rediscovered the hard way, so they live here instead of in a chat log:
#
#   bash ce-app/scripts/sandbox-test-env.sh
#   source /tmp/ce-test-env.sh          # exports the paths the tests need
#
# It provides: a backend virtualenv, a static ffmpeg, a Chromium that runs
# without a system package manager, and two test clips in a codec plain
# Chromium can actually decode (VP8/Vorbis — an unbranded build has no H.264).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV=/tmp/cevenv
FFDIR=/tmp/ffbin
MEDIA=/tmp/media
HB=/tmp/hb

echo "→ backend virtualenv"
[ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q fastapi "uvicorn[standard]" sqlalchemy pydantic-settings psutil \
    python-multipart pytest httpx scenedetect imageio-ffmpeg

echo "→ ffmpeg"
mkdir -p "$FFDIR"
[ -x "$FFDIR/ffmpeg" ] || {
  cp "$("$VENV/bin/python" -c 'import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())')" "$FFDIR/ffmpeg"
  chmod +x "$FFDIR/ffmpeg"
}

echo "→ test media"
mkdir -p "$MEDIA"
for i in 1 2; do
  [ -f "$MEDIA/clip$i.webm" ] || "$FFDIR/ffmpeg" -y -loglevel error \
    -f lavfi -i "testsrc=size=320x240:rate=25:duration=3" \
    -f lavfi -i "sine=frequency=$((300 * i)):duration=3" \
    -c:v libvpx -b:v 300k -c:a libvorbis -shortest "$MEDIA/clip$i.webm"
done

# a portrait file too: the monitor must take the shape of the footage
[ -f "$MEDIA/vertical.webm" ] || "$FFDIR/ffmpeg" -y -loglevel error \
  -f lavfi -i "testsrc=size=360x640:rate=25:duration=4" \
  -f lavfi -i "sine=frequency=440:duration=4" \
  -c:v libvpx -b:v 400k -c:a libvorbis -shortest "$MEDIA/vertical.webm"
# a click track at an exact 120 BPM: ground truth for beat detection
[ -f "$MEDIA/beat120.wav" ] || "$FFDIR/ffmpeg" -y -loglevel error \
  -f lavfi -i "aevalsrc='0.9*sin(2*PI*880*t)*exp(-30*mod(t\,0.5))':d=8:s=44100" "$MEDIA/beat120.wav"
# and one big enough to trigger the editing proxy
[ -f "$MEDIA/big.mp4" ] || "$FFDIR/ffmpeg" -y -loglevel error \
  -f lavfi -i "testsrc=size=2560x1440:rate=25:duration=3" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$MEDIA/big.mp4"

echo "→ headless Chromium"
mkdir -p "$HB"
( cd "$HB" && [ -d node_modules/@sparticuz ] || npm i --no-audit --no-fund --silent \
    @sparticuz/chromium@131.0.1 puppeteer-core@23.9.0 )
[ -x /tmp/chromium ] || ( cd "$HB" && node -e "require('@sparticuz/chromium').executablePath()" >/dev/null )
[ -d /tmp/chromium-libs/lib ] || {
  node -e "const z=require('node:zlib'),f=require('node:fs');
    f.writeFileSync('/tmp/al2023.tar', z.brotliDecompressSync(f.readFileSync('$HB/node_modules/@sparticuz/chromium/bin/al2023.tar.br')))"
  mkdir -p /tmp/chromium-libs && tar xf /tmp/al2023.tar -C /tmp/chromium-libs
}

cat > /tmp/ce-test-env.sh <<ENV
export CE_FFMPEG_DIR=$FFDIR
export CHROME_PATH=/tmp/chromium
export LD_LIBRARY_PATH=/tmp/chromium-libs/lib:/tmp/chromium-libs
export NODE_PATH=$HB/node_modules
export CE_TEST_A=$MEDIA/clip1.webm
export CE_TEST_B=$MEDIA/clip2.webm
export CE_TEST_VERTICAL=$MEDIA/vertical.webm
export CE_TEST_BIG=$MEDIA/big.mp4
export CE_TEST_BEAT=$MEDIA/beat120.wav
export CE_VENV=$VENV
ENV

cat <<TXT

ready. Next:

  source /tmp/ce-test-env.sh
  (cd $ROOT/backend && CE_FFMPEG_DIR=\$CE_FFMPEG_DIR \$CE_VENV/bin/python -m pytest -q)
  (cd $ROOT/backend && \$CE_VENV/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8742 --reload) &
  (cd $ROOT/frontend && npm run dev -- --host 0.0.0.0) &
  (cd $ROOT/frontend && npm run test:ui && npm run test:playback)

Always start uvicorn with --reload: a stale process has twice looked like a broken feature.
TXT
