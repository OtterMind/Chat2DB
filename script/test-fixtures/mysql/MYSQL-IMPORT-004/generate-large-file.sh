#!/usr/bin/env bash
set -euo pipefail

count="${1:-100000}"
printf '%s\n' 'USE chat2db_import004;'
for ((index = 1; index <= count; index++)); do
  printf "INSERT INTO import004_innodb(id, value_text) VALUES (%d, 'row-%d');\n" "$((1000000 + index))" "$index"
done
