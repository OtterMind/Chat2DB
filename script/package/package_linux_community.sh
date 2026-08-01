#!/usr/bin/env bash

set -euo pipefail

if [ -z "${1:-}" ]; then
    echo "Usage: $0 <version>" >&2
    exit 1
fi

APP_VERSION="$1"
APP_NAME="Chat2DB Community"
APP_ARTIFACT_PREFIX="Chat2DB-Community"
PACKAGE_NAME="chat2db-community"
VENDOR_NAME="AiTa Technology (Hangzhou) Co., Ltd."
MAINTAINER_EMAIL="support@chat2db.ai"
MAIN_JAR="chat2db-community.jar"
MAIN_CLASS="org.springframework.boot.loader.launch.PropertiesLauncher"
PROTOCOL_NAME="chat2db-community"
PROTOCOL_DESCRIPTION="Chat2DB Community Protocol Handler"

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LICENSE_FILE="${PROJECT_ROOT}/LICENSE"
INPUT_DIR="${PROJECT_ROOT}/jpackage/input/linux"
MAIN_JAR_PATH="${INPUT_DIR}/${MAIN_JAR}"
LIB_DIR="${INPUT_DIR}/lib"
COMMUNITY_ICON_FILE="${PROJECT_ROOT}/jpackage/input/icons/community/logo.png"
ICON_FILE="${COMMUNITY_ICON_FILE}"
OUTPUT_DIR="${PROJECT_ROOT}/jpackage/output"
ASSOCIATIONS_FILE="${OUTPUT_DIR}/chat2db-community-protocol.properties"
INSTALL_DIR="/opt/${PACKAGE_NAME}"
MIME_TYPE="x-scheme-handler/${PROTOCOL_NAME}"
DESCRIPTION="${PROTOCOL_DESCRIPTION}"
APP_EXECUTABLE_ESCAPED="${APP_NAME// /\\ }"

validate_resources() {
    if [ ! -f "${MAIN_JAR_PATH}" ]; then
        echo "Error: Community application jar not found: ${MAIN_JAR_PATH}" >&2
        exit 1
    fi
    if [ ! -d "${LIB_DIR}" ]; then
        echo "Error: Community external dependency directory not found: ${LIB_DIR}" >&2
        exit 1
    fi
    if [ ! -f "${ICON_FILE}" ]; then
        echo "Error: Linux icon file not found: ${ICON_FILE}" >&2
        exit 1
    fi
    if [ ! -f "${LICENSE_FILE}" ]; then
        echo "Error: license file not found: ${LICENSE_FILE}" >&2
        exit 1
    fi
    if [ ! -d "${PROJECT_ROOT}/jpackage/input/runtime/linux/Home" ]; then
        echo "Error: Linux runtime image not found: ${PROJECT_ROOT}/jpackage/input/runtime/linux/Home" >&2
        exit 1
    fi
    for cmd in curl find jpackage; do
        if ! command -v "${cmd}" >/dev/null 2>&1; then
            echo "Error: required command not found: ${cmd}" >&2
            exit 1
        fi
    done
}

detect_arch() {
    local machine_arch
    machine_arch=$(uname -m)
    case "${machine_arch}" in
        x86_64|amd64)
            ARCH_LABEL="x86_64"
            DEB_ARCH="amd64"
            RPM_ARCH="x86_64"
            APPIMAGE_TOOL_ARCH="x86_64"
            ;;
        aarch64|arm64)
            ARCH_LABEL="arm64"
            DEB_ARCH="arm64"
            RPM_ARCH="aarch64"
            APPIMAGE_TOOL_ARCH="aarch64"
            ;;
        *)
            echo "Error: unsupported Linux architecture: ${machine_arch}" >&2
            exit 1
            ;;
    esac
}

