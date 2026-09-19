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

# A stale JAR in the jpackage input root would be copied into the installed app.
cp "${WORK_DIR}/tool.jar" "${WORK_DIR}/jpackage/input/win/stale-root.jar"
if chat2db_validate_desktop_input "${WORK_DIR}/jpackage/input/win" 2>/dev/null; then
    echo 'Root-level JAR must fail thin desktop validation' >&2
    exit 1
fi
rm "${WORK_DIR}/jpackage/input/win/stale-root.jar"
chat2db_validate_desktop_input "${WORK_DIR}/jpackage/input/win"

# The launcher configuration is the only path that delivers the update signing key.
APP_IMAGE="${WORK_DIR}/app image"
mkdir -p "${APP_IMAGE}/lib/app"
printf '[Application]\napp.classpath=$APPDIR/runtime/app.jar\n' > "${APP_IMAGE}/lib/app/Example App.cfg"
chat2db_verify_launcher_update_options "${APP_IMAGE}"
CHAT2DB_UPDATE_KEY_ID='test-key'
CHAT2DB_UPDATE_PUBLIC_KEY_B64='dGVzdC1rZXk='
if chat2db_verify_launcher_update_options "${APP_IMAGE}" 2>/dev/null; then
    echo 'Launcher configuration without key options must fail validation' >&2
    exit 1
fi
printf 'java-options=-Dchat2db.update.key-id=test-key\njava-options=-Dchat2db.update.public-key=dGVzdC1rZXk=\n' \
    >> "${APP_IMAGE}/lib/app/Example App.cfg"
chat2db_verify_launcher_update_options "${APP_IMAGE}"
unset CHAT2DB_UPDATE_KEY_ID CHAT2DB_UPDATE_PUBLIC_KEY_B64
chat2db_verify_launcher_update_options "${APP_IMAGE}"

chat2db_verify_update_java_option_arguments --java-options -Xms128M
CHAT2DB_UPDATE_KEY_ID='test-key'
CHAT2DB_UPDATE_PUBLIC_KEY_B64='dGVzdC1rZXk='
if chat2db_verify_update_java_option_arguments --java-options -Xms128M 2>/dev/null; then
    echo 'jpackage arguments without key options must fail validation' >&2
    exit 1
fi
chat2db_verify_update_java_option_arguments \
    --java-options "-Dchat2db.update.key-id=test-key" \
    --java-options "-Dchat2db.update.public-key=dGVzdC1rZXk="
unset CHAT2DB_UPDATE_KEY_ID CHAT2DB_UPDATE_PUBLIC_KEY_B64

# Studio packaging scripts turn this helper's output into jpackage options, so keep
# it callable and keep its output shape stable.
CHAT2DB_UPDATE_KEY_ID='test-key'
CHAT2DB_UPDATE_PUBLIC_KEY_B64='dGVzdC1rZXk='
update_options=$(chat2db_update_java_options)
test "$(printf '%s\n' "${update_options}" | wc -l | tr -d ' ')" = 2
printf '%s\n' "${update_options}" | grep -qx -- '-Dchat2db.update.key-id=test-key'
printf '%s\n' "${update_options}" | grep -qx -- '-Dchat2db.update.public-key=dGVzdC1rZXk='

unset CHAT2DB_UPDATE_PUBLIC_KEY_B64
if chat2db_update_java_options >/dev/null 2>&1; then
    echo 'Update key id without a public key must fail option generation' >&2
    exit 1
fi

CHAT2DB_UPDATE_PUBLIC_KEY_B64='dGVzdC1rZXk='
unset CHAT2DB_UPDATE_KEY_ID
if chat2db_update_java_options >/dev/null 2>&1; then
    echo 'Update public key without a key id must fail option generation' >&2
    exit 1
fi

unset CHAT2DB_UPDATE_KEY_ID CHAT2DB_UPDATE_PUBLIC_KEY_B64
test -z "$(chat2db_update_java_options)"

echo 'Shared desktop layout tests passed.'
