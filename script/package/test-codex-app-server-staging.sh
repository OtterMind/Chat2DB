#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "${WORK_DIR}"' EXIT

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  fi
}

case "$(uname -m)" in
  arm64|aarch64) ARCH=arm64 ;;
  x86_64|amd64) ARCH=x64 ;;
  *) echo "unsupported test architecture" >&2; exit 1 ;;
esac

ASSET_DIR="${WORK_DIR}/assets"
SOURCE_DIR="${WORK_DIR}/source"
STAGE_ROOT="${WORK_DIR}/stage"
mkdir -p "${ASSET_DIR}" "${SOURCE_DIR}" "${STAGE_ROOT}"
printf '%s\n' 'fixture app-server binary' > "${SOURCE_DIR}/codex-app-server-test"
printf '%s\n' 'fixture Apache-2.0 license' > "${ASSET_DIR}/LICENSE.openai-codex"
tar -czf "${ASSET_DIR}/fixture.tar.gz" -C "${SOURCE_DIR}" codex-app-server-test

ARCHIVE_SHA=$(sha256_file "${ASSET_DIR}/fixture.tar.gz")
LICENSE_SHA=$(sha256_file "${ASSET_DIR}/LICENSE.openai-codex")
MANIFEST="${WORK_DIR}/fixture.manifest"
{
  printf '%s\n' 'schemaVersion=1'
  printf '%s\n' 'version=0.0.0-test'
  printf '%s\n' 'protocolLabel=test-jsonl-v2'
  printf '%s\n' 'licenseSpdx=Apache-2.0'
  printf '%s\n' 'licenseUrl=https://invalid.example.test/LICENSE'
  printf '%s\n' "licenseSha256=${LICENSE_SHA}"
  printf 'mac\t%s\tfixture.tar.gz\t%s\tcodex-app-server-test\tcodex-app-server\n' \
    "${ARCH}" "${ARCHIVE_SHA}"
} > "${MANIFEST}"

CODEX_APP_SERVER_RELEASE_MANIFEST="${MANIFEST}" \
CODEX_APP_SERVER_ASSET_DIR="${ASSET_DIR}" \
  bash "${SCRIPT_DIR}/stage-codex-app-server.sh" mac "${STAGE_ROOT}"

test -x "${STAGE_ROOT}/codex-app-server/codex-app-server"
test -f "${STAGE_ROOT}/codex-app-server/LICENSE.openai-codex"
grep -Fxq 'version=0.0.0-test' "${STAGE_ROOT}/codex-app-server/runtime.properties"
grep -Eq '^binarySha256=[0-9a-f]{64}$' "${STAGE_ROOT}/codex-app-server/runtime.properties"

printf '%s\n' 'tampered archive' >> "${ASSET_DIR}/fixture.tar.gz"
if CODEX_APP_SERVER_RELEASE_MANIFEST="${MANIFEST}" \
  CODEX_APP_SERVER_ASSET_DIR="${ASSET_DIR}" \
  bash "${SCRIPT_DIR}/stage-codex-app-server.sh" mac "${WORK_DIR}/tampered"; then
  echo "[error] tampered Codex app-server archive was accepted" >&2
  exit 1
fi

echo "[check] Codex app-server staging manifest and checksum gates passed"
