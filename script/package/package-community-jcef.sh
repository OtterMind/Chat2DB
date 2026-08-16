#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  script/package/package-community-jcef.sh <version> [prepare|mac|linux|win]

Targets:
  prepare  Build and stage Community application files only.
  mac      Build, stage, prepare macOS JBR, sign native libraries, and package.
  linux    Build, stage, prepare Linux JBR, and package.
  win      Build, stage, prepare Windows JBR, and package.

Environment:
  SKIP_BACKEND=true             Skip Maven backend build.
  SKIP_FRONTEND=true            Skip frontend build.
  COMMUNITY_UPDATE_BASE_URL     Metadata base URL.
  MAC_SIGNING_IDENTITY          macOS Developer ID Application identity.

Examples:
  script/package/package-community-jcef.sh 5.3.0 prepare
  script/package/package-community-jcef.sh 5.3.0 mac
EOF
}

if [ -z "${1:-}" ]; then
  usage >&2
  exit 1
fi

VERSION="$1"
TARGET="${2:-prepare}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
SERVER_DIR="${ROOT_DIR}/chat2db-community-server"
CLIENT_DIR="${ROOT_DIR}/chat2db-community-client"
JPACKAGE_INPUT_DIR="${ROOT_DIR}/jpackage/input"
SOURCE_FILE_DIR="${JPACKAGE_INPUT_DIR}/sourceFile"
COMMUNITY_JAR="${SERVER_DIR}/chat2db-community-start/target/chat2db-community.jar"
COMMUNITY_LIB_DIR="${SERVER_DIR}/chat2db-community-start/target/lib"
COMMUNITY_LIB_ZIP="${SERVER_DIR}/chat2db-community-start/target/lib.zip"
UPDATE_BASE_URL="${COMMUNITY_UPDATE_BASE_URL:-https://cdn.chat2db-ai.com/community/updates}"
JBR_BASE_URL="https://cache-redirector.jetbrains.com/intellij-jbr"
JBR_WORK_DIR=""
JBR_EXTRACT_DIR=""

case "${TARGET}" in
  prepare|mac|linux|win) ;;
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

verify_jcef_i18n_resources() {
  local jcef_jar
  local jar_index
  local resource
  local required_resources=(
    "i18n/messages.properties"
    "i18n/messages_en.properties"
    "i18n/messages_en_US.properties"
    "i18n/messages_ja.properties"
    "i18n/messages_ja_JP.properties"
    "i18n/messages_zh.properties"
    "i18n/messages_zh_CN.properties"
    "i18n/messages_zh_Hans.properties"
    "i18n/messages_zh_Hans_CN.properties"
  )

  jcef_jar=$(find "${COMMUNITY_LIB_DIR}" -maxdepth 1 \
    -name 'chat2db-community-jcef-*.jar' -print -quit)
  if [ -z "${jcef_jar}" ]; then
    echo "[error] chat2db-community-jcef jar not found: ${COMMUNITY_LIB_DIR}" >&2
    exit 1
  fi

  jar_index=$(mktemp)
  jar tf "${jcef_jar}" > "${jar_index}"
  for resource in "${required_resources[@]}"; do
    if ! grep -Fxq "${resource}" "${jar_index}"; then
      echo "[error] required JCEF i18n resource missing: ${resource}" >&2
      rm -f "${jar_index}"
      exit 1
    fi
  done
  rm -f "${jar_index}"
  echo "[check] JCEF i18n resources present in $(basename "${jcef_jar}")"
}

verify_flatlaf_runtime_dependency() {
  local flatlaf_jar
  local flatlaf_count
  local jar_index
  local lib_zip_index
  local required_entry
  local required_entries=(
    "com/formdev/flatlaf/FlatLaf.class"
    "com/formdev/flatlaf/FlatDarkLaf.class"
    "com/formdev/flatlaf/themes/FlatMacLightLaf.class"
    "com/formdev/flatlaf/themes/FlatMacDarkLaf.class"
    "com/formdev/flatlaf/natives/libflatlaf-macos-x86_64.dylib"
    "com/formdev/flatlaf/natives/libflatlaf-macos-arm64.dylib"
  )

  flatlaf_count=$(find "${COMMUNITY_LIB_DIR}" -maxdepth 1 -type f \
    -name 'flatlaf-*.jar' -print | wc -l | tr -d '[:space:]')
  if [ "${flatlaf_count}" -ne 1 ]; then
    echo "[error] expected exactly one FlatLaf runtime dependency, found ${flatlaf_count}: ${COMMUNITY_LIB_DIR}" >&2
    exit 1
  fi
  flatlaf_jar=$(find "${COMMUNITY_LIB_DIR}" -maxdepth 1 \
    -type f -name 'flatlaf-*.jar' -print -quit)

  jar_index=$(mktemp)
  if ! jar tf "${flatlaf_jar}" > "${jar_index}"; then
    rm -f "${jar_index}"
    echo "[error] failed to inspect FlatLaf runtime dependency: ${flatlaf_jar}" >&2
    exit 1
  fi
  for required_entry in "${required_entries[@]}"; do
    if ! grep -Fxq "${required_entry}" "${jar_index}"; then
      rm -f "${jar_index}"
      echo "[error] required FlatLaf entry missing from runtime dependency: ${required_entry}" >&2
      exit 1
    fi
  done
  rm -f "${jar_index}"

  lib_zip_index=$(mktemp)
  if ! jar tf "${COMMUNITY_LIB_ZIP}" > "${lib_zip_index}"; then
    rm -f "${lib_zip_index}"
    echo "[error] failed to inspect Community external dependency archive: ${COMMUNITY_LIB_ZIP}" >&2
    exit 1
  fi
  if [ "$(grep -Fxc "lib/$(basename "${flatlaf_jar}")" "${lib_zip_index}" || true)" -ne 1 ]; then
    rm -f "${lib_zip_index}"
    echo "[error] FlatLaf runtime dependency missing or duplicated in archive: ${COMMUNITY_LIB_ZIP}" >&2
    exit 1
  fi
  rm -f "${lib_zip_index}"
  echo "[check] FlatLaf runtime dependency present: $(basename "${flatlaf_jar}")"
}

