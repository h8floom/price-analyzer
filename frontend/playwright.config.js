import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 40_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'test-results/playwright-html', open: 'never' }],
    ['junit', { outputFile: 'test-results/playwright-junit.xml' }],
  ],
  outputDir: 'test-results/playwright-artifacts',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'mvn -B -f backend/pom.xml spring-boot:run',
      cwd: '..',
      url: 'http://127.0.0.1:8080/api/health',
      timeout: 180_000,
      reuseExistingServer: false,
      env: {
        SPRING_PROFILES_ACTIVE: 'e2e',
        SPRING_DATASOURCE_URL: 'jdbc:h2:mem:price_lab7_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE',
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'org.h2.Driver',
        SPRING_DATASOURCE_USERNAME: 'sa',
        SPRING_DATASOURCE_PASSWORD: '',
        APP_DEMO_SEED: 'false',
        SPRING_SQL_INIT_DATA_LOCATIONS: new URL('./e2e/admin-fixture.sql', import.meta.url).href,
      },
    },
    {
      command: 'npm run dev -- --host 127.0.0.1',
      cwd: '.',
      url: 'http://127.0.0.1:5173',
      timeout: 90_000,
      reuseExistingServer: false,
    },
  ],
});
