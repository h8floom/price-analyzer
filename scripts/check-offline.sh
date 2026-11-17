#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "FRONTEND: node:test, no npm downloads"
(cd frontend && npm run test:offline)
echo "BACKEND: javac + Java 21, no Maven downloads"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
javac -d "$DIR"   backend/src/main/java/ru/priceanalyzer/domain/Product.java   backend/src/main/java/ru/priceanalyzer/domain/PriceEntry.java   backend/src/main/java/ru/priceanalyzer/domain/PriceMath.java   backend/offline-tests/PriceMathSmokeTest.java
java -cp "$DIR" PriceMathSmokeTest
echo "Offline checks completed successfully."
