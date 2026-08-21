#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
METADATA_SCRIPT="${SCRIPT_DIR}/../generate_metadata.sh"
TEST_ROOT=$(mktemp -d)
VERSION="5.4.0"

cleanup() {
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  echo "[error] $1" >&2
  exit 1
}

mkdir -p "${TEST_ROOT}/payloads"

# Create deterministic mock payloads.
echo "mock-jar-content" > "${TEST_ROOT}/payloads/chat2db-community.jar"
mkdir -p "${TEST_ROOT}/payloads-lib-staging/lib"
echo "mock-lib-content" > "${TEST_ROOT}/payloads-lib-staging/lib/mock.jar"
(cd "${TEST_ROOT}/payloads-lib-staging" && zip -qr "${TEST_ROOT}/payloads/lib.zip" lib)
mkdir -p "${TEST_ROOT}/payloads/dist"
echo "mock-dist-content" > "${TEST_ROOT}/payloads/dist/index.html"
(cd "${TEST_ROOT}/payloads" && zip -qr dist.zip dist)
rm -rf "${TEST_ROOT}/payloads/dist"
bash "${METADATA_SCRIPT}" "${VERSION}" "${TEST_ROOT}/payloads" "${TEST_ROOT}/outputs"

require_file() {
  if [ ! -f "$1" ]; then
    fail "missing output file: $1"
  fi
}

for f in github-version.json cdn-version.json local_version.json latest_version.json cdn-latest-version.json receipt.json; do
  require_file "${TEST_ROOT}/outputs/${f}"
done

# local_version.json must be a copy of the GitHub manifest.
if ! cmp -s "${TEST_ROOT}/outputs/github-version.json" "${TEST_ROOT}/outputs/local_version.json"; then
  fail "local_version.json is not identical to github-version.json"
fi

# GitHub manifest must contain required top-level fields.
if [ "$(jq -r '.forceUpdate' "${TEST_ROOT}/outputs/github-version.json")" != "false" ]; then
  fail "GitHub manifest forceUpdate must be false"
fi

release_page_url=$(jq -r '.releasePageUrl' "${TEST_ROOT}/outputs/github-version.json")
expected_release_page="https://github.com/OtterMind/Chat2DB/releases/tag/v${VERSION}"
if [ "${release_page_url}" != "${expected_release_page}" ]; then
  fail "unexpected releasePageUrl: ${release_page_url}"
fi

# CDN manifest must omit GitHub-only top-level fields.
if jq -e 'has("releasePageUrl")' "${TEST_ROOT}/outputs/cdn-version.json" >/dev/null; then
  fail "CDN manifest must not contain releasePageUrl"
fi
if jq -e 'has("forceUpdate")' "${TEST_ROOT}/outputs/cdn-version.json" >/dev/null; then
  fail "CDN manifest must not contain forceUpdate"
fi

# GitHub pointer must bind the full GitHub manifest without containing payload data.
github_pointer_fields="$(jq -r 'keys[]' "${TEST_ROOT}/outputs/latest_version.json" | sort | tr '\n' ' ')"
if [ "${github_pointer_fields}" != "forceUpdate metadataSha256 releaseNotes releasePageUrl version " ]; then
  fail "GitHub latest pointer fields changed: ${github_pointer_fields}"
fi
if [ "$(jq -r '.version' "${TEST_ROOT}/outputs/latest_version.json")" != "${VERSION}" ]; then
  fail "GitHub latest pointer version mismatch"
fi
if [ "$(jq -r '.metadataSha256' "${TEST_ROOT}/outputs/latest_version.json")" != "$(shasum -a 256 "${TEST_ROOT}/outputs/github-version.json" | awk '{print $1}')" ]; then
  fail "GitHub latest pointer metadata SHA mismatch"
fi
if jq -e 'has("files")' "${TEST_ROOT}/outputs/latest_version.json" >/dev/null; then
  fail "GitHub latest pointer must not include payload files"
fi

# CDN bridge pointer must keep legacy schema.
if jq -e 'has("metadataSha256")' "${TEST_ROOT}/outputs/cdn-latest-version.json" >/dev/null; then
  fail "legacy CDN pointer must not contain metadataSha256"
