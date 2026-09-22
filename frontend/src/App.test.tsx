import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import { I18nProvider } from './i18n';
import { ThemeProvider } from './theme';

/**
 * An MCP client's request can arrive at a browser that is not signed in. Signing in does not move the
 * address, so the request it came for is the screen that appears next, rather than the console.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    const url = String(path);
    const method = init?.method ?? 'GET';
    if (url.endsWith('/session')) {
      return Promise.resolve(
        method === 'POST'
          ? answer(200, { id: 'u1', username: 'ada', displayName: 'Ada Lovelace', role: 'USER' })
          : answer(401),
      );
    }
    if (url.includes('/mcp/authorizations/')) {
      return Promise.resolve(
        answer(200, {
          clientName: 'Claude Code',
          redirectUri: 'http://127.0.0.1:33418/callback',
          redirectHost: '127.0.0.1:33418',
          loopback: true,
          expiresAt: '2026-09-22T10:15:00Z',
        }),
      );
    }
    return Promise.resolve(answer(200, []));
  });
  vi.stubGlobal('fetch', fetchMock);
  window.history.replaceState(null, '', '/mcp/authorize?request=r1');
});

afterEach(() => window.history.replaceState(null, '', '/'));

function answer(status: number, body: unknown = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => body,
  } as unknown as Response;
}

function Wrapper({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <I18nProvider>{children}</I18nProvider>
    </ThemeProvider>
  );
}

describe('App', () => {
  it('asks for a sign-in first, then shows the request it arrived with instead of the console', async () => {
    const user = userEvent.setup();
    render(<App />, { wrapper: Wrapper });

    await user.type(await screen.findByLabelText(/username|identifiant/i), 'ada');
    await user.type(screen.getByLabelText(/password|mot de passe/i), 'hunter2');
    await user.click(screen.getByRole('button', { name: /sign in|se connecter/i }));

    expect(await screen.findByText('“Claude Code”')).toBeInTheDocument();
    expect(screen.getByText('127.0.0.1:33418')).toBeInTheDocument();
    // Standalone: no rail offering the rest of the console in the middle of the decision.
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
