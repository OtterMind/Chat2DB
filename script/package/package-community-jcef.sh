#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  script/package/package-community-jcef.sh <version> [target]

Targets:
  build-canonical | prepare-canonical
      Build backend/frontend once, archive lib/dist, render manifests and receipt.
      Outputs the canonical updater payload artifact to:
        jpackage/canonical-artifact/<version>/

  prepare-native
      Consume the canonical artifact, verify its receipt, re-hash staged files,
      and stage unchanged payload inputs for all native platforms.
      Requires CANONICAL_ARTIFACT_DIR pointing to a canonical artifact directory.

  mac | linux | win
      Consume the canonical artifact, verify its receipt, re-hash staged files,
      stage platform-specific inputs, prepare the platform runtime, and package.
      Requires CANONICAL_ARTIFACT_DIR pointing to a canonical artifact directory.
      These modes NEVER rebuild backend/frontend or regenerate jar/ZIP/metadata.

Environment:
  CANONICAL_ARTIFACT_DIR      Path to canonical artifact directory.
                              Defaults to jpackage/canonical-artifact/<version>/.
                              Required for all native (non-canonical) modes.
  SKIP_BACKEND=true           Skip Maven backend build (canonical mode only).
  SKIP_FRONTEND=true          Skip frontend build (canonical mode only).
  MAC_SIGNING_IDENTITY        macOS Developer ID Application identity.

Examples:
  script/package/package-community-jcef.sh 5.4.0 build-canonical
  CANONICAL_ARTIFACT_DIR=/path/to/artifact script/package/package-community-jcef.sh 5.4.0 prepare-native
  CANONICAL_ARTIFACT_DIR=/path/to/artifact script/package/package-community-jcef.sh 5.4.0 mac
EOF
}

if [ -z "${1:-}" ]; then
  usage >&2
  exit 1
fi

VERSION="$1"
TARGET="${2:-build-canonical}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
JPACKAGE_INPUT_DIR="${ROOT_DIR}/jpackage/input"
CANONICAL_ARTIFACT_DIR="${CANONICAL_ARTIFACT_DIR:-${ROOT_DIR}/jpackage/canonical-artifact/${VERSION}}"

JBR_BASE_URL="https://cache-redirector.jetbrains.com/intellij-jbr"
JBR_WORK_DIR=""
JBR_EXTRACT_DIR=""

case "${TARGET}" in
  build-canonical|prepare-canonical|prepare-native|mac|linux|win) ;;
  *)
    echo "[error] unknown target: ${TARGET}" >&2
    usage >&2
    exit 1
    ;;
esac

cleanup() {
  if [ -n "${JBR_WORK_DIR}" ]; then
    rm -rf "${JBR_WORK_DIR}"
  fi
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

require_file() {
  if [ ! -f "$1" ]; then
    echo "[error] required file not found: $1" >&2
    exit 1
  fi
}

require_dir() {
  if [ ! -d "$1" ]; then
    echo "[error] required directory not found: $1" >&2
    exit 1
  fi
}

# --- Cross-platform helpers ---
get_sha256() {
  local FILE_TO_HASH=$1
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$FILE_TO_HASH" | awk '{print $1}' | tr '[:upper:]' '[:lower:]'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$FILE_TO_HASH" | awk '{print $1}' | tr '[:upper:]' '[:lower:]'
  elif command -v certutil >/dev/null 2>&1; then
    certutil -hashfile "$FILE_TO_HASH" SHA256 | sed -n '2p' | tr -d ' \r\n' | tr '[:upper:]' '[:lower:]'
  else
    echo "[error] no supported hash tool found (shasum, sha256sum, or certutil)." >&2
    exit 1
  fi
}

get_file_size() {
  local FILE=$1
  if [[ "$(uname)" == "Darwin" ]]; then
    stat -f %z "$FILE"
  else
    stat -c %s "$FILE"
  fi
}

# Map canonical payload filename to the receipt entry id.
get_payload_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
  esac
}

