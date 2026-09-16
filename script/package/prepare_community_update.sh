#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 5 ]; then
  echo "Usage: $0 <version> <release-epoch> <build-sha> <platform> <arch>" >&2
  exit 1
fi
VERSION="$1"
RELEASE_EPOCH="$2"
BUILD_SHA="$3"
PLATFORM="$4"
ARCH="$5"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${COMMUNITY_SOURCE_DIR:-${SCRIPT_DIR}/../..}" && pwd)
source "${SCRIPT_DIR}/community-version.sh"
NATIVE_VERSION=$(community_native_version "${VERSION}")
CHANNEL=STABLE
if [[ "${VERSION}" == *-beta.* ]]; then CHANNEL=BETA; fi
PACKAGE_DIR="${ROOT_DIR}/jpackage/output"
OUTPUT_DIR="${PACKAGE_DIR}/updates"
BASE_URL="https://github.com/OtterMind/Chat2DB/releases/download/v${VERSION}"
mkdir -p "${OUTPUT_DIR}"
generate() {
  bash "${SCRIPT_DIR}/generate_update_v2.sh" \
    "${VERSION}" "${NATIVE_VERSION}" COMMUNITY "${CHANNEL}" "${PLATFORM}" "${ARCH}" \
    "$1" "$2" "$3" "${OUTPUT_DIR}" "${BASE_URL}" "${RELEASE_EPOCH}" "${BUILD_SHA}" \
    "https://github.com/OtterMind/Chat2DB/releases/tag/v${VERSION}"
}
case "${ARCH}" in
  ARM64) mac_arch=arm64; deb_arch=arm64; rpm_arch=aarch64; image_arch=arm64 ;;
  X64) mac_arch=x64; deb_arch=amd64; rpm_arch=x86_64; image_arch=x86_64 ;;
  *) echo "Unsupported architecture" >&2; exit 1 ;;
esac
case "${PLATFORM}" in
  MACOS)
    archive="${PACKAGE_DIR}/community-update-${mac_arch}.tar.gz"
    capture_dir=$(mktemp -d)
    trap 'rm -rf "${capture_dir}"' EXIT
    bash "${SCRIPT_DIR}/capture_macos_update_package.sh" \
      "${PACKAGE_DIR}/Chat2DB-Community-${VERSION}-${mac_arch}.dmg" "${capture_dir}/package" "${ROOT_DIR}"
    tar -czf "${archive}" -C "${capture_dir}" package
    generate MACOS_APP_ARCHIVE "${archive}" "Contents/MacOS/Chat2DB Community"
    ;;
  WINDOWS)
    generate WINDOWS_EXE "${PACKAGE_DIR}/Chat2DB-Community-${VERSION}.exe" "Chat2DB Community.exe"
    ;;
  LINUX)
    generate LINUX_DEB "${PACKAGE_DIR}/Chat2DB-Community-${VERSION}-${deb_arch}.deb" "bin/Chat2DB Community"
    generate LINUX_RPM "${PACKAGE_DIR}/Chat2DB-Community-${VERSION}-${rpm_arch}.rpm" "bin/Chat2DB Community"
    generate LINUX_APPIMAGE "${PACKAGE_DIR}/Chat2DB-Community-${VERSION}-${image_arch}.AppImage" .
    ;;
  *) echo "Unsupported platform" >&2; exit 1 ;;
esac
