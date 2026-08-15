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
        // A floor, not a target. Set just under where the suite stands so that coverage can only
        // be raised from here: what this catches is a screen added with no test at all, which is
        // how the console arrived at having none for the two that display secrets. Raise each
        // figure as the gap closes rather than leaving it to a convention nobody enforces.
        //
        // Recalibrated for Vitest 4, and the branch figure in particular is not a fall. The v8
        // provider now maps its counts back through the AST rather than counting whatever the
        // transformed output happened to look like: the same suite over the same code went from
        // 719 branches to 1723, because the ones it used to miss are now counted and most of them
        // are uncovered. What follows is where that measurement stands today.
        thresholds: {
          statements: 43,
          lines: 43,
          branches: 30,
          functions: 35,
        },
      },
    },
  };
});