# --- Canonical artifact consumption ---
consume_canonical_artifact() {
  local platform="${1:-}"

  echo "[run] consume canonical artifact from ${CANONICAL_ARTIFACT_DIR}"
  require_dir "${CANONICAL_ARTIFACT_DIR}"
  require_file "${CANONICAL_ARTIFACT_DIR}/receipt.json"
  require_file "${CANONICAL_ARTIFACT_DIR}/chat2db-community.jar"
  require_file "${CANONICAL_ARTIFACT_DIR}/lib.zip"
  require_file "${CANONICAL_ARTIFACT_DIR}/dist.zip"
  require_file "${CANONICAL_ARTIFACT_DIR}/local_version.json"

  local receipt="${CANONICAL_ARTIFACT_DIR}/receipt.json"

  # Verify receipt version matches.
  local receipt_version
  receipt_version=$(jq -r '.version' "${receipt}")
  if [ "${receipt_version}" != "${VERSION}" ]; then
    echo "[error] canonical artifact version mismatch: receipt=${receipt_version}, expected=${VERSION}" >&2
    exit 1
  fi

  # Re-hash payloads against receipt.
  for payload in chat2db-community.jar lib.zip dist.zip; do
    local payload_id
    payload_id=$(get_payload_id "$payload")
    local expected_sha
    local expected_size
    expected_sha=$(jq -r --arg id "$payload_id" '.files[] | select(.id == $id) | .sha256' "${receipt}")
    expected_size=$(jq -r --arg id "$payload_id" '.files[] | select(.id == $id) | .size' "${receipt}")

    local actual_sha
    local actual_size
    actual_sha=$(get_sha256 "${CANONICAL_ARTIFACT_DIR}/${payload}")
    actual_size=$(get_file_size "${CANONICAL_ARTIFACT_DIR}/${payload}")

    if [ "${expected_sha}" != "${actual_sha}" ]; then
      echo "[error] canonical artifact SHA-256 mismatch for ${payload}" >&2
      exit 1
    fi
    if [ "${expected_size}" != "${actual_size}" ]; then
      echo "[error] canonical artifact size mismatch for ${payload}" >&2
      exit 1
    fi
  done

  # Re-hash local manifest against receipt.
  local expected_local_manifest_sha
  local actual_local_manifest_sha
  expected_local_manifest_sha=$(jq -r '.localManifestSha256' "${receipt}")
  actual_local_manifest_sha=$(get_sha256 "${CANONICAL_ARTIFACT_DIR}/local_version.json")
  if [ "${expected_local_manifest_sha}" != "${actual_local_manifest_sha}" ]; then
    echo "[error] canonical artifact local_version.json SHA-256 mismatch" >&2
    exit 1
  fi

  echo "[check] canonical artifact receipt verified"

  # Stage for requested platform(s).
  if [ -n "${platform}" ]; then
    stage_platform_input "${platform}"
  else
    stage_platform_input mac
    stage_platform_input win
    stage_platform_input linux
  fi
}

stage_platform_input() {
  local platform="$1"
  local target_dir="${JPACKAGE_INPUT_DIR}/${platform}"

  echo "[run] stage platform input: ${platform}"
  mkdir -p "${target_dir}"
  rm -rf "${target_dir}/dist" "${target_dir}/lib"
  rm -f "${target_dir}/chat2db-community.jar" "${target_dir}/local_version.json"

  cp "${CANONICAL_ARTIFACT_DIR}/chat2db-community.jar" "${target_dir}/chat2db-community.jar"
  cp "${CANONICAL_ARTIFACT_DIR}/local_version.json" "${target_dir}/local_version.json"

  require_command unzip
  unzip -q "${CANONICAL_ARTIFACT_DIR}/lib.zip" -d "${target_dir}/lib"
  unzip -q "${CANONICAL_ARTIFACT_DIR}/dist.zip" -d "${target_dir}/dist"

  require_command python3
  python3 "${SCRIPT_DIR}/verify_staged_payload.py" \
    "${CANONICAL_ARTIFACT_DIR}/chat2db-community.jar" "${target_dir}/chat2db-community.jar" \
    "${CANONICAL_ARTIFACT_DIR}/local_version.json" "${target_dir}/local_version.json" \
    "${CANONICAL_ARTIFACT_DIR}/lib.zip" "${target_dir}/lib" \
    "${CANONICAL_ARTIFACT_DIR}/dist.zip" "${target_dir}/dist"

  echo "[check] staged ${platform} input matches canonical byte copies and archive inventories"
}

# --- JBR runtime preparation (platform-specific native work) ---
download_jbr() {
  local archive_name="$1"
  local archive_path

  require_command curl
  require_command tar

  JBR_WORK_DIR=$(mktemp -d)
  JBR_EXTRACT_DIR="${JBR_WORK_DIR}/runtime"
  archive_path="${JBR_WORK_DIR}/${archive_name}"
  mkdir -p "${JBR_EXTRACT_DIR}"

  echo "[run] download JBR runtime: ${archive_name}"
  curl --fail --location --retry 3 \
    --output "${archive_path}" \
    "${JBR_BASE_URL}/${archive_name}"
  tar -xzf "${archive_path}" -C "${JBR_EXTRACT_DIR}" --strip-components=1
}

