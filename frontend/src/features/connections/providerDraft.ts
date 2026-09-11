import type {
  AuthType,
  Provider,
  ProviderInput,
  SignatureAlgorithm,
  SignatureEncoding,
  TokenClientAuth,
} from '../../api';

/**
 * The one description of an API, whichever screen writes it.
 *
 * The registration flow, the catalogue's "Edit API" panel and the connection page used to each keep
 * their own copy of these fields, and they drifted: one could not sign requests, another could not
 * reach the traffic policy, and the third wiped the account connection on every save because the
 * backend replaces the whole record. Every screen now holds the same draft and sends the same input.
 */

export type Signing = {
  algorithm: SignatureAlgorithm;
  template: string;
  encoding: SignatureEncoding;
  signatureHeader: string;
  signatureParameter: string;
  timestampHeader: string;
  timestampParameter: string;
};

export type ProviderDraft = {
  name: string;
  slug: string;
  baseUrl: string;
  enabled: boolean;
  allowPrivateDestination: boolean;
  authType: AuthType;
  headerName: string;
  queryParameter: string;
  tokenUrl: string;
  tokenScopes: string;
  tokenClientAuth: TokenClientAuth;
  clientIdHeader: string;
  signing: Signing;
  /** Whether this API also lets an account holder connect theirs, beside whatever it presents. */
  connectable: boolean;
  connectionAuthorizationUrl: string;
  connectionTokenUrl: string;
  connectionScopes: string;
  connectionClientAuth: TokenClientAuth;
  cacheEnabled: boolean;
  cacheTtlSeconds: number;
  normalizeJson: boolean;
  jsonArrayPaths: string;
  graphql: boolean;
  graphqlPath: string;
  graphqlMaxDepth: number;
  graphqlMaxAliases: number;
  rateLimitPerMinute: number;
  rateLimitBurst: number;
};

export type SetDraft = (patch: Partial<ProviderDraft>) => void;

/** What a new API starts as: the backend's own defaults, stated rather than left implicit. */
export const NEW_PROVIDER: ProviderDraft = {
  name: '',
  slug: '',
  baseUrl: '',
  enabled: true,
  allowPrivateDestination: false,
  authType: 'BEARER',
  headerName: 'X-Api-Key',
  queryParameter: 'api_key',
  tokenUrl: '',
  tokenScopes: '',
  // Basic is what the spec requires of every server, so it is the default.
  tokenClientAuth: 'BASIC',
  clientIdHeader: '',
  signing: {
    algorithm: 'HMAC_SHA256',
    template: '',
    encoding: 'HEX',
    signatureHeader: '',
    signatureParameter: '',
    timestampHeader: '',
    timestampParameter: '',
  },
  connectable: false,
  connectionAuthorizationUrl: '',
  connectionTokenUrl: '',
  connectionScopes: '',
  connectionClientAuth: 'BASIC',
  cacheEnabled: true,
  cacheTtlSeconds: 0,
  normalizeJson: false,
  jsonArrayPaths: '',
  graphql: false,
  graphqlPath: '/graphql',
  graphqlMaxDepth: 0,
  graphqlMaxAliases: 0,
  rateLimitPerMinute: 0,
  rateLimitBurst: 0,
};

export function draftFrom(provider: Provider): ProviderDraft {
  return {
    name: provider.name,
    slug: provider.slug,
    baseUrl: provider.baseUrl,
    enabled: provider.enabled,
    allowPrivateDestination: provider.allowPrivateDestination,
    authType: provider.authType,
    // A signed API's key header is optional, so an absent one stays absent rather than defaulted.
    headerName:
      provider.headerName ?? (provider.authType === 'HMAC_SIGNATURE' ? '' : NEW_PROVIDER.headerName),
    queryParameter: provider.queryParameter ?? NEW_PROVIDER.queryParameter,
    tokenUrl: provider.tokenUrl ?? '',
    tokenScopes: provider.tokenScopes ?? '',
    tokenClientAuth: provider.tokenClientAuth ?? 'BASIC',
    clientIdHeader: provider.clientIdHeader ?? '',
    signing: {
      algorithm: provider.signatureAlgorithm ?? 'HMAC_SHA256',
      template: provider.signatureTemplate ?? '',
      encoding: provider.signatureEncoding ?? 'HEX',
      signatureHeader: provider.signatureHeader ?? '',
      signatureParameter: provider.signatureParameter ?? '',
      timestampHeader: provider.timestampHeader ?? '',
      timestampParameter: provider.timestampParameter ?? '',
    },
    connectable: Boolean(provider.connectionAuthorizationUrl),
    connectionAuthorizationUrl: provider.connectionAuthorizationUrl ?? '',
    connectionTokenUrl: provider.connectionTokenUrl ?? '',
    connectionScopes: provider.connectionScopes ?? '',
    connectionClientAuth: provider.connectionClientAuth ?? 'BASIC',
    cacheEnabled: provider.cacheEnabled ?? true,
    cacheTtlSeconds: provider.cacheTtlSeconds ?? 0,
    normalizeJson: provider.normalizeJson ?? false,
    jsonArrayPaths: provider.jsonArrayPaths ?? '',
    graphql: Boolean(provider.graphqlPath),
    graphqlPath: provider.graphqlPath ?? '/graphql',
    graphqlMaxDepth: provider.graphqlMaxDepth ?? 0,
    graphqlMaxAliases: provider.graphqlMaxAliases ?? 0,
    rateLimitPerMinute: provider.rateLimitPerMinute ?? 0,
    rateLimitBurst: provider.rateLimitBurst ?? 0,
  };
}

