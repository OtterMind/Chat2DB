#!/bin/bash
set -euo pipefail

# --- Usage ---
# ./generate_metadata.sh <version> <source_directory> [output_directory]
#
# Generates publication manifests from a canonical payload directory.
# The source directory must contain exactly the three managed payloads:
#   chat2db-community.jar
#   lib.zip
#   dist.zip
#
# Outputs (in output_directory, defaulting to source_directory):
#   github-version.json   GitHub Release manifest (published as version.json)
#   cdn-version.json      CDN bridge compatibility manifest
#   local_version.json    Copy of github-version.json for packaged clients
#   latest_version.json   lightweight GitHub Release latest pointer
#   cdn-latest-version.json legacy CDN latest-version pointer (bridge only)
#   receipt.json          Canonical artifact receipt with SHA-256 and sizes
#
# Environment:
#   COMMUNITY_GITHUB_REPOSITORY   Default: OtterMind/Chat2DB
#   CDN_BASE_URL                  Default: https://cdn.chat2db-ai.com/community/updates

# --- 1. Argument validation ---
if [[ "$#" -lt 2 ]]; then
    echo "Error: missing arguments."
    echo "Usage: $0 <version> <source_directory> [output_directory]"
    exit 1
fi

VERSION="$1"
SOURCE_DIR="$2"
OUTPUT_DIR="${3:-$SOURCE_DIR}"

COMMUNITY_GITHUB_REPOSITORY="${COMMUNITY_GITHUB_REPOSITORY:-OtterMind/Chat2DB}"
CDN_BASE_URL="${CDN_BASE_URL:-${COMMUNITY_UPDATE_BASE_URL:-https://cdn.chat2db-ai.com/community/updates}}"

# Check directories.
if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "Error: source directory does not exist: $SOURCE_DIR"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

# --- Cross-platform SHA256 helper ---
get_sha256() {
  local FILE_TO_HASH=$1
  if command -v shasum &> /dev/null; then
    shasum -a 256 "$FILE_TO_HASH" | awk '{print $1}' | tr '[:upper:]' '[:lower:]'
  elif command -v sha256sum &> /dev/null; then
    sha256sum "$FILE_TO_HASH" | awk '{print $1}' | tr '[:upper:]' '[:lower:]'
  elif command -v certutil &> /dev/null; then
    certutil -hashfile "$FILE_TO_HASH" SHA256 | sed -n '2p' | tr -d ' \r\n' | tr '[:upper:]' '[:lower:]'
  else
    echo "Error: no supported hash tool found (shasum, sha256sum, or certutil)." >&2
    exit 1
  fi
}

get_file_size() {
  local FILE=$1
  if [[ "$(uname)" == "Darwin" ]]; then # macOS
    stat -f %z "$FILE"
  else # Linux & Git Bash on Windows
    stat -c %s "$FILE"
  fi
}

# --- 2. Validate canonical payloads ---
PAYLOADS=(chat2db-community.jar lib.zip dist.zip)
for payload in "${PAYLOADS[@]}"; do
  if [[ ! -f "$SOURCE_DIR/$payload" ]]; then
    echo "Error: canonical payload missing: $SOURCE_DIR/$payload"
    exit 1
  fi
done

# --- 3. Build the shared files array (order matters) ---
# File metadata is identical in GitHub and CDN manifests except for the URL.
# Avoid associative arrays for bash 3.2 compatibility (macOS default shell).
get_payload_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
  esac
}

get_payload_local_target() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community.jar" ;;
    lib.zip) echo "lib" ;;
    dist.zip) echo "dist" ;;
  esac
}

get_payload_type() {
  case "$1" in
    chat2db-community.jar) echo "jar" ;;
    lib.zip) echo "zip" ;;
    dist.zip) echo "zip" ;;
  esac
}

json_files_array="[]"
receipt_files_array="[]"

for server_file_name in "${PAYLOADS[@]}"; do
  file_path="$SOURCE_DIR/$server_file_name"
  file_size_byte=$(get_file_size "$file_path")
  sha256=$(get_sha256 "$file_path")
  id=$(get_payload_id "$server_file_name")
  local_target_name=$(get_payload_local_target "$server_file_name")
  type=$(get_payload_type "$server_file_name")

  # Manifest file object (legacy schema plus GitHub-only top-level fields in GitHub variant)
  json_file_object=$(jq -n \
      --arg id "$id" \
      --arg serverFileName "$server_file_name" \
      --arg localTargetName "$local_target_name" \
      --arg sha256 "$sha256" \
      --arg type "$type" \
      --argjson fileSizeByte "$file_size_byte" \
      '{id: $id, serverFileName: $serverFileName, localTargetName: $localTargetName, sha256: $sha256, type: $type, extractTo: null, updateStrategy: null, fileSizeByte: $fileSizeByte, deleted: false}')

  json_files_array=$(echo "$json_files_array" | jq --argjson obj "$json_file_object" '. + [$obj]')

  # Receipt file object
  receipt_file_object=$(jq -n \
      --arg id "$id" \
      --arg serverFileName "$server_file_name" \
      --arg sha256 "$sha256" \
      --argjson size "$file_size_byte" \
      '{id: $id, serverFileName: $serverFileName, sha256: $sha256, size: $size}')

  receipt_files_array=$(echo "$receipt_files_array" | jq --argjson obj "$receipt_file_object" '. + [$obj]')
