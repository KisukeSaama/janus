import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';

import { api, del, download, post, put } from './client';
import type {
  Account,
  AccountInput,
  AgentFile,
  Application,
  ApplicationInput,
  AuditPage,
  Credential,
  CredentialInput,
  Grant,
  GrantInput,
  Identity,
  IssuedApplication,
  McpAuthorization,
  McpConnection,
  McpDecision,
  McpServer,
  NotificationFeed,
  Provider,
  ProviderCapabilities,
  ProviderPage,
  ProviderPing,
  ProviderInput,
  Traffic,
} from './types';

/**
 * Every read and every write the console performs, in one place.
 *
 * The console used to reload all five collections after any change. It now states what a change
 * makes stale, and only that is refetched — with the important subtlety that the API denormalises
 * names: a renamed provider appears inside credentials and grants too, so those are invalidated with
 * it rather than left showing a name that no longer exists.
 */

export const keys = {
  applications: ['applications'] as const,
  /**
   * The whole catalogue, and one page of it. The page key extends the collection key on purpose:
   * invalidating `providers` reaches every catalogue page with it, which is what a renamed or
   * withdrawn API requires — the two are the same records, read two ways.
   */
  providers: ['providers'] as const,
  providerCatalog: (query: string, page: number, size: number) => ['providers', query, page, size] as const,
  // Deliberately outside the `providers` key: an edit to a destination cannot change what the
  // deployment allows, and invalidating the catalogue must not refetch this.
  providerCapabilities: ['provider-capabilities'] as const,
  credentials: ['credentials'] as const,
  grants: ['grants'] as const,
  notifications: ['notifications'] as const,
  traffic: ['traffic'] as const,
  oauthCallback: ['oauth-callback'] as const,
  session: ['session'] as const,
  accounts: ['accounts'] as const,
  /**
   * The coding agent's file, per calling service. The server writes it from the records every other
   * key here names, so any write to those makes every copy of it stale; see `invalidate`.
   */
  agentFile: ['agent-file'] as const,
  agentFileFor: (applicationId?: string) => ['agent-file', applicationId ?? ''] as const,
  mcpServer: ['mcp-server'] as const,
  mcpConnections: ['mcp-connections'] as const,
  mcpAuthorization: (request: string) => ['mcp-authorization', request] as const,
  audit: (page: number, size: number, filter: AuditFilter) =>
    ['audit', page, size, filter.outcome ?? '', filter.from ?? '', filter.to ?? ''] as const,
};

/**
 * What the reader narrowed the journal to. The instants are ISO-8601; `from` is inclusive and `to` is
 * exclusive, so two adjacent ranges count every event once.
 */
export type AuditFilter = { outcome?: string; from?: string; to?: string };

function auditQuery(filter: AuditFilter): URLSearchParams {
  const query = new URLSearchParams();
  if (filter.outcome) query.set('outcome', filter.outcome);
  if (filter.from) query.set('from', filter.from);
  if (filter.to) query.set('to', filter.to);
  return query;
}

/** Records change when an operator changes them, so a short staleness window is generous already. */
const RECORD_STALE_MS = 30_000;

/**
 * The API catalogue is administered, shared by the whole deployment, and read by five screens that
 * only want it to resolve names. Every mutation that can change it invalidates it by name below, so
 * the window here is not what keeps it correct — it is what stops a console left open on a second
 * monitor from re-reading the entire catalogue, a page of a hundred at a time, on every window focus.
 */
const CATALOG_STALE_MS = 300_000;

export function useApplications() {
  return useQuery({
    queryKey: keys.applications,
    queryFn: () => api<Application[]>('/applications'),
    staleTime: RECORD_STALE_MS,
  });
}

export function useProviders() {
  return useQuery({
    queryKey: keys.providers,
    queryFn: async () => {
      const first = await api<ProviderPage>('/providers?size=100');
      if (first.totalPages <= 1) return first.content;
      const rest = await Promise.all(
        Array.from({ length: first.totalPages - 1 }, (_, index) =>
          api<ProviderPage>(`/providers?size=100&page=${index + 1}`),
        ),
      );
      return [first, ...rest].flatMap((page) => page.content);
    },
    staleTime: CATALOG_STALE_MS,
  });
}

