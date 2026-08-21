#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  script/package/build_canonical_payload.sh <version>

Builds the canonical Community desktop updater payload exactly once.
Outputs are written to:
  jpackage/canonical-artifact/<version>/
    chat2db-community.jar
    lib.zip
    dist.zip
    latest_version.json   (lightweight GitHub latest pointer)
    version.json          (complete GitHub manifest)
    local_version.json    (copy of GitHub manifest)
    receipt.json

Environment:
  SKIP_BACKEND=true             Skip Maven backend build.
  SKIP_FRONTEND=true            Skip frontend build.
  COMMUNITY_GITHUB_REPOSITORY   Default: OtterMind/Chat2DB
  CDN_BASE_URL                  Default: https://cdn.chat2db-ai.com/community/updates

Examples:
  script/package/build_canonical_payload.sh 5.4.0
EOF
}

if [ -z "${1:-}" ]; then
  usage >&2
  exit 1
fi

VERSION="$1"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
SERVER_DIR="${ROOT_DIR}/chat2db-community-server"
CLIENT_DIR="${ROOT_DIR}/chat2db-community-client"

COMMUNITY_JAR="${SERVER_DIR}/chat2db-community-start/target/chat2db-community.jar"
COMMUNITY_LIB_DIR="${SERVER_DIR}/chat2db-community-start/target/lib"
COMMUNITY_LIB_ZIP="${SERVER_DIR}/chat2db-community-start/target/lib.zip"

CANONICAL_ARTIFACT_DIR="${CANONICAL_ARTIFACT_DIR:-${ROOT_DIR}/jpackage/canonical-artifact/${VERSION}}"

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
}

verify_jcef_production_artifact() {
  local jcef_jar
  local jar_index

  jcef_jar=$(find "${COMMUNITY_LIB_DIR}" -maxdepth 1 \
    -name 'chat2db-community-jcef-*.jar' -print -quit)
  if [ -z "${jcef_jar}" ]; then
    echo "[error] chat2db-community-jcef jar not found: ${COMMUNITY_LIB_DIR}" >&2
    exit 1
  fi

  jar_index=$(mktemp)
  jar tf "${jcef_jar}" > "${jar_index}"
  if grep -E '(^|/)(TestUpdateSource|FakeUpdateSource)(\$.*)?\.class$' "${jar_index}"; then
    rm -f "${jar_index}"
    echo "[error] test-only updater source leaked into production JCEF artifact" >&2
    exit 1
  fi
  rm -f "${jar_index}"
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
}

zip_frontend_dist() {
  rm -f "${CLIENT_DIR}/dist.zip"
  # Use zip for deterministic, cross-platform canonical archives.
  # 7z is intentionally not used here to avoid per-platform ZIP byte differences.
  if command -v zip >/dev/null 2>&1; then
    (cd "${CLIENT_DIR}" && zip -qr dist.zip dist)
    return
  fi
  echo "[error] zip is required for canonical dist.zip creation" >&2
  exit 1
}

# --- Build backend ---
if [ "${SKIP_BACKEND:-false}" != "true" ]; then
  echo "[run] build Community backend"
  mvn clean install -U -B \
    -Dmaven.test.skip=true \
    -Drevision="${VERSION}" \
    -Dchat2db.finalName=chat2db-community \
    -f "${SERVER_DIR}/pom.xml"
fi
require_file "${COMMUNITY_JAR}"
require_dir "${COMMUNITY_LIB_DIR}"
require_file "${COMMUNITY_LIB_ZIP}"
verify_jcef_i18n_resources
verify_jcef_production_artifact
verify_flatlaf_runtime_dependency

# --- Build frontend ---
if [ "${SKIP_FRONTEND:-false}" != "true" ]; then
  echo "[run] build Community frontend"
  pushd "${CLIENT_DIR}" >/dev/null
  yarn install --frozen-lockfile
  yarn run build:web:community --app_version="${VERSION}"
  zip_frontend_dist
  popd >/dev/null
fi
require_file "${CLIENT_DIR}/dist.zip"

