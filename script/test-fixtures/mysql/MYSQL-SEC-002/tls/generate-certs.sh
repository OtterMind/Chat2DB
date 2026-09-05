#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:-"$script_dir/generated"}
mkdir -p "$output_dir"

openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
  -subj '/CN=Chat2DB Test CA' \
  -keyout "$output_dir/ca-key.pem" \
  -out "$output_dir/ca.pem"

openssl req -newkey rsa:2048 -nodes \
  -subj '/CN=localhost' \
  -keyout "$output_dir/server-key.pem" \
  -out "$output_dir/server.csr"
openssl x509 -req -days 7 \
  -in "$output_dir/server.csr" \
  -CA "$output_dir/ca.pem" \
  -CAkey "$output_dir/ca-key.pem" \
  -CAcreateserial \
  -out "$output_dir/server-cert.pem"

openssl req -newkey rsa:2048 -nodes \
  -subj '/CN=Chat2DB Test Client' \
  -keyout "$output_dir/client-key.pem" \
  -out "$output_dir/client.csr"
openssl x509 -req -days 7 \
  -in "$output_dir/client.csr" \
  -CA "$output_dir/ca.pem" \
  -CAkey "$output_dir/ca-key.pem" \
  -CAcreateserial \
  -out "$output_dir/client-cert.pem"

chmod 600 "$output_dir"/*-key.pem
printf 'Generated disposable TLS fixtures in %s\n' "$output_dir"
