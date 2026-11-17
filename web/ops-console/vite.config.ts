import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      // Run against a DEV stack: the ops-web edge does the auth check.
      '/api': 'http://localhost:8082',
    },
  },
});
