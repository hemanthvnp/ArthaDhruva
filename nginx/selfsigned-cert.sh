#!/bin/sh
# Local/dev TLS: generate a throwaway self-signed cert on first start rather than committing a
# private key to the repo. A real deployment mounts a real cert at /etc/nginx/certs instead.
set -e
mkdir -p /etc/nginx/certs
if [ ! -f /etc/nginx/certs/tls.crt ]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -keyout /etc/nginx/certs/tls.key -out /etc/nginx/certs/tls.crt \
    -subj "/CN=localhost" >/dev/null 2>&1
fi
