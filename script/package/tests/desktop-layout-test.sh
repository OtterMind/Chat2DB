#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "${SCRIPT_DIR}/desktop_layout.sh"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "${WORK_DIR}"' EXIT

mkdir -p "${WORK_DIR}/jpackage" "${WORK_DIR}/frontend" "${WORK_DIR}/empty"
echo CHAT2DB_DESKTOP_LAYOUT=versioned-thin > "${WORK_DIR}/jpackage/build-layout.properties"
echo '<html>desktop</html>' > "${WORK_DIR}/frontend/index.html"
jar cf "${WORK_DIR}/tool.jar" -C "${WORK_DIR}/empty" .

for platform in mac win linux; do
    input="${WORK_DIR}/jpackage/input/${platform}"
    mkdir -p "${input}/runtime/lib" "${input}/tools"
    cp "${WORK_DIR}/tool.jar" "${input}/tools/chat2db-updater.jar"
    cp "${WORK_DIR}/tool.jar" "${input}/tools/chat2db-bootstrap.jar"
    cp "${WORK_DIR}/tool.jar" "${input}/runtime/custom-app.jar"
    echo '{"mainJar":"runtime/custom-app.jar"}' > "${input}/runtime/launch.json"
done

bash "${SCRIPT_DIR}/prepare_desktop_layout.sh" 5.3.7-beta.4 "${WORK_DIR}/frontend" 2 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "${WORK_DIR}"
chat2db_load_desktop_layout "${WORK_DIR}"
test "${MAIN_JAR}" = tools/chat2db-bootstrap.jar
test "${MAIN_CLASS}" = ai.chat2db.community.bootstrap.Chat2DBBootstrap

for platform in mac win linux; do
    input="${WORK_DIR}/jpackage/input/${platform}"
    chat2db_validate_desktop_input "${input}"
    cmp "${WORK_DIR}/frontend/index.html" "${input}/runtime/dist/index.html"
    jq -e '.version == "5.3.7-beta.4" and .releaseEpoch == 2' "${input}/version.json" >/dev/null
    test ! -e "${input}/dist"
done

APP_NAME='Example App'
VENDOR_NAME='Example Vendor'
INPUT_DIR="${WORK_DIR}/input with spaces"
chat2db_jpackage_arguments 5.3.704 "${WORK_DIR}/output with spaces" "${WORK_DIR}/jbr"
test "${#CHAT2DB_JPACKAGE_ARGS[@]}" = 16
test "${CHAT2DB_JPACKAGE_ARGS[1]}" = 'Example App'
test "${CHAT2DB_JPACKAGE_ARGS[7]}" = "${INPUT_DIR}"
test "${CHAT2DB_JPACKAGE_ARGS[9]}" = tools/chat2db-bootstrap.jar

rm "${WORK_DIR}/jpackage/input/mac/runtime/custom-app.jar"
if chat2db_validate_desktop_input "${WORK_DIR}/jpackage/input/mac" 2>/dev/null; then
    echo 'Missing product JAR must fail validation' >&2
    exit 1
fi
echo 'Shared desktop layout tests passed.'
