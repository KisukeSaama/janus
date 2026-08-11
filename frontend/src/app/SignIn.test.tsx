import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../i18n';
import { ThemeProvider } from '../theme';
import { SignIn } from './SignIn';

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  localStorage.clear();
});

function answer(status: number, body: unknown = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => body,
  } as unknown as Response;
}

/** The console's own providers, minus routing: the screen under test needs both to render at all. */
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

const renderSignIn = () => render(<SignIn />, { wrapper: Wrapper });

async function signInAs(username: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/username|identifiant/i), username);
  await user.type(screen.getByLabelText(/password|mot de passe/i), password);
  await user.click(screen.getByRole('button', { name: /sign in|connexion|se connecter/i }));
}

describe('SignIn', () => {
  it('asks for a username and a password and nothing else', () => {
    renderSignIn();

    expect(screen.getByLabelText(/username|identifiant/i)).toBeRequired();
    expect(screen.getByLabelText(/password|mot de passe/i)).toHaveAttribute('type', 'password');
  });

  it('sends what was typed to the session endpoint', async () => {
    fetchMock.mockResolvedValue(answer(200, { username: 'root', role: 'SUPER_ADMIN' }));

    renderSignIn();
    await signInAs('root', 'hunter2');

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    const [path, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/admin/session');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body as string)).toEqual({ username: 'root', password: 'hunter2' });
  });

  /**
   * A refused password and an unknown username are answered identically on purpose: telling them
   * apart is how an attacker learns which usernames exist.
   */
  it('says the same thing whether the password or the username was wrong', async () => {
    fetchMock.mockResolvedValue(answer(401));

    const { unmount } = renderSignIn();
    await signInAs('root', 'wrong-password');
    const refusedPassword = (await screen.findByRole('alert')).textContent;
    unmount();

    renderSignIn();
    await signInAs('nobody-by-that-name', 'hunter2');
    const unknownUser = (await screen.findByRole('alert')).textContent;

    expect(refusedPassword).toBe(unknownUser);
    expect(refusedPassword).toBeTruthy();
  });

  /** Whatever the backend said about why, the reader is told only that it was not accepted. */
  it('does not pass a backend explanation of a refusal through to the reader', async () => {
    fetchMock.mockResolvedValue(answer(401, { detail: 'Account root is disabled' }));

    renderSignIn();
    await signInAs('root', 'hunter2');

    expect((await screen.findByRole('alert')).textContent ?? '').not.toContain('disabled');
  });

  it('tells the reader to wait when the attempt was throttled', async () => {
    fetchMock.mockResolvedValue(answer(429, { detail: 'Too many failed sign-in attempts' }));

    renderSignIn();
    await signInAs('root', 'hunter2');

    expect((await screen.findByRole('alert')).textContent ?? '').toMatch(/wait|attend|patient|trop/i);
  });

  /** The password is never held anywhere it could be replayed from — including the form itself. */
  it('keeps nothing in the page after a refusal', async () => {
    fetchMock.mockResolvedValue(answer(401));

    const { container } = renderSignIn();
    await signInAs('root', 'hunter2');
    await screen.findByRole('alert');

    expect(container.innerHTML).not.toContain('hunter2');
  });

  it('refuses to submit an empty form to the backend at all', async () => {
    renderSignIn();

    await userEvent.setup().click(screen.getByRole('button', { name: /sign in|connexion|se connecter/i }));

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
