import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Identity } from '../api';
import { I18nProvider } from '../i18n';
import { ThemeProvider } from '../theme';
import { ConsentPage } from './Consent';
import { leaveFor } from './routes';

/**
 * Letting an AI assistant act as the person signed in. The request is decided once, and whichever
 * way it goes the browser leaves for the address Janus answers with: the client is owed an answer
 * either way, and a screen that stayed put would leave it waiting.
 */

// The browser's own `location` cannot be replaced from a script, so the one exit is stood in for.
vi.mock('./routes', async (original) => ({ ...(await original<typeof import('./routes')>()), leaveFor: vi.fn() }));

const IDENTITY: Identity = { id: 'u1', username: 'ada', displayName: 'Ada Lovelace', role: 'ADMIN' };

const REQUEST = {
  clientName: 'Claude Code',
  redirectUri: 'https://assistant.example.net/callback',
  redirectHost: 'assistant.example.net',
  loopback: false,
  expiresAt: '2026-09-22T10:15:00Z',
};

const fetchMock = vi.fn();

beforeEach(() => {
  vi.mocked(leaveFor).mockReset();
  fetchMock.mockReset();
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    const url = String(path);
    if ((init?.method ?? 'GET') === 'GET') return Promise.resolve(answer(200, REQUEST));
    const verdict = url.endsWith('/approve') ? 'code=abc&state=xyz' : 'error=access_denied&state=xyz';
    return Promise.resolve(answer(200, { redirectUrl: `https://assistant.example.net/callback?${verdict}` }));
  });
  vi.stubGlobal('fetch', fetchMock);
  at('/mcp/authorize?request=r1');
});

afterEach(() => at('/'));

function at(path: string) {
  window.history.replaceState(null, '', path);
}

function answer(status: number, body: unknown = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => body,
  } as unknown as Response;
}

function Wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <I18nProvider>{children}</I18nProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const renderConsent = () => render(<ConsentPage identity={IDENTITY} />, { wrapper: Wrapper });
const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');

describe('ConsentPage', () => {
  it('names the client as a claim, and says plainly where a remote answer goes', async () => {
    renderConsent();

    expect(await screen.findByText('“Claude Code”')).toBeInTheDocument();
    expect(screen.getByText(/cannot confirm|ne peut pas confirmer/i)).toBeInTheDocument();
    expect(screen.getByText('assistant.example.net')).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(/remote site|site distant/i);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/admin/mcp/authorizations/r1');
  });

  it('approves the request, then leaves for the address Janus answered with', async () => {
    const user = userEvent.setup();
    renderConsent();

    await user.click(await screen.findByRole('button', { name: /^approve$|^approuver$/i }));

    await waitFor(() => expect(leaveFor).toHaveBeenCalledOnce());
    expect(writes().map(([path, init]) => [String(path), init.method])).toEqual([
      ['/api/admin/mcp/authorizations/r1/approve', 'POST'],
    ]);
    expect(leaveFor).toHaveBeenCalledWith('https://assistant.example.net/callback?code=abc&state=xyz');
    // On its way out, it offers nothing more: the request has been decided.
    expect(screen.getByRole('button', { name: /deny|refuser/i })).toBeDisabled();
  });

  it('denies the request, and still hands the answer back to the client', async () => {
    const user = userEvent.setup();
    renderConsent();

    await user.click(await screen.findByRole('button', { name: /^deny$|^refuser$/i }));

    await waitFor(() => expect(leaveFor).toHaveBeenCalledOnce());
    expect(writes().map(([path]) => String(path))).toEqual(['/api/admin/mcp/authorizations/r1/deny']);
    expect(leaveFor).toHaveBeenCalledWith('https://assistant.example.net/callback?error=access_denied&state=xyz');
  });

  it('says a request is over when Janus no longer holds it, and offers nothing to decide', async () => {
    fetchMock.mockResolvedValue(answer(404, { detail: 'Unknown request' }));
    renderConsent();

    expect(await screen.findByRole('heading', { name: /expired|expiré/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^approve$|^approuver$/i })).not.toBeInTheDocument();
    expect(leaveFor).not.toHaveBeenCalled();
  });

  it('explains a request the authorization endpoint already refused, without asking Janus again', async () => {
    at('/mcp/authorize?error=invalid_redirect_uri');
    renderConsent();

    expect(screen.getByRole('heading', { name: /cannot be answered|impossible de répondre/i })).toBeInTheDocument();
    expect(screen.getByText(/does not match|ne correspond pas/i)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses to follow an answer the browser would run rather than leave for', async () => {
    fetchMock.mockImplementation((_: string, init?: RequestInit) =>
      Promise.resolve(
        (init?.method ?? 'GET') === 'GET' ? answer(200, REQUEST) : answer(200, { redirectUrl: 'javascript:alert(1)' }),
      ),
    );
    const user = userEvent.setup();
    renderConsent();

    await user.click(await screen.findByRole('button', { name: /^approve$|^approuver$/i }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(leaveFor).not.toHaveBeenCalled();
  });
});
