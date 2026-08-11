import { useCallback, useSyncExternalStore } from 'react';

/**
 * Layout that changes structure, not just size, needs the breakpoint in JS.
 *
 * The query is matched once per distinct string and the result kept, because `matchMedia` returns a
 * fresh object every call: built inside the render, it handed `useSyncExternalStore` a new
 * subscription on every render of every table, panel and skeleton on the page, so the browser tore
 * down and re-established the listener each time any of them redrew.
 */
const LISTS = new Map<string, MediaQueryList>();

function listFor(query: string): MediaQueryList | null {
  if (typeof window === 'undefined') return null;
  let list = LISTS.get(query);
  if (!list) {
    list = window.matchMedia(query);
    LISTS.set(query, list);
  }
  return list;
}

export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (notify: () => void) => {
      const list = listFor(query);
      list?.addEventListener('change', notify);
      return () => list?.removeEventListener('change', notify);
    },
    [query],
  );

  const snapshot = useCallback(() => listFor(query)?.matches ?? false, [query]);

  return useSyncExternalStore(subscribe, snapshot, () => false);
}

export const WIDE = '(min-width: 60rem)';