java_options() {
    cat <<EOF
-Ddeploy.local=false
-Dspring.profiles.active=release
-Dloader.path=lib
-Dchat2db.mode=DESKTOP
-Dchat2db.runtime.mode=community
-Dchat2db.network.status=OFFLINE
-Dchat2db.ai.subscription.enabled=${CHAT2DB_SUBSCRIPTION_AI_JAVA_ENABLED:-false}
-Dchat2db.ai.subscription.runtime-manifest=codex-app-server/runtime.properties
-Dchat2db.ai.secret-import.enabled=${CHAT2DB_SECRET_IMPORT_JAVA_ENABLED:-false}
-Dfile.encoding=UTF-8
-Dserver.address=127.0.0.1
-Dserver.port=10825
-noverify
-Xms128M
EOF
}

prepare_association_file() {
    cat > "${ASSOCIATIONS_FILE}" <<EOF
[File Association]
mime-type=${MIME_TYPE}
description=${DESCRIPTION}
EOF
}

create_resource_dir() {
    local resource_dir="$1"
    rm -rf "${resource_dir}"
    mkdir -p "${resource_dir}"

    local desktop_content
    desktop_content=$(cat <<EOF
[Desktop Entry]
Name=${APP_NAME}
Comment=${DESCRIPTION}
Exec=${INSTALL_DIR}/bin/${APP_EXECUTABLE_ESCAPED} %U
Terminal=false
Type=Application
Icon=${APP_NAME}
Categories=Development;
MimeType=${MIME_TYPE};
StartupNotify=true
EOF
)

    printf '%s\n' "${desktop_content}" > "${resource_dir}/${APP_NAME}.desktop"
    printf '%s\n' "${desktop_content}" > "${resource_dir}/${PACKAGE_NAME}.desktop"
    cp "${ICON_FILE}" "${resource_dir}/${APP_NAME}.png"
    cp "${ICON_FILE}" "${resource_dir}/${PACKAGE_NAME}.png"
}

build_base_args() {
    local dest_dir="$1"
    BASE_ARGS=(
        "--name" "${APP_NAME}"
        "--app-version" "${APP_VERSION}"
        "--vendor" "${VENDOR_NAME}"
        "--input" "${INPUT_DIR}"
        "--main-jar" "${MAIN_JAR}"
        "--main-class" "${MAIN_CLASS}"
        "--dest" "${dest_dir}"
        "--runtime-image" "${PROJECT_ROOT}/jpackage/input/runtime/linux/Home"
        "--icon" "${ICON_FILE}"
    )

    while IFS= read -r opt; do
        [ -n "${opt}" ] && BASE_ARGS+=("--java-options" "${opt}")
    done < <(java_options)
}

move_single_artifact() {
    local search_dir="$1"
    local pattern="$2"
    local final_path="$3"
    local found_path
    found_path=$(find "${search_dir}" -name "${pattern}" -print -quit)
    if [ -z "${found_path}" ]; then
        echo "Error: artifact not found: ${pattern}" >&2
        exit 1
    fi
    mv "${found_path}" "${final_path}"
    echo "Artifact generated: ${final_path}"
}

build_deb() {
    local work_dir="${OUTPUT_DIR}/_work/deb"
    local resource_dir="${work_dir}/resources"
    mkdir -p "${work_dir}"
    create_resource_dir "${resource_dir}"
    build_base_args "${work_dir}"

    jpackage \
        --type deb \
        "${BASE_ARGS[@]}" \
        --license-file "${LICENSE_FILE}" \
        --file-associations "${ASSOCIATIONS_FILE}" \
        --install-dir "${INSTALL_DIR}" \
        --resource-dir "${resource_dir}" \
        --linux-package-name "${PACKAGE_NAME}" \
        --linux-deb-maintainer "${MAINTAINER_EMAIL}" \
        --linux-menu-group Development \
        --linux-app-category Development \
        --linux-shortcut

    move_single_artifact "${work_dir}" "*.deb" "${OUTPUT_DIR}/${APP_ARTIFACT_PREFIX}-${APP_VERSION}-${DEB_ARCH}.deb"
}