/**
 * Answers from configuration and changes only when the deployment restarts, so it is fetched once
 * and never refetched.
 */
export function useProviderCapabilities() {
  return useQuery({
    queryKey: keys.providerCapabilities,
    queryFn: () => api<ProviderCapabilities>('/providers/capabilities'),
    staleTime: Infinity,
  });
}

export function useProviderCatalog(query: string, page: number, size = 20) {
  const params = new URLSearchParams({ q: query, page: String(page), size: String(size) });
  return useQuery({
    queryKey: keys.providerCatalog(query, page, size),
    queryFn: () => api<ProviderPage>(`/providers?${params}`),
    placeholderData: (previous) => previous,
    staleTime: RECORD_STALE_MS,
  });
}

export function useCredentials() {
  return useQuery({
    queryKey: keys.credentials,
    queryFn: () => api<Credential[]>('/credentials'),
    staleTime: RECORD_STALE_MS,
  });
}

export function useGrants() {
  return useQuery({
    queryKey: keys.grants,
    queryFn: () => api<Grant[]>('/grants'),
    staleTime: RECORD_STALE_MS,
  });
}

/**
 * The address providers must be told to send people back to.
 *
 * Asked of the server rather than derived from the browser: the console may be served from another
 * origin entirely, and what has to be registered is where Janus itself answers. It changes only when
 * a deployment is reconfigured, so it is cached for the session.
 */
export function useOAuthCallback() {
  return useQuery({
    queryKey: keys.oauthCallback,
    queryFn: () => api<{ url: string; configured: boolean }>('/oauth/callback'),
    staleTime: Infinity,
  });
}

export function useAudit(page: number, size: number, filter: AuditFilter = {}) {
  const query = auditQuery(filter);
  query.set('page', String(page));
  query.set('size', String(size));
  return useQuery({
    queryKey: keys.audit(page, size, filter),
    queryFn: () => api<AuditPage>(`/audit-events?${query}`),
    // The gateway writes to this stream continuously; a page of it is stale as soon as it lands.
    staleTime: 0,
  });
}

/**
 * The same window as a file. It is a mutation rather than a query because it has an effect the cache
 * has no use for: a download happens once, when asked for, and is never replayed from memory.
 */
export function useAuditExport() {
  return useMutation({
    mutationFn: (filter: AuditFilter) => {
      const day = new Date().toISOString().slice(0, 10);
      return download(`/audit-events/export?${auditQuery(filter)}`, `janus-activity-${day}.csv`);
    },
  });
}

export function useNotifications() {
  return useQuery({
    queryKey: keys.notifications,
    queryFn: () => api<NotificationFeed>('/notifications'),
    staleTime: RECORD_STALE_MS,
  });
}

export function useTraffic() {
  return useQuery({ queryKey: keys.traffic, queryFn: () => api<Traffic>('/gateway/traffic'), staleTime: 0 });
}

/* ── What a change makes stale ─────────────────────────────────────────── */

/**
 * Anything that touches a record also writes an audit event, so the activity stream follows. The
 * coding agent's file follows too: it is a rendering of services, APIs, secrets and grants together,
 * and marking it stale costs nothing while nobody has it on screen.
 */
function invalidate(client: QueryClient, ...touched: readonly (readonly unknown[])[]) {
  for (const key of touched) void client.invalidateQueries({ queryKey: key });
  void client.invalidateQueries({ queryKey: ['audit'] });
  void client.invalidateQueries({ queryKey: keys.agentFile });
}

export function useCreateApplication() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ApplicationInput) => post<IssuedApplication>('/applications', input),
    onSuccess: () => invalidate(client, keys.applications),
  });
}

export function useUpdateApplication() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: ApplicationInput }) =>
      put<Application>(`/applications/${id}`, input),
    // Grants carry the application's name; a rename has to reach them too.
    onSuccess: () => invalidate(client, keys.applications, keys.grants),
  });
}

