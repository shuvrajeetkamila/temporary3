import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  server: {
    host: '0.0.0.0',
    allowedHosts: true,
    strictPort: true,
  },
  preview: {
    host: '0.0.0.0',
    allowedHosts: true,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
