#!/usr/bin/env bash
# Removes every Docker database created by start-test-databases.sh.
set -euo pipefail

for name in c2d-test-firebird c2d-test-questdb c2d-test-cratedb \
            c2d-test-timescaledb c2d-test-iotdb; do
  if [ "$(docker ps --all --quiet --filter "name=^${name}$")" ]; then
    docker rm --force "${name}" > /dev/null
    echo "${name}: removed"
  fi
done
