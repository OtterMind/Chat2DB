#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <staged-macos-input-directory>" >&2
  exit 1
fi

INPUT_DIR="$1"
SIGNING_IDENTITY="${MAC_SIGNING_IDENTITY:-}"
SIGNED_COUNT=0
MAX_SIGN_ATTEMPTS=3
SIGN_RETRY_DELAY_SECONDS="${MAC_SIGN_RETRY_DELAY_SECONDS:-5}"
WORK_COUNTER=0
WORK_ROOT=""
NEW_WORK_DIR=""
PENDING_ARCHIVES=()

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

for command_name in awk codesign file find grep mktemp security unzip zip; do
  require_command "${command_name}"
done

if [ ! -d "${INPUT_DIR}" ]; then
  echo "[error] staged macOS input directory not found: ${INPUT_DIR}" >&2
  exit 1
fi

resolve_signing_identity() {
  if [ -z "${SIGNING_IDENTITY}" ]; then
    SIGNING_IDENTITY=$(security find-identity -v -p codesigning \
      | awk -F '"' '/Developer ID Application/ { print $2; exit }')
  fi
  if [ -z "${SIGNING_IDENTITY}" ]; then
    echo "[error] no Developer ID Application signing identity found" >&2
    security find-identity -v -p codesigning || true
    exit 1
  fi
  if ! security find-identity -v -p codesigning \
    | grep -F "${SIGNING_IDENTITY}" >/dev/null; then
    echo "[error] macOS signing identity not found: ${SIGNING_IDENTITY}" >&2
    exit 1
  fi
}

cleanup() {
  local pending_archive

  if [ "${#PENDING_ARCHIVES[@]}" -gt 0 ]; then
    for pending_archive in "${PENDING_ARCHIVES[@]}"; do
      rm -f "${pending_archive}"
    done
  fi
  if [ -n "${WORK_ROOT}" ]; then
    rm -rf "${WORK_ROOT}"
  fi
}
trap cleanup EXIT

new_work_dir() {
  WORK_COUNTER=$((WORK_COUNTER + 1))
  NEW_WORK_DIR="${WORK_ROOT}/${WORK_COUNTER}"
  mkdir -p "${NEW_WORK_DIR}"
}

is_macho_file() {
  local file_description
  file_description=$(file -b "$1")
  [[ "${file_description}" == *Mach-O* ]]
}

verify_macho_signature() {
  local native_file="$1"
  local signature_details

  if ! codesign --verify --strict --verbose=2 "${native_file}"; then
    return 1
  fi
  if ! signature_details=$(codesign --display --verbose=4 "${native_file}" 2>&1); then
    return 1
  fi
  if ! grep -q 'flags=.*runtime' <<<"${signature_details}"; then
    echo "[error] hardened runtime is missing from signed Mach-O: ${native_file}" >&2
    echo "${signature_details}" >&2
    return 1
  fi
}

sign_and_verify_macho() {
  local native_file="$1"
  local relative_path="$2"
  local attempt

  echo "[sign] ${relative_path}"
  for ((attempt = 1; attempt <= MAX_SIGN_ATTEMPTS; attempt++)); do
    if codesign \
      --force \
      --sign "${SIGNING_IDENTITY}" \
      --options runtime \
      --timestamp \
      "${native_file}" \
      && verify_macho_signature "${native_file}"; then
      return 0
    fi

    if [ "${attempt}" -lt "${MAX_SIGN_ATTEMPTS}" ]; then
      echo "[warn] signing verification failed for ${relative_path}; retrying ($((attempt + 1))/${MAX_SIGN_ATTEMPTS})" >&2
      sleep "${SIGN_RETRY_DELAY_SECONDS}"
    fi
  done

  echo "[error] failed to sign and verify Mach-O after ${MAX_SIGN_ATTEMPTS} attempts: ${relative_path}" >&2
  return 1
}

sign_macho_tree() {
  local root_dir="$1"
  local native_file

  while IFS= read -r -d '' native_file; do
    if ! is_macho_file "${native_file}"; then
      continue
    fi

    sign_and_verify_macho \
      "${native_file}" \
      "${native_file#"${root_dir}/"}"
    SIGNED_COUNT=$((SIGNED_COUNT + 1))
  done < <(find "${root_dir}" -type f -print0)
}

sign_jar_payloads() {
  local jar_file="$1"
  local jar_abs
  local work_dir
  local nested_jar
  local count_before
  local modified=false
  local rebuilt_jar

  jar_abs="$(cd "$(dirname "${jar_file}")" && pwd)/$(basename "${jar_file}")"
  new_work_dir
  work_dir="${NEW_WORK_DIR}"

  if ! unzip -q -o "${jar_abs}" -d "${work_dir}" 2>/dev/null; then
    rm -rf "${work_dir}"
    return
  fi

  while IFS= read -r -d '' nested_jar; do
    count_before="${SIGNED_COUNT}"
    sign_jar_payloads "${nested_jar}"
    if [ "${SIGNED_COUNT}" -gt "${count_before}" ]; then
      modified=true
    fi
  done < <(find "${work_dir}" -type f -name '*.jar' -print0)

  count_before="${SIGNED_COUNT}"
  sign_macho_tree "${work_dir}"
  if [ "${SIGNED_COUNT}" -gt "${count_before}" ]; then
    modified=true
  fi

  if [ "${modified}" = true ]; then
    rebuilt_jar=$(mktemp "${jar_abs}.tmp.XXXXXX")
    rm -f "${rebuilt_jar}"
    PENDING_ARCHIVES+=("${rebuilt_jar}")
    (cd "${work_dir}" && zip -q -r -0 "${rebuilt_jar}" .)
    unzip -tq "${rebuilt_jar}" >/dev/null
    mv -f "${rebuilt_jar}" "${jar_abs}"
  fi
  rm -rf "${work_dir}"
}

resolve_signing_identity
WORK_ROOT=$(mktemp -d)

echo "[run] sign every macOS Mach-O payload inside staged JARs"
while IFS= read -r -d '' jar_file; do
  sign_jar_payloads "${jar_file}"
done < <(find "${INPUT_DIR}" -type f -name '*.jar' -print0)

echo "[check] signed and verified ${SIGNED_COUNT} Mach-O payload(s) with hardened runtime"
