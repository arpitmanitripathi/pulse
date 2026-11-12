/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the browser talks only to Vite (:5173); Vite forwards /api to the backend,
// so the app sees one origin, exactly as it will behind Caddy in production.
const api = process.env.PULSE_API ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: { '/api': api },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
})
