import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n';
import { ConnectFlow } from './ConnectFlow';

/**
 * Registering an API writes a catalogue entry for the whole deployment; activating it writes a
 * credential for one account. The flow may do both, but the second is asked for, never assumed.
 *
 * Neither of them is a connection. Admitting a service to a destination belongs to the flow that
 * registers a service, and is tested with it.
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  // Routed by path rather than by call order: the screens read what they need to display, and a
  // queue of responses would hand a provider to whichever request happened to go out first.
  fetchMock.mockImplementation((path: string, init?: RequestInit) => {
    if ((init?.method ?? 'GET') === 'GET')
      return Promise.resolve(
        String(path).includes('/oauth/callback')
          ? answer(200, { url: 'https://janus.example.com/oauth/callback', configured: true })
          : answer(200, []),
      );
    return Promise.resolve(answer(200, { id: 'p1', application: { id: 'a1' }, apiKey: 'jnt_x' }));
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

/**
 * Everything the first step needs, up to the screen where activation is decided.
 *
 * The flow opens directly on the API description form.
 */
async function describeApi() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/what is this API called|comment s.appelle cette API/i), 'Payments');
  await user.type(screen.getByLabelText(/HTTPS/i), 'https://api.example.com');
  await user.click(screen.getByRole('button', { name: /continue|continuer/i }));
  return user;
}

/**
 * The calls that create something, in order.
 *
 * The screens also read what they need to display — the redirect address to declare at the provider,
 * among others — and a test about which records get created should not have to be rewritten every
 * time a screen shows one more thing.
 */
const writes = () => fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');

const paths = () => writes().map(([path]) => String(path));

const renderFlow = (onDone = vi.fn()) =>
  render(<ConnectFlow onClose={vi.fn()} onHome={vi.fn()} onDone={onDone} />, { wrapper: Wrapper });

describe('ConnectFlow', () => {
  it('does not ask for a secret until the operator activates the API for their account', async () => {
    renderFlow();
    const user = await describeApi();

    expect(screen.queryByLabelText(/the secret itself|le secret lui-m.me/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: /activate for my account|activer pour mon compte/i }));

    expect(screen.getByLabelText(/the secret itself|le secret lui-m.me/i)).toBeRequired();
  });

  it('registers the catalogue entry alone when activation was not asked for', async () => {
    const onDone = vi.fn();
    renderFlow(onDone);
    const user = await describeApi();

    await user.click(screen.getByRole('button', { name: /register the API|enregistrer l.API/i }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(paths()).toEqual(['/api/admin/providers']);
  });

  it('provisions the operator’s own credential once they ask for it', async () => {
    renderFlow();
    const user = await describeApi();

    await user.click(screen.getByRole('checkbox', { name: /activate for my account|activer pour mon compte/i }));
    await user.type(screen.getByLabelText(/the secret itself|le secret lui-m.me/i), 'sk-live-1');
    await user.click(screen.getByRole('button', { name: /register the API|enregistrer l.API/i }));

    // Two records and no third: which service may call this destination is the service's decision,
    // taken in the flow that registers one. Nothing here admits anybody to anything.
    await waitFor(() => expect(paths()).toEqual(['/api/admin/providers', '/api/admin/credentials']));
    expect(JSON.parse(writes()[1][1].body)).toMatchObject({ providerId: 'p1', secret: 'sk-live-1' });
  });

  it('records where an exchange puts its client credentials', async () => {
    renderFlow();
    const user = await describeApi();

    await user.click(screen.getByRole('radio', { name: /client id and secret|client id et client secret/i }));
    await user.type(screen.getByLabelText(/token endpoint|point de d.livrance/i), 'https://id.twitch.tv/oauth2/token');
    // Basic is the default, and the endpoints that read only the form body are why this is asked
    // here rather than found out from a refused exchange.
    await user.click(screen.getByRole('radio', { name: /in the form body|dans le corps du formulaire/i }));
    await user.click(screen.getByRole('button', { name: /register the API|enregistrer l.API/i }));

    await waitFor(() => expect(paths()).toEqual(['/api/admin/providers']));
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({
      authType: 'OAUTH2_CLIENT_CREDENTIALS',
      tokenClientAuth: 'POST',
    });
  });

  it('unwinds the catalogue entry when the credential is refused', async () => {
    refuse('/credentials');
    renderFlow();
    const user = await describeApi();

    await user.click(screen.getByRole('checkbox', { name: /activate for my account|activer pour mon compte/i }));
    await user.type(screen.getByLabelText(/the secret itself|le secret lui-m.me/i), 'sk-live-1');
    await user.click(screen.getByRole('button', { name: /register the API|enregistrer l.API/i }));

    await waitFor(() => expect(paths()).toContain('/api/admin/providers/p1'));
    expect(writes().at(-1)?.[1].method).toBe('DELETE');
  });
});
