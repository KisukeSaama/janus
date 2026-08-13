import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, AUTH_REQUIRED, del, download, post, put } from './client';

/** The shape fetch returns, with only the parts the client actually reads. */
function answer(status: number, body?: unknown, headers: Record<string, string> = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name.toLowerCase()] ?? null },
    json: async () => {
      if (body === undefined) throw new Error('no body');
      return body;
    },
  } as unknown as Response;
}

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const lastCall = () => fetchMock.mock.calls[0] as [string, RequestInit];
const sentHeaders = () => lastCall()[1].headers as Record<string, string>;

describe('addressing', () => {
  it('sends every call to the administration API on this origin', async () => {
    fetchMock.mockResolvedValue(answer(200, { ok: true }));

    await api('/providers');

    expect(lastCall()[0]).toBe('/api/admin/providers');
    expect(lastCall()[1].credentials).toBe('same-origin');
  });

  it('returns what the endpoint answered', async () => {
    fetchMock.mockResolvedValue(answer(200, { slug: 'spotify' }));

    await expect(api('/providers/1')).resolves.toEqual({ slug: 'spotify' });
  });

  it('returns nothing for an answer that has no body', async () => {
    fetchMock.mockResolvedValue(answer(204));

    await expect(del('/providers/1')).resolves.toBeUndefined();
  });

  it('returns nothing for an answer that declares an empty body', async () => {
    fetchMock.mockResolvedValue(answer(200, undefined, { 'content-length': '0' }));

    await expect(api('/providers/1')).resolves.toBeUndefined();
  });
});

/**
 * A cookie travels on its own and a header does not, so echoing one into the other is what proves
 * the request came from this page rather than from somebody else's.
 */
describe('cross-site request forgery', () => {
  it('echoes the token back on a write', async () => {
    document.cookie = 'XSRF-TOKEN=token-1';
    fetchMock.mockResolvedValue(answer(200, {}));

    await post('/providers', { name: 'Spotify' });

    expect(sentHeaders()['X-XSRF-TOKEN']).toBe('token-1');
  });

  it('decodes a token the browser stored encoded', async () => {
    document.cookie = 'XSRF-TOKEN=a%2Bb%3Dc';
    fetchMock.mockResolvedValue(answer(200, {}));

    await put('/providers/1', {});

    expect(sentHeaders()['X-XSRF-TOKEN']).toBe('a+b=c');
  });

  // A read changes nothing, so it needs no proof of origin.
  it('does not send the token on a read', async () => {
    document.cookie = 'XSRF-TOKEN=token-1';
    fetchMock.mockResolvedValue(answer(200, {}));

    await api('/providers');

    expect(sentHeaders()['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('sends the write anyway when there is no token to echo', async () => {
    fetchMock.mockResolvedValue(answer(200, {}));

    await post('/providers', {});

    expect(sentHeaders()['X-XSRF-TOKEN']).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('picks its own cookie out of several', async () => {
    document.cookie = 'other=first';
    document.cookie = 'XSRF-TOKEN=token-1';
    document.cookie = 'another=last';
    fetchMock.mockResolvedValue(answer(200, {}));

    await post('/providers', {});

    expect(sentHeaders()['X-XSRF-TOKEN']).toBe('token-1');
  });
});

/** A file goes through the same session as everything else, and fails the same way. */
describe('downloading', () => {
  function file(status: number, body?: unknown) {
    return { ...answer(status, body), blob: async () => new Blob(['occurred_at\r\n']) } as unknown as Response;
  }

  // jsdom implements neither half of the object URL API the save goes through.
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:janus');
    URL.revokeObjectURL = vi.fn();
  });

  it('saves what the endpoint answered under the name it was given', async () => {
    fetchMock.mockResolvedValue(file(200));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    await download('/audit-events/export?outcome=DENIED', 'janus-activity.csv');

    expect(lastCall()[0]).toBe('/api/admin/audit-events/export?outcome=DENIED');
    expect(lastCall()[1].credentials).toBe('same-origin');
    expect(click).toHaveBeenCalledOnce();
    // Nothing is left behind in the document once the browser has been handed the file.
    expect(document.querySelector('a')).toBeNull();
  });

  it('reports a refused session rather than saving the refusal', async () => {
    fetchMock.mockResolvedValue(file(401));

    await expect(download('/audit-events/export', 'janus-activity.csv')).rejects.toMatchObject({
      code: 'AUTH_REQUIRED',
    });
  });

  it("explains a refusal in the backend's own words", async () => {
    fetchMock.mockResolvedValue(file(400, { detail: 'The range must start before it ends' }));

    await expect(download('/audit-events/export', 'janus-activity.csv')).rejects.toThrow(
      'The range must start before it ends',
    );
  });
});

describe('failures', () => {
  /** The console has nothing to clear — the session cookie is not readable from here — so it signals. */
  it('reports a refused session by its code so the console can return to sign-in', async () => {
    fetchMock.mockResolvedValue(answer(401));

    await expect(api('/providers')).rejects.toMatchObject({ code: 'AUTH_REQUIRED', message: AUTH_REQUIRED });
  });

  it('reports an unreachable backend rather than a parse failure', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(api('/providers')).rejects.toMatchObject({ code: 'OFFLINE', status: 0 });
  });

  it('reports being throttled by its code, whatever the body said', async () => {
    fetchMock.mockResolvedValue(answer(429, { detail: 'slow down' }));

    await expect(api('/providers')).rejects.toMatchObject({ code: 'THROTTLED', status: 429 });
  });

  /** Field by field, so a form can point at the entry that was refused. */
  it('carries the field errors a validation failure named', async () => {
    fetchMock.mockResolvedValue(answer(400, { errors: { slug: 'must be lowercase', name: 'is required' } }));

    const error = await api('/providers').catch((e: unknown) => e as ApiError);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).fieldErrors).toEqual({ slug: 'must be lowercase', name: 'is required' });
    expect((error as ApiError).message).toContain('slug must be lowercase');
  });

  it("passes the backend's own explanation through untouched", async () => {
    fetchMock.mockResolvedValue(answer(400, { detail: 'You already have an API with that slug' }));

    await expect(api('/providers')).rejects.toThrow('You already have an API with that slug');
  });

  it('falls back to the title when there is no detail', async () => {
    fetchMock.mockResolvedValue(answer(409, { title: 'Conflict' }));

    await expect(api('/providers')).rejects.toThrow('Conflict');
  });

  it('still says something useful when the failure carried no readable body', async () => {
    fetchMock.mockResolvedValue(answer(500));

    await expect(api('/providers')).rejects.toThrow('Request failed (500)');
  });
});
