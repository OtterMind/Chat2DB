#!/usr/bin/env bash

set -euo pipefail

if [ -z "${1:-}" ]; then
    echo "Usage: $0 <version> [final_dmg_filename_with_extension]" >&2
    exit 1
fi

APP_VERSION="$1"
CUSTOM_DMG_FILENAME="${2:-}"

APP_NAME="Chat2DB Community"
APP_IDENTIFIER="com.chat2db.community"
PROTOCOL_NAME="chat2db-community"
VENDOR_NAME="AiTa Technology (Hangzhou) Co., Ltd."
MAIN_JAR="chat2db-community.jar"
MAIN_CLASS="org.springframework.boot.loader.launch.PropertiesLauncher"
APP_ICON_NAME="${APP_NAME}.icns"

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
INPUT_DIR="${PROJECT_ROOT}/jpackage/input/mac"
RESOURCE_TEMPLATE_DIR="${INPUT_DIR}/../macres"
RESOURCE_DIR="${PROJECT_ROOT}/jpackage/output/macres-community"
INFO_PLIST_FILE="${RESOURCE_DIR}/Info.plist"
MAIN_JAR_PATH="${INPUT_DIR}/${MAIN_JAR}"
LIB_DIR="${INPUT_DIR}/lib"
MAC_RUNTIME_IMAGE="${INPUT_DIR}/../runtime/mac/Home"
COMMUNITY_ICON_FILE="${PROJECT_ROOT}/jpackage/input/icons/community/logo.icns"
ICON_FILE="${COMMUNITY_ICON_FILE}"
OUTPUT_DIR="${PROJECT_ROOT}/jpackage/output"
ASSOCIATIONS_FILE="${OUTPUT_DIR}/sql-association-community.properties"
MAC_SIGNING_IDENTITY="${MAC_SIGNING_IDENTITY:-}"

validate_resources() {
    if [ ! -f "${MAIN_JAR_PATH}" ]; then
        echo "Error: Community application jar not found: ${MAIN_JAR_PATH}" >&2
        exit 1
    fi
    if [ ! -d "${LIB_DIR}" ]; then
        echo "Error: Community external dependency directory not found: ${LIB_DIR}" >&2
        exit 1
    fi
    if [ ! -d "${MAC_RUNTIME_IMAGE}/lib" ]; then
        echo "Error: macOS runtime image must contain lib: ${MAC_RUNTIME_IMAGE}" >&2
        echo "Hint: copy the extracted macOS JBR/JDK Contents/Home directory to jpackage/input/runtime/mac/Home." >&2
        exit 1
    fi
    if [ ! -d "${RESOURCE_TEMPLATE_DIR}" ]; then
        echo "Error: macOS resource directory not found: ${RESOURCE_TEMPLATE_DIR}" >&2
        exit 1
    fi
    if [ ! -f "${ICON_FILE}" ]; then
        echo "Warning: icon file not found, jpackage will use the default icon: ${ICON_FILE}"
        ICON_FILE=""
    fi
}

prepare_resource_dir() {
    rm -rf "${RESOURCE_DIR}"
    mkdir -p "${OUTPUT_DIR}"
    cp -R "${RESOURCE_TEMPLATE_DIR}" "${RESOURCE_DIR}"
    if [ -n "${ICON_FILE}" ]; then
        cp "${ICON_FILE}" "${RESOURCE_DIR}/${APP_ICON_NAME}"
    fi
    if [ -f "${INFO_PLIST_FILE}" ]; then
        /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString ${APP_VERSION}" "${INFO_PLIST_FILE}" 2>/dev/null \
            || /usr/libexec/PlistBuddy -c "Add :CFBundleShortVersionString string ${APP_VERSION}" "${INFO_PLIST_FILE}"
        /usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${APP_VERSION}" "${INFO_PLIST_FILE}" 2>/dev/null \
            || /usr/libexec/PlistBuddy -c "Add :CFBundleVersion string ${APP_VERSION}" "${INFO_PLIST_FILE}"
    fi
}

prepare_association_file() {
    cat > "${ASSOCIATIONS_FILE}" <<EOF
extension=sql
mime-type=text/sql
description=SQL File
EOF
}

remove_existing_signatures() {
    local jcef_path="${INPUT_DIR}/Frameworks"
    if [ -d "${jcef_path}" ]; then
        find "${jcef_path}" -name "*.app" -exec codesign --remove-signature {} \; 2>/dev/null || true
        find "${jcef_path}" -name "*.dylib" -exec codesign --remove-signature {} \; 2>/dev/null || true
        find "${jcef_path}" -name "*.framework" -exec codesign --remove-signature {} \; 2>/dev/null || true
    fi
}

