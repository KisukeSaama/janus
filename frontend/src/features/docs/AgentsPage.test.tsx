import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { Identity } from '../../api';
import { parsePath } from '../../app/routes';
import { I18nProvider } from '../../i18n';
import { AgentsPage } from './AgentsPage';

/**
 * AI coding: the file for a repository and the assistant in the console, on one page. What is
 * exercised is what the merge had to keep — that the reader is told which half is which, that the
 * old address still lands here — and the one write the page makes: revoking an assistant, which
 * asks first, writes, and the list follows.
 */

const IDENTITY: Identity = { id: 'u1', username: 'ada', displayName: 'Ada Lovelace', role: 'USER' };

const CONNECTION = {
  id: 'm1',
  clientName: 'Claude Code',
  createdAt: '2026-09-01T09:00:00Z',
  lastUsedAt: null,
  expiresAt: '2026-12-01T09:00:00Z',
};

const fetchMock = vi.fn();
let connections: (typeof CONNECTION)[] = [];

beforeEach(() => {
  // The table asks whether it is on a narrow screen; jsdom answers nothing at all without this.
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
  );
  connections = [CONNECTION];
  fetchMock.mockReset();
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    const url = String(path);
    if ((init?.method ?? 'GET') === 'GET') {
      if (url.endsWith('/mcp/server')) return Promise.resolve(answer(200, { url: 'https://janus.example.com/mcp' }));
      if (url.endsWith('/mcp/connections')) return Promise.resolve(answer(200, connections));
      if (url.includes('/agent-file'))
        return Promise.resolve(answer(200, { fileName: 'JANUS.md', content: '# Janus gateway\n', apiCount: 0 }));
      return Promise.resolve(answer(200, []));
    }
    // Revoking removes the row on the server, which is what the refetch that follows must show.
    connections = [];
    return Promise.resolve(answer(204));
  });
  vi.stubGlobal('fetch', fetchMock);
});

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
      <I18nProvider>{children}</I18nProvider>
    </QueryClientProvider>
  );
}

const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');

describe('AgentsPage', () => {
  it('opens on the two halves, each a link to its own part of the page', async () => {
    render(<AgentsPage identity={IDENTITY} />, { wrapper: Wrapper });

    const file = screen.getByRole('link', { name: /in your repository|dans votre dépôt/i });
    const assistant = screen.getByRole('link', { name: /in this console|dans cette console/i });
    expect(file).toHaveAttribute('href', '#file');
    expect(assistant).toHaveAttribute('href', '#assistant');
    expect(document.getElementById('file')).not.toBeNull();
    expect(document.getElementById('assistant')).not.toBeNull();
  });

  it('keeps the old assistants address, which now lands here', () => {
    expect(parsePath('/documentation/mcp')).toEqual({ page: 'agents' });
    expect(parsePath('/documentation/ai-coding')).toEqual({ page: 'agents' });
  });

  it('prints the server address into the command that adds it', async () => {
    render(<AgentsPage identity={IDENTITY} />, { wrapper: Wrapper });

    expect(
      await screen.findByText('claude mcp add --transport http janus https://janus.example.com/mcp'),
    ).toBeInTheDocument();
  });

  it('revokes an assistant after asking, and the list follows', async () => {
    const user = userEvent.setup();
    render(<AgentsPage identity={IDENTITY} />, { wrapper: Wrapper });

    await user.click(await screen.findByRole('button', { name: /revoke “claude code”|révoquer « claude code »/i }));

    // Nothing is written until the question has been answered.
    expect(writes()).toHaveLength(0);
    const dialog = screen.getByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: /^revoke$|^révoquer$/i }));

    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(String(writes()[0][0])).toBe('/api/admin/mcp/connections/m1');
    expect(writes()[0][1].method).toBe('DELETE');
    expect(await screen.findByText(/no assistant connected|aucun assistant connecté/i)).toBeInTheDocument();
  });
});
