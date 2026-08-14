#!/usr/bin/env bash
#
# verify_latest_release.sh
#
# Verify that a published Community desktop GitHub Release is publicly visible
# as Latest and that its latest-download version.json matches the staged bundle.
#
# Required environment:
#   GH_TOKEN                      GitHub CLI token (read-only is sufficient)
#   GH_REPO                       GitHub repository for gh CLI, e.g. owner/repo
#   TAG_NAME                      Git tag, e.g. v5.4.0
#   VERSION                       numeric SemVer, e.g. 5.4.0
#   REPO                          production update repository, e.g. OtterMind/Chat2DB
#   RELEASE_ASSETS_DIR            directory containing the staged release assets
#   ALLOWED_EXTRA_RELEASE_ASSETS  whitespace-separated list of allowed extras

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=release_asset_contract.sh
source "${SCRIPT_DIR}/release_asset_contract.sh"

GH_TOKEN="${GH_TOKEN:-}"
GH_REPO="${GH_REPO:-}"
TAG_NAME="${TAG_NAME:-}"
VERSION="${VERSION:-}"
REPO="${REPO:-OtterMind/Chat2DB}"
RELEASE_ASSETS_DIR="${RELEASE_ASSETS_DIR:-}"
ALLOWED_EXTRA_RELEASE_ASSETS="${ALLOWED_EXTRA_RELEASE_ASSETS:-}"

if [ -z "${GH_TOKEN}" ] || [ -z "${GH_REPO}" ] || [ -z "${TAG_NAME}" ] || [ -z "${VERSION}" ] || [ -z "${RELEASE_ASSETS_DIR}" ]; then
  echo "[error] missing required environment variable" >&2
  exit 1
fi

export GH_TOKEN
export GH_REPO

required_release_assets=(
  "version.json"
  "chat2db-community.jar"
  "lib.zip"
  "dist.zip"
  "Chat2DB-Community-${VERSION}-arm64.dmg"
  "Chat2DB-Community-${VERSION}-x64.dmg"
  "Chat2DB-Community-${VERSION}.msi"
  "Chat2DB-Community-${VERSION}-amd64.deb"
  "Chat2DB-Community-${VERSION}-arm64.deb"
  "Chat2DB-Community-${VERSION}-x86_64.rpm"
  "Chat2DB-Community-${VERSION}-aarch64.rpm"
  "Chat2DB-Community-${VERSION}-x86_64.AppImage"
  "Chat2DB-Community-${VERSION}-arm64.AppImage"
  "SHA256SUMS"
)

fail() {
  echo "[error] $*" >&2
  exit 1
}

# --- 1. Release is published, stable, and marked Latest ---------------------
echo "[check] Release ${TAG_NAME} is published and Latest"
is_draft="$(gh release view "${TAG_NAME}" --json isDraft --jq '.isDraft')"
if [ "${is_draft}" != "false" ]; then
  fail "Release ${TAG_NAME} is still a draft"
fi

is_latest="$(gh release view "${TAG_NAME}" --json isLatest --jq '.isLatest')"
if [ "${is_latest}" != "true" ]; then
  fail "Release ${TAG_NAME} is not marked Latest"
fi

# --- 2. Required assets exist exactly once ----------------------------------
echo "[check] required Release assets"
remote_assets=()
while IFS= read -r asset; do
  remote_assets+=("${asset}")
done < <(gh release view "${TAG_NAME}" --json assets --jq '.assets[].name' | sort)

for asset in "${required_release_assets[@]}"; do
  count="$(printf '%s\n' "${remote_assets[@]}" | grep -cx "${asset}" || true)"
  if [ "${count}" -ne 1 ]; then
    fail "required asset ${asset} must occur exactly once remotely, found ${count}"
  fi
done

allowlist_set=()
if [ -n "${ALLOWED_EXTRA_RELEASE_ASSETS}" ]; then
  read -r -a allowlist_set <<< "${ALLOWED_EXTRA_RELEASE_ASSETS//,/ }"
fi

for asset in "${remote_assets[@]}"; do
  if printf '%s\n' "${required_release_assets[@]}" | grep -qx "${asset}"; then
    continue
  fi
  if [ "${#allowlist_set[@]}" -gt 0 ] && printf '%s\n' "${allowlist_set[@]}" | grep -qx "${asset}"; then
    continue
  fi
  fail "unlisted extra remote asset: ${asset}"
done

