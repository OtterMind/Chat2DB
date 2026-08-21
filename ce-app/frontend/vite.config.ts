import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import pkg from './package.json'

export default defineConfig({
  // Relative asset URLs are mandatory: the packaged app is loaded over file://,
  // where the default absolute '/assets/...' resolves to the filesystem root.
  base: './',
  plugins: [react()],
  // One React instance, always. A second copy (from a freshly added dependency
  // or a stale optimiser cache) throws "invalid hook call" at runtime while the
  // type checker stays perfectly happy.
  resolve: { dedupe: ['react', 'react-dom'] },
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
  // one source of truth for the version shown in the UI
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
})