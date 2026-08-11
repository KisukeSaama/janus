import type { Application, Credential, Grant, Provider } from '../api';

/**
 * Janus stores four records to authorize one call. An operator does not maintain four records: they
 * maintain one statement, "this service may call that API", and they want to know whether it
 * currently works.
 *
 * A Connection is that statement, assembled from the grant and the three records it points at. It is
 * derived on the client and owns no state of its own, so the registry stays the single source of
 * truth and every edit still goes through the ordinary endpoints.
 */

export type Blocker = 'grant' | 'application' | 'provider' | 'credential';

export type Connection = {
  /** The grant's identifier: a connection is exactly as unique as the grant behind it. */
  id: string;
  grant: Grant;
  application?: Application;
  provider?: Provider;
  credential?: Credential;
  /** The gateway path callers use, without the origin. */
  gatewayPath: string;
  /**
   * Whether a call would actually be forwarded right now. Four records can each switch it off, and
   * the grant's own `enabled` flag is only one of them: a disabled provider answers 404 while the
   * grant still reads "active", which is exactly the state an operator cannot diagnose from four
   * separate tables.
   */
  live: boolean;
  /** The first record standing in the way, so the console can name it instead of saying "inactive". */
  blockedBy?: Blocker;
};

export function buildConnections(
  grants: Grant[],
  apps: Application[],
  providers: Provider[],
  credentials: Credential[],
  username?: string,
): Connection[] {
  const byId = <T extends { id: string }>(rows: T[]) => new Map(rows.map((row) => [row.id, row]));
  const appById = byId(apps);
  const providerById = byId(providers);
  const credentialById = byId(credentials);

  return grants.map((grant) => {
    const application = appById.get(grant.applicationId);
    const provider = providerById.get(grant.providerId);
    const credential = credentialById.get(grant.credentialId);

    // Checked in the order the gateway itself checks them, so the record named first is the record
    // that would refuse the call first.
    const blockedBy: Blocker | undefined = !grant.enabled
      ? 'grant'
      : application && !application.enabled
        ? 'application'
        : provider && !provider.enabled
          ? 'provider'
          : credential && !credential.enabled
            ? 'credential'
            : undefined;

    return {
      id: grant.id,
      grant,
      application,
      provider,
      credential,
      gatewayPath: provider ? `${username ? `/${username}` : ''}/gateway/${provider.slug}` : '',
      live: !blockedBy,
      blockedBy,
    };
  });
}

/** Alphabetical by caller, then by destination: the order an operator scans for a name in. */
export function sortConnections(connections: Connection[]): Connection[] {
  return [...connections].sort(
    (a, b) =>
      a.grant.applicationName.localeCompare(b.grant.applicationName) ||
      a.grant.providerName.localeCompare(b.grant.providerName),
  );
}

/** `Payments API v2` becomes `payments-api-v2`, which is what the slug pattern accepts. */
export function toSlug(name: string): string {
  return (
    name
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .slice(0, 60)
      // Trimmed after the cut, not before: a truncation that lands on a hyphen would otherwise
      // produce a slug the server refuses.
      .replace(/^-+|-+$/g, '')
  );
}

/**
 * A path in the form the gateway expects: absolute, trimmed. Somebody typing `v1/orders` means
 * `/v1/orders`, and refusing the entry over a missing slash teaches nothing.
 */
export function absolutePath(pattern: string): string {
  const value = pattern.trim();
  if (!value) return '';
  return value.startsWith('/') ? value : `/${value}`;
}

export function gatewayUrl(username: string, slug: string, origin = window.location.origin): string {
  return `${origin.replace(/\/$/, '')}/${encodeURIComponent(username)}/gateway/${slug}`;
}

export function curlFor(username: string, slug: string, path: string, applicationId: string, key: string): string {
  const target = path.startsWith('/') ? path : `/${path}`;
  return [
    `curl ${gatewayUrl(username, slug)}${target}`,
    `  -H "X-Janus-Application-Id: ${applicationId}"`,
    `  -H "X-Janus-Api-Key: ${key}"`,
  ].join(' \\\n');
}

/* ── Trying the connection for real ────────────────────────────────────── */

export type ProbeResult = {
  /** `forwarded` means the request reached the upstream API, whatever the API then answered. */
  verdict: 'forwarded' | 'refused' | 'unreachable';
  status: number;
  /** Janus's own explanation, when Janus is the one refusing. */
  detail?: string;
  correlationId?: string;
  /** HIT, MISS, BYPASS… present when the provider allows response reuse. */
  cache?: string;
  millis: number;
};

/**
 * Calls the gateway the way a client service would: same origin, the two Janus headers, nothing
 * else. The console has the key in memory for exactly one moment, right after it was issued, and
 * this is the only use it makes of it.
 *
 * A refusal by Janus and a refusal by the upstream API look the same to a caller reading only the
 * status code, so they are told apart by the media type: Janus answers `application/problem+json`,
 * an upstream API answers whatever it answers.
 */
export async function probeGateway(
  username: string,
  slug: string,
  path: string,
  applicationId: string,
  key: string,
  method = 'GET',
): Promise<ProbeResult> {
  const started = performance.now();
  const target = path.startsWith('/') ? path : `/${path}`;
  let response: Response;
  try {
    response = await fetch(`/${encodeURIComponent(username)}/gateway/${slug}${target}`, {
      method,
      headers: { 'X-Janus-Application-Id': applicationId, 'X-Janus-Api-Key': key },
      // A test request must never be answered from the browser's own store.
      cache: 'no-store',
    });
  } catch {
    return { verdict: 'unreachable', status: 0, millis: Math.round(performance.now() - started) };
  }

  const millis = Math.round(performance.now() - started);
  const correlationId = response.headers.get('X-Janus-Correlation-Id') ?? undefined;
  const cache = response.headers.get('X-Janus-Cache') ?? undefined;
  const fromJanus = (response.headers.get('content-type') ?? '').includes('problem+json');

  if (response.ok || !fromJanus) {
    return { verdict: 'forwarded', status: response.status, correlationId, cache, millis };
  }

  const problem: { detail?: string; title?: string } | null = await response.json().catch(() => null);
  return {
    verdict: 'refused',
    status: response.status,
    detail: problem?.detail ?? problem?.title,
    correlationId,
    millis,
  };
}
