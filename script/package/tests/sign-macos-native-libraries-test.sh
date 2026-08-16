#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SIGNER="${SCRIPT_DIR}/../sign-macos-native-libraries.sh"
EXTRA_JAR="${1:-}"
TEST_ROOT=$(mktemp -d)
MOCK_BIN="${TEST_ROOT}/bin"
MOCK_CODESIGN_LOG="${TEST_ROOT}/codesign.log"
MOCK_CODESIGN_FAIL_STATE="${TEST_ROOT}/codesign-failed-once"
IDENTITY="Developer ID Application: Test Signing (TESTTEAM)"
EXPECTED_SIGNING_CALLS=4

cleanup() {
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  echo "[error] $1" >&2
  exit 1
}

make_macho() {
  local output_file="$1"
  printf '\xcf\xfa\xed\xfe\x0c\x00\x00\x01\x00\x00\x00\x00\x02\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' > "${output_file}"
}

mkdir -p \
  "${MOCK_BIN}" \
  "${TEST_ROOT}/input/lib" \
  "${TEST_ROOT}/outer/native" \
  "${TEST_ROOT}/outer/dependencies" \
  "${TEST_ROOT}/nested/bin"

cat > "${MOCK_BIN}/security" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ "$*" = "find-identity -v -p codesigning" ]; then
  echo '  1) 0123456789ABCDEF "Developer ID Application: Test Signing (TESTTEAM)"'
  echo '     1 valid identities found'
  exit 0
fi
echo "unexpected security arguments: $*" >&2
exit 1
EOF

cat > "${MOCK_BIN}/codesign" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  --force)
    printf '%s\n' "$*" >> "${MOCK_CODESIGN_LOG}"
    target_file="${!#}"
    printf '\nMOCK-CODESIGNED-RUNTIME\n' >> "${target_file}"
    ;;
  --verify)
    target_file="${!#}"
    if [ -n "${MOCK_CODESIGN_ALWAYS_FAIL_TARGET:-}" ] \
      && [[ "${target_file}" == *"${MOCK_CODESIGN_ALWAYS_FAIL_TARGET}" ]]; then
      echo "A timestamp was expected but was not found." >&2
      exit 1
    fi
    if [ -n "${MOCK_CODESIGN_FAIL_ONCE_TARGET:-}" ] \
      && [[ "${target_file}" == *"${MOCK_CODESIGN_FAIL_ONCE_TARGET}" ]] \
      && [ ! -f "${MOCK_CODESIGN_FAIL_STATE}" ]; then
      touch "${MOCK_CODESIGN_FAIL_STATE}"
      echo "A timestamp was expected but was not found." >&2
      exit 1
    fi
    ;;
  --display)
    target_file="${!#}"
    echo "Executable=${target_file}" >&2
    echo 'CodeDirectory v=20500 size=256 flags=0x10000(runtime)' >&2
    ;;
  *)
    echo "unexpected codesign arguments: $*" >&2
    exit 1
    ;;
esac
EOF
chmod +x "${MOCK_BIN}/security" "${MOCK_BIN}/codesign"

make_macho "${TEST_ROOT}/outer/native/libtest.dylib"
make_macho "${TEST_ROOT}/outer/native/pty4j-unix-spawn-helper"
make_macho "${TEST_ROOT}/nested/bin/nested-helper"
chmod +x \
  "${TEST_ROOT}/outer/native/pty4j-unix-spawn-helper" \
  "${TEST_ROOT}/nested/bin/nested-helper"
echo "not native" > "${TEST_ROOT}/outer/native/readme.txt"

if ! file -b "${TEST_ROOT}/outer/native/pty4j-unix-spawn-helper" | grep -q 'Mach-O'; then
  fail "generated fixture is not recognized as Mach-O"
fi

(cd "${TEST_ROOT}/nested" && zip -q -r "${TEST_ROOT}/outer/dependencies/nested.jar" .)
(cd "${TEST_ROOT}/outer" && zip -q -r "${TEST_ROOT}/input/lib/fixture.jar" .)
if [ -n "${EXTRA_JAR}" ]; then
  if [ ! -f "${EXTRA_JAR}" ]; then
    fail "extra JAR does not exist: ${EXTRA_JAR}"
  fi
  cp "${EXTRA_JAR}" "${TEST_ROOT}/input/lib/extra.jar"
  EXPECTED_SIGNING_CALLS=$((EXPECTED_SIGNING_CALLS + 2))
fi

PATH="${MOCK_BIN}:${PATH}" \
MAC_SIGNING_IDENTITY="${IDENTITY}" \
MAC_SIGN_RETRY_DELAY_SECONDS=0 \
MOCK_CODESIGN_LOG="${MOCK_CODESIGN_LOG}" \
MOCK_CODESIGN_FAIL_ONCE_TARGET="libtest.dylib" \
MOCK_CODESIGN_FAIL_STATE="${MOCK_CODESIGN_FAIL_STATE}" \
  bash "${SIGNER}" "${TEST_ROOT}/input"

signed_count=$(wc -l < "${MOCK_CODESIGN_LOG}" | tr -d '[:space:]')
if [ "${signed_count}" -ne "${EXPECTED_SIGNING_CALLS}" ]; then
  fail "expected ${EXPECTED_SIGNING_CALLS} Mach-O signing calls, got ${signed_count}"
fi
for expected in libtest.dylib pty4j-unix-spawn-helper nested-helper; do
  if ! grep -F -- "${expected}" "${MOCK_CODESIGN_LOG}" >/dev/null; then
    fail "missing signing call for ${expected}"
  fi
