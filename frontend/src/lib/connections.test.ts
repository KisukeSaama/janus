import { describe, expect, it } from 'vitest';
import type { Application, Credential, Grant, Provider } from '../api';
import { absolutePath, buildConnections, curlFor, gatewayUrl, sortConnections, toSlug } from './connections';

const app = (over: Partial<Application> = {}): Application =>
  ({ id: 'app-1', name: 'checkout', enabled: true, apiKeyRotatedAt: new Date().toISOString(), ...over }) as Application;

const provider = (over: Partial<Provider> = {}): Provider =>
  ({ id: 'prov-1', name: 'Spotify', slug: 'spotify', enabled: true, ...over }) as Provider;

const credential = (over: Partial<Credential> = {}): Credential =>
  ({ id: 'cred-1', name: 'key', enabled: true, ...over }) as Credential;

const grant = (over: Partial<Grant> = {}): Grant =>
  ({
    id: 'grant-1',
    applicationId: 'app-1',
    providerId: 'prov-1',
    credentialId: 'cred-1',
    applicationName: 'checkout',
    providerName: 'Spotify',
    enabled: true,
    ...over,
  }) as Grant;

const build = (g: Grant, a = app(), p = provider(), c = credential()) => buildConnections([g], [a], [p], [c])[0];

describe('gatewayUrl', () => {
  it('uses the Janus origin and owner namespace, never the upstream API address', () => {
    expect(gatewayUrl('kisukesaama', 'tmdb', 'https://janus.kisukesaama.com')).toBe(
      'https://janus.kisukesaama.com/kisukesaama/gateway/tmdb',
    );
  });
});

describe('buildConnections', () => {
  it('assembles one statement out of the four records behind it', () => {
    const connection = build(grant());

    expect(connection.id).toBe('grant-1');
    expect(connection.live).toBe(true);
    expect(connection.blockedBy).toBeUndefined();
    expect(connection.gatewayPath).toBe('/gateway/spotify');
  });

  /**
   * The state four separate tables cannot show: the grant reads active and the call still fails,
   * because something further down was switched off.
   */
  it('reports a connection as not live when any record behind it is disabled', () => {
    expect(build(grant({ enabled: false })).live).toBe(false);
    expect(build(grant(), app({ enabled: false })).live).toBe(false);
    expect(build(grant(), app(), provider({ enabled: false })).live).toBe(false);
    expect(build(grant(), app(), provider(), credential({ enabled: false })).live).toBe(false);
  });

  /** Named in the order the gateway checks them, so the record named is the one that refuses first. */
  it('names the record that would refuse the call first', () => {
    expect(build(grant({ enabled: false }), app({ enabled: false })).blockedBy).toBe('grant');
    expect(build(grant(), app({ enabled: false }), provider({ enabled: false })).blockedBy).toBe('application');
    expect(build(grant(), app(), provider({ enabled: false }), credential({ enabled: false }))).toMatchObject({
      blockedBy: 'provider',
    });
    expect(build(grant(), app(), provider(), credential({ enabled: false })).blockedBy).toBe('credential');
  });

  /** A grant pointing at a record the reader cannot see must still render rather than crash. */
  it('survives a record it cannot resolve', () => {
    const connection = buildConnections([grant({ providerId: 'gone' })], [app()], [provider()], [credential()])[0];

    expect(connection.provider).toBeUndefined();
    expect(connection.gatewayPath).toBe('');
    expect(connection.live).toBe(true);
  });
});

describe('sortConnections', () => {
  it('orders by caller, then by destination', () => {
    const rows = buildConnections(
      [
        grant({ id: 'b', applicationName: 'zulu', providerName: 'Alpha' }),
        grant({ id: 'c', applicationName: 'alpha', providerName: 'Zulu' }),
        grant({ id: 'a', applicationName: 'alpha', providerName: 'Alpha' }),
      ],
      [app()],
      [provider()],
      [credential()],
    );

    expect(sortConnections(rows).map((row) => row.id)).toEqual(['a', 'c', 'b']);
  });

  it('leaves the list it was given alone', () => {
    const rows = buildConnections([grant({ applicationName: 'zulu' }), grant({ applicationName: 'alpha' })], [], [], []);
    const before = rows.map((row) => row.grant.applicationName);

    sortConnections(rows);

    expect(rows.map((row) => row.grant.applicationName)).toEqual(before);
  });
});

describe('toSlug', () => {
  it('turns a name into something the slug rule accepts', () => {
    expect(toSlug('Payments API v2')).toBe('payments-api-v2');
    expect(toSlug('Crédit Agricole')).toBe('credit-agricole');
    expect(toSlug('  spaced  out  ')).toBe('spaced-out');
    expect(toSlug('a//b__c')).toBe('a-b-c');
  });

  /** Trimmed after the cut: a truncation landing on a hyphen would produce a slug the server refuses. */
  it('never ends on a hyphen, even when the cut lands on one', () => {
    const slug = toSlug(`${'a'.repeat(59)} tail`);

    expect(slug.length).toBeLessThanOrEqual(60);
    expect(slug.endsWith('-')).toBe(false);
    expect(slug.startsWith('-')).toBe(false);
  });

  it('gives nothing back for a name with nothing usable in it', () => {
    expect(toSlug('!!!')).toBe('');
  });
});

describe('absolutePath', () => {
  // Refusing an entry over a missing slash teaches nothing.
  it('accepts a path written without its leading slash', () => {
    expect(absolutePath('v1/orders')).toBe('/v1/orders');
    expect(absolutePath('  v1/orders  ')).toBe('/v1/orders');
    expect(absolutePath('/v1/orders')).toBe('/v1/orders');
  });

  it('gives nothing back for an empty entry', () => {
    expect(absolutePath('   ')).toBe('');
  });
});

describe('curlFor', () => {
  it('writes a command carrying exactly the two headers the gateway expects', () => {
    const command = curlFor('kisukesaama', 'spotify', 'v1/tracks', 'app-1', 'jns_secret');

    expect(command).toContain('/kisukesaama/gateway/spotify/v1/tracks');
    expect(command).toContain('X-Janus-Application-Id: app-1');
    expect(command).toContain('X-Janus-Api-Key: jns_secret');
  });
});
