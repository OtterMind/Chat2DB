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
FRONTEND_HEALTH_URL="${FRONTEND_URL}"
FRONTEND_TIMEOUT_SECONDS=180
BACKEND_URL="http://127.0.0.1:10825/"
BACKEND_HEALTH_URL="${BACKEND_URL}api/system"
BACKEND_TIMEOUT_SECONDS=120
FRONTEND_PID=""
BACKEND_PID=""

case "$(uname -s)" in
    Darwin)
        JAVA_OPTIONS+=(
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
    exit "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
set -m

echo "[dev] checkout: ${ROOT_DIR}"
echo "[dev] JBR_HOME: ${JBR_HOME}"
echo "[dev] backend jar: ${BACKEND_JAR}"

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
    if curl --noproxy '*' --fail --silent --max-time 2 --output /dev/null "${FRONTEND_HEALTH_URL}"; then
        break
    fi
    if [ "${SECONDS}" -ge "${FRONTEND_DEADLINE}" ]; then
        echo "[error] frontend did not become ready within ${FRONTEND_TIMEOUT_SECONDS}s" >&2
        exit 1
    fi
    sleep 1
done
echo "[dev] frontend ready: ${FRONTEND_URL} (pid ${FRONTEND_PID})"

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
    -jar "${BACKEND_JAR}" &
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
