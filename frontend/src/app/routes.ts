import { useCallback, useSyncExternalStore } from 'react';

import type { MessageKey } from '../i18n';

/**
 * Where you are, in the address bar.
 *
 * The console used to hold its position in component state, which meant a page could not be linked
 * to, reloaded, or reached with the back button. Seven destinations do not justify a routing library,
 * so this is the History API with a parser: one place that knows the shape of every URL.
 */

export type Page =
  | 'dashboard'
  | 'activity'
  | 'applications'
  | 'credentials'
  | 'accounts'
  | 'documentation'
  | 'agents';

/** The dashboard carries an optional connection: the record it lists, opened from its list. */
export type Location =
  | { page: Exclude<Page, 'dashboard'> }
  | { page: 'dashboard'; id?: string };

const PATHS: Record<Page, string> = {
  dashboard: '/dashboard',
  activity: '/activity',
  applications: '/registry/applications',
  credentials: '/registry/credentials',
  accounts: '/accounts',
  documentation: '/documentation',
  agents: '/documentation/ai-coding',
};

/** The title a page prints, and the group it belongs to. Both live here so no view invents its own. */
export const PAGE_TITLE: Record<Page, MessageKey> = {
  dashboard: 'dashboard.title',
  activity: 'activity.title',
  applications: 'applications.title',
  credentials: 'credentials.title',
  accounts: 'accounts.title',
  documentation: 'docs.title',
  agents: 'agents.title',
};

export const PAGE_SECTION: Record<Page, MessageKey> = {
  dashboard: 'nav.console',
  activity: 'nav.console',
  applications: 'nav.registry',
  credentials: 'nav.registry',
  accounts: 'nav.administration',
  documentation: 'nav.reference',
  agents: 'nav.reference',
};

/** A connection keeps its own address: it is a record, not a state of the dashboard. */
export function toPath(location: Location): string {
  if (location.page === 'dashboard' && location.id) return `/connections/${location.id}`;
  return PATHS[location.page];
}

/** Anything unrecognised lands on the console's home rather than on a blank page. */
export function parsePath(pathname: string): Location {
  const segments = pathname.split('/').filter(Boolean);
  if (segments[0] === 'dashboard') return { page: 'dashboard' };
  // `/connections` was the home before the dashboard was; on its own it now names the section of one.
  if (segments[0] === 'connections') return { page: 'dashboard', id: segments[1] };
  if (segments[0] === 'activity') return { page: 'activity' };
  if (segments[0] === 'accounts') return { page: 'accounts' };
  if (segments[0] === 'documentation') {
    return { page: segments[1] === 'ai-coding' ? 'agents' : 'documentation' };
  }
  if (segments[0] === 'registry') {
    const page = segments[1];
    if (page === 'applications' || page === 'credentials') return { page };
    // `/registry/providers` and `/registry/grants` were pages of their own. A destination is now read
    // and edited on the connection that uses it, so an old bookmark lands on the list of those.
  }
  return { page: 'dashboard' };
}

/* ── Reading and writing the address ───────────────────────────────────── */

const listeners = new Set<() => void>();

function subscribe(notify: () => void) {
  listeners.add(notify);
  window.addEventListener('popstate', notify);
  return () => {
    listeners.delete(notify);
    window.removeEventListener('popstate', notify);
  };
}

/** Snapshot has to be referentially stable, so the raw pathname is the store and parsing happens after. */
const snapshot = () => window.location.pathname;

export function useLocation(): [Location, (to: Location, options?: { replace?: boolean }) => void] {
  const pathname = useSyncExternalStore(subscribe, snapshot, () => '/');

  const navigate = useCallback((to: Location, options?: { replace?: boolean }) => {
    const path = toPath(to);
    if (path === window.location.pathname) return;
    window.history[options?.replace ? 'replaceState' : 'pushState'](null, '', path);
    // pushState does not fire popstate; the store is told by hand.
    for (const notify of listeners) notify();
  }, []);

  return [parsePath(pathname), navigate];
}
