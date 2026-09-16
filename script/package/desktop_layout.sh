#!/usr/bin/env bash

chat2db_load_desktop_layout() {
    local project_root="$1"
    local layout_file="${project_root}/jpackage/build-layout.properties"
    if [ ! -f "${layout_file}" ]; then
        echo "Error: desktop build layout marker is missing: ${layout_file}" >&2
        return 1
    fi

    CHAT2DB_DESKTOP_LAYOUT=$(sed -n 's/^CHAT2DB_DESKTOP_LAYOUT=//p' "${layout_file}" | tail -n 1)
    case "${CHAT2DB_DESKTOP_LAYOUT}" in
        bridge-fat)
            MAIN_JAR="${CHAT2DB_LEGACY_MAIN_JAR:?Legacy main JAR is required}"
            MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"
            ;;
        versioned-thin)
            MAIN_JAR="tools/chat2db-bootstrap.jar"
            MAIN_CLASS="ai.chat2db.community.bootstrap.Chat2DBBootstrap"
            ;;
        *)
            echo "Error: unsupported desktop build layout: ${CHAT2DB_DESKTOP_LAYOUT:-<empty>}" >&2
            return 1
            ;;
    esac
    export CHAT2DB_DESKTOP_LAYOUT MAIN_JAR MAIN_CLASS
}

