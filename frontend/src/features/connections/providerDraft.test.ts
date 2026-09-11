import { describe, expect, it } from 'vitest';

import type { Provider } from '../../api';
import { contractComplete, draftFrom, NEW_PROVIDER, toProviderInput } from './providerDraft';

/**
 * The API endpoint replaces the whole record, so a form that forgets a field clears it. Opening an API
 * and saving it untouched must send back everything it had.
 */

const base: Provider = {
  id: 'p1',
  name: 'Exchange',
  slug: 'exchange',
  baseUrl: 'https://api.exchange.example',
  enabled: true,
  allowPrivateDestination: false,
  cacheEnabled: false,
  cacheTtlSeconds: 30,
  normalizeJson: true,
  jsonArrayPaths: 'items',
  graphqlPath: '/graphql',
  graphqlMaxDepth: 8,
  graphqlMaxAliases: 20,
  rateLimitPerMinute: 600,
  rateLimitBurst: 50,
  authType: 'HMAC_SIGNATURE',
  headerName: 'CB-ACCESS-KEY',
  signatureAlgorithm: 'HMAC_SHA512',
  signatureTemplate: '{timestamp}{method}{path}{body}',
  signatureEncoding: 'BASE64',
  signatureHeader: 'CB-ACCESS-SIGN',
  timestampHeader: 'CB-ACCESS-TIMESTAMP',
  connectionAuthorizationUrl: 'https://login.exchange.example/authorize',
  connectionTokenUrl: 'https://login.exchange.example/token',
  connectionScopes: 'wallet:read',
  connectionClientAuth: 'POST',
  activated: false,
  createdAt: '2026-01-01T00:00:00Z',
};

describe('provider draft', () => {
  it('sends back the signing recipe, the account connection and the policy untouched', () => {
    const input = toProviderInput(draftFrom(base));

    expect(input).toMatchObject({
      authType: 'HMAC_SIGNATURE',
      headerName: 'CB-ACCESS-KEY',
      signatureAlgorithm: 'HMAC_SHA512',
      signatureTemplate: '{timestamp}{method}{path}{body}',
      signatureEncoding: 'BASE64',
      signatureHeader: 'CB-ACCESS-SIGN',
      signatureParameter: null,
      timestampHeader: 'CB-ACCESS-TIMESTAMP',
      connectionAuthorizationUrl: 'https://login.exchange.example/authorize',
      connectionTokenUrl: 'https://login.exchange.example/token',
      connectionScopes: 'wallet:read',
      connectionClientAuth: 'POST',
      cacheEnabled: false,
      cacheTtlSeconds: 30,
      normalizeJson: true,
      jsonArrayPaths: 'items',
      graphqlPath: '/graphql',
      graphqlMaxDepth: 8,
      graphqlMaxAliases: 20,
      rateLimitPerMinute: 600,
      rateLimitBurst: 50,
    });
    expect(contractComplete(draftFrom(base))).toBe(true);
  });

  it('clears what belongs to another strategy, and a withdrawn connection as a block', () => {
    const draft = { ...draftFrom(base), authType: 'BEARER' as const, connectable: false };
    const input = toProviderInput(draft);

    expect(input.signatureTemplate).toBeNull();
    expect(input.headerName).toBeNull();
    expect(input.connectionAuthorizationUrl).toBeNull();
    expect(input.connectionClientAuth).toBeNull();
  });

  it('refuses a signature placed both in a header and a parameter', () => {
    const draft = draftFrom(base);
    expect(contractComplete({ ...draft, signing: { ...draft.signing, signatureParameter: 'sig' } })).toBe(false);
  });

  it('starts a new API on the backend defaults', () => {
    expect(toProviderInput(NEW_PROVIDER)).toMatchObject({ enabled: true, cacheEnabled: true, graphqlPath: null });
  });
});
