/**
 * The shapes the administration API answers with.
 *
 * Janus is deployed once per environment, so nothing here carries a DEV/PROD discriminator: which
 * environment you are looking at is which address you opened.
 */

export type AuthType =
  | 'NONE'
  | 'BEARER'
  | 'API_KEY_HEADER'
  | 'API_KEY_QUERY'
  | 'BASIC'
  | 'OAUTH2_CLIENT_CREDENTIALS'
  | 'HMAC_SIGNATURE';

/**
 * The account connection an API may offer beside whatever its application identity is. Null
 * throughout when it offers none. Deliberately not another AuthType: the two are set together, which
 * is what lets one API be one entry in the registry.
 */
export type ConnectionFields = {
  /** Where the person signs in and agrees. Its presence is what "offers a connection" means. */
  connectionAuthorizationUrl?: string;
  connectionTokenUrl?: string;
  connectionScopes?: string;
  connectionClientAuth?: TokenClientAuth;
};

/** How Janus proves who it is at an upstream token endpoint. Basic is what RFC 6749 requires. */
export type TokenClientAuth = 'BASIC' | 'POST';

/** The keyed hashes a signed request may use. */
export type SignatureAlgorithm = 'HMAC_SHA256' | 'HMAC_SHA512';

/** How a computed signature is written down. Each API simply decided; neither is more correct. */
export type SignatureEncoding = 'HEX' | 'BASE64';

/**
 * The settings that belong to a signed request, which is the one strategy whose shape differs per
 * API. Sent flat rather than nested, as the rest of the contract is.
 */
export type SignatureFields = {
  signatureAlgorithm?: SignatureAlgorithm;
  /** What gets signed: {method} {path} {query} {body} {timestamp} {timestamp_ms}, and literals. */
  signatureTemplate?: string;
  signatureEncoding?: SignatureEncoding;
  signatureHeader?: string;
  signatureParameter?: string;
  timestampHeader?: string;
  timestampParameter?: string;
};

export type Application = {
  id: string;
  name: string;
  description?: string;
  enabled: boolean;
  /** Browser origins allowed to present this service's tokens. Empty means any, and is the default. */
  allowedOrigins: string[];
  /** When the key currently in the application's hands was issued. Moves on every rotation. */
  apiKeyRotatedAt: string;
  createdAt: string;
  updatedAt?: string;
};

export type Provider = {
  id: string;
  name: string;
  slug: string;
  baseUrl: string;
  enabled: boolean;
  /** Whether this destination lives on the local network. Only offered where the deployment allows it. */
  allowPrivateDestination: boolean;
  /** Whether Janus may reuse a response from this destination at all. */
  cacheEnabled: boolean;
  /** Freshness assumed when the upstream states none. Zero leaves the decision to it. */
  cacheTtlSeconds: number;
  /** Whether callers receive JSON whatever this destination answers in. */
  normalizeJson: boolean;
  /** Elements that must always be arrays once converted, comma-separated. */
  jsonArrayPaths?: string;
  /** Outbound ceiling for this destination, every caller combined. Zero is no ceiling. */
  rateLimitPerMinute: number;
  rateLimitBurst: number;
  authType: AuthType;
  headerName?: string;
  queryParameter?: string;
  tokenUrl?: string;
  tokenScopes?: string;
  tokenClientAuth?: TokenClientAuth;
  /** The header this API wants the client id on, beside the token obtained with it. */
  clientIdHeader?: string;
  /** Whether the signed-in account has provisioned its personal credential for this API. */
  activated: boolean;
  createdAt: string;
  updatedAt?: string;
} & SignatureFields &
  ConnectionFields;

/**
 * Why a probe ended the way it did, as the backend names it. `ANSWERED` is the only one that means
 * the destination is there; the rest each point at a different thing to go and look at.
 */
export type PingReason = 'ANSWERED' | 'TIMED_OUT' | 'UNRESOLVED' | 'TLS_FAILED' | 'BLOCKED' | 'UNREACHABLE';

/**
 * Whether a registered API answered just now. A status of 401 or 404 still counts as reachable: the
 * probe presents no credential and asks for no path, so anything but silence means somebody is home.
 */