export function useRotateApplicationKey() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => post<IssuedApplication>(`/applications/${id}/rotate-key`),
    onSuccess: () => invalidate(client, keys.applications),
  });
}

export function useDeleteApplication() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/applications/${id}`),
    onSuccess: () => invalidate(client, keys.applications, keys.grants),
  });
}

// An API is registered by the setup flow, which creates the provider and its credential together.
// Nothing else creates one, so there is no hook for it here.
export function useUpdateProvider() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: ProviderInput }) => put<Provider>(`/providers/${id}`, input),
    onSuccess: () => invalidate(client, keys.providers, keys.credentials, keys.grants, keys.traffic),
  });
}

export function useDeleteProvider() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/providers/${id}`),
    onSuccess: () => invalidate(client, keys.providers, keys.credentials, keys.grants, keys.traffic),
  });
}

/**
 * Asks whether a registered API is answering. A mutation rather than a query: it is a question with
 * an effect — Janus reaches out to somebody else's API — asked when somebody asks it, and an answer
 * from a minute ago is not an answer to it.
 */
export function usePingProvider() {
  return useMutation({
    mutationFn: (id: string) => post<ProviderPing>(`/providers/${id}/ping`),
  });
}

export function usePurgeProviderCache() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api<{ purged: number }>(`/providers/${id}/cache`, { method: 'DELETE' }),
    onSuccess: () => invalidate(client, keys.traffic),
  });
}

export function useCreateCredential() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CredentialInput) => post<Credential>('/credentials', input),
    onSuccess: () => invalidate(client, keys.credentials, keys.notifications),
  });
}

export function useUpdateCredential() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: CredentialInput }) =>
      put<Credential>(`/credentials/${id}`, input),
    // A moved deadline rearms the announcements, so the feed is stale the moment this returns.
    onSuccess: () => invalidate(client, keys.credentials, keys.grants, keys.notifications, keys.traffic),
  });
}

export function useDeleteCredential() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/credentials/${id}`),
    onSuccess: () => invalidate(client, keys.credentials, keys.grants, keys.notifications),
  });
}

export function useCreateGrant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: GrantInput) => post<Grant>('/grants', input),
    onSuccess: () => invalidate(client, keys.grants),
  });
}

export function useUpdateGrant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: GrantInput }) => put<Grant>(`/grants/${id}`, input),
    onSuccess: () => invalidate(client, keys.grants, keys.traffic),
  });
}

export function useDeleteGrant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/grants/${id}`),
    onSuccess: () => invalidate(client, keys.grants, keys.traffic),
  });
}

export function useMarkNotificationsRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => post<NotificationFeed>('/notifications/read'),
    onSuccess: (feed) => client.setQueryData(keys.notifications, feed),
  });
}

export function useDismissNotification() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/notifications/${id}`),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export function usePurgeGatewayCache() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<{ purged: number }>('/gateway/cache', { method: 'DELETE' }),
    onSuccess: () => invalidate(client, keys.traffic),
  });
}

/* ── Who is signed in, and the accounts an administrator manages ────────── */

/**
 * The session, asked for once and then remembered.
 *
 * `retry: false` matters: not being signed in is the ordinary first answer, and repeating the
 * question three times only delays the sign-in screen. The query is also the console's own gate —
 * pending shows nothing, a 401 shows the form, success shows the console.
 */
export function useSession() {
  return useQuery({
    queryKey: keys.session,
    queryFn: () => api<Identity>('/session'),
    retry: false,
    staleTime: Infinity,
  });
}

export function useSignIn() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) => post<Identity>('/session', credentials),
    onSuccess: (identity) => client.setQueryData(keys.session, identity),
  });
}

export function useSignOut() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => del('/session'),
    // Everything fetched under the previous session goes with it: the next person to sign in at this
    // browser must not read what the last one had loaded.
    //
    // Order matters. Anything still in flight is cancelled first, then the active session observer
    // receives the signed-out state before private record caches are discarded.
    onSuccess: async () => {
      await client.cancelQueries();
      client.setQueryData(keys.session, null);
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== keys.session[0] });
    },
  });
}

export function useAccounts() {
  return useQuery({
    queryKey: keys.accounts,
    queryFn: () => api<Account[]>('/accounts'),
    staleTime: RECORD_STALE_MS,
  });
}

export function useCreateAccount() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AccountInput) => post<Account>('/accounts', input),
    onSuccess: () => invalidate(client, keys.accounts),
  });
}

export function useUpdateAccount() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: AccountInput }) => put<Account>(`/accounts/${id}`, input),
    // An edit can be one's own — a changed display name, a new password — so the session is stale too.
    onSuccess: () => invalidate(client, keys.accounts, keys.session),
  });
}

export function useDeleteAccount() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/accounts/${id}`),
    onSuccess: () => invalidate(client, keys.accounts),
  });
}

