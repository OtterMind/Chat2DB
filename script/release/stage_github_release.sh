#!/usr/bin/env bash
#
# stage_github_release.sh
#
# Create or refresh a draft GitHub Release containing the validated Community
# desktop release bundle. Refuses to mutate an already-published Release.
#
# Required environment:
#   GH_TOKEN                      GitHub CLI token
#   GH_REPO                       GitHub repository, e.g. owner/repo
#   TAG_NAME                      Git tag, e.g. v5.4.0
#   VERSION                       numeric SemVer, e.g. 5.4.0
#   RELEASE_ASSETS_DIR            directory containing validated release assets
#   ALLOWED_EXTRA_RELEASE_ASSETS  whitespace-separated list of allowed extras

set -euo pipefail

GH_TOKEN="${GH_TOKEN:-}"
GH_REPO="${GH_REPO:-}"
TAG_NAME="${TAG_NAME:-}"
VERSION="${VERSION:-}"
RELEASE_ASSETS_DIR="${RELEASE_ASSETS_DIR:-}"
ALLOWED_EXTRA_RELEASE_ASSETS="${ALLOWED_EXTRA_RELEASE_ASSETS:-}"

if [ -z "${GH_TOKEN}" ] || [ -z "${GH_REPO}" ] || [ -z "${TAG_NAME}" ] || [ -z "${VERSION}" ] || [ -z "${RELEASE_ASSETS_DIR}" ]; then
  echo "[error] missing required environment variable" >&2
  exit 1
fi

export GH_TOKEN
export GH_REPO
RELEASE_TITLE="Chat2DB v${VERSION}"

required_release_assets=(
  "latest_version.json"
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

# --- 1. Local asset inventory -----------------------------------------------
if [ ! -d "${RELEASE_ASSETS_DIR}" ]; then
  fail "release assets directory not found: ${RELEASE_ASSETS_DIR}"
fi

mapfile -t local_assets < <(find "${RELEASE_ASSETS_DIR}" -maxdepth 1 -type f -printf '%f\n' | sort)
if [ "${#local_assets[@]}" -lt "${#required_release_assets[@]}" ]; then
  fail "expected at least ${#required_release_assets[@]} assets, found ${#local_assets[@]}"
fi

for asset in "${required_release_assets[@]}"; do
  count="$(printf '%s\n' "${local_assets[@]}" | grep -cx "${asset}" || true)"
  if [ "${count}" -ne 1 ]; then
    fail "required asset ${asset} must occur exactly once locally, found ${count}"
  fi
  if [ ! -s "${RELEASE_ASSETS_DIR}/${asset}" ]; then
    fail "required asset ${asset} is empty"
  fi
done

allowlist_set=()
if [ -n "${ALLOWED_EXTRA_RELEASE_ASSETS}" ]; then
  read -r -a allowlist_set <<< "${ALLOWED_EXTRA_RELEASE_ASSETS//,/ }"
fi

for asset in "${local_assets[@]}"; do
  if printf '%s\n' "${required_release_assets[@]}" | grep -qx "${asset}"; then
    continue
  fi
  if [ "${#allowlist_set[@]}" -gt 0 ] && printf '%s\n' "${allowlist_set[@]}" | grep -qx "${asset}"; then
    continue
  fi
  fail "unlisted extra local asset: ${asset}"
done

echo "[info] local assets:"
printf ' - %s\n' "${local_assets[@]}"

# --- 2. Refuse to mutate a published Release --------------------------------
if draft=$(gh release view "${TAG_NAME}" --json isDraft --jq '.isDraft' 2>/dev/null); then
  if [ "${draft}" != "true" ]; then
    fail "Release ${TAG_NAME} is already published; refusing to replace assets"
  fi
  echo "[info] refreshing existing draft Release ${TAG_NAME}"
  gh release edit "${TAG_NAME}" --title "${RELEASE_TITLE}"
  gh release upload "${TAG_NAME}" "${RELEASE_ASSETS_DIR}"/* --clobber
else
  echo "[info] creating draft Release ${TAG_NAME}"
  gh release create "${TAG_NAME}" "${RELEASE_ASSETS_DIR}"/* \
    --verify-tag \
    --draft \
    --generate-notes \
    --title "${RELEASE_TITLE}"
fi

if [ "$(gh release view "${TAG_NAME}" --json name --jq '.name')" != "${RELEASE_TITLE}" ]; then
  fail "Release title does not match ${RELEASE_TITLE}"
fi

# --- 3. Verify remote asset inventory matches local -------------------------
echo "[info] verifying remote Release assets"
mapfile -t remote_assets < <(gh release view "${TAG_NAME}" --json assets --jq '.assets[].name' | sort)

printf '%s\n' "${local_assets[@]}" | sort > "${RUNNER_TEMP:-/tmp}/expected-assets.txt"
printf '%s\n' "${remote_assets[@]}" | sort > "${RUNNER_TEMP:-/tmp}/actual-assets.txt"

if ! diff -u "${RUNNER_TEMP:-/tmp}/expected-assets.txt" "${RUNNER_TEMP:-/tmp}/actual-assets.txt"; then
  fail "remote Release assets do not match local bundle"
fi

echo "[done] draft Release ${TAG_NAME} staged with ${#remote_assets[@]} assets"
