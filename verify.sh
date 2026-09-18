#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "==> starting MongoDB"
docker compose up -d

echo
echo "==> running tests"
./gradlew test

echo
echo "==> running ledger verification"
./gradlew selfCheck

echo
echo "==> verification complete"