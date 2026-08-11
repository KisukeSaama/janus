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
 */

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(answer(200, { id: 'p1' }));
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

/** Everything the first step needs, up to the screen where activation is decided. */
async function describeApi() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/what is this API called|comment s.appelle cette API/i), 'Payments');
  await user.type(screen.getByLabelText(/HTTPS/i), 'https://api.example.com');
  await user.click(screen.getByRole('button', { name: /continue|continuer/i }));
  return user;
}

const paths = () => fetchMock.mock.calls.map(([path]) => String(path));

const renderFlow = (onDone = vi.fn()) =>
  render(<ConnectFlow onClose={vi.fn()} onDone={onDone} />, { wrapper: Wrapper });

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

    await waitFor(() => expect(paths()).toEqual(['/api/admin/providers', '/api/admin/credentials']));
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toMatchObject({ providerId: 'p1', secret: 'sk-live-1' });
  });

  it('unwinds the catalogue entry when the credential is refused', async () => {
    fetchMock.mockResolvedValueOnce(answer(200, { id: 'p1' })).mockResolvedValueOnce(answer(400, { detail: 'nope' }));
    renderFlow();
    const user = await describeApi();

    await user.click(screen.getByRole('checkbox', { name: /activate for my account|activer pour mon compte/i }));
    await user.type(screen.getByLabelText(/the secret itself|le secret lui-m.me/i), 'sk-live-1');
    await user.click(screen.getByRole('button', { name: /register the API|enregistrer l.API/i }));

    await waitFor(() => expect(paths()).toContain('/api/admin/providers/p1'));
    expect(fetchMock.mock.calls[2][1].method).toBe('DELETE');
  });
});
