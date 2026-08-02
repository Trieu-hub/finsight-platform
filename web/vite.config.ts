/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Where /api is forwarded. Fixed at localhost:8080 for ordinary local work; overridable so the
// E2E run can point the preview server at whatever host the stack is on.
const apiTarget = process.env.API_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Vitest — pure logic (formatters, JWT decode, roulette payout maths) plus component/hook tests
  // that render. One jsdom environment for both: the logic tests are indifferent to it, and a
  // second project just to save them a DOM would cost more config than it saves runtime.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
  server: {
    // Dev proxy: the browser calls /api/... (same origin as the FE), Vite forwards
    // it to the api-gateway. This sidesteps CORS entirely during development — no
    // backend change needed. In production the reverse proxy (Caddy/Traefik) plays
    // this role.
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  // `vite preview` serves the built bundle and does NOT inherit server.proxy, so the same rule is
  // repeated here. This is what the Playwright E2E run drives: the production bundle, with /api
  // reaching the gateway the way Caddy routes it in production.
  preview: {
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
})
