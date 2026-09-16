#!/usr/bin/env bash

set -euo pipefail

if [ -z "${1:-}" ]; then
    echo "Usage: $0 <version>" >&2
    exit 1
fi

APP_VERSION="$1"
APP_NAME="Chat2DB Community"
VENDOR_NAME="Aita Technology (Hangzhou) Co., Ltd."
ARTIFACT_BASE="Chat2DB-Community"
WIN_UPGRADE_UUID="4D7C78BC-B42F-4F81-9F5F-56E3F5E4E9B2"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "${COMMUNITY_SOURCE_DIR:-${SCRIPT_DIR}/../..}" && pwd)
source "${SCRIPT_DIR}/desktop_layout.sh"
chat2db_load_desktop_layout "${PROJECT_ROOT}"
source "${SCRIPT_DIR}/community-version.sh"
NATIVE_VERSION=$(community_native_version "${APP_VERSION}")
LICENSE_FILE="${PROJECT_ROOT}/LICENSE"
INPUT_DIR="${PROJECT_ROOT}/jpackage/input/win"
MAIN_JAR_PATH="${INPUT_DIR}/${MAIN_JAR}"
LIB_DIR="${INPUT_DIR}/runtime/lib"
COMMUNITY_ICON_FILE="${PROJECT_ROOT}/jpackage/input/icons/community/logo.ico"
ICON_FILE="${COMMUNITY_ICON_FILE}"
RUNTIME_IMAGE_PATH="${PROJECT_ROOT}/jpackage/input/runtime/win/Home"
OUTPUT_DIR="${PROJECT_ROOT}/jpackage/output"
ASSOCIATIONS_FILE="${OUTPUT_DIR}/sql-association-community.properties"

chat2db_validate_desktop_input "${INPUT_DIR}"

echo "[info] project root: ${PROJECT_ROOT}"
echo "[info] app version : ${APP_VERSION}"

if [ ! -f "${MAIN_JAR_PATH}" ]; then
    echo "[error] Community main JAR not found: ${MAIN_JAR_PATH}" >&2
    exit 1
fi
if [ ! -d "${LIB_DIR}" ]; then
    echo "[error] Community external dependency directory not found: ${LIB_DIR}" >&2
    exit 1
fi
if [ ! -d "${RUNTIME_IMAGE_PATH}" ]; then
    echo "[error] runtime image not found: ${RUNTIME_IMAGE_PATH}" >&2
    exit 1
fi
if [ ! -f "${ICON_FILE}" ]; then
    echo "[warn] icon not found, jpackage will use default: ${ICON_FILE}"
fi
if [ ! -f "${LICENSE_FILE}" ]; then
    echo "[error] license file not found: ${LICENSE_FILE}" >&2
    exit 1
fi

rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
cat > "${ASSOCIATIONS_FILE}" <<EOF
extension=sql
mime-type=text/sql
description=SQL File
EOF

echo "[info] JAVA_HOME=${JAVA_HOME:-<unset>}"
java -version
which jpackage
jpackage --version

chat2db_jpackage_arguments "${NATIVE_VERSION}" "${OUTPUT_DIR}" "${RUNTIME_IMAGE_PATH}"
JPACKAGE_ARGS=(
    "${CHAT2DB_JPACKAGE_ARGS[@]}"
    --verbose
    --type msi
    --license-file "${LICENSE_FILE}"
    --file-associations "${ASSOCIATIONS_FILE}"
    --win-shortcut
    --win-menu
    --win-menu-group "${APP_NAME}"
    --win-dir-chooser
    --install-dir "${APP_NAME}"
    --win-upgrade-uuid "${WIN_UPGRADE_UUID}"
)

if [ -f "${ICON_FILE}" ]; then
    JPACKAGE_ARGS+=(--icon "${ICON_FILE}")
fi

JAVA_OPTS=(
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"
    "-Dsun.java2d.d3d=false"
    "-Ddeploy.local=false"
    "-Dspring.profiles.active=release"
    "-Dfile.encoding=UTF-8"
    "-Dchat2db.mode=DESKTOP"
    "-Dchat2db.runtime.mode=community"
    "-Dchat2db.network.status=OFFLINE"
    "-Dserver.address=127.0.0.1"
    "-Dserver.port=10825"
    "-Xms128M"
)

for opt in "${JAVA_OPTS[@]}"; do
    JPACKAGE_ARGS+=(--java-options "${opt}")
done

echo "[run] jpackage ${JPACKAGE_ARGS[*]}"
jpackage "${JPACKAGE_ARGS[@]}"

SRC_MSI="${OUTPUT_DIR}/${APP_NAME}-${NATIVE_VERSION}.msi"
DST_MSI="${OUTPUT_DIR}/${ARTIFACT_BASE}-${APP_VERSION}.msi"
if [ ! -f "${SRC_MSI}" ]; then
    echo "[error] jpackage did not produce ${SRC_MSI}" >&2
    ls -la "${OUTPUT_DIR}" >&2
    exit 1
fi
mv "${SRC_MSI}" "${DST_MSI}"

echo "[done] MSI ready: ${DST_MSI}"