const orNull = (value: string) => value.trim() || null;

/**
 * The authentication contract, in the shape both the API and a credential take it. Fields that belong
 * to another strategy are sent as null, which is what the backend clears them with.
 */
export function contractOf(draft: ProviderDraft) {
  const { authType, signing } = draft;
  const signs = authType === 'HMAC_SIGNATURE';
  const exchanges = authType === 'OAUTH2_CLIENT_CREDENTIALS';
  return {
    authType,
    headerName: authType === 'API_KEY_HEADER' || signs ? orNull(draft.headerName) : null,
    queryParameter: authType === 'API_KEY_QUERY' ? orNull(draft.queryParameter) : null,
    tokenUrl: exchanges ? orNull(draft.tokenUrl) : null,
    tokenScopes: exchanges ? orNull(draft.tokenScopes) : null,
    tokenClientAuth: exchanges ? draft.tokenClientAuth : null,
    clientIdHeader: exchanges ? orNull(draft.clientIdHeader) : null,
    signatureAlgorithm: signs ? signing.algorithm : null,
    signatureTemplate: signs ? orNull(signing.template) : null,
    signatureEncoding: signs ? signing.encoding : null,
    signatureHeader: signs ? orNull(signing.signatureHeader) : null,
    signatureParameter: signs ? orNull(signing.signatureParameter) : null,
    timestampHeader: signs ? orNull(signing.timestampHeader) : null,
    timestampParameter: signs ? orNull(signing.timestampParameter) : null,
  };
}

/**
 * The whole record. Everything is sent on every save, because the endpoint replaces rather than
 * patches: a field left out is a field cleared.
 */
export function toProviderInput(draft: ProviderDraft): ProviderInput {
  const { connectable, graphql } = draft;
  return {
    name: draft.name.trim(),
    slug: draft.slug.trim(),
    baseUrl: draft.baseUrl.trim(),
    enabled: draft.enabled,
    allowPrivateDestination: draft.allowPrivateDestination,
    ...contractOf(draft),
    // Cleared as a block when the box is unticked, so withdrawing a connection is one gesture rather
    // than three emptied fields the backend would refuse as half a flow.
    connectionAuthorizationUrl: connectable ? orNull(draft.connectionAuthorizationUrl) : null,
    connectionTokenUrl: connectable ? orNull(draft.connectionTokenUrl) : null,
    connectionScopes: connectable ? orNull(draft.connectionScopes) : null,
    connectionClientAuth: connectable ? draft.connectionClientAuth : null,
    cacheEnabled: draft.cacheEnabled,
    cacheTtlSeconds: draft.cacheTtlSeconds,
    normalizeJson: draft.normalizeJson,
    // The array declaration only means anything while normalisation is on.
    jsonArrayPaths: draft.normalizeJson ? orNull(draft.jsonArrayPaths) : null,
    graphqlPath: graphql ? orNull(draft.graphqlPath) : null,
    graphqlMaxDepth: graphql ? draft.graphqlMaxDepth : 0,
    graphqlMaxAliases: graphql ? draft.graphqlMaxAliases : 0,
    rateLimitPerMinute: draft.rateLimitPerMinute,
    rateLimitBurst: draft.rateLimitBurst,
  };
}

/** Whether the destination is enough to go on with. The slug is checked by whoever renders it. */
export function destinationComplete(draft: ProviderDraft) {
  return draft.name.trim() !== '' && draft.slug.trim().length >= 3 && draft.baseUrl.trim() !== '';
}

/** Whether the contract, the connection offer and the policy would be accepted as they stand. */
export function contractComplete(draft: ProviderDraft) {
  const { authType, signing } = draft;
  return (
    (authType !== 'API_KEY_HEADER' || draft.headerName.trim() !== '') &&
    (authType !== 'API_KEY_QUERY' || draft.queryParameter.trim() !== '') &&
    (authType !== 'OAUTH2_CLIENT_CREDENTIALS' || draft.tokenUrl.trim() !== '') &&
    (!draft.connectable ||
      (draft.connectionAuthorizationUrl.trim() !== '' && draft.connectionTokenUrl.trim() !== '')) &&
    // A signature travels in exactly one place, which is the one rule a reader can get wrong here.
    (authType !== 'HMAC_SIGNATURE' ||
      (signing.template.trim() !== '' &&
        (signing.signatureHeader.trim() !== '') !== (signing.signatureParameter.trim() !== ''))) &&
    (!draft.graphql || draft.graphqlPath.trim() !== '')
  );
}
