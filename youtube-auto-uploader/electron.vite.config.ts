import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    build: { lib: { entry: resolve('electron/main.ts') }, rollupOptions: { output: { entryFileNames: 'index.js' } } }
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: { lib: { entry: resolve('electron/preload.ts') }, rollupOptions: { output: { entryFileNames: 'index.js' } } }
  },
  renderer: {
    root: '.',
    resolve: { alias: { '@': resolve('src') } },
    plugins: [react()],
    build: { rollupOptions: { input: resolve('index.html') } }
  }
})
