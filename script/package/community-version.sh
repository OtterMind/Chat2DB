#!/usr/bin/env bash

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/desktop_layout.sh"

# Keep display versions separate from the numeric versions required by native installers.
community_native_version() {
    local version="${1:-}"
    if [[ ! "${version}" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})(-beta\.([1-9][0-9]?))?$ ]]; then
        echo "Error: expected major.minor.patch or major.minor.patch-beta.N: ${version}" >&2
        return 1
    fi
    local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
    local beta="${BASH_REMATCH[5]:-}" stage=99
    if [ -n "${beta}" ]; then
        if (( beta > 98 )); then
            echo "Error: Beta sequence must be 1..98; 99 is reserved for the stable release" >&2
            return 1
        fi
        stage="${beta}"
    fi
    local build=$((patch * 100 + stage))
    if (( major > 255 || minor > 255 || build > 65535 )); then
        echo "Error: native version exceeds platform limits: ${version}" >&2
        return 1
    fi
    chat2db_jpackage_version "${version}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    community_native_version "${1:-}"
fi
