#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 <project-root> <app-bundle> <signing-identity>" >&2
    exit 2
fi

PROJECT_ROOT=$(cd "$1" && pwd)
APP_BUNDLE="$2"
SIGNING_IDENTITY="$3"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Error: macOS app images can only be finalized on macOS" >&2
    exit 1
fi
if [ ! -d "${APP_BUNDLE}/Contents/app" ]; then
    echo "Error: signed macOS app bundle is missing: ${APP_BUNDLE}" >&2
    exit 1
fi
if [ -z "${SIGNING_IDENTITY}" ]; then
    echo "Error: macOS signing identity is required" >&2
    exit 1
fi

# shellcheck source=script/package/desktop_layout.sh
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "${SCRIPT_DIR}/desktop_layout.sh"
chat2db_load_desktop_layout "${PROJECT_ROOT}"

SIGN_ARGS=(
    --force
    --sign "${SIGNING_IDENTITY}"
    "--preserve-metadata=identifier,entitlements,requirements,flags,runtime"
)
if [ "${SIGNING_IDENTITY}" != "-" ]; then
    SIGN_ARGS+=(--timestamp --options runtime)
fi
codesign "${SIGN_ARGS[@]}" "${APP_BUNDLE}"
codesign --verify --deep --strict --verbose=2 "${APP_BUNDLE}"
chat2db_validate_update_package "${APP_BUNDLE}/Contents"

echo "Finalized signed macOS app image: ${APP_BUNDLE}"
