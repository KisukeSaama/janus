import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';

import { api, del, post, put } from './client';
import type {
  Account,
  AccountInput,
  Application,
  ApplicationInput,
  AuditPage,
  Credential,
  CredentialInput,
  Grant,
  GrantInput,
  Identity,
  IssuedApplication,
  NotificationFeed,
  Provider,
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
  providers: ['providers'] as const,
  credentials: ['credentials'] as const,
  grants: ['grants'] as const,
  notifications: ['notifications'] as const,
  traffic: ['traffic'] as const,
  session: ['session'] as const,
  accounts: ['accounts'] as const,
  audit: (page: number, size: number, outcome?: string) => ['audit', page, size, outcome ?? ''] as const,
};

/** Records change when an operator changes them, so a short staleness window is generous already. */
const RECORD_STALE_MS = 30_000;

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
    queryFn: () => api<Provider[]>('/providers'),
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

export function useAudit(page: number, size: number, outcome?: string) {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (outcome) query.set('outcome', outcome);
  return useQuery({
    queryKey: keys.audit(page, size, outcome),
    queryFn: () => api<AuditPage>(`/audit-events?${query}`),
    // The gateway writes to this stream continuously; a page of it is stale as soon as it lands.
    staleTime: 0,
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

/** Anything that touches a record also writes an audit event, so the activity stream follows. */
function invalidate(client: QueryClient, ...touched: readonly (readonly unknown[])[]) {
  for (const key of touched) void client.invalidateQueries({ queryKey: key });
  void client.invalidateQueries({ queryKey: ['audit'] });
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

export function useCreateProvider() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ProviderInput) => post<Provider>('/providers', input),
    onSuccess: () => invalidate(client, keys.providers),
  });
}

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
    // Order matters. Anything still in flight is cancelled first, and the session is answered again
    // straight after the cache is emptied — a console left on screen over an empty cache refetches
    // every page it was showing, against a session that has just ended.
    onSuccess: async () => {
      await client.cancelQueries();
      client.clear();
      client.setQueryData(keys.session, null);
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
