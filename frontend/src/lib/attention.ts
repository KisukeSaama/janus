import type { Application, Credential } from '../api';
import type { Connection } from './connections';

/**
 * The console's opinion about what needs doing, computed from data the operator already has.
 *
 * An administrator opens Janus to answer one question: is anything wrong? Counting objects does not
 * answer it. These checks do, and each one names a record you can act on.
 */

/** Ninety days is the common rotation cadence for machine credentials. */
export const KEY_MAX_AGE_DAYS = 90;

const DAY = 86_400_000;

/**
 * Deadlines are not here. `Coming due` on the dashboard names each secret, its API and its date,
 * which is the same finding said properly; counting them a second line above it was the one thing
 * the section could not add.
 */
export type AttentionKind =
  | 'stalledConnections'
  | 'staleKeys'
  | 'idleApplications'
  | 'idleCredentials'
  | 'idleActivations';

/** Where the console sends an operator to act on the finding. */
export type AttentionTarget = 'connections' | 'applications' | 'credentials';

export type Attention = {
  kind: AttentionKind;
  severity: 'warn' | 'info';
  target: AttentionTarget;
  /** The records concerned, so the view can name them rather than only count them. */
  names: string[];
  /**
   * The same records, addressable. A finding that can only be counted sends the reader back to a
   * full list to find the three rows it was about; carrying the identifiers lets the destination
   * open on exactly those.
   */
  ids: string[];
};

export const isKeyStale = (application: Application, now = Date.now()) =>
  now - new Date(application.apiKeyRotatedAt).getTime() > KEY_MAX_AGE_DAYS * DAY;

const label = (connection: Connection) =>
  `${connection.grant.applicationName} → ${connection.grant.providerName}`;

export function assess(
  {
    apps,
    credentials,
    connections,
  }: {
    apps: Application[];
    credentials: Credential[];
    connections: Connection[];
  },
  now = Date.now(),
): Attention[] {
  const live = connections.filter((c) => c.live);
  const reachedApps = new Set(live.map((c) => c.grant.applicationId));
  const usedCredentials = new Set(live.map((c) => c.grant.credentialId));

  const stalled = connections.filter((c) => c.grant.enabled && !c.live);
  const stale = apps.filter((a) => a.enabled && isKeyStale(a, now));
  const idleApps = apps.filter((a) => a.enabled && !reachedApps.has(a.id));
  const unused = credentials.filter((c) => c.enabled && !usedCredentials.has(c.id));
  // An open API stores no value, so "delete it to remove the secret" names something that does not
  // exist. What is left to do about it is switch the activation off, which is a different sentence.
  const idleCredentials = unused.filter((c) => c.authType !== 'NONE');
  const idleActivations = unused.filter((c) => c.authType === 'NONE');

  const found: Attention[] = [
    {
      // The state four separate tables cannot show: the grant reads active, and the call still fails
      // because something further down the chain was switched off.
      kind: 'stalledConnections',
      severity: 'warn',
      target: 'connections',
      names: stalled.map(label),
      ids: stalled.map((c) => c.id),
    },
    {
      kind: 'staleKeys',
      severity: 'warn',
      target: 'applications',
      names: stale.map((a) => a.name),
      ids: stale.map((a) => a.id),
    },
    {
      // A key in circulation that reaches nothing is a credential with no purpose and a real cost.
      kind: 'idleApplications',
      severity: 'info',
      target: 'applications',
      names: idleApps.map((a) => a.name),
      ids: idleApps.map((a) => a.id),
    },
    {
      kind: 'idleCredentials',
      severity: 'info',
      target: 'credentials',
      names: idleCredentials.map((c) => c.name),
      ids: idleCredentials.map((c) => c.id),
    },
    {
      kind: 'idleActivations',
      severity: 'info',
      target: 'credentials',
      names: idleActivations.map((c) => c.name),
      ids: idleActivations.map((c) => c.id),
    },
  ];

  return found.filter((item) => item.names.length > 0);
}
