#!/bin/sh
# Generates random secrets into ./secrets (gitignored), one file per secret. File names are the
# Spring property names, which is what lets `spring.config.import=configtree:/run/secrets/` in the
# backend expose each mounted Docker secret as a property with no code changes.
# `tr -d '\r\n'`: openssl on Windows emits CRLF, and a stray \r inside a password or a base64 key
# is a silent, miserable bug to chase.
set -e
mkdir -p secrets
clean() { tr -d '\r\n'; }
# If a secret was ever missing when the stack started, Docker creates an empty *directory* at its path
# (a bind mount of a nonexistent file). Remove such empty directories so the file can be generated.
for f in DB_PASSWORD DB_APP_PASSWORD DB_WORKER_PASSWORD NEO4J_PASSWORD JWT_SECRET TOTP_ENCRYPTION_KEY; do
  if [ -d "secrets/$f" ]; then
    rmdir "secrets/$f" 2>/dev/null || { echo "secrets/$f is a non-empty directory; remove it and re-run" >&2; exit 1; }
  fi
done
[ -f secrets/DB_PASSWORD ]      || { openssl rand -base64 24 | tr -d '\r\n=/+' > secrets/DB_PASSWORD;      echo "created secrets/DB_PASSWORD"; }
[ -f secrets/DB_APP_PASSWORD ]  || { openssl rand -base64 24 | tr -d '\r\n=/+' > secrets/DB_APP_PASSWORD;  echo "created secrets/DB_APP_PASSWORD"; }
[ -f secrets/DB_WORKER_PASSWORD ] || { openssl rand -base64 24 | tr -d '\r\n=/+' > secrets/DB_WORKER_PASSWORD; echo "created secrets/DB_WORKER_PASSWORD"; }
[ -f secrets/NEO4J_PASSWORD ]   || { openssl rand -base64 24 | tr -d '\r\n=/+' > secrets/NEO4J_PASSWORD;   echo "created secrets/NEO4J_PASSWORD"; }
[ -f secrets/JWT_SECRET ]       || { openssl rand -base64 48 | clean > secrets/JWT_SECRET;       echo "created secrets/JWT_SECRET"; }
[ -f secrets/TOTP_ENCRYPTION_KEY ] || { openssl rand -base64 32 | clean > secrets/TOTP_ENCRYPTION_KEY; echo "created secrets/TOTP_ENCRYPTION_KEY"; }
chmod 600 secrets/*