prepare_macos_runtime() {
  local machine_arch
  local archive_name
  local jbr_home

  machine_arch=$(uname -m)
  case "${machine_arch}" in
    arm64|aarch64)
      archive_name="jbr_jcef-17.0.12-osx-aarch64-b1207.37.tar.gz"
      ;;
    x86_64|amd64)
      archive_name="jbr_jcef-17.0.12-osx-x64-b1207.37.tar.gz"
      ;;
    *)
      echo "[error] unsupported macOS architecture: ${machine_arch}" >&2
      exit 1
      ;;
  esac

  download_jbr "${archive_name}"
  jbr_home="${JBR_EXTRACT_DIR}/Contents/Home"
  require_dir "${jbr_home}/lib"
  require_dir "${JBR_EXTRACT_DIR}/Contents/Frameworks"

  rm -rf \
    "${JPACKAGE_INPUT_DIR}/runtime/mac" \
    "${JPACKAGE_INPUT_DIR}/mac/Frameworks"
  mkdir -p \
    "${JPACKAGE_INPUT_DIR}/runtime/mac/Home" \
    "${JPACKAGE_INPUT_DIR}/mac"
  cp -R "${jbr_home}/." "${JPACKAGE_INPUT_DIR}/runtime/mac/Home/"
  cp -R \
    "${JBR_EXTRACT_DIR}/Contents/Frameworks" \
    "${JPACKAGE_INPUT_DIR}/mac/Frameworks"
  rm -rf "${JPACKAGE_INPUT_DIR}/mac/Frameworks/cef_server.app"

  require_dir "${JPACKAGE_INPUT_DIR}/runtime/mac/Home/lib"
  export JAVA_HOME="${jbr_home}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
}

prepare_linux_runtime() {
  local machine_arch
  local archive_name

  machine_arch=$(uname -m)
  case "${machine_arch}" in
    aarch64|arm64)
      archive_name="jbr_jcef-17.0.12-linux-aarch64-b1207.37.tar.gz"
      ;;
    x86_64|amd64)
      archive_name="jbr_jcef-17.0.12-linux-x64-b1207.37.tar.gz"
      ;;
    *)
      echo "[error] unsupported Linux architecture: ${machine_arch}" >&2
      exit 1
      ;;
  esac

  download_jbr "${archive_name}"
  require_dir "${JBR_EXTRACT_DIR}/lib"

  rm -rf "${JPACKAGE_INPUT_DIR}/runtime/linux"
  mkdir -p "${JPACKAGE_INPUT_DIR}/runtime/linux/Home"
  cp -R "${JBR_EXTRACT_DIR}/." "${JPACKAGE_INPUT_DIR}/runtime/linux/Home/"
  require_dir "${JPACKAGE_INPUT_DIR}/runtime/linux/Home/lib"
}

prepare_windows_runtime() {
  download_jbr "jbr_jcef-17.0.12-windows-x64-b1207.37.tar.gz"
  require_file "${JBR_EXTRACT_DIR}/bin/java.exe"

  rm -rf "${JPACKAGE_INPUT_DIR}/runtime/win"
  mkdir -p "${JPACKAGE_INPUT_DIR}/runtime/win/Home"
  cp -R "${JBR_EXTRACT_DIR}/." "${JPACKAGE_INPUT_DIR}/runtime/win/Home/"
  require_file "${JPACKAGE_INPUT_DIR}/runtime/win/Home/bin/java.exe"
}

# --- Native packaging dispatch ---
build_windows_updater() {
  local windows_updater_project="${ROOT_DIR}/jpackage/updater"
  echo "[run] build reproducible Windows updater helper"
  mvn -B -f "${windows_updater_project}/pom.xml" clean package
  cp "${windows_updater_project}/target/chat2db-community-updater.jar" \
    "${JPACKAGE_INPUT_DIR}/win/updater.jar"
}

package_mac() {
  consume_canonical_artifact mac
  prepare_macos_runtime
  bash "${SCRIPT_DIR}/sign-macos-native-libraries.sh" \
    "${JPACKAGE_INPUT_DIR}/mac"
  local machine_arch
  machine_arch=$(uname -m)
  local arch_suffix
  if [ "${machine_arch}" = "arm64" ] || [ "${machine_arch}" = "aarch64" ]; then
    arch_suffix="arm64"
  else
    arch_suffix="x64"
  fi
  bash "${SCRIPT_DIR}/package_macos_community.sh" \
    "${VERSION}" \
    "Chat2DB-Community-${VERSION}-${arch_suffix}.dmg"
}

package_linux() {
  consume_canonical_artifact linux
  prepare_linux_runtime
  bash "${SCRIPT_DIR}/package_linux_community.sh" "${VERSION}"
}

package_win() {
  consume_canonical_artifact win
  build_windows_updater
  prepare_windows_runtime
  bash "${SCRIPT_DIR}/package_win_community.sh" "${VERSION}"
}

# --- Main dispatch ---
case "${TARGET}" in
  build-canonical|prepare-canonical)
    echo "[run] canonical payload build mode"
    bash "${SCRIPT_DIR}/build_canonical_payload.sh" "${VERSION}"
    ;;
  prepare-native)
    echo "[run] native prepare mode (all platforms)"
    consume_canonical_artifact
    echo "[done] native inputs staged for all platforms"
    ;;
  mac)
    echo "[run] native macOS packaging mode"
    package_mac
    ;;
  linux)
    echo "[run] native Linux packaging mode"
    package_linux
    ;;
  win)
    echo "[run] native Windows packaging mode"
    package_win
    ;;
esac