chat2db_jpackage_version() {
    local release_version="${1#v}"
    local explicit_native_version="${2:-}"
    if [ -n "${explicit_native_version}" ]; then
        if [[ ! "${explicit_native_version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
            echo "Error: unsupported native package version: ${explicit_native_version}" >&2
            return 1
        fi
        printf '%s\n' "${explicit_native_version}"
        return 0
    fi
    if [[ ! "${release_version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-([0-9A-Za-z-]+)(\.([0-9A-Za-z-]+))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$ ]]; then
        echo "Error: unsupported desktop release version: ${1}" >&2
        return 1
    fi
    local base="${release_version%%[-+]*}"
    local major minor patch suffix stage identifier
    IFS='.' read -r major minor patch <<< "${base}"
    suffix="${release_version#${base}}"
    stage=99
    if [[ "${suffix}" =~ ^-beta\.([0-9]+) ]]; then
        identifier="${BASH_REMATCH[1]}"
        stage="${identifier}"
    elif [[ "${suffix}" =~ ^-rc\.([0-9]+) ]]; then
        identifier="${BASH_REMATCH[1]}"
        stage="${identifier}"
    elif [[ "${suffix}" =~ ^- ]]; then
        echo "Error: unsupported prerelease label for native package version: ${1}" >&2
        return 1
    fi
    if (( stage < 1 || stage > 99 )); then
        echo "Error: prerelease sequence must map to native build range 1..99: ${1}" >&2
        return 1
    fi
    local native_build=$((patch * 100 + stage))
    if (( native_build > 65535 )); then
        echo "Error: native package build exceeds platform limit: ${1}" >&2
        return 1
    fi
    printf '%s.%s.%s\n' "${major}" "${minor}" "${native_build}"
}

chat2db_validate_desktop_input() {
    local input_dir="$1"
    if [ ! -f "${input_dir}/${MAIN_JAR}" ]; then
        echo "Error: desktop main JAR is missing: ${input_dir}/${MAIN_JAR}" >&2
        return 1
    fi
    if [ ! -f "${input_dir}/tools/chat2db-updater.jar" ]; then
        echo "Error: update helper is missing: ${input_dir}/tools/chat2db-updater.jar" >&2
        return 1
    fi
    if ! command -v jar >/dev/null 2>&1; then
        echo "Error: JDK jar command is required to validate the update helper" >&2
        return 1
    fi
    if jar tf "${input_dir}/tools/chat2db-updater.jar" \
            | grep -E '(^|/)ai/chat2db/[^/]+/jcef/|(^|/)com/fasterxml/jackson/' >/dev/null; then
        echo "Error: update helper exposes unisolated classes to the desktop launcher classpath" >&2
        return 1
    fi
    if [ ! -f "${input_dir}/version.json" ]; then
        echo "Error: installed version metadata is missing: ${input_dir}/version.json" >&2
        return 1
    fi

    if [ "${CHAT2DB_DESKTOP_LAYOUT}" = "versioned-thin" ]; then
        local required
        for required in \
            "$(jq -er .mainJar "${input_dir}/runtime/launch.json")" \
            runtime/launch.json \
            runtime/dist/index.html; do
            if [ ! -f "${input_dir}/${required}" ]; then
                echo "Error: thin desktop runtime file is missing: ${input_dir}/${required}" >&2
                return 1
            fi
        done
        if [ ! -d "${input_dir}/runtime/lib" ]; then
            echo "Error: thin desktop runtime library directory is missing: ${input_dir}/runtime/lib" >&2
            return 1
        fi
    elif [ ! -f "${input_dir}/dist/index.html" ]; then
        echo "Error: bridge desktop frontend is missing: ${input_dir}/dist/index.html" >&2
        return 1
    fi
}

chat2db_update_java_options() {
    local key_id="${CHAT2DB_UPDATE_KEY_ID:-}"
    local public_key="${CHAT2DB_UPDATE_PUBLIC_KEY_B64:-}"
    if { [ -n "${key_id}" ] && [ -z "${public_key}" ]; } || \
       { [ -z "${key_id}" ] && [ -n "${public_key}" ]; }; then
        echo "Error: CHAT2DB_UPDATE_KEY_ID and CHAT2DB_UPDATE_PUBLIC_KEY_B64 must be set together" >&2
        return 1
    fi
    if [ -n "${key_id}" ]; then
        printf '%s\n' "-Dchat2db.update.key-id=${key_id}"
        printf '%s\n' "-Dchat2db.update.public-key=${public_key}"
    fi
}

chat2db_capture_update_package() {
    local image_root="$1"
    local output_dir="$2"
    local version_file
    version_file=$(find "${image_root}" -type f -path '*/app/version.json' -print -quit)
    if [ -z "${version_file}" ]; then
        echo "Error: jpackage app-image does not contain app/version.json: ${image_root}" >&2
        return 1
    fi

    local app_dir install_root app_parent
    app_dir=$(dirname "${version_file}")
    app_parent=$(dirname "${app_dir}")
    if [ "$(basename "${app_parent}")" = "Contents" ] \
            && [[ "$(basename "$(dirname "${app_parent}")")" == *.app ]]; then
        install_root=$(dirname "${app_parent}")
    elif [ "$(basename "${app_parent}")" = "lib" ]; then
        install_root=$(dirname "${app_parent}")
    else
        install_root="${app_parent}"
    fi
    rm -rf "${output_dir}"
    mkdir -p "${output_dir}"
    cp -R "${install_root}/." "${output_dir}/"
    chat2db_validate_update_package "${output_dir}"
}

chat2db_validate_update_package() {
    local package_dir="$1"
    local version_file app_dir
    version_file=$(find "${package_dir}" -type f -path '*/app/version.json' -print -quit)
    if [ -z "${version_file}" ]; then
        echo "Error: captured full package has no app/version.json" >&2
        return 1
    fi
    app_dir=$(dirname "${version_file}")
    if [ "${CHAT2DB_DESKTOP_LAYOUT}" = "versioned-thin" ]; then
        local required
        for required in \
            tools/chat2db-bootstrap.jar \
            tools/chat2db-updater.jar \
            runtime/launch.json \
            "$(jq -er .mainJar "${app_dir}/runtime/launch.json")" \
            runtime/dist/index.html; do
            if [ ! -f "${app_dir}/${required}" ]; then
                echo "Error: captured full package app is missing ${required}" >&2
                return 1
            fi
        done
        if [ ! -d "${app_dir}/runtime/lib" ]; then
            echo "Error: captured full package app is missing runtime/lib" >&2
            return 1
        fi
    fi

}

# Common native launcher arguments; platform scripts add their packaging options.
chat2db_jpackage_arguments() {
    local native_version="$1" destination="$2" runtime_image="$3"
    CHAT2DB_JPACKAGE_ARGS=(
        --name "${APP_NAME}"
        --app-version "${native_version}"
        --vendor "${VENDOR_NAME}"
        --input "${INPUT_DIR}"
        --main-jar "${MAIN_JAR}"
        --main-class "${MAIN_CLASS}"
        --dest "${destination}"
        --runtime-image "${runtime_image}"
    )
}
