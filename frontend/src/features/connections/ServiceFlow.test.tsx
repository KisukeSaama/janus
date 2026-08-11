import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n';
import { ServiceFlow } from './ServiceFlow';

/**
 * Registering a service is where connections are made: the identity that will call, and one access
 * rule per API it was ticked against. The key it ends on exists on that screen and nowhere else,
 * which is why the flow does not close itself over it.
 */

/** Three registered APIs, two of which nobody holds a credential for and so cannot be granted yet. */
const PROVIDERS = [
  { id: 'p1', name: 'Spotify', slug: 'spotify', baseUrl: 'https://api.spotify.com/v1', authType: 'BEARER', enabled: true },
  { id: 'p2', name: 'Notion', slug: 'notion', baseUrl: 'https://api.notion.com/v1', authType: 'BEARER', enabled: true },
  { id: 'p3', name: 'PokeAPI', slug: 'pokeapi', baseUrl: 'https://pokeapi.co/api/v2', authType: 'NONE', enabled: true },
];
const CREDENTIALS = [{ id: 'c1', name: 'spotify-secret', providerId: 'p1', authType: 'BEARER', enabled: true }];

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  // Routed by path rather than by call order: the screens read what they need to display, and a
  // queue of responses would hand a provider to whichever request happened to go out first.
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    const url = String(path);
    if ((init?.method ?? 'GET') === 'GET') {
      if (url.includes('/providers')) return Promise.resolve(answer(200, { content: PROVIDERS, totalPages: 1 }));
      if (url.includes('/credentials')) return Promise.resolve(answer(200, CREDENTIALS));
      return Promise.resolve(answer(200, []));
    }
    // A credential written from the list comes back as the record the row it unlocks now holds, so
    // it is echoed rather than answered with the generic write.
    if (url.endsWith('/credentials')) {
      const body = JSON.parse(String(init?.body ?? '{}'));
      return Promise.resolve(answer(200, { id: `c-${body.providerId}`, ...body }));
    }
    return Promise.resolve(answer(200, { id: 'g1', application: { id: 'a1' }, apiKey: 'jnt_x' }));
  });
  vi.stubGlobal('fetch', fetchMock);
});

/** Makes one write fail, whatever else the flow reads before reaching it. */
function refuse(path: string) {
  const inner = fetchMock.getMockImplementation()!;
  fetchMock.mockImplementation((called: string, init?: RequestInit) =>
    String(called).endsWith(path) && (init?.method ?? 'GET') !== 'GET'
      ? Promise.resolve(answer(400, { detail: 'nope' }))
      : inner(called, init),
  );
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
      <I18nProvider>{children}</I18nProvider>
    </QueryClientProvider>
  );
}

const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');
const paths = () => writes().map(([path]) => String(path));

const renderFlow = (onDone = vi.fn()) =>
  render(<ServiceFlow onClose={vi.fn()} onHome={vi.fn()} onDone={onDone} />, { wrapper: Wrapper });

/**
 * The Activate button belonging to one row, found through that row rather than by name: every row's
 * button reads the same word, and what tells them apart is which API they sit beside.
 */
async function activateFor(api: RegExp) {
  const row = (await screen.findByRole('checkbox', { name: api })).closest('.py-3') as HTMLElement;
  return within(row).getByRole('button', { name: /activate|activer/i });
}

/** Names the service and moves on to the APIs it may call. */
async function nameService() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/^name$|^nom$/i), 'orders-api');
  await user.click(screen.getByRole('button', { name: /continue|continuer/i }));
  return user;
}