package_application() {
    jpackage --version
    file "$(which jpackage)"
    security find-identity -v -p codesigning || true

    local signing_identity="${MAC_SIGNING_IDENTITY}"
    if [ -z "${signing_identity}" ]; then
        signing_identity=$(security find-identity -v -p codesigning | awk -F '"' '/Developer ID Application/ { print $2; exit }')
    fi
    if [ -z "${signing_identity}" ]; then
        echo "Error: no Developer ID Application signing identity found in keychain" >&2
        security find-identity -v -p codesigning || true
        exit 1
    fi
    if ! security find-identity -v -p codesigning | grep -F "${signing_identity}" >/dev/null; then
        echo "Error: macOS signing identity not found: ${signing_identity}" >&2
        exit 1
    fi

    local args=(
        "--verbose"
        "--type" "dmg"
        "--name" "${APP_NAME}"
        "--app-version" "${APP_VERSION}"
        "--vendor" "${VENDOR_NAME}"
        "--input" "${INPUT_DIR}"
        "--main-jar" "${MAIN_JAR}"
        "--main-class" "${MAIN_CLASS}"
        "--dest" "${OUTPUT_DIR}"
        "--runtime-image" "${MAC_RUNTIME_IMAGE}"
        "--file-associations" "${ASSOCIATIONS_FILE}"
        "--mac-sign"
        "--mac-signing-key-user-name" "${signing_identity}"
        "--resource-dir" "${RESOURCE_DIR}"
    )

    if [ -n "${ICON_FILE}" ]; then
        args+=("--icon" "${ICON_FILE}")
    fi

    local java_opts=(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
        "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"
        "-Dapple.awt.application.appearance=system"
        "-Dapple.awt.application.name=\"${APP_NAME}\""
        "-Ddeploy.local=false"
        "-Xdock:name=\"${APP_NAME}\""
        "-Dapple.laf.useScreenMenuBar=true"
        "-Dspring.profiles.active=release"
        "-Dloader.path=lib"
        "-Dchat2db.mode=DESKTOP"
        "-Dchat2db.runtime.mode=community"
        "-Dchat2db.network.status=OFFLINE"
        "-Dfile.encoding=UTF-8"
        "-Dserver.address=127.0.0.1"
        "-Dserver.port=10825"
        "-Xms128M"
    )

    for opt in "${java_opts[@]}"; do
        args+=("--java-options" "${opt}")
    done

    args+=(
        "--mac-package-identifier" "${APP_IDENTIFIER}"
        "--mac-app-category" "public.app-category.developer-tools"
    )

    echo "Running: jpackage ${args[*]}"
    jpackage "${args[@]}"
}

