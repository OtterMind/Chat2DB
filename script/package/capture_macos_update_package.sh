#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <notarized-dmg> <output-dir> <project-root>" >&2
    exit 1
fi

DMG_PATH="$1"
OUTPUT_DIR="$2"
PROJECT_ROOT="$3"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Error: macOS update packages can only be captured on macOS" >&2
    exit 1
fi
if [ ! -s "${DMG_PATH}" ]; then
    echo "Error: notarized DMG is missing: ${DMG_PATH}" >&2
    exit 1
fi

# shellcheck source=script/package/desktop_layout.sh
source "${SCRIPT_DIR}/desktop_layout.sh"
chat2db_load_desktop_layout "${PROJECT_ROOT}"

MOUNT_DIR=$(mktemp -d)
cleanup() {
    hdiutil detach "${MOUNT_DIR}" -quiet >/dev/null 2>&1 || true
    rm -rf "${MOUNT_DIR}"
}
trap cleanup EXIT

hdiutil attach -nobrowse -readonly -mountpoint "${MOUNT_DIR}" "${DMG_PATH}" >/dev/null
APP_COUNT=$(find "${MOUNT_DIR}" -maxdepth 1 -type d -name '*.app' -print | wc -l | tr -d ' ')
if [ "${APP_COUNT}" -ne 1 ]; then
    echo "Error: expected exactly one application bundle in ${DMG_PATH}, found ${APP_COUNT}" >&2
    exit 1
fi

APP_BUNDLE=$(find "${MOUNT_DIR}" -maxdepth 1 -type d -name '*.app' -print -quit)
codesign --verify --deep --strict --verbose=2 "${APP_BUNDLE}"
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
/usr/bin/ditto "${APP_BUNDLE}/Contents" "${OUTPUT_DIR}/Contents"
chat2db_validate_update_package "${OUTPUT_DIR}"

echo "Captured signed full application from notarized DMG: ${OUTPUT_DIR}"
