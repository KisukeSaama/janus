/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The console proxies every surface the backend answers on. `/api` is the administration API it is
// built against; `/gateway` is proxied so the "send a test request" step can call the gateway the
// way a client service would, from the same origin, with no CORS exception to grant in development;
// `/oauth` is proxied so the exchange the guide documents can be tried from the same address.
export default defineConfig(({ mode }) => {
  const target = mode === 'docker' ? 'http://backend:8080' : 'http://localhost:8080';
  return {
    plugins: [react(), tailwindcss()],
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': { target, changeOrigin: true },
        '^/(?:[^/]+/)?gateway': { target, changeOrigin: true },
        '/oauth': { target, changeOrigin: true },
      },
    },
    // The console holds real decisions of its own — what counts as live, what needs attention, what
    // a failure says to the reader — and they are worth the same scrutiny as the backend's.
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/test/setup.ts',
      css: false,
      coverage: {
        provider: 'v8',
        reporter: ['text-summary', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        // Translation tables, type-only modules and the entry point assert nothing when covered.
        exclude: ['src/main.tsx', 'src/i18n/{en,fr}.ts', 'src/api/types.ts', 'src/test/**'],
      },
    },
  };
});
