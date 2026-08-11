import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// jsdom keeps the document between tests in the same file; without this a query that should find
// one element finds the previous test's copy of it as well.
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.cookie.split('; ').forEach((entry) => {
    const name = entry.split('=')[0];
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  });
});
