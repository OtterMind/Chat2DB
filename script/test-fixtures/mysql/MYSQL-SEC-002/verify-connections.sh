#!/usr/bin/env bash
set -euo pipefail

mysql_bin=${MYSQL_BIN:-mysql}
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
password=${MYSQL_SEC_002_PASSWORD:?Set MYSQL_SEC_002_PASSWORD for the local fixture accounts}
tls_dir=${MYSQL_SEC_002_TLS_DIR:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tls/generated"}

verify() {
  local user=$1
  local ssl_mode=$2
  shift 2
  MYSQL_PWD="$password" "$mysql_bin" \
    --protocol=TCP --host="$host" --port="$port" --user="$user" \
    --ssl-mode="$ssl_mode" "$@" \
    --batch --skip-column-names \
    --execute="SELECT CURRENT_USER(); SHOW STATUS LIKE 'Ssl_cipher';"
}

if [[ ${1:-} == "--single" ]]; then
  verify "${MYSQL_SEC_002_USER:?Set MYSQL_SEC_002_USER with --single}" \
    "${MYSQL_SEC_002_SSL_MODE:-PREFERRED}"
  exit 0
fi

verify sec002_none DISABLED
verify sec002_ssl REQUIRED
verify sec002_x509 VERIFY_CA \
  --ssl-ca="$tls_dir/ca.pem" \
  --ssl-cert="$tls_dir/client-cert.pem" \
  --ssl-key="$tls_dir/client-key.pem"
verify sec002_specified VERIFY_CA \
  --ssl-ca="$tls_dir/ca.pem" \
  --ssl-cert="$tls_dir/client-cert.pem" \
  --ssl-key="$tls_dir/client-key.pem"

if [[ ${MYSQL_SEC_002_VERIFY_NATIVE:-0} == "1" ]]; then
  verify sec002_native PREFERRED
fi
