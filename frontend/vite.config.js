import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.PRICE_ANALYZER_BACKEND_URL || 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{js,jsx}'],
    exclude: ['**/*.offline.test.js'],
    reporters: ['default', 'junit'],
    outputFile: { junit: 'test-results/vitest-junit.xml' },
    coverage: {
      provider: 'v8',
      include: ['src/lib/**/*.js'],
      exclude: ['src/lib/**/*.test.js'],
      reporter: ['text', 'html', 'lcov', 'json-summary'],
    },
  },
});
