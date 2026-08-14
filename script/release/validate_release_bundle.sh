#!/usr/bin/env bash
#
# validate_release_bundle.sh
#
# Validate the assembled Community desktop release bundle before any Release or
# CDN write happens. Exits non-zero on any contract violation.
#
# Required environment:
#   VERSION                    numeric SemVer, e.g. 5.4.0
#   TAG_NAME                   Git tag, e.g. v5.4.0
#   REPO                       GitHub repository, e.g. OtterMind/Chat2DB
#   CANONICAL_ARTIFACT_DIR     path to the downloaded canonical artifact
#   PLATFORM_ARTIFACTS_DIR     path to downloaded native installer artifacts
#   IS_BRIDGE_N                "true" only for the bridge release
#   BRIDGE_VERSION             optional, the configured bridge version
#   CDN_BASE_URL               legacy CDN base URL for bridge manifest check
#   ALLOWED_EXTRA_RELEASE_ASSETS  whitespace-separated list of allowed extras
#
# On success, creates release-assets/ containing the 14 required Release Assets.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=release_asset_contract.sh
source "${SCRIPT_DIR}/release_asset_contract.sh"

VERSION="${VERSION:-}"
TAG_NAME="${TAG_NAME:-}"
REPO="${REPO:-OtterMind/Chat2DB}"
CANONICAL_ARTIFACT_DIR="${CANONICAL_ARTIFACT_DIR:-}"
PLATFORM_ARTIFACTS_DIR="${PLATFORM_ARTIFACTS_DIR:-}"
IS_BRIDGE_N="${IS_BRIDGE_N:-false}"
BRIDGE_VERSION="${BRIDGE_VERSION:-}"
CDN_BASE_URL="${CDN_BASE_URL:-https://cdn.chat2db-ai.com/community/updates}"
ALLOWED_EXTRA_RELEASE_ASSETS="${ALLOWED_EXTRA_RELEASE_ASSETS:-}"

if [ -z "${VERSION}" ] || [ -z "${TAG_NAME}" ] || [ -z "${CANONICAL_ARTIFACT_DIR}" ] || [ -z "${PLATFORM_ARTIFACTS_DIR}" ]; then
  echo "[error] missing required environment variable" >&2
  exit 1
fi

required_installers=(
  "Chat2DB-Community-${VERSION}-arm64.dmg"
  "Chat2DB-Community-${VERSION}-x64.dmg"
  "Chat2DB-Community-${VERSION}.msi"
  "Chat2DB-Community-${VERSION}-amd64.deb"
  "Chat2DB-Community-${VERSION}-arm64.deb"
  "Chat2DB-Community-${VERSION}-x86_64.rpm"
  "Chat2DB-Community-${VERSION}-aarch64.rpm"
  "Chat2DB-Community-${VERSION}-x86_64.AppImage"
  "Chat2DB-Community-${VERSION}-arm64.AppImage"
)

required_updater_assets=(
  "version.json"
  "chat2db-community.jar"
  "lib.zip"
  "dist.zip"
)

required_release_assets=(
  "${required_updater_assets[@]}"
  "${required_installers[@]}"
  "SHA256SUMS"
)

fail() {
  echo "[error] $*" >&2
  exit 1
}

