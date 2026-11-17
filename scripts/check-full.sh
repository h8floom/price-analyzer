#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm install --no-audit --no-fund && npm run lint && npm run test:coverage && npm run build)
(cd backend && mvn -B clean verify)
echo "Full checks completed."
