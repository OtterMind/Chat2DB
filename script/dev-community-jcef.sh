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
FRONTEND_PID=""

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

cleanup() {
    local status=$?

    trap - EXIT INT TERM
    if [ -n "${FRONTEND_PID}" ]; then
        kill -TERM -- "-${FRONTEND_PID}" 2>/dev/null \
            || kill -TERM "${FRONTEND_PID}" 2>/dev/null \
            || true
        wait "${FRONTEND_PID}" 2>/dev/null || true
    fi
    exit "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
set -m

(
    cd "${CLIENT_DIR}"
    exec yarn run start:community:hot
) &
FRONTEND_PID=$!

echo "[dev] waiting for ${FRONTEND_URL}"
until curl --fail --silent --output /dev/null "${FRONTEND_URL}"; do
    if ! kill -0 "${FRONTEND_PID}" 2>/dev/null; then
        echo "[error] frontend exited before it became ready" >&2
        exit 1
    fi
    sleep 1
done

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
    -jar "${BACKEND_JAR}"
