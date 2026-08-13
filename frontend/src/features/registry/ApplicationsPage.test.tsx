import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n';
import { ApplicationsPage } from './ApplicationsPage';

/**
 * Editing what a service may reach. The list of APIs is the same question the setup flow asks, and
 * it has the same answer for a row nobody holds a credential for: what is missing is supplied here
 * rather than in another screen the reader has to go and find.
 */

const APPLICATIONS = [
  { id: 'a1', name: 'orders-api', enabled: true, allowedOrigins: [], apiKeyRotatedAt: '2026-08-01T00:00:00Z' },
];
/** One API this account holds a credential for, and one open API it holds none for. */
const PROVIDERS = [
  { id: 'p1', name: 'Spotify', slug: 'spotify', baseUrl: 'https://api.spotify.com/v1', authType: 'BEARER', enabled: true },
  { id: 'p2', name: 'PokeAPI', slug: 'pokeapi', baseUrl: 'https://pokeapi.co/api/v2', authType: 'NONE', enabled: true },
];
const CREDENTIALS = [{ id: 'c1', name: 'spotify-secret', providerId: 'p1', authType: 'BEARER', enabled: true }];

const fetchMock = vi.fn();

beforeEach(() => {
  // The table asks whether it is on a narrow screen; jsdom answers nothing at all without this.
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })),
  );
  fetchMock.mockReset();
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    const url = String(path);
    if ((init?.method ?? 'GET') === 'GET') {
      if (url.includes('/applications')) return Promise.resolve(answer(200, APPLICATIONS));
      if (url.includes('/providers')) return Promise.resolve(answer(200, { content: PROVIDERS, totalPages: 1 }));
      if (url.includes('/credentials')) return Promise.resolve(answer(200, CREDENTIALS));
      return Promise.resolve(answer(200, []));
    }
    // The credential comes back as the record the row it unlocks now holds, so it is echoed.
    if (url.endsWith('/credentials')) {
      const body = JSON.parse(String(init?.body ?? '{}'));
      return Promise.resolve(answer(200, { id: `c-${body.providerId}`, ...body }));
    }
    return Promise.resolve(answer(200, { id: 'x1' }));
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
const paths = () => writes().map(([path]) => String(path));

describe('ApplicationsPage', () => {
  it('activates an API from the list of what a service may reach, and subscribes it', async () => {
    const user = userEvent.setup();
    render(<ApplicationsPage />, { wrapper: Wrapper });

    await user.click(await screen.findByRole('button', { name: /more actions for|autres actions pour/i }));
    await user.click(await screen.findByRole('menuitem', { name: /^edit$|^modifier$/i }));

    // The row is listed and cannot be ticked, since a subscription has to name the credential it
    // presents — and the way to hold one is on the row rather than in another screen.
    const box = await screen.findByRole('checkbox', { name: /pokeapi/i });
    expect(box).toBeDisabled();
    const row = box.closest('.py-3') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /activate|activer/i }));

    await waitFor(() => expect(screen.getByRole('checkbox', { name: /pokeapi/i })).toBeEnabled());
    expect(screen.getByRole('checkbox', { name: /pokeapi/i })).toBeChecked();

    await user.click(screen.getByRole('button', { name: /save|enregistrer/i }));

    await waitFor(() => expect(paths()).toContain('/api/admin/grants'));
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({ providerId: 'p2', authType: 'NONE', secret: null });
    expect(JSON.parse(writes().at(-1)![1].body)).toMatchObject({
      applicationId: 'a1',
      providerId: 'p2',
      credentialId: 'c-p2',
    });
  });
});