export type ProviderPing = {
  reachable: boolean;
  /** What it answered, or 0 when nothing did. */
  status: number;
  millis: number;
  reason: PingReason;
};

export type ProviderPage = {
  content: Provider[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type Credential = {
  id: string;
  name: string;
  providerId: string;
  providerName: string;
  authType: AuthType;
  headerName?: string;
  queryParameter?: string;
  /** Where the application's own credentials are exchanged, for the strategy that exchanges them. */
  tokenUrl?: string;
  tokenScopes?: string;
  tokenClientAuth?: TokenClientAuth;
  clientIdHeader?: string;
  /** Whether the connection still needs an OAuth client of its own before anyone can be asked. */
  connectionAwaitingSecret?: boolean;
  /** Where the value lives, never the value. Absent for NONE, which stores none. */
  secretRef?: string;
  enabled: boolean;
  /**
   * Whether somebody still has to agree at the provider before this can be used. The one state that
   * turns a row into a button: it is fixed by a person, not by an edit.
   */
  awaitingAuthorization: boolean;
  /** When consent was last given, and whom the provider says it belongs to. */
  authorizedAt?: string;
  authorizedSubject?: string;
  /** When the upstream key stops working. Absent when no date was recorded for it. */
  expiresAt?: string;
  createdAt: string;
  updatedAt?: string;
} & SignatureFields &
  ConnectionFields;

export type Grant = {
  id: string;
  applicationId: string;
  applicationName: string;
  providerId: string;
  providerName: string;
  credentialId: string;
  credentialName: string;
  enabled: boolean;
  /** What this application may ask of this provider per minute. Zero is no ceiling. */
  rateLimitPerMinute: number;
  rateLimitBurst: number;
  /** The path this grant admits calls under. Absent is the whole destination. */
  pathPrefix?: string;
  /** The methods it admits. Empty is all of them. */
  methods: HttpMethod[];
  /** Whether it may speak for the connected account, rather than only as the service itself. */
  allowAccountIdentity: boolean;
  createdAt: string;
  updatedAt?: string;
};

/** The methods the gateway forwards, in the order the console offers them. */
export const HTTP_METHODS = ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

export type HttpMethod = (typeof HTTP_METHODS)[number];

export type Audit = {
  id: string;
  occurredAt: string;
  actorType: string;
  actorId?: string;
  action: string;
  outcome: string;
  requestMethod?: string;
  requestPath?: string;
  statusCode?: number;
  detail?: string;
  correlationId: string;
};

export type AuditPage = {
  content: Audit[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

/** What the gateway is currently holding on the callers' behalf, and who is refusing traffic. */
export type Traffic = {
  cache: {
    enabled: boolean;
    entries: number;
    bytes: number;
    maxEntries: number;
    maxBytes: number;
    stores: number;
    evictions: number;
    outcomes: Record<string, number>;
    hitRatio: number;
  };
  /** The registry reads behind authorisation, and how often one was answered from memory. */
  authorization: {
    enabled: boolean;
    providers: number;
    grants: number;
    hitRatio: number;
  };
  cooldowns: {
    providerId: string;
    providerName?: string;
    providerSlug?: string;
    until: string;
    status: number;
  }[];
};

/** Stages as the backend names them, in escalating order. */
export type ExpiryStage = 'NOTICE' | 'WARNING' | 'EXPIRED';

/**
 * One announcement raised by the daily expiry sweep. It carries the stage and the deadline rather
 * than a countdown, so a console left open overnight cannot keep claiming seven days when there are
 * six: the days are worked out where they are drawn.
 */
export type Notification = {
  id: string;
  stage: ExpiryStage;
  /** INFO, WARN, CRITICAL — the backend's own reading of the stage. */
  severity: string;
  credentialId: string;
  credentialName: string;
  providerName: string;
  expiresAt: string;
  createdAt: string;
  readAt?: string;
};

/** The feed carries its own unread count, so a badge costs no second request. */
export type NotificationFeed = { items: Notification[]; unread: number };

/** The one response that ever contains an API key, on registration and on rotation. */
export type IssuedApplication = { application: Application; apiKey: string };

/* ── What the console sends ────────────────────────────────────────────── */

export type ApplicationInput = {
  name: string;
  description?: string | null;
  enabled: boolean;
  /** Stated in full on every write, like route rules: the list is the statement, not a patch of it. */
  allowedOrigins: string[];
};

/**
 * What this deployment lets a destination be. Fixed at startup, so the console reads it once and
 * hides whatever is not on offer rather than letting somebody submit what the backend refuses.
 */
export type ProviderCapabilities = {
  privateDestinations: boolean;
};

export type ProviderInput = {
  name: string;
  slug: string;
  baseUrl: string;
  enabled: boolean;
  allowPrivateDestination?: boolean;
  cacheEnabled?: boolean;
  cacheTtlSeconds?: number;
  normalizeJson?: boolean;
  jsonArrayPaths?: string | null;
  rateLimitPerMinute?: number;
  rateLimitBurst?: number;
  authType: AuthType;
  headerName?: string | null;
  queryParameter?: string | null;
  tokenUrl?: string | null;
  tokenScopes?: string | null;
  tokenClientAuth?: TokenClientAuth | null;
  clientIdHeader?: string | null;
  /** The account connection, or null throughout when the API offers none. */
  connectionAuthorizationUrl?: string | null;
  connectionTokenUrl?: string | null;
  connectionScopes?: string | null;
  connectionClientAuth?: TokenClientAuth | null;
};

export type CredentialInput = {
  name: string;
  providerId: string;
  authType: AuthType;
  headerName?: string | null;
  queryParameter?: string | null;
  tokenUrl?: string | null;
  tokenScopes?: string | null;
  tokenClientAuth?: TokenClientAuth | null;
  clientIdHeader?: string | null;
  /**
   * The OAuth client the account connection exchanges with, when the API does not already store one
   * for the application itself. Left out whenever the two are the same client.
   */
  connectionSecret?: string | null;
  /** The signing recipe, repeated from the API that states it. Null on every other strategy. */
  signatureAlgorithm?: SignatureAlgorithm | null;
  signatureTemplate?: string | null;
  signatureEncoding?: SignatureEncoding | null;
  signatureHeader?: string | null;
  signatureParameter?: string | null;
  timestampHeader?: string | null;
  timestampParameter?: string | null;
  /** Sent on create, and on update only to replace what OpenBao already holds. */
  secret?: string | null;
  expiresAt?: string | null;
  enabled: boolean;
};

export type GrantInput = {
  applicationId: string;
  providerId: string;
  credentialId: string;
  enabled: boolean;
  rateLimitPerMinute?: number;
  rateLimitBurst?: number;
  /** Empty on both counts is the whole destination, which is what a grant has always meant. */
  pathPrefix?: string | null;
  methods?: HttpMethod[];
  /** Absent is yes, which is what every grant written before the question existed already does. */
  allowAccountIdentity?: boolean;
};

/* ── Who may sign in ────────────────────────────────────────────────────── */

/**
 * Every role owns personal applications and credentials. Administrators also manage the shared API catalogue.
 */
export type AccountRole = 'SUPER_ADMIN' | 'ADMIN' | 'USER';

/** The signed-in person, as the console needs to know them. */
export type Identity = { id: string; username: string; displayName: string; role: AccountRole };

export type Account = {
  id: string;
  username: string;
  displayName: string;
  email: string;
  role: AccountRole;
  enabled: boolean;
  passwordChangedAt: string;
  /** Absent until they have signed in once. */
  lastSignedInAt?: string;
  createdAt: string;
  updatedAt?: string;
};

export type AccountInput = {
  /** Set once, at creation: it names the actor on every journal entry already written. */
  username: string;
  displayName: string;
  email: string;
  role: AccountRole;
  enabled: boolean;
  /** Blank on an update means "leave the current password alone". */
  password?: string | null;
  /** Sent only when somebody changes their own password, which is the one case that has to prove it. */
  currentPassword?: string | null;
};
