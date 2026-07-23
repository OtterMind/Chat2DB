#!/usr/bin/env bash
# Starts the Docker databases used by the configuration-only integration tests
# in chat2db-community-generic (and the HSQLDB/Derby in-memory flows need no
# container at all). Idempotent: running containers are left untouched,
# stopped ones are restarted, missing ones are created.
#
# Usage:
#   ./script/test/start-test-databases.sh          # start everything
#   ./script/test/start-test-databases.sh firebird # start one database
#   ./script/test/stop-test-databases.sh           # tear everything down
#
# The integration tests guard themselves with socket probes and skip cleanly
# for any database that is not running, so starting a subset is fine.
set -euo pipefail

start() {
  local name="$1"; shift
  if [ "$(docker ps --quiet --filter "name=^${name}$")" ]; then
    echo "${name}: already running"
    return
  fi
  if [ "$(docker ps --all --quiet --filter "name=^${name}$")" ]; then
    docker start "${name}" > /dev/null
    echo "${name}: restarted"
    return
  fi
  docker run --detach --name "${name}" "$@" > /dev/null
  echo "${name}: created"
}

want() {
  local name="$1"
  shift
  # No filter arguments (or a single blank) means "start everything".
  [ "$#" -eq 0 ] && return 0
  [ "$#" -eq 1 ] && [ -z "$1" ] && return 0
  printf '%s\n' "$@" | grep -qx "${name}"
}

targets=("${@:-}")

want "firebird" "${targets[@]}" && start c2d-test-firebird \
  --publish 127.0.0.1:3050:3050 \
  --env FIREBIRD_ROOT_PASSWORD=masterkey \
  --env FIREBIRD_DATABASE=demo.fdb \
  firebirdsql/firebird:5

want "questdb" "${targets[@]}" && start c2d-test-questdb \
  --publish 127.0.0.1:8812:8812 \
  questdb/questdb:8.2.1

want "cratedb" "${targets[@]}" && start c2d-test-cratedb \
  --publish 127.0.0.1:5433:5432 \
  crate:5.9 -Cdiscovery.type=single-node

want "timescaledb" "${targets[@]}" && start c2d-test-timescaledb \
  --publish 127.0.0.1:5434:5432 \
  --env POSTGRES_PASSWORD=postgres \
  timescale/timescaledb:2.17.2-pg16

want "iotdb" "${targets[@]}" && start c2d-test-iotdb \
  --publish 127.0.0.1:6667:6667 \
  apache/iotdb:1.3.2-standalone

echo "Run the suite with tests enabled, e.g.:"
echo "  mvn -f chat2db-community-server/pom.xml -pl :chat2db-community-generic -am \\"
echo "    -Dmaven.test.skip=false -DskipTests=false '-Dsurefire.includes=**/*Test.java' \\"
echo "    -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=false test"
