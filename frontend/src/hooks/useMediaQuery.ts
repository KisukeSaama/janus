import { useSyncExternalStore } from 'react';

/** Layout that changes structure, not just size, needs the breakpoint in JS. */
export function useMediaQuery(query: string): boolean {
  const list = typeof window === 'undefined' ? null : window.matchMedia(query);
  return useSyncExternalStore(
    (notify) => {
      list?.addEventListener('change', notify);
      return () => list?.removeEventListener('change', notify);
    },
    () => list?.matches ?? false,
    () => false,
  );
}

export const WIDE = '(min-width: 60rem)';

/* ── Identity ──────────────────────────────────────────────────────────── */

/**
 * Two uprights of unequal weight: a threshold, half open.
 *
 * The second post starts lower than the first, so the pair reads as a step rather than a pause
 * button. The asymmetry is what survives at 16px, where an even gap would not.
 */