done
if grep -F -- "readme.txt" "${MOCK_CODESIGN_LOG}" >/dev/null; then
  fail "non-Mach-O file was selected for signing"
fi
if [ "$(grep -F -c -- '--options runtime --timestamp' "${MOCK_CODESIGN_LOG}")" -ne "${EXPECTED_SIGNING_CALLS}" ]; then
  fail "every signing call must request hardened runtime and a timestamp"
fi
if [ "$(grep -F -c -- 'libtest.dylib' "${MOCK_CODESIGN_LOG}")" -ne 2 ]; then
  fail "timestamp verification failure did not trigger exactly one retry"
fi

mkdir -p "${TEST_ROOT}/repacked" "${TEST_ROOT}/nested-repacked"
unzip -q "${TEST_ROOT}/input/lib/fixture.jar" -d "${TEST_ROOT}/repacked"
unzip -q \
  "${TEST_ROOT}/repacked/dependencies/nested.jar" \
  -d "${TEST_ROOT}/nested-repacked"
if [ ! -x "${TEST_ROOT}/repacked/native/pty4j-unix-spawn-helper" ]; then
  fail "extensionless helper lost its executable mode during JAR repack"
fi
if [ ! -x "${TEST_ROOT}/nested-repacked/bin/nested-helper" ]; then
  fail "nested helper lost its executable mode during JAR repack"
fi
if [ -x "${TEST_ROOT}/repacked/native/libtest.dylib" ]; then
  fail "non-executable dylib gained an executable mode during JAR repack"
fi
for signed_file in \
  "${TEST_ROOT}/repacked/native/libtest.dylib" \
  "${TEST_ROOT}/repacked/native/pty4j-unix-spawn-helper" \
  "${TEST_ROOT}/nested-repacked/bin/nested-helper"; do
  if ! grep -a -F -- 'MOCK-CODESIGNED-RUNTIME' "${signed_file}" >/dev/null; then
    fail "mock signature did not survive JAR repack: ${signed_file}"
  fi
done

if [ -n "${EXTRA_JAR}" ]; then
  extra_dir="${TEST_ROOT}/extra-repacked"
  mkdir -p "${extra_dir}"
  unzip -q "${TEST_ROOT}/input/lib/extra.jar" -d "${extra_dir}"
  extra_helper="${extra_dir}/resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper"
  extra_dylib="${extra_dir}/resources/com/pty4j/native/darwin/libpty.dylib"
  if [ ! -x "${extra_helper}" ]; then
    fail "real extensionless helper lost its executable mode"
  fi
  if [ -x "${extra_dylib}" ]; then
    fail "real libpty.dylib gained an executable mode"
  fi
  for signed_file in "${extra_helper}" "${extra_dylib}"; do
    if ! grep -a -F -- 'MOCK-CODESIGNED-RUNTIME' "${signed_file}" >/dev/null; then
      fail "real pty4j mock signature did not survive JAR repack: ${signed_file}"
    fi
  done
fi

mkdir -p "${TEST_ROOT}/exhausted/source" "${TEST_ROOT}/exhausted/input"
make_macho "${TEST_ROOT}/exhausted/source/libalways-fail.dylib"
(cd "${TEST_ROOT}/exhausted/source" && zip -q -r "${TEST_ROOT}/exhausted/input/fixture.jar" .)
cp "${TEST_ROOT}/exhausted/input/fixture.jar" "${TEST_ROOT}/exhausted/original.jar"
exhausted_log="${TEST_ROOT}/exhausted-codesign.log"
exhausted_output="${TEST_ROOT}/exhausted-output.log"
: > "${exhausted_log}"
if PATH="${MOCK_BIN}:${PATH}" \
  MAC_SIGNING_IDENTITY="${IDENTITY}" \
  MAC_SIGN_RETRY_DELAY_SECONDS=0 \
  MOCK_CODESIGN_LOG="${exhausted_log}" \
  MOCK_CODESIGN_ALWAYS_FAIL_TARGET="libalways-fail.dylib" \
  /bin/bash "${SIGNER}" "${TEST_ROOT}/exhausted/input" \
  > "${exhausted_output}" 2>&1; then
  fail "signer succeeded after exhausting timestamp retries"
fi
if [ "$(wc -l < "${exhausted_log}" | tr -d '[:space:]')" -ne 3 ]; then
  fail "signer did not stop after exactly three failed signing attempts"
fi
if ! cmp -s \
  "${TEST_ROOT}/exhausted/original.jar" \
  "${TEST_ROOT}/exhausted/input/fixture.jar"; then
  fail "failed signing attempt replaced the original JAR"
fi
if grep -F -- '[check] signed and verified' "${exhausted_output}" >/dev/null; then
  fail "failed signing attempt reported a successful signed count"
fi

mkdir -p "${TEST_ROOT}/no-native/source" "${TEST_ROOT}/no-native/input"
echo "plain text only" > "${TEST_ROOT}/no-native/source/readme.txt"
(cd "${TEST_ROOT}/no-native/source" && zip -q -r "${TEST_ROOT}/no-native/input/plain.jar" .)
no_native_log="${TEST_ROOT}/no-native-codesign.log"
: > "${no_native_log}"
PATH="${MOCK_BIN}:${PATH}" \
MAC_SIGNING_IDENTITY="${IDENTITY}" \
MOCK_CODESIGN_LOG="${no_native_log}" \
  /bin/bash "${SIGNER}" "${TEST_ROOT}/no-native/input"
if [ -s "${no_native_log}" ]; then
  fail "JAR without Mach-O payloads triggered a signing call"
fi

echo "[check] recursive Mach-O selection, retry exhaustion, hardened signing, and executable modes passed"
