#!/usr/bin/env bash
set -euo pipefail

fixture_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cert_dir="${fixture_dir}/certs"
mkdir -p "${cert_dir}"

openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -subj '/CN=Chat2DB TLS Fixture CA' \
  -keyout "${cert_dir}/ca-key.pem" -out "${cert_dir}/ca.pem"
openssl req -newkey rsa:2048 -nodes \
  -subj '/CN=localhost' \
  -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "${cert_dir}/server-key.pem" -out "${cert_dir}/server.csr"
printf '%s\n' 'subjectAltName=DNS:localhost,IP:127.0.0.1' > "${cert_dir}/server.ext"
openssl x509 -req -days 2 -sha256 \
  -in "${cert_dir}/server.csr" \
  -CA "${cert_dir}/ca.pem" -CAkey "${cert_dir}/ca-key.pem" -CAcreateserial \
  -extfile "${cert_dir}/server.ext" \
  -out "${cert_dir}/server-cert.pem"
chmod 600 "${cert_dir}"/*-key.pem