copy_dist() {
  local platform="$1"
  local target_dir="${JPACKAGE_INPUT_DIR}/${platform}"

  mkdir -p "${target_dir}"
  rm -rf "${target_dir}/dist" "${target_dir}/lib"
  rm -f "${target_dir}/chat2db-community.jar"
  cp -R "${CLIENT_DIR}/dist" "${target_dir}/dist"
  cp -R "${COMMUNITY_LIB_DIR}" "${target_dir}/lib"
  cp "${COMMUNITY_JAR}" "${target_dir}/chat2db-community.jar"
  cp "${SOURCE_FILE_DIR}/local_version.json" "${target_dir}/local_version.json"
}

zip_frontend_dist() {
  rm -f "${CLIENT_DIR}/dist.zip"
  if command -v zip >/dev/null 2>&1; then
    (cd "${CLIENT_DIR}" && zip -qr dist.zip dist)
    return
  fi
  if command -v 7z >/dev/null 2>&1; then
    (cd "${CLIENT_DIR}" && 7z a -tzip dist.zip ./dist >/dev/null)
    return
  fi
  echo "[error] neither zip nor 7z is available" >&2
  exit 1
}

stage_community_input() {
  if [ "${SKIP_BACKEND:-false}" != "true" ]; then
    echo "[run] build Community backend"
    mvn clean install -U -B \
      -Dmaven.test.skip=true \
      -Dchat2db.finalName=chat2db-community \
      -f "${SERVER_DIR}/pom.xml"
  fi
  require_file "${COMMUNITY_JAR}"
  require_dir "${COMMUNITY_LIB_DIR}"
  require_file "${COMMUNITY_LIB_ZIP}"
  verify_jcef_i18n_resources
  verify_flatlaf_runtime_dependency

  if [ "${SKIP_FRONTEND:-false}" != "true" ]; then
    echo "[run] build Community frontend"
    pushd "${CLIENT_DIR}" >/dev/null
    yarn install --frozen-lockfile
    yarn run build:web:community --app_version="${VERSION}"
    zip_frontend_dist
    mkdir -p static
    rm -rf static/dist
    cp -R dist static/dist
    popd >/dev/null
  fi
  require_file "${CLIENT_DIR}/dist.zip"

  echo "[run] stage Community jpackage input"
  mkdir -p \
    "${SOURCE_FILE_DIR}" \
    "${JPACKAGE_INPUT_DIR}/mac" \
    "${JPACKAGE_INPUT_DIR}/win" \
    "${JPACKAGE_INPUT_DIR}/linux"
  rm -f \
    "${SOURCE_FILE_DIR}"/*.jar \
    "${SOURCE_FILE_DIR}"/*.zip \
    "${SOURCE_FILE_DIR}/version.json" \
    "${SOURCE_FILE_DIR}/local_version.json"

  cp "${COMMUNITY_JAR}" "${SOURCE_FILE_DIR}/chat2db-community.jar"
  cp "${COMMUNITY_LIB_ZIP}" "${SOURCE_FILE_DIR}/lib.zip"
  cp "${CLIENT_DIR}/dist.zip" "${SOURCE_FILE_DIR}/dist.zip"
  bash "${SCRIPT_DIR}/generate_metadata.sh" \
    "${VERSION}" \
    "${SOURCE_FILE_DIR}" \
    "${UPDATE_BASE_URL}"
  cp "${SOURCE_FILE_DIR}/version.json" "${SOURCE_FILE_DIR}/local_version.json"

  copy_dist mac
  copy_dist win
  copy_dist linux
}

stage_community_input

case "${TARGET}" in
  prepare)
    echo "[done] Community jpackage input prepared"
    ;;
  mac)
    prepare_macos_runtime
    bash "${SCRIPT_DIR}/sign-macos-native-libraries.sh" \
      "${JPACKAGE_INPUT_DIR}/mac"
    machine_arch=$(uname -m)
    if [ "${machine_arch}" = "arm64" ] || [ "${machine_arch}" = "aarch64" ]; then
      arch_suffix="arm64"
    else
      arch_suffix="x64"
    fi
    bash "${SCRIPT_DIR}/package_macos_community.sh" \
      "${VERSION}" \
      "Chat2DB-Community-${VERSION}-${arch_suffix}.dmg"
    ;;
  linux)
    prepare_linux_runtime
    bash "${SCRIPT_DIR}/package_linux_community.sh" "${VERSION}"
    ;;
  win)
    prepare_windows_runtime
    bash "${SCRIPT_DIR}/package_win_community.sh" "${VERSION}"
    ;;
esac
