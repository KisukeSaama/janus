/**
 * The one way this console talks to Janus.
 *
 * Nothing about the session is held here. The server sets an HttpOnly cookie, which no script on
 * this origin can read — including this one, and including anything that manages to run beside it.
 * The console therefore has nothing to keep, nothing to clear, and nothing to leak; signing out is a
 * request, not a variable being emptied.
 *
 * What the console does have to send back is the CSRF token, on every write. Spring puts it in a
 * readable cookie for exactly this purpose: a cookie travels on its own, a header does not, so
 * echoing one into the other is what proves the request came from this page rather than from
 * somebody else's.
 */

/** Signals that the session was refused; the console returns to the sign-in screen. */
export const AUTH_REQUIRED = 'AUTH_REQUIRED';

/**
 * Failures Janus itself recognises carry a code so the console can render them in the reader's
 * language. Anything the backend explains in prose is passed through untranslated.
 */
export type ErrorCode = 'OFFLINE' | 'THROTTLED' | 'AUTH_REQUIRED';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly fieldErrors?: Record<string, string>,
    readonly code?: ErrorCode,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type Problem = { detail?: string; title?: string; errors?: Record<string, string> };

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string {
  return (
    document.cookie
      .split('; ')
      .find((entry) => entry.startsWith(`${name}=`))
      ?.slice(name.length + 1) ?? ''
  );
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(options.headers as object) };
  if (!SAFE.has(method)) {
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = decodeURIComponent(token);
  }

  let response: Response;
  try {
    response = await fetch(`/api/admin${path}`, { ...options, headers, credentials: 'same-origin' });
  } catch {
    throw new ApiError('Janus is unreachable. Check that the backend is running.', 0, undefined, 'OFFLINE');
  }

  if (response.status === 401) {
    throw new ApiError(AUTH_REQUIRED, 401, undefined, 'AUTH_REQUIRED');
  }

  if (!response.ok) {
    const problem: Problem | null = await response.json().catch(() => null);
    throw new ApiError(
      describe(problem, response.status),
      response.status,
      problem?.errors,
      response.status === 429 ? 'THROTTLED' : undefined,
    );
  }

  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  return response.json();
}

/** Convenience for the three verbs that always carry a JSON body. */
export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });

export const put = <T>(path: string, body: unknown) =>
  api<T>(path, { method: 'PUT', body: JSON.stringify(body) });

export const del = (path: string) => api<void>(path, { method: 'DELETE' });

function describe(problem: Problem | null, status: number): string {
  if (status === 429) return 'Too many failed attempts. Wait before trying again.';
  if (problem?.errors) {
    const fields = Object.entries(problem.errors).map(([field, message]) => `${field} ${message}`);
    if (fields.length) return fields.join('; ');
  }
  return problem?.detail || problem?.title || `Request failed (${status})`;
}