build_rpm() {
    local work_dir="${OUTPUT_DIR}/_work/rpm"
    local resource_dir="${work_dir}/resources"
    mkdir -p "${work_dir}"
    create_resource_dir "${resource_dir}"
    build_base_args "${work_dir}"

    jpackage \
        --type rpm \
        "${BASE_ARGS[@]}" \
        --license-file "${LICENSE_FILE}" \
        --file-associations "${ASSOCIATIONS_FILE}" \
        --install-dir "${INSTALL_DIR}" \
        --resource-dir "${resource_dir}" \
        --linux-package-name "${PACKAGE_NAME}" \
        --linux-rpm-license-type LicenseRef-Chat2DB \
        --linux-menu-group Development \
        --linux-app-category Development \
        --linux-shortcut

    move_single_artifact "${work_dir}" "*.rpm" "${OUTPUT_DIR}/${APP_ARTIFACT_PREFIX}-${APP_VERSION}-${RPM_ARCH}.rpm"
}

download_appimagetool() {
    local tool_path="$1"
    local tool_url="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${APPIMAGE_TOOL_ARCH}.AppImage"
    curl -L --fail -o "${tool_path}" "${tool_url}"
    chmod +x "${tool_path}"
}

prepare_appimage_dir() {
    local app_dir="$1"
    local desktop_path="${app_dir}/${PACKAGE_NAME}.desktop"
    local apprun_path="${app_dir}/AppRun"
    local icon_path="${app_dir}/${PACKAGE_NAME}.png"

    printf '%s\n' "[Desktop Entry]" > "${desktop_path}"
    printf '%s\n' "Name=${APP_NAME}" >> "${desktop_path}"
    printf '%s\n' "Comment=${DESCRIPTION}" >> "${desktop_path}"
    printf '%s\n' "Exec=AppRun %U" >> "${desktop_path}"
    printf '%s\n' "Terminal=false" >> "${desktop_path}"
    printf '%s\n' "Type=Application" >> "${desktop_path}"
    printf '%s\n' "Icon=${PACKAGE_NAME}" >> "${desktop_path}"
    printf '%s\n' "Categories=Development;" >> "${desktop_path}"
    printf '%s\n' "MimeType=${MIME_TYPE};" >> "${desktop_path}"
    printf '%s\n' "StartupNotify=true" >> "${desktop_path}"

    cp "${ICON_FILE}" "${icon_path}"
    cp "${ICON_FILE}" "${app_dir}/.DirIcon"

    cat > "${apprun_path}" <<EOF
#!/bin/sh
HERE=\$(dirname "\$(readlink -f "\$0")")
exec "\${HERE}/bin/${APP_NAME}" "\$@"
EOF
    chmod +x "${apprun_path}"
}

build_appimage() {
    local work_dir="${OUTPUT_DIR}/_work/appimage"
    local app_image_dest="${work_dir}/app-image"
    local tool_path="${work_dir}/appimagetool-${APPIMAGE_TOOL_ARCH}.AppImage"
    mkdir -p "${app_image_dest}"
    build_base_args "${app_image_dest}"

    jpackage --type app-image "${BASE_ARGS[@]}"

    local app_dir
    app_dir=$(find "${app_image_dest}" -mindepth 1 -maxdepth 1 -type d -print -quit)
    if [ -z "${app_dir}" ]; then
        echo "Error: jpackage app-image directory not found." >&2
        exit 1
    fi

    prepare_appimage_dir "${app_dir}"
    download_appimagetool "${tool_path}"

    APPIMAGE_EXTRACT_AND_RUN=1 ARCH="${APPIMAGE_TOOL_ARCH}" "${tool_path}" \
        "${app_dir}" \
        "${OUTPUT_DIR}/${APP_ARTIFACT_PREFIX}-${APP_VERSION}-${ARCH_LABEL}.AppImage"
}

validate_resources
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
detect_arch
prepare_association_file
build_deb
build_rpm
build_appimage
echo "Linux Community artifacts generated."
