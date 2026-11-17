#!/usr/bin/env bash
# Generates this build's own local CA and server certificate (never committed):
#   certs/ca.key, certs/ca.crt   name-constrained CA (htcsense.com, accu-weather.com only)
#   certs/leaf.key, leaf.crt     server cert for the hosts in certs/leaf.cnf, signed by that CA
#   assets/ca.pem, ca.hash       CA the app installs into the user trust store (<hash>.0)
#   assets/server.p12            leaf + key for the local HTTPS server
# The CA is created once and kept: phones already trust it. The server cert is re-issued from
# it whenever the host list in certs/leaf.cnf changes (e.g. Sense 5 on 4.3 moved weather to HTTPS).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p certs assets

if [ ! -f certs/ca.key ] || [ ! -f certs/ca.crt ]; then
    if [ -f assets/ca.pem ]; then
        echo "assets/ca.pem exists but certs/ca.key is missing; restore it rather than replacing the CA" >&2
        exit 1
    fi
    openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days 7300 \
        -config certs/ca.cnf -keyout certs/ca.key -out certs/ca.crt
    echo "generated a new local CA"
fi
cp certs/ca.crt assets/ca.pem
# Android 4.x names trusted certs by the OpenSSL 0.9 (MD5) subject hash.
openssl x509 -in certs/ca.crt -noout -subject_hash_old > assets/ca.hash

wanted=$(sed -n 's/^subjectAltName=//p' certs/leaf.cnf)
have=""
if [ -f certs/leaf.crt ]; then
    have=$(openssl x509 -in certs/leaf.crt -noout -ext subjectAltName 2>/dev/null | tail -n +2 \
        | tr -d ' ' | sed 's/DNS:/DNS:/g')
fi
if [ -f assets/server.p12 ] && [ "$have" = "$wanted" ]; then
    exit 0
fi

# MSYS_NO_PATHCONV: stop Git Bash on Windows rewriting "/CN=..." into a file path.
MSYS_NO_PATHCONV=1 openssl req -new -newkey rsa:2048 -nodes -subj "/CN=geo-prism.htcsense.com" \
    -keyout certs/leaf.key -out certs/leaf.csr
openssl x509 -req -sha256 -days 3650 -in certs/leaf.csr -CA certs/ca.crt -CAkey certs/ca.key \
    -CAcreateserial -extfile certs/leaf.cnf -extensions ext -out certs/leaf.crt
# Android 4.2 only reads PKCS#12 with the legacy SHA-1/3DES algorithms.
openssl pkcs12 -export -in certs/leaf.crt -inkey certs/leaf.key -certfile certs/ca.crt \
    -name htcrevive -passout pass:htcrevive \
    -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1 -out assets/server.p12
echo "issued server certificate for: $wanted"
