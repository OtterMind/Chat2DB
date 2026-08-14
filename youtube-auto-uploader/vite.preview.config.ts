import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';
export default defineConfig({ plugins: [react()], resolve: { alias: { '@': resolve('src') } }, server: { host: '0.0.0.0', allowedHosts: true } });
