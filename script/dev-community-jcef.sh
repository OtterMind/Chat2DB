#!/usr/bin/env bash

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
ROOT_DIR=$(cd "${SCRIPT_DIR}/.." && pwd -P)
CLIENT_DIR="${ROOT_DIR}/chat2db-community-client"
BACKEND_TARGET_DIR="${ROOT_DIR}/chat2db-community-server/chat2db-community-start/target"
BACKEND_JAR="${BACKEND_TARGET_DIR}/chat2db-community.jar"
BACKEND_LIB_DIR="${BACKEND_TARGET_DIR}/lib"
JAVA_BIN="${JBR_HOME:?JBR_HOME must point to a JBR 17 runtime with JCEF}/bin/java"
JAVA_OPTIONS=()
FRONTEND_URL="http://127.0.0.1:8889/"
FRONTEND_HEALTH_URL="${FRONTEND_URL}umi.js"
FRONTEND_TIMEOUT_SECONDS=180
BACKEND_URL="http://127.0.0.1:10825/"
BACKEND_HEALTH_URL="${BACKEND_URL}api/system"
BACKEND_TIMEOUT_SECONDS=120
DESKTOP_READY_TIMEOUT_SECONDS=120
DESKTOP_READY_FILE="${TMPDIR:-/tmp}/chat2db-community-jcef-${USER:-developer}-$$.ready"
MAC_DEV_APP_NAME="Chat2DB Community Dev"
MAC_DEV_OUTPUT_DIR="${ROOT_DIR}/jpackage/output/dev-community-jcef"
MAC_DEV_APP_DIR="${MAC_DEV_OUTPUT_DIR}/${MAC_DEV_APP_NAME}.app"
MAC_DEV_EXECUTABLE="${MAC_DEV_APP_DIR}/Contents/MacOS/${MAC_DEV_APP_NAME}"
MAC_DEV_STAGING_DIR=""
FRONTEND_PID=""
BACKEND_PID=""

case "$(uname -s)" in
    Darwin)
        JAVA_OPTIONS+=(
            -XstartOnFirstThread
            --add-opens=java.desktop/sun.awt=ALL-UNNAMED
            --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
            --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED
            --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED
            -Dapple.awt.application.appearance=system
            "-Dapple.awt.application.name=Chat2DB Community"
            -Dapple.laf.useScreenMenuBar=true
        )
        ;;
    MINGW*|MSYS*|CYGWIN*)
        JAVA_BIN="${JBR_HOME}/bin/java.exe"
        JAVA_OPTIONS+=(
            --add-opens=java.desktop/sun.awt=ALL-UNNAMED
            --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
            -Dsun.java2d.d3d=false
        )
        ;;
esac

if [ -n "${CHAT2DB_DEV_UPDATE_DIRECTORY:-}" ]; then
    JAVA_OPTIONS+=("-Dchat2db.jcef.dev-update-directory=${CHAT2DB_DEV_UPDATE_DIRECTORY}")
fi

assert_port_available() {
    local host=$1
    local port=$2
    local name=$3

    if ! node -e '
        const portfinder = require(process.argv[1]);
        const port = Number(process.argv[2]);
        portfinder.getPortPromise({ port }).then(
            (availablePort) => process.exit(availablePort === port ? 0 : 1),
            () => process.exit(1),
        );
    ' "${CLIENT_DIR}/node_modules/@umijs/utils/compiled/portfinder" "${port}"; then
        echo "[error] ${name} port ${host}:${port} is unavailable; free this port on all local interfaces" >&2
        exit 1
    fi
}

terminate_process() {
    local pid=$1

    if [ -z "${pid}" ]; then
        return
    fi
    kill -TERM -- "-${pid}" 2>/dev/null \
        || kill -TERM "${pid}" 2>/dev/null \
        || true
}

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    terminate_process "${BACKEND_PID}"
    terminate_process "${FRONTEND_PID}"
    [ -z "${BACKEND_PID}" ] || wait "${BACKEND_PID}" 2>/dev/null || true
    [ -z "${FRONTEND_PID}" ] || wait "${FRONTEND_PID}" 2>/dev/null || true
    rm -f "${DESKTOP_READY_FILE}"
    if [ -n "${MAC_DEV_STAGING_DIR}" ]; then
        rm -rf "${MAC_DEV_STAGING_DIR}"
    fi
    exit "${status}"
}

