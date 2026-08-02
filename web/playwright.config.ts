import { defineConfig, devices } from '@playwright/test'

/**
 * Browser end-to-end tests. Unlike the Vitest suite (which mocks the network), these drive a real
 * browser against the **production bundle** talking to a real, running backend — the k6 smoke test
 * covers the same journeys at the API level, this covers the half k6 cannot see: that the UI
 * actually renders and wires them up.
 *
 * The backend is NOT started here. `BASE_URL` must already serve the stack's api-gateway on
 * `/api/**`; in CI that is the ephemeral compose stack (see `.github/workflows/staging.yml`), and
 * locally it is `docker compose up -d`. The frontend itself is served by `vite preview` over the
 * built `dist/`, whose proxy forwards `/api` to the gateway exactly as Caddy does in production.
 */
const API_TARGET = process.env.API_TARGET ?? 'http://localhost:8080'
const PORT = Number(process.env.PREVIEW_PORT ?? 4173)

export default defineConfig({
  testDir: './e2e',
  // A journey that writes to the backend cannot safely run twice at once against one stack, and
  // the CI runner has two cores hosting nine JVMs — serial keeps failures meaningful.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  // One retry in CI only: the stack is shared with a k6 load run, so a single timeout is noise.
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 60_000,
  expect: {
    // Cold JVMs behind the gateway: the first authenticated fan-out can take seconds.
    timeout: 15_000,
  },
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run preview -- --port ${PORT} --strictPort`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: { API_TARGET },
  },
})
