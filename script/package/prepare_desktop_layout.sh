#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 5 ]; then
    echo "Usage: $0 <version> <dist-dir> <release-epoch> <build-sha> <project-root>" >&2
    exit 1
fi

VERSION="$1"
DIST_DIR="$2"
RELEASE_EPOCH="$3"
BUILD_SHA="$4"
PROJECT_ROOT="$5"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=script/package/desktop_layout.sh
source "${SCRIPT_DIR}/desktop_layout.sh"
chat2db_load_desktop_layout "${PROJECT_ROOT}"

if [ ! -f "${DIST_DIR}/index.html" ]; then
    echo "Error: frontend dist is incomplete: ${DIST_DIR}" >&2
    exit 1
fi
if [[ ! "${RELEASE_EPOCH}" =~ ^[0-9]+$ ]]; then
    echo "Error: release epoch must be a non-negative integer" >&2
    exit 1
fi
if [[ ! "${BUILD_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Error: build SHA must be a 40-character lowercase Git commit" >&2
    exit 1
fi

VERSION_FILE=$(mktemp)
trap 'rm -f "${VERSION_FILE}"' EXIT
jq -n \
    --arg version "${VERSION}" \
    --argjson releaseEpoch "${RELEASE_EPOCH}" \
    --arg buildSha "${BUILD_SHA}" \
    '{version: $version, releaseEpoch: $releaseEpoch, buildSha: $buildSha}' \
    > "${VERSION_FILE}"

for platform in mac win linux; do
    input_dir="${PROJECT_ROOT}/jpackage/input/${platform}"
    mkdir -p "${input_dir}"
    rm -rf "${input_dir}/dist" "${input_dir}/runtime/dist"
    if [ "${CHAT2DB_DESKTOP_LAYOUT}" = "bridge-fat" ]; then
        cp -R "${DIST_DIR}" "${input_dir}/dist"
    else
        mkdir -p "${input_dir}/runtime"
        cp -R "${DIST_DIR}" "${input_dir}/runtime/dist"
    fi
    cp "${VERSION_FILE}" "${input_dir}/version.json"
    chat2db_validate_desktop_input "${input_dir}"
done

rm -f "${PROJECT_ROOT}/jpackage/input/sourceFile/lib.zip"
echo "Prepared ${CHAT2DB_DESKTOP_LAYOUT} desktop inputs for ${VERSION}."