validate_packaged_dmg() {
    local dmg_file="$1"
    local mount_dir
    mount_dir=$(mktemp -d)

    cleanup_mount() {
        hdiutil detach "${mount_dir}" -quiet >/dev/null 2>&1 || true
        rm -rf "${mount_dir}"
    }

    if ! hdiutil attach "${dmg_file}" -readonly -nobrowse -mountpoint "${mount_dir}" >/dev/null; then
        cleanup_mount
        echo "Error: failed to mount generated DMG: ${dmg_file}" >&2
        exit 1
    fi

    local app_dir
    app_dir=$(find "${mount_dir}" -maxdepth 1 -name "${APP_NAME}.app" -print -quit)
    if [ -z "${app_dir}" ]; then
        cleanup_mount
        echo "Error: packaged app not found in DMG: ${APP_NAME}.app" >&2
        exit 1
    fi

    local contents_dir="${app_dir}/Contents"
    local plist_file="${contents_dir}/Info.plist"
    local app_root="${contents_dir}/app"
    local short_version
    local bundle_version
    local icon_name

    short_version=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "${plist_file}" 2>/dev/null || true)
    bundle_version=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "${plist_file}" 2>/dev/null || true)
    icon_name=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIconFile" "${plist_file}" 2>/dev/null || true)

    if [ "${short_version}" != "${APP_VERSION}" ] || [ "${bundle_version}" != "${APP_VERSION}" ]; then
        cleanup_mount
        echo "Error: packaged app version mismatch. expected=${APP_VERSION}, short=${short_version}, bundle=${bundle_version}" >&2
        exit 1
    fi

    if [ -z "${icon_name}" ]; then
        cleanup_mount
        echo "Error: CFBundleIconFile is empty in packaged Info.plist" >&2
        exit 1
    fi
    if [[ "${icon_name}" != *.icns ]]; then
        icon_name="${icon_name}.icns"
    fi
    if [ ! -f "${contents_dir}/Resources/${icon_name}" ]; then
        cleanup_mount
        echo "Error: packaged icon does not exist: Contents/Resources/${icon_name}" >&2
        exit 1
    fi

    if [ ! -f "${app_root}/dist/index.html" ]; then
        cleanup_mount
        echo "Error: packaged frontend entry missing: Contents/app/dist/index.html" >&2
        exit 1
    fi
    if [ -e "${app_root}/lib/dist/index.html" ]; then
        cleanup_mount
        echo "Error: invalid frontend path exists under external lib: Contents/app/lib/dist/index.html" >&2
        exit 1
    fi
    if [ ! -d "${app_root}/Frameworks/Chromium Embedded Framework.framework" ]; then
        cleanup_mount
        echo "Error: JCEF framework missing from Contents/app/Frameworks" >&2
        exit 1
    fi

    local flatlaf_jar
    local flatlaf_count
    flatlaf_count=$(find "${app_root}/lib" -maxdepth 1 -type f \
        -name "flatlaf-*.jar" -print | wc -l | tr -d '[:space:]')
    if [ "${flatlaf_count}" -ne 1 ]; then
        cleanup_mount
        echo "Error: expected exactly one FlatLaf runtime dependency in packaged external lib, found ${flatlaf_count}" >&2
        exit 1
    fi
    flatlaf_jar=$(find "${app_root}/lib" -maxdepth 1 -type f \
        -name "flatlaf-*.jar" -print -quit)

    local flatlaf_index
    local required_flatlaf_entry
    local required_flatlaf_entries=(
        "com/formdev/flatlaf/FlatLaf.class"
        "com/formdev/flatlaf/FlatDarkLaf.class"
        "com/formdev/flatlaf/themes/FlatMacLightLaf.class"
        "com/formdev/flatlaf/themes/FlatMacDarkLaf.class"
        "com/formdev/flatlaf/natives/libflatlaf-macos-x86_64.dylib"
        "com/formdev/flatlaf/natives/libflatlaf-macos-arm64.dylib"
    )
    flatlaf_index=$(mktemp)
    if ! jar tf "${flatlaf_jar}" > "${flatlaf_index}"; then
        rm -f "${flatlaf_index}"
        cleanup_mount
        echo "Error: failed to inspect packaged FlatLaf runtime dependency" >&2
        exit 1
    fi
    for required_flatlaf_entry in "${required_flatlaf_entries[@]}"; do
        if ! grep -Fxq "${required_flatlaf_entry}" "${flatlaf_index}"; then
            rm -f "${flatlaf_index}"
            cleanup_mount
            echo "Error: required FlatLaf entry missing from packaged runtime dependency: ${required_flatlaf_entry}" >&2
            exit 1
        fi
    done
    rm -f "${flatlaf_index}"

    local jcef_jar
    jcef_jar=$(find "${app_root}/lib" -maxdepth 1 -name "chat2db-community-jcef-*.jar" -print -quit)
    if [ -z "${jcef_jar}" ]; then
        cleanup_mount
        echo "Error: chat2db-community-jcef jar missing from packaged external lib" >&2
        exit 1
    fi

    local jar_index
    jar_index=$(mktemp)
    jar tf "${jcef_jar}" > "${jar_index}"

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
    local resource
    for resource in "${required_resources[@]}"; do
        if ! grep -Fxq "${resource}" "${jar_index}"; then
            rm -f "${jar_index}"
            cleanup_mount
            echo "Error: required JCEF i18n resource missing from packaged jar: ${resource}" >&2
            exit 1
        fi
    done
    rm -f "${jar_index}"

    if ! codesign --verify --deep --strict --verbose=2 "${app_dir}"; then
        cleanup_mount
        echo "Error: packaged app failed strict code-signature verification" >&2
        exit 1
    fi

    cleanup_mount
    echo "[check] packaged DMG metadata, resources, and strict app signature are valid"
}

validate_resources
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"
prepare_resource_dir
prepare_association_file
remove_existing_signatures
package_application

generated_dmg=$(find "${OUTPUT_DIR}" -name "*.dmg" -print -quit)
if [ -z "${generated_dmg}" ]; then
    echo "Error: no .dmg file found in output directory." >&2
    exit 1
fi

validate_packaged_dmg "${generated_dmg}"

if [ -n "${CUSTOM_DMG_FILENAME}" ]; then
    mv "${generated_dmg}" "${OUTPUT_DIR}/${CUSTOM_DMG_FILENAME}"
    echo "DMG output: ${OUTPUT_DIR}/${CUSTOM_DMG_FILENAME}"
else
    echo "DMG output: ${generated_dmg}"
fi
