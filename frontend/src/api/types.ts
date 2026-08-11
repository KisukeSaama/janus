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
  | 'OAUTH2_CLIENT_CREDENTIALS';

/** How Janus proves who it is at an upstream token endpoint. Basic is what RFC 6749 requires. */
export type TokenClientAuth = 'BASIC' | 'POST';

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
  /** Whether Janus may reuse a response from this destination at all. */
  cacheEnabled: boolean;
  /** Freshness assumed when the upstream states none. Zero leaves the decision to it. */
  cacheTtlSeconds: number;
  /** Outbound ceiling for this destination, every caller combined. Zero is no ceiling. */
  rateLimitPerMinute: number;
  rateLimitBurst: number;
  authType: AuthType;
  headerName?: string;
  queryParameter?: string;
  tokenUrl?: string;
  tokenScopes?: string;
  tokenClientAuth?: TokenClientAuth;
  /** Whether the signed-in account has provisioned its personal credential for this API. */
  activated: boolean;
  createdAt: string;
  updatedAt?: string;
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
  /** Where client credentials are exchanged, for OAUTH2_CLIENT_CREDENTIALS. */
  tokenUrl?: string;
  tokenScopes?: string;
  tokenClientAuth?: TokenClientAuth;
  /** Where the value lives, never the value. Absent for NONE, which stores none. */
  secretRef?: string;
  enabled: boolean;
  /** When the upstream key stops working. Absent when no date was recorded for it. */
  expiresAt?: string;
  createdAt: string;
  updatedAt?: string;
};

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
  createdAt: string;
  updatedAt?: string;
};

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

export type ProviderInput = {
  name: string;
  slug: string;
  baseUrl: string;
  enabled: boolean;
  cacheEnabled?: boolean;
  cacheTtlSeconds?: number;
  rateLimitPerMinute?: number;
  rateLimitBurst?: number;
  authType: AuthType;
  headerName?: string | null;
  queryParameter?: string | null;
  tokenUrl?: string | null;
  tokenScopes?: string | null;
  tokenClientAuth?: TokenClientAuth | null;
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
};
