import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/** Dev-сервер на :3000, /api проксируется на бэкенд :8080. */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