describe('ServiceFlow', () => {
  it('cannot tick an API nobody holds a credential for', async () => {
    renderFlow();
    await nameService();

    // Listed, because an API registered a minute ago must not look like it vanished, and disabled,
    // because a grant has to name the credential it presents.
    expect(await screen.findByRole('checkbox', { name: /spotify/i })).toBeEnabled();
    expect(screen.getByRole('checkbox', { name: /notion/i })).toBeDisabled();
  });

  it('activates an open API on the click, from the list itself', async () => {
    renderFlow();
    const user = await nameService();

    // Nothing to type: an open API holds no secret, so the row is one click from being grantable
    // rather than a dead end that sends the reader out of a half-written service.
    await user.click(await activateFor(/pokeapi/i));

    const box = screen.getByRole('checkbox', { name: /pokeapi/i });
    await waitFor(() => expect(box).toBeEnabled());
    expect(box).toBeChecked();
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({ providerId: 'p3', authType: 'NONE', secret: null });
  });

  it('asks for the one value an API needs, then grants it with the service', async () => {
    renderFlow();
    const user = await nameService();

    await user.click(await activateFor(/notion/i));
    await user.type(screen.getByLabelText(/the secret itself|le secret lui-même/i), 'ntn_live');
    await user.click(await activateFor(/notion/i));

    // The credential the reader just provisioned is what the row was missing, so it is ticked on the
    // way back rather than asked about a second time.
    await waitFor(() => expect(screen.getByRole('checkbox', { name: /notion/i })).toBeChecked());
    await user.click(screen.getByRole('button', { name: /issue a key|émettre une clé/i }));

    await waitFor(() =>
      expect(paths()).toEqual(['/api/admin/credentials', '/api/admin/applications', '/api/admin/grants']),
    );
    expect(JSON.parse(writes()[2][1].body)).toMatchObject({ providerId: 'p2', credentialId: 'c-p2' });
  });

  it('writes the service, then one access rule per API ticked', async () => {
    renderFlow();
    const user = await nameService();

    await user.click(await screen.findByRole('checkbox', { name: /spotify/i }));
    await user.click(screen.getByRole('button', { name: /issue a key|émettre une clé/i }));

    await waitFor(() => expect(paths()).toEqual(['/api/admin/applications', '/api/admin/grants']));
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({ name: 'orders-api', enabled: true });
    expect(JSON.parse(writes()[1][1].body)).toMatchObject({
      applicationId: 'a1',
      providerId: 'p1',
      credentialId: 'c1',
    });
  });

  it('issues a key to a service that reaches nothing, when that is the answer', async () => {
    renderFlow();
    const user = await nameService();

    await user.click(screen.getByRole('button', { name: /issue a key|émettre une clé/i }));

    // A service registered today may be subscribed next week; refusing its key until then helps
    // nobody. What it does not get is an example call, because there is nothing to call.
    await waitFor(() => expect(paths()).toEqual(['/api/admin/applications']));
    expect(await screen.findByText('jnt_x')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /send the request|envoyer la requête/i })).not.toBeInTheDocument();
  });

  it('ends on the key, which exists on this screen and nowhere else', async () => {
    const onDone = vi.fn();
    renderFlow(onDone);
    const user = await nameService();

    await user.click(await screen.findByRole('checkbox', { name: /spotify/i }));
    await user.click(screen.getByRole('button', { name: /issue a key|émettre une clé/i }));

    expect(await screen.findByText('jnt_x')).toBeInTheDocument();
    // Janus keeps a hash of it, so the flow does not close itself over the one screen that has it.
    expect(onDone).not.toHaveBeenCalled();
    // And the reader leaves with the request that proves the whole chain agrees.
    expect(screen.getByRole('button', { name: /send the request|envoyer la requête/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^done$|^terminé$/i }));
    expect(onDone).toHaveBeenCalled();
  });

  it('takes the service back when an access rule is refused', async () => {
    refuse('/grants');
    renderFlow();
    const user = await nameService();

    await user.click(await screen.findByRole('checkbox', { name: /spotify/i }));
    await user.click(screen.getByRole('button', { name: /issue a key|émettre une clé/i }));

    // A key issued for a service that reaches nothing it was promised is worse than no key: it is a
    // credential nobody knows is useless. Deleting the application takes its grants with it.
    await waitFor(() => expect(paths()).toContain('/api/admin/applications/a1'));
    expect(writes().at(-1)?.[1].method).toBe('DELETE');
    expect(screen.queryByText('jnt_x')).not.toBeInTheDocument();
  });
});
