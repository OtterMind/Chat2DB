#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
METADATA_SCRIPT="${PROJECT_ROOT}/script/package/generate_metadata.sh"
VALIDATE_SCRIPT="${PROJECT_ROOT}/script/release/validate_release_bundle.sh"
TEST_ROOT=$(mktemp -d)
VERSION="5.4.0"
TAG_NAME="v${VERSION}"

cleanup() {
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  echo "[error] $*" >&2
  exit 1
}

CANONICAL_DIR="${TEST_ROOT}/canonical"
PLATFORM_DIR="${TEST_ROOT}/platform"
WORK_DIR="${TEST_ROOT}/work"
mkdir -p "${CANONICAL_DIR}" "${PLATFORM_DIR}" "${WORK_DIR}"

printf 'canonical-jar\n' > "${CANONICAL_DIR}/chat2db-community.jar"
python3 - "${CANONICAL_DIR}" <<'PY'
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root / "lib.zip", "w") as archive:
    archive.writestr("lib/a.jar", "canonical-lib\n")
with zipfile.ZipFile(root / "dist.zip", "w") as archive:
    archive.writestr("dist/index.html", "canonical-dist\n")
PY

bash "${METADATA_SCRIPT}" "${VERSION}" "${CANONICAL_DIR}" "${CANONICAL_DIR}"
cp "${CANONICAL_DIR}/github-version.json" "${CANONICAL_DIR}/version.json"

for installer in \
  "Chat2DB-Community-${VERSION}-arm64.dmg" \
  "Chat2DB-Community-${VERSION}-x64.dmg" \
  "Chat2DB-Community-${VERSION}.msi" \
  "Chat2DB-Community-${VERSION}-amd64.deb" \
  "Chat2DB-Community-${VERSION}-arm64.deb" \
  "Chat2DB-Community-${VERSION}-x86_64.rpm" \
  "Chat2DB-Community-${VERSION}-aarch64.rpm" \
  "Chat2DB-Community-${VERSION}-x86_64.AppImage" \
  "Chat2DB-Community-${VERSION}-arm64.AppImage"; do
  printf 'installer\n' > "${PLATFORM_DIR}/${installer}"
done

(
  cd "${WORK_DIR}"
  VERSION="${VERSION}" \
  TAG_NAME="${TAG_NAME}" \
  REPO="OtterMind/Chat2DB" \
  CANONICAL_ARTIFACT_DIR="${CANONICAL_DIR}" \
  PLATFORM_ARTIFACTS_DIR="${PLATFORM_DIR}" \
  bash "${VALIDATE_SCRIPT}"
)

for payload in chat2db-community.jar lib.zip dist.zip; do
  if [ ! -s "${WORK_DIR}/release-assets/${payload}" ]; then
    fail "validated release bundle is missing ${payload}"
  fi
done

expected_ids=$(printf '%s\n' chat2db-community-server chat2db-community-lib chat2db-web | sort)
actual_ids=$(jq -r '.files[].id' "${CANONICAL_DIR}/version.json" | sort)
if [ "${actual_ids}" != "${expected_ids}" ]; then
  fail "fixture manifest did not preserve stable metadata IDs"
fi

echo "[check] release bundle accepts stable metadata IDs while publishing named assets"