done

# --- 4. Render GitHub manifest ---
github_url_prefix="https://github.com/${COMMUNITY_GITHUB_REPOSITORY}/releases/download/v${VERSION}"
github_files_array=$(echo "$json_files_array" | jq \
    --arg prefix "$github_url_prefix" \
    '[.[] | .url = "\($prefix)/\(.serverFileName)"]')

github_manifest=$(jq -n \
    --arg version "$VERSION" \
    --arg releaseNotes "Known issue fixes" \
    --arg releasePageUrl "https://github.com/${COMMUNITY_GITHUB_REPOSITORY}/releases/tag/v${VERSION}" \
    --argjson files "$github_files_array" \
    '{version: $version, releaseNotes: $releaseNotes, releasePageUrl: $releasePageUrl, forceUpdate: false, files: $files, launchCommand: null}')

# --- 5. Render CDN bridge manifest ---
cdn_files_array=$(echo "$json_files_array" | jq \
    --arg prefix "${CDN_BASE_URL}/${VERSION}" \
    '[.[] | .url = "\($prefix)/\(.serverFileName)"]')

cdn_manifest=$(jq -n \
    --arg version "$VERSION" \
    --arg releaseNotes "Known issue fixes" \
    --argjson files "$cdn_files_array" \
    '{version: $version, releaseNotes: $releaseNotes, files: $files, launchCommand: null}')

# --- 6. Write outputs ---
GITHUB_VERSION_FILE="$OUTPUT_DIR/github-version.json"
CDN_VERSION_FILE="$OUTPUT_DIR/cdn-version.json"
LOCAL_VERSION_FILE="$OUTPUT_DIR/local_version.json"
LATEST_VERSION_FILE="$OUTPUT_DIR/latest_version.json"
CDN_LATEST_VERSION_FILE="$OUTPUT_DIR/cdn-latest-version.json"
RECEIPT_FILE="$OUTPUT_DIR/receipt.json"

echo "$github_manifest" > "$GITHUB_VERSION_FILE"
echo "$cdn_manifest" > "$CDN_VERSION_FILE"
# Packaged clients receive the GitHub manifest as their local baseline.
cp "$GITHUB_VERSION_FILE" "$LOCAL_VERSION_FILE"

# The GitHub pointer is intentionally small: check requests read only this
# asset, while download fetches and verifies the complete version.json.
manifest_sha256=$(get_sha256 "$GITHUB_VERSION_FILE")
latest_json=$(jq -n \
    --arg version "$VERSION" \
    --arg metadataSha256 "$manifest_sha256" \
    --arg releaseNotes "Known issue fixes" \
    --arg releasePageUrl "https://github.com/${COMMUNITY_GITHUB_REPOSITORY}/releases/tag/v${VERSION}" \
    '{version: $version, metadataSha256: $metadataSha256, releaseNotes: $releaseNotes, releasePageUrl: $releasePageUrl, forceUpdate: false}')
echo "$latest_json" > "$LATEST_VERSION_FILE"

# CDN bridge pointer (legacy schema, frozen at bridge release N).
cdn_latest_json=$(jq -n \
    --arg latestVersion "$VERSION" \
    --arg metadataUrl "${CDN_BASE_URL}/${VERSION}/version.json" \
    '{latestVersion: $latestVersion, metadataUrl: $metadataUrl, forceUpdate: false}')
echo "$cdn_latest_json" > "$CDN_LATEST_VERSION_FILE"

# Canonical artifact receipt.
local_manifest_sha256=$(get_sha256 "$LOCAL_VERSION_FILE")
receipt_json=$(jq -n \
    --arg version "$VERSION" \
    --argjson files "$receipt_files_array" \
    --arg manifestSha256 "$manifest_sha256" \
    --arg localManifestSha256 "$local_manifest_sha256" \
    '{version: $version, files: $files, manifestSha256: $manifestSha256, localManifestSha256: $localManifestSha256}')
echo "$receipt_json" > "$RECEIPT_FILE"

# --- 7. Dual manifest identity check ---
# Normalize only approved differences:
#   - payload URLs (host/path prefix)
#   - GitHub-only top-level fields: releasePageUrl, forceUpdate
# Then prove the remaining semantic fields are structurally identical.
normalized_equal=$(jq -n \
    --slurpfile github "$GITHUB_VERSION_FILE" \
    --slurpfile cdn "$CDN_VERSION_FILE" \
    '($github[0] | del(.releasePageUrl, .forceUpdate) | .files |= map(del(.url)))
     ==
     ($cdn[0] | .files |= map(del(.url)))')

if [[ "$normalized_equal" != "true" ]]; then
    echo "Error: GitHub and CDN manifests differ beyond approved fields." >&2
    exit 1
fi

echo ""
echo "Success: manifests and receipt generated in $OUTPUT_DIR"
echo "  GitHub manifest:  $GITHUB_VERSION_FILE"
echo "  CDN manifest:     $CDN_VERSION_FILE"
echo "  Local manifest:   $LOCAL_VERSION_FILE"
echo "  GitHub pointer:   $LATEST_VERSION_FILE"
echo "  CDN pointer:      $CDN_LATEST_VERSION_FILE"
echo "  Receipt:          $RECEIPT_FILE"
