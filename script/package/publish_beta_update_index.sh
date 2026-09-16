#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <release-index.json> <build-sha>" >&2
    exit 1
fi

INDEX_FILE="$1"
BUILD_SHA="$2"
CHANNEL_TAG="community-beta"

validate_index() {
    jq -e '.schemaVersion == 2 and .channel == "BETA" and .status == "ACTIVE" and
        (.releaseEpoch | type == "number" and . > 0 and . == floor) and
        (.releases | type == "array" and length > 0)' "$1" >/dev/null
}

test "$(basename "${INDEX_FILE}")" = release-index.json
[[ "${BUILD_SHA}" =~ ^[0-9a-f]{40}$ ]]
validate_index "${INDEX_FILE}"

WORK_DIR=$(mktemp -d)
trap 'rm -rf "${WORK_DIR}"' EXIT

gh release list --limit 1000 --json tagName --jq '.[].tagName' > "${WORK_DIR}/releases"
if grep -Fxq "${CHANNEL_TAG}" "${WORK_DIR}/releases"; then
    gh release view "${CHANNEL_TAG}" --json assets --jq '.assets[].name' > "${WORK_DIR}/assets"
else
    gh release create "${CHANNEL_TAG}" --target "${BUILD_SHA}" --prerelease --latest=false \
        --title 'Community Beta updates' \
        --notes 'Beta update index. Installers and signed manifests are attached to their versioned releases.'
    : > "${WORK_DIR}/assets"
fi

if grep -Fxq release-index.json "${WORK_DIR}/assets"; then
    gh release download "${CHANNEL_TAG}" --pattern release-index.json --dir "${WORK_DIR}"
    PREVIOUS_INDEX="${WORK_DIR}/release-index.json"
    validate_index "${PREVIOUS_INDEX}"
    PREVIOUS_EPOCH=$(jq -r '.releaseEpoch' "${PREVIOUS_INDEX}")
    RELEASE_EPOCH=$(jq -r '.releaseEpoch' "${INDEX_FILE}")
    if [ "${RELEASE_EPOCH}" -le "${PREVIOUS_EPOCH}" ]; then
        if cmp -s "${INDEX_FILE}" "${PREVIOUS_INDEX}"; then
            echo 'Beta update index is already published.'
            exit 0
        fi
        echo "Refusing to replace Beta epoch ${PREVIOUS_EPOCH} with ${RELEASE_EPOCH}" >&2
        exit 1
    fi
fi

gh release upload "${CHANNEL_TAG}" "${INDEX_FILE}" --clobber
mkdir -p "${WORK_DIR}/published"
gh release download "${CHANNEL_TAG}" --pattern release-index.json --dir "${WORK_DIR}/published"
cmp "${INDEX_FILE}" "${WORK_DIR}/published/release-index.json"