/** Hands one account's whole registry to another. Everything the console shows is then stale. */
export function useTransferRecords() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ from, to }: { from: string; to: string }) =>
      post<{ services: number; apis: number }>(`/accounts/${from}/transfer?to=${to}`),
    onSuccess: () =>
      invalidate(client, keys.accounts, keys.applications, keys.providers, keys.credentials, keys.grants),
  });
}

/* ── The coding agent's file, and the assistants that act over MCP ──────── */

/**
 * `JANUS.md` for one calling service, or the placeholder file when none is named.
 *
 * Written by the server rather than here, so the file a person downloads and the file an assistant
 * fetches over MCP are the same file, and both carry the address Janus is configured to answer on
 * rather than whichever one this console happened to be opened at. The previous file stays on screen
 * while the next service's loads, so the page does not collapse between two choices.
 */
export function useAgentFile(applicationId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: keys.agentFileFor(applicationId),
    queryFn: () => {
      const query = applicationId ? `?${new URLSearchParams({ applicationId })}` : '';
      return api<AgentFile>(`/agent-file${query}`);
    },
    enabled,
    placeholderData: (previous) => previous,
    staleTime: RECORD_STALE_MS,
  });
}

/** Where the MCP endpoint answers. Configuration, like the OAuth callback: asked once per session. */
export function useMcpServer() {
  return useQuery({
    queryKey: keys.mcpServer,
    queryFn: () => api<McpServer>('/mcp/server'),
    staleTime: Infinity,
  });
}

/**
 * One pending authorization request, read by the consent screen.
 *
 * Asked once and never again. A request is decided exactly once, so a refetch on window focus after
 * the decision went out would come back 404 and flip the screen to "expired" under the reader in the
 * instant before the browser leaves. A 404 is an answer rather than a blip, so nothing is retried.
 */
export function useMcpAuthorization(request: string) {
  return useQuery({
    queryKey: keys.mcpAuthorization(request),
    queryFn: () => api<McpAuthorization>(`/mcp/authorizations/${encodeURIComponent(request)}`),
    enabled: request !== '',
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

/**
 * Approving or refusing a request. Both answer with the address the browser must go to next, because
 * the client is owed an answer either way; following it is the screen's job, not the cache's.
 */
export function useDecideMcpAuthorization() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ request, approve }: { request: string; approve: boolean }) =>
      post<McpDecision>(`/mcp/authorizations/${encodeURIComponent(request)}/${approve ? 'approve' : 'deny'}`),
    onSuccess: (_, { approve }) => {
      if (approve) void client.invalidateQueries({ queryKey: keys.mcpConnections });
    },
  });
}

/** The assistants this account has let in. Its own only: nobody revokes somebody else's. */
export function useMcpConnections() {
  return useQuery({
    queryKey: keys.mcpConnections,
    queryFn: () => api<McpConnection[]>('/mcp/connections'),
    staleTime: RECORD_STALE_MS,
  });
}

export function useRevokeMcpConnection() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => del(`/mcp/connections/${encodeURIComponent(id)}`),
    onSuccess: () => invalidate(client, keys.mcpConnections),
  });
}
