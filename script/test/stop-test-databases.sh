#!/usr/bin/env bash
# Removes every Docker database created by start-test-databases.sh.
set -euo pipefail

want() {
  local name="$1"
  shift
  # No filter arguments (or a single blank) means "stop everything".
  [ "$#" -eq 0 ] && return 0
  [ "$#" -eq 1 ] && [ -z "$1" ] && return 0
  printf '%s\n' "$@" | grep -qx "${name}"
}

stop() {
  local name="$1"
  if [ "$(docker ps --all --quiet --filter "name=^${name}$")" ]; then
    docker rm --force "${name}" > /dev/null
    echo "${name}: removed"
  fi
}

targets=("${@:-}")

want "firebird" "${targets[@]}" && stop c2d-test-firebird
want "questdb" "${targets[@]}" && stop c2d-test-questdb
want "cratedb" "${targets[@]}" && stop c2d-test-cratedb
want "timescaledb" "${targets[@]}" && stop c2d-test-timescaledb
want "iotdb" "${targets[@]}" && stop c2d-test-iotdb
want "yugabytedb" "${targets[@]}" && stop c2d-test-yugabytedb
want "greenplum" "${targets[@]}" && stop c2d-test-greenplum
want "trino" "${targets[@]}" && stop c2d-test-trino
want "mysql57" "${targets[@]}" && stop c2d-test-mysql57
want "mysql80" "${targets[@]}" && stop c2d-test-mysql80

exit 0