# --- Stage canonical artifact ---
echo "[run] stage canonical updater payload artifact"
mkdir -p "${CANONICAL_ARTIFACT_DIR}"
rm -f \
  "${CANONICAL_ARTIFACT_DIR}/chat2db-community.jar" \
  "${CANONICAL_ARTIFACT_DIR}/lib.zip" \
  "${CANONICAL_ARTIFACT_DIR}/dist.zip" \
  "${CANONICAL_ARTIFACT_DIR}/version.json" \
  "${CANONICAL_ARTIFACT_DIR}/github-version.json" \
  "${CANONICAL_ARTIFACT_DIR}/cdn-version.json" \
  "${CANONICAL_ARTIFACT_DIR}/local_version.json" \
  "${CANONICAL_ARTIFACT_DIR}/latest_version.json" \
  "${CANONICAL_ARTIFACT_DIR}/cdn-latest-version.json" \
  "${CANONICAL_ARTIFACT_DIR}/receipt.json" \
  "${CANONICAL_ARTIFACT_DIR}/updater.jar"

cp "${COMMUNITY_JAR}" "${CANONICAL_ARTIFACT_DIR}/chat2db-community.jar"
cp "${COMMUNITY_LIB_ZIP}" "${CANONICAL_ARTIFACT_DIR}/lib.zip"
cp "${CLIENT_DIR}/dist.zip" "${CANONICAL_ARTIFACT_DIR}/dist.zip"

# --- Generate manifests and receipt ---
bash "${SCRIPT_DIR}/generate_metadata.sh" \
  "${VERSION}" \
  "${CANONICAL_ARTIFACT_DIR}" \
  "${CANONICAL_ARTIFACT_DIR}"

# The canonical artifact's version.json is the GitHub manifest.
cp "${CANONICAL_ARTIFACT_DIR}/github-version.json" "${CANONICAL_ARTIFACT_DIR}/version.json"

# --- Verify receipt ---
echo "[check] verify canonical artifact receipt"
RECEIPT_FILE="${CANONICAL_ARTIFACT_DIR}/receipt.json"
require_file "${RECEIPT_FILE}"

get_payload_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
  esac
}

for payload in chat2db-community.jar lib.zip dist.zip; do
  payload_id=$(get_payload_id "$payload")
  expected_sha=$(jq -r --arg id "$payload_id" '.files[] | select(.id == $id) | .sha256' "${RECEIPT_FILE}")
  expected_size=$(jq -r --arg id "$payload_id" '.files[] | select(.id == $id) | .size' "${RECEIPT_FILE}")
  actual_sha=$(get_sha256 "${CANONICAL_ARTIFACT_DIR}/${payload}")
  actual_size=$(get_file_size "${CANONICAL_ARTIFACT_DIR}/${payload}")

  if [ "${expected_sha}" != "${actual_sha}" ]; then
    echo "[error] receipt SHA-256 mismatch for ${payload}" >&2
    exit 1
  fi
  if [ "${expected_size}" != "${actual_size}" ]; then
    echo "[error] receipt size mismatch for ${payload}" >&2
    exit 1
  fi
done

expected_manifest_sha=$(jq -r '.manifestSha256' "${RECEIPT_FILE}")
actual_manifest_sha=$(get_sha256 "${CANONICAL_ARTIFACT_DIR}/version.json")
if [ "${expected_manifest_sha}" != "${actual_manifest_sha}" ]; then
  echo "[error] receipt manifestSha256 mismatch" >&2
  exit 1
fi

expected_local_manifest_sha=$(jq -r '.localManifestSha256' "${RECEIPT_FILE}")
actual_local_manifest_sha=$(get_sha256 "${CANONICAL_ARTIFACT_DIR}/local_version.json")
if [ "${expected_local_manifest_sha}" != "${actual_local_manifest_sha}" ]; then
  echo "[error] receipt localManifestSha256 mismatch" >&2
  exit 1
fi

echo "[done] canonical updater payload artifact ready at ${CANONICAL_ARTIFACT_DIR}"
