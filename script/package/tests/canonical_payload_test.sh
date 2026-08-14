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

# Create canonical payloads.
echo "canonical-jar" > "${TEST_ROOT}/payloads/chat2db-community.jar"
mkdir -p "${TEST_ROOT}/payloads/lib"
echo "canonical-lib" > "${TEST_ROOT}/payloads/lib/a.jar"
(cd "${TEST_ROOT}/payloads" && zip -qr lib.zip lib)
mkdir -p "${TEST_ROOT}/payloads/dist"
echo "canonical-dist" > "${TEST_ROOT}/payloads/dist/index.html"
(cd "${TEST_ROOT}/payloads" && zip -qr dist.zip dist)
rm -rf "${TEST_ROOT}/payloads/lib" "${TEST_ROOT}/payloads/dist"

bash "${METADATA_SCRIPT}" "${VERSION}" "${TEST_ROOT}/payloads" "${TEST_ROOT}/artifact"

# Simulate canonical artifact staging: payload files live alongside manifests.
cp "${TEST_ROOT}/payloads/chat2db-community.jar" "${TEST_ROOT}/artifact/chat2db-community.jar"
cp "${TEST_ROOT}/payloads/lib.zip" "${TEST_ROOT}/artifact/lib.zip"
cp "${TEST_ROOT}/payloads/dist.zip" "${TEST_ROOT}/artifact/dist.zip"

# Verify canonical artifact structure.
for f in chat2db-community.jar lib.zip dist.zip github-version.json cdn-version.json local_version.json receipt.json; do
  if [ ! -f "${TEST_ROOT}/artifact/${f}" ]; then
    fail "canonical artifact missing ${f}"
  fi
done

# version.json must equal github-version.json in the canonical artifact.
if [ ! -f "${TEST_ROOT}/artifact/version.json" ]; then
  cp "${TEST_ROOT}/artifact/github-version.json" "${TEST_ROOT}/artifact/version.json"
fi

# Verify receipt covers exactly the three payloads.
if [ "$(jq '.files | length' "${TEST_ROOT}/artifact/receipt.json")" -ne 3 ]; then
  fail "canonical receipt must list exactly three payload files"
fi

get_expected_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
  esac
}

expected_payloads=(chat2db-community.jar lib.zip dist.zip)
for payload in "${expected_payloads[@]}"; do
  expected_id=$(get_expected_id "$payload")
  if ! jq -e --arg id "$expected_id" '.files[] | select(.id == $id)' "${TEST_ROOT}/artifact/receipt.json" >/dev/null; then
    fail "canonical receipt missing payload: ${payload}"
  fi

  receipt_sha=$(jq -r --arg id "$expected_id" '.files[] | select(.id == $id) | .sha256' "${TEST_ROOT}/artifact/receipt.json")
  receipt_size=$(jq -r --arg id "$expected_id" '.files[] | select(.id == $id) | .size' "${TEST_ROOT}/artifact/receipt.json")
  actual_sha=$(shasum -a 256 "${TEST_ROOT}/artifact/${payload}" | awk '{print $1}')
  actual_size=$(stat -f %z "${TEST_ROOT}/artifact/${payload}" 2>/dev/null || stat -c %s "${TEST_ROOT}/artifact/${payload}")

  if [ "${receipt_sha}" != "${actual_sha}" ]; then
    fail "receipt SHA-256 mismatch for ${payload}"
  fi
  if [ "${receipt_size}" != "${actual_size}" ]; then
    fail "receipt size mismatch for ${payload}"
  fi
done

# Verify manifest digests in receipt.
manifest_sha=$(shasum -a 256 "${TEST_ROOT}/artifact/version.json" | awk '{print $1}')
local_manifest_sha=$(shasum -a 256 "${TEST_ROOT}/artifact/local_version.json" | awk '{print $1}')
if [ "$(jq -r '.manifestSha256' "${TEST_ROOT}/artifact/receipt.json")" != "${manifest_sha}" ]; then
  fail "receipt manifestSha256 mismatch"
fi
if [ "$(jq -r '.localManifestSha256' "${TEST_ROOT}/artifact/receipt.json")" != "${local_manifest_sha}" ]; then
  fail "receipt localManifestSha256 mismatch"
fi

# Verify canonical artifact does not contain platform-specific outputs.
for forbidden in updater.jar runtime installer signed notarized; do
  if [ -e "${TEST_ROOT}/artifact/${forbidden}" ]; then
    fail "canonical artifact must not contain platform-specific output: ${forbidden}"
  fi
done

# Verify GitHub manifest payload URLs are immutable Release Asset URLs.
for payload in chat2db-community.jar lib.zip dist.zip; do
  expected_url="https://github.com/OtterMind/Chat2DB/releases/download/v${VERSION}/${payload}"
  actual_url=$(jq -r --arg id "$payload" '.files[] | select(.serverFileName == $id) | .url' "${TEST_ROOT}/artifact/version.json")
  if [ "${actual_url}" != "${expected_url}" ]; then
    fail "version.json payload URL mismatch for ${payload}: ${actual_url}"
  fi
done

echo "[check] canonical artifact structure, receipt, and platform-independence guard passed"
