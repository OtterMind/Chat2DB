#!/usr/bin/env bash

# Stable manifest IDs are intentionally distinct from GitHub Release asset names.
payload_metadata_id() {
  case "$1" in
    chat2db-community.jar) echo "chat2db-community-server" ;;
    lib.zip) echo "chat2db-community-lib" ;;
    dist.zip) echo "chat2db-web" ;;
    *)
      echo "[error] unknown managed payload: $1" >&2
      return 1
      ;;
  esac
}