# --- 3. /releases/latest resolves to the expected tag -----------------------
echo "[check] /releases/latest resolves to ${TAG_NAME}"
latest_url="https://github.com/${REPO}/releases/latest"
resolved_tag="$(curl -sSL --max-redirs 5 -o /dev/null -w '%{url_effective}' "${latest_url}" | sed 's/.*\/tag\///')"
if [ -z "${resolved_tag}" ]; then
  fail "could not resolve ${latest_url}"
fi
if [ "${resolved_tag}" != "${TAG_NAME}" ]; then
  fail "/releases/latest resolved to ${resolved_tag}, expected ${TAG_NAME}"
fi

# --- 4. Latest version.json matches staged metadata -------------------------
echo "[check] /releases/latest/download/version.json"
staged_manifest="${RELEASE_ASSETS_DIR}/version.json"
if [ ! -f "${staged_manifest}" ]; then
  fail "staged version.json not found"
fi

public_manifest="$(mktemp)"
trap 'rm -f "${public_manifest}"' EXIT

public_url="https://github.com/${REPO}/releases/latest/download/version.json"
curl -sSL --max-redirs 5 -o "${public_manifest}" "${public_url}"

if ! diff -q "${staged_manifest}" "${public_manifest}" >/dev/null; then
  fail "public version.json does not match staged version.json"
fi

# --- 5. Manifest version and payload URLs match vX.Y.Z ----------------------
echo "[check] manifest version and payload URLs"
public_version="$(jq -r '.version' "${public_manifest}")"
if [ "${public_version}" != "${VERSION}" ]; then
  fail "public manifest version mismatch: ${public_version} != ${VERSION}"
fi

expected_base="https://github.com/${REPO}/releases/download/${TAG_NAME}"
while IFS= read -r payload; do
  payload_id="$(payload_metadata_id "${payload}")"
  url="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .url' "${public_manifest}")"
  server_name="$(jq -r --arg id "${payload_id}" '.files[] | select(.id == $id) | .serverFileName' "${public_manifest}")"
  expected_url="${expected_base}/${server_name}"
  if [ "${url}" != "${expected_url}" ]; then
    fail "public payload URL mismatch for ${payload}: ${url} != ${expected_url}"
  fi
done <<<"$(printf '%s\n' chat2db-community.jar lib.zip dist.zip)"

# --- 6. Range request against an updater payload ---------------------------
echo "[check] updater payload Range request"
range_payload="chat2db-community.jar"
range_payload_url="${expected_base}/${range_payload}"
range_response="$(mktemp)"
range_headers="$(mktemp)"
trap 'rm -f "${public_manifest}" "${range_response}" "${range_headers}"' EXIT

expected_payload_size="$(wc -c < "${RELEASE_ASSETS_DIR}/${range_payload}" | tr -d '[:space:]')"
range_status="$(curl -sSL --max-redirs 5 -D "${range_headers}" -o "${range_response}" -w '%{http_code}' \
  -H 'Range: bytes=0-7' "${range_payload_url}")"
actual_range_size="$(wc -c < "${range_response}" | tr -d '[:space:]')"

case "${range_status}" in
  206)
    content_range="$(grep -i '^content-range:' "${range_headers}" | tail -n 1 | cut -d: -f2- | tr -d '\r' | xargs)"
    content_length="$(grep -i '^content-length:' "${range_headers}" | tail -n 1 | cut -d: -f2- | tr -d '\r' | xargs)"
    if [ "${content_range}" != "bytes 0-7/${expected_payload_size}" ]; then
      fail "Range Content-Range mismatch: ${content_range}"
    fi
    if [ "${content_length}" != "8" ] || [ "${actual_range_size}" != "8" ]; then
      fail "Range response length mismatch: header=${content_length}, body=${actual_range_size}"
    fi
    echo "[info] updater payload supports validated HTTP 206 Range responses"
    ;;
  200)
    content_length="$(grep -i '^content-length:' "${range_headers}" | tail -n 1 | cut -d: -f2- | tr -d '\r' | xargs)"
    if [ "${content_length}" != "${expected_payload_size}" ] || [ "${actual_range_size}" != "${expected_payload_size}" ]; then
      fail "Range-ignored payload response does not match the complete staged payload size"
    fi
    echo "[info] updater payload server ignored Range; verified full response for restart-from-zero handling"
    ;;
  *)
    fail "updater payload Range request returned unexpected HTTP ${range_status}"
    ;;
esac

echo "[done] public latest release verified for ${TAG_NAME}"
