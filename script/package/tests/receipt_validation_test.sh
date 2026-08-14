#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)
PACKAGE_SCRIPT="${SCRIPT_DIR}/../package-community-jcef.sh"
METADATA_SCRIPT="${SCRIPT_DIR}/../generate_metadata.sh"
JPACKAGE_INPUT_DIR="${PROJECT_ROOT}/jpackage/input"
TEST_ROOT=$(mktemp -d)
VERSION="5.4.0"

cleanup() {
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  echo "[error] $1" >&2
  exit 1
}

build_fixture_artifact() {
  local artifact_dir="$1"
  mkdir -p "${artifact_dir}"

  echo "canonical-jar" > "${artifact_dir}/chat2db-community.jar"
  mkdir -p "${artifact_dir}/lib-staging/lib"
  echo "canonical-lib" > "${artifact_dir}/lib-staging/lib/a.jar"
  (cd "${artifact_dir}/lib-staging" && zip -qr "${artifact_dir}/lib.zip" lib)
  mkdir -p "${artifact_dir}/dist"
  echo "canonical-dist" > "${artifact_dir}/dist/index.html"
  (cd "${artifact_dir}" && zip -qr dist.zip dist)
  rm -rf "${artifact_dir}/lib-staging" "${artifact_dir}/dist"

  bash "${METADATA_SCRIPT}" "${VERSION}" "${artifact_dir}" "${artifact_dir}"
}

# --- Test 1: Successful consume and staged re-hash ---
echo "[test] consume canonical artifact and verify staged re-hash"
ARTIFACT_DIR="${TEST_ROOT}/artifact1"
build_fixture_artifact "${ARTIFACT_DIR}"

if ! CANONICAL_ARTIFACT_DIR="${ARTIFACT_DIR}" bash "${PACKAGE_SCRIPT}" "${VERSION}" prepare-native; then
  fail "prepare-native failed on valid canonical artifact"
fi

for platform in mac win linux; do
  if [ ! -f "${JPACKAGE_INPUT_DIR}/${platform}/chat2db-community.jar" ]; then
    fail "platform input not staged: ${platform}/chat2db-community.jar"
  fi
  if [ ! -f "${JPACKAGE_INPUT_DIR}/${platform}/local_version.json" ]; then
    fail "platform input not staged: ${platform}/local_version.json"
  fi
  if [ ! -d "${JPACKAGE_INPUT_DIR}/${platform}/lib" ]; then
    fail "platform input not staged: ${platform}/lib"
  fi
  if [ ! -d "${JPACKAGE_INPUT_DIR}/${platform}/dist" ]; then
    fail "platform input not staged: ${platform}/dist"
  fi
done

# --- Test 1b: Staged archive trees are verified after extraction ---
echo "[test] staged archive tree tampering fails verification"
echo "tampered" > "${JPACKAGE_INPUT_DIR}/mac/lib/lib/a.jar"
if python3 "${SCRIPT_DIR}/../verify_staged_payload.py" \
  "${ARTIFACT_DIR}/chat2db-community.jar" "${JPACKAGE_INPUT_DIR}/mac/chat2db-community.jar" \
  "${ARTIFACT_DIR}/local_version.json" "${JPACKAGE_INPUT_DIR}/mac/local_version.json" \
  "${ARTIFACT_DIR}/lib.zip" "${JPACKAGE_INPUT_DIR}/mac/lib" \
  "${ARTIFACT_DIR}/dist.zip" "${JPACKAGE_INPUT_DIR}/mac/dist" 2>"${TEST_ROOT}/staged-tamper.err"; then
  fail "staged payload verification should fail after archive tree tampering"
fi
if ! grep -q "staged lib inventory mismatch" "${TEST_ROOT}/staged-tamper.err"; then
  fail "staged payload tampering error message unexpected"
fi

# --- Test 2: Missing canonical artifact fails closed ---
echo "[test] missing canonical artifact fails closed"
if CANONICAL_ARTIFACT_DIR="${TEST_ROOT}/missing" bash "${PACKAGE_SCRIPT}" "${VERSION}" prepare-native 2>"${TEST_ROOT}/missing.err"; then
  fail "prepare-native should fail when canonical artifact is missing"
fi
if ! grep -q "required directory not found" "${TEST_ROOT}/missing.err"; then
  fail "missing artifact error message unexpected"
fi

# --- Test 3: Stale payload (digest mismatch) fails closed ---
echo "[test] stale payload digest fails closed"
ARTIFACT_DIR="${TEST_ROOT}/artifact3"
build_fixture_artifact "${ARTIFACT_DIR}"
echo "tampered" > "${ARTIFACT_DIR}/chat2db-community.jar"
if CANONICAL_ARTIFACT_DIR="${ARTIFACT_DIR}" bash "${PACKAGE_SCRIPT}" "${VERSION}" prepare-native 2>"${TEST_ROOT}/stale.err"; then
  fail "prepare-native should fail on digest mismatch"
fi
if ! grep -q "SHA-256 mismatch" "${TEST_ROOT}/stale.err"; then
  fail "stale payload error message unexpected"
fi

# --- Test 4: Missing receipt fails closed ---
echo "[test] missing receipt fails closed"
ARTIFACT_DIR="${TEST_ROOT}/artifact4"
build_fixture_artifact "${ARTIFACT_DIR}"
rm "${ARTIFACT_DIR}/receipt.json"
if CANONICAL_ARTIFACT_DIR="${ARTIFACT_DIR}" bash "${PACKAGE_SCRIPT}" "${VERSION}" prepare-native 2>"${TEST_ROOT}/receipt.err"; then
  fail "prepare-native should fail when receipt is missing"
fi
if ! grep -q "required file not found" "${TEST_ROOT}/receipt.err"; then
  fail "missing receipt error message unexpected"
fi

# --- Test 5: local_version.json mismatch fails closed ---
echo "[test] local_version.json mismatch fails closed"
ARTIFACT_DIR="${TEST_ROOT}/artifact5"
build_fixture_artifact "${ARTIFACT_DIR}"
echo '{"tampered": true}' > "${ARTIFACT_DIR}/local_version.json"
if CANONICAL_ARTIFACT_DIR="${ARTIFACT_DIR}" bash "${PACKAGE_SCRIPT}" "${VERSION}" prepare-native 2>"${TEST_ROOT}/local.err"; then
  fail "prepare-native should fail on local_version.json mismatch"
fi
if ! grep -q "local_version.json SHA-256 mismatch" "${TEST_ROOT}/local.err"; then
  fail "local_version.json mismatch error message unexpected"
fi

# --- Test 6: Version mismatch between receipt and CLI fails closed ---
echo "[test] version mismatch fails closed"
ARTIFACT_DIR="${TEST_ROOT}/artifact6"
build_fixture_artifact "${ARTIFACT_DIR}"
if CANONICAL_ARTIFACT_DIR="${ARTIFACT_DIR}" bash "${PACKAGE_SCRIPT}" "5.5.0" prepare-native 2>"${TEST_ROOT}/version.err"; then
  fail "prepare-native should fail on version mismatch"
fi
if ! grep -q "canonical artifact version mismatch" "${TEST_ROOT}/version.err"; then
  fail "version mismatch error message unexpected"
fi

echo "[check] receipt validation and consume-mode fail-closed behavior passed"