require_file() {
  if [ ! -f "$1" ]; then
    fail "missing file: $1"
  fi
  if [ ! -s "$1" ]; then
    fail "empty file: $1"
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_size() {
  if [ "$(uname)" = "Darwin" ]; then
    stat -f %z "$1"
  else
    stat -c %s "$1"
  fi
}

# --- 1. Canonical artifact exists and contains required files ---------------
echo "[check] canonical artifact in ${CANONICAL_ARTIFACT_DIR}"
for name in "${required_updater_assets[@]}" receipt.json local_version.json; do
  require_file "${CANONICAL_ARTIFACT_DIR}/${name}"
done

# --- 2. Canonical receipt is valid JSON and matches staged files ------------
receipt="${CANONICAL_ARTIFACT_DIR}/receipt.json"
echo "[check] canonical receipt"
if ! jq -e '.' "${receipt}" >/dev/null; then
  fail "receipt.json is not valid JSON"
fi

receipt_version="$(jq -r '.version' "${receipt}")"
if [ "${receipt_version}" != "${VERSION}" ]; then
  fail "receipt version mismatch: ${receipt_version} != ${VERSION}"
fi

for payload in chat2db-community.jar lib.zip dist.zip; do
  payload_id="$(payload_metadata_id "${payload}")"
  expected_sha="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .sha256' "${receipt}")"
  expected_size="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .size' "${receipt}")"
  actual_sha="$(sha256_file "${CANONICAL_ARTIFACT_DIR}/${payload}")"
  actual_size="$(file_size "${CANONICAL_ARTIFACT_DIR}/${payload}")"
  if [ "${expected_sha}" != "${actual_sha}" ]; then
    fail "canonical receipt SHA-256 mismatch for ${payload}"
  fi
  if [ "${expected_size}" != "${actual_size}" ]; then
    fail "canonical receipt size mismatch for ${payload}"
  fi
done

manifest_sha="$(sha256_file "${CANONICAL_ARTIFACT_DIR}/version.json")"
local_manifest_sha="$(sha256_file "${CANONICAL_ARTIFACT_DIR}/local_version.json")"
if [ "$(jq -r '.manifestSha256' "${receipt}")" != "${manifest_sha}" ]; then
  fail "canonical receipt manifestSha256 mismatch"
fi
if [ "$(jq -r '.localManifestSha256' "${receipt}")" != "${local_manifest_sha}" ]; then
  fail "canonical receipt localManifestSha256 mismatch"
fi

# --- 3. Platform installer artifacts exist and are nonempty -----------------
echo "[check] native installer artifacts"
mkdir -p release-assets
for asset in "${required_installers[@]}"; do
  matches=()
  while IFS= read -r -d '' match; do
    matches+=("${match}")
  done < <(find "${PLATFORM_ARTIFACTS_DIR}" -type f -name "${asset}" -print0)

  if [ "${#matches[@]}" -ne 1 ]; then
    fail "expected exactly one ${asset}, found ${#matches[@]}"
  fi
  cp "${matches[0]}" "release-assets/${asset}"
done

# --- 4. Platform receipts, if present, match canonical receipt --------------
echo "[check] platform receipts"
platform_receipt_count=0
while IFS= read -r -d '' receipt_file; do
  platform_receipt_count=$((platform_receipt_count + 1))
  if ! diff -q "${receipt}" "${receipt_file}" >/dev/null; then
    fail "platform receipt mismatch: ${receipt_file}"
  fi
done < <(find "${PLATFORM_ARTIFACTS_DIR}" -type f -name 'receipt.json' -print0)

if [ "${platform_receipt_count}" -eq 0 ]; then
  echo "[warn] no platform receipt.json files found; relying on canonical receipt"
fi

# --- 5. GitHub version.json contract ----------------------------------------
echo "[check] GitHub version.json"
github_manifest="${CANONICAL_ARTIFACT_DIR}/version.json"
if ! jq -e '.' "${github_manifest}" >/dev/null; then
  fail "version.json is not valid JSON"
fi

manifest_version="$(jq -r '.version' "${github_manifest}")"
if [ "${manifest_version}" != "${VERSION}" ]; then
  fail "version.json version mismatch: ${manifest_version} != ${VERSION}"
fi

force_update="$(jq -r '.forceUpdate' "${github_manifest}")"
if [ "${force_update}" != "false" ]; then
  fail "version.json forceUpdate must be false, got ${force_update}"
fi

release_page_url="$(jq -r '.releasePageUrl // empty' "${github_manifest}")"
expected_release_page="https://github.com/${REPO}/releases/tag/${TAG_NAME}"
if [ -n "${release_page_url}" ] && [ "${release_page_url}" != "${expected_release_page}" ]; then
  fail "version.json releasePageUrl mismatch: ${release_page_url} != ${expected_release_page}"
fi

file_ids="$(jq -r '.files[].id' "${github_manifest}" | sort)"
expected_ids="$(for payload in chat2db-community.jar lib.zip dist.zip; do payload_metadata_id "${payload}"; done | sort)"
if [ "${file_ids}" != "${expected_ids}" ]; then
  fail "version.json file IDs mismatch"
fi

# --- 6. Payload URL / size / SHA-256 validation -----------------------------
echo "[check] payload metadata"
expected_base="https://github.com/${REPO}/releases/download/${TAG_NAME}"
while IFS= read -r payload; do
  payload_id="$(payload_metadata_id "${payload}")"
  expected_sha="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .sha256' "${receipt}")"
  expected_size="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .size' "${receipt}")"
  url="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .url' "${github_manifest}")"
  server_name="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .serverFileName' "${github_manifest}")"

  expected_url="${expected_base}/${server_name}"
  if [ "${url}" != "${expected_url}" ]; then
    fail "payload URL mismatch for ${payload}: ${url} != ${expected_url}"
  fi

  actual_size="$(file_size "${CANONICAL_ARTIFACT_DIR}/${payload}")"
  if [ "${expected_size}" != "${actual_size}" ]; then
    fail "version.json size mismatch for ${payload}"
  fi

  actual_sha="$(sha256_file "${CANONICAL_ARTIFACT_DIR}/${payload}")"
  if [ "${expected_sha}" != "${actual_sha}" ]; then
    fail "version.json SHA-256 mismatch for ${payload}"
  fi
done <<<"$(printf '%s\n' chat2db-community.jar lib.zip dist.zip)"

# --- 7. Copy updater assets into release bundle -----------------------------
for asset in "${required_updater_assets[@]}"; do
  cp "${CANONICAL_ARTIFACT_DIR}/${asset}" "release-assets/${asset}"
done

# --- 8. Regenerate SHA256SUMS over the 13 non-SHA256SUMS required assets -----
echo "[check] SHA256SUMS"
(
  cd release-assets
  for asset in "${required_updater_assets[@]}" "${required_installers[@]}"; do
    printf '%s  %s\n' "$(sha256_file "${asset}")" "${asset}"
  done > SHA256SUMS
)

# --- 9. Required asset uniqueness and extras allowlist ----------------------
echo "[check] required assets and extras allowlist"
found_assets=()
while IFS= read -r asset; do
  found_assets+=("${asset}")
done < <(find release-assets -maxdepth 1 -type f -exec basename {} \; | sort)

for asset in "${required_release_assets[@]}"; do
  count="$(printf '%s\n' "${found_assets[@]}" | grep -cx "${asset}" || true)"
  if [ "${count}" -ne 1 ]; then
    fail "required asset ${asset} must occur exactly once, found ${count}"
  fi
done

# Build allowlist set (whitespace and comma separated).
allowlist_set=()
if [ -n "${ALLOWED_EXTRA_RELEASE_ASSETS}" ]; then
  read -r -a allowlist_set <<< "${ALLOWED_EXTRA_RELEASE_ASSETS//,/ }"
fi

for found in "${found_assets[@]}"; do
  if printf '%s\n' "${required_release_assets[@]}" | grep -qx "${found}"; then
    continue
  fi
  if [ "${#allowlist_set[@]}" -gt 0 ] && printf '%s\n' "${allowlist_set[@]}" | grep -qx "${found}"; then
    continue
  fi
  fail "unlisted extra asset: ${found}"
done

# --- 10. Bridge N: validate GitHub/CDN manifest allowed differences ---------
if [ "${IS_BRIDGE_N}" = "true" ]; then
  echo "[check] bridge N GitHub/CDN manifest equivalence"
  cdn_manifest="${CANONICAL_ARTIFACT_DIR}/cdn-version.json"
  if [ ! -f "${cdn_manifest}" ]; then
    # Generate a CDN manifest from the GitHub manifest for validation.
    cdn_manifest="$(mktemp)"
    trap 'rm -f "${cdn_manifest}"' EXIT
    jq --arg base "${CDN_BASE_URL}/${VERSION}" \
      'del(.releasePageUrl, .forceUpdate) | .files |= map(.url = "\($base)/\(.serverFileName)")' \
      "${github_manifest}" > "${cdn_manifest}"
  fi

  # Compare legacy semantic fields.
  for field in version releaseNotes; do
    gh_value="$(jq -r ".${field} // empty" "${github_manifest}")"
    cdn_value="$(jq -r ".${field} // empty" "${cdn_manifest}")"
    if [ "${gh_value}" != "${cdn_value}" ]; then
      fail "bridge manifest ${field} mismatch: GitHub=${gh_value} CDN=${cdn_value}"
    fi
  done

  gh_files="$(jq -c '.files | map({id, serverFileName, localTargetName, sha256, fileSizeByte, type, deleted})' "${github_manifest}")"
  cdn_files="$(jq -c '.files | map({id, serverFileName, localTargetName, sha256, fileSizeByte, type, deleted})' "${cdn_manifest}")"
  if [ "${gh_files}" != "${cdn_files}" ]; then
    fail "bridge manifest semantic file fields differ"
  fi

  # Verify CDN URLs.
  while IFS= read -r payload; do
    payload_id="$(payload_metadata_id "${payload}")"
    url="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .url' "${cdn_manifest}")"
    server_name="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .serverFileName' "${cdn_manifest}")"
    expected_url="${CDN_BASE_URL}/${VERSION}/${server_name}"
    if [ "${url}" != "${expected_url}" ]; then
      fail "bridge CDN payload URL mismatch for ${payload}: ${url} != ${expected_url}"
    fi
  done <<<"$(printf '%s\n' chat2db-community.jar lib.zip dist.zip)"

  # GitHub-only fields must be absent from CDN manifest.
  if jq -e 'has("releasePageUrl")' "${cdn_manifest}" >/dev/null; then
    fail "CDN manifest must not contain releasePageUrl"
  fi
  if jq -e 'has("forceUpdate")' "${cdn_manifest}" >/dev/null; then
    fail "CDN manifest must not contain forceUpdate"
  fi
fi

echo "[done] release bundle validated"
echo "[info] release-assets:"
ls -la release-assets
