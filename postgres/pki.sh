#!/bin/sh
# One-shot private CA + Postgres server certificate, generated once into the shared pki volume.
# Real deployments mount certs from a secrets manager instead; this makes verify-full TLS work
# out of the box without committing any key material.
set -e
cd /pki
if [ ! -f server.crt ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout ca.key -out ca.crt -subj "/CN=arthadhruva-local-ca"
  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=postgres"
  printf "subjectAltName=DNS:postgres,DNS:localhost" > san.ext
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -out server.crt -extfile san.ext
  rm -f server.csr san.ext
fi
chown 70:70 server.key && chmod 600 server.key
chmod 644 ca.crt server.crt && chmod 600 ca.key