prepare_macos_dev_app() {
    local input_dir
    local output_dir
    local framework_dir="${JBR_HOME}/../Frameworks"
    local icon_file="${ROOT_DIR}/jpackage/input/icons/community/logo.icns"
    local args
    local java_opts
    local opt

    command -v jpackage >/dev/null 2>&1 || {
        echo "[error] jpackage is required to launch Community JCEF on macOS" >&2
        exit 1
    }
    [ -d "${framework_dir}/Chromium Embedded Framework.framework" ] || {
        echo "[error] JBR_HOME does not provide macOS JCEF frameworks: ${framework_dir}" >&2
        exit 1
    }

    MAC_DEV_STAGING_DIR=$(mktemp -d "${TMPDIR:-/tmp}/chat2db-community-jcef-app.XXXXXX")
    input_dir="${MAC_DEV_STAGING_DIR}/input"
    output_dir="${MAC_DEV_STAGING_DIR}/output"
    mkdir -p "${input_dir}" "${output_dir}"
    cp "${BACKEND_JAR}" "${input_dir}/chat2db-community.jar"
    cp -R "${BACKEND_LIB_DIR}" "${input_dir}/lib"
    cp -R "${framework_dir}" "${input_dir}/Frameworks"

    args=(
        --type app-image
        --name "${MAC_DEV_APP_NAME}"
        --app-version 5.3.0
        --vendor "AiTa Technology (Hangzhou) Co., Ltd."
        --input "${input_dir}"
        --main-jar chat2db-community.jar
        --main-class org.springframework.boot.loader.launch.PropertiesLauncher
        --dest "${output_dir}"
        --runtime-image "${JBR_HOME}"
        --mac-package-identifier com.chat2db.community.dev
    )
    if [ -f "${icon_file}" ]; then
        args+=(--icon "${icon_file}")
    fi

    java_opts=(
        --add-opens=java.desktop/sun.awt=ALL-UNNAMED
        --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
        --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED
        --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED
        -Dapple.awt.application.appearance=system
        -Dapple.laf.useScreenMenuBar=true
        -Dspring.profiles.active=dev
        -Dloader.path=lib
        -Dchat2db.gui=true
        -Dchat2db.mode=DESKTOP
        -Dchat2db.runtime.mode=community
        -Dchat2db.jcef.web-frontend=true
        -Dchat2db.network.status=OFFLINE
        -Dfile.encoding=UTF-8
        "-Dchat2db.community.encryption-key-file=${CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE:-${HOME}/.config/chat2db-community/encryption.key}"
        -Dserver.address=127.0.0.1
        -Dserver.port=10825
        "-Dchat2db.jcef.ready-file=${DESKTOP_READY_FILE}"
        -Xms128M
    )
    for opt in "${java_opts[@]}"; do
        args+=(--java-options "${opt}")
    done
    if [ -n "${CHAT2DB_DEV_UPDATE_DIRECTORY:-}" ]; then
        args+=(--java-options "-Dchat2db.jcef.dev-update-directory=${CHAT2DB_DEV_UPDATE_DIRECTORY}")
    fi

    echo "[dev] building macOS JCEF app image: ${MAC_DEV_APP_DIR}"
    jpackage "${args[@]}"
    rm -rf "${MAC_DEV_OUTPUT_DIR}"
    mkdir -p "$(dirname "${MAC_DEV_OUTPUT_DIR}")"
    mv "${output_dir}" "${MAC_DEV_OUTPUT_DIR}"
    rm -rf "${MAC_DEV_STAGING_DIR}"
    MAC_DEV_STAGING_DIR=""
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
set -m

echo "[dev] checkout: ${ROOT_DIR}"
echo "[dev] JBR_HOME: ${JBR_HOME}"
echo "[dev] backend jar: ${BACKEND_JAR}"
rm -f "${DESKTOP_READY_FILE}"

assert_port_available 127.0.0.1 8889 frontend
assert_port_available 127.0.0.1 10825 backend

(
    cd "${CLIENT_DIR}"
    exec yarn run start:community:hot
) &
FRONTEND_PID=$!

echo "[dev] frontend pid ${FRONTEND_PID}; waiting up to ${FRONTEND_TIMEOUT_SECONDS}s for ${FRONTEND_HEALTH_URL}"
FRONTEND_DEADLINE=$((SECONDS + FRONTEND_TIMEOUT_SECONDS))
while true; do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        echo "[error] frontend exited before it became ready" >&2
        exit 1
    fi
    FRONTEND_CONTENT_TYPE=$(curl --noproxy '*' --fail --silent --max-time 2 --output /dev/null --write-out '%{content_type}' "${FRONTEND_HEALTH_URL}" 2>/dev/null || true)
    if [[ "${FRONTEND_CONTENT_TYPE}" == *javascript* ]]; then
        break
    fi
    if [ "${SECONDS}" -ge "${FRONTEND_DEADLINE}" ]; then
        echo "[error] frontend did not become ready within ${FRONTEND_TIMEOUT_SECONDS}s" >&2
        exit 1
    fi
    sleep 1
done
echo "[dev] frontend ready: ${FRONTEND_URL} (pid ${FRONTEND_PID})"

if [ "$(uname -s)" = "Darwin" ]; then
    prepare_macos_dev_app
    "${MAC_DEV_EXECUTABLE}" &
else
    "${JAVA_BIN}" \
        "${JAVA_OPTIONS[@]}" \
        "-Dloader.path=${BACKEND_LIB_DIR}" \
        -Dchat2db.gui=true \
        -Dchat2db.runtime.mode=community \
        -Dchat2db.mode=DESKTOP \
        -Dchat2db.jcef.web-frontend=true \
        -Dchat2db.network.status=OFFLINE \
        -Dfile.encoding=UTF-8 \
        "-Dchat2db.community.encryption-key-file=${CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE:-${HOME}/.config/chat2db-community/encryption.key}" \
        -Dserver.address=127.0.0.1 \
        -Dserver.port=10825 \
        -Dspring.profiles.active=dev \
        "-Dchat2db.jcef.ready-file=${DESKTOP_READY_FILE}" \
        -jar "${BACKEND_JAR}" &
fi
BACKEND_PID=$!

echo "[dev] JCEF backend pid ${BACKEND_PID}; waiting up to ${BACKEND_TIMEOUT_SECONDS}s for ${BACKEND_HEALTH_URL}"
BACKEND_DEADLINE=$((SECONDS + BACKEND_TIMEOUT_SECONDS))
while true; do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        echo "[error] frontend exited while the JCEF backend was starting" >&2
        exit 1
    fi
    if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
        BACKEND_STATUS=0
        wait "${BACKEND_PID}" || BACKEND_STATUS=$?
        if [ "${BACKEND_STATUS}" -eq 0 ]; then
            BACKEND_STATUS=1
        fi
        echo "[error] JCEF backend exited before it became ready" >&2
        exit "${BACKEND_STATUS}"
    fi
    BACKEND_HEALTH_RESPONSE=$(curl --noproxy '*' --fail --silent --max-time 2 "${BACKEND_HEALTH_URL}" 2>/dev/null || true)
    if [[ "${BACKEND_HEALTH_RESPONSE}" == *'"success":true'* ]]; then
        break
    fi
    if [ "${SECONDS}" -ge "${BACKEND_DEADLINE}" ]; then
        echo "[error] JCEF backend did not become ready within ${BACKEND_TIMEOUT_SECONDS}s" >&2
        exit 1
    fi
    sleep 1
done

echo "[dev] JCEF backend ready: ${BACKEND_URL} (pid ${BACKEND_PID})"
echo "[dev] waiting up to ${DESKTOP_READY_TIMEOUT_SECONDS}s for the JCEF desktop window"
DESKTOP_READY_DEADLINE=$((SECONDS + DESKTOP_READY_TIMEOUT_SECONDS))
while [ ! -f "${DESKTOP_READY_FILE}" ]; do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        echo "[error] frontend exited while the JCEF desktop window was starting" >&2
        exit 1
    fi
    if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
        echo "[error] JCEF process exited before the desktop window became ready" >&2
        exit 1
    fi
    if [ "${SECONDS}" -ge "${DESKTOP_READY_DEADLINE}" ]; then
        echo "[error] JCEF desktop window did not become ready within ${DESKTOP_READY_TIMEOUT_SECONDS}s" >&2
        exit 1
    fi
    sleep 1
done
echo "[dev] JCEF desktop window ready (pid ${BACKEND_PID})"
echo "[dev] logs are attached to this terminal"

while kill -0 "${FRONTEND_PID}" 2>/dev/null && kill -0 "${BACKEND_PID}" 2>/dev/null; do
    sleep 1
done

if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    BACKEND_STATUS=0
    wait "${BACKEND_PID}" || BACKEND_STATUS=$?
    exit "${BACKEND_STATUS}"
fi

FRONTEND_STATUS=0
wait "${FRONTEND_PID}" || FRONTEND_STATUS=$?
if [ "${FRONTEND_STATUS}" -eq 0 ]; then
    FRONTEND_STATUS=1
fi
echo "[error] frontend exited while the JCEF backend was running" >&2
exit "${FRONTEND_STATUS}"
