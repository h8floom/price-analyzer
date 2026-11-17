set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/check-offline.sh
echo 'Frontend Vitest, ESLint and Vite'
(cd frontend && npm install --no-audit --no-fund && npm run lint && npm run test:coverage && npm run build)
echo 'Backend Maven + JUnit + MockMvc + JaCoCo'
(cd backend && mvn -B clean verify)
echo 'Playwright Chromium (needs npm/browser/Maven installed)'
(cd frontend && npx playwright install chromium && npm run test:e2e)
echo 'All Lab 7 checks completed successfully.'
