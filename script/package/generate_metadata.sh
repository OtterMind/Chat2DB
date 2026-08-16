#!/bin/bash
set -euo pipefail

# --- Usage ---
# ./generate_metadata.sh <version> <source_directory> [base_url]
# Example:
# ./generate_metadata.sh 5.3.0 /path/to/your/files https://cdn.chat2db-ai.com/community/updates

# --- 1. Argument validation ---
if [[ "$#" -lt 2 ]]; then
    echo "Error: missing arguments."
    echo "Usage: $0 <version> <source_directory> [base_url]"
    exit 1
fi

VERSION="$1"
SOURCE_DIR="$2"
BASE_URL=${3:-"https://cdn.chat2db-ai.com/community/updates"}

# Check the source directory.
if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "Error: directory does not exist: $SOURCE_DIR"
    exit 1
fi

# --- Cross-platform SHA256 helper ---
get_sha256() {
  local FILE_TO_HASH=$1
  if command -v shasum &> /dev/null; then
    shasum -a 256 "$FILE_TO_HASH" | awk '{print $1}'
  elif command -v certutil &> /dev/null; then
    certutil -hashfile "$FILE_TO_HASH" SHA256 | sed -n '2p' | tr -d ' \r\n'
  else
    echo "Error: no supported hash tool found (shasum or certutil)." >&2
    exit 1
  fi
}


# --- 2. Initialize the JSON array ---
json_files_array="[]"

# --- 3. Process source files ---
echo "Processing directory: $SOURCE_DIR ..."
for file_path in "$SOURCE_DIR"/*; do
    if [[ -f "$file_path" ]]; then
        server_file_name=$(basename "$file_path")
        if [[ "$server_file_name" == "generate_metadata.sh" || "$server_file_name" == "version.json" || "$server_file_name" == "local_version.json" ]]; then
            continue
        fi
        echo " - Processing file: $server_file_name"

        if [[ "$(uname)" == "Darwin" ]]; then # macOS
            file_size_byte=$(stat -f %z "$file_path")
        else # Linux & Git Bash on Windows
            file_size_byte=$(stat -c %s "$file_path")
        fi

        sha256=$(get_sha256 "$file_path")

        id=""
        local_target_name=""
        type=""
        if [[ "$server_file_name" == *".jar" ]]; then
            id="chat2db-community-server"
            local_target_name="$server_file_name"
            type="jar"
        elif [[ "$server_file_name" == "lib.zip" ]]; then
            id="chat2db-community-lib"
            local_target_name="lib"
            type="zip"
        elif [[ "$server_file_name" == *".zip" ]]; then
            id="chat2db-web"
            local_target_name="${server_file_name%.zip}"
            type="zip"
        else
            echo "   -> Skipping unknown file type: $server_file_name"
            continue
        fi

        file_url="${BASE_URL}/${VERSION}/${server_file_name}"

        # --- 4. Create and append the file object with jq ---
        json_file_object=$(jq -n \
            --arg id "$id" \
            --arg serverFileName "$server_file_name" \
            --arg localTargetName "$local_target_name" \
            --arg url "$file_url" \
            --arg sha256 "$sha256" \
            --arg type "$type" \
            --argjson fileSizeByte "$file_size_byte" \
            '{id: $id, serverFileName: $serverFileName, localTargetName: $localTargetName, url: $url, sha256: $sha256, type: $type, extractTo: null, updateStrategy: null, fileSizeByte: $fileSizeByte}')

        json_files_array=$(echo "$json_files_array" | jq --argjson obj "$json_file_object" '. + [$obj]')
    fi
done

# --- 5. Build the final JSON object ---
final_json=$(jq -n \
    --arg version "$VERSION" \
    --arg releaseNotes "Known issue fixes" \
    --argjson files "$json_files_array" \
    '{version: $version, releaseNotes: $releaseNotes, files: $files, launchCommand: null}')

# --- 6. Write the JSON file ---
OUTPUT_FILE="$SOURCE_DIR/version.json"
echo "$final_json" > "$OUTPUT_FILE"

echo ""
echo "Success: metadata generated at $OUTPUT_FILE"
