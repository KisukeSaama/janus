import { describe, expect, it } from 'vitest';
import type { Application, Credential } from '../api';
import { assess, isKeyStale, KEY_MAX_AGE_DAYS } from './attention';
import { buildConnections, type Connection } from './connections';

const DAY = 86_400_000;
const NOW = new Date('2026-06-15T12:00:00Z').getTime();
const daysAgo = (days: number) => new Date(NOW - days * DAY).toISOString();
const inDays = (days: number) => new Date(NOW + days * DAY).toISOString();

const app = (over: Partial<Application> = {}): Application =>
  ({ id: 'app-1', name: 'checkout', enabled: true, apiKeyRotatedAt: daysAgo(1), ...over }) as Application;

const credential = (over: Partial<Credential> = {}): Credential =>
  ({ id: 'cred-1', name: 'spotify key', enabled: true, ...over }) as Credential;

/** A live connection between the given application and credential, built the way the console does. */
function connection(over: { applicationId?: string; credentialId?: string; enabled?: boolean } = {}): Connection {
  const applicationId = over.applicationId ?? 'app-1';
  const credentialId = over.credentialId ?? 'cred-1';
  const enabled = over.enabled ?? true;
  return buildConnections(
    [
      {
        id: `grant-${applicationId}-${credentialId}`,
        applicationId,
        providerId: 'prov-1',
        credentialId,
        applicationName: 'checkout',
        providerName: 'Spotify',
        enabled,
      } as never,
    ],
    [app({ id: applicationId })],
    [{ id: 'prov-1', name: 'Spotify', slug: 'spotify', enabled: true } as never],
    [credential({ id: credentialId })],
  )[0];
}

const kinds = (findings: ReturnType<typeof assess>) => findings.map((f) => f.kind);

describe('assess', () => {
  /** An administrator opens Janus to answer one question: is anything wrong? */
  it('says nothing when there is nothing to act on', () => {
    const findings = assess({ apps: [app()], credentials: [credential()], connections: [connection()] }, NOW);

    expect(findings).toEqual([]);
  });

  /**
   * The state four separate tables cannot show: the grant reads active, and the call fails anyway
   * because something further down was switched off.
   */
  it('raises a connection that reads active but would not carry a call', () => {
    const stalled = connection();
    stalled.live = false;
    stalled.blockedBy = 'provider';

    const findings = assess({ apps: [app()], credentials: [credential()], connections: [stalled] }, NOW);

    expect(kinds(findings)).toContain('stalledConnections');
    expect(findings[0].names).toEqual(['checkout → Spotify']);
    expect(findings[0].severity).toBe('warn');
  });

  /** `Coming due` on the dashboard names each secret and its date; a count above it said less. */
  it('leaves the recorded deadlines to the section that states them', () => {
    const findings = assess(
      { apps: [app()], credentials: [credential({ expiresAt: inDays(3) })], connections: [connection()] },
      NOW,
    );

    expect(findings).toEqual([]);
  });

  it('raises a key that has been in circulation past the rotation cadence', () => {
    const findings = assess(
      { apps: [app({ apiKeyRotatedAt: daysAgo(KEY_MAX_AGE_DAYS + 1) })], credentials: [credential()], connections: [connection()] },
      NOW,
    );

    expect(kinds(findings)).toContain('staleKeys');
  });

  /** A key in circulation that reaches nothing is a credential with no purpose and a real cost. */
  it('raises a service that reaches nothing and a secret nothing uses', () => {
    const findings = assess({ apps: [app()], credentials: [credential()], connections: [] }, NOW);

    expect(kinds(findings)).toEqual(expect.arrayContaining(['idleApplications', 'idleCredentials']));
    expect(findings.every((f) => f.severity === 'info')).toBe(true);
  });

  /**
   * An open API stores nothing, so "delete it to remove the value from OpenBao" names a value that
   * was never written. The finding it belongs to asks for the activation to be switched off instead.
   */
  it('tells an unused open API apart from an unused secret', () => {
    const findings = assess(
      { apps: [], credentials: [credential({ authType: 'NONE', name: 'pokeapi-v2-open' })], connections: [] },
      NOW,
    );

    expect(kinds(findings)).toEqual(['idleActivations']);
    expect(findings[0].names).toEqual(['pokeapi-v2-open']);
    expect(findings[0].target).toBe('credentials');
  });

  /** A connection that is switched off does not count as reaching anything. */
  it('counts only live connections as reaching a service', () => {
    const stalled = connection();
    stalled.live = false;

    const findings = assess({ apps: [app()], credentials: [credential()], connections: [stalled] }, NOW);

    expect(kinds(findings)).toContain('idleApplications');
  });

  /** A record somebody deliberately switched off is not a finding; it is a decision. */
  it('says nothing about records that are already disabled', () => {
    const findings = assess(
      {
        apps: [app({ enabled: false, apiKeyRotatedAt: daysAgo(KEY_MAX_AGE_DAYS + 10) })],
        credentials: [credential({ enabled: false, expiresAt: inDays(1) })],
        connections: [],
      },
      NOW,
    );

    expect(findings).toEqual([]);
  });

  /** A finding that can only be counted sends the reader back to a full list to find its three rows. */
  it('carries the records concerned so the destination can open on exactly those', () => {
    const findings = assess(
      { apps: [app({ id: 'app-9', name: 'orphan' })], credentials: [], connections: [] },
      NOW,
    );

    expect(findings[0].ids).toEqual(['app-9']);
    expect(findings[0].names).toEqual(['orphan']);
    expect(findings[0].target).toBe('applications');
  });

  it('reports every finding that applies rather than only the first', () => {
    const findings = assess(
      {
        apps: [app({ apiKeyRotatedAt: daysAgo(KEY_MAX_AGE_DAYS + 1) })],
        credentials: [credential({ expiresAt: inDays(2) })],
        connections: [],
      },
      NOW,
    );

    expect(kinds(findings)).toEqual(
      expect.arrayContaining(['staleKeys', 'idleApplications', 'idleCredentials']),
    );
  });
});

describe('isKeyStale', () => {
  it('turns stale on the far side of the rotation cadence and not before', () => {
    expect(isKeyStale(app({ apiKeyRotatedAt: daysAgo(KEY_MAX_AGE_DAYS - 1) }), NOW)).toBe(false);
    expect(isKeyStale(app({ apiKeyRotatedAt: daysAgo(KEY_MAX_AGE_DAYS + 1) }), NOW)).toBe(true);
  });
});
