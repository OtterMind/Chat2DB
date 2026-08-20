import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // Relative asset URLs are mandatory: the packaged app is loaded over file://,
  // where the default absolute '/assets/...' resolves to the filesystem root.
  base: './',
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    allowedHosts: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8742', changeOrigin: true },
      '/ws': { target: 'ws://127.0.0.1:8742', ws: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
})