fi
pointer_fields="$(jq -r 'keys[]' "${TEST_ROOT}/outputs/cdn-latest-version.json" | sort | tr '\n' ' ')"
if [ "${pointer_fields}" != "forceUpdate latestVersion metadataUrl " ]; then
  fail "legacy CDN pointer fields changed: ${pointer_fields}"
fi
if [ "$(jq -r '.latestVersion' "${TEST_ROOT}/outputs/cdn-latest-version.json")" != "${VERSION}" ]; then
  fail "CDN pointer latestVersion mismatch"
fi
expected_metadata_url="https://cdn.chat2db-ai.com/community/updates/${VERSION}/version.json"
if [ "$(jq -r '.metadataUrl' "${TEST_ROOT}/outputs/cdn-latest-version.json")" != "${expected_metadata_url}" ]; then
  fail "CDN pointer metadataUrl mismatch"
fi

# All payload URLs must match their publication target.
for payload in chat2db-community.jar lib.zip dist.zip; do
  expected_github_url="https://github.com/OtterMind/Chat2DB/releases/download/v${VERSION}/${payload}"
  actual_github_url=$(jq -r --arg id "$payload" '.files[] | select(.serverFileName == $id) | .url' "${TEST_ROOT}/outputs/github-version.json")
  if [ "${actual_github_url}" != "${expected_github_url}" ]; then
    fail "GitHub URL mismatch for ${payload}: ${actual_github_url}"
  fi

  expected_cdn_url="https://cdn.chat2db-ai.com/community/updates/${VERSION}/${payload}"
  actual_cdn_url=$(jq -r --arg id "$payload" '.files[] | select(.serverFileName == $id) | .url' "${TEST_ROOT}/outputs/cdn-version.json")
  if [ "${actual_cdn_url}" != "${expected_cdn_url}" ]; then
    fail "CDN URL mismatch for ${payload}: ${actual_cdn_url}"
  fi
done

# Receipt must match the canonical schema.
if [ "$(jq -r '.version' "${TEST_ROOT}/outputs/receipt.json")" != "${VERSION}" ]; then
  fail "receipt version mismatch"
fi
if [ "$(jq '.files | length' "${TEST_ROOT}/outputs/receipt.json")" -ne 3 ]; then
  fail "receipt must list exactly three files"
fi
get_expected_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
  esac
}

for payload in chat2db-community.jar lib.zip dist.zip; do
  expected_id=$(get_expected_id "$payload")
  if ! jq -e --arg id "$expected_id" '.files[] | select(.id == $id)' "${TEST_ROOT}/outputs/receipt.json" >/dev/null; then
    fail "receipt missing entry for ${payload} (id=${expected_id})"
  fi
done

manifest_sha=$(shasum -a 256 "${TEST_ROOT}/outputs/github-version.json" | awk '{print $1}')
local_manifest_sha=$(shasum -a 256 "${TEST_ROOT}/outputs/local_version.json" | awk '{print $1}')
if [ "$(jq -r '.manifestSha256' "${TEST_ROOT}/outputs/receipt.json")" != "${manifest_sha}" ]; then
  fail "receipt manifestSha256 mismatch"
fi
if [ "$(jq -r '.localManifestSha256' "${TEST_ROOT}/outputs/receipt.json")" != "${local_manifest_sha}" ]; then
  fail "receipt localManifestSha256 mismatch"
fi

# Dual manifest identity check: semantic fields identical beyond approved differences.
normalized_equal=$(jq -n \
    --slurpfile github "${TEST_ROOT}/outputs/github-version.json" \
    --slurpfile cdn "${TEST_ROOT}/outputs/cdn-version.json" \
    '($github[0] | del(.releasePageUrl, .forceUpdate) | .files |= map(del(.url)))
     ==
     ($cdn[0] | .files |= map(del(.url)))')

if [[ "${normalized_equal}" != "true" ]]; then
  fail "GitHub/CDN manifest identity check failed"
fi

echo "[check] dual manifest identity, receipt schema, and legacy compatibility passed"
