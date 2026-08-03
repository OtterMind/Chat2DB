#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <mac|linux|win> <jpackage-platform-input-dir>" >&2
  exit 1
fi

TARGET="$1"
PLATFORM_INPUT_DIR="$2"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MANIFEST="${CODEX_APP_SERVER_RELEASE_MANIFEST:-${SCRIPT_DIR}/codex-app-server-release.manifest}"
RELEASE_BASE_URL="https://github.com/openai/codex/releases/download"
WORK_DIR=""

cleanup() {
  if [ -n "${WORK_DIR}" ]; then
    rm -rf "${WORK_DIR}"
  fi
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "${file}" | awk '{print $NF}'
  else
    echo "[error] no SHA-256 command available" >&2
    exit 1
  fi
}

manifest_value() {
  local key="$1"
  awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "${MANIFEST}"
}

detect_arch() {
  local machine_arch
  machine_arch=$(uname -m)
  case "${machine_arch}" in
    arm64|aarch64) echo arm64 ;;
    x86_64|amd64) echo x64 ;;
    *)
      echo "[error] unsupported architecture: ${machine_arch}" >&2
      exit 1
      ;;
  esac
}

require_command awk
require_command curl
require_command tar
if [ ! -f "${MANIFEST}" ]; then
  echo "[error] Codex app-server release manifest not found: ${MANIFEST}" >&2
  exit 1
fi

case "${TARGET}" in
  mac|linux|win) ;;
  *)
    echo "[error] unsupported Codex app-server target: ${TARGET}" >&2
    exit 1
    ;;
esac

ARCH=$(detect_arch)
if [ "${TARGET}" = "win" ]; then
  # The Community Windows workflow currently produces x64 installers only.
  ARCH=x64
fi

ROW=$(awk -F '\t' -v target="${TARGET}" -v arch="${ARCH}" \
  'NF == 6 && $1 == target && $2 == arch { print; exit }' "${MANIFEST}")
if [ -z "${ROW}" ]; then
  echo "[error] no pinned Codex app-server asset for ${TARGET}/${ARCH}" >&2
  exit 1
fi

IFS=$'\t' read -r _ _ ASSET ARCHIVE_SHA ARCHIVE_ENTRY RUNTIME_BINARY <<< "${ROW}"

VERSION=$(manifest_value version)
SCHEMA_VERSION=$(manifest_value schemaVersion)
PROTOCOL_LABEL=$(manifest_value protocolLabel)
LICENSE_SPDX=$(manifest_value licenseSpdx)
LICENSE_URL=$(manifest_value licenseUrl)
LICENSE_SHA=$(manifest_value licenseSha256)

for required in VERSION SCHEMA_VERSION PROTOCOL_LABEL LICENSE_SPDX LICENSE_URL LICENSE_SHA; do
  if [ -z "${!required}" ]; then
    echo "[error] missing ${required} in Codex app-server release manifest" >&2
    exit 1
  fi
done

WORK_DIR=$(mktemp -d)
ARCHIVE_PATH="${WORK_DIR}/${ASSET}"
LICENSE_PATH="${WORK_DIR}/LICENSE.openai-codex"
EXTRACT_DIR="${WORK_DIR}/extract"
mkdir -p "${EXTRACT_DIR}"

if [ -n "${CODEX_APP_SERVER_ASSET_DIR:-}" ]; then
  cp "${CODEX_APP_SERVER_ASSET_DIR}/${ASSET}" "${ARCHIVE_PATH}"
  cp "${CODEX_APP_SERVER_ASSET_DIR}/LICENSE.openai-codex" "${LICENSE_PATH}"
else
  echo "[run] download pinned Codex app-server ${VERSION} for ${TARGET}/${ARCH}"
  curl --fail --location --retry 2 \
    --output "${ARCHIVE_PATH}" \
    "${RELEASE_BASE_URL}/rust-v${VERSION}/${ASSET}"
  curl --fail --location --retry 2 \
    --output "${LICENSE_PATH}" \
    "${LICENSE_URL}"
fi

if [ "$(sha256_file "${ARCHIVE_PATH}")" != "${ARCHIVE_SHA}" ]; then
  echo "[error] Codex app-server archive SHA-256 mismatch for ${ASSET}" >&2
  exit 1
fi
if [ "$(sha256_file "${LICENSE_PATH}")" != "${LICENSE_SHA}" ]; then
  echo "[error] OpenAI Codex license SHA-256 mismatch" >&2
  exit 1
fi

tar -xzf "${ARCHIVE_PATH}" -C "${EXTRACT_DIR}"
SOURCE_BINARY="${EXTRACT_DIR}/${ARCHIVE_ENTRY}"
if [ ! -f "${SOURCE_BINARY}" ]; then
  echo "[error] pinned app-server archive entry missing: ${ARCHIVE_ENTRY}" >&2
  exit 1
fi

STAGE_DIR="${PLATFORM_INPUT_DIR}/codex-app-server"
rm -rf "${STAGE_DIR}"
mkdir -p "${STAGE_DIR}"
cp "${SOURCE_BINARY}" "${STAGE_DIR}/${RUNTIME_BINARY}"
chmod 0755 "${STAGE_DIR}/${RUNTIME_BINARY}"
cp "${LICENSE_PATH}" "${STAGE_DIR}/LICENSE.openai-codex"

BINARY_SHA=$(sha256_file "${STAGE_DIR}/${RUNTIME_BINARY}")
MANIFEST_SHA=$(sha256_file "${MANIFEST}")
cat > "${STAGE_DIR}/runtime.properties" <<EOF
schemaVersion=${SCHEMA_VERSION}
provider=OPENAI
accessType=SUBSCRIPTION
version=${VERSION}
protocolLabel=${PROTOCOL_LABEL}
binary=${RUNTIME_BINARY}
binarySha256=${BINARY_SHA}
releaseManifestSha256=${MANIFEST_SHA}
licenseSpdx=${LICENSE_SPDX}
licenseSha256=${LICENSE_SHA}
EOF

echo "[check] staged pinned Codex app-server ${VERSION} for ${TARGET}/${ARCH}"
