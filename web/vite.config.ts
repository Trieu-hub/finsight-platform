import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Dev proxy: the browser calls /api/... (same origin as the FE), Vite forwards
    // it to the api-gateway. This sidesteps CORS entirely during development — no
    // backend change needed. In production the reverse proxy (Caddy/Traefik) plays
    // this role.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
