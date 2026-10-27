import { defineConfig } from '@playwright/test';

const remoteURL = process.env.DOTKI_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: remoteURL ? 90_000 : 30_000,
  use: {
    baseURL: remoteURL || 'http://127.0.0.1:18080',
    viewport: { width: 1280, height: 960 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: remoteURL ? undefined : {
    command: 'server/build/bin/native/debugExecutable/server.kexe',
    env: { PORT: '18080', HOST: '127.0.0.1' },
    url: 'http://127.0.0.1:18080/health',
    reuseExistingServer: false,
    timeout: 15_000,
  },
});
