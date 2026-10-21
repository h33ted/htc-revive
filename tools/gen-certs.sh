#!/usr/bin/env bash
# Generates this build's own local CA and server certificate (never committed):
#   certs/ca.key, certs/ca.crt   name-constrained CA (htcsense.com, accu-weather.com only)
#   certs/leaf.key, leaf.crt     server cert for the Prism hosts, signed by that CA
#   assets/ca.pem, ca.hash       CA the app installs into the user trust store (<hash>.0)
#   assets/server.p12            leaf + key for the local HTTPS server
# Existing files are kept: phones already trust the CA they were set up with.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f assets/server.p12 ] && [ -f assets/ca.pem ] && [ -f assets/ca.hash ] && exit 0

mkdir -p certs assets
openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days 7300 \
    -config certs/ca.cnf -keyout certs/ca.key -out certs/ca.crt
# MSYS_NO_PATHCONV: stop Git Bash on Windows rewriting "/CN=..." into a file path.
MSYS_NO_PATHCONV=1 openssl req -new -newkey rsa:2048 -nodes -subj "/CN=geo-prism.htcsense.com" \
    -keyout certs/leaf.key -out certs/leaf.csr
openssl x509 -req -sha256 -days 3650 -in certs/leaf.csr -CA certs/ca.crt -CAkey certs/ca.key \
    -CAcreateserial -extfile certs/leaf.cnf -extensions ext -out certs/leaf.crt

cp certs/ca.crt assets/ca.pem
# Android 4.x names trusted certs by the OpenSSL 0.9 (MD5) subject hash.
openssl x509 -in certs/ca.crt -noout -subject_hash_old > assets/ca.hash
# Android 4.2 only reads PKCS#12 with the legacy SHA-1/3DES algorithms.
openssl pkcs12 -export -in certs/leaf.crt -inkey certs/leaf.key -certfile certs/ca.crt \
    -name htcrevive -passout pass:htcrevive \
    -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1 -out assets/server.p12
echo "generated a new local CA ($(cat assets/ca.hash))"
