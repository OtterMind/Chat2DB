#!/usr/bin/env bash

set -euo pipefail

if [ -z "${1:-}" ]; then
    echo "Usage: $0 <version>" >&2
    exit 1
fi

APP_VERSION="$1"
APP_NAME="Chat2DB Community"
ARTIFACT_BASE="Chat2DB-Community"

PACKAGING_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PROJECT_ROOT=$(cd "${COMMUNITY_SOURCE_DIR:-${PACKAGING_ROOT}}" && pwd)
source "${PACKAGING_ROOT}/script/package/community-version.sh"
NATIVE_VERSION=$(community_native_version "${APP_VERSION}")
OUTPUT_DIR="${PROJECT_ROOT}/jpackage/output"
ISS_TEMPLATE="${PACKAGING_ROOT}/jpackage/installer.iss"
ICON_FILE="${PROJECT_ROOT}/jpackage/input/icons/community/logo.ico"
REPO_CHINESE_ISL="${PACKAGING_ROOT}/jpackage/lang/ChineseSimplified.isl"

SIGNED_MSI="${OUTPUT_DIR}/${ARTIFACT_BASE}-${APP_VERSION}.msi"
OUTPUT_BASE="${ARTIFACT_BASE}-${APP_VERSION}"
FINAL_EXE="${OUTPUT_DIR}/${OUTPUT_BASE}.exe"

if [ ! -f "${SIGNED_MSI}" ]; then
    echo "[error] MSI not found: ${SIGNED_MSI}" >&2
    ls -la "${OUTPUT_DIR}" >&2
    exit 1
fi
if [ ! -f "${ISS_TEMPLATE}" ]; then
    echo "[error] installer.iss template missing: ${ISS_TEMPLATE}" >&2
    exit 1
fi

ISCC_CANDIDATES=(
    "iscc"
    "iscc.exe"
    "/c/Program Files (x86)/Inno Setup 6/ISCC.exe"
    "/c/Program Files/Inno Setup 6/ISCC.exe"
    "C:/Program Files (x86)/Inno Setup 6/ISCC.exe"
    "C:/Program Files/Inno Setup 6/ISCC.exe"
)
ISCC=""
for cand in "${ISCC_CANDIDATES[@]}"; do
    if command -v "${cand}" >/dev/null 2>&1; then
        ISCC="$(command -v "${cand}")"
        break
    fi
    if [ -f "${cand}" ]; then
        ISCC="${cand}"
        break
    fi
done
if [ -z "${ISCC}" ]; then
    echo "[error] ISCC.exe not found. Run 'choco install innosetup -y' before this script." >&2
    exit 1
fi

to_unix_path() {
    local input="${1:-}"
    if [ -z "${input}" ]; then
        return 1
    fi

    if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "${input}"
    else
        printf '%s\n' "${input}"
    fi
}

find_chinese_isl() {
    local candidate=""
    local -a candidates=()
    local iscc_unix=""
    local iscc_dir=""

    iscc_unix=$(to_unix_path "${ISCC}" 2>/dev/null || true)
    if [ -n "${iscc_unix}" ]; then
        iscc_dir=$(cd "$(dirname "${iscc_unix}")" && pwd)
        candidates+=(
            "${iscc_dir}/Languages/ChineseSimplified.isl"
            "${iscc_dir}/../Languages/ChineseSimplified.isl"
            "${iscc_dir}/../../Inno Setup 6/Languages/ChineseSimplified.isl"
        )
    fi

    candidates+=(
        "/c/Program Files (x86)/Inno Setup 6/Languages/ChineseSimplified.isl"
        "/c/Program Files/Inno Setup 6/Languages/ChineseSimplified.isl"
        "/c/ProgramData/chocolatey/lib/InnoSetup/tools/Languages/ChineseSimplified.isl"
        "/c/ProgramData/chocolatey/lib/innosetup/tools/Languages/ChineseSimplified.isl"
        "C:/Program Files (x86)/Inno Setup 6/Languages/ChineseSimplified.isl"
        "C:/Program Files/Inno Setup 6/Languages/ChineseSimplified.isl"
    )

    for candidate in "${candidates[@]}"; do
        if [ -f "${candidate}" ]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done

    if command -v find >/dev/null 2>&1; then
        while IFS= read -r candidate; do
            if [ -n "${candidate}" ] && [ -f "${candidate}" ]; then
                printf '%s\n' "${candidate}"
                return 0
            fi
        done < <(find \
            "/c/Program Files (x86)" \
            "/c/Program Files" \
            "/c/ProgramData/chocolatey/lib" \
            -path "*/Languages/ChineseSimplified.isl" \
            -print 2>/dev/null)
    fi

    return 1
}

MSI_WIN=$(cygpath -m "${SIGNED_MSI}")
OUTPUT_DIR_WIN=$(cygpath -m "${OUTPUT_DIR}")
ICON_WIN=""
if [ -f "${ICON_FILE}" ]; then
    ICON_WIN=$(cygpath -m "${ICON_FILE}")
fi

CHINESE_LANG_DEFINE=""
if [ -f "${REPO_CHINESE_ISL}" ]; then
    CHINESE_LANG_DEFINE="$(cygpath -m "${REPO_CHINESE_ISL}")"
else
    CHINESE_LANG_PATH=$(find_chinese_isl || true)
    if [ -n "${CHINESE_LANG_PATH}" ]; then
        CHINESE_LANG_DEFINE='compiler:Languages\ChineseSimplified.isl'
    fi
fi

GENERATED_ISS="${OUTPUT_DIR}/installer.community.generated.iss"
{
    echo "#define AppName \"${APP_NAME}\""
    echo "#define AppVersion \"${APP_VERSION}\""
    echo "#define AppNativeVersion \"${NATIVE_VERSION}\""
    echo "#define MsiFile \"${MSI_WIN}\""
    echo "#define OutputDir \"${OUTPUT_DIR_WIN}\""
    echo "#define OutputBaseName \"${OUTPUT_BASE}\""
    if [ -n "${ICON_WIN}" ]; then
        echo "#define IconFile \"${ICON_WIN}\""
    fi
    if [ -n "${CHINESE_LANG_DEFINE}" ]; then
        echo "#define ChineseMessagesFile \"${CHINESE_LANG_DEFINE}\""
    fi
    cat "${ISS_TEMPLATE}"
} > "${GENERATED_ISS}"

"${ISCC}" "$(cygpath -w "${GENERATED_ISS}")"

if [ ! -f "${FINAL_EXE}" ]; then
    echo "[error] Inno Setup did not produce ${FINAL_EXE}" >&2
    ls -la "${OUTPUT_DIR}" >&2
    exit 1
fi

echo "[done] wrapped EXE: ${FINAL_EXE}"